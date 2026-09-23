package app.skein.core.vault.db

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Host-JVM unit tests for [SkeinSQLiteDriver] using a fake
 * [SkeinSQLiteNative] — no libskein_sqlite.so involved. Verifies the
 * passphrase-zeroing contract, the extension probe ORDER, and the
 * exception mapping. The instrumented equivalent (real .so on a device)
 * lives at `src/androidTest/.../SkeinSQLiteDriverInstrumentedTest.kt`.
 */
class SkeinSQLiteDriverTest {
    @Test
    fun `passphrase byte array is zeroed after successful open`() {
        val fake =
            FakeSkeinSQLiteNative().apply {
                fakeCipherVersion = "4.17.0 community"
                fakeVecVersion = "v0.1.9"
            }
        val driver = SkeinSQLiteDriver(fake)
        val passphrase = "hunter2".toByteArray()
        val original = passphrase.copyOf()

        driver.openWithKey("db.file", passphrase).close()

        assertThat(passphrase.all { it == 0.toByte() }).isTrue()
        // Fake received the pre-zero bytes, not zeros.
        assertThat(fake.nativeKeyBytesSeen).isEqualTo(original.toList())
    }

    @Test
    fun `passphrase byte array is zeroed when open throws`() {
        val fake = FakeSkeinSQLiteNative().apply { throwOnKey = true }
        val driver = SkeinSQLiteDriver(fake)
        val passphrase = byteArrayOf(1, 2, 3, 4, 5)

        try {
            driver.openWithKey("db.file", passphrase)
            error("expected SkeinSQLiteException")
        } catch (_: SkeinSQLiteException) {
            // fall through
        }
        assertThat(passphrase.toList()).containsExactly(0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte())
    }

    @Test
    fun `extension probes run in cipher then vec then fts5 order`() {
        val fake =
            FakeSkeinSQLiteNative().apply {
                fakeCipherVersion = "4.17.0 community"
                fakeVecVersion = "v0.1.9"
            }
        val driver = SkeinSQLiteDriver(fake)

        driver.openWithKey("db.file", passphrase = null).close()

        val order = fake.prepareCalls.map { it.substringBefore('(').trim() } + fake.execCalls
        // "PRAGMA cipher_version" prepared first, then "SELECT vec_version",
        // then FTS5 CREATE / DROP via exec.
        assertThat(order[0]).contains("cipher_version")
        assertThat(order[1]).contains("vec_version")
        assertThat(order.any { it.contains("USING fts5") }).isTrue()
    }

    @Test
    fun `NOTADB from cipher probe maps to EncryptedDatabaseWithoutKeyException`() {
        val fake =
            FakeSkeinSQLiteNative().apply {
                cipherProbeError = SkeinSQLiteException(resultCode = 26, message = "file is not a database")
            }
        val driver = SkeinSQLiteDriver(fake)

        try {
            driver.openWithKey("encrypted.db", passphrase = null)
            error("expected EncryptedDatabaseWithoutKeyException")
        } catch (ex: EncryptedDatabaseWithoutKeyException) {
            assertThat(ex.message).contains("encrypted")
        }
    }

    @Test
    fun `handle is closed when verifyExtensions fails`() {
        val fake =
            FakeSkeinSQLiteNative().apply {
                fakeCipherVersion = "4.17.0 community"
                vecProbeReturnsRow = false
            }
        val driver = SkeinSQLiteDriver(fake)

        try {
            driver.openWithKey("db.file", passphrase = null)
            error("expected SkeinSQLiteException")
        } catch (_: SkeinSQLiteException) {
            // expected
        }
        assertThat(fake.closedHandles).contains(FakeSkeinSQLiteNative.DB_HANDLE)
    }

    // --- §4.9 connection setup (primary SkeinSQLiteDriver(key: ByteArray) constructor) ---

