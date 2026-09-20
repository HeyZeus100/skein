package app.skein.feature.shell.layout

import androidx.window.core.layout.WindowSizeClass

/**
 * The three responsive width tiers this issue cares about (spec `E6.I2`):
 * `< 600dp`, `600–840dp`, `>= 840dp`. These are exactly the `window-core`
 * `WindowSizeClass` medium/expanded breakpoints, so [classifyWidth] just
 * reads them off [WindowSizeClass.isWidthAtLeastBreakpoint].
 */
enum class LayoutWidthClass { COMPACT, MEDIUM, EXPANDED }

fun classifyWidth(windowSizeClass: WindowSizeClass): LayoutWidthClass {
    val expandedBound = WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND
    val mediumBound = WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND
    return when {
        windowSizeClass.isWidthAtLeastBreakpoint(expandedBound) -> LayoutWidthClass.EXPANDED
        windowSizeClass.isWidthAtLeastBreakpoint(mediumBound) -> LayoutWidthClass.MEDIUM
        else -> LayoutWidthClass.COMPACT
    }
}

/** The three top-level pane arrangements `AdaptivePaneHost` can render (spec §8.2/§8.3). */
enum class PaneLayoutState { SINGLE_PANE, DUAL_PANE, SPLIT_DUAL }

/**
 * Everything [AdaptivePaneHost] needs to decide what to draw, derived
 * (never stored) from width + posture + the user's [AdaptiveLayoutState].
 */
data class AdaptiveLayoutResult(
    val paneLayoutState: PaneLayoutState,
    val timelineMode: TimelineMode,
    /** Spec §8.3: "Folded phone: tabs → 'Recent ▾' dropdown" — the 600–840dp tier. */
    val useTabDropdown: Boolean,
)

/**
 * Pure decision function: width tier + fold posture + the persisted
 * [AdaptiveLayoutState] in, one [AdaptiveLayoutResult] out. No I/O, no
 * Compose — trivially unit-testable, which is the point (posture changes
 * must never mutate [AdaptiveLayoutState] itself; they only change what
 * this function returns for the *next* recomposition).
 */
fun computeAdaptiveLayout(
    widthClass: LayoutWidthClass,
    posture: FoldPosture,
    layoutState: AdaptiveLayoutState,
): AdaptiveLayoutResult {
    // Tabletop (horizontal hinge, half-open) splits the screen top/bottom, not
    // left/right: a side-by-side dual pane wouldn't fit the usable area, so
    // v1 falls back to single-pane regardless of width class.
    val forcedSingle = posture.isTabletop

    return when {
        widthClass == LayoutWidthClass.COMPACT || forcedSingle ->
            AdaptiveLayoutResult(
                paneLayoutState = PaneLayoutState.SINGLE_PANE,
                timelineMode = TimelineMode.HIDDEN,
                useTabDropdown = false,
            )
        widthClass == LayoutWidthClass.MEDIUM ->
            AdaptiveLayoutResult(
                paneLayoutState = PaneLayoutState.SINGLE_PANE,
                timelineMode = TimelineMode.HIDDEN,
                useTabDropdown = true,
            )
        layoutState.splitEnabled ->
            AdaptiveLayoutResult(
                paneLayoutState = PaneLayoutState.SPLIT_DUAL,
                timelineMode = layoutState.timelineMode,
                useTabDropdown = false,
            )
        else ->
            AdaptiveLayoutResult(
                paneLayoutState = PaneLayoutState.DUAL_PANE,
                timelineMode = layoutState.timelineMode,
                useTabDropdown = false,
            )
    }
}
