package app.skein.core.vault.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device tests for [SkeinSQLiteDriver] — real libskein_sqlite.so +
 * libskein_sqlite_jni.so. Skipped on host JVM (no `.so`).
 *
 * Follow-up: skein-k3b2 tracks provisioning an API 35 emulator inside CI
 * so `connectedFossDebugAndroidTest` can run headless. Until then these
 * are opt-in.
 */
@RunWith(AndroidJUnit4::class)
class SkeinSQLiteDriverInstrumentedTest {
    private val tempFiles = mutableListOf<File>()

    @After
    fun cleanup() {
        tempFiles.forEach { it.delete() }
    }

    private fun tempDbFile(): File {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val f = File.createTempFile("skein-test-", ".db", ctx.cacheDir)
        tempFiles += f
        // createTempFile leaves an empty file; SQLite is happy to open+init.
        f.delete()
        return f
    }

    @Test
    fun cipherVersionMatchesPinnedSqlcipher() {
        val driver = SkeinSQLiteDriver()
        driver.openWithKey(":memory:", passphrase = null).use { conn ->
            conn.prepare("PRAGMA cipher_version").use { stmt ->
                assertThat(stmt.step()).isTrue()
                // Pinned in native/sqlite/CMakeLists.txt §0.
                assertThat(stmt.getText(0)).contains("4.17.0")
            }
        }
    }

    @Test
    fun vecVersionMatchesPinned() {
        val driver = SkeinSQLiteDriver()
        driver.openWithKey(":memory:", passphrase = null).use { conn ->
            conn.prepare("SELECT vec_version()").use { stmt ->
                assertThat(stmt.step()).isTrue()
                assertThat(stmt.getText(0)).isEqualTo("v0.1.9")
            }
        }
    }

    @Test
    fun fts5VirtualTableProbe() {
        // If verifyExtensions passes at open time, FTS5 is confirmed;
        // this test asserts the same probe runs to completion on the
        // real driver.
        SkeinSQLiteDriver().openWithKey(":memory:", passphrase = null).use { /* no-op */ }
    }

    @Test
    fun passphraseRoundTrip() {
        val dbFile = tempDbFile()
        val keyA = "correct horse battery staple".toByteArray()
        val keyACopy = keyA.copyOf()

        SkeinSQLiteDriver().openWithKey(dbFile.absolutePath, keyA).use { conn ->
            (conn as SkeinSQLiteConnection).exec("CREATE TABLE t(x TEXT)")
            conn.prepare("INSERT INTO t VALUES(?)").use { stmt ->
                stmt.bindText(1, "hello")
                assertThat(stmt.step()).isFalse() // DONE, no row
            }
        }
        // Passphrase was zeroed by openWithKey.
        assertThat(keyA.all { it == 0.toByte() }).isTrue()

        // Wrong key: cipher_version probe should throw.
        val wrong = "wrong".toByteArray()
        try {
            SkeinSQLiteDriver().openWithKey(dbFile.absolutePath, wrong).close()
            error("expected an exception from wrong key")
        } catch (_: RuntimeException) {
            // expected — either SkeinSQLiteException or the
            // EncryptedDatabaseWithoutKeyException mapping.
        }

        // Correct key: roundtrip.
        SkeinSQLiteDriver().openWithKey(dbFile.absolutePath, keyACopy).use { conn ->
            conn.prepare("SELECT x FROM t").use { stmt ->
                assertThat(stmt.step()).isTrue()
                assertThat(stmt.getText(0)).isEqualTo("hello")
            }
        }
    }

    // --- E2.I1 remaining scope: primary SkeinSQLiteDriver(key: ByteArray) constructor,
    // §4.9 PRAGMA sequence, and additional bind/get round trips (real SQLCipher engine). ---

    private fun randomKey(seed: Byte): ByteArray = ByteArray(32) { (seed + it).toByte() }

