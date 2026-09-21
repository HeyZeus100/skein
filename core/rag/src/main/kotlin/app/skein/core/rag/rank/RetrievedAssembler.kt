// `RetrievedAssembler` (skein-wqli, E5.I13 prep). Bridges the fused,
// ranked `List<ScoredChunk>` that [PprRanker.rank] / [ScoreFusion.combine]
// return onto `us.aherrera.skein.core.model.Retrieved` (locked verbatim by
// `E0.I12`/skein-x4f), so `RetrievalServiceImpl` (`E5.I13`, skein-do6) can
// call `PprRanker.rank` and this assembler and be done — no re-deriving a
// document id, title or provenance set from a chunk id by hand.
//
// This is additive: it does not touch `PprRanker`, `ScoreFusion` or any
// recall stage's public signature. It lives in `app.skein.core.rag.rank`
// (not `us.aherrera.skein.core.model`) — bridging `core/rag`'s producer
// types to `core/model`'s locked consumer type is exactly the "namespace"
// note on skein-wqli; the two packages stay where they are.
//
// ## Inputs, and why no new store call is invented
//
// [assemble] takes the same two things `RetrievalServiceImpl` already has
// once it has called `PprRanker.rank`:
//   - `ranked`: that call's own return value, already ordered (descending
//     score, ascending `chunkId` tie-break) and capped at `k` — this class
//     preserves that order and count exactly; it never re-sorts, re-caps
//     or drops a survivor except when a store lookup below comes back
//     empty.
//   - `sources`: the `Map<CitationSourceKind, List<ScoredChunk>>` the
//     caller already built to *pass to* `PprRanker.rank` (one entry per
//     recall stage that ran). [Retrieved.recalledBy] is derived from it via
//     [RecallSource.citationSourceKind] (`core/model/.../Retrieval.kt`) run
//     in reverse — the one existing bridge between the two enums, not a
//     second one invented here. `CitationSourceKind.RERANK` has no
//     [RecallSource] counterpart (a rerank stage reorders, it does not
//     recall — see that enum's kdoc) and contributes nothing.
//
// ## Hydration, batched
//
// `Retrieved.docTitle`/`sourceKind` need each hit's `Document`, and
// `Retrieved.text`/`docId` need its `Chunk` row. `PprRanker.rank` already
// looked both up internally (`docByChunk`) but does not expose them —
// exposing internal state was not what plan `E5.I12` locked, and re-deriving
// it externally is exactly `E5.I13`'s job per that class's own file header
// ("`E5.I13` … will map this list onto [`Retrieved`] — with the document
// id, locator and source kind it hydrates from the store at that point").
// So this class re-fetches, the same way `PprRanker.docByChunk` and
// `RetrievalServiceImplTest`'s spec both do it: one batched
// `IndexStore.getChunks(chunkIds)` call for every chunk, then one
// `VaultRepository.getDocument` call per *distinct* document id (`ranked`
// is already ≤ `k`, typically 8, and multiple chunks routinely share a
// document, so deduping here is a real savings) — never one store call per
// ranked chunk.
//
// ## locator / revisionHash
//
// Both stay `null`. `Retrieved.locator` would come from a chunk's stored
// byte offsets, but the v1 `chunks` table (`001_initial.sql`) has no such
// columns — `id, doc_id, ord, text, token_count, embedder_id,
// embedder_version` only, and `us.aherrera.skein.core.model.Chunk` mirrors
// exactly that. The *chunker's* own `app.skein.core.rag.chunk.Chunk` does
// carry `[start, end)`, but those offsets exist only during ingest
// (`IngestSteps`) and are not persisted anywhere this class — running
// post-recall, against already-committed chunks — can read back. So there
// is nothing to populate `locator` from yet; it is additive over plan
// `§4.3` for exactly this reason (see `Retrieval.kt`'s kdoc on the field).
// `Retrieved.revisionHash` is `null` until `skein-uo5n`'s Migration 003
// (`document_revisions` + `chunks.revision_hash`) lands and a producer for
// it exists in the repository layer.

package app.skein.core.rag.rank

