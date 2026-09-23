package app.skein.feature.shell.nav

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import app.skein.core.model.DocId
import app.skein.core.model.DocumentKind
import app.skein.core.model.VaultRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/** One plain-text search hit — title or body — tagged with [kind] for the results list's glyph (spec §8.2). */
data class SearchResult(
    val docId: DocId,
    val title: String,
    val kind: DocumentKind,
)

/**
 * Non-drawer half of the command bar (spec §8.2/§8.4, plan `E6.I4` slice
 * A): plain-text live search — title hits via [VaultRepository.searchTitles]
 * before body hits via [VaultRepository.searchBodies], debounced 150ms —
 * and `/` command-palette matching against a [CommandRegistry]. Kept
 * separate from [NavState] (the pure drawer/destination/query state
 * machine [NavStateTest] already covers) because this class is the one
 * that needs a [VaultRepository] and a coroutine scope; it's exercised the
 * same way `EditorAutosaveTest` drives `EditorState` —
 * `runTest`/`backgroundScope` for virtual debounce time, no Compose UI test
 * required.
 *
 * The query text itself still lives on [NavState] (`CommandBarHost` chains
 * [onQueryChanged] after `NavState.setQuery`, so there is exactly one
 * source of truth for what's typed) — this class only reacts to it.
 * [vaultRepository] is nullable so a host with none wired yet (a preview,
 * or `SkeinApp`'s own no-arg default) still renders a working, search-less
 * command bar instead of crashing.
 *
 * Never logs [SearchResult]/query text anywhere (spec §9): the only I/O
 * this class performs is [VaultRepository.searchTitles]/[searchBodies]/
 * [Command.run], none of which touch `SkeinLog`.
 */
@Stable
class CommandBarState(
    private val vaultRepository: VaultRepository?,
    private val registry: CommandRegistry,
    private val onOpenPreview: (docId: DocId, title: String) -> Unit,
    private val searchDebounceMs: Long = 150L,
    searchScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private var queryState: String by mutableStateOf("")

    var results: List<SearchResult> by mutableStateOf(emptyList())
        private set

    init {
        searchScope.launch {
            snapshotFlow { queryState }
                .debounce(searchDebounceMs)
                .collect { text -> performSearch(text) }
        }
    }

    /** `/` leading character — the same recognition [NavState.isCommand] uses; kept local so this class needs no `NavState` reference. */
    val isCommand: Boolean get() = queryState.startsWith("/")

    /** Palette entries matching what's typed after `/` ([CommandRegistry.filter]); empty outside command mode. */
    val paletteCommands: List<Command>
        get() = if (isCommand) registry.filter(queryState.removePrefix("/")) else emptyList()

    /** Called on every command-bar keystroke — see class kdoc. */
    fun onQueryChanged(text: String) {
        queryState = text
        if (text.startsWith("/") || text.isBlank()) results = emptyList()
    }

    private suspend fun performSearch(text: String) {
        val repo = vaultRepository
        if (repo == null || text.isBlank() || text.startsWith("/")) {
            results = emptyList()
            return
        }
        val titleHits = repo.searchTitles(text).map { SearchResult(it.id, it.title, it.kind) }
        val titleIds = titleHits.mapTo(mutableSetOf()) { it.docId }
        val bodyHits =
            repo
                .searchBodies(text)
                .map { it.document }
                .filterNot { it.id in titleIds }
                .map { SearchResult(it.id, it.title, it.kind) }
        results = titleHits + bodyHits
    }

    /**
     * Enter key: in command mode, runs the [CommandRegistry.match]ed
     * [Command] with its argument; in text mode, opens the top [results]
     * hit as a preview tab (spec §8.2: "Enter opens the top hit as a
     * preview tab"). Returns whether anything ran, so the caller
     * (`CommandBarHost`) knows whether to clear the query.
     */
    suspend fun onSubmit(): Boolean {
        if (isCommand) {
            val (command, arg) = registry.match(queryState.removePrefix("/")) ?: return false
            command.run(arg)
            return true
        }
        val top = results.firstOrNull() ?: return false
        onOpenPreview(top.docId, top.title)
        return true
    }

    /** A tapped result row opens the same way Enter would open the top hit. */
    fun openResult(result: SearchResult) = onOpenPreview(result.docId, result.title)
}
