// skein-xtov.24.7 (AL-08): the Navigation 3 shell host (ADAPTIVE_LAYOUT_SPEC.md
// §8, as amended by §8.9 "What AL-06, AL-07 and AL-08 must do differently").
// `NavDisplay` sits in AL-07's container content slot, at one composition
// position across drawer ↔ rail (§8.9 item 10), over one decorated entry list
// per destination (item 2) and the §8.4 scene chain with Skein's directive.
// AL-09a/b replace the placeholder entries with the real screens.
package app.skein.feature.shell.host

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.HingePolicy
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation.BackNavigationBehavior
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.SupportingPaneSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberSupportingPaneSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import app.skein.core.designsystem.components.LocalSkeinWindowPartitions
import app.skein.core.designsystem.theme.SkeinSize
import app.skein.core.model.Document
import app.skein.core.navigation.ChatContextKey
import app.skein.core.navigation.ChatHomeKey
import app.skein.core.navigation.ConnectionsKey
import app.skein.core.navigation.Destination
import app.skein.core.navigation.GraphNodeKey
import app.skein.core.navigation.NavMode
import app.skein.core.navigation.NewChatKey
import app.skein.core.navigation.ObjectKind
import app.skein.core.navigation.PaneRole
import app.skein.core.navigation.SkeinId
import app.skein.core.navigation.SkeinKey
import app.skein.core.navigation.TransientKey
import app.skein.core.navigation.TransientKind
import app.skein.core.navigation.contentKey
import app.skein.core.navigation.destination
import app.skein.core.navigation.role
import app.skein.feature.shell.container.ChatHistoryItem
import app.skein.feature.shell.container.SkeinDestination
import app.skein.feature.shell.container.SkeinNavigationContainer
import app.skein.feature.shell.container.SkeinSpace
import app.skein.feature.shell.layout.SecondarySurface
import app.skein.feature.shell.layout.SkeinHingeSafeArea
import app.skein.feature.shell.layout.SkeinLayoutDecision
import app.skein.feature.shell.layout.SkeinNavContainer
import app.skein.feature.shell.layout.SkeinPosture
import app.skein.feature.shell.layout.currentSkeinWindowLayout
import app.skein.feature.shell.layout.surfaceBounds
import app.skein.feature.shell.layout.windowPartitions
import app.skein.feature.shell.testing.ShellTestTags
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException

/** The window decision, for entries (§8.6: "entries read `LocalSkeinWindowLayout` to decide"). */
val LocalSkeinWindowLayout = staticCompositionLocalOf<SkeinLayoutDecision> { error("no SkeinShellHost above") }

object SkeinShellHostTestTags {
    const val NAV_DISPLAY = "skein_shell_nav_display"
}

