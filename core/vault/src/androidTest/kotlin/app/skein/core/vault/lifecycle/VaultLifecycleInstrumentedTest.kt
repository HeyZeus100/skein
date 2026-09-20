// On-device tests for [VaultLifecycle] against the real libskein_sqlite.so
// + libskein_sqlite_jni.so (`E2.I13` / skein-yrp), exercising real
// SQLCipher encryption, real WAL checkpointing, and `PRAGMA
// integrity_check` — none of which the JVM-fake-backed `VaultLifecycleTest`
// can meaningfully cover (see that file's class KDoc).
//
// Follow-up: skein-k3b2 tracks provisioning an API 35 emulator inside CI so
// `connectedFossDebugAndroidTest` can run headless; no device/emulator was
// available to actually execute this suite in the skein-yrp worktree (same
// situation as `MigratorInstrumentedTest` / `VaultKeyProviderInstrumentedTest`).
// This class is compiled in the ordinary Gradle `check` (which does not run
// instrumented tests) so a broken statement surfaces as a compile-time
// error even without the device queue.

package app.skein.core.vault.lifecycle

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.skein.core.vault.db.SkeinSQLiteDriver
import app.skein.core.vault.db.migrations.Migrator
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VaultLifecycleInstrumentedTest {
    private val tempDirs = mutableListOf<File>()

    @After
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    /** Deterministic per-test key: same [seed] always yields the same 32 bytes. */
    private fun randomKey(seed: Byte): ByteArray = ByteArray(32) { (seed + it).toByte() }

    private fun newVaultDir(): File {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.cacheDir, "vault-lifecycle-test-${System.nanoTime()}")
        dir.mkdirs()
        tempDirs += dir
        return dir
    }

    private fun newLifecycle(dir: File = newVaultDir()): VaultLifecycle =
        VaultLifecycle(
            driverFactory = { key -> SkeinSQLiteDriver(key) },
            migrator = { driver -> Migrator(driver) },
            paths = VaultPaths(vaultDir = dir),
        )

    @Test
    fun createThenOpenThenCloseThenOpenThenCloseRoundTrip() =
        runBlocking {
            val lifecycle = newLifecycle()

            val createResult = lifecycle.create(randomKey(1))
            assertThat(createResult).isInstanceOf(CreateResult.Success::class.java)
            assertThat(lifecycle.isOpen.value).isTrue()

            lifecycle.close()
            assertThat(lifecycle.isOpen.value).isFalse()

            val openResult = lifecycle.open(randomKey(1))
            assertThat(openResult).isInstanceOf(OpenResult.Success::class.java)
            assertThat((openResult as OpenResult.Success).migration.toVersion).isEqualTo(1)
            assertThat(lifecycle.isOpen.value).isTrue()

            lifecycle.close()
            assertThat(lifecycle.isOpen.value).isFalse()
        }

    @Test
    fun createTwiceFailsWithAlreadyExists() =
        runBlocking {
            val lifecycle = newLifecycle()
            lifecycle.create(randomKey(2))

            val second = lifecycle.create(randomKey(2))

            assertThat(second).isEqualTo(CreateResult.AlreadyExists)
        }

    @Test
    fun openNonexistentVaultFailsWithNotFound() =
        runBlocking {
            val lifecycle = newLifecycle()

            val result = lifecycle.open(randomKey(3))

            assertThat(result).isEqualTo(OpenResult.NotFound)
        }

    @Test
    fun openWithWrongKeyReturnsTypedWrongKey() =
        runBlocking {
            val lifecycle = newLifecycle()
            lifecycle.create(randomKey(4))
            lifecycle.close()

            val result = lifecycle.open(randomKey(40)) // different key material

            assertThat(result).isEqualTo(OpenResult.WrongKey)
            assertThat(lifecycle.isOpen.value).isFalse()
        }

    @Test
    fun closeLeavesNoWalOrShmResidue() =
        runBlocking {
            val dir = newVaultDir()
            val paths = VaultPaths(vaultDir = dir)
            val lifecycle = newLifecycle(dir)
            lifecycle.create(randomKey(5))

            lifecycle.close()

            val walFile = File(paths.databaseFile.path + "-wal")
            val shmFile = File(paths.databaseFile.path + "-shm")
            assertThat(walFile.exists() && walFile.length() > 0).isFalse()
            assertThat(shmFile.exists() && shmFile.length() > 0).isFalse()
        }

    @Test
    fun integrityCheckOnAFreshlyCreatedVaultReportsOk() =
        runBlocking {
            val lifecycle = newLifecycle()
            lifecycle.create(randomKey(6))

            val result = lifecycle.integrityCheck()

            assertThat(result).isEqualTo(IntegrityResult.Ok)
        }

    @Test
    fun integrityCheckWhileNotOpenReportsNotOpen() =
        runBlocking {
            val lifecycle = newLifecycle()

            val result = lifecycle.integrityCheck()

            assertThat(result).isEqualTo(IntegrityResult.NotOpen)
        }
}
