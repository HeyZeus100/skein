package app.skein.feature.shell.host

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.adaptive.HingeInfo
import androidx.compose.material3.adaptive.Posture
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.computeWindowSizeClass
import app.skein.core.designsystem.components.LocalSkeinWindowActive
import app.skein.core.designsystem.components.SkeinTooltip
import app.skein.core.designsystem.components.SkeinWindowPartitions
import app.skein.core.designsystem.icons.SkeinIcons
import app.skein.core.designsystem.theme.SkeinSize
import app.skein.core.designsystem.theme.SkeinSpacing
import app.skein.core.navigation.Destination
import app.skein.core.navigation.NewChatKey
import app.skein.core.navigation.SkeinId
import app.skein.feature.shell.container.ChatHistoryItem
import app.skein.feature.shell.container.SkeinDestination
import app.skein.feature.shell.container.SkeinNavigationContainer
import app.skein.feature.shell.container.SkeinSpace
import app.skein.feature.shell.layout.SecondarySurface
import app.skein.feature.shell.layout.SkeinLayoutDecision
import app.skein.feature.shell.layout.SkeinPosture
import app.skein.feature.shell.layout.currentSkeinWindowLayout
import app.skein.feature.shell.layout.skeinWindowLayout
import app.skein.feature.shell.layout.surfaceBounds
import app.skein.feature.shell.layout.windowPartitions
import kotlin.math.ceil

object WorkspaceTestTags {
    const val PRIMARY_PANE = "skein_workspace_primary"
    const val SECONDARY_PANE = "skein_workspace_secondary"
    const val TOGGLE_SPLIT = "skein_workspace_toggle_split"
    const val ACTIVATE_PRIMARY = "skein_workspace_activate_primary"
    const val ACTIVATE_SECONDARY = "skein_workspace_activate_secondary"
    const val SWAP_PANES = "skein_workspace_swap_panes"
}

internal data class WorkspacePaneEnvironment(
    val layout: SkeinLayoutDecision,
    val info: WindowAdaptiveInfo,
    val partitions: SkeinWindowPartitions?,
)

internal val LocalWorkspacePane = staticCompositionLocalOf<WorkspacePaneEnvironment?> { null }

