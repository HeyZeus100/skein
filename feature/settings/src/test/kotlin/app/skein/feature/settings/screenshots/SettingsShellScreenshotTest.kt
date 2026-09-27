// skein-xtov.24.9 (AL-09b): goldens for the Settings destination in the
// NavDisplay shell on the owner's Fold — outer (524 dp: categories only) and
// inner (1007 dp: categories │ Appearance preselected), light and dark.
// Recorded with `tools/ux/shots record settings --tests
// "app.skein.feature.settings.screenshots.SettingsShellScreenshotTest"`.
package app.skein.feature.settings.screenshots

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.core.navigation.Destination
import app.skein.feature.settings.entries.SettingsEntryDeps
import app.skein.feature.settings.entries.SettingsHost
import app.skein.feature.settings.rememberSettingsViewModel
import app.skein.feature.shell.host.SkeinShellState
import app.skein.testing.ui.SkeinDevice
import app.skein.testing.ui.UxDeviceRule
import app.skein.testing.ui.UxSpec
import app.skein.testing.ui.captureUx
import app.skein.testing.ui.skeinComposeRule
import app.skein.testing.ui.uxSpecs
import kotlinx.coroutines.flow.flowOf
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsShellScreenshotTest(
    private val spec: UxSpec,
) {
    @get:Rule(order = 0)
    val deviceRule = UxDeviceRule(spec)

    @get:Rule(order = 1)
    val composeRule = skeinComposeRule()

    private lateinit var shell: SkeinShellState

    private fun assumeFold() {
        assumeTrue(spec.fontScale == 1f)
        assumeTrue(spec.device == SkeinDevice.FOLD_OUTER_524 || spec.device == SkeinDevice.FOLD_INNER_1007)
    }

    private fun show() {
        composeRule.setContent {
            SkeinTheme {
                val viewModel =
                    rememberSettingsViewModel(
                        flagSecureEnabledFlow = flowOf(true),
                        onSetFlagSecureEnabled = {},
                    )
                val deps = SettingsEntryDeps(viewModel, "0.1.0 (1)")
                SettingsHost(deps, size = null) { shell = it }
            }
        }
        composeRule.runOnIdle { shell.navigate { switchTo(it, Destination.SETTINGS) } }
        composeRule.waitForIdle()
    }

    /** Outer: the categories list only; inner: categories │ Appearance preselected (spec §8.9). */
    @Test
    fun categories() {
        assumeFold()
        show()
        composeRule.onRoot().captureUx(spec, "settings-categories")
    }

    /** A selected category — outer: full-screen with ←; inner: beside the list. */
    @Test
    fun category() {
        assumeFold()
        show()
        composeRule.onNodeWithText("Privacy & security").performClick()
        composeRule.waitForIdle()
        composeRule.onRoot().captureUx(spec, "settings-category")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun parameters(): List<Array<Any>> = uxSpecs()
    }
}
