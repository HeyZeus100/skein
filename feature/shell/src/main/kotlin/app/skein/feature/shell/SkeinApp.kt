package app.skein.feature.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.skein.feature.shell.layout.AdaptivePaneHost
import app.skein.feature.shell.layout.rememberAdaptiveLayoutState
import app.skein.feature.shell.nav.CommandBar
import app.skein.feature.shell.nav.Destination
import app.skein.feature.shell.nav.NavDrawer
import app.skein.feature.shell.nav.NavState
import app.skein.feature.shell.nav.rememberNavState
import app.skein.feature.shell.split.rememberSplitCoordinator
import app.skein.feature.shell.tabs.FlushRegistry
import app.skein.feature.shell.tabs.MockTabContent
import app.skein.feature.shell.tabs.Tab
import app.skein.feature.shell.tabs.TabHost
import app.skein.feature.shell.tabs.TabId
import app.skein.feature.shell.tabs.TabKind
import app.skein.feature.shell.tabs.TabsState
import app.skein.feature.shell.tabs.rememberTabsState
import app.skein.feature.shell.testing.ShellTestTags
import app.skein.feature.shell.theme.SkeinTheme
import app.skein.feature.shell.theme.SkeinThemeMode
import java.util.UUID

/**
 * The app shell (plan `E6.I3`): [SkeinTheme] wrapping the persistent nav
 * layer — [NavDrawer] around the [CommandBar] + [AdaptivePaneHost] stack.
 * [NavState] owns drawer open/closed, the active destination, and the
 * command bar query.
 *
 * [destinationContent] renders the primary pane for the active
 * [Destination]; it defaults to [DestinationPlaceholder], which is still
 * correct for every destination [SkeinApp] doesn't yet have a real screen
 * for (`E6.I8`+ / `E7.I3`+ own most of those). `:feature:shell` cannot
 * depend on feature modules that host real screens (e.g. `:feature:settings`
 * depends on `:feature:shell` for [app.skein.feature.shell.theme.SkeinTheme]
 * / `SecureTextField`, so the reverse dependency would cycle) — this slot is
 * how a leaf module like `:app`, which can depend on everything, wires a
 * real screen (e.g. `Destination.SETTINGS ->
 * app.skein.feature.settings.SettingsScreen(...)`) in without `:feature:shell`
 * ever knowing that screen's module exists. A real nav-graph replacing this
 * switch is out of scope for `E6.I3`/`E6.I14`.
 *
 * [noteTabContent] is the same kind of slot, one level down: `TabHost`
 * composes it (instead of [destinationContent]) whenever the *active tab*
 * is `TabKind.NOTE` (plan `E6.I9`) — a document opened as a tab, not a nav
 * destination. `:feature:editor` (home of `NoteTab`/`EditorState`) already
 * depends on `:feature:shell` for `SecureBasicTextField`, so this module
 * cannot depend back on it; `:app` supplies the real note tab the same way
 * it supplies [destinationContent]'s real screens. The `onPin`/
 * `onOpenDocument` callbacks handed to [noteTabContent] close over
 * *this pane's* [TabsState] directly — `:app` doesn't need a
 * `TabController` of its own for those two operations, only for vault
 * access ([noteTabContent]'s own closure already has that from wherever it
 * builds `NoteTab`).
 */
