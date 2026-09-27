// skein-xtov.24.6 (AL-07, UX Wave 3): the adaptive navigation container —
// one modal drawer on Compact/short windows, an 80/240 dp rail on
// Medium+ (ADAPTIVE_LAYOUT_SPEC.md §3.1). Built alongside the current
// NavDrawer/IconRail/command bar, not in place of them: AL-09a/b perform
// the switch and delete the old ones.
//
// **NavigationSuiteScaffold vs. the prototype's shape (spec §8.9 item 10).**
// The spec flags that re-parenting `NavDisplay` between a drawer and a
// rail crashed a `SaveableStateHolder` ("Key … was used multiple times")
// and instructs: verify `NavigationSuiteScaffold` keeps the content slot's
// position across the switch before using it, else follow
// `spike/nav3-prototype`'s shape. That prototype (`ProtoShell.kt`)
// doesn't use the suite at all — one `ModalNavigationDrawer` always in the
// tree, gestures gated on the container, the rail as a `Row` sibling, and
// `content` called from one fixed call site regardless of which sibling
// renders beside it. Compose's slot table keys composition state by call
// site, not by sibling position, so that shape is *known* to preserve
// `content`'s state (this bead's own `SkeinNavigationContainerTest`
// exercises exactly that with a `rememberSaveable` counter across a live
// drawer↔rail flip). `NavigationSuiteScaffold` 1.4.0 was not exercised
// against that same crash here — this bead adds no new catalog dependency
// and instead mirrors the prototype's already-proven tree. If AL-08 later
// verifies the suite is equally stable, swapping the container's
// internals is a change local to this file; the public API does not
// depend on the choice.
package app.skein.feature.shell.container

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.skein.core.designsystem.theme.SkeinSize
import app.skein.feature.shell.layout.SkeinLayoutDecision
import app.skein.feature.shell.layout.SkeinNavContainer
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * Opens the drawer from inside [SkeinNavigationContainer]'s `content`
 * (spec §3.5: every destination screen owns its own top bar, and the
 * Phone/Short ones use ☰). Production screens read this instead of
 * threading an "open drawer" callback through `content`'s
 * `@Composable () -> Unit` signature. A no-op default so a preview or a
 * screen composed outside the container doesn't crash.
 */
val LocalSkeinDrawerOpener = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * One navigation container per window (spec §3.1): [SkeinDrawerContent]
 * in a modal drawer on `SkeinNavContainer.DRAWER`, [SkeinRailContent] as a
 * rail sibling otherwise. [content] (AL-08's `NavDisplay`) sits at one
 * fixed call site so it never loses composition state when [decision]'s
 * container flips (see this file's header comment).
 *
 * The drawer is size-aware (spec §3.3 Test G): composition state, not
 * `rememberSaveable` (a saveable would reopen over a new layout after a
 * recreation — spec's P2-05), snapped **closed** whenever the container
 * stops being the drawer, and starting closed whenever it becomes the
 * drawer again. [onNavigate]/[onNewChat]/[onSearch] and every history
 * row's `onOpen` (§3.3: "the drawer closes when the user picks a
 * destination, New chat, a chat row or the search field") close it too;
 * a row's `onRename`/`onDelete` deliberately do not (§3.3's dialog
 * exception).
 */
@Composable
fun SkeinNavigationContainer(
    decision: SkeinLayoutDecision,
    destination: SkeinDestination,
    onNavigate: (SkeinDestination) -> Unit,
    onNewChat: () -> Unit,
    onSearch: () -> Unit,
    history: List<ChatHistoryItem>,
    spaces: List<SkeinSpace>,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
    now: () -> Long = System::currentTimeMillis,
    content: @Composable () -> Unit,
) {
    val isDrawer = decision.nav == SkeinNavContainer.DRAWER
    // Not `rememberDrawerState`/`rememberSaveable`: see the doc above.
    val drawerState = remember { DrawerState(DrawerValue.Closed) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(isDrawer) { drawerState.snapTo(DrawerValue.Closed) }

    fun closeIfDrawer() {
        if (isDrawer) scope.launch { drawerState.close() }
    }

    val wrappedHistory =
        remember(history, isDrawer) {
            history.map { row ->
                row.copy(onOpen = {
                    row.onOpen()
                    closeIfDrawer()
                })
            }
        }

    // One tree for both containers (spec §8.9 item 10): `content` is
    // called from the single `Box` below regardless of which branch of
    // `if (!isDrawer)` renders above it.
    ModalNavigationDrawer(
        modifier = modifier.testTag(SkeinNavContainerTestTags.ROOT),
        drawerState = drawerState,
        // §3.2's own recipe: gestures (both open and close) only once the
        // drawer is already open, so an edge swipe on a Drawer window can
        // never *open* it — that would collide with the system Back gesture.
        gesturesEnabled = isDrawer && drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet(
                drawerState = drawerState,
                modifier =
                    Modifier
                        .width(drawerWidth(decision))
                        .testTag(SkeinNavContainerTestTags.DRAWER_SHEET),
            ) {
                SkeinDrawerContent(
                    destination = destination,
                    onNavigate = {
                        onNavigate(it)
                        closeIfDrawer()
                    },
                    onNewChat = {
                        onNewChat()
                        closeIfDrawer()
                    },
                    onSearch = {
                        onSearch()
                        closeIfDrawer()
                    },
                    history = wrappedHistory,
                    spaces = spaces,
                    zone = zone,
                    now = now,
                )
            }
        },
    ) {
        CompositionLocalProvider(LocalSkeinDrawerOpener provides { scope.launch { drawerState.open() } }) {
            Row(Modifier.fillMaxSize()) {
                if (!isDrawer) {
                    SkeinRailContent(
                        destination = destination,
                        onNavigate = onNavigate,
                        onNewChat = onNewChat,
                        spaces = spaces,
                        expanded = decision.nav == SkeinNavContainer.EXPANDED_RAIL,
                        modifier = Modifier.testTag(SkeinNavContainerTestTags.RAIL),
                    )
                }
                Box(Modifier.weight(1f).fillMaxWidth()) { content() }
            }
        }
    }
}

/** Spec §13a: `drawerMax` (320 dp), also capped at `window − 56 dp`. */
private fun drawerWidth(decision: SkeinLayoutDecision): Dp = minOf(SkeinSize.drawerMax, decision.size.width - 56.dp)
