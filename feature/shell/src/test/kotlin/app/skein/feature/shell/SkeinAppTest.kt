package app.skein.feature.shell

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.window.core.layout.WindowSizeClass
import app.skein.feature.shell.layout.AdaptiveLayoutState
import app.skein.feature.shell.layout.AdaptivePaneHost
import app.skein.feature.shell.layout.FoldPosture
import app.skein.feature.shell.layout.TimelineMode
import app.skein.feature.shell.nav.Destination
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
        composeRule.onNodeWithText(EMPTY_PANE).assertExists()
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
        composeRule.onNodeWithText(EMPTY_PANE).assertDoesNotExist()
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
        composeRule.onNodeWithText(EMPTY_PANE).assertExists()
    }

    @Test
    fun `a null timeline slot keeps the No tabs open landing in single-pane`() {
        composeRule.setContent {
            SkeinApp(windowSizeClass = compactWidth, posture = FoldPosture.Unknown)
        }

        composeRule.onNodeWithText(EMPTY_PANE).assertExists()
    }

    // ---- skein-0td0: the `overlay` slot -----------------------------------------

    @Test
    fun `the overlay slot renders above the rest of the shell content`() {
        composeRule.setContent {
            SkeinApp(
                windowSizeClass = compactWidth,
                posture = FoldPosture.Unknown,
                overlay = { _, _ -> Text("OVERLAY_CONTENT") },
            )
        }

        // The shell root (nav/command bar/pane host) is still composed
        // underneath — the overlay is additive, not a replacement.
        composeRule.onNodeWithText(EMPTY_PANE).assertExists()
        composeRule.onNodeWithText("OVERLAY_CONTENT").assertExists()
    }

    @Test
    fun `the overlay slot's openPreview opens a real preview tab on the primary TabsState`() {
        composeRule.setContent {
            SkeinApp(
                windowSizeClass = compactWidth,
                posture = FoldPosture.Unknown,
                overlay = { openPreview, _ ->
                    Text(
                        text = "OPEN_FROM_OVERLAY",
                        modifier = Modifier.clickable { openPreview("doc-1", "Note One") },
                    )
                },
                noteTabContent = { tab, _, _, _ -> Text("NOTE_CONTENT_${tab.docId}") },
            )
        }

        composeRule.onNodeWithText("OPEN_FROM_OVERLAY").performClick()

        composeRule.onNodeWithText("NOTE_CONTENT_doc-1").assertExists()
    }

    @Test
    fun `the overlay slot's openPinned opens a real pinned tab on the primary TabsState`() {
        composeRule.setContent {
            SkeinApp(
                windowSizeClass = compactWidth,
                posture = FoldPosture.Unknown,
                overlay = { _, openPinned ->
                    Text(
                        text = "PIN_FROM_OVERLAY",
                        modifier = Modifier.clickable { openPinned("doc-2", "Note Two") },
                    )
                },
                noteTabContent = { tab, _, _, _ -> Text("NOTE_CONTENT_${tab.docId}") },
            )
        }

        composeRule.onNodeWithText("PIN_FROM_OVERLAY").performClick()

        composeRule.onNodeWithText("NOTE_CONTENT_doc-2").assertExists()
    }

    @Test
    fun `a null overlay slot renders nothing extra`() {
        composeRule.setContent {
            SkeinApp(windowSizeClass = compactWidth, posture = FoldPosture.Unknown)
        }

        composeRule.onNodeWithText(EMPTY_PANE).assertExists()
    }

    // ---- skein-5cr5: emptyContent consults navState.destination ---------------

    private fun navigateViaDrawer(label: String) {
        composeRule.onNodeWithContentDescription("Open navigation drawer").performClick()
        composeRule.onNodeWithText(label, substring = true).performClick()
    }

    @Test
    fun `no tabs open with destination SETTINGS renders destinationContent in single-pane`() {
        composeRule.setContent {
            SkeinApp(
                windowSizeClass = compactWidth,
                posture = FoldPosture.Unknown,
                destinationContent = { destination -> Text("DEST_${destination.name}") },
            )
        }

        navigateViaDrawer("Settings")

        composeRule.onNodeWithText("DEST_${Destination.SETTINGS.name}").assertExists()
        composeRule.onNodeWithText(EMPTY_PANE).assertDoesNotExist()
    }

    @Test
    fun `no tabs open with destination SETTINGS renders destinationContent in dual-pane`() {
        composeRule.setContent {
            SkeinApp(
                windowSizeClass = expandedWidth,
                posture = FoldPosture.Unknown,
                destinationContent = { destination -> Text("DEST_${destination.name}") },
            )
        }

        navigateViaDrawer("Settings")

        composeRule.onNodeWithText("DEST_${Destination.SETTINGS.name}").assertExists()
        composeRule.onNodeWithText(EMPTY_PANE).assertDoesNotExist()
    }

    @Test
    fun `navigating back to destination TIMELINE keeps the landing pane, not destinationContent`() {
        composeRule.setContent {
            SkeinApp(
                windowSizeClass = compactWidth,
                posture = FoldPosture.Unknown,
                destinationContent = { destination -> Text("DEST_${destination.name}") },
            )
        }

        navigateViaDrawer("Settings")
        composeRule.onNodeWithText("DEST_${Destination.SETTINGS.name}").assertExists()

        navigateViaDrawer("Timeline")

        composeRule.onNodeWithText(EMPTY_PANE).assertExists()
        composeRule.onNodeWithText("DEST_${Destination.SETTINGS.name}").assertDoesNotExist()
    }

    // UX-P0-03 / AL-01: this used to pin the defect ("an open tab still wins
    // over a non-TIMELINE destination"); drawer items now work with a tab open.
    @Test
    fun `drawer Settings shows Settings while a tab is open`() {
        composeRule.setContent {
            SkeinApp(
                windowSizeClass = compactWidth,
                posture = FoldPosture.Unknown,
                destinationContent = { destination -> Text("DEST_${destination.name}") },
                timelinePane = { _, onEntryOpen, _ ->
                    Text(
                        text = "OPEN_ENTRY",
                        modifier = Modifier.clickable { onEntryOpen("doc-1", "Note One") },
                    )
                },
                noteTabContent = { tab, _, _, _ -> Text("NOTE_CONTENT_${tab.docId}") },
            )
        }

        composeRule.onNodeWithText("OPEN_ENTRY").performClick()
        composeRule.onNodeWithText("NOTE_CONTENT_doc-1").assertExists()

        navigateViaDrawer("Settings")

        composeRule.onNodeWithText("DEST_${Destination.SETTINGS.name}").assertExists()
        composeRule.onNodeWithText("NOTE_CONTENT_doc-1").assertDoesNotExist()

        // Timeline is the way home: the landing comes back, the tab stays open.
        navigateViaDrawer("Timeline")

        composeRule.onNodeWithText("OPEN_ENTRY").assertExists()
        composeRule.onNodeWithText("Recent", substring = true).assertExists()
    }

    // ---- Stage H (skein-xtov.22): hide the dead ------------------------------

    @Test
    fun `the drawer offers only destinations that have a screen`() {
        composeRule.setContent {
            SkeinApp(windowSizeClass = compactWidth, posture = FoldPosture.Unknown)
        }

        composeRule.onNodeWithContentDescription("Open navigation drawer").performClick()

        composeRule.onNodeWithText("Timeline", substring = true).assertExists()
        composeRule.onNodeWithText("Settings", substring = true).assertExists()
        listOf("Notes", "Graph", "Personas").forEach { label ->
            composeRule.onNodeWithText(label, substring = true).assertDoesNotExist()
        }
    }

    @Test
    fun `split view has no dead icon rail`() {
        composeRule.setContent {
            AdaptivePaneHost(
                layoutState = AdaptiveLayoutState(initialSplitEnabled = true, initialTimelineMode = TimelineMode.RAIL),
                windowSizeClass = expandedWidth,
                posture = FoldPosture.Unknown,
                timeline = { Text("TIMELINE") },
                primary = { Text("PRIMARY") },
                secondary = { Text("SECONDARY") },
            )
        }

        composeRule.onNodeWithText("PRIMARY").assertExists()
        composeRule.onNodeWithText("SECONDARY").assertExists()
        listOf("Expand timeline", "Timeline", "Graph", "Personas", "Settings").forEach { label ->
            composeRule.onNodeWithContentDescription(label).assertDoesNotExist()
        }
    }

    private companion object {
        const val EMPTY_PANE = "Open a chat or note from the list"
    }
}
