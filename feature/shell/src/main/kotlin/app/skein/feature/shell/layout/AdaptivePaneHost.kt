package app.skein.feature.shell.layout

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.window.core.layout.WindowSizeClass
import app.skein.feature.shell.theme.LocalSkeinTokens

/**
 * Top-level adaptive layout host for the shell (spec §8.2/§8.3, plan
 * `E6.I2`). Reads fold posture + window width, derives one of
 * [PaneLayoutState.SINGLE_PANE] / [PaneLayoutState.DUAL_PANE] /
 * [PaneLayoutState.SPLIT_DUAL] via [computeAdaptiveLayout], and lays out
 * the [timeline] / [primary] / [secondary] slots accordingly. It owns no
 * screen content — those slots are filled by callers (real screens land in
 * `E6.I4`+); this host only arranges panes.
 *
 * @param layoutState user-driven timeline/split/tab preferences (survives
 *   posture changes and process death via [rememberAdaptiveLayoutState]).
 * @param windowSizeClass current width/height tier; defaults to
 *   [currentWindowAdaptiveInfoV2] so callers only need to pass this
 *   explicitly in tests/previews that force a size.
 * @param posture current fold posture; defaults to [rememberFoldPosture].
 * @param secondary content for the second split pane; only composed when
 *   [PaneLayoutState.SPLIT_DUAL] is active. Defaults to [primary] so a
 *   caller that doesn't yet have two tabs still gets something to look at.
 *   Always invoked with `splitAvailable = false` (see [RightZone]) — v1
 *   never offers splitting *within* the secondary pane (plan `E6.I6`).
 *
 * [primary] and [secondary] each receive `splitAvailable: Boolean` — true
 * only at [LayoutWidthClass.EXPANDED] width (spec `E6.I6`: "Split view only
 * appears at unfolded widths"), i.e. whenever [PaneLayoutState.SINGLE_PANE]
 * isn't what was computed — so a `TabHost` filling either slot knows
 * whether to offer its own `⧉` button / "Open in split" menu item without
 * recomputing width/posture itself.
 */
@Composable
fun AdaptivePaneHost(
    layoutState: AdaptiveLayoutState,
    modifier: Modifier = Modifier,
    windowSizeClass: WindowSizeClass = currentWindowAdaptiveInfoV2().windowSizeClass,
    posture: FoldPosture = rememberFoldPosture().value,
    timeline: @Composable () -> Unit,
    primary: @Composable (splitAvailable: Boolean) -> Unit,
    secondary: @Composable (splitAvailable: Boolean) -> Unit = primary,
) {
    val tokens = LocalSkeinTokens.current
    val widthClass = classifyWidth(windowSizeClass)
    val result = computeAdaptiveLayout(widthClass, posture, layoutState)
    val splitAvailable = result.paneLayoutState != PaneLayoutState.SINGLE_PANE

    Row(modifier = modifier.fillMaxSize()) {
        when (result.timelineMode) {
            TimelineMode.FULL -> {
                Column(
                    modifier = Modifier.weight(tokens.timelineShare).fillMaxHeight(),
                ) { timeline() }
                RightZone(
                    modifier = Modifier.weight(1f - tokens.timelineShare).fillMaxHeight(),
                    result = result,
                    splitAvailable = splitAvailable,
                    primary = primary,
                    secondary = secondary,
                )
            }
            TimelineMode.RAIL -> {
                IconRail(
                    onExpand = layoutState::toggleTimeline,
                    modifier = Modifier.fillMaxHeight(),
                )
                RightZone(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    result = result,
                    splitAvailable = splitAvailable,
                    primary = primary,
                    secondary = secondary,
                )
            }
            TimelineMode.HIDDEN -> {
                // Compact/medium/tabletop: only the right zone is composed at
                // all — no zero-width timeline placeholder (acceptance
                // criterion: "width 400 dp → only the right pane").
                RightZone(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    result = result,
                    splitAvailable = splitAvailable,
                    primary = primary,
                    secondary = secondary,
                )
            }
        }
    }
}

@Composable
private fun RightZone(
    modifier: Modifier,
    result: AdaptiveLayoutResult,
    splitAvailable: Boolean,
    primary: @Composable (Boolean) -> Unit,
    secondary: @Composable (Boolean) -> Unit,
) {
    // `E6.I5`: the tab chrome (wide strip vs. folded-phone "Recent ▾"
    // dropdown) is `TabHost`'s own concern now, driven by its own
    // `windowSizeClass`. `primary`/`secondary` are expected to each be a
    // `TabHost` (one per split side) — this zone only arranges panes, same
    // as before tabs existed.
    Column(modifier = modifier) {
        when (result.paneLayoutState) {
            PaneLayoutState.SPLIT_DUAL ->
                SplitHost(
                    modifier = Modifier.fillMaxSize(),
                    leftContent = { primary(splitAvailable) },
                    // No splitting *within* the right pane in v1 (plan `E6.I6`
                    // guardrail): the secondary pane never offers its own ⧉.
                    rightContent = { secondary(false) },
                )
            PaneLayoutState.SINGLE_PANE, PaneLayoutState.DUAL_PANE ->
                Column(modifier = Modifier.fillMaxSize()) { primary(splitAvailable) }
        }
    }
}
