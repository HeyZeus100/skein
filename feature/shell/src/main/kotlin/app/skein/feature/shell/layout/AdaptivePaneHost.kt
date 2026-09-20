package app.skein.feature.shell.layout

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
 */
@Composable
fun AdaptivePaneHost(
    layoutState: AdaptiveLayoutState,
    modifier: Modifier = Modifier,
    windowSizeClass: WindowSizeClass = currentWindowAdaptiveInfoV2().windowSizeClass,
    posture: FoldPosture = rememberFoldPosture().value,
    timeline: @Composable () -> Unit,
    primary: @Composable () -> Unit,
    secondary: @Composable () -> Unit = primary,
) {
    val tokens = LocalSkeinTokens.current
    val widthClass = classifyWidth(windowSizeClass)
    val result = computeAdaptiveLayout(widthClass, posture, layoutState)

    Row(modifier = modifier.fillMaxSize()) {
        when (result.timelineMode) {
            TimelineMode.FULL -> {
                Column(
                    modifier = Modifier.weight(tokens.timelineShare).fillMaxHeight(),
                ) { timeline() }
                RightZone(
                    modifier = Modifier.weight(1f - tokens.timelineShare).fillMaxHeight(),
                    result = result,
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
    primary: @Composable () -> Unit,
    secondary: @Composable () -> Unit,
) {
    Column(modifier = modifier) {
        if (result.useTabDropdown) {
            RecentTabsDropdownPlaceholder()
        }
        when (result.paneLayoutState) {
            PaneLayoutState.SPLIT_DUAL ->
                SplitHost(
                    modifier = Modifier.fillMaxSize(),
                    leftContent = primary,
                    rightContent = secondary,
                )
            PaneLayoutState.SINGLE_PANE, PaneLayoutState.DUAL_PANE ->
                Column(modifier = Modifier.fillMaxSize()) { primary() }
        }
    }
}

/**
 * Placeholder for spec §8.3's "Folded phone: tabs → 'Recent ▾' dropdown".
 * The real tab list/menu is `E6.I5`; this only marks where it slots in so
 * the 600–840dp breakpoint is visibly distinct in previews.
 */
@Composable
private fun RecentTabsDropdownPlaceholder(modifier: Modifier = Modifier) {
    Text(
        text = "Recent ▾",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(8.dp),
    )
}
