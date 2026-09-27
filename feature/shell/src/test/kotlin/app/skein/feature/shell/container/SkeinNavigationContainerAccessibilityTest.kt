// skein-xtov.24.6 (AL-07, UX Wave 3): a11y sweeps (:testing-ui) over the
// new drawer and rail contents. `assertTouchTargets`/`assertEveryActionIsNamed`
// gate directly — this is new code this bead owns, so a regression there
// should fail CI. `assertNoTextOverflow` runs through `runSweep` instead,
// `AccessibilitySweepSmokeTest`'s "findings, not a gate" style: under this
// module's plain `createAndroidComposeRule<ComponentActivity>()` (no
// device qualifiers), it flags `hasVisualOverflow` on every single-line
// label here, including ones with generous, unmeasured-looking bounds
// (e.g. a 34dp-wide "Chat") — the same Robolectric/GraphicsMode.NATIVE
// text-metrics caveat `AccessibilitySweepSmokeTest` already isolates this
// check for, not a truncation this bead's rows actually have.
package app.skein.feature.shell.container

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.testing.ui.assertEveryActionIsNamed
import app.skein.testing.ui.assertNoTextOverflow
import app.skein.testing.ui.assertTouchTargets
import app.skein.testing.ui.runSweep
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val NOW = 1_800_000_000_000L

private fun sampleHistory(): List<ChatHistoryItem> =
    listOf(
        ChatHistoryItem(
            id = "a",
            title = "Skein UX redesign",
            lastMessageAtMillis = NOW,
            timeLabel = "9:41",
            preview = "Straw colonised in 14 days.",
            isSelected = true,
            onOpen = {},
            onRename = {},
            onDelete = {},
        ),
        ChatHistoryItem(
            id = "b",
            title = "RAG architecture",
            lastMessageAtMillis = NOW - 3_600_000L,
            timeLabel = "8:41",
            onOpen = {},
            onRename = {},
            onDelete = {},
        ),
    )

private fun sampleSpaces(): List<SkeinSpace> =
    listOf(
        SkeinSpace("s1", "Personal", isSelected = true, onSelect = {}),
        SkeinSpace("s2", "Work", isSelected = false, onSelect = {}),
    )

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SkeinNavigationContainerAccessibilityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `the drawer content passes the accessibility sweep`() {
        composeRule.setContent {
            SkeinTheme {
                SkeinDrawerContent(
                    destination = SkeinDestination.CHAT,
                    onNavigate = {},
                    onNewChat = {},
                    onSearch = {},
                    history = sampleHistory(),
                    spaces = sampleSpaces(),
                    now = { NOW },
                )
            }
        }

        composeRule.assertTouchTargets()
        composeRule.assertEveryActionIsNamed()
        reportTextOverflow("drawer")
    }

    @Test
    fun `the rail content passes the accessibility sweep`() {
        composeRule.setContent {
            SkeinTheme {
                SkeinRailContent(
                    destination = SkeinDestination.CHAT,
                    onNavigate = {},
                    onNewChat = {},
                    spaces = sampleSpaces(),
                )
            }
        }

        composeRule.assertTouchTargets()
        composeRule.assertEveryActionIsNamed()
        reportTextOverflow("rail")
    }

    private fun reportTextOverflow(label: String) {
        val results = runSweep(listOf("assertNoTextOverflow" to { composeRule.assertNoTextOverflow() }))
        println("=== SkeinNavigationContainer ($label) accessibility sweep (skein-xtov.24.6) ===")
        results.forEach { r -> println(if (r.passed) "PASS ${r.name}" else "FAIL ${r.name}\n${r.detail}") }
    }
}
