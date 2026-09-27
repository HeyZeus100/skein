// Accessibility sweep over the real Privacy & security category.
package app.skein.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.core.navigation.SettingsCategory
import app.skein.testing.ui.ClickRecorder
import app.skein.testing.ui.assertEveryActionIsNamed
import app.skein.testing.ui.assertNoDeadControls
import app.skein.testing.ui.assertNoTextOverflow
import app.skein.testing.ui.assertTouchTargets
import app.skein.testing.ui.runSweep
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Not a pass/fail gate: this bead's job is to report what the sweep finds
 * on a real screen, not to fix the category (Wave 11's job) — see
 * [runSweep]'s doc.
 *
 * GraphicsMode.NATIVE: assertNoTextOverflow needs real glyph metrics —
 * Robolectric's legacy graphics mode stubs text measurement to near-zero
 * widths, which would misreport every row's label as overflowing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccessibilitySweepSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `sweep security category and report findings`() {
        val recorder = ClickRecorder()
        composeRule.setContent {
            SkeinTheme {
                SettingsCategoryScreen(
                    category = SettingsCategory.PRIVACY_AND_SECURITY,
                    viewModel = rememberSettingsViewModel(flowOf(true), { recorder.recordClick() }),
                    appVersion = "0.1.0 (1)",
                )
            }
        }

        val results =
            runSweep(
                listOf(
                    "assertTouchTargets" to { composeRule.assertTouchTargets() },
                    "assertEveryActionIsNamed" to { composeRule.assertEveryActionIsNamed() },
                    "assertNoTextOverflow" to { composeRule.assertNoTextOverflow() },
                    "assertNoDeadControls" to { composeRule.assertNoDeadControls(recorder) },
                ),
            )
        println("=== Security category accessibility sweep (skein-xtov.23.19, UT-5) ===")
        results.forEach { r -> println(if (r.passed) "PASS ${r.name}" else "FAIL ${r.name}\n${r.detail}") }
    }
}
