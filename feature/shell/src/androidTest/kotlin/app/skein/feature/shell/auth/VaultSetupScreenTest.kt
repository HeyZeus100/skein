package app.skein.feature.shell.auth

import androidx.biometric.BiometricPrompt
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.fragment.app.FragmentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.skein.core.vault.key.RewrapResult
import app.skein.core.vault.key.SetupResult
import app.skein.core.vault.key.UnlockResult
import app.skein.core.vault.key.VaultKeyProvider
import app.skein.feature.shell.testing.ShellTestTags
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device Compose UI test for [VaultSetupScreen] (bd `skein-ank2`),
 * mirroring [BiometricUnlockScreenTest]'s shape: a real `FragmentActivity`
 * host, the `VaultKeyProvider` seam faked so every [SetupResult] branch is
 * scriptable without a Keystore or biometric hardware. Compiled here; the
 * on-device run is gated on the CI emulator lane tracked by bd `skein-k3b2`.
 */
@RunWith(AndroidJUnit4::class)
class VaultSetupScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<FragmentActivity>()

    private fun show(
        provider: VaultKeyProvider,
        onProvisioned: (Boolean) -> Unit = {},
        onAlreadyInitialised: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                VaultSetupScreen(
                    keyProvider = provider,
                    onProvisioned = onProvisioned,
                    onAlreadyInitialised = onAlreadyInitialised,
                )
            }
        }
        composeRule.waitForIdle()
    }

    // skein-v9g (E3.I11): the recovery / device-migration entry point.

    @Test
    fun offers_restore_from_a_passphrase_export_alongside_a_fresh_setup() {
        val provider = ScriptedSetupProvider { SetupResult.UserCancelled }

        show(provider)

        composeRule.onNodeWithTag(ShellTestTags.VAULT_RESTORE_BUTTON).assertIsDisplayed()
    }

    @Test
    fun the_restore_entry_point_does_not_mint_a_fresh_master() {
        val provider = ScriptedSetupProvider { SetupResult.UserCancelled }
        show(provider)

        composeRule.onNodeWithTag(ShellTestTags.VAULT_RESTORE_BUTTON).performClick()
        composeRule.waitForIdle()

        // Opening the document picker must never reach `setup()`.
        assertEquals(0, provider.setupCalls)
    }

    @Test
    fun explains_and_waits_for_a_tap_before_calling_setup() {
        val provider = ScriptedSetupProvider { SetupResult.UserCancelled }

        show(provider)

        composeRule.onNodeWithTag(ShellTestTags.VAULT_SETUP_BEGIN_BUTTON).assertIsDisplayed()
        assertEquals(0, provider.setupCalls)
    }

    @Test
    fun tapping_begin_runs_setup_once_and_shows_progress() {
        val neverCompletes = CompletableDeferred<SetupResult>()
        val provider = ScriptedSetupProvider { neverCompletes.await() }
        show(provider)

        composeRule.onNodeWithTag(ShellTestTags.VAULT_SETUP_BEGIN_BUTTON).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(ShellTestTags.VAULT_SETUP_PROGRESS).assertIsDisplayed()
        assertEquals(1, provider.setupCalls)
    }

    @Test
    fun success_reports_provisioned_with_the_strongbox_flag() {
        var provisioned: Boolean? = null
        show(
            ScriptedSetupProvider { SetupResult.Success(masterKeyVersion = 1, strongBoxBacked = true) },
            onProvisioned = { provisioned = it },
        )

        composeRule.onNodeWithTag(ShellTestTags.VAULT_SETUP_BEGIN_BUTTON).performClick()
        composeRule.waitForIdle()

        assertEquals(true, provisioned)
    }

    @Test
    fun strongbox_fallback_reports_provisioned_without_strongbox() {
        var provisioned: Boolean? = null
        show(
            ScriptedSetupProvider { SetupResult.StrongBoxUnavailableFallback(masterKeyVersion = 1) },
            onProvisioned = { provisioned = it },
        )

        composeRule.onNodeWithTag(ShellTestTags.VAULT_SETUP_BEGIN_BUTTON).performClick()
        composeRule.waitForIdle()

        assertEquals(false, provisioned)
    }

    @Test
    fun already_initialised_routes_to_unlock() {
        var routed = false
        show(ScriptedSetupProvider { SetupResult.AlreadyInitialised }, onAlreadyInitialised = { routed = true })

        composeRule.onNodeWithTag(ShellTestTags.VAULT_SETUP_BEGIN_BUTTON).performClick()
        composeRule.waitForIdle()

        assertTrue(routed)
    }

    @Test
    fun cancellation_shows_retry() {
        show(ScriptedSetupProvider { SetupResult.UserCancelled })

        composeRule.onNodeWithTag(ShellTestTags.VAULT_SETUP_BEGIN_BUTTON).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(ShellTestTags.VAULT_SETUP_RETRY_BUTTON).assertIsDisplayed()
    }

    @Test
    fun no_enrolled_biometric_offers_enrolment_and_retry() {
        show(ScriptedSetupProvider { SetupResult.NoBiometricEnrolled })

        composeRule.onNodeWithTag(ShellTestTags.VAULT_SETUP_BEGIN_BUTTON).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(ShellTestTags.VAULT_SETUP_ENROL_BUTTON).assertIsDisplayed()
        composeRule.onNodeWithTag(ShellTestTags.VAULT_SETUP_RETRY_BUTTON).assertIsDisplayed()
    }

    @Test
    fun corrupt_envelope_routes_to_unlock_instead_of_offering_setup_again() {
        var routed = false
        show(
            ScriptedSetupProvider { SetupResult.Failed("key envelope corrupt") },
            onAlreadyInitialised = { routed = true },
        )

        composeRule.onNodeWithTag(ShellTestTags.VAULT_SETUP_BEGIN_BUTTON).performClick()
        composeRule.waitForIdle()

        assertTrue(routed)
        composeRule.onNodeWithTag(ShellTestTags.VAULT_SETUP_RETRY_BUTTON).assertDoesNotExist()
    }
}

