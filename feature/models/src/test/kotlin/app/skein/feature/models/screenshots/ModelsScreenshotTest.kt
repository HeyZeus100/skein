// skein-xtov.9 — "before" captures of `/models`. `:app` draws ModelsScreen in
// `SkeinApp`'s overlay slot, i.e. full-window inside `SkeinTheme`, which is
// exactly what is composed here.
package app.skein.feature.models.screenshots

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import app.skein.feature.models.ModelListItem
import app.skein.feature.models.ModelsScreen
import app.skein.feature.shell.theme.SkeinTheme
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziActivity
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
    val composeRule = createAndroidComposeRule<RoborazziActivity>()

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
        composeRule.setContent { SkeinTheme { ModelsScreen(models, onSetDefault = {}, onDelete = {}, onDismiss = {}) } }
        composeRule.onRoot().captureUx(spec, "models")
    }

    @Test
    fun modelsEmpty() {
        composeRule.setContent {
            SkeinTheme {
                ModelsScreen(
                    emptyList(),
                    onSetDefault = {},
                    onDelete = {},
                    onDismiss = {},
                )
            }
        }
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
        composeRule.setContent {
            SkeinTheme { ModelsScreen(listOf(model), onSetDefault = {}, onDelete = {}, onDismiss = {}) }
        }
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/${spec.device.dir}/models-delete-confirm${spec.suffix}.png")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun parameters(): List<Array<Any>> = uxSpecs()
    }
}
