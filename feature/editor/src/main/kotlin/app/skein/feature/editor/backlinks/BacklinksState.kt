// `BacklinksState` (bd `skein-9jj`, plan `E7.I8`, spec §8.5): state holder
// for the collapsible backlinks drawer at the bottom of every note. Same
// shape as `TimelineState`/`EditorState` — pure Kotlin (no Compose
// imports), driven by an already-open `VaultRepository`/`IndexStore` pair
// the caller injects; this module never opens or unlocks a vault itself
// (same guardrail `EditorState`'s header documents for `:feature:editor`).
//
// ## Query shape
//
// For the current [docId], backlinks are every `WIKILINK` edge whose
// `dst_id` names this document — see `Edge`'s kdoc
// (`core/model/.../Vault.kt`) for the node-id convention. Per the plan
// text: "context snippet (the chunk containing the link, via
// `chunksForDocs` + a local search for `[[title`)". [chunksForDocs] +
// [excerptFor] implement exactly that: the first chunk containing
// `"[[<title>"` (case-insensitive) wins the excerpt; [linkCount] counts
// every occurrence across that source document's chunks.
//
// ## Unresolved-sentinel backlinks
//
// `EdgeUpserter` (`core/vault`, bd `skein-ys2`) writes an edge whose
// `dst_id` is the *document id* only when the wikilink target resolves at
// index time; a link to a not-yet-existing title is stored under the
// sentinel `dst_id = "title:<lowercased target>"` (see that file's header
// for the full rationale) and is **not** retroactively rewritten when a
// document with that title is later created — that resolver pass is
// explicitly out of `skein-ys2`'s scope and hasn't landed yet. Without
// accounting for this, a note created *after* another note already linked
// to its title would show zero backlinks despite the link existing.
// [queryBacklinks] therefore issues two `edgesTo` reads per query — the
// document's own id, and [titleSentinel] of its current title — and
// unions the source ids. The sentinel format is duplicated here (as
// [titleSentinel]) rather than pulled in via a `:core:vault` dependency:
// it's a documented `Edge` node-id convention, not an `EdgeUpserter`
// implementation detail, and this module deliberately stays free of
// `:core:vault` (bd `skein-03f` guardrails extend to every `:feature:*`
// surface that isn't the vault-owning coordinator).
//
// ## Live updates: two merged invalidation signals
//
// [invalidationTicks] merges two sources, and needs both.
//
//  1. **`IndexStore.observeChanges()`** (bd `skein-rkxi`) — the primary
//     signal. `IndexStoreImpl` publishes an `IndexChange` after the
//     outermost commit of every index write, so a `WIKILINK`-kind
//     `EdgesReplaced` is exactly "somebody's wikilinks were just
//     re-indexed". This closes the gap the original `skein-9jj`
//     implementation documented: when edge re-indexing lands
//     asynchronously, well after the triggering document write committed
//     (a background `IngestWorker`), the edge write now ticks on its own
//     account instead of leaving the drawer stale until the next
//     unrelated document write.
//
//     The event names the edges' *source*, not their destination, so it
//     cannot say whether this note was affected — the filter is therefore
//     "any `EdgesReplaced` that nominated `WIKILINK`", and the drawer
//     re-queries and lets the result speak. That is the same fan-out
//     `observeTimeline` already had, narrowed from "any document write
//     anywhere" to "any wikilink re-index anywhere". Chunk-level changes
//     (`ChunksReplaced`) are deliberately *not* subscribed: they fire per
//     document for every ingest pass, and the only thing they affect here
//     is an excerpt whose source-document body write already ticked (2).
//
//  2. **[VaultRepository.observeTimeline]** — kept as a fallback, not
//     removed. It is the only one of the two that emits an initial value
//     at subscription time (`onStart { emit(Unit) }` in
//     `VaultRepositoryImpl.changeTicks`), so it is what seeds the drawer's
//     very first query; `observeChanges` has no replay by contract. It
//     also still covers renames and deletes of the *target* note, which
//     change [titleSentinel] and the target title the excerpt search looks
//     for without any edge being rewritten.
//
// Both flows remain `core/model` contracts. `ChangeBus`/`TableChange`
// (`core/vault/.../repository/ChangeBus.kt`) stay `core/vault`-internal
// and this module still has no `:core:vault` dependency (bd `skein-03f`).
package app.skein.feature.editor.backlinks

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import us.aherrera.skein.core.model.Chunk
import us.aherrera.skein.core.model.DocId
import us.aherrera.skein.core.model.Document
import us.aherrera.skein.core.model.EdgeKind
import us.aherrera.skein.core.model.IndexChange
import us.aherrera.skein.core.model.IndexStore
import us.aherrera.skein.core.model.TimelineFilter
import us.aherrera.skein.core.model.VaultRepository

/** One row of the backlinks drawer: a document that links to the current note. */
public data class BacklinkGroup(
    val document: Document,
    /** The chunk excerpt around the first `[[<title>` occurrence (or a plain fallback if none was found). */
    val excerpt: String,
    /** Number of `[[<title>` occurrences found across [document]'s chunks — badge on the row, floor of 1. */
    val linkCount: Int,
)

