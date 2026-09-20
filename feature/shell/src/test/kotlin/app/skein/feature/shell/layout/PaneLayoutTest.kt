package app.skein.feature.shell.layout

import androidx.window.core.layout.WindowSizeClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `E6.I2` state-transition tests: fold posture + width tier → layout state.
 * Pure functions, no Compose runtime / Robolectric needed.
 */
class PaneLayoutTest {
    @Test
    fun `width just under 600dp classifies as COMPACT`() {
        val windowSizeClass = WindowSizeClass(599f, 900f)

        val result = classifyWidth(windowSizeClass)

        assertEquals(LayoutWidthClass.COMPACT, result)
    }

    @Test
    fun `width at the 600dp boundary classifies as MEDIUM`() {
        val windowSizeClass = WindowSizeClass(600f, 900f)

        val result = classifyWidth(windowSizeClass)

        assertEquals(LayoutWidthClass.MEDIUM, result)
    }

    @Test
    fun `width just under 840dp classifies as MEDIUM`() {
        val windowSizeClass = WindowSizeClass(839f, 900f)

        val result = classifyWidth(windowSizeClass)

        assertEquals(LayoutWidthClass.MEDIUM, result)
    }

    @Test
    fun `width at the 840dp boundary classifies as EXPANDED`() {
        val windowSizeClass = WindowSizeClass(840f, 900f)

        val result = classifyWidth(windowSizeClass)

        assertEquals(LayoutWidthClass.EXPANDED, result)
    }

    @Test
    fun `compact width yields single pane`() {
        val result = computeAdaptiveLayout(LayoutWidthClass.COMPACT, FoldPosture.Unknown, AdaptiveLayoutState())

        assertEquals(PaneLayoutState.SINGLE_PANE, result.paneLayoutState)
    }

    @Test
    fun `compact width never shows the tab dropdown`() {
        val result = computeAdaptiveLayout(LayoutWidthClass.COMPACT, FoldPosture.Unknown, AdaptiveLayoutState())

        assertFalse(result.useTabDropdown)
    }

    @Test
    fun `medium width yields single pane`() {
        val result = computeAdaptiveLayout(LayoutWidthClass.MEDIUM, FoldPosture.Unknown, AdaptiveLayoutState())

        assertEquals(PaneLayoutState.SINGLE_PANE, result.paneLayoutState)
    }

    @Test
    fun `medium width shows the tab dropdown`() {
        val result = computeAdaptiveLayout(LayoutWidthClass.MEDIUM, FoldPosture.Unknown, AdaptiveLayoutState())

        assertTrue(result.useTabDropdown)
    }

    @Test
    fun `expanded width with split disabled yields dual pane`() {
        val layoutState = AdaptiveLayoutState(initialSplitEnabled = false)

        val result = computeAdaptiveLayout(LayoutWidthClass.EXPANDED, FoldPosture.Unknown, layoutState)

        assertEquals(PaneLayoutState.DUAL_PANE, result.paneLayoutState)
    }

    @Test
    fun `expanded width with split enabled yields split dual`() {
        val layoutState = AdaptiveLayoutState(initialSplitEnabled = true)

        val result = computeAdaptiveLayout(LayoutWidthClass.EXPANDED, FoldPosture.Unknown, layoutState)

        assertEquals(PaneLayoutState.SPLIT_DUAL, result.paneLayoutState)
    }

    @Test
    fun `expanded width never shows the tab dropdown`() {
        val result = computeAdaptiveLayout(LayoutWidthClass.EXPANDED, FoldPosture.Unknown, AdaptiveLayoutState())

        assertFalse(result.useTabDropdown)
    }

    @Test
    fun `tabletop posture forces single pane even at expanded width`() {
        val tabletop = FoldPosture(FoldPostureState.HALF_OPENED, FoldOrientation.HORIZONTAL)

        val result = computeAdaptiveLayout(LayoutWidthClass.EXPANDED, tabletop, AdaptiveLayoutState())

        assertEquals(PaneLayoutState.SINGLE_PANE, result.paneLayoutState)
    }

    @Test
    fun `book posture (vertical hinge) does not force single pane at expanded width`() {
        val book = FoldPosture(FoldPostureState.HALF_OPENED, FoldOrientation.VERTICAL)

        val result = computeAdaptiveLayout(LayoutWidthClass.EXPANDED, book, AdaptiveLayoutState())

        assertEquals(PaneLayoutState.DUAL_PANE, result.paneLayoutState)
    }

    @Test
    fun `a posture change never mutates the layout state's timeline mode`() {
        val layoutState = AdaptiveLayoutState(initialTimelineMode = TimelineMode.FULL)
        val flat = FoldPosture(FoldPostureState.FLAT, FoldOrientation.VERTICAL)
        val halfOpened = FoldPosture(FoldPostureState.HALF_OPENED, FoldOrientation.VERTICAL)

        computeAdaptiveLayout(LayoutWidthClass.EXPANDED, flat, layoutState)
        computeAdaptiveLayout(LayoutWidthClass.EXPANDED, halfOpened, layoutState)

        assertEquals(TimelineMode.FULL, layoutState.timelineMode)
    }

    @Test
    fun `a posture change never mutates the layout state's split flag`() {
        val layoutState = AdaptiveLayoutState(initialSplitEnabled = true)
        val flat = FoldPosture(FoldPostureState.FLAT, FoldOrientation.VERTICAL)
        val halfOpened = FoldPosture(FoldPostureState.HALF_OPENED, FoldOrientation.VERTICAL)

        computeAdaptiveLayout(LayoutWidthClass.EXPANDED, flat, layoutState)
        computeAdaptiveLayout(LayoutWidthClass.EXPANDED, halfOpened, layoutState)

        assertTrue(layoutState.splitEnabled)
    }
}
