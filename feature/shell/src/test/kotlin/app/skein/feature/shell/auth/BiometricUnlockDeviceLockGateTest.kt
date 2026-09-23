// skein-9psb — Robolectric Compose coverage for the auto-present gate
// [BiometricUnlockScreen] applies before ever calling `UnlockManager.unlock`:
// while the (fake, injected) keyguard reports the device locked, the screen
// must show [ShellTestTags.BIOMETRIC_UNLOCK_WAITING_MESSAGE] and never call
// `unlock()`; once the device is reported unlocked — signalled here the same
// way the real fix listens for it, `Intent.ACTION_USER_PRESENT` — the screen
// presents exactly once. Mirrors `BiometricUnlockResetAffordanceTest`'s
// Robolectric setup (`createAndroidComposeRule<FragmentActivity>()`, sdk 34 —
// bd memory `robolectric-sdk37-needs-java21`) and fakes `VaultKeyProvider`
// directly so no real `BiometricPrompt`/Keystore is needed.

package app.skein.feature.shell.auth

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.biometric.BiometricPrompt
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import app.skein.core.model.AuthorizationToken
import app.skein.core.vault.key.RewrapResult
import app.skein.core.vault.key.SetupResult
import app.skein.core.vault.key.UnlockResult
import app.skein.core.vault.key.VaultKeyProvider
import app.skein.core.vault.session.UnlockManager
import app.skein.feature.shell.testing.ShellTestTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BiometricUnlockDeviceLockGateTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<FragmentActivity>()

    @Test
    fun `a locked keyguard suppresses the auto-prompt`() {
        val unlockCalls = AtomicInteger(0)
        val provider = CountingVaultKeyProvider(unlockCalls)
        val manager = UnlockManager(keyProvider = provider)

        composeRule.setContent {
            MaterialTheme {
                BiometricUnlockScreen(
                    unlockManager = manager,
                    onUnlocked = {},
                    onRecoveryRequired = {},
                    isDeviceLocked = { true },
                )
            }
        }
        composeRule.waitForIdle()

        assertEquals(0, unlockCalls.get())
        composeRule.onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_WAITING_MESSAGE).assertIsDisplayed()
    }

    @Test
    fun `an unlock-present event triggers the suppressed prompt`() {
        val unlockCalls = AtomicInteger(0)
        val provider = CountingVaultKeyProvider(unlockCalls)
        val manager = UnlockManager(keyProvider = provider)
        var locked = true

        composeRule.setContent {
            MaterialTheme {
                BiometricUnlockScreen(
                    unlockManager = manager,
                    onUnlocked = {},
                    onRecoveryRequired = {},
                    isDeviceLocked = { locked },
                )
            }
        }
        composeRule.waitForIdle()
        assertEquals(0, unlockCalls.get())

        // Act — the device unlocks; the real fix learns this from the same
        // system broadcast fired here.
        locked = false
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        appContext.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()

        assertEquals(1, unlockCalls.get())
    }

    @Test
    fun `an already-unlocked device presents immediately, unchanged from before skein-9psb`() {
        val unlockCalls = AtomicInteger(0)
        val provider = CountingVaultKeyProvider(unlockCalls)
        val manager = UnlockManager(keyProvider = provider)

        composeRule.setContent {
            MaterialTheme {
                BiometricUnlockScreen(
                    unlockManager = manager,
                    onUnlocked = {},
                    onRecoveryRequired = {},
                    isDeviceLocked = { false },
                )
            }
        }
        composeRule.waitForIdle()

        assertEquals(1, unlockCalls.get())
        composeRule.onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_PROGRESS).assertIsDisplayed()
    }
}

/** Fakes the [VaultKeyProvider] seam, counting `unlock()` calls. Always succeeds once called. */
private class CountingVaultKeyProvider(
    private val unlockCalls: AtomicInteger,
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
    ): UnlockResult {
        unlockCalls.incrementAndGet()
        return UnlockResult.Success(AuthorizationToken(unlockCalls.get().toLong()))
    }

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
