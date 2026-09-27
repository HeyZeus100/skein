// skein-xtov.24.6 (AL-07, UX Wave 3): the navigation rail's contents
// (ADAPTIVE_LAYOUT_SPEC.md §3.4): ✎ New chat at top, then the five
// destinations with Settings pinned to the bottom. There is no chat
// history in the rail (spec §3.4) — it lives in the Conversations list
// pane (AL-08) or the Chat root screen on a Single window.
package app.skein.feature.shell.container

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.skein.core.designsystem.components.SkeinTooltip
import app.skein.core.designsystem.components.skeinFocusRing
import app.skein.core.designsystem.icons.SkeinIcons
import app.skein.core.designsystem.theme.SkeinSize

/** Spec §3.4: "New chat (primary action, 56 dp, label … for TalkBack and tooltip)". */
private val NEW_CHAT_BUTTON_SIZE = 56.dp

/**
 * The rail (spec §3.4, §3.2). [expanded] selects the 240 dp wide-rail width
 * (`SkeinNavContainer.EXPANDED_RAIL`, XL windows only); it still lays out
 * icon-over-label like the 80 dp collapsed rail.
 *
 * ponytail: the true "wide" rail (icon + label side by side,
 * `WideNavigationRail`, material3 1.4.0, still experimental) isn't built —
 * XL is the rarest tier and isn't in this bead's required goldens. Upgrade
 * [expanded] to a real wide-rail layout if/when AL-08 needs XL parity.
 */
@Composable
fun SkeinRailContent(
    destination: SkeinDestination,
    onNavigate: (SkeinDestination) -> Unit,
    onNewChat: () -> Unit,
    spaces: List<SkeinSpace>,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
) {
    val (top, bottom) = SkeinDestination.entries.partition { it != SkeinDestination.SETTINGS }
    NavigationRail(
        modifier = if (expanded) modifier.width(SkeinSize.railExpanded) else modifier,
        header = {
            SkeinSpaceSwitcher(spaces, compact = true)
            SkeinTooltip("New chat") {
                FilledIconButton(
                    onClick = onNewChat,
                    modifier = Modifier.size(NEW_CHAT_BUTTON_SIZE).skeinFocusRing(),
                ) {
                    Icon(
                        painter = painterResource(SkeinIcons.NewChat),
                        contentDescription = "New chat",
                        modifier = Modifier.size(SkeinSize.iconStandard),
                    )
                }
            }
        },
    ) {
        top.forEach { d -> RailItem(d, destination, onNavigate) }
        Spacer(Modifier.weight(1f))
        bottom.forEach { d -> RailItem(d, destination, onNavigate) }
    }
}

@Composable
private fun RailItem(
    d: SkeinDestination,
    current: SkeinDestination,
    onNavigate: (SkeinDestination) -> Unit,
) {
    val selected = d == current
    NavigationRailItem(
        selected = selected,
        onClick = { onNavigate(d) },
        icon = {
            Icon(
                painter = painterResource(if (selected) d.iconSelected else d.icon),
                contentDescription = null,
            )
        },
        label = { Text(d.label) },
        alwaysShowLabel = true,
    )
}