/**
 * State holder for [BacklinksDrawer]. See the file header for the query
 * shape, unresolved-sentinel handling, and the live-update mechanism.
 *
 * @param initialDocId the note this drawer starts showing backlinks for.
 * @param vaultRepository already-open; used for `getDocument` (target
 *   title + resolving each backlinking source) and `observeTimeline` (the
 *   live-update trigger — see file header).
 * @param indexStore already-open; used for `edgesTo`, `chunksForDocs`,
 *   and `observeChanges` (the primary live-update trigger — see file
 *   header).
 * @param scope owner of the shared [backlinks] flow — a Compose
 *   `rememberCoroutineScope()` in the app, `backgroundScope` in tests.
 * @param onOpen invoked with a backlinking document's id on row tap — the
 *   caller (a note-tab host that already holds a repository reference)
 *   decides whether that means a preview tab, a pinned tab, or something
 *   else. Mirrors `EditorState.onLinkOpen`'s "the state hands back an id,
 *   the parent decides" shape.
 */
public class BacklinksState(
    initialDocId: DocId,
    private val vaultRepository: VaultRepository,
    private val indexStore: IndexStore,
    scope: CoroutineScope,
    public val onOpen: (DocId) -> Unit = {},
) {
    private val docIdState = MutableStateFlow(initialDocId)

    /** The note this drawer currently shows backlinks for. */
    public val docId: DocId get() = docIdState.value

    /** Backlinking documents, grouped one row per source document. Empty when there are none (yet). */
    @OptIn(ExperimentalCoroutinesApi::class)
    public val backlinks: StateFlow<List<BacklinkGroup>> =
        docIdState
            .flatMapLatest { id -> invalidationTicks().map { queryBacklinks(id) } }
            .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    /**
     * Switches the drawer to a different note (e.g. the host reuses one
     * drawer instance across tab switches) — re-queries immediately for
     * [newDocId] rather than showing the previous note's stale rows.
     */
    public fun switchDocument(newDocId: DocId) {
        docIdState.value = newDocId
    }

    /** Row tap — routes [document]'s id through [onOpen]. */
    public fun open(document: Document) {
        onOpen(document.id)
    }

    /** See the file header: the index stream is the primary signal, the timeline tick the seed and fallback. */
    private fun invalidationTicks(): Flow<Unit> =
        merge(
            vaultRepository.observeTimeline(TimelineFilter(), limit = 1).map {},
            indexStore
                .observeChanges()
                .filter { it is IndexChange.EdgesReplaced && EdgeKind.WIKILINK in it.kinds }
                .map {},
        )

    private suspend fun queryBacklinks(target: DocId): List<BacklinkGroup> {
        val targetDoc = vaultRepository.getDocument(target) ?: return emptyList()

        val srcIds = LinkedHashSet<DocId>()
        indexStore.edgesTo(target, EdgeKind.WIKILINK).forEach { srcIds += it.srcId }
        indexStore.edgesTo(titleSentinel(targetDoc.title), EdgeKind.WIKILINK).forEach { srcIds += it.srcId }
        if (srcIds.isEmpty()) return emptyList()

        val chunksByDoc = indexStore.chunksForDocs(srcIds, limitPerDoc = CHUNK_LIMIT_PER_DOC).groupBy { it.docId }
        val needle = "[[${targetDoc.title.lowercase()}"

        return srcIds.mapNotNull { srcId ->
            val doc = vaultRepository.getDocument(srcId) ?: return@mapNotNull null
            val (excerpt, matches) = excerptFor(chunksByDoc[srcId].orEmpty(), needle)
            BacklinkGroup(document = doc, excerpt = excerpt, linkCount = maxOf(matches, 1))
        }
    }

    /** First-match excerpt + total occurrence count of [needle] (case-insensitive) across [chunks]. */
    private fun excerptFor(
        chunks: List<Chunk>,
        needle: String,
    ): Pair<String, Int> {
        var total = 0
        var firstExcerpt: String? = null
        for (chunk in chunks.sortedBy { it.ord }) {
            val hay = chunk.text.lowercase()
            var from = 0
            while (true) {
                val at = hay.indexOf(needle, from)
                if (at < 0) break
                total++
                if (firstExcerpt == null) firstExcerpt = windowAround(chunk.text, at)
                from = at + needle.length
            }
        }
        val firstChunkText = chunks.firstOrNull()?.text.orEmpty()
        val fallback = firstChunkText.take(EXCERPT_FALLBACK_LENGTH)
        return (firstExcerpt ?: fallback) to total
    }

    private fun windowAround(
        text: String,
        matchIndex: Int,
    ): String {
        val start = maxOf(0, matchIndex - EXCERPT_CONTEXT_CHARS)
        val end = minOf(text.length, matchIndex + EXCERPT_CONTEXT_CHARS)
        return text.substring(start, end).trim()
    }

    public companion object {
        // Five seconds without a subscriber before the underlying query
        // tears down — same rationale/value as `TimelineState`.
        internal const val STOP_TIMEOUT_MILLIS: Long = 5_000L
        private const val CHUNK_LIMIT_PER_DOC: Int = 20
        private const val EXCERPT_CONTEXT_CHARS: Int = 60
        private const val EXCERPT_FALLBACK_LENGTH: Int = 120

        /**
         * `edges.dst_id` sentinel for an unresolved wikilink target. Mirrors
         * `EdgeUpserter.unresolvedTarget` (`core/vault`, bd `skein-ys2`) —
         * see the file header for why it's duplicated here instead of
         * imported.
         */
        internal fun titleSentinel(title: String): String = "title:${title.lowercase()}"
    }
}
