// skein-xtov.23.19 (UT-5): runs the :testing-ui accessibility sweep helpers
// over the real chat composer (`ChatBottomBar`) as a smoke — reporting
// findings for Wave 11, not fixing the composer here.
package app.skein.feature.chat

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
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
 * Not a pass/fail gate: reports what the sweep finds on the real composer,
 * doesn't fix it here — see [runSweep]'s doc.
 *
 * GraphicsMode.NATIVE: assertNoTextOverflow needs real glyph metrics —
 * Robolectric's legacy graphics mode stubs text measurement to near-zero
 * widths, which would misreport the "$" prompt glyph as overflowing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccessibilitySweepSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `sweep the chat composer and report findings`() {
        val recorder = ClickRecorder()
        composeRule.setContent {
            SkeinTheme {
                ChatBottomBar(
                    isGenerating = false,
                    onSend = { recorder.recordClick() },
                    onCancel = { recorder.recordClick() },
                    wikilinkSuggest = { emptyList() },
                    onAttach = { _, _, _ -> null },
                )
            }
        }
        // Send is disabled until there's text; type some so its click is
        // actually exercised instead of being (correctly) excluded as disabled.
        composeRule.onNodeWithTag(COMPOSER_TEST_TAG).performTextInput("hello")

        val results =
            runSweep(
                listOf(
                    "assertTouchTargets" to { composeRule.assertTouchTargets() },
                    "assertEveryActionIsNamed" to { composeRule.assertEveryActionIsNamed() },
                    "assertNoTextOverflow" to { composeRule.assertNoTextOverflow() },
                    "assertNoDeadControls" to { composeRule.assertNoDeadControls(recorder) },
                ),
            )
        println("=== ChatBottomBar accessibility sweep (skein-xtov.23.19, UT-5) ===")
        results.forEach { r -> println(if (r.passed) "PASS ${r.name}" else "FAIL ${r.name}\n${r.detail}") }
    }
}
