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
import app.skein.testing.ui.UxDeviceRule
import app.skein.testing.ui.UxSpec
import app.skein.testing.ui.captureUx
import app.skein.testing.ui.skeinComposeRule
import app.skein.testing.ui.uxSpecs
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModelsScreenshotTest(
    private val spec: UxSpec,
) {
    @get:Rule(order = 0)
    val deviceRule = UxDeviceRule(spec)

    @get:Rule(order = 1)
    val composeRule = skeinComposeRule()

    private fun showModels(models: List<ModelListItem>) {
        lateinit var shell: SkeinShellState
        val deps = ModelsEntryDeps(models, onSetDefault = {}, onDelete = {}, onImport = {})
        composeRule.setContent { SkeinTheme { ModelsHost(deps, size = null) { shell = it } } }
        composeRule.runOnIdle { shell.navigate { switchTo(it, Destination.MODELS) } }
        composeRule.waitForIdle()
    }

    @Test
    fun models() {
        val models =
            listOf(
                ModelListItem(
                    id = "qwen2.5-3b-instruct-abliterated-q3_k_m",
                    displayName = "qwen2.5-3b-instruct-abliterated-q3_k_m.gguf",
                    sizeBytes = 1_590_000_000L,
                    licenseSpdx = "Apache-2.0",
                    isDefault = true,
                    isLoaded = true,
                ),
                ModelListItem(
                    id = "llama-3.2-1b-instruct-q4_k_m",
                    displayName = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
                    sizeBytes = 807_690_000L,
                    licenseSpdx = "LicenseRef-Llama-3.2-Community",
                    isDefault = false,
                    isLoaded = false,
                ),
                ModelListItem(
                    id = "phi-3.5-mini-instruct-q4_k_m",
                    displayName = "Phi-3.5-mini-instruct-Q4_K_M.gguf",
                    sizeBytes = 2_390_000_000L,
                    licenseSpdx = "UNKNOWN",
                    isDefault = false,
                    isLoaded = false,
                ),
            )
        showModels(models)
        composeRule.onRoot().captureUx(spec, "models")
    }

    @Test
    fun modelsEmpty() {
        showModels(emptyList())
        composeRule.onRoot().captureUx(spec, "models-empty")
    }

    /** Stage H (LC-27): Delete asks first. Whole screen, so the dialog window is in the capture. */
    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun modelsDeleteConfirm() {
        val model =
            ModelListItem(
                id = "llama-3.2-1b-instruct-q4_k_m",
                displayName = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
                sizeBytes = 807_690_000L,
                licenseSpdx = "LicenseRef-Llama-3.2-Community",
                isDefault = false,
                isLoaded = false,
            )
        showModels(listOf(model))
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.waitForIdle()
        // A whole-screen capture (the confirmation dialog floats in its own
        // window, off `composeRule.onRoot()`), so this bypasses `captureUx` —
        // but still resolves against the module's `roborazzi.outputDir` via
        // `filePathStrategy` (docs/ux/UX_TEST_PLAN.md §3.5/§4.1), like every
        // other capture in this module.
        captureScreenRoboImage("${spec.device.dir}/models-delete-confirm${spec.nameSuffix}.png")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun parameters(): List<Array<Any>> = uxSpecs()
    }
}
