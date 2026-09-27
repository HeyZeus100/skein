// skein-xtov.24.4 (AL-05, throwaway): §8.8's wiring sketch made to run. The
// stacks, the entry SaveableStateHolder (T2) and the session entry stores
// (T3) sit above the gate; NavDisplay renders the current destination through
// the §8.4 chain with Skein's directive (§2.3, from `skeinWindowLayout`).
package app.skein.prototype.nav3

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.HingePolicy
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation.BackNavigationBehavior
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberSupportingPaneSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpSize
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import app.skein.core.designsystem.theme.SkeinSize
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.core.model.IndexStore
import app.skein.core.model.VaultRepository
import app.skein.feature.chat.SendPipeline
import app.skein.feature.shell.layout.SkeinLayoutDecision
import app.skein.feature.shell.layout.SkeinNavContainer
import app.skein.feature.shell.layout.skeinWindowLayout
import kotlinx.coroutines.launch

/** What the hosted screens need; tests pass fakes. */
class ProtoDeps(
    val vault: VaultRepository,
    val sendPipeline: SendPipeline,
    val indexStore: IndexStore,
    val chats: List<SkeinId>,
    val notes: List<SkeinId>,
)

/** A stand-in for `VaultGate` + the lock sequence (§7.7): lock listeners run before the gate closes. */
class ProtoGate(
    open: Boolean = false,
) {
    var isOpen by mutableStateOf(open)
        private set
    private val onLock = mutableListOf<() -> Unit>()

    fun addLockListener(listener: () -> Unit): () -> Unit {
        onLock += listener
        return { onLock -= listener }
    }

    fun unlock() {
        isOpen = true
    }

    fun lock() {
        onLock.toList().forEach { it() }
        isOpen = false
    }
}

val LocalSkeinLayout = staticCompositionLocalOf<SkeinLayoutDecision> { error("no layout") }

/** Material's directive with Skein's pane count, side-pane width and spacer (AL-04 hand-off). */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
fun skeinDirective(
    info: WindowAdaptiveInfo,
    layout: SkeinLayoutDecision,
): PaneScaffoldDirective =
    calculatePaneScaffoldDirective(info, HingePolicy.AvoidSeparating).copy(
        maxHorizontalPartitions = layout.maxPanes,
        horizontalPartitionSpacerSize = SkeinSize.paneSpacer,
        defaultPanePreferredWidth = layout.sidePaneWidth,
    )

/** Everything above the gate (§8.8's `setContent`). */
@Composable
fun ProtoRoot(
    deps: ProtoDeps,
    gate: ProtoGate,
    initialNav: () -> ProtoNavigationState = { ProtoNavigationState() },
    windowInfo: @Composable () -> Pair<WindowAdaptiveInfo, DpSize> = {
        currentWindowAdaptiveInfoV2() to LocalWindowInfo.current.containerDpSize
    },
    // Test hook: the restored navigation state after a recreation.
    onNavigationState: (ProtoNavigationState) -> Unit = {},
) {
    val nav = rememberProtoNavigationState(initialNav)
    SideEffect { onNavigationState(nav) }
    val entryState = rememberSaveableStateHolder()
    val stores: SessionEntryStores = viewModel()
    val sheets = remember { SheetPresentation() }
    DisposableEffect(gate, stores) { onDispose(gate.addLockListener(stores::clearAll)) }

    SkeinTheme {
        if (!gate.isOpen) {
            Text("Unlock Skein", Modifier.testTag(GATE_TAG))
        } else {
            // M4d: sanitise (B8) before the first entry renders.
            var sanitised by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                nav.sanitise { id -> deps.vault.getDocument(id.value) != null }
                sanitised = true
            }
            if (sanitised) {
                val (info, size) = windowInfo()
                ProtoShell(nav, entryState, stores, sheets, deps, info, size)
            }
        }
    }
}

