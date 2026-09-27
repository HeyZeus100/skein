package app.skein.core.designsystem.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Component sizes (spec §4.4, DS5), plus the touch-target minimum (§7.1) and
 * hairline/focus-ring geometry (§5.4). Values are the resting/visual size;
 * [touchTarget] is the minimum hit area every interactive element must meet
 * even when its visual size (e.g. [chipHeight]) is smaller than that.
 */
object SkeinSize {
    /** Minimum hit area for every interactive element (§7.1, Material `minimumInteractiveComponentSize`). */
    val touchTarget: Dp = 48.dp

    /** Standard icon size: most icons and toolbars. */
    val iconStandard: Dp = 24.dp

    /** Dense rows and chips. */
    val iconDense: Dp = 20.dp

    /** Activity status marks. */
    val iconInline: Dp = 16.dp

    /** Material chip icons undershoot [iconDense]. */
    val iconChip: Dp = 18.dp

    /** Visual height; touch area is [touchTarget]. Grows with font scale. */
    val buttonHeight: Dp = 40.dp

    /** Visual height; touch area is [touchTarget]. */
    val chipHeight: Dp = 32.dp

    /** Material list rows. */
    val rowOneLine: Dp = 56.dp
    val rowTwoLine: Dp = 72.dp
    val rowThreeLine: Dp = 88.dp

    /** Denser rows for chat history (§10.2). */
    val rowDrawerHistory: Dp = 48.dp
    val rowPaneConversation: Dp = 64.dp

    /** Minimum top app bar height; grows with font scale (§3.5). */
    val topBar: Dp = 64.dp

    /** Minimum composer height; grows to 6 lines, then scrolls. */
    val composerMin: Dp = 56.dp

    /** Collapsed navigation rail width (IA §3.4). */
    val rail: Dp = 80.dp

    /** Expanded navigation rail width, XL windows only (`ADAPTIVE_LAYOUT_SPEC.md` §2.4). */
    val railExpanded: Dp = 240.dp

    /** List pane width on Expanded and Large. */
    val listPane: Dp = 320.dp

    /** Narrowest list or extra pane: a title plus a date stays legible at font scale 1.0. */
    val sidePaneMin: Dp = 280.dp

    /** Narrowest detail pane the layout's width guard allows: chat bubbles plus the composer. */
    val detailPaneMin: Dp = 360.dp

    /** Detail width that earns side panes [listPane] instead of [sidePaneMin]. */
    val detailPaneComfort: Dp = 480.dp

    /** Gap between panes (Material's partition spacer); it hosts the pane-expansion drag handle. */
    val paneSpacer: Dp = 24.dp

    /** Extra pane (context inspector, Connections) width on Expanded. [extraPaneLarge] is Large's own width. */
    val extraPane: Dp = 320.dp

    /** Extra/third pane width on the Large window class (wider than [extraPane]). */
    val extraPaneLarge: Dp = 360.dp

    /** Modal nav drawer max width; also capped at `window − 56` (§4.2). */
    val drawerMax: Dp = 320.dp

    val sheetMaxWidth: Dp = 640.dp
    val paletteMaxWidth: Dp = 640.dp
    val dialogMaxWidth: Dp = 560.dp

    /** Cap for prose, measured at 79 characters / 16 sp (§4.3). */
    val readingMax: Dp = 576.dp

    val hairline: Dp = 1.dp
    val focusRing: Dp = 2.dp
    val focusRingGap: Dp = 2.dp

    /** Always paired with text; never colour alone. */
    val statusDot: Dp = 8.dp
}
