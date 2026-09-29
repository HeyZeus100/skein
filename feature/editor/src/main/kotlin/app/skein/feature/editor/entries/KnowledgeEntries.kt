// skein-xtov.24.8 (AL-09a): the Knowledge destination's NavDisplay entries
// (ADAPTIVE_LAYOUT_SPEC.md §8.2, §4.3; KNOWLEDGE_UX_SPEC.md §3, §6, §9.3,
// §10): the list (the timeline's, chats left out), a note, a new-note draft,
// a minimal file viewer and Connections. Everything opens by kind (IA §2
// principle 2, LC-20); an entry whose id stops resolving shows its gone state
// (OBJECT_LIFECYCLE_SPEC.md §3.5). Also [SourceEntry], Chat's opened source.
package app.skein.feature.editor.entries

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.skein.core.designsystem.components.SkeinAction
import app.skein.core.designsystem.components.SkeinEmptyState
import app.skein.core.designsystem.components.SkeinListRow
import app.skein.core.designsystem.components.SkeinStatus
import app.skein.core.designsystem.components.SkeinStatusKind
import app.skein.core.designsystem.icons.SkeinIcons
import app.skein.core.designsystem.theme.SkeinSpacing
import app.skein.core.model.DocumentKind
import app.skein.core.model.IndexStore
import app.skein.core.model.Persona
import app.skein.core.model.TimelineFilter
import app.skein.core.model.VaultRepository
import app.skein.core.navigation.ConnectionsKey
import app.skein.core.navigation.Destination
import app.skein.core.navigation.FileKey
import app.skein.core.navigation.KnowledgeHomeKey
import app.skein.core.navigation.NewNoteKey
import app.skein.core.navigation.NoteKey
import app.skein.core.navigation.SkeinId
import app.skein.core.navigation.SkeinKey
import app.skein.core.navigation.TransientKey
import app.skein.core.navigation.TransientKind
import app.skein.core.navigation.contentKey
import app.skein.feature.editor.backlinks.BacklinksDrawer
import app.skein.feature.editor.backlinks.rememberBacklinksState
import app.skein.feature.editor.notetab.NoteTab
import app.skein.feature.shell.host.EntryAction
import app.skein.feature.shell.host.EntryDocument
import app.skein.feature.shell.host.EntryNavButton
import app.skein.feature.shell.host.EntryNavIcon
import app.skein.feature.shell.host.EntryTopBar
import app.skein.feature.shell.host.GoneEntry
import app.skein.feature.shell.host.LocalSheetMode
import app.skein.feature.shell.host.LocalSkeinWindowLayout
import app.skein.feature.shell.host.SheetMode
import app.skein.feature.shell.host.SheetPeekRow
import app.skein.feature.shell.host.SkeinShellState
import app.skein.feature.shell.host.entryBottomPadding
import app.skein.feature.shell.host.followById
import app.skein.feature.shell.host.navIconFor
import app.skein.feature.shell.host.open
import app.skein.feature.shell.host.rememberEntryDocument
import app.skein.feature.timeline.TimelineScreen
import app.skein.feature.timeline.rememberTimelineState
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.ZoneId

/** What the Knowledge entries (and Chat's opened sources) need from the open vault; [clock] and [zone] date the rows. */
class KnowledgeEntryDeps(
    val repository: VaultRepository,
    val indexStore: IndexStore,
    val personas: Flow<List<Persona>> = flowOf(emptyList()),
    val clock: () -> Long = System::currentTimeMillis,
    val zone: ZoneId = ZoneId.systemDefault(),
    val preparation: Flow<KnowledgePreparation> = flowOf(KnowledgePreparation()),
)

/** Counts from the current ingest pass, supplied by the app; a notification never sets this state. */
data class KnowledgePreparation(
    val running: Boolean = false,
    val processed: Int = 0,
    val awaitingMeaningSearch: Int = 0,
)

object KnowledgeEntryTestTags {
    const val LIST = "knowledge_entry_list"
    const val NEW_NOTE_ACTION = "knowledge_entry_new_note_action"
    const val SEARCH_ACTION = "knowledge_entry_search_action"
    const val EMPTY_DETAIL = "knowledge_entry_empty_detail"
    const val CONNECTIONS = "knowledge_entry_connections"
    const val CONNECTIONS_PEEK = "knowledge_entry_connections_peek"
    const val CHAT_SOURCE = "knowledge_entry_chat_source"
    const val PREPARATION_STATUS = "knowledge_entry_preparation_status"
}

/** The kinds the Knowledge list shows (IA §3.2): every note and file; chats live in Chat. */
val KNOWLEDGE_KINDS: Set<DocumentKind> = setOf(DocumentKind.NOTE, DocumentKind.ATTACHMENT, DocumentKind.AIOUT)

