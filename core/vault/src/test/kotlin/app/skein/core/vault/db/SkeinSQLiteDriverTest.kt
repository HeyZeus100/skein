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
}