/**
 * The shell below `VaultGate` (§7.7 step 3, M4): composed only once the vault
 * is open. It first sanitises the hoisted stacks through [resolveKinds] (B8:
 * content-free), dropping every id that no longer resolves, and renders
 * nothing until that is done; only then does `NavDisplay` compose.
 *
 * [entryContent] renders one key; [detailPlaceholder] fills an empty detail
 * pane beside a destination's list. [search], when given, backs the search
 * overlay the drawer's search row and [SkeinShellState.openSearch] open.
 * [now] and [zone] date the drawer's history groups alongside its supplied rows.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun SkeinShellHost(
    shell: SkeinShellState,
    resolveKinds: suspend (Set<SkeinId>) -> Map<SkeinId, ObjectKind>,
    modifier: Modifier = Modifier,
    history: List<ChatHistoryItem> = emptyList(),
    spaces: List<SkeinSpace> = emptyList(),
    detailPlaceholder: @Composable (Destination) -> Unit = { PlaceholderEntry(null) },
    search: (suspend (String) -> List<Document>)? = null,
    zone: ZoneId = ZoneId.systemDefault(),
    now: () -> Long = System::currentTimeMillis,
    onNavigationReady: () -> Unit = {},
    windowAdaptiveInfo: WindowAdaptiveInfo = currentWindowAdaptiveInfoV2(),
    entryContent: @Composable (SkeinKey) -> Unit = { PlaceholderEntry(it) },
) {
    val resolver by rememberUpdatedState(resolveKinds)
    val navigationReady by rememberUpdatedState(onNavigationReady)
    var sanitised by remember { mutableStateOf(false) }
    // M4d: before the first entry renders. A failed lookup resolves nothing, so the stacks fail closed to their roots.
    LaunchedEffect(Unit) {
        val ids = shell.navigator.referencedIds(shell.nav)
        val kinds =
            try {
                if (ids.isEmpty()) emptyMap() else resolver(ids)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyMap()
            }
        shell.navigate { sanitise(it, kinds::get) }
        // AL-14: a pending notification applies after restore, before any destination renders.
        navigationReady()
        sanitised = true
    }
    if (!sanitised) return

    val workspacePane = LocalWorkspacePane.current
    val info = workspacePane?.info ?: windowAdaptiveInfo
    val layout = workspacePane?.layout ?: currentSkeinWindowLayout(info)
    val layoutDirection = LocalLayoutDirection.current
    val mode = layout.navMode()
    val top = shell.nav.topLevel
    val supportsList = top == Destination.CHAT || top == Destination.KNOWLEDGE
    val listCollapsed = supportsList && layout.maxPanes > 1 && !shell.isListExpanded(top)
    val extraVisible = shell.nav.currentStack.lastOrNull()?.role == PaneRole.EXTRA
    val directive = remember(info, layout, supportsList, listCollapsed, extraVisible) {
        skeinDirective(info, layout).let {
            it.copy(
                maxHorizontalPartitions = if (listCollapsed) { if (extraVisible) 2 else 1 } else it.maxHorizontalPartitions,
                defaultPanePreferredWidth = if (supportsList && !extraVisible && layout.posture == SkeinPosture.Flat) {
                    SkeinSize.sidePaneMin
                } else {
                    it.defaultPanePreferredWidth
                },
            )
        }
    }
    // §2.5, §7.4 item 2: a sheet expanded on one pane becomes a pane on two, and a later shrink shows the peek.
    LaunchedEffect(layout.maxPanes) { if (layout.maxPanes > 1) shell.sheets.collapseAll() }

    val content by rememberUpdatedState(entryContent)
    val placeholder by rememberUpdatedState(detailPlaceholder)
    val showRootPlaceholder by rememberUpdatedState(listCollapsed)
    val provider =
        remember {
            // No `entryProvider {}` DSL: its fallback throws "Unknown screen $key", a key in an exception (M13).
            { key: SkeinKey ->
                NavEntry(
                    key,
                    key.contentKey,
                    metadataOf(key) { destination ->
                        HingeEntryPane(key) { EntryInsets { placeholder(destination) } }
                    },
                ) {
                    HingeEntryPane(it) {
                        EntryInsets {
                            CompositionLocalProvider(LocalEntryIsList provides (it.role == PaneRole.LIST && !showRootPlaceholder)) {
                                if (showRootPlaceholder && it.role == PaneRole.LIST) placeholder(it.destination) else content(it)
                            }
                        }
                    }
                }
            }
        }
    // One decorated list per destination, on every composition (§8.9 item 2): switching destinations is not
    // a pop, so the other stacks keep their T2 and T3. Each destination has its own T2 holder and T3 scope,
    // because a content key is unique only within its stack.
    val entries =
        Destination.entries.associateWith { d ->
            key(d) {
                val decorators =
                    listOf(
                        rememberSaveableStateHolderNavEntryDecorator<SkeinKey>(shell.entryState.getValue(d)),
                        remember(shell.stores) { sessionEntryDecorator(shell.stores, d) },
                    )
                // Presenting Phone's draft alone must not pop the hidden Conversations state.
                val stack = shell.nav.stack(d)
                val visible =
                    shell.navigator
                        .visibleStack(stack, mode)
                        .map { it.contentKey }
                        .toSet()
                rememberDecoratedNavEntries(stack, decorators, provider).filter { it.contentKey in visible }
            }
        }
    val strategies =
        listOf<SceneStrategy<SkeinKey>>(
            SkeinSheetSceneStrategy(layout, shell.sheets.expandedKeys, shell.sheets::expand, layoutDirection),
            // A bare draft replaces the landing placeholder, so Back must leave it to the system.
            // Other details still pop even when `List | Detail` and `List | placeholder` share geometry.
            rememberListDetailSceneStrategy<SkeinKey>(
                shouldHandleSinglePaneLayout = listCollapsed,
                backNavigationBehavior =
                    if (shell.navigator.isChatLandingRoot(shell.nav.currentStack, mode)) {
                        BackNavigationBehavior.PopUntilScaffoldValueChange
                    } else {
                        BackNavigationBehavior.PopUntilContentChange
                    },
                directive = directive,
            ),
            rememberSupportingPaneSceneStrategy<SkeinKey>(directive = directive),
        )

    CompositionLocalProvider(
        LocalSkeinWindowLayout provides layout,
        LocalSkeinWindowPartitions provides (if (workspacePane != null) workspacePane.partitions else layout.windowPartitions(layoutDirection)),
    ) {
        Surface(modifier.fillMaxSize().testTag(ShellTestTags.SKEIN_SHELL_ROOT)) {
            val display: @Composable () -> Unit = {
                // §8.3 rule 6b: Back at another destination's root goes to Chat; at the Chat root, to the system.
                NavigationBackHandler(
                    state = rememberNavigationEventState(NavigationEventInfo.None),
                    isBackEnabled = top != Destination.CHAT && shell.nav.currentStack.size == 1,
                    onBackCompleted = { shell.navigate { switchTo(it, Destination.CHAT) } },
                )
                NavDisplay(
                    entries = entries.getValue(top),
                    modifier = Modifier.fillMaxSize().testTag(SkeinShellHostTestTags.NAV_DISPLAY),
                    sceneStrategies = strategies,
                    transitionSpec = { crossFade() },
                    popTransitionSpec = { crossFade() },
                    onBack = { shell.navigate { back(it, mode) } },
                )
            }
            if (workspacePane != null) {
                display()
            } else {
                SkeinNavigationContainer(
                    decision = layout,
                    destination = SkeinDestination.valueOf(top.name),
                    onNavigate = { d -> shell.navigate { switchTo(it, Destination.valueOf(d.name)) } },
                    onNewChat = { shell.navigate { goTo(it, NewChatKey(SkeinId.random())) } },
                    onSearch = { if (search != null) shell.openSearch() },
                    history = history,
                    spaces = spaces,
                    zone = zone,
                    now = now,
                    closeRequest = shell.drawerCloseRequest,
                    content = display,
                )
            }
            if (shell.searchOpen && search != null) {
                // Search is modal across the window, while its reading/input surface stays
                // in one hinge partition. The full-window surface also blocks the entries below.
                Surface(Modifier.fillMaxSize()) {
                    SkeinHingeSafeArea(
                        if (layout.posture == SkeinPosture.Flat) {
                            null
                        } else {
                            layout.surfaceBounds(SecondarySurface.COMMAND_PALETTE, layoutDirection)
                        },
                        Modifier.fillMaxSize(),
                    ) {
                        EntryInsets {
                            SkeinSearchOverlay(
                                search = search,
                                onOpen = { document ->
                                    shell.closeSearch()
                                    shell.open(document)
                                },
                                onDismiss = shell::closeSearch,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** AL-06's navigation mode for this window: a drawer is Phone; with a rail, the pane count. */