/** One Knowledge key's content (§8.2). */
@Composable
fun KnowledgeEntry(
    key: SkeinKey,
    shell: SkeinShellState,
    deps: KnowledgeEntryDeps,
) {
    when (key) {
        KnowledgeHomeKey -> KnowledgeList(shell, deps)
        is NoteKey -> NoteRoute(key, key.docId.value, shell, deps)
        is NewNoteKey -> NoteRoute(key, key.draftId.value, shell, deps, draft = true)
        is FileKey -> FileRoute(key, key.docId.value, shell, deps)
        is ConnectionsKey -> ConnectionsEntry(key, key.docId.value, shell, deps)
        is TransientKey -> {
            val raw = shell.nav.rawIdOf(key) ?: return
            when (key.kind) {
                TransientKind.NOTE -> NoteRoute(key, raw, shell, deps)
                TransientKind.FILE -> FileRoute(key, raw, shell, deps)
                TransientKind.CONNECTIONS -> ConnectionsEntry(key, raw, shell, deps)
                else -> Unit
            }
        }
        else -> Unit
    }
}

/** KNOWLEDGE_UX_SPEC.md §3.8 "Expanded, nothing selected": never a bare placeholder, never auto-open. */
@Composable
fun KnowledgeDetailPlaceholder(shell: SkeinShellState) {
    SkeinEmptyState(
        headline = "Pick a note or file, or start something new.",
        primaryAction = SkeinAction("New note", SkeinIcons.NewNote) { shell.newNote() },
        modifier = Modifier.fillMaxSize().testTag(KnowledgeEntryTestTags.EMPTY_DETAIL),
    )
}

/**
 * A cited or linked document opened from a chat (`ChatSourceKey`, CHAT_UX_SPEC.md
 * §17.6), by kind: a note in the editor, a file in the viewer. Never the editor
 * over a chat (K-P0-3).
 */
@Composable
fun SourceEntry(
    key: SkeinKey,
    rawId: String,
    shell: SkeinShellState,
    deps: KnowledgeEntryDeps,
) {
    // Read once, for the kind: after that the note or file entry watches its own id.
    val resolved by produceState<EntryDocument>(EntryDocument.Loading, deps.repository, rawId) {
        value = deps.repository.getDocument(rawId)?.let(EntryDocument::Present) ?: EntryDocument.Gone
    }
    when (val document = resolved) {
        EntryDocument.Loading -> Unit
        EntryDocument.Gone ->
            shell.GoneEntry(
                rawId,
                "This item isn't available. It may have been deleted.",
                null,
                "Back",
            )
        is EntryDocument.Present ->
            when (document.document.kind) {
                DocumentKind.ATTACHMENT -> FileRoute(key, rawId, shell, deps, goneTo = null)
                DocumentKind.NOTE, DocumentKind.AIOUT -> NoteRoute(key, rawId, shell, deps, goneTo = null)
                DocumentKind.CHAT ->
                    SkeinEmptyState(
                        headline = "This is a chat.",
                        primaryAction = SkeinAction("Open chat") { shell.open(document.document) },
                        modifier = Modifier.fillMaxSize().testTag(KnowledgeEntryTestTags.CHAT_SOURCE),
                    )
            }
    }
}

/**
 * The Knowledge list: the timeline's list pieces over notes, files and AI
 * outputs. A row opens its item by kind, replacing the detail (§8.3 rule 3).
 * ⌕ opens the shell's search overlay (the retired command bar's search).
 * ponytail: no inline search field, row menu or selected row yet (KNOWLEDGE_UX_SPEC.md
 * §3–§4, Wave 6), and the filter chips are composition state, not T2 (§7.3 row 19).
 */
@Composable
private fun KnowledgeList(
    shell: SkeinShellState,
    deps: KnowledgeEntryDeps,
) {
    val state =
        rememberTimelineState(
            repo = deps.repository,
            personaSource = deps.personas,
            initial = TimelineFilter(kinds = KNOWLEDGE_KINDS),
            kinds = KNOWLEDGE_KINDS,
        )
    Column(Modifier.fillMaxSize().testTag(KnowledgeEntryTestTags.LIST)) {
        shell.EntryTopBar(KnowledgeHomeKey, "Knowledge") {
            EntryAction(SkeinIcons.Search, "Search", Modifier.testTag(KnowledgeEntryTestTags.SEARCH_ACTION)) {
                shell.openSearch()
            }
            EntryAction(SkeinIcons.NewNote, "New note", Modifier.testTag(KnowledgeEntryTestTags.NEW_NOTE_ACTION)) {
                shell.newNote()
            }
        }
        val preparation by deps.preparation.collectAsState(KnowledgePreparation())
        KnowledgePreparationStatus(preparation)
        TimelineScreen(
            state = state,
            onEntryClick = { shell.open(it) },
            modifier = Modifier.weight(1f),
            zone = deps.zone,
            now = deps.clock,
            bottomContentPadding = entryBottomPadding().calculateBottomPadding(),
        )
    }
}