import us.aherrera.skein.core.model.ChunkId
import us.aherrera.skein.core.model.CitationSourceKind
import us.aherrera.skein.core.model.DocId
import us.aherrera.skein.core.model.Document
import us.aherrera.skein.core.model.IndexStore
import us.aherrera.skein.core.model.RecallSource
import us.aherrera.skein.core.model.Retrieved
import us.aherrera.skein.core.model.ScoredChunk
import us.aherrera.skein.core.model.VaultRepository

/**
 * Bridges [PprRanker]/[ScoreFusion]'s `List<ScoredChunk>` output to
 * `List<Retrieved>`. See the file header for the mapping and for why
 * [locator]/`revisionHash` stay null.
 *
 * @param index backs the batched chunk hydration (`getChunks`).
 * @param repo backs the per-document hydration (`getDocument`, deduped).
 */
public class RetrievedAssembler(
    private val index: IndexStore,
    private val repo: VaultRepository,
) {
    /**
     * Maps [ranked] onto [Retrieved], preserving its order and size exactly
     * except for a ranked chunk whose `Chunk` row or `Document` can no
     * longer be read back (an orphan, or a document deleted between rank
     * and assemble) — that entry is dropped rather than surfaced half-null
     * or thrown.
     *
     * [sources] is the per-recall-stage map the caller already built for
     * [PprRanker.rank]; it drives [Retrieved.recalledBy] only and need not
     * contain every id in [ranked] (a chunk [PprRanker] surfaced via the
     * PPR blend alone, with no recall stage of its own — impossible under
     * the current pipeline since every candidate originates in `sources`,
     * but not assumed here — simply gets an empty [Retrieved.recalledBy]).
     *
     * An empty [ranked] returns `emptyList()` without calling the store.
     */
    public suspend fun assemble(
        ranked: List<ScoredChunk>,
        sources: Map<CitationSourceKind, List<ScoredChunk>>,
    ): List<Retrieved> {
        if (ranked.isEmpty()) return emptyList()

        val recalledByChunk = recalledBy(sources)
        val chunksById = index.getChunks(ranked.map { it.chunkId })
        val documentsByDocId = hydrateDocuments(chunksById.values.map { it.docId })

        val out = ArrayList<Retrieved>(ranked.size)
        for (scored in ranked) {
            val chunk = chunksById[scored.chunkId] ?: continue
            val document = documentsByDocId[chunk.docId] ?: continue
            out +=
                Retrieved(
                    chunkId = scored.chunkId,
                    docId = chunk.docId,
                    docTitle = document.title,
                    text = chunk.text,
                    score = scored.score,
                    sourceKind = document.kind,
                    recalledBy = recalledByChunk[scored.chunkId] ?: emptySet(),
                    revisionHash = null,
                    locator = null,
                )
        }
        return out
    }

    /** One [VaultRepository.getDocument] call per distinct id in [docIds], never per chunk. */
    private suspend fun hydrateDocuments(docIds: Collection<DocId>): Map<DocId, Document> {
        val out = LinkedHashMap<DocId, Document>()
        for (docId in docIds.toSet()) {
            repo.getDocument(docId)?.let { out[docId] = it }
        }
        return out
    }

    /**
     * The reverse of [RecallSource.citationSourceKind]: which [RecallSource]
     * values, if any, listed each chunk id. [CitationSourceKind.RERANK] has
     * no [RecallSource] counterpart and is skipped.
     */
    private fun recalledBy(sources: Map<CitationSourceKind, List<ScoredChunk>>): Map<ChunkId, Set<RecallSource>> {
        if (sources.isEmpty()) return emptyMap()
        val out = HashMap<ChunkId, MutableSet<RecallSource>>()
        for ((kind, list) in sources) {
            val recallSource = RECALL_SOURCE_BY_CITATION_KIND[kind] ?: continue
            for (scored in list) {
                out.getOrPut(scored.chunkId) { mutableSetOf() } += recallSource
            }
        }
        return out
    }

    private companion object {
        /** Built once from the single locked bridge, [RecallSource.citationSourceKind] — not re-specified here. */
        val RECALL_SOURCE_BY_CITATION_KIND: Map<CitationSourceKind, RecallSource> =
            RecallSource.entries.associateBy { it.citationSourceKind }
    }
}
