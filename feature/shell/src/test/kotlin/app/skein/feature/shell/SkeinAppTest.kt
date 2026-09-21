package app.skein.feature.shell

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.window.core.layout.WindowSizeClass
import app.skein.feature.shell.layout.FoldPosture
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * bd `skein-64y9`: [SkeinApp]'s `timelinePane` slot. `windowSizeClass`/
 * `posture` are passed explicitly (same seam `AdaptivePaneHostPreviews`
 * uses) so each test drives a deterministic pane layout without depending on
 * Robolectric's real window metrics or fold-feature plumbing.
 *
 * Pinned to SDK 34 (bd memory `robolectric-sdk37-needs-java21`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkeinAppTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val expandedWidth = WindowSizeClass(1000f, 900f)
    private val compactWidth = WindowSizeClass(400f, 800f)

    @Test
    fun `dual-pane renders the timeline slot expanded in the left pane`() {
        composeRule.setContent {
            SkeinApp(
                windowSizeClass = expandedWidth,
                posture = FoldPosture.Unknown,
                timelinePane = { expanded, _, _ ->
                    Text(if (expanded) "TIMELINE_EXPANDED" else "TIMELINE_COMPACT")
                },
            )
        }

        composeRule.onNodeWithText("TIMELINE_EXPANDED").assertExists()
        composeRule.onNodeWithText("TIMELINE_COMPACT").assertDoesNotExist()
        // The right zone still has no tabs open — the slot only replaces the
        // hardcoded left-pane placeholder, not `TabHost`'s own empty state.
        composeRule.onNodeWithText("No tabs open — back to timeline").assertExists()
    }

    @Test
    fun `single-pane with no tabs renders the timeline slot compact as landing content`() {
        composeRule.setContent {
            SkeinApp(
                windowSizeClass = compactWidth,
                posture = FoldPosture.Unknown,
                timelinePane = { expanded, _, _ ->
                    Text(if (expanded) "TIMELINE_EXPANDED" else "TIMELINE_COMPACT")
                },
            )
        }

        composeRule.onNodeWithText("TIMELINE_COMPACT").assertExists()
        composeRule.onNodeWithText("TIMELINE_EXPANDED").assertDoesNotExist()
        composeRule.onNodeWithText("No tabs open — back to timeline").assertDoesNotExist()
    }

    @Test
    fun `opening a tab from the timeline slot replaces the landing content`() {
        composeRule.setContent {
            SkeinApp(
                windowSizeClass = compactWidth,
                posture = FoldPosture.Unknown,
                timelinePane = { _, onEntryOpen, _ ->
                    Text(
                        text = "OPEN_ENTRY",
                        modifier = Modifier.clickable { onEntryOpen("doc-1", "Note One") },
                    )
                },
                noteTabContent = { tab, _, _, _ -> Text("NOTE_CONTENT_${tab.docId}") },
            )
        }

        composeRule.onNodeWithText("OPEN_ENTRY").assertExists()

        composeRule.onNodeWithText("OPEN_ENTRY").performClick()

        composeRule.onNodeWithText("NOTE_CONTENT_doc-1").assertExists()
        composeRule.onNodeWithText("OPEN_ENTRY").assertDoesNotExist()
    }

    @Test
    fun `a null timeline slot keeps the previous placeholder behaviour`() {
        composeRule.setContent {
            SkeinApp(windowSizeClass = expandedWidth, posture = FoldPosture.Unknown)
        }

        composeRule.onNodeWithText("Timeline").assertExists()
        composeRule.onNodeWithText("No tabs open — back to timeline").assertExists()
    }

    @Test
    fun `a null timeline slot keeps the No tabs open landing in single-pane`() {
        composeRule.setContent {
            SkeinApp(windowSizeClass = compactWidth, posture = FoldPosture.Unknown)
        }

        composeRule.onNodeWithText("No tabs open — back to timeline").assertExists()
    }
}