    @Test
    fun `open with key runs the §4_9 PRAGMA sequence in order and zeroes the constructor key`() {
        val fake =
            FakeSkeinSQLiteNative().apply {
                fakeCipherVersion = "4.17.0 community"
                fakeVecVersion = "v0.1.9"
            }
        val key = ByteArray(32) { it.toByte() }
        val expectedHex = key.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val driver = SkeinSQLiteDriver(fake, key)

        driver.open("vault.db").close()

        // Key array (same reference passed to the constructor) is zeroed.
        assertThat(key.all { it == 0.toByte() }).isTrue()

        val pragmaExecs = fake.execCalls.take(4)
        assertThat(pragmaExecs[0]).isEqualTo("PRAGMA key = \"x'$expectedHex'\";")
        assertThat(pragmaExecs[1]).isEqualTo("PRAGMA cipher_memory_security = ON;")
        assertThat(pragmaExecs[2]).isEqualTo("PRAGMA foreign_keys = ON;")
        assertThat(pragmaExecs[3]).isEqualTo("PRAGMA journal_mode = WAL;")
    }

    @Test
    fun `open with key never applies pragmas when no key was supplied`() {
        val fake =
            FakeSkeinSQLiteNative().apply {
                fakeCipherVersion = "4.17.0 community"
                fakeVecVersion = "v0.1.9"
            }
        val driver = SkeinSQLiteDriver(fake)

        driver.open("vault.db").close()

        assertThat(fake.execCalls.none { it.startsWith("PRAGMA key") }).isTrue()
        assertThat(fake.execCalls.none { it.contains("cipher_memory_security") }).isTrue()
    }

    // --- skein-gg11.10: PRAGMA foreign_keys / journal_mode must never be
    // gated on "was a key supplied" — see SkeinSQLiteDriver's class KDoc
    // and applyConnectionPragmas' KDoc for the on-device failure this
    // fixes (document_revisions never cascaded because the unkeyed
    // `:memory:` contract-test connection never ran `PRAGMA
    // foreign_keys = ON` at all). ---

    @Test
    fun `open with no key still applies PRAGMA foreign_keys = ON and journal_mode = WAL`() {
        val fake =
            FakeSkeinSQLiteNative().apply {
                fakeCipherVersion = "4.17.0 community"
                fakeVecVersion = "v0.1.9"
            }
        val driver = SkeinSQLiteDriver(fake)

        driver.open("vault.db").close()

        assertThat(fake.execCalls).contains("PRAGMA foreign_keys = ON;")
        assertThat(fake.execCalls).contains("PRAGMA journal_mode = WAL;")
    }

    @Test
    fun `openWithKey with a null passphrase still applies PRAGMA foreign_keys = ON and journal_mode = WAL`() {
        val fake =
            FakeSkeinSQLiteNative().apply {
                fakeCipherVersion = "4.17.0 community"
                fakeVecVersion = "v0.1.9"
            }
        val driver = SkeinSQLiteDriver(fake)

        driver.openWithKey("db.file", passphrase = null).close()

        // This is exactly the connection shape
        // `VaultRepositoryImplContractTest.repo()` opens (`openWithKey(":memory:",
        // passphrase = null)`) — before this fix, neither PRAGMA ran on it,
        // so `document_revisions`' `ON DELETE CASCADE` never fired on the
        // real driver.
        assertThat(fake.execCalls).contains("PRAGMA foreign_keys = ON;")
        assertThat(fake.execCalls).contains("PRAGMA journal_mode = WAL;")
    }

    @Test
    fun `openWithKey with a real passphrase still applies PRAGMA foreign_keys = ON and journal_mode = WAL`() {
        val fake =
            FakeSkeinSQLiteNative().apply {
                fakeCipherVersion = "4.17.0 community"
                fakeVecVersion = "v0.1.9"
            }
        val driver = SkeinSQLiteDriver(fake)

        driver.openWithKey("db.file", passphrase = "hunter2".toByteArray()).close()

        assertThat(fake.execCalls).contains("PRAGMA foreign_keys = ON;")
        assertThat(fake.execCalls).contains("PRAGMA journal_mode = WAL;")
    }

