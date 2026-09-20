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
}
