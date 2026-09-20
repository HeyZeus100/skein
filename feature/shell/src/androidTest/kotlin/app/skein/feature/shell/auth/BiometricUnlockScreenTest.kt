package app.skein.feature.shell.auth

import androidx.biometric.BiometricPrompt
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.fragment.app.FragmentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.skein.core.vault.key.RewrapResult
import app.skein.core.vault.key.SetupResult
import app.skein.core.vault.key.UnlockResult
import app.skein.core.vault.key.VaultKeyProvider
import app.skein.core.vault.session.UnlockManager
import app.skein.feature.shell.testing.ShellTestTags
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import us.aherrera.skein.core.model.AuthorizationToken

/**
 * On-device Compose UI test for [BiometricUnlockScreen] (bd `skein-ugo`,
 * plan `E3.I4`). Runs on an emulator/device — no Robolectric — matching
 * `:feature:editor`'s `SkeinEditorInstrumentedTest` shape. Compiled here;
 * the on-device run is gated on the CI emulator lane tracked by bd
 * `skein-k3b2`.
 *
 * Drives [BiometricUnlockScreen] through a [ScriptedVaultKeyProvider] fed
 * straight to a real [UnlockManager] — the real `BiometricPrompt` /
 * `CryptoObject` plumbing lives in `AndroidBiometricAuthenticator`
 * (`:core:vault`, `skein-3el`) below `VaultKeyProvider`, which is exactly
 * the seam these tests fake out, so no actual biometric hardware/enrolment
 * is needed to exercise every [app.skein.core.vault.session.UnlockOutcome]
 * branch this screen handles.
 */
@RunWith(AndroidJUnit4::class)
class BiometricUnlockScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<FragmentActivity>()

    @Test
    fun presents_prompt_on_first_render() {
        // Never resolves during this test — the point is to observe the
        // screen mid-flight, i.e. the prompt/unwrap in progress after the
        // screen auto-presents on first composition.
        val neverCompletes = CompletableDeferred<UnlockResult>()
        val manager = UnlockManager(keyProvider = ScriptedVaultKeyProvider { neverCompletes.await() })

        composeRule.setContent {
            MaterialTheme {
                BiometricUnlockScreen(
                    unlockManager = manager,
                    onUnlocked = {},
                    onRecoveryRequired = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_PROGRESS).assertIsDisplayed()
    }

    @Test
    fun cancellation_shows_retry() {
        val manager = UnlockManager(keyProvider = ScriptedVaultKeyProvider { UnlockResult.UserCancelled })

        composeRule.setContent {
            MaterialTheme {
                BiometricUnlockScreen(
                    unlockManager = manager,
                    onUnlocked = {},
                    onRecoveryRequired = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_RETRY_BUTTON).assertIsDisplayed()
    }

    @Test
    fun error_shows_message() {
        val manager =
            UnlockManager(
                keyProvider = ScriptedVaultKeyProvider { UnlockResult.Failed("cipher init failed") },
            )

        composeRule.setContent {
            MaterialTheme {
                BiometricUnlockScreen(
                    unlockManager = manager,
                    onUnlocked = {},
                    onRecoveryRequired = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_MESSAGE).assertIsDisplayed()
        composeRule.onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_RETRY_BUTTON).assertIsDisplayed()
    }

    @Test
    fun success_invokes_onUnlocked_with_the_token() {
        val expected = AuthorizationToken(epoch = 1L)
        var captured: AuthorizationToken? = null
        val manager = UnlockManager(keyProvider = ScriptedVaultKeyProvider { UnlockResult.Success(expected) })

        composeRule.setContent {
            MaterialTheme {
                BiometricUnlockScreen(
                    unlockManager = manager,
                    onUnlocked = { captured = it },
                    onRecoveryRequired = {},
                )
            }
        }
        composeRule.waitForIdle()

        assertEquals(expected, captured)
    }

    @Test
    fun key_permanently_invalidated_invokes_onRecoveryRequired() {
        var recoveryRequired = false
        val manager =
            UnlockManager(
                keyProvider =
                    ScriptedVaultKeyProvider {
                        UnlockResult.KeyPermanentlyInvalidated(VaultKeyProvider.Factor.BIOMETRIC)
                    },
            )

        composeRule.setContent {
            MaterialTheme {
                BiometricUnlockScreen(
                    unlockManager = manager,
                    onUnlocked = {},
                    onRecoveryRequired = { recoveryRequired = true },
                )
            }
        }
        composeRule.waitForIdle()

        assertTrue(recoveryRequired)
    }
}

/**
 * Fakes the [VaultKeyProvider] seam directly (rather than the lower-level
 * `BiometricAuthenticator`) so these UI tests can script every
 * `UnlockResult` variant without a real Keystore/BiometricPrompt. [unlock]
 * is the only method [BiometricUnlockScreen] drives; the others are not
 * expected to be called from this screen and fail loudly if they are.
 */
private class ScriptedVaultKeyProvider(
    private val onUnlock: suspend () -> UnlockResult,
) : VaultKeyProvider {
    override suspend fun setup(
        activity: FragmentActivity,
        prompt: BiometricPrompt.PromptInfo,
    ): SetupResult = error("BiometricUnlockScreen does not call VaultKeyProvider.setup")

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
