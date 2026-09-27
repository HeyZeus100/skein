package app.skein.core.designsystem.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The window classes the layout grid is keyed on (spec §4.2). Breakpoint
 * *decisions* (which pane shows when) belong to `ADAPTIVE_LAYOUT_SPEC.md`;
 * these are the measurement buckets that spec's classes resolve to.
 */
enum class SkeinWindowClass {
    /** Phones 360–411 dp; Fold outer 443 dp stock, ≈ 524 dp at 330 dpi. */
    COMPACT,

    /** < 600 dp tall (outer landscape). */
    COMPACT_HEIGHT,

    /** 600–839 dp. */
    MEDIUM,

    /** 840–1199 dp; Fold inner 852 dp stock, 1006–1043 dp at 330 dpi. */
    EXPANDED,

    /** ≥ 1200 dp. */
    LARGE,
}

/** One window class's grid measurements (spec §4.2 table). */
data class SkeinWindowLayout(
    /** Horizontal gutter for the primary/detail pane. Expanded's list pane uses [SkeinLayout.expandedListPaneGutter] instead. */
    val gutter: Dp,
    /** Panes shown at once — Skein has no inner column grid, only the IA's panes. */
    val paneCount: Int,
    /** Minimum top app bar height for this class; grows with font scale (§3.5). */
    val topBarHeight: Dp,
)

/**
 * Per-window-class gutters, pane counts and top-bar heights (spec §4.2,
 * DS5). Skein does not use a column grid inside content: inside a pane,
 * content aligns to one reading column (capped at [SkeinSize.readingMax])
 * plus the [SkeinSpacing] baseline, and only the *panes* change by window
 * class.
 */
object SkeinLayout {
    val compact = SkeinWindowLayout(gutter = 16.dp, paneCount = 1, topBarHeight = 64.dp)

    /** Outer-landscape posture: the top bar shrinks; the caller adds side insets on top of [gutter]. */
    val compactHeight = SkeinWindowLayout(gutter = 16.dp, paneCount = 1, topBarHeight = 56.dp)
    val medium = SkeinWindowLayout(gutter = 24.dp, paneCount = 1, topBarHeight = 64.dp)

    /** List pane + detail. The list pane itself uses [expandedListPaneGutter], not [SkeinWindowLayout.gutter]. */
    val expanded = SkeinWindowLayout(gutter = 24.dp, paneCount = 2, topBarHeight = 64.dp)

    /** List pane + detail + supporting pane ([SkeinSize.extraPaneLarge] wide). */
    val large = SkeinWindowLayout(gutter = 24.dp, paneCount = 3, topBarHeight = 64.dp)

    /** Expanded's list-pane gutter: its rows are inset pills at a tighter gutter than the 24 dp detail pane uses. */
    val expandedListPaneGutter: Dp = 12.dp

    /**
     * Skein's height gate (`ADAPTIVE_LAYOUT_SPEC.md` §2.4): a window shorter
     * than this is "short" — a drawer and one pane, whatever its width. It
     * catches the closed Fold in landscape (1175 × 524 at 330 dpi), which
     * Material classes as Medium height and would give two panes.
     */
    val twoPaneMinHeight: Dp = 600.dp

    fun of(windowClass: SkeinWindowClass): SkeinWindowLayout =
        when (windowClass) {
            SkeinWindowClass.COMPACT -> compact
            SkeinWindowClass.COMPACT_HEIGHT -> compactHeight
            SkeinWindowClass.MEDIUM -> medium
            SkeinWindowClass.EXPANDED -> expanded
            SkeinWindowClass.LARGE -> large
        }
}
