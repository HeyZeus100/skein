package app.skein.feature.shell.auth

import android.content.Intent
import android.os.Looper
import androidx.biometric.BiometricPrompt
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.fragment.app.FragmentActivity
import app.skein.core.model.AuthorizationToken
import app.skein.core.vault.key.RewrapResult
import app.skein.core.vault.key.SetupResult
import app.skein.core.vault.key.UnlockResult
import app.skein.core.vault.key.VaultKeyProvider
import app.skein.core.vault.session.UnlockManager
import app.skein.core.vault.session.UnlockState
import app.skein.feature.shell.testing.ShellTestTags
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BiometricUnlockCredentialFallbackTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<FragmentActivity>()
    private val biometric = VaultKeyProvider.Factor.BIOMETRIC
    private val credential = VaultKeyProvider.Factor.DEVICE_CREDENTIAL
    private val shown = mutableStateOf(true)
    private val factors = mutableListOf<VaultKeyProvider.Factor>()
    private val tokens = mutableListOf<AuthorizationToken>()
    private var recoveries = 0
    private var setups = 0
    private var resets = 0

    private fun show(
        deviceLocked: () -> Boolean = { false },
        authenticate: suspend (VaultKeyProvider.Factor) -> UnlockResult,
    ): UnlockManager {
        val manager =
            UnlockManager(
                CredentialProvider { factor ->
                    factors += factor
                    authenticate(factor)
                },
            )
        composeRule.setContent {
            MaterialTheme {
                if (shown.value) {
                    BiometricUnlockScreen(
                        unlockManager = manager,
                        onUnlocked = { tokens += it },
                        onRecoveryRequired = { recoveries++ },
                        onNotInitialised = { setups++ },
                        onResetRequested = { resets++ },
                        onKeyMaterialGoneResetRequested = { resets++ },
                        isDeviceLocked = deviceLocked,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        return manager
    }

    @Test
    fun `missing biometric offers explicit credential authentication without automatic recovery or reset`() {
        val token = AuthorizationToken(101)
        show { factor ->
            if (factor == biometric) UnlockResult.KeyMaterialGone(biometric) else UnlockResult.Success(token)
        }
        composeRule.onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_CREDENTIAL_BUTTON).assertIsDisplayed()
        assertEquals(listOf(biometric), factors)
        assertTrue(tokens.isEmpty())
        assertEquals(0, recoveries + setups + resets)

        composeRule.onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_CREDENTIAL_BUTTON).performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(biometric, credential), factors)
        assertEquals(listOf(token), tokens)
        assertEquals(0, recoveries + setups + resets)
    }

    @Test
    fun `cancelled credential attempt stays locked and retry keeps selected factor`() {
        show { UnlockResult.UserCancelled }
        composeRule.onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_CREDENTIAL_BUTTON).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(biometric, credential), factors)
        composeRule.onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_CREDENTIAL_BUTTON).assertDoesNotExist()
        composeRule.onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_RESET_BUTTON).assertDoesNotExist()

        composeRule.onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_RETRY_BUTTON).performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(biometric, credential, credential), factors)
        assertTrue(tokens.isEmpty())
        assertEquals(0, recoveries + setups + resets)
    }

    @Test
    fun `two queued credential clicks present once and disposal cancels the pending attempt`() {
        val pending = CompletableDeferred<UnlockResult>()
        val manager =
            show { factor ->
                if (factor == biometric) UnlockResult.KeyMaterialGone(biometric) else pending.await()
            }
        val click =
            composeRule
                .onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_CREDENTIAL_BUTTON)
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        composeRule.runOnIdle {
            click()
            click()
        }
        composeRule.waitForIdle()
        assertEquals(listOf(biometric, credential), factors)
        assertEquals(UnlockState.Unlocking, manager.state.value)

        composeRule.runOnIdle { shown.value = false }
        composeRule.waitForIdle()
        pending.complete(UnlockResult.Success(AuthorizationToken(102)))
        composeRule.waitForIdle()

        assertEquals(UnlockState.Locked, manager.state.value)
        assertNull(manager.authorizationToken.value)
        assertTrue(tokens.isEmpty())
        assertEquals(0, recoveries + setups + resets)
    }

    @Test
    fun `credential device-lock race waits and repeats credential only after wake`() {
        var locked = false
        var credentialCalls = 0
        show(deviceLocked = { locked }) { factor ->
            when {
                factor == biometric -> UnlockResult.KeyMaterialGone(biometric)
                credentialCalls++ == 0 -> {
                    locked = true
                    UnlockResult.DeviceLocked
                }
                else -> UnlockResult.UserCancelled
            }
        }
        composeRule.onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_CREDENTIAL_BUTTON).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_WAITING_MESSAGE).assertIsDisplayed()
        assertEquals(listOf(biometric, credential), factors)

        locked = false
        composeRule.activity.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()

        assertEquals(listOf(biometric, credential, credential), factors)
        assertTrue(tokens.isEmpty())
    }

    @Test
    fun `missing credential remains a typed failure with no repeated credential action or automatic reset`() {
        val manager = show { factor -> UnlockResult.KeyMaterialGone(factor) }
        composeRule.onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_CREDENTIAL_BUTTON).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_CREDENTIAL_BUTTON).assertDoesNotExist()
        composeRule.onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_RETRY_BUTTON).assertDoesNotExist()
        composeRule.onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_RESET_BUTTON).assertIsDisplayed()
        assertEquals(UnlockState.Locked, manager.state.value)
        assertEquals(listOf(biometric, credential), factors)
        assertEquals(0, recoveries + setups + resets)
    }

    @Test
    fun `credential invalidation remains recovery-required and never publishes success`() {
        val manager =
            show { factor ->
                if (factor == biometric) {
                    UnlockResult.KeyMaterialGone(biometric)
                } else {
                    UnlockResult.KeyPermanentlyInvalidated(credential)
                }
            }
        composeRule.onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_CREDENTIAL_BUTTON).performClick()
        composeRule.waitForIdle()

        assertEquals(UnlockState.RecoveryRequired, manager.state.value)
        assertEquals(1, recoveries)
        assertEquals(0, setups + resets)
        assertTrue(tokens.isEmpty())
    }
}

private class CredentialProvider(
    private val authenticate: suspend (VaultKeyProvider.Factor) -> UnlockResult,
) : VaultKeyProvider {
    override suspend fun setup(
        activity: FragmentActivity,
        prompt: BiometricPrompt.PromptInfo,
    ): SetupResult = error("No setup")

    override suspend fun setup(
        activity: FragmentActivity,
        prompt: BiometricPrompt.PromptInfo,
        existingMaster: ByteArray,
    ): SetupResult = error("No import")

    override fun isInitialised(): Boolean = error("No probe")

    override suspend fun unlock(
        activity: FragmentActivity,
        prompt: BiometricPrompt.PromptInfo,
        factor: VaultKeyProvider.Factor,
    ): UnlockResult = authenticate(factor)

    override fun currentKey(): ByteArray? = null

    override fun lock() = Unit

    override suspend fun rewrapAfterInvalidation(
        activity: FragmentActivity,
        prompt: BiometricPrompt.PromptInfo,
        survivingFactor: VaultKeyProvider.Factor,
    ): RewrapResult = error("No rewrap")
}