/** One navigation container, independently owned content. Split visibility never changes a nav stack. */
@Composable
fun SkeinWorkspaceHost(
    workspace: SkeinWorkspaceState,
    history: List<ChatHistoryItem>,
    spaces: List<SkeinSpace>,
    searchEnabled: Boolean,
    modifier: Modifier = Modifier,
    windowAdaptiveInfo: WindowAdaptiveInfo = currentWindowAdaptiveInfoV2(),
    paneContent: @Composable (SkeinShellState) -> Unit,
) {
    val layout = currentSkeinWindowLayout(windowAdaptiveInfo)
    val active = workspace.activeShell
    val focus = LocalFocusManager.current
    DisposableEffect(workspace, focus) {
        workspace.beforeActivate = { focus.clearFocus(force = true) }
        onDispose { workspace.beforeActivate = null }
    }
    Surface(modifier.fillMaxSize()) {
        SkeinNavigationContainer(
            decision = layout,
            destination = SkeinDestination.valueOf(active.nav.topLevel.name),
            onNavigate = { destination -> active.navigate { switchTo(it, Destination.valueOf(destination.name)) } },
            onNewChat = { active.navigate { goTo(it, NewChatKey(SkeinId.random())) } },
            onSearch = { if (searchEnabled) active.openSearch() },
            history = history,
            spaces = spaces,
            closeRequest = active.drawerCloseRequest,
        ) {
            Column(
                Modifier.fillMaxSize().windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                    ),
                ),
            ) {
                WorkspaceControlsArea(layout) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = SkeinSize.touchTarget)
                            .padding(horizontal = SkeinSpacing.space8),
                        horizontalArrangement = Arrangement.spacedBy(SkeinSpacing.space8),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        repeat(2) { position ->
                            val pane = workspace.paneAtPosition(position)
                            WorkspaceIconButton(
                                onClick = { workspace.activate(pane) },
                                icon = if (position == 0) SkeinIcons.PaneLeft else SkeinIcons.PaneRight,
                                label = if (position == 0) "Left pane" else "Right pane",
                                showTooltip = layout.posture == SkeinPosture.Flat,
                                isSelected = workspace.activePane == pane,
                                modifier =
                                    Modifier.testTag(
                                        if (pane == WorkspacePane.PRIMARY) {
                                            WorkspaceTestTags.ACTIVATE_PRIMARY
                                        } else {
                                            WorkspaceTestTags.ACTIVATE_SECONDARY
                                        },
                                    ),
                            )
                        }
                        WorkspaceIconButton(
                            onClick = workspace::toggleSplit,
                            icon = SkeinIcons.Split,
                            label = if (workspace.splitRequested) "Hide split view" else "Show split view",
                            showTooltip = layout.posture == SkeinPosture.Flat,
                            isSelected = workspace.splitRequested,
                            modifier = Modifier.testTag(WorkspaceTestTags.TOGGLE_SPLIT),
                        )
                        WorkspaceIconButton(
                            onClick = workspace::swapPanes,
                            icon = SkeinIcons.Swap,
                            label = "Swap panes",
                            showTooltip = layout.posture == SkeinPosture.Flat,
                            modifier = Modifier.testTag(WorkspaceTestTags.SWAP_PANES),
                        )
                    }
                }
                WorkspacePanes(workspace, layout, windowAdaptiveInfo, Modifier.weight(1f), paneContent)
            }
        }
    }
}

/** Compact chrome keeps navigation visible without painting across either document. */
@Composable
private fun WorkspaceIconButton(
    onClick: () -> Unit,
    icon: Int,
    label: String,
    showTooltip: Boolean,
    modifier: Modifier = Modifier,
    isSelected: Boolean? = null,
) {
    val button: @Composable () -> Unit = {
        IconButton(
            onClick = onClick,
            colors =
                IconButtonDefaults.iconButtonColors(
                    containerColor =
                        if (isSelected == true) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
                    contentColor =
                        if (isSelected == true) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                ),
            modifier =
                modifier.size(SkeinSize.touchTarget).semantics {
                    contentDescription = label
                    if (isSelected != null) selected = isSelected
                },
        ) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(SkeinSize.iconStandard),
            )
        }
    }
    // The shared tooltip has a free-standing popup, not a hinge-partitioned surface.
    // Keep these optional hints off separating folds; the button still has its full accessible label.
    if (showTooltip) SkeinTooltip(label, content = button) else button()
}

/** Measure the whole control row before choosing a hinge-safe vertical slot. */
@Composable
private fun WorkspaceControlsArea(
    window: SkeinLayoutDecision,
    content: @Composable () -> Unit,
) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val direction = LocalLayoutDirection.current
    val bookBounds =
        if (window.posture is SkeinPosture.Book) {
            window.surfaceBounds(
                SecondarySurface.RENAME_DIALOG,
                direction,
            )
        } else {
            null
        }
    Layout(
        modifier = Modifier.fillMaxWidth().onGloballyPositioned { origin = it.positionInWindow() },
        content = content,
    ) { measurables, constraints ->
        val left = bookBounds?.let { ceil(it.left.toPx() - origin.x).toInt().coerceIn(0, constraints.maxWidth) } ?: 0
        val right =
            bookBounds?.let { (it.right.toPx() - origin.x).toInt().coerceIn(left, constraints.maxWidth) }
                ?: constraints.maxWidth
        val child =
            measurables.single().measure(
                constraints.copy(minWidth = right - left, maxWidth = right - left, minHeight = 0),
            )
        val hinge = (window.posture as? SkeinPosture.Tabletop)?.hinge
        val rowTop = origin.y
        val intersects = hinge != null && rowTop < hinge.bottom.toPx() && rowTop + child.height > hinge.top.toPx()
        val belowHinge =
            if (intersects) {
                ceil(
                    checkNotNull(hinge).bottom.toPx() - rowTop,
                ).toInt().coerceAtLeast(0)
            } else {
                0
            }
        val top = belowHinge.coerceAtMost((constraints.maxHeight - child.height).coerceAtLeast(0))
        layout(constraints.maxWidth, top + child.height) { child.place(left, top) }
    }
}

