// `PprRanker` (skein-dxj, E5.I12, spec §7.2 rank step). The step between
// recall and the context budget: it takes the three recall lists
// (`LexicalRecall`, `VectorRecall`, `GraphRecall` — all in the sibling
// `app.skein.core.rag.recall` package) and returns one ranked, persona-
// filtered, de-duplicated candidate list.
//
// Pure Kotlin over the `IndexStore` / `VaultRepository` interfaces from
// `:core:model` — no SQL of its own, no dependency on `:core:vault`,
// mirroring the recall stages' shape. The arithmetic lives in
// [ScoreFusion] and [PersonalizedPageRank]; this class is the part that
// reads the store.
//
// ## The pipeline (plan `E5.I12`, in order)
//
//   1. **Union** the recall lists (≤ 90 chunks: 3 sources × their own
//      `DEFAULT_K = 30`).
//   2. **Persona filter, before ranking.** Drop chunks whose document's
//      `persona_id` is neither null nor the requested persona —
//      persona-less documents are shared. Filtering happens on the recall
//      lists themselves, so the RRF ranks in step 3 are ranks *among
//      surviving chunks*: a chunk is never penalised for sitting behind a
//      chunk the caller is not allowed to see.
//   3. **Fuse** the surviving lists by reciprocal rank ([ScoreFusion]).
//   4. **Build the ranking graph** from the candidate documents plus their
//      1-hop neighbours — `IndexStore.neighborhood(seeds, hops = 1,
//      maxNodes = seeds + 200)`. This is the only graph read the rank step
//      makes, and it is why the step is bounded to the recalled
//      neighbourhood rather than the whole vault: nothing outside those
//      edges is ever scored, no matter how large the vault is.
//   5. **Personalized PageRank** over that graph, personalized on the
//      per-document fused recall mass ([PersonalizedPageRank]).
//   6. **Blend** `0.6 × fusedRecall + 0.4 × ppr(doc)` and sort
//      (descending score, ascending `chunkId`), capped at `k`.
//
// ## Why `List<ScoredChunk>` and not a citation-shaped type
//
// `docs/design/POST_REVIEW_RESOLUTIONS.md` §1.3 requires the *retrieval
// payload* to carry `(documentId, revisionHash, locator, excerpt,
// sourceKind)`. Two of those the rank step structurally cannot supply:
// `Locator`'s `byteStart`/`byteEnd` exist only on the chunker's own
// `app.skein.core.rag.chunk.Chunk` (see its file header — "IngestSteps is
// expected to … derive `Locator.byteStart`/`byteEnd`"), not on the
// persisted `core.model.Chunk` this class reads back from `IndexStore`,
// and `revisionHash` comes from the `document_revisions` table that is
// Migration 003 and not in v1's DDL. Inventing a half-populated record
// here would be inventing fields, which §1 explicitly rules out. So the
// rank step returns the same `List<ScoredChunk>` the three recall stages
// speak in, under the same ordering convention, and `E0.I12` (skein-x4f,
// still OPEN) formalizes the `RetrievalService` / `Retrieved` types that
// `E5.I13`'s `RetrievalServiceImpl` will map this list onto — with the
// document id, locator and source kind it hydrates from the store at that
// point. `docByChunk` is already computed here for the persona filter, so
// that mapping costs `E5.I13` nothing extra.

package app.skein.core.rag.rank

import us.aherrera.skein.core.model.ChunkId
import us.aherrera.skein.core.model.CitationSourceKind
import us.aherrera.skein.core.model.DocId
import us.aherrera.skein.core.model.Edge
import us.aherrera.skein.core.model.IndexStore
import us.aherrera.skein.core.model.PersonaId
import us.aherrera.skein.core.model.ScoredChunk
import us.aherrera.skein.core.model.VaultRepository

/**
 * Personalized-PageRank ranker and score fusion (spec §7.2 rank step). See
 * the file header for the pipeline and for why the result is a plain
 * `List<ScoredChunk>`.
 *
 * @param index backs chunk hydration (`getChunks`) and the 1-hop graph
 *   expansion (`neighborhood`).
 * @param repo backs the persona filter (`getDocument`).
 * @param config every numeric knob — see [RankerConfig], tuned in `E10.I5`.
 */
