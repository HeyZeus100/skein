package app.skein.core.designsystem.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.requestFocus
import app.skein.core.designsystem.icons.SkeinIcons
import app.skein.core.designsystem.theme.SkeinSpacing
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.core.designsystem.theme.SkeinThemeMode
import app.skein.testing.ui.SkeinDevice
import app.skein.testing.ui.UxDeviceRule
import app.skein.testing.ui.UxSpec
import app.skein.testing.ui.captureUx
import app.skein.testing.ui.skeinComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * DS9's component gallery: one capture per required state
 * (light/dark, font scale 2.0 — the task's own list, not the full device
 * matrix DS13's later screen-level gallery uses). Shows every new primitive
 * together: both filter-chip selection states, the context chip in its
 * normal and warning tone, the sources chip, an input chip with its remove
 * action, keycaps both with and without a hardware keyboard attached (so
 * the gating itself is visible in the same capture), and a button with its
 * keyboard focus ring forced on.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SkeinInteractionPrimitivesScreenshotTest(
    private val spec: UxSpec,
) {
    @get:Rule(order = 0)
    val deviceRule = UxDeviceRule(spec)

    @get:Rule(order = 1)
    val composeRule = skeinComposeRule()

    @Test
    fun componentsGallery() {
        composeRule.setContent {
            SkeinTheme(mode = if (spec.dark) SkeinThemeMode.DARK else SkeinThemeMode.LIGHT) {
                Surface(Modifier.fillMaxSize()) {
                    Gallery()
                }
            }
        }
        composeRule.onNodeWithTag(FOCUS_TARGET_TAG).requestFocus()
        composeRule.waitForIdle()
        composeRule.onRoot().captureUx(spec, "components-gallery")
    }

    @Composable
    private fun Gallery() {
        var filterSelected by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier.padding(SkeinSpacing.space16),
            verticalArrangement = Arrangement.spacedBy(SkeinSpacing.space16),
        ) {
            Text("Filter chip", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(SkeinSpacing.space8)) {
                SkeinFilterChip(label = "All", selected = !filterSelected, onClick = { filterSelected = false })
                SkeinFilterChip(
                    label = "Notes",
                    selected = filterSelected,
                    onClick = { filterSelected = true },
                )
            }

            Text("Context chip", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(SkeinSpacing.space8)) {
                SkeinContextChip(label = "2 notes · Knowledge on", onClick = {})
                SkeinContextChip(label = "2 notes · 1 file · ⚠", onClick = {}, warning = true)
            }

            Text("Sources chip", style = MaterialTheme.typography.titleSmall)
            SkeinSourcesChip(count = 3, onClick = {})

            Text("Input chip", style = MaterialTheme.typography.titleSmall)
            SkeinInputChip(label = "Fold launch plan", onRemove = {}, leadingIcon = SkeinIcons.Note)

            Text("Keycaps", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(SkeinSpacing.space16)) {
                Column {
                    Text("hardware keyboard attached", style = MaterialTheme.typography.bodySmall)
                    CompositionLocalProvider(
                        LocalConfiguration provides
                            hardwareKeyboardConfiguration(present = true),
                    ) {
                        SkeinKeycaps("Ctrl", "K")
                    }
                }
                Column {
                    Text("no hardware keyboard", style = MaterialTheme.typography.bodySmall)
                    CompositionLocalProvider(
                        LocalConfiguration provides
                            hardwareKeyboardConfiguration(present = false),
                    ) {
                        SkeinKeycaps("Ctrl", "K")
                    }
                }
            }

            Text("Keyboard focus ring", style = MaterialTheme.typography.titleSmall)
            CompositionLocalProvider(LocalSkeinKeyboardMode provides true) {
                Button(
                    onClick = {},
                    modifier =
                        Modifier
                            .testTag(FOCUS_TARGET_TAG)
                            .skeinFocusRing(),
                ) {
                    Text("Try again")
                }
            }
        }
    }

    private fun hardwareKeyboardConfiguration(present: Boolean): Configuration =
        Configuration().apply {
            keyboard = if (present) Configuration.KEYBOARD_QWERTY else Configuration.KEYBOARD_NOKEYS
            hardKeyboardHidden =
                if (present) Configuration.HARDKEYBOARDHIDDEN_NO else Configuration.HARDKEYBOARDHIDDEN_YES
        }

    private companion object {
        const val FOCUS_TARGET_TAG = "focus-ring-demo"

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun parameters(): List<Array<Any>> =
            listOf(
                UxSpec(SkeinDevice.PHONE, dark = false),
                UxSpec(SkeinDevice.PHONE, dark = true),
                UxSpec(SkeinDevice.PHONE, dark = false, fontScale = 2f),
            ).map { arrayOf<Any>(it) }
    }
}
