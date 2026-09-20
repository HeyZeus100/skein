package app.skein.feature.shell.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `E6.I2` state-transition tests for [AdaptiveLayoutState] in isolation from Compose/layout. */
class AdaptiveLayoutStateTest {
    @Test
    fun `default state starts with the timeline fully expanded`() {
        val state = AdaptiveLayoutState()

        assertEquals(TimelineMode.FULL, state.timelineMode)
    }

    @Test
    fun `default state starts with split disabled`() {
        val state = AdaptiveLayoutState()

        assertFalse(state.splitEnabled)
    }

    @Test
    fun `default state starts with no active tab`() {
        val state = AdaptiveLayoutState()

        assertNull(state.activeTabId)
    }

    @Test
    fun `toggling the timeline from full collapses it to the rail`() {
        val state = AdaptiveLayoutState(initialTimelineMode = TimelineMode.FULL)

        state.toggleTimeline()

        assertEquals(TimelineMode.RAIL, state.timelineMode)
    }

    @Test
    fun `toggling the timeline from the rail restores it to full`() {
        val state = AdaptiveLayoutState(initialTimelineMode = TimelineMode.RAIL)

        state.toggleTimeline()

        assertEquals(TimelineMode.FULL, state.timelineMode)
    }

    @Test
    fun `enabling split sets the split flag`() {
        val state = AdaptiveLayoutState(initialSplitEnabled = false)

        state.toggleSplit()

        assertTrue(state.splitEnabled)
    }

    @Test
    fun `enabling split from a full timeline collapses it to the rail`() {
        val state = AdaptiveLayoutState(initialTimelineMode = TimelineMode.FULL, initialSplitEnabled = false)

        state.toggleSplit()

        assertEquals(TimelineMode.RAIL, state.timelineMode)
    }

    @Test
    fun `disabling split restores the timeline mode from before split was enabled`() {
        val state = AdaptiveLayoutState(initialTimelineMode = TimelineMode.FULL, initialSplitEnabled = false)
        state.toggleSplit()

        state.toggleSplit()

        assertEquals(TimelineMode.FULL, state.timelineMode)
    }

    @Test
    fun `disabling split clears the split flag`() {
        val state = AdaptiveLayoutState(initialSplitEnabled = true)

        state.toggleSplit()

        assertFalse(state.splitEnabled)
    }

    @Test
    fun `toggling the timeline while split is enabled is a no-op`() {
        val state = AdaptiveLayoutState(initialTimelineMode = TimelineMode.FULL, initialSplitEnabled = false)
        state.toggleSplit()

        state.toggleTimeline()

        assertEquals(TimelineMode.RAIL, state.timelineMode)
    }

    @Test
    fun `opening a tab sets it as the active tab`() {
        val state = AdaptiveLayoutState()

        state.openTab("chat-1")

        assertEquals("chat-1", state.activeTabId)
    }
}
