package app.skein.core.designsystem.theme

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * skein-xtov.23.5 (DS5): every value below is exactly
 * `docs/ux/DESIGN_SYSTEM.md` §4.1 (spacing scale), §4.4 (component sizes) and
 * §4.2 (per-window-class gutter/pane count/top bar).
 */
class SkeinSpaceSizeLayoutTest {
    @Test
    fun `spacing scale matches §4-1`() {
        assertEquals(2.dp, SkeinSpacing.space2)
        assertEquals(4.dp, SkeinSpacing.space4)
        assertEquals(8.dp, SkeinSpacing.space8)
        assertEquals(12.dp, SkeinSpacing.space12)
        assertEquals(16.dp, SkeinSpacing.space16)
        assertEquals(20.dp, SkeinSpacing.space20)
        assertEquals(24.dp, SkeinSpacing.space24)
        assertEquals(32.dp, SkeinSpacing.space32)
        assertEquals(40.dp, SkeinSpacing.space40)
        assertEquals(48.dp, SkeinSpacing.space48)
        assertEquals(64.dp, SkeinSpacing.space64)
    }

    @Test
    fun `component sizes match §4-4`() {
        assertEquals(48.dp, SkeinSize.touchTarget)
        assertEquals(24.dp, SkeinSize.iconStandard)
        assertEquals(20.dp, SkeinSize.iconDense)
        assertEquals(16.dp, SkeinSize.iconInline)
        assertEquals(18.dp, SkeinSize.iconChip)
        assertEquals(40.dp, SkeinSize.buttonHeight)
        assertEquals(32.dp, SkeinSize.chipHeight)
        assertEquals(56.dp, SkeinSize.rowOneLine)
        assertEquals(72.dp, SkeinSize.rowTwoLine)
        assertEquals(88.dp, SkeinSize.rowThreeLine)
        assertEquals(48.dp, SkeinSize.rowDrawerHistory)
        assertEquals(64.dp, SkeinSize.rowPaneConversation)
        assertEquals(64.dp, SkeinSize.topBar)
        assertEquals(56.dp, SkeinSize.composerMin)
        assertEquals(80.dp, SkeinSize.rail)
        assertEquals(320.dp, SkeinSize.listPane)
        assertEquals(320.dp, SkeinSize.extraPane)
        assertEquals(360.dp, SkeinSize.extraPaneLarge)
        assertEquals(320.dp, SkeinSize.drawerMax)
        assertEquals(640.dp, SkeinSize.sheetMaxWidth)
        assertEquals(640.dp, SkeinSize.paletteMaxWidth)
        assertEquals(560.dp, SkeinSize.dialogMaxWidth)
        assertEquals(576.dp, SkeinSize.readingMax)
        assertEquals(1.dp, SkeinSize.hairline)
        assertEquals(2.dp, SkeinSize.focusRing)
        assertEquals(2.dp, SkeinSize.focusRingGap)
        assertEquals(8.dp, SkeinSize.statusDot)
    }

    @Test
    fun `every window class resolves the §4-2 gutter, pane count and top bar`() {
        assertEquals(SkeinWindowLayout(gutter = 16.dp, paneCount = 1, topBarHeight = 64.dp), SkeinLayout.compact)
        assertEquals(
            SkeinWindowLayout(gutter = 16.dp, paneCount = 1, topBarHeight = 56.dp),
            SkeinLayout.compactHeight,
        )
        assertEquals(SkeinWindowLayout(gutter = 24.dp, paneCount = 1, topBarHeight = 64.dp), SkeinLayout.medium)
        assertEquals(SkeinWindowLayout(gutter = 24.dp, paneCount = 2, topBarHeight = 64.dp), SkeinLayout.expanded)
        assertEquals(SkeinWindowLayout(gutter = 24.dp, paneCount = 3, topBarHeight = 64.dp), SkeinLayout.large)
        assertEquals(12.dp, SkeinLayout.expandedListPaneGutter)
    }

    @Test
    fun `SkeinLayout-of dispatches every window class to its own spec`() {
        SkeinWindowClass.entries.forEach { windowClass ->
            val expected =
                when (windowClass) {
                    SkeinWindowClass.COMPACT -> SkeinLayout.compact
                    SkeinWindowClass.COMPACT_HEIGHT -> SkeinLayout.compactHeight
                    SkeinWindowClass.MEDIUM -> SkeinLayout.medium
                    SkeinWindowClass.EXPANDED -> SkeinLayout.expanded
                    SkeinWindowClass.LARGE -> SkeinLayout.large
                }
            assertEquals(windowClass.name, expected, SkeinLayout.of(windowClass))
        }
    }
}
