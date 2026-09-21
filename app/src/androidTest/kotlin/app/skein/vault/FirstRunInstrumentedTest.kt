// skein-ank2: the first-run acceptance criteria on a device — "fresh
// install: setup, then unlock → bringUp → DocumentsProvider roots
// non-empty; second launch: unlock without setup" — over the real
// `UnlockManager`, the real `DeviceVaultOpener` (SQLCipher via
// libskein_sqlite.so, migrations, the SQL services) under a throwaway
// directory, the production first-persona seed, and the real
// `VaultDocumentsProvider` statics, with the gate's routing asserted
// through the same `gatePhase` `MainActivity` renders.
//
// Only the key provider is faked: the real one cannot complete `setup()` or
// `unlock()` without a driven `BiometricPrompt` (per-use-auth Keystore
// keys, `ATTACHMENT_ENCRYPTION.md` §2.3), so that end-to-end run belongs to
// the skein-k3b2 lane's scripted-biometric emulator. The fake models the
// one thing the gate depends on — whether an envelope exists — and never
// holds real material (a random 32-byte key for the test's lifetime).
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
import app.skein.core.vault.session.UnlockState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import us.aherrera.skein.core.model.AuthorizationToken
import java.io.File
import java.io.FileNotFoundException
import java.security.SecureRandom

@RunWith(AndroidJUnit4::class)
class FirstRunInstrumentedTest {
    /**
     * Models the key envelope: absent until [setup] succeeds, after which
     * [unlock] hands out a random in-memory key; before, `NotInitialised`,
     * exactly as the real provider reports it.
     */
    private class EnvelopeModellingKeyProvider : VaultKeyProvider {
        @Volatile
        var initialised: Boolean = false

        @Volatile
        var setupCalls: Int = 0

        @Volatile
        private var master: ByteArray? = null
        private var epoch = 0L

        override suspend fun setup(
            activity: FragmentActivity,
            prompt: BiometricPrompt.PromptInfo,
        ): SetupResult {
            setupCalls++
            if (initialised) return SetupResult.AlreadyInitialised
            initialised = true
            return SetupResult.Success(masterKeyVersion = 1, strongBoxBacked = false)
        }

        override fun isInitialised(): Boolean = initialised

        override suspend fun unlock(
            activity: FragmentActivity,
            prompt: BiometricPrompt.PromptInfo,
            factor: VaultKeyProvider.Factor,
        ): UnlockResult {
            if (!initialised) return UnlockResult.NotInitialised
            master = master ?: ByteArray(KEY_LENGTH).also(SecureRandom()::nextBytes)
            return UnlockResult.Success(AuthorizationToken(++epoch))
        }

        override fun currentKey(): ByteArray? = master

        override fun lock() {
            // The same key must reopen the same file across "launches", so
            // only the in-memory copy handed to callers is dropped here;
            // the fake's own copy lives for the test. Never real material.
        }

        override suspend fun rewrapAfterInvalidation(
            activity: FragmentActivity,
            prompt: BiometricPrompt.PromptInfo,
            survivingFactor: VaultKeyProvider.Factor,
        ): RewrapResult = RewrapResult.Failed("not supported in tests")
    }

    /** One process lifetime's vault stack over [vaultDir] — a fresh one per "launch". */
    private inner class Launch {
        val manager = UnlockManager(keyProvider = keyProvider, scope = null, installShutdownHook = false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val opener =
            DeviceVaultOpener(
                keyProvider = keyProvider,
                paths = VaultPaths(vaultDir = vaultDir),
                attachmentsDir = File(vaultDir, VaultServices.ATTACHMENTS_DIR),
            )
        val bootstrap =
            VaultBootstrap(
                unlockManager = manager,
                openVault = opener::open,
                provider = DocumentsProviderPort.forContext(context),
                scope = scope,
                seed = VaultServices::seedFirstPersona,
            )

        /** What `MainActivity`'s gate would show right now, given the probe result. */
        fun phase(): GatePhase =
            gatePhase(
                session = bootstrap.session.value,
                unlockState = manager.state.value,
                recoveryRequired = false,
                provisioned = keyProvider.isInitialised(),
            )

        fun setUp() {
            withActivity { activity -> runBlocking { keyProvider.setup(activity, prompt) } }
        }

        fun unlockAndBringUp(): BringUpResult {
            withActivity { activity ->
                runBlocking { manager.unlock(activity, prompt, VaultKeyProvider.Factor.BIOMETRIC) }
            }
            return runBlocking { bootstrap.bringUp() }
        }

        fun end() {
            runBlocking { manager.lockAndAwait(LockReason.SESSION_ENDED) }
            scope.cancel()
        }

        private fun withActivity(block: (FragmentActivity) -> Unit) {
            // `setup` / `unlock` need a FragmentActivity host; the launcher
            // activity is one. The fake provider never shows a prompt.
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity(block)
            }
        }
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val vaultDir = File(context.cacheDir, "first-run-test-${System.nanoTime()}")
    private val keyProvider = EnvelopeModellingKeyProvider()
    private val provider = VaultDocumentsProvider()
    private val prompt =
        BiometricPrompt.PromptInfo
            .Builder()
            .setTitle("Set up")
            .setNegativeButtonText("Cancel")
            .build()
    private val launches = mutableListOf<Launch>()

    private fun launch(): Launch = Launch().also(launches::add)

    @After
    fun tearDown() {
        launches.forEach { runCatching { it.end() } }
        VaultDocumentsProvider.install(null)
        vaultDir.deleteRecursively()
    }

    /** Roots the provider serves right now; a fail-closed (uninstalled/locked) provider serves none. */
    private fun rootCount(): Int =
        try {
            provider.queryRoots(null).use { it.count }
        } catch (_: FileNotFoundException) {
            0
        }

    @Test
    fun fresh_install_routes_to_setup_then_unlock_then_serves_the_root() {
        val first = launch()
        assertEquals(GatePhase.Setup, first.phase())

        first.setUp()
        assertEquals(GatePhase.Unlock, first.phase())
        val result = first.unlockAndBringUp()

        assertTrue("bring-up did not complete: $result", result is BringUpResult.Ready)
        assertEquals(1, rootCount())
        assertTrue(first.manager.state.value is UnlockState.Unlocked)
    }

    @Test
    fun fresh_install_seeds_the_first_persona() {
        val first = launch()
        first.setUp()

        val result = first.unlockAndBringUp() as BringUpResult.Ready

        val personaService = result.session.personaService
        val personas = runBlocking { personaService.observeAll().first() }
        assertFalse("expected the first persona after the first open", personas.isEmpty())
    }

    @Test
    fun second_launch_unlocks_without_setup() {
        val first = launch()
        first.setUp()
        first.unlockAndBringUp()
        first.end()
        assertEquals(0, rootCount())

        val second = launch()
        assertEquals(GatePhase.Unlock, second.phase())
        val result = second.unlockAndBringUp()

        assertTrue("second bring-up did not complete: $result", result is BringUpResult.Ready)
        assertEquals(1, keyProvider.setupCalls)
        assertEquals(1, rootCount())
    }

    @Test
    fun a_second_setup_is_refused_and_the_vault_still_opens() {
        val first = launch()
        first.setUp()
        first.unlockAndBringUp()
        first.end()

        val second = launch()
        second.setUp()
        val result = second.unlockAndBringUp()

        assertTrue("bring-up after a refused setup did not complete: $result", result is BringUpResult.Ready)
    }

    private companion object {
        const val KEY_LENGTH = 32
    }
}