/** Fakes the `VaultKeyProvider` seam so [VaultSetupScreen]'s only call, [setup], is scriptable. */
private class ScriptedSetupProvider(
    private val onSetup: suspend () -> SetupResult,
) : VaultKeyProvider {
    @Volatile
    var setupCalls: Int = 0

    override suspend fun setup(
        activity: FragmentActivity,
        prompt: BiometricPrompt.PromptInfo,
    ): SetupResult {
        setupCalls++
        return onSetup()
    }

    /** skein-v9g: the recovery / device-migration overload. Records the adopted bytes. */
    override suspend fun setup(
        activity: FragmentActivity,
        prompt: BiometricPrompt.PromptInfo,
        existingMaster: ByteArray,
    ): SetupResult {
        importedMaster = existingMaster.copyOf()
        setupCalls++
        return onSetup()
    }

    /** The master the last restore adopted, or `null` if none has run. */
    @Volatile
    var importedMaster: ByteArray? = null
        private set

    override fun isInitialised(): Boolean = false

    override suspend fun unlock(
        activity: FragmentActivity,
        prompt: BiometricPrompt.PromptInfo,
        factor: VaultKeyProvider.Factor,
    ): UnlockResult = error("VaultSetupScreen does not call VaultKeyProvider.unlock")

    override fun currentKey(): ByteArray? = null

    override fun lock() {
        // No-op: no key material is ever created by this fake.
    }

    override suspend fun rewrapAfterInvalidation(
        activity: FragmentActivity,
        prompt: BiometricPrompt.PromptInfo,
        survivingFactor: VaultKeyProvider.Factor,
    ): RewrapResult = error("VaultSetupScreen does not call VaultKeyProvider.rewrapAfterInvalidation")
}