const val GATE_TAG = "gate"
const val RAIL_TAG = "rail"
const val DRAWER_BUTTON_TAG = "drawer-button"
const val DRAWER_SHEET_TAG = "drawer-sheet"

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun ProtoShell(
    nav: ProtoNavigationState,
    entryState: SaveableStateHolder,
    stores: SessionEntryStores,
    sheets: SheetPresentation,
    deps: ProtoDeps,
    info: WindowAdaptiveInfo,
    size: DpSize,
) {
    val layout = skeinWindowLayout(info, size, LocalDensity.current)
    val directive = remember(info, layout) { skeinDirective(info, layout) }
    // §2.5: a later shrink shows the peek, never a sheet the user expanded on another window.
    LaunchedEffect(layout.maxPanes) { if (layout.maxPanes > 1) sheets.collapseAll() }

    val decorators =
        listOf(
            rememberSaveableStateHolderNavEntryDecorator<ProtoKey>(entryState),
            remember(stores) { sessionEntryDecorator<ProtoKey>(stores) },
        )
    val provider = remember(deps, nav, sheets) { protoEntryProvider(deps, nav, sheets) }
    // One decorated list per destination (NiA): switching destination is not a pop, so
    // the other stacks keep their T2/T3 state.
    val entriesByDestination =
        Destination.entries.associateWith { d ->
            key(d) { rememberDecoratedNavEntries(nav.stacks.getValue(d), decorators, provider) }
        }
    val strategies =
        listOf(
            SkeinSheetSceneStrategy<ProtoKey>(layout, sheets.expandedKeys, sheets::expand),
            // Not the default PopUntilScaffoldValueChange: on Dual, `List | Detail` and
            // `List | placeholder` are the same scaffold value, so Back from an open chat would
            // leave the app instead of restoring the placeholder (§3.6 step 4).
            rememberListDetailSceneStrategy(
                backNavigationBehavior = BackNavigationBehavior.PopUntilContentChange,
                directive = directive,
            ),
            rememberSupportingPaneSceneStrategy(directive = directive),
        )

    val sceneDecorators = remember { listOf(SceneIdentityDecorator<ProtoKey>()) }

    CompositionLocalProvider(LocalSkeinLayout provides layout) {
        NavContainer(layout, nav) {
            // §8.3 rule 6b: a destination root other than Chat goes to Chat.
            NavigationBackHandler(
                state = rememberNavigationEventState(NavigationEventInfo.None),
                isBackEnabled = nav.top != Destination.CHAT && nav.current.size == 1,
                onBackCompleted = { nav.switchTo(Destination.CHAT) },
            )
            NavDisplay(
                entries = entriesByDestination.getValue(nav.top),
                modifier =
                    Modifier.fillMaxSize().onPreviewKeyEvent {
                        it.key == Key.Escape && it.type == KeyEventType.KeyUp && nav.escape()
                    },
                sceneStrategies = strategies,
                sceneDecoratorStrategies = sceneDecorators,
                transitionSpec = { crossFade() },
                popTransitionSpec = { crossFade() },
                onBack = { nav.back() },
            )
        }
    }
}

// §2.7: a cross-fade of ≤ 200 ms between scenes.
private fun crossFade(): ContentTransform = fadeIn(tween(150)) togetherWith fadeOut(tween(150))

/** One container per window (§3.1); the drawer is snapped closed whenever the container changes (Test G). */
@Composable
private fun NavContainer(
    layout: SkeinLayoutDecision,
    nav: ProtoNavigationState,
    content: @Composable () -> Unit,
) {
    val isDrawer = layout.nav == SkeinNavContainer.DRAWER
    // Not `rememberDrawerState`: that one is saveable, and a recreation must not reopen the drawer (G6).
    val drawerState = remember { DrawerState(DrawerValue.Closed) }
    LaunchedEffect(isDrawer) { drawerState.snapTo(DrawerValue.Closed) }
    val scope = rememberCoroutineScope()
    // One tree for both containers: NavDisplay must keep its place in the composition when the
    // container changes (drawer <-> rail), or every entry is disposed and re-created on a fold.
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = isDrawer,
        drawerContent = {
            ModalDrawerSheet(drawerState, Modifier.testTag(DRAWER_SHEET_TAG)) {
                Destination.entries.forEach { d ->
                    NavigationDrawerItem(
                        label = { Text(d.name) },
                        selected = nav.top == d,
                        onClick = {
                            nav.switchTo(d)
                            scope.launch { drawerState.close() }
                        },
                    )
                }
            }
        },
    ) {
        Row(Modifier.fillMaxSize()) {
            if (!isDrawer) {
                NavigationRail(Modifier.testTag(RAIL_TAG)) {
                    Destination.entries.forEach { d ->
                        NavigationRailItem(
                            selected = nav.top == d,
                            onClick = { nav.switchTo(d) },
                            icon = { Text(d.name.take(1)) },
                        )
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxSize()) {
                if (isDrawer) {
                    TextButton(
                        onClick = { scope.launch { drawerState.open() } },
                        modifier = Modifier.testTag(DRAWER_BUTTON_TAG),
                    ) {
                        Text("☰")
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) { content() }
            }
        }
    }
}
