package app.skein.feature.shell.split

import app.skein.feature.shell.layout.AdaptiveLayoutState
import app.skein.feature.shell.layout.TimelineMode
import app.skein.feature.shell.tabs.Tab
import app.skein.feature.shell.tabs.TabId
import app.skein.feature.shell.tabs.TabKind
import app.skein.feature.shell.tabs.TabState
import app.skein.feature.shell.tabs.TabsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun tab(
    id: String,
    docId: String = id,
    title: String = id,
    kind: TabKind = TabKind.NOTE,
    state: TabState = TabState.PINNED,
) = Tab(TabId(id), docId, title, kind, state)

/** `E6.I6` tests for [SplitCoordinator] bridging [TabsState] and [AdaptiveLayoutState]. */
class SplitCoordinatorTest {
    @Test
    fun `opening in split removes the tab from the primary pane`() {
        val primary = TabsState(initialTabs = listOf(tab("a")))
        val secondary = TabsState()
        val coordinator = SplitCoordinator(primary, secondary, AdaptiveLayoutState())

        coordinator.openInSplit(TabId("a"))

        assertTrue(primary.tabs.isEmpty())
    }

    @Test
    fun `opening in split places the tab into the secondary pane`() {
        val primary = TabsState(initialTabs = listOf(tab("a")))
        val secondary = TabsState()
        val coordinator = SplitCoordinator(primary, secondary, AdaptiveLayoutState())

        coordinator.openInSplit(TabId("a"))

        assertEquals(listOf(TabId("a")), secondary.tabs.map { it.id })
    }

    @Test
    fun `opening in split activates the moved tab in the secondary pane`() {
        val primary = TabsState(initialTabs = listOf(tab("a")))
        val secondary = TabsState()
        val coordinator = SplitCoordinator(primary, secondary, AdaptiveLayoutState())

        coordinator.openInSplit(TabId("a"))

        assertEquals(TabId("a"), secondary.activeId)
    }

    @Test
    fun `opening in split enables split on the layout state`() {
        val primary = TabsState(initialTabs = listOf(tab("a")))
        val secondary = TabsState()
        val layoutState = AdaptiveLayoutState()
        val coordinator = SplitCoordinator(primary, secondary, layoutState)

        coordinator.openInSplit(TabId("a"))

        assertTrue(layoutState.splitEnabled)
    }

    @Test
    fun `opening in split collapses the timeline`() {
        val primary = TabsState(initialTabs = listOf(tab("a")))
        val secondary = TabsState()
        val layoutState = AdaptiveLayoutState(initialTimelineMode = TimelineMode.FULL)
        val coordinator = SplitCoordinator(primary, secondary, layoutState)

        coordinator.openInSplit(TabId("a"))

        assertEquals(TimelineMode.RAIL, layoutState.timelineMode)
    }

    @Test
    fun `opening in split leaves the primary pane's other active tab untouched`() {
        val primary = TabsState(initialTabs = listOf(tab("a"), tab("b")))
        primary.activate(TabId("b"))
        val secondary = TabsState()
        val coordinator = SplitCoordinator(primary, secondary, AdaptiveLayoutState())

        coordinator.openInSplit(TabId("a"))

        assertEquals(TabId("b"), primary.activeId)
    }

    @Test
    fun `opening a tab that is already in the secondary pane is a no-op for split state`() {
        val primary = TabsState(initialTabs = listOf(tab("a")))
        val secondary = TabsState(initialTabs = listOf(tab("s")))
        val layoutState = AdaptiveLayoutState()
        val coordinator = SplitCoordinator(primary, secondary, layoutState)

        coordinator.openInSplit(TabId("s"))

        assertFalse(layoutState.splitEnabled)
    }

    @Test
    fun `opening a tab that is already in the secondary pane does not duplicate it`() {
        val primary = TabsState(initialTabs = listOf(tab("a")))
        val secondary = TabsState(initialTabs = listOf(tab("s")))
        val coordinator = SplitCoordinator(primary, secondary, AdaptiveLayoutState())

        coordinator.openInSplit(TabId("s"))

        assertEquals(1, secondary.tabs.size)
    }

    @Test
    fun `closing the only tab in the secondary pane exits split`() {
        val primary = TabsState(initialTabs = listOf(tab("a")))
        val secondary = TabsState()
        val layoutState = AdaptiveLayoutState()
        val coordinator = SplitCoordinator(primary, secondary, layoutState)
        coordinator.openInSplit(TabId("a"))
        secondary.close(TabId("a"))

        coordinator.exitSplitIfSecondaryEmpty()

        assertFalse(layoutState.splitEnabled)
    }

    @Test
    fun `closing the only tab in the secondary pane restores the prior timeline mode`() {
        val primary = TabsState(initialTabs = listOf(tab("a")))
        val secondary = TabsState()
        val layoutState = AdaptiveLayoutState(initialTimelineMode = TimelineMode.FULL)
        val coordinator = SplitCoordinator(primary, secondary, layoutState)
        coordinator.openInSplit(TabId("a"))
        secondary.close(TabId("a"))

        coordinator.exitSplitIfSecondaryEmpty()

        assertEquals(TimelineMode.FULL, layoutState.timelineMode)
    }

    @Test
    fun `exiting split leaves the secondary pane with no active tab`() {
        val primary = TabsState(initialTabs = listOf(tab("a")))
        val secondary = TabsState()
        val layoutState = AdaptiveLayoutState()
        val coordinator = SplitCoordinator(primary, secondary, layoutState)
        coordinator.openInSplit(TabId("a"))
        secondary.close(TabId("a"))

        coordinator.exitSplitIfSecondaryEmpty()

        assertNull(secondary.activeId)
    }

    @Test
    fun `exiting split is a no-op while the secondary pane still has tabs`() {
        val primary = TabsState(initialTabs = listOf(tab("a")))
        val secondary = TabsState()
        val layoutState = AdaptiveLayoutState()
        val coordinator = SplitCoordinator(primary, secondary, layoutState)
        coordinator.openInSplit(TabId("a"))

        coordinator.exitSplitIfSecondaryEmpty()

        assertTrue(layoutState.splitEnabled)
    }

    @Test
    fun `exiting split when split was never enabled is a no-op`() {
        val primary = TabsState()
        val secondary = TabsState()
        val layoutState = AdaptiveLayoutState()
        val coordinator = SplitCoordinator(primary, secondary, layoutState)

        coordinator.exitSplitIfSecondaryEmpty()

        assertFalse(layoutState.splitEnabled)
    }

    @Test
    fun `opening an unknown tab id does not enable split`() {
        val primary = TabsState(initialTabs = listOf(tab("a")))
        val secondary = TabsState()
        val layoutState = AdaptiveLayoutState()
        val coordinator = SplitCoordinator(primary, secondary, layoutState)

        coordinator.openInSplit(TabId("does-not-exist"))

        assertFalse(layoutState.splitEnabled)
    }
}
