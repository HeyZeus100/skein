// skein-xtov.23.19 (UT-5): runs the :testing-ui accessibility sweep helpers
// over the real SettingsScreen as a smoke — reporting findings for Wave 11,
// not fixing SettingsScreen here (out of this bead's scope; see
// docs/ux/UX_TEST_PLAN.md §9's "Known offenders today" row, which already
// names some of these by dp size).
package app.skein.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.skein.feature.shell.theme.SkeinTheme
import app.skein.testing.ui.ClickRecorder
import app.skein.testing.ui.assertEveryActionIsNamed
import app.skein.testing.ui.assertNoDeadControls
import app.skein.testing.ui.assertNoTextOverflow
import app.skein.testing.ui.assertTouchTargets
import app.skein.testing.ui.runSweep
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Not a pass/fail gate: this bead's job is to report what the sweep finds
 * on a real screen, not to fix `SettingsScreen` (Wave 11's job) — see
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
    fun `sweep SettingsScreen and report findings`() {
        val recorder = ClickRecorder()
        composeRule.setContent {
            // SkeinTheme, matching how SettingsRoute/MainActivity actually
            // host this screen (not just for fidelity: verified it does NOT
            // remove the widespread assertNoTextOverflow findings below —
            // see the report's note on that).
            SkeinTheme {
                SettingsScreen(
                    flagSecureEnabled = true,
                    onFlagSecureEnabledChange = { recorder.recordClick() },
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
        println("=== SettingsScreen accessibility sweep (skein-xtov.23.19, UT-5) ===")
        results.forEach { r -> println(if (r.passed) "PASS ${r.name}" else "FAIL ${r.name}\n${r.detail}") }
    }
}