    @Test
    fun `PRAGMA journal_mode reports wal and PRAGMA foreign_keys reports 1 after keyed open`() {
        val fake =
            FakeSkeinSQLiteNative().apply {
                fakeCipherVersion = "4.17.0 community"
                fakeVecVersion = "v0.1.9"
            }
        val driver = SkeinSQLiteDriver(fake, ByteArray(32) { 0x11 })

        driver.open("vault.db").use { conn ->
            conn.prepare("PRAGMA journal_mode").use { stmt ->
                assertThat(stmt.step()).isTrue()
                assertThat(stmt.getText(0)).isEqualTo("wal")
            }
            conn.prepare("PRAGMA foreign_keys").use { stmt ->
                assertThat(stmt.step()).isTrue()
                assertThat(stmt.getLong(0)).isEqualTo(1L)
            }
        }
    }

    @Test
    fun `wrong key on an existing file surfaces the raw SkeinSQLiteException with not a database`() {
        val fake =
            FakeSkeinSQLiteNative().apply {
                cipherProbeError = SkeinSQLiteException(resultCode = 26, message = "file is not a database")
            }
        val driver = SkeinSQLiteDriver(fake, ByteArray(32) { 0x22 })

        try {
            driver.open("vault.db")
            error("expected SkeinSQLiteException")
        } catch (ex: SkeinSQLiteException) {
            assertThat(ex.message).contains("not a database")
        }
    }

    @Test
    fun `no key supplied still maps NOTADB to EncryptedDatabaseWithoutKeyException`() {
        val fake =
            FakeSkeinSQLiteNative().apply {
                cipherProbeError = SkeinSQLiteException(resultCode = 26, message = "file is not a database")
            }
        val driver = SkeinSQLiteDriver(fake)

        try {
            driver.open("vault.db")
            error("expected EncryptedDatabaseWithoutKeyException")
        } catch (ex: EncryptedDatabaseWithoutKeyException) {
            assertThat(ex.message).contains("encrypted")
        }
    }

    // --- prepared statement bind/get round trips (via SkeinSQLiteStatement directly) ---

    @Test
    fun `bindLong then getLong round trips`() {
        val fake = FakeSkeinSQLiteNative()
        val stmt = SkeinSQLiteStatement(fake, STMT_HANDLE)

        stmt.bindLong(1, 42L)

        assertThat(stmt.getLong(0)).isEqualTo(42L)
    }

    @Test
    fun `bindDouble then getDouble round trips`() {
        val fake = FakeSkeinSQLiteNative()
        val stmt = SkeinSQLiteStatement(fake, STMT_HANDLE)

        stmt.bindDouble(1, 3.14159)

        assertThat(stmt.getDouble(0)).isEqualTo(3.14159)
    }

    @Test
    fun `bindText then getText round trips`() {
        val fake = FakeSkeinSQLiteNative()
        val stmt = SkeinSQLiteStatement(fake, STMT_HANDLE)

        stmt.bindText(1, "hello skein")

        assertThat(stmt.getText(0)).isEqualTo("hello skein")
    }

    @Test
    fun `bindBlob then getBlob round trips`() {
        val fake = FakeSkeinSQLiteNative()
        val stmt = SkeinSQLiteStatement(fake, STMT_HANDLE)
        val blob = byteArrayOf(1, 2, 3, 4, 5)

        stmt.bindBlob(1, blob)

        assertThat(stmt.getBlob(0)).isEqualTo(blob)
    }

    @Test
    fun `bindNull then isNull round trips`() {
        val fake = FakeSkeinSQLiteNative()
        val stmt = SkeinSQLiteStatement(fake, STMT_HANDLE)

        stmt.bindNull(1)

        assertThat(stmt.isNull(0)).isTrue()
    }

    @Test
    fun `256-byte int8 vector round trips via bindBlob and getBlob`() {
        val fake = FakeSkeinSQLiteNative()
        val stmt = SkeinSQLiteStatement(fake, STMT_HANDLE)
        val vector = ByteArray(256) { (it - 128).toByte() }

        stmt.bindBlob(1, vector)
        val roundTripped = stmt.getBlob(0)

        assertThat(roundTripped.size).isEqualTo(256)
        assertThat(roundTripped).isEqualTo(vector)
    }

    private companion object {
        const val STMT_HANDLE = 500L
    }
}