@Composable
private fun WorkspacePanes(
    workspace: SkeinWorkspaceState,
    window: SkeinLayoutDecision,
    windowInfo: WindowAdaptiveInfo,
    modifier: Modifier,
    content: @Composable (SkeinShellState) -> Unit,
) {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(modifier.fillMaxWidth().onGloballyPositioned { origin = it.positionInWindow() }) {
        val width = maxWidth
        val height = maxHeight
        val start = with(density) { origin.x.toDp() }
        val top = with(density) { origin.y.toDp() }
        val hinge = (window.posture as? SkeinPosture.Book)?.hinge
        val firstWidth = hinge?.let { (it.left - start).coerceIn(0.dp, width) } ?: (width / 2)
        val secondStart = hinge?.let { (it.right - start).coerceIn(0.dp, width) } ?: firstWidth
        val split =
            workspace.splitRequested &&
                height >= 600.dp &&
                firstWidth >= SkeinSize.detailPaneMin &&
                width - secondStart >= SkeinSize.detailPaneMin
        val rects =
            if (split) {
                listOf(
                    DpRect(start, top, start + firstWidth, top + height),
                    DpRect(
                        start + secondStart,
                        top,
                        start + width,
                        top + height,
                    ),
                )
            } else {
                List(2) { DpRect(start, top, start + width, top + height) }
            }
        Layout(
            modifier = Modifier.fillMaxSize(),
            content = {
                WorkspacePane.entries.forEach { pane ->
                    key(pane) {
                        val bounds = rects[workspace.positionOf(pane)]
                        val size = DpSize(bounds.right - bounds.left, bounds.bottom - bounds.top)
                        val posture = if (split && hinge != null) SkeinPosture.Flat else window.posture
                        val localHinges =
                            if (hinge !=
                                null
                            ) {
                                emptyList()
                            } else {
                                windowInfo.windowPosture.hingeList.map { feature ->
                                    HingeInfo(
                                        bounds =
                                            feature.bounds.translate(
                                                with(density) { Offset(-bounds.left.toPx(), -bounds.top.toPx()) },
                                            ),
                                        isFlat = feature.isFlat,
                                        isVertical = feature.isVertical,
                                        isSeparating = feature.isSeparating,
                                        isOccluding = feature.isOccluding,
                                    )
                                }
                            }
                        val info =
                            if (!split) {
                                windowInfo
                            } else {
                                WindowAdaptiveInfo(
                                    WindowSizeClass.BREAKPOINTS_V2.computeWindowSizeClass(
                                        size.width.value.toInt(),
                                        size.height.value.toInt(),
                                    ),
                                    Posture(isTabletop = windowInfo.windowPosture.isTabletop, hingeList = localHinges),
                                )
                            }
                        // The outer container already owns its rail: add that allowance only while
                        // calculating the content decision, then preserve real window geometry.
                        val preliminary = skeinWindowLayout(info, size, density)
                        val paneLayout =
                            if (!split) {
                                window
                            } else {
                                skeinWindowLayout(
                                    info,
                                    size.copy(
                                        width =
                                            size.width + preliminary.nav.width,
                                    ),
                                    density,
                                ).copy(size = window.size, posture = posture)
                            }
                        val originalPartitions = window.windowPartitions(direction)
                        val partitions =
                            if (!split) {
                                originalPartitions
                            } else {
                                originalPartitions?.takeIf { hinge == null }?.let {
                                    it.copy(
                                        reading = it.reading.intersect(bounds),
                                        confirmation = it.confirmation.intersect(bounds),
                                        anchors =
                                            it.anchors
                                                .map { anchor -> anchor.intersect(bounds) }
                                                .filter { anchor ->
                                                    anchor.right > anchor.left &&
                                                        anchor.bottom > anchor.top
                                                },
                                    )
                                } ?: SkeinWindowPartitions(
                                    window = DpRect(0.dp, 0.dp, window.size.width, window.size.height),
                                    reading = bounds,
                                    confirmation = bounds,
                                    anchors = listOf(bounds),
                                )
                            }
                        WorkspacePaneSlot(
                            workspace,
                            pane,
                            split || workspace.activePane == pane,
                            WorkspacePaneEnvironment(paneLayout, info, partitions),
                        ) { content(workspace.shell(pane)) }
                    }
                }
            },
        ) { measurables, constraints ->
            val first = with(density) { firstWidth.roundToPx() }
            val second = with(density) { secondStart.roundToPx() }
            val placeables =
                measurables.mapIndexed { index, measurable ->
                    val position = workspace.positionOf(WorkspacePane.entries[index])
                    val paneWidth =
                        if (!split) {
                            constraints.maxWidth
                        } else if (position ==
                            0
                        ) {
                            first
                        } else {
                            constraints.maxWidth - second
                        }
                    measurable.measure(Constraints.fixed(paneWidth.coerceAtLeast(0), constraints.maxHeight))
                }
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeables.forEachIndexed { index, placeable ->
                    val position = workspace.positionOf(WorkspacePane.entries[index])
                    if (split ||
                        workspace.activePane.ordinal == index
                    ) {
                        placeable.place(
                            if (split &&
                                position == 1
                            ) {
                                second
                            } else {
                                0
                            },
                            0,
                        )
                    }
                }
            }
        }
    }
}