    @Test
    fun keyedOpenRunsSection4_9PragmaSequence() {
        val dbFile = tempDbFile()
        val key = randomKey(1)

        SkeinSQLiteDriver(key).open(dbFile.absolutePath).use { conn ->
            conn.prepare("PRAGMA journal_mode").use { stmt ->
                assertThat(stmt.step()).isTrue()
                assertThat(stmt.getText(0)).isEqualTo("wal")
            }
            conn.prepare("PRAGMA foreign_keys").use { stmt ->
                assertThat(stmt.step()).isTrue()
                assertThat(stmt.getLong(0)).isEqualTo(1L)
            }
        }

        // The constructor's key array was zeroed by open().
        assertThat(key.all { it == 0.toByte() }).isTrue()
    }

    @Test
    fun openingWithWrongKeyThrowsNotADatabase() {
        val dbFile = tempDbFile()
        val correctKey = randomKey(2)

        SkeinSQLiteDriver(correctKey).open(dbFile.absolutePath).use { conn ->
            (conn as SkeinSQLiteConnection).exec("CREATE TABLE t(x TEXT)")
        }

        val wrongKey = randomKey(9)
        try {
            SkeinSQLiteDriver(wrongKey).open(dbFile.absolutePath).close()
            error("expected an exception from the wrong key")
        } catch (ex: RuntimeException) {
            // A key WAS supplied (just the wrong one), so the raw
            // SkeinSQLiteException (not EncryptedDatabaseWithoutKeyException)
            // should propagate, carrying SQLite's own diagnosis.
            assertThat(ex).isInstanceOf(SkeinSQLiteException::class.java)
            assertThat(ex.message).contains("not a database")
        }
    }

    @Test
    fun bindAndGetRoundTripsForAllSupportedTypes() {
        val dbFile = tempDbFile()
        val key = randomKey(3)

        SkeinSQLiteDriver(key).open(dbFile.absolutePath).use { conn ->
            val connection = conn as SkeinSQLiteConnection
            connection.exec(
                "CREATE TABLE round_trip(i INTEGER, d REAL, t TEXT, b BLOB, n TEXT)",
            )
            connection.prepare("INSERT INTO round_trip VALUES(?, ?, ?, ?, ?)").use { insert ->
                insert.bindLong(1, 123456789L)
                insert.bindDouble(2, 2.71828)
                insert.bindText(3, "skein vault")
                insert.bindBlob(4, byteArrayOf(9, 8, 7, 6, 5))
                insert.bindNull(5)
                assertThat(insert.step()).isFalse() // DONE, no row
            }
            connection.prepare("SELECT i, d, t, b, n FROM round_trip").use { select ->
                assertThat(select.step()).isTrue()
                assertThat(select.getLong(0)).isEqualTo(123456789L)
                assertThat(select.getDouble(1)).isEqualTo(2.71828)
                assertThat(select.getText(2)).isEqualTo("skein vault")
                assertThat(select.getBlob(3)).isEqualTo(byteArrayOf(9, 8, 7, 6, 5))
                assertThat(select.isNull(4)).isTrue()
            }
        }
    }

    @Test
    fun getBlobRoundTrips256ByteInt8Vector() {
        val dbFile = tempDbFile()
        val key = randomKey(4)
        val vector = ByteArray(256) { (it - 128).toByte() }

        SkeinSQLiteDriver(key).open(dbFile.absolutePath).use { conn ->
            val connection = conn as SkeinSQLiteConnection
            connection.exec("CREATE VIRTUAL TABLE vec_round_trip USING vec0(embedding int8[256])")
            // An int8-declared vec0 column needs the `vec_int8(?)`
            // constructor around the bound parameter (skein-2hzi) — a bare
            // 256-byte BLOB is read as 64 float32s by sqlite-vec and the
            // column rejects it ("expected int8, but a float32 vector was
            // provided"). `IndexSql.UPSERT_EMBEDDING` already does this in
            // production; this test binds the same way.
            connection.prepare("INSERT INTO vec_round_trip(rowid, embedding) VALUES(1, vec_int8(?))").use { insert ->
                insert.bindBlob(1, vector)
                assertThat(insert.step()).isFalse() // DONE, no row
            }
            connection.prepare("SELECT embedding FROM vec_round_trip WHERE rowid = 1").use { select ->
                assertThat(select.step()).isTrue()
                val roundTripped = select.getBlob(0)
                assertThat(roundTripped.size).isEqualTo(256)
                assertThat(roundTripped).isEqualTo(vector)
            }
        }
    }
}