@Composable
fun SkeinApp(
    themeMode: SkeinThemeMode = SkeinThemeMode.SYSTEM,
    destinationContent: @Composable (Destination) -> Unit = { destination ->
        DestinationPlaceholder(label = destination.name)
    },
    flushRegistry: FlushRegistry = remember { FlushRegistry() },
    noteTabContent: @Composable (
        tab: Tab,
        onPin: () -> Unit,
        onOpenDocument: (docId: String, title: String) -> Unit,
        flushRegistry: FlushRegistry,
    ) -> Unit = { tab, _, _, _ -> MockTabContent(tab) },
) {
    SkeinTheme(mode = themeMode) {
        val navState = rememberNavState()
        val layoutState = rememberAdaptiveLayoutState()
        val primaryTabsState = rememberTabsState()
        val secondaryTabsState = rememberTabsState()
        val splitCoordinator = rememberSplitCoordinator(primaryTabsState, secondaryTabsState, layoutState)

        NavDrawer(
            open = navState.drawerOpen,
            activeDestination = navState.destination,
            onNavigate = navState::navigate,
            onDismiss = navState::closeDrawer,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .testTag(ShellTestTags.SKEIN_SHELL_ROOT),
            ) {
                CommandBar(
                    query = navState.query,
                    onQueryChange = navState::setQuery,
                    onMenuClick = navState::openDrawer,
                    modelName = "qwen",
                    modelActive = true,
                )
                AdaptivePaneHost(
                    layoutState = layoutState,
                    modifier = Modifier.weight(1f),
                    timeline = { DestinationPlaceholder(label = "Timeline") },
                    primary = { splitAvailable ->
                        TabHost(
                            tabsState = primaryTabsState,
                            splitAvailable = splitAvailable,
                            onOpenInSplit = splitCoordinator::openInSplit,
                            content = { tab ->
                                tabContent(
                                    tab = tab,
                                    tabsState = primaryTabsState,
                                    destinationContent = destinationContent,
                                    noteTabContent = noteTabContent,
                                    flushRegistry = flushRegistry,
                                    navState = navState,
                                )
                            },
                        )
                    },
                    secondary = { splitAvailable ->
                        TabHost(
                            tabsState = secondaryTabsState,
                            splitAvailable = splitAvailable,
                            onEmpty = splitCoordinator::exitSplitIfSecondaryEmpty,
                            content = { tab ->
                                tabContent(
                                    tab = tab,
                                    tabsState = secondaryTabsState,
                                    destinationContent = destinationContent,
                                    noteTabContent = noteTabContent,
                                    flushRegistry = flushRegistry,
                                    navState = navState,
                                )
                            },
                        )
                    },
                )
            }
        }
    }
}

/**
 * Picks [noteTabContent] vs. [destinationContent] for a `TabHost`'s active
 * [tab] (plan `E6.I9`): a `TabKind.NOTE` tab is a document, and gets a real
 * `onPin`/`onOpenDocument` wired against [tabsState] — the pane's own
 * [TabsState] already has everything those two need, so `:app`'s
 * [noteTabContent] closure only has to supply vault access, not tab
 * mechanics. Every other kind (chat/attachment, not yet real screens) keeps
 * the pre-`E6.I9` behavior of falling back to [destinationContent].
 *
 * `onOpenDocument` mints a fresh [TabId] for the resolved document and opens
 * it as a preview (Cursor-style single-click semantics, `E6.I5`) — the same
 * "reuse the existing preview for this docId" behavior any other preview
 * open gets, courtesy of [TabsState.openPreview] itself.
 */
@Composable
private fun tabContent(
    tab: Tab,
    tabsState: TabsState,
    destinationContent: @Composable (Destination) -> Unit,
    noteTabContent: @Composable (
        tab: Tab,
        onPin: () -> Unit,
        onOpenDocument: (docId: String, title: String) -> Unit,
        flushRegistry: FlushRegistry,
    ) -> Unit,
    flushRegistry: FlushRegistry,
    navState: NavState,
) {
    when (tab.kind) {
        TabKind.NOTE ->
            noteTabContent(
                tab,
                { tabsState.pin(tab.id) },
                { docId, title ->
                    tabsState.openPreview(Tab(TabId(UUID.randomUUID().toString()), docId, title, TabKind.NOTE))
                },
                flushRegistry,
            )
        else -> destinationContent(navState.destination)
    }
}

/**
 * Stand-in for a destination with no real screen yet. Only names the active
 * destination so the nav wiring is visibly correct. Public so hosts
 * customizing [destinationContent] can reuse it for the destinations they
 * still don't have a screen for (see [SkeinApp]'s doc).
 */
@Composable
fun DestinationPlaceholder(label: String) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