public class PprRanker(
    private val index: IndexStore,
    private val repo: VaultRepository,
    private val config: RankerConfig = RankerConfig.DEFAULT,
) {
    private val fusion = ScoreFusion(config)
    private val pageRank = PersonalizedPageRank(config)

    /**
     * Ranks the union of [sources] and returns at most [k] chunks, ordered
     * by descending score then ascending `chunkId`, de-duplicated by
     * `chunkId`.
     *
     * [sources] is keyed by the stage that produced each list —
     * `CitationSourceKind.LEXICAL` / `VECTOR` / `GRAPH` (and `RERANK`,
     * once a reranker exists; it fuses like any other source). A stage
     * that found nothing may be omitted or passed empty: under reciprocal-
     * rank fusion a missing stage contributes no term, which is exactly
     * what "no vectors yet" (`VectorRecall` before skein-079 wires up the
     * embedder) should mean — see [ScoreFusion]'s file header.
     *
     * [personaId] is the persona the retrieval is running under. A chunk
     * survives only if its document's `personaId` is `null` (shared) or
     * equal to [personaId]; **passing `null` therefore admits only shared
     * documents**, which is the plan's rule read literally ("drop chunks
     * whose document `persona_id` is neither null nor the given persona").
     * A chunk whose document row cannot be read at all is dropped too — an
     * orphan cannot be persona-checked, and surfacing it would be the
     * riskier of the two failure modes.
     *
     * Returns `emptyList()` for a non-positive [k], for empty [sources],
     * and when the persona filter removes every candidate — in the last
     * two cases without touching the graph at all.
     */
    public suspend fun rank(
        sources: Map<CitationSourceKind, List<ScoredChunk>>,
        personaId: PersonaId? = null,
        k: Int = config.defaultK,
    ): List<ScoredChunk> {
        if (k <= 0) return emptyList()

        val candidateIds = candidateIds(sources)
        if (candidateIds.isEmpty()) return emptyList()

        val docByChunk = docByChunk(candidateIds, personaId)
        if (docByChunk.isEmpty()) return emptyList()

        val survivors = filterSources(sources, docByChunk.keys)
        val fusedRecall = capCandidates(fusion.fuseRecall(survivors))
        if (fusedRecall.isEmpty()) return emptyList()

        val fusedDocByChunk =
            if (fusedRecall.size == docByChunk.size) {
                docByChunk
            } else {
                docByChunk.filterKeys { it in fusedRecall }
            }

        val personalization = fusion.personalization(fusedRecall, fusedDocByChunk)
        val edges = neighbourhood(personalization.keys)
        val ppr = pageRank.rank(edges, personalization)

        return fusion.combine(fusedRecall, fusedDocByChunk, ppr, k)
    }

    /** Union of every source's chunk ids, in ascending order (determinism anchor). */
    private fun candidateIds(sources: Map<CitationSourceKind, List<ScoredChunk>>): List<ChunkId> {
        val ids = HashSet<ChunkId>()
        for (kind in CitationSourceKind.entries) {
            val list = sources[kind] ?: continue
            for (scored in list) ids += scored.chunkId
        }
        return ids.sorted()
    }

    /**
     * Hydrates each candidate chunk's document id and applies the persona
     * filter (see [rank]'s kdoc). Every document is read once, not once
     * per chunk.
     */
    private suspend fun docByChunk(
        candidateIds: List<ChunkId>,
        personaId: PersonaId?,
    ): Map<ChunkId, DocId> {
        val chunks = index.getChunks(candidateIds)
        if (chunks.isEmpty()) return emptyMap()

        val verdictByDoc = HashMap<DocId, Boolean>()
        val out = LinkedHashMap<ChunkId, DocId>(chunks.size * 2)
        for (chunkId in candidateIds) {
            val docId = chunks[chunkId]?.docId ?: continue
            val allowed =
                verdictByDoc.getOrPut(docId) {
                    val document = repo.getDocument(docId)
                    document != null && (document.personaId == null || document.personaId == personaId)
                }
            if (allowed) out[chunkId] = docId
        }
        return out
    }

    /** Drops filtered-out chunks from each source list, preserving each list's order. */
    private fun filterSources(
        sources: Map<CitationSourceKind, List<ScoredChunk>>,
        survivors: Set<ChunkId>,
    ): Map<CitationSourceKind, List<ScoredChunk>> {
        val out = LinkedHashMap<CitationSourceKind, List<ScoredChunk>>(sources.size * 2)
        for (kind in CitationSourceKind.entries) {
            val list = sources[kind] ?: continue
            out[kind] =
                if (list.all { it.chunkId in survivors }) list else list.filter { it.chunkId in survivors }
        }
        return out
    }

    /**
     * Defensive `maxCandidates` cap (plan `E5.I12`: "the union of the
     * three recall lists (≤ 90 chunks)"). A caller fanning in wider than
     * the plan's defaults keeps its best chunks by fused recall rather
     * than being rejected — and the graph step stays bounded.
     */
    private fun capCandidates(fusedRecall: Map<ChunkId, Double>): Map<ChunkId, Double> {
        if (fusedRecall.size <= config.maxCandidates) return fusedRecall
        val kept =
            fusedRecall.entries
                .sortedWith(compareByDescending<Map.Entry<ChunkId, Double>> { it.value }.thenBy { it.key })
                .take(config.maxCandidates)
                .map { it.key }
                .sorted()
        val out = LinkedHashMap<ChunkId, Double>(kept.size * 2)
        for (chunkId in kept) out[chunkId] = fusedRecall.getValue(chunkId)
        return out
    }

    /** Step 4: the candidate documents plus their [RankerConfig.neighborHops]-hop neighbours, node-capped. */
    private suspend fun neighbourhood(seeds: Set<DocId>): List<Edge> {
        if (seeds.isEmpty() || config.neighborHops == 0) return emptyList()
        return index.neighborhood(
            seeds = seeds,
            hops = config.neighborHops,
            maxNodes = seeds.size + config.maxNeighborNodes,
        )
    }
}
