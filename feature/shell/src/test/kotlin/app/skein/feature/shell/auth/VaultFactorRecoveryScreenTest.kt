package app.skein.feature.shell.auth

import androidx.biometric.BiometricPrompt
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.fragment.app.FragmentActivity
import app.skein.core.model.AuthorizationToken
import app.skein.core.vault.key.RewrapResult
import app.skein.core.vault.key.SetupResult
import app.skein.core.vault.key.UnlockResult
import app.skein.core.vault.key.VaultKeyProvider
import app.skein.core.vault.session.LockReason
import app.skein.core.vault.session.UnlockManager
import app.skein.core.vault.session.UnlockState
import app.skein.feature.shell.testing.ShellTestTags
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultFactorRecoveryScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<FragmentActivity>()
    private val biometric = VaultKeyProvider.Factor.BIOMETRIC
    private val credential = VaultKeyProvider.Factor.DEVICE_CREDENTIAL
    private val factors = mutableListOf<VaultKeyProvider.Factor>()
    private val tokens = mutableListOf<AuthorizationToken>()
    private val shown = mutableStateOf(true)
    private val prompt =
        BiometricPrompt.PromptInfo
            .Builder()
            .setTitle("Unlock")
            .setNegativeButtonText("Cancel")
            .build()

    private fun manager(authenticate: suspend (VaultKeyProvider.Factor) -> UnlockResult): UnlockManager =
        UnlockManager(
            RecoveryProvider { factor ->
                factors += factor
                authenticate(factor)
            },
        )

    private fun show(manager: UnlockManager) {
        composeRule.setContent {
            MaterialTheme {
                if (shown.value) VaultFactorRecoveryScreen(manager, { tokens += it }, isDeviceLocked = { false })
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `known biometric invalidation offers one explicit credential action then preserves typed retry`() {
        val manager =
            manager { factor ->
                if (factor == biometric) {
                    UnlockResult.KeyPermanentlyInvalidated(biometric)
                } else {
                    UnlockResult.UserCancelled
                }
            }
        runBlocking { manager.unlock(composeRule.activity, prompt, biometric) }
        show(manager)
        assertEquals(listOf(biometric), factors)
        composeRule.onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_RETRY_BUTTON).assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(biometric, credential), factors)
        assertEquals(UnlockState.RecoveryRequired, manager.state.value)
        assertEquals(biometric, manager.recoveryFactor.value)
        composeRule
            .onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_MESSAGE)
            .assertTextContains("Authentication was cancelled.", substring = true)
        composeRule.onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_RESET_BUTTON).assertDoesNotExist()
        assertTrue(tokens.isEmpty())
    }

    @Test
    fun `survivor success calls the host once without setup or replacement`() {
        val token = AuthorizationToken(301)
        val manager =
            manager { factor ->
                if (factor == biometric) {
                    UnlockResult.KeyPermanentlyInvalidated(biometric)
                } else {
                    UnlockResult.Success(token)
                }
            }
        runBlocking { manager.unlock(composeRule.activity, prompt, biometric) }
        show(manager)
        composeRule.onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_RETRY_BUTTON).performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(biometric, credential), factors)
        assertEquals(listOf(token), tokens)
        assertNull(manager.recoveryFactor.value)
        assertEquals(token, manager.authorizationToken.value)
    }

    @Test
    fun `unknown failed factor renders guidance and never guesses an authentication method`() {
        val manager = manager { UnlockResult.Success(AuthorizationToken(302)) }
        runBlocking {
            manager.unlock(composeRule.activity, prompt, biometric)
            manager.lockAndAwait(LockReason.KEY_INVALIDATED)
        }
        show(manager)

        composeRule
            .onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_MESSAGE)
            .assertTextContains("while recovery is assessed", substring = true)
        composeRule.onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_RETRY_BUTTON).assertDoesNotExist()
        assertEquals(listOf(biometric), factors)
        assertTrue(tokens.isEmpty())
    }

    @Test
    fun `missing survivor leaves recovery intact without another prompt or destructive action`() {
        val manager =
            manager { factor ->
                if (factor == biometric) {
                    UnlockResult.KeyPermanentlyInvalidated(biometric)
                } else {
                    UnlockResult.KeyMaterialGone(credential)
                }
            }
        runBlocking { manager.unlock(composeRule.activity, prompt, biometric) }
        show(manager)
        composeRule.onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_RETRY_BUTTON).performClick()
        composeRule.waitForIdle()

        composeRule
            .onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_MESSAGE)
            .assertTextContains("The other device key is missing.", substring = true)
        composeRule.onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_RETRY_BUTTON).assertDoesNotExist()
        composeRule.onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_RESET_BUTTON).assertDoesNotExist()
        assertEquals(UnlockState.RecoveryRequired, manager.state.value)
        assertEquals(listOf(biometric, credential), factors)
        assertTrue(tokens.isEmpty())
    }

    @Test
    fun `disposing recovery screen cancels authentication and late completion cannot unlock`() {
        val pending = CompletableDeferred<UnlockResult>()
        val manager =
            manager { factor ->
                if (factor == biometric) UnlockResult.KeyPermanentlyInvalidated(biometric) else pending.await()
            }
        runBlocking { manager.unlock(composeRule.activity, prompt, biometric) }
        show(manager)
        composeRule.onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_RETRY_BUTTON).performClick()
        composeRule.waitForIdle()
        assertEquals(UnlockState.Unlocking, manager.state.value)
        composeRule.runOnIdle { shown.value = false }
        composeRule.waitForIdle()
        pending.complete(UnlockResult.Success(AuthorizationToken(303)))
        composeRule.waitForIdle()

        assertEquals(UnlockState.RecoveryRequired, manager.state.value)
        assertEquals(biometric, manager.recoveryFactor.value)
        assertNull(manager.authorizationToken.value)
        assertTrue(tokens.isEmpty())
    }
}

private class RecoveryProvider(
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
