// skein-2ige: end-to-end bring-up on a device — a real `UnlockManager`, the
// real `DeviceVaultOpener` (SQLCipher via libskein_sqlite.so, migrations,
// `VaultRepositoryImpl` & co.) under a throwaway directory, and the real
// `VaultDocumentsProvider` statics, then the provider's roots queried the
// way DocumentsUI would. Only the key provider is faked (a random 32-byte
// key held for the test's lifetime — never real material).
//
// Compiled by `compileFossDebugAndroidTestKotlin`; the on-device run is
// gated on the emulator lane tracked by bd `skein-k3b2`, like every other
// `*InstrumentedTest` in the repo.

package app.skein.vault

import android.content.Context
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.skein.MainActivity
import app.skein.core.vault.key.RewrapResult
import app.skein.core.vault.key.SetupResult
import app.skein.core.vault.key.UnlockResult
import app.skein.core.vault.key.VaultKeyProvider
import app.skein.core.vault.lifecycle.VaultPaths
import app.skein.core.vault.provider.VaultDocumentsProvider
import app.skein.core.vault.session.LockReason
import app.skein.core.vault.session.UnlockManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import us.aherrera.skein.core.model.AuthorizationToken
import java.io.File
import java.io.FileNotFoundException
import java.security.SecureRandom

@RunWith(AndroidJUnit4::class)
class VaultBootstrapInstrumentedTest {
    /** Test-only provider: a random in-memory key, unlock always succeeds. */
    private class RandomKeyVaultKeyProvider : VaultKeyProvider {
        @Volatile
        private var master: ByteArray? = null
        private var epoch = 0L

        override suspend fun setup(
            activity: FragmentActivity,
            prompt: BiometricPrompt.PromptInfo,
        ): SetupResult = SetupResult.Success(masterKeyVersion = 1, strongBoxBacked = false)

        override fun isInitialised(): Boolean = true

        override suspend fun unlock(
            activity: FragmentActivity,
            prompt: BiometricPrompt.PromptInfo,
            factor: VaultKeyProvider.Factor,
        ): UnlockResult {
            master = ByteArray(KEY_LENGTH).also(SecureRandom()::nextBytes)
            return UnlockResult.Success(AuthorizationToken(++epoch))
        }

        override fun currentKey(): ByteArray? = master

        override fun lock() {
            master?.fill(0)
            master = null
        }

        override suspend fun rewrapAfterInvalidation(
            activity: FragmentActivity,
            prompt: BiometricPrompt.PromptInfo,
            survivingFactor: VaultKeyProvider.Factor,
        ): RewrapResult = RewrapResult.Failed("not supported in tests")
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val vaultDir = File(context.cacheDir, "vault-bootstrap-test-${System.nanoTime()}")
    private val keyProvider = RandomKeyVaultKeyProvider()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val manager = UnlockManager(keyProvider = keyProvider, scope = null, installShutdownHook = false)
    private val opener =
        DeviceVaultOpener(
            keyProvider = keyProvider,
            paths = VaultPaths(vaultDir = vaultDir),
            attachmentsDir = File(vaultDir, VaultServices.ATTACHMENTS_DIR),
        )
    private val bootstrap =
        VaultBootstrap(
            unlockManager = manager,
            openVault = opener::open,
            provider = DocumentsProviderPort.forContext(context),
            scope = scope,
        )
    private val provider = VaultDocumentsProvider()
    private val prompt =
        BiometricPrompt.PromptInfo
            .Builder()
            .setTitle("Unlock")
            .setNegativeButtonText("Cancel")
            .build()

    @After
    fun tearDown() {
        runBlocking { manager.lockAndAwait(LockReason.SESSION_ENDED) }
        VaultDocumentsProvider.install(null)
        scope.cancel()
        vaultDir.deleteRecursively()
    }

    private fun unlockAndBringUp() {
        // `UnlockManager.unlock` needs a FragmentActivity host; the launcher
        // activity is one. The fake provider never shows a prompt.
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                runBlocking { manager.unlock(activity, prompt, VaultKeyProvider.Factor.BIOMETRIC) }
            }
        }
        val result = runBlocking { bootstrap.bringUp() }
        check(result is BringUpResult.Ready) { "bring-up did not complete: $result" }
    }

    /** Roots the provider serves right now; a fail-closed (uninstalled/locked) provider serves none. */
    private fun rootCount(): Int =
        try {
            provider.queryRoots(null).use { it.count }
        } catch (_: FileNotFoundException) {
            0
        }

    @Test
    fun unlock_then_bringUp_serves_the_vault_root() {
        unlockAndBringUp()

        assertEquals(1, rootCount())
    }

    @Test
    fun lock_after_bringUp_serves_no_roots() {
        unlockAndBringUp()

        runBlocking { manager.lockAndAwait(LockReason.USER_REQUESTED) }

        assertEquals(0, rootCount())
    }

    @Test
    fun a_second_unlock_reopens_the_same_vault_file() {
        unlockAndBringUp()
        runBlocking { manager.lockAndAwait(LockReason.USER_REQUESTED) }

        unlockAndBringUp()

        assertEquals(1, rootCount())
    }

    private companion object {
        const val KEY_LENGTH = 32
    }
}
