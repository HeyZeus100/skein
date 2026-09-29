package app.skein.feature.shell.container

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRailDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import app.skein.core.designsystem.components.SkeinTooltip
import app.skein.core.designsystem.components.skeinFocusRing
import app.skein.core.designsystem.icons.SkeinIcons
import app.skein.core.designsystem.theme.SkeinSize
import app.skein.feature.shell.layout.hasFiniteCoordinates
import kotlin.math.roundToInt

private enum class RailSlot { MEASURE, TOP, BOTTOM, SETTINGS }

/**
 * Wave-3 placement only: normal Material controls, not the P2 tabletop redesign.
 * Actual label heights determine the split. Compact spacing precedes overflow to
 * the lower partition; labels and touch targets are never shrunk. Each partition
 * scrolls independently and clips input/drawing at its edge. Settings stays outside
 * those scroll regions at the bottom (or above the crease when the lower region
 * cannot hold its touch target). The ordinary flat rail is a separate unchanged path.
 */
@Composable
internal fun SkeinTabletopRail(
    destination: SkeinDestination,
    onNavigate: (SkeinDestination) -> Unit,
    onNewChat: () -> Unit,
    spaces: List<SkeinSpace>,
    hinge: DpRect,
    modifier: Modifier,
    expanded: Boolean,
) {
    val controls =
        buildList {
            if (spaces.size >= 2) add(RailControl.Space)
            add(RailControl.NewChat)
            SkeinDestination.entries.filter { it != SkeinDestination.SETTINGS }.forEach {
                add(
                    RailControl.Destination(it),
                )
            }
        }
    var origin by remember { mutableStateOf<Offset?>(null) }
    Surface(
        modifier.width(if (expanded) SkeinSize.railExpanded else SkeinSize.rail).fillMaxHeight(),
        color = NavigationRailDefaults.ContainerColor,
    ) {
        SubcomposeLayout(
            Modifier
                .windowInsetsPadding(NavigationRailDefaults.windowInsets)
                .padding(vertical = 8.dp)
                .onGloballyPositioned { origin = it.positionInWindow() },
        ) { constraints ->
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val position = origin
            val itemConstraints = Constraints(maxWidth = width)
            val gap = 8.dp.roundToPx()
            val probes =
                subcompose(RailSlot.MEASURE) {
                    controls.forEach { control ->
                        // These unplaced measurement-only nodes expose no actions or source text.
                        Box(Modifier.clearAndSetSemantics {}) {
                            RailControlContent(control, destination, onNavigate, onNewChat, spaces)
                        }
                    }
                }.map { it.measure(itemConstraints) }
            val settings =
                subcompose(RailSlot.SETTINGS) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        RailItem(SkeinDestination.SETTINGS, destination, onNavigate)
                    }
                }.single().measure(itemConstraints)
            val valid = hinge.hasFiniteCoordinates()
            val bandStart =
                if (valid && position != null) {
                    (
                        minOf(
                            hinge.top,
                            hinge.bottom,
                        ).toPx() - position.y
                    ).roundToInt().coerceIn(0, height)
                } else {
                    height
                }
            val bandEnd =
                if (valid && position != null) {
                    (
                        maxOf(
                            hinge.top,
                            hinge.bottom,
                        ).toPx() - position.y
                    ).roundToInt().coerceIn(bandStart, height)
                } else {
                    height
                }
            val settingsBelow = height - bandEnd >= settings.height
            val settingsY = ((if (settingsBelow) height else bandStart) - settings.height).coerceAtLeast(0)
            val topHeight = if (settingsBelow) bandStart else (settingsY - gap).coerceAtLeast(0)
            val bottomHeight = if (settingsBelow) (settingsY - gap - bandEnd).coerceAtLeast(0) else 0

            fun prefixThatFits(spacing: Int): Int {
                var used = 0
                var count = 0
                for (probe in probes) {
                    val next = used + (if (count == 0) 0 else spacing) + probe.height
                    if (next > topHeight) break
                    used = next
                    count++
                }
                return count
            }
            val regularCount = prefixThatFits(gap)
            val compactCount = prefixThatFits(0)
            val spacing = if (compactCount > regularCount) 0 else gap
            // If the lower region is unavailable, the top region scrolls all controls.
            val topCount =
                if (bottomHeight <
                    (probes.maxOfOrNull { it.height } ?: 0)
                ) {
                    controls.size
                } else {
                    maxOf(regularCount, compactCount)
                }
            val top =
                subcompose(RailSlot.TOP) {
                    RailPartition(
                        controls.take(topCount),
                        destination,
                        onNavigate,
                        onNewChat,
                        spaces,
                        spacing.toDp(),
                        "top",
                    )
                }.single().measure(Constraints.fixed(width, topHeight))
            val bottom =
                subcompose(RailSlot.BOTTOM) {
                    RailPartition(controls.drop(topCount), destination, onNavigate, onNewChat, spaces, 8.dp, "bottom")
                }.single().measure(Constraints.fixed(width, bottomHeight))
            layout(width, height) {
                // Do not expose a target using a guessed zero origin on the first frame.
                if (position != null) {
                    top.place(0, 0)
                    bottom.place(0, bandEnd)
                    if (settings.height <= (if (settingsBelow) height - bandEnd else bandStart)) {
                        settings.place((width - settings.width) / 2, settingsY)
                    }
                }
            }
        }
    }
}

private sealed interface RailControl {
    data object Space : RailControl

    data object NewChat : RailControl

    data class Destination(
        val destination: SkeinDestination,
    ) : RailControl
}

@Composable
private fun RailPartition(
    controls: List<RailControl>,
    current: SkeinDestination,
    onNavigate: (SkeinDestination) -> Unit,
    onNewChat: () -> Unit,
    spaces: List<SkeinSpace>,
    spacing: androidx.compose.ui.unit.Dp,
    partition: String,
) {
    Column(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .verticalScroll(rememberScrollState())
            .testTag("skein_rail_$partition"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        controls.forEach { RailControlContent(it, current, onNavigate, onNewChat, spaces) }
    }
}

@Composable
private fun RailControlContent(
    control: RailControl,
    current: SkeinDestination,
    onNavigate: (SkeinDestination) -> Unit,
    onNewChat: () -> Unit,
    spaces: List<SkeinSpace>,
) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        when (control) {
            RailControl.Space -> SkeinSpaceSwitcher(spaces, compact = true)
            RailControl.NewChat ->
                SkeinTooltip("New chat") {
                    FilledIconButton(onNewChat, Modifier.size(56.dp).skeinFocusRing()) {
                        Icon(
                            painterResource(SkeinIcons.NewChat),
                            contentDescription = "New chat",
                            modifier = Modifier.size(SkeinSize.iconStandard),
                        )
                    }
                }
            is RailControl.Destination -> RailItem(control.destination, current, onNavigate)
        }
    }
}
