// skein-xtov.9 — "before" captures of the pre-unlock screens, composed the
// way `:app`'s VaultGate composes them (`SkeinTheme` + `EdgeToEdgeSurface`).
// Both screens look up a `FragmentActivity` from `LocalContext`, so the host
// here is a no-action-bar `FragmentActivity` (the app's own
// `Theme.Material.NoActionBar`) rather than Roborazzi's activity.
package app.skein.feature.shell.screenshots

import android.app.Application
import android.content.ComponentName
import android.os.Bundle
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import app.skein.core.vault.key.RewrapResult
import app.skein.core.vault.key.SetupResult
import app.skein.core.vault.key.UnlockResult
import app.skein.core.vault.key.VaultKeyProvider
import app.skein.core.vault.session.UnlockManager
import app.skein.feature.shell.auth.BiometricUnlockScreen
import app.skein.feature.shell.auth.VaultSetupScreen
import app.skein.feature.shell.layout.EdgeToEdgeSurface
import app.skein.feature.shell.theme.SkeinTheme
import kotlinx.coroutines.awaitCancellation
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AuthScreenshotTest(
    private val spec: UxSpec,
) {
    @get:Rule(order = 0)
    val registerHost =
        object : ExternalResource() {
            override fun before() {
                val app = ApplicationProvider.getApplicationContext<Application>()
                Shadows
                    .shadowOf(
                        app.packageManager,
                    ).addActivityIfNotPresent(ComponentName(app, UxFragmentActivity::class.java))
            }
        }

    @get:Rule(order = 1)
    val deviceRule = UxDeviceRule(spec)

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<UxFragmentActivity>()

    /** Waiting on the system biometric prompt (the prompt itself is a system window). */
    @Test
    fun unlock() {
        val manager = UnlockManager(keyProvider = ScriptedKeyProvider { awaitCancellation() })
        composeRule.setContent { Gate { m -> BiometricUnlockScreen(manager, {}, {}, m, isDeviceLocked = { false }) } }
        composeRule.waitForIdle()
        composeRule.onRoot().captureUx(spec, "vault-unlock")
    }

    /** Unreadable key envelope: the only state that offers the destructive reset. */
    @Test
    fun unlockEnvelopeUnreadable() {
        val manager = UnlockManager(keyProvider = ScriptedKeyProvider { UnlockResult.Failed("key envelope corrupt") })
        composeRule.setContent {
            Gate { m -> BiometricUnlockScreen(manager, {}, {}, m, onResetRequested = {}, isDeviceLocked = { false }) }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureUx(spec, "vault-unlock-error")
    }

    @Test
    fun setup() {
        composeRule.setContent {
            Gate { m -> VaultSetupScreen(ScriptedKeyProvider { awaitCancellation() }, {}, {}, m) }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureUx(spec, "vault-setup")
    }

    @Composable
    private fun Gate(content: @Composable (Modifier) -> Unit) {
        SkeinTheme { EdgeToEdgeSurface { m -> content(m) } }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun parameters(): List<Array<Any>> = uxSpecs()
    }
}

/** `FragmentActivity` host with `:app`'s window theme (no action bar). */
class UxFragmentActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(android.R.style.Theme_Material_NoActionBar)
        super.onCreate(savedInstanceState)
    }
}

/** Key provider whose unlock/setup run [onUnlock]; nothing here ever creates key material. */
private class ScriptedKeyProvider(
    private val onUnlock: suspend () -> UnlockResult,
) : VaultKeyProvider {
    override suspend fun setup(
        activity: FragmentActivity,
        prompt: BiometricPrompt.PromptInfo,
    ): SetupResult = awaitCancellation()

    override suspend fun setup(
        activity: FragmentActivity,
        prompt: BiometricPrompt.PromptInfo,
        existingMaster: ByteArray,
    ): SetupResult = awaitCancellation()

    override fun isInitialised(): Boolean = true

    override suspend fun unlock(
        activity: FragmentActivity,
        prompt: BiometricPrompt.PromptInfo,
        factor: VaultKeyProvider.Factor,
    ): UnlockResult = onUnlock()

    override fun currentKey(): ByteArray? = null

    override fun lock() = Unit

    override suspend fun rewrapAfterInvalidation(
        activity: FragmentActivity,
        prompt: BiometricPrompt.PromptInfo,
        survivingFactor: VaultKeyProvider.Factor,
    ): RewrapResult = awaitCancellation()
}