fun SkeinLayoutDecision.navMode(): NavMode =
    when {
        nav == SkeinNavContainer.DRAWER -> NavMode.PHONE
        maxPanes >= 3 -> NavMode.TRIPLE
        maxPanes == 2 -> NavMode.DUAL
        else -> NavMode.SINGLE
    }

/** Skein's directive (§2.3, §8.4): Material's, with Skein's pane count, spacer and side-pane width. */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
internal fun skeinDirective(
    info: WindowAdaptiveInfo,
    layout: SkeinLayoutDecision,
): PaneScaffoldDirective =
    calculatePaneScaffoldDirective(info, HingePolicy.AvoidSeparating).copy(
        maxHorizontalPartitions = layout.maxPanes,
        horizontalPartitionSpacerSize = SkeinSize.paneSpacer,
        defaultPanePreferredWidth = layout.sidePaneWidth,
    )

/** §8.2's role column; the scene key is the key's destination, so a Knowledge detail never pairs with the Chat list. */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
private fun metadataOf(
    key: SkeinKey,
    placeholder: @Composable (Destination) -> Unit,
): Map<String, Any> {
    val scene = key.destination
    val sheet = sheetSurfaceOf(key)?.let { mapOf(SHEET_METADATA_KEY to it) }.orEmpty()
    return when (key.role) {
        PaneRole.LIST -> ListDetailSceneStrategy.listPane(scene) { placeholder(scene) }
        PaneRole.DETAIL -> ListDetailSceneStrategy.detailPane(scene)
        PaneRole.EXTRA -> ListDetailSceneStrategy.extraPane(scene) + sheet
        PaneRole.MAIN -> SupportingPaneSceneStrategy.mainPane(scene)
        PaneRole.SUPPORTING -> SupportingPaneSceneStrategy.supportingPane(scene) + sheet
    }
}

/** The §2.5 sheet surfaces. An opened source is a full-screen route on one pane, never a sheet. */
internal fun sheetSurfaceOf(key: SkeinKey): SecondarySurface? =
    when {
        key is ChatContextKey -> SecondarySurface.CONTEXT_INSPECTOR
        key is ConnectionsKey || (key is TransientKey && key.kind == TransientKind.CONNECTIONS) ->
            SecondarySurface.CONNECTIONS
        key is GraphNodeKey || (key is TransientKey && key.kind == TransientKind.GRAPH_NODE) ->
            SecondarySurface.NODE_DETAIL
        else -> null
    }

// §2.7 and §8.9 item 8: a 150 ms cross-fade between scenes.
private fun crossFade(): ContentTransform = fadeIn(tween(FADE_MILLIS)) togetherWith fadeOut(tween(FADE_MILLIS))

private const val FADE_MILLIS = 150

/**
 * AL-08's stand-in entry for a destination not re-hosted yet (AL-09b's): the
 * destination and the pane, never an id or a title (M5, M13). The Chat root
 * follows §8.2: the landing in a drawer window, the Conversations list with a rail.
 */
@Composable
fun PlaceholderEntry(key: SkeinKey?) {
    val label =
        when {
            key == null -> "Nothing selected"
            key == ChatHomeKey && LocalSkeinWindowLayout.current.nav == SkeinNavContainer.DRAWER -> "New chat"
            key == ChatHomeKey -> "Conversations"
            else -> "${SkeinDestination.valueOf(key.destination.name).label} · ${key.role.name.lowercase()}"
        }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(label) }
}
