package app.skein.feature.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.window.core.layout.WindowSizeClass
import app.skein.core.model.PersonaId
import app.skein.core.model.VaultRepository
import app.skein.feature.shell.layout.AdaptivePaneHost
import app.skein.feature.shell.layout.FoldPosture
import app.skein.feature.shell.layout.PaneLayoutState
import app.skein.feature.shell.layout.classifyWidth
import app.skein.feature.shell.layout.computeAdaptiveLayout
import app.skein.feature.shell.layout.rememberAdaptiveLayoutState
import app.skein.feature.shell.layout.rememberFoldPosture
import app.skein.feature.shell.nav.CommandBarHost
import app.skein.feature.shell.nav.CommandBarState
import app.skein.feature.shell.nav.CommandRegistry
import app.skein.feature.shell.nav.CommandScope
import app.skein.feature.shell.nav.Destination
import app.skein.feature.shell.nav.NavDrawer
import app.skein.feature.shell.nav.NavState
import app.skein.feature.shell.nav.newNoteCommand
import app.skein.feature.shell.nav.rememberNavState
import app.skein.feature.shell.split.rememberSplitCoordinator
import app.skein.feature.shell.tabs.EmptyTabHostPlaceholder
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
 *
 * [timelinePane] is the seam bd `skein-64y9` adds: `:feature:shell` cannot
 * depend on `:feature:timeline` (same dependency-direction constraint as
 * [destinationContent]'s doc above), so `:app` supplies the real
 * `TimelineScreen`/`TimelineRail` content here instead. It's composed in
 * exactly one of two places, mirroring `AdaptivePaneHost`'s own posture
 * logic (never both at once):
 *  - the dedicated left pane, with `expanded = true`, whenever the timeline
 *    mode is `FULL` (open Fold / wide dual-pane) — replacing the
 *    `DestinationPlaceholder` this slot used to hard-code;
 *  - the primary `TabHost`'s landing content, with `expanded = false`, when
 *    the layout is single-pane (folded/phone) *and* no tab is open — this is
 *    what stops a fresh launch from showing "No tabs open" (the bug
 *    `skein-64y9` was filed for).
 *
 * `onEntryOpen`/`onEntryPin` are the same shape as [noteTabContent]'s
 * `onOpenDocument`: `:app`'s timeline content only needs a `docId`/`title`
 * to open a preview ([TabsState.openPreview]) or a pinned tab
 * ([TabsState.openPinned]) on *this pane's* primary [TabsState] — the same
 * one [tabContent] resolves `TabKind.NOTE` tabs against.
 *
 * `null` (the default) keeps pre-`skein-64y9` behaviour exactly: the
 * hardcoded `DestinationPlaceholder` in the left pane, and `TabHost`'s own
 * "No tabs open" placeholder when single-pane has no active tab.
 *
 * [overlay] is the seam bd `skein-0td0` adds: a fourth slot, the same shape
 * as [timelinePane] — `:feature:shell` hands back two callbacks
 * (`openPreview`/`openPinned`, named after [TabsState.openPreview]/
 * [TabsState.openPinned] since that's exactly what they call) that close
 * over *this composable's own* [primaryTabsState] — and lets `:app` decide
 * what, if anything, to draw on top of the whole shell. This is for content
 * that lives *alongside* [AdaptivePaneHost] rather than inside one of its
 * panes or tabs, the same relationship [app.skein.feature.graph.GraphScreen]
 * already has to `SkeinApp` per its own file header (an overlay opened from
 * the ✦ button, drawn over everything, dismissed by its own `onClose`).
 * Before this slot existed, `:app` had to hand-roll that relationship itself
 * — wrapping `SkeinApp` in its own `Box` in `MainActivity` — which put the
 * overlay outside `SkeinApp` entirely and left it with no way to reach
 * [primaryTabsState] to actually open a tab (bd `skein-0td0`'s bug: tapping
 * a graph node only dismissed the overlay). Composed last inside this
 * function's own `Box`, so it always paints over [AdaptivePaneHost] — it
 * does not participate in that host's pane layout at all, matching how
 * [timelinePane]'s doc above describes [destinationContent] and
 * [noteTabContent] as slots `:feature:shell` cannot own real screens for,
 * except one level further out: this slot isn't shown *inside* any pane.
 *
 * A dedicated `overlay` slot (rather than reusing [timelinePane] or
 * threading a `TabOpener` through [destinationContent]) was picked because
 * every existing slot is scoped to *content shown in a specific place*
 * (a pane, a tab) — an overlay drawn over the whole shell, shown or hidden
 * by `:app`'s own state (`graphDocId`), is a different shape and forcing it
 * into one of those slots would mean either drawing it inside a pane (wrong
 * — it must cover the nav drawer/command bar too) or growing
 * [destinationContent] a second, unrelated purpose. `null` (the default)
 * means "nothing to draw", matching every other slot's null-safe default.
 *
 * [vaultRepository] / [personaId] are `E6.I4` slice A's (bd `skein-ps0`)
 * seam for the command bar's `/new note` command and plain-text search:
 * unlike [noteTabContent]/[timelinePane]/[overlay], these aren't
 * `:app`-only screen slots — [CommandBar] itself lives in this module and
 * `VaultRepository` is already on this module's classpath (`:core:vault`
 * `api`-exposes `:core:model`, and `:feature:editor`'s `NoteTab` takes the
 * same type directly for the same reason), so there's no cross-module
 * dependency-direction problem in taking it as a plain parameter here.
 * `null` (the default) keeps every existing caller — every `SkeinAppTest`
 * case, every preview — compiling and behaving unchanged: a search-less,
 * command-less bar with no crash. `:app`'s `MainActivity` passes
 * `session.repository`; `personaId` stays `null` until the shell has a
 * "current persona" concept of its own to expose (coordinator note
 * 2026-09-22).
 */
@Composable
fun SkeinApp(
    themeMode: SkeinThemeMode = SkeinThemeMode.SYSTEM,
    vaultRepository: VaultRepository? = null,
    personaId: PersonaId? = null,
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
    timelinePane: (
        @Composable (
            expanded: Boolean,
            onEntryOpen: (docId: String, title: String) -> Unit,
            onEntryPin: (docId: String, title: String) -> Unit,
        ) -> Unit
    )? = null,
    overlay: (
        @Composable (
            openPreview: (docId: String, title: String) -> Unit,
            openPinned: (docId: String, title: String) -> Unit,
        ) -> Unit
    )? = null,
    windowSizeClass: WindowSizeClass = currentWindowAdaptiveInfoV2().windowSizeClass,
    posture: FoldPosture = rememberFoldPosture().value,
) {
    SkeinTheme(mode = themeMode) {
        val navState = rememberNavState()
        val layoutState = rememberAdaptiveLayoutState()
        val primaryTabsState = rememberTabsState()
        val secondaryTabsState = rememberTabsState()
        val splitCoordinator = rememberSplitCoordinator(primaryTabsState, secondaryTabsState, layoutState)
        // Single source of truth for "is the timeline slot the only place
        // left to put content" — the same pure decision `AdaptivePaneHost`
        // makes internally (and is handed the same `windowSizeClass`/
        // `posture` here so the two never disagree).
        val isSinglePane =
            computeAdaptiveLayout(classifyWidth(windowSizeClass), posture, layoutState).paneLayoutState ==
                PaneLayoutState.SINGLE_PANE
        val onTimelineEntryOpen: (docId: String, title: String) -> Unit = { docId, title ->
            primaryTabsState.openPreview(Tab(TabId(UUID.randomUUID().toString()), docId, title, TabKind.NOTE))
        }
        val onTimelineEntryPin: (docId: String, title: String) -> Unit = { docId, title ->
            primaryTabsState.openPinned(Tab(TabId(UUID.randomUUID().toString()), docId, title, TabKind.NOTE))
        }
        // Same shape as the timeline callbacks above, minted for `overlay`
        // instead: both ultimately just call `primaryTabsState.openPreview`/
        // `openPinned` with a fresh `TabId`, so `:app`'s overlay content
        // (e.g. `GraphScreen`) gets real tab-opening semantics without
        // needing its own `TabsState` handle.
        val onOverlayOpenPreview: (docId: String, title: String) -> Unit = { docId, title ->
            primaryTabsState.openPreview(Tab(TabId(UUID.randomUUID().toString()), docId, title, TabKind.NOTE))
        }
        val onOverlayOpenPinned: (docId: String, title: String) -> Unit = { docId, title ->
            primaryTabsState.openPinned(Tab(TabId(UUID.randomUUID().toString()), docId, title, TabKind.NOTE))
        }

        // E6.I4 slice A (skein-ps0): the `/` palette's command source.
        // `/new note` is (re-)registered whenever `vaultRepository` changes
        // — `null` unregisters it, so a shell with no vault wired shows an
        // empty (but not broken) palette instead of a command that would
        // NPE if run. `E7.I6` registers `CommandScope.EDITOR` commands into
        // this same registry without touching this effect.
        val commandRegistry = remember { CommandRegistry() }
        LaunchedEffect(vaultRepository, personaId, primaryTabsState) {
            if (vaultRepository != null) {
                commandRegistry.register(
                    CommandScope.GLOBAL,
                    listOf(newNoteCommand(vaultRepository, personaId, primaryTabsState)),
                )
            } else {
                commandRegistry.unregister(CommandScope.GLOBAL)
            }
        }
        val commandBarScope = rememberCoroutineScope()
        val commandBarState =
            remember(vaultRepository, commandRegistry, primaryTabsState) {
                CommandBarState(
                    vaultRepository = vaultRepository,
                    registry = commandRegistry,
                    onOpenPreview = onTimelineEntryOpen,
                    searchScope = commandBarScope,
                )
            }

        // skein-1vfg: targetSdk 37 forces edge-to-edge on Android 15+, and
        // nothing below handled window insets, so the command bar and its
        // hamburger glyph rendered half under the status bar/clock. The
        // background is painted to the true window edges (this `Box`, via
        // `.background(...)`, is the outermost layer) while the CONTENT —
        // this `Column`, everything `CommandBarHost`/`AdaptivePaneHost`
        // render — is inset by `WindowInsets.safeDrawing` (status bar,
        // display cutout, navigation bar). `safeDrawing` already unions
        // `ime()` in too, so the command bar's palette/search results and
        // any focused text field below them clear the keyboard the same
        // way, with no second `imePadding()` call. `overlay` (e.g.
        // `GraphScreen`) insets itself via the same
        // `app.skein.feature.shell.layout.EdgeToEdgeSurface` this modifier
        // pair is shared with.
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
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
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .testTag(ShellTestTags.SKEIN_SHELL_ROOT),
                ) {
                    CommandBarHost(
                        navState = navState,
                        commandBarState = commandBarState,
                        modelName = "qwen",
                        modelActive = true,
                    )
                    AdaptivePaneHost(
                        layoutState = layoutState,
                        modifier = Modifier.weight(1f),
                        windowSizeClass = windowSizeClass,
                        posture = posture,
                        timeline = {
                            timelinePane?.invoke(true, onTimelineEntryOpen, onTimelineEntryPin)
                                ?: DestinationPlaceholder(label = "Timeline")
                        },
                        primary = { splitAvailable ->
                            TabHost(
                                tabsState = primaryTabsState,
                                splitAvailable = splitAvailable,
                                onOpenInSplit = splitCoordinator::openInSplit,
                                emptyContent = {
                                    if (navState.destination == Destination.TIMELINE) {
                                        if (isSinglePane && timelinePane != null) {
                                            timelinePane(false, onTimelineEntryOpen, onTimelineEntryPin)
                                        } else {
                                            EmptyTabHostPlaceholder()
                                        }
                                    } else {
                                        destinationContent(navState.destination)
                                    }
                                },
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
            overlay?.invoke(onOverlayOpenPreview, onOverlayOpenPinned)
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