private fun DpRect.intersect(other: DpRect): DpRect {
    val left = maxOf(left, other.left)
    val top = maxOf(top, other.top)
    return DpRect(left, top, maxOf(left, minOf(right, other.right)), maxOf(top, minOf(bottom, other.bottom)))
}

@Composable
private fun WorkspacePaneSlot(
    workspace: SkeinWorkspaceState,
    pane: WorkspacePane,
    visible: Boolean,
    environment: WorkspacePaneEnvironment,
    content: @Composable () -> Unit,
) {
    val active = workspace.activePane == pane
    val parent = checkNotNull(LocalNavigationEventDispatcherOwner.current)
    val dispatcher = remember(parent, pane) { NavigationEventDispatcher(parent.navigationEventDispatcher) }
    val owner =
        remember(dispatcher) {
            object : NavigationEventDispatcherOwner {
                override val navigationEventDispatcher = dispatcher
            }
        }
    SideEffect { dispatcher.isEnabled = active && visible }
    DisposableEffect(dispatcher) { onDispose { dispatcher.dispose() } }
    CompositionLocalProvider(
        LocalWorkspacePane provides environment,
        LocalNavigationEventDispatcherOwner provides owner,
        LocalSkeinWindowActive provides (active && visible),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .testTag(
                    if (pane ==
                        WorkspacePane.PRIMARY
                    ) {
                        WorkspaceTestTags.PRIMARY_PANE
                    } else {
                        WorkspaceTestTags.SECONDARY_PANE
                    },
                ).then(if (visible) Modifier else Modifier.clearAndSetSemantics { })
                .then(if (active && visible) Modifier else Modifier.focusProperties { canFocus = false })
                .pointerInput(workspace, pane) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.any { it.pressed && !it.previousPressed }) workspace.activate(pane)
                        }
                    }
                },
        ) { content() }
    }
}
