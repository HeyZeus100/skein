package app.skein.feature.editor.autocomplete

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Drives the `[[` wikilink autocomplete popup for any [AutocompleteHost] —
 * bd `skein-zzu` / plan `E7.I5`: "shared with chat". `SkeinEditor` and, once
 * it exists, the chat bottom bar (`E6.I8`) each construct one of these
 * against their own [AutocompleteHost] implementation; this class knows
 * nothing about either.
 *
 * Deliberately vault-free: suggestions come from the caller-supplied
 * [suggest] callback, never from a `VaultRepository` reference held here
 * (the same guardrail `EditorState` documents for wikilink resolution and
 * autosave — see its doc comment). A caller that wants ranked vault titles
 * wires `suggest = { query -> vaultRepository.searchTitles(query).map {
 * Suggestion(it.title) } }` (optionally re-ranked through [TitleMatcher])
 * outside this module.
 *
 * @param host the editing surface's [AutocompleteHost] adapter.
 * @param suggest queried with the in-progress title text (the part of the
 *   `[[` typed so far, before any `|alias`) whenever it changes; its result
 *   becomes [suggestions] (capped at [TitleMatcher.DEFAULT_LIMIT], the last
 *   slot reserved for a synthesized "Create" row once a non-blank query
 *   has no exact use for it — see [confirm]).
 * @param scope launches the (cancellable-per-keystroke) [suggest] call and
 *   [onCreate]; callers typically pass `rememberCoroutineScope()`.
 * @param onCreate invoked with the typed title when the "Create" row is
 *   confirmed — the caller's chance to call `VaultRepository.createDocument`.
 */
public class WikilinkAutocompleteState(
    private val host: AutocompleteHost,
    private val suggest: suspend (String) -> List<Suggestion>,
    private val scope: CoroutineScope,
    private val onCreate: suspend (String) -> Unit = {},
) {
    private var trigger: WikilinkTrigger? by mutableStateOf(null)
    private var suggestionsState: List<Suggestion> by mutableStateOf(emptyList())
    private var selectedIndexState: Int by mutableStateOf(0)
    private var queryJob: Job? = null

    /** True while an open `[[` is being tracked and the popup should render. */
    public val isVisible: Boolean get() = trigger != null

    /** Current rows, titles ranked by [suggest] plus a trailing "Create" row when applicable. */
    public val suggestions: List<Suggestion> get() = suggestionsState

    /** Index into [suggestions] the keyboard/tap cursor currently highlights. */
    public val selectedIndex: Int get() = selectedIndexState

    /**
     * Call whenever the host's text or caret moves (e.g. from the text
     * field's `onValueChange`). Re-derives the trigger from
     * [AutocompleteHost.textBeforeCursor] and, only when the title query
     * portion actually changed, re-invokes [suggest].
     */
    public fun onTextChanged() {
        val newTrigger = findWikilinkTrigger(host.textBeforeCursor)
        if (newTrigger == null) {
            if (trigger != null) dismiss()
            return
        }
        val queryChanged = newTrigger.query != trigger?.query
        trigger = newTrigger
        if (!queryChanged) return

        selectedIndexState = 0
        queryJob?.cancel()
        queryJob =
            scope.launch {
                val results = suggest(newTrigger.query)
                suggestionsState = buildRows(newTrigger.query, results)
            }
    }

    /** Moves the highlight to the next row, wrapping past the last one. */
    public fun moveDown() {
        if (suggestionsState.isEmpty()) return
        selectedIndexState = (selectedIndexState + 1) % suggestionsState.size
    }

    /** Moves the highlight to the previous row, wrapping past the first one. */
    public fun moveUp() {
        if (suggestionsState.isEmpty()) return
        selectedIndexState = (selectedIndexState - 1 + suggestionsState.size) % suggestionsState.size
    }

    /** Closes the popup (`Esc`/space per plan `E7.I5`) without touching the host's text. */
    public fun dismiss() {
        queryJob?.cancel()
        trigger = null
        suggestionsState = emptyList()
        selectedIndexState = 0
    }

    /** Confirms whichever row [selectedIndex] currently points at; a no-op while [isVisible] is false. */
    public fun confirmSelected() {
        val current = trigger ?: return
        val chosen = suggestionsState.getOrNull(selectedIndexState) ?: return
        confirm(current, chosen)
    }

    /** Confirms an explicitly tapped [suggestion] (independent of keyboard [selectedIndex]). */
    public fun confirm(suggestion: Suggestion) {
        val current = trigger ?: return
        confirm(current, suggestion)
    }

    private fun confirm(
        current: WikilinkTrigger,
        suggestion: Suggestion,
    ) {
        val cursor = host.textBeforeCursor.length
        val replacement =
            if (current.alias != null) {
                "[[${suggestion.title}|${current.alias}]]"
            } else {
                "[[${suggestion.title}]]"
            }
        host.replaceRange(current.start, cursor, replacement)
        if (suggestion.isCreate) {
            scope.launch { onCreate(suggestion.title) }
        }
        dismiss()
    }

    private fun buildRows(
        query: String,
        results: List<Suggestion>,
    ): List<Suggestion> {
        if (query.isBlank()) return results.take(TitleMatcher.DEFAULT_LIMIT)
        val createRow = Suggestion(title = query, isCreate = true)
        val titleRows = results.take(TitleMatcher.DEFAULT_LIMIT - 1)
        return titleRows + createRow
    }
}
