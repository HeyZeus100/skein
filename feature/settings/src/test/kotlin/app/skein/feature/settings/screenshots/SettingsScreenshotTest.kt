// skein-xtov.9 — "before" captures of Settings, full window, in the same
// scaffold `SettingsScreenPreviews` uses (SkeinTheme + background Surface),
// with every setting at its shipped default. In-app it sits below the
// shell's command bar in the SETTINGS destination.
package app.skein.feature.settings.screenshots

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import app.skein.feature.settings.SettingsScreen
import app.skein.feature.shell.theme.SkeinTheme
import com.github.takahirom.roborazzi.RoborazziActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenshotTest(
    private val spec: UxSpec,
) {
    @get:Rule(order = 0)
    val deviceRule = UxDeviceRule(spec)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<RoborazziActivity>()

    @Test
    fun settings() {
        composeRule.setContent {
            SkeinTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SettingsScreen(flagSecureEnabled = true, onFlagSecureEnabledChange = {}, appVersion = "0.1.0 (1)")
                }
            }
        }
        composeRule.onRoot().captureUx(spec, "settings")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun parameters(): List<Array<Any>> = uxSpecs()
    }
}
