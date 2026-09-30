// skein-v3wb — Robolectric Compose coverage for the one place the
// destructive reset affordance may appear: [BiometricUnlockScreen]'s
// corrupt/unreadable-envelope state, and ONLY there. Mirrors
// `SkeinAppTest`'s Robolectric setup (`createAndroidComposeRule` +
// `RobolectricTestRunner`, sdk 34 — bd memory `robolectric-sdk37-needs-java21`)
// and the androidTest `BiometricUnlockScreenTest`'s `ScriptedVaultKeyProvider`
// fake, so `UnlockManager` drives a real state machine without a real
// biometric prompt.

package app.skein.feature.shell.auth

import androidx.biometric.BiometricPrompt
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.fragment.app.FragmentActivity
import app.skein.core.vault.key.RewrapResult
import app.skein.core.vault.key.SetupResult
import app.skein.core.vault.key.UnlockResult
import app.skein.core.vault.key.VaultKeyProvider
import app.skein.core.vault.session.UnlockManager
import app.skein.feature.shell.testing.ShellTestTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BiometricUnlockResetAffordanceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<FragmentActivity>()
    private var unlockCalls = 0

    private fun setContent(
        outcome: UnlockResult,
        onResetRequested: (() -> Unit)? = { },
        onKeyMaterialGoneResetRequested: ((VaultKeyProvider.Factor) -> Unit)? = null,
    ) {
        val manager =
            UnlockManager(
                keyProvider =
                    FakeVaultKeyProvider {
                        unlockCalls++
                        outcome
                    },
            )
        composeRule.setContent {
            MaterialTheme {
                BiometricUnlockScreen(
                    unlockManager = manager,
                    onUnlocked = {},
                    onRecoveryRequired = {},
                    onResetRequested = onResetRequested,
                    onKeyMaterialGoneResetRequested = onKeyMaterialGoneResetRequested,
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `a corrupt envelope offers the reset affordance when the host wires one`() {
        setContent(UnlockResult.Failed("key envelope corrupt"))

        composeRule.onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_RESET_BUTTON).assertIsDisplayed()
    }

    @Test
    fun `an unreadable envelope offers the reset affordance when the host wires one`() {
        setContent(UnlockResult.Failed("key envelope io failure"))

        composeRule.onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_RESET_BUTTON).assertIsDisplayed()
    }

    @Test
    fun `a corrupt envelope offers no reset affordance when the host does not wire one`() {
        setContent(UnlockResult.Failed("key envelope corrupt"), onResetRequested = null)

        composeRule.onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_RESET_BUTTON).assertDoesNotExist()
    }

    @Test
    fun `a generic failure never offers the reset affordance`() {
        setContent(UnlockResult.Failed("cipher init failed: KeyStoreException"))

        composeRule.onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_RESET_BUTTON).assertDoesNotExist()
    }

    @Test
    fun `user cancellation never offers the reset affordance`() {
        setContent(UnlockResult.UserCancelled)

        composeRule.onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_RESET_BUTTON).assertDoesNotExist()
    }

    @Test
    fun `tapping the reset affordance invokes onResetRequested`() {
        var requested = false
        setContent(UnlockResult.Failed("key envelope corrupt"), onResetRequested = { requested = true })

        composeRule.onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_RESET_BUTTON).performClick()

        org.junit.Assert.assertTrue(requested)
    }

    @Test
    fun `missing biometric key preserves the chance of credential recovery without retrying`() {
        setContent(UnlockResult.KeyMaterialGone(VaultKeyProvider.Factor.BIOMETRIC))

        composeRule
            .onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_MESSAGE)
            .assertTextContains("The device-credential key may still be available.", substring = true)
        composeRule.onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_RETRY_BUTTON).assertDoesNotExist()
        composeRule.onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_RESET_BUTTON).assertDoesNotExist()
        composeRule.waitForIdle()
        org.junit.Assert.assertEquals(1, unlockCalls)
    }

    @Test
    fun `missing credential key reset invokes only its typed callback after the click`() {
        var genericReset = false
        var requestedFactor: VaultKeyProvider.Factor? = null
        val factor = VaultKeyProvider.Factor.DEVICE_CREDENTIAL
        setContent(
            UnlockResult.KeyMaterialGone(factor),
            onResetRequested = { genericReset = true },
            onKeyMaterialGoneResetRequested = { requestedFactor = it },
        )

        composeRule
            .onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_MESSAGE)
            .assertTextContains("The fingerprint or face key may still be available.", substring = true)
        org.junit.Assert.assertNull(requestedFactor)
        composeRule.onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_RETRY_BUTTON).assertDoesNotExist()
        composeRule.onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_RESET_BUTTON).performClick()

        org.junit.Assert.assertEquals(factor, requestedFactor)
        org.junit.Assert.assertFalse(genericReset)
        org.junit.Assert.assertEquals(1, unlockCalls)
    }

    @Test
    fun `generic failure cannot enter typed key-loss reset even when host offers it`() {
        var requested = false
        setContent(
            UnlockResult.Failed("cipher init failed: KeyStoreException"),
            onKeyMaterialGoneResetRequested = { requested = true },
        )

        composeRule.onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_RESET_BUTTON).assertDoesNotExist()
        org.junit.Assert.assertFalse(requested)
    }
}

/** Fakes the [VaultKeyProvider] seam directly, matching the androidTest `ScriptedVaultKeyProvider`'s shape. */
private class FakeVaultKeyProvider(
    private val onUnlock: suspend () -> UnlockResult,
) : VaultKeyProvider {
    override suspend fun setup(
        activity: FragmentActivity,
        prompt: BiometricPrompt.PromptInfo,
    ): SetupResult = error("BiometricUnlockScreen does not call VaultKeyProvider.setup")

    override suspend fun setup(
        activity: FragmentActivity,
        prompt: BiometricPrompt.PromptInfo,
        existingMaster: ByteArray,
    ): SetupResult = error("BiometricUnlockScreen does not call VaultKeyProvider.setup(existingMaster)")

    override fun isInitialised(): Boolean = error("BiometricUnlockScreen does not probe VaultKeyProvider.isInitialised")

    override suspend fun unlock(
        activity: FragmentActivity,
        prompt: BiometricPrompt.PromptInfo,
        factor: VaultKeyProvider.Factor,
    ): UnlockResult = onUnlock()

    override fun currentKey(): ByteArray? = null

    override fun lock() {
        // No-op: no key material is ever created by this fake.
    }

    override suspend fun rewrapAfterInvalidation(
        activity: FragmentActivity,
        prompt: BiometricPrompt.PromptInfo,
        survivingFactor: VaultKeyProvider.Factor,
    ): RewrapResult = error("BiometricUnlockScreen does not call VaultKeyProvider.rewrapAfterInvalidation")
}
