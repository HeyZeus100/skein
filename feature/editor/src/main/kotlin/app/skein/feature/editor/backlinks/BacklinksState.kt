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
// ## Live updates without `ChangeBus`
//
// The plan says backlinks "update live via the `ChangeBus`", but
// `ChangeBus`/`TableChange` (`core/vault/.../repository/ChangeBus.kt`)
// are `core/vault`-internal and not part of the `VaultRepository`/
// `IndexStore` contracts this module is allowed to depend on — and
// `IndexStoreImpl` documents its own `ChangeBus` slot as still
// *reserved*, not wired to anything yet (see that file's header, "E7.I5").
// There is therefore no edge-level invalidation stream to subscribe to
// today. The next best available signal is
// [VaultRepository.observeTimeline]: both the real and in-memory
// implementations tick that flow on *every* committed `documents` write,
// not just the current document's — see `VaultRepositoryImpl
// .observeTimeline` (`changeTicks { it is TableChange.Documents }`, no
// `docId` filter) and `InMemoryVaultRepository`'s equivalent. Editing a
// source note (the write that precedes re-indexing its wikilinks) is
// itself such a write, so re-querying `edgesTo` on every timeline tick
// (with `limit = 1` — only the tick matters, not the page contents) does
// catch "a link was added elsewhere" without the caller reopening this
// note. The gap this doesn't close: if edge re-indexing runs
// asynchronously well after the triggering document write commits (e.g.
// a background `IngestWorker`), a tick that fires before the edge write
// lands won't be followed by another one once the edge write alone
// completes, since `IndexStore` has no wired signal of its own. Closing
// that gap needs `IndexStoreImpl`'s reserved `ChangeBus` slot to actually
// wire up and surface through the `IndexStore` interface — tracked as
// follow-on work, not this bead's to fix under its no-`core/vault`-edits
// constraint.
package app.skein.feature.editor.backlinks

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import us.aherrera.skein.core.model.Chunk
import us.aherrera.skein.core.model.DocId
import us.aherrera.skein.core.model.Document
import us.aherrera.skein.core.model.EdgeKind
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
 * @param indexStore already-open; used for `edgesTo` and `chunksForDocs`.
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

    private fun invalidationTicks(): Flow<Unit> = vaultRepository.observeTimeline(TimelineFilter(), limit = 1).map {}

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
