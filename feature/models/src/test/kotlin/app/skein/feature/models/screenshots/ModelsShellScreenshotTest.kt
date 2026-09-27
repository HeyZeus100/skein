// skein-xtov.24.9 (AL-09b): goldens for the Models destination in the
// NavDisplay shell on the owner's Fold — outer (524 dp: list only) and inner
// (1007 dp: list │ details), light and dark. Recorded with `tools/ux/shots
// record models --tests "app.skein.feature.models.screenshots.ModelsShellScreenshotTest"`.
package app.skein.feature.models.screenshots

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.core.navigation.Destination
import app.skein.feature.models.ModelListItem
import app.skein.feature.models.entries.ModelsEntryDeps
import app.skein.feature.models.entries.ModelsHost
import app.skein.feature.shell.host.SkeinShellState
import app.skein.testing.ui.SkeinDevice
import app.skein.testing.ui.UxDeviceRule
import app.skein.testing.ui.UxSpec
import app.skein.testing.ui.captureUx
import app.skein.testing.ui.skeinComposeRule
import app.skein.testing.ui.uxSpecs
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Two synthetic model names — no personal data (UX_TEST_PLAN.md §4.1). */
private val FIXTURE_MODELS =
    listOf(
        ModelListItem("0190a3c4-5b6d-7e8f-9a0b-1c2d3e4f5a6b", "Qwen 2.5 3B", 2_147_483_648L, "Apache-2.0", true, false),
        ModelListItem("0190a3c4-5b6d-7e8f-9a0b-1c2d3e4f5a6c", "Llama 3.2 1B", 1_200_000_000L, "Llama3.2", false, false),
    )

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModelsShellScreenshotTest(
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
        val deps = ModelsEntryDeps(models = FIXTURE_MODELS, onSetDefault = {}, onDelete = {})
        composeRule.setContent { SkeinTheme { ModelsHost(deps, size = null) { shell = it } } }
        composeRule.runOnIdle { shell.navigate { switchTo(it, Destination.MODELS) } }
        composeRule.waitForIdle()
    }

    /** Outer: the list only; inner: the list │ the empty-detail placeholder (spec §8.2). */
    @Test
    fun list() {
        assumeFold()
        show()
        composeRule.onRoot().captureUx(spec, "models-list")
    }

    /** A selected model's details — outer: full-screen with ←; inner: beside the list. */
    @Test
    fun details() {
        assumeFold()
        show()
        composeRule.onNodeWithText(FIXTURE_MODELS[0].displayName).performClick()
        composeRule.waitForIdle()
        composeRule.onRoot().captureUx(spec, "models-details")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun parameters(): List<Array<Any>> = uxSpecs()
    }
}