/** An active pass and deferred semantic work are distinct; finishing lexical work never implies both are ready. */
@Composable
private fun KnowledgePreparationStatus(preparation: KnowledgePreparation) {
    val label =
        when {
            preparation.running && preparation.processed > 0 ->
                "Preparing for search… · ${documentCount(preparation.processed)} processed"
            preparation.running -> "Preparing for search…"
            preparation.awaitingMeaningSearch > 0 ->
                "${documentCount(preparation.awaitingMeaningSearch)} awaiting search by meaning"
            else -> return
        }
    SkeinStatus(
        kind = if (preparation.running) SkeinStatusKind.Loading else SkeinStatusKind.Idle,
        label = label,
        // Progress has no known denominator. Announce once per state, not every document.
        announcement = if (preparation.running) "Preparing for search…" else label,
        modifier =
            Modifier
                .padding(horizontal = SkeinSpacing.space16, vertical = SkeinSpacing.space8)
                .testTag(KnowledgeEntryTestTags.PREPARATION_STATUS),
    )
}

private fun documentCount(count: Int): String = if (count == 1) "1 document" else "$count documents"

/** KNOWLEDGE_UX_SPEC.md §6.1: every "New note" lands in Knowledge on a draft; no row until the first commit. */
fun SkeinShellState.newNote() = navigate { goTo(it, NewNoteKey(SkeinId.random())) }

/**
 * A note (or, with [draft], a `NewNoteKey` draft whose row [DraftNoteRepository]
 * creates at the first non-blank save). Pending edits are a session pending
 * writer, so the lock commits them before the vault closes (§7.7, M8). ✦ opens
 * Connections; a wikilink or backlink follows by kind. Opened as a chat's
 * source, [goneTo] is null: its gone state returns to the chat (§6.7).
 */
@Composable
private fun NoteRoute(
    key: SkeinKey,
    rawId: String,
    shell: SkeinShellState,
    deps: KnowledgeEntryDeps,
    draft: Boolean = false,
    goneTo: Destination? = Destination.KNOWLEDGE,
) {
    val repository =
        if (draft) remember(deps.repository, rawId) { DraftNoteRepository(deps.repository, rawId) } else deps.repository
    // A draft is never gone: with no row it is an empty draft (OBJECT_LIFECYCLE_SPEC.md §3.5).
    val document = if (draft) null else rememberEntryDocument(deps.repository, rawId)
    if (document is EntryDocument.Gone) {
        shell.GoneEntry(rawId, "This note was deleted.", goneTo, goneLabel(goneTo))
        return
    }
    if (document == EntryDocument.Loading) return
    val scope = rememberCoroutineScope()
    var writer by remember { mutableStateOf<DisposableHandle?>(null) }
    val onePane = LocalSkeinWindowLayout.current.maxPanes == 1
    val hasNav = shell.navIconFor(key) != EntryNavIcon.NONE
    NoteTab(
        docId = rawId,
        vaultRepository = repository,
        indexStore = deps.indexStore,
        onOpenDocument = { id, _ -> scope.launch { shell.followById(deps.repository, id) } },
        onOpenGraph = { shell.showConnections(rawId, onePane) },
        registerFlush = { flush -> writer = shell.stores.addPendingWriter { flush(LOCK_FLUSH) } },
        unregisterFlush = {
            writer?.dispose()
            writer = null
        },
        navigationIcon = if (hasNav) ({ shell.EntryNavButton(key) }) else null,
    )
}

/** ✦ (§3.5): follows [ConnectionsKey]; on one pane the user asked for it, so the sheet opens expanded (§2.5). */
internal fun SkeinShellState.showConnections(
    rawId: String,
    onePane: Boolean,
) {
    navigate { openConnections(it, rawId) }
    val top = nav.currentStack.last()
    if (onePane && (top is ConnectionsKey || top is TransientKey)) sheets.expand(top.contentKey)
}

/**
 * Connections (KNOWLEDGE_UX_SPEC.md §10): what links here, and the way to Graph.
 * A peek after a fold on one pane. ponytail: "Linked from" only; "Links to", the
 * local graph and properties come with the Connections bead (Wave 6).
 */
@Composable
private fun ConnectionsEntry(
    key: SkeinKey,
    rawId: String,
    shell: SkeinShellState,
    deps: KnowledgeEntryDeps,
) {
    val scroll = rememberScrollState()
    if (LocalSheetMode.current == SheetMode.PEEK) {
        SheetPeekRow("Connections", Modifier.testTag(KnowledgeEntryTestTags.CONNECTIONS_PEEK))
        return
    }
    val scope = rememberCoroutineScope()
    val backlinks =
        rememberBacklinksState(rawId, deps.repository, deps.indexStore) { id ->
            scope.launch { shell.followById(deps.repository, id) }
        }
    Column(Modifier.fillMaxSize().testTag(KnowledgeEntryTestTags.CONNECTIONS)) {
        shell.EntryTopBar(key, "Connections")
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(entryBottomPadding())) {
            BacklinksDrawer(state = backlinks, initiallyExpanded = true)
            SkeinListRow(
                title = "Open in Graph",
                leadingIcon = SkeinIcons.Graph,
                onClick = { shell.navigate { openGraph(it, rawId) } },
            )
        }
    }
}

/** §3.5's way out: back to Knowledge, or (as a chat's source) back to the chat. */
internal fun goneLabel(goneTo: Destination?) = if (goneTo == null) "Back" else "Go to Knowledge"

/** Inside the lock's shared observer window (UnlockManager's 500 ms). */
private val LOCK_FLUSH: Duration = Duration.ofMillis(400)
