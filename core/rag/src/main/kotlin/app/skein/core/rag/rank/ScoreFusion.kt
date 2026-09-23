// `ScoreFusion` (skein-dxj, E5.I12, spec §7.2 rank step). The arithmetic
// half of the rank step: turn the three recall lists into one fused recall
// score per chunk, project that onto documents as the personalization
// vector [PersonalizedPageRank] needs, and blend recall with PPR into the
// final ranked list. [PprRanker] is the half that talks to the store.
//
// Pure Kotlin over `:core:model` value types. No I/O, no coroutines.
//
// ## Fusion is by rank, not by raw score (plan `E5.I12`)
//
// "personalization vector = per-document sum of normalized recall scores
// (RRF-style: `1/(60+rank)` per source, summed)". Reciprocal-rank fusion
// deliberately throws away each stage's *score scale* and keeps only its
// *ordering*, which is the only thing the three stages agree on: BM25's
// `score/maxScore`, cosine's `(s+1)/2` and the graph's `1/(1+hop)` are all
// nominally `[0, 1]` and all mean different things — a 0.5 cosine is an
// unrelated chunk, a 0.5 graph score is a direct neighbour. Comparing
// their ranks compares like with like.
//
// ## "No vectors yet" needs no special case
//
// `VectorRecall` returns `emptyList()` until embeddings exist (skein-079
// wires the embedder process up). Under RRF a missing stage simply
// contributes no term to the sum: a chunk found by lexical alone scores
// `1/(60+rank_lex)` instead of `1/(60+rank_lex) + 1/(60+rank_vec)`. Because
// every surviving chunk loses the same stage, the *ordering* is untouched,
// and the max-normalization in [combine] restores the surviving scores to
// `[0, 1]` with the best chunk at exactly 1.0 — the same shape the recall
// stages themselves promise. So the renormalization is per-candidate-set
// (divide by the best fused score actually achieved), not a redistribution
// of a missing stage's weight onto the others: there is no per-stage weight
// to redistribute, since RRF weights every stage equally by construction.
// Tuning that (e.g. weighting vector recall above graph recall) is
// `E10.I5`'s job, not a v1 guess.
//
// ## Determinism
//
// Sources are folded in `CitationSourceKind.entries` order rather than the
// caller's map-iteration order, so the floating-point sum for a chunk found
// by several stages is identical no matter how the caller built its map.
// Ties in the final ordering break on ascending `chunkId`, matching
// `LexicalRecall`/`VectorRecall`/`GraphRecall`.

package app.skein.core.rag.rank

import app.skein.core.model.ChunkId
import app.skein.core.model.CitationSourceKind
import app.skein.core.model.DocId
import app.skein.core.model.ScoredChunk

/**
 * Reciprocal-rank fusion of the recall stages plus the final
 * recall-versus-PPR blend. See the file header.
 *
 * @param config supplies `rrfK`, `recallWeight` and `pprWeight`.
 */
public class ScoreFusion(
    private val config: RankerConfig = RankerConfig.DEFAULT,
) {
    /**
     * Fused recall score per chunk: `Σ_sources 1 / (rrfK + rank)`, where
     * `rank` is the chunk's 1-based position in that source's list.
     *
     * A chunk listed twice by the same source is counted at its first
     * (best) rank only — de-duplication is per source as well as across
     * sources. Sources missing from [sources], or present with an empty
     * list, contribute nothing (see the file header on "no vectors yet").
     * The returned map is keyed in ascending `chunkId` order and is *not*
     * normalized; [combine] does that.
     */
    public fun fuseRecall(sources: Map<CitationSourceKind, List<ScoredChunk>>): Map<ChunkId, Double> {
        val fused = HashMap<ChunkId, Double>()
        for (kind in CitationSourceKind.entries) {
            val list = sources[kind] ?: continue
            var rank = 0
            val seen = HashSet<ChunkId>(list.size * 2)
            for (scored in list) {
                if (!seen.add(scored.chunkId)) continue
                rank++
                fused[scored.chunkId] = (fused[scored.chunkId] ?: 0.0) + 1.0 / (config.rrfK + rank)
            }
        }
        val out = LinkedHashMap<ChunkId, Double>(fused.size * 2)
        for (chunkId in fused.keys.sorted()) out[chunkId] = fused.getValue(chunkId)
        return out
    }

    /**
     * The personalization vector: per-document sum of [fusedRecall] over
     * the document's candidate chunks, normalized to sum 1 (plan
     * `E5.I12`).
     *
     * Chunks with no entry in [docByChunk] (an orphaned chunk whose
     * document row is gone) are skipped. An empty or all-zero input
     * returns `emptyMap()`, which [PersonalizedPageRank] reads as "no
     * personalization" and falls back to a uniform vector.
     */
    public fun personalization(
        fusedRecall: Map<ChunkId, Double>,
        docByChunk: Map<ChunkId, DocId>,
    ): Map<DocId, Double> {
        val perDoc = HashMap<DocId, Double>()
        for ((chunkId, score) in fusedRecall) {
            val docId = docByChunk[chunkId] ?: continue
            perDoc[docId] = (perDoc[docId] ?: 0.0) + score
        }
        var total = 0.0
        for (value in perDoc.values) total += value
        if (total <= 0.0) return emptyMap()

        val out = LinkedHashMap<DocId, Double>(perDoc.size * 2)
        for (docId in perDoc.keys.sorted()) out[docId] = perDoc.getValue(docId) / total
        return out
    }

    /**
     * The final blend (plan `E5.I12`): `0.6 × fusedRecall(chunk) + 0.4 ×
     * ppr(doc(chunk))`, each component max-normalized over the candidate
     * set first so the two weights mean what they say — raw RRF sums live
     * around `1/60` and raw PPR mass around `1/n`, so blending them
     * unnormalized would silently make the weights depend on the candidate
     * count.
     *
     * With both components in `[0, 1]` and weights summing to 1, the
     * returned scores are in `[0, 1]`. Ordering is by descending score,
     * then ascending `chunkId`; the list is de-duplicated by `chunkId` (it
     * is keyed by one) and truncated to [k]. A non-positive [k] returns
     * `emptyList()`, mirroring the recall stages' own guard.
     *
     * Chunks missing from [docByChunk], or whose document is missing from
     * [ppr], contribute a PPR component of 0 rather than being dropped —
     * dropping is the persona filter's job ([PprRanker]), not this
     * function's.
     */
    public fun combine(
        fusedRecall: Map<ChunkId, Double>,
        docByChunk: Map<ChunkId, DocId>,
        ppr: Map<DocId, Double>,
        k: Int,
    ): List<ScoredChunk> {
        if (k <= 0 || fusedRecall.isEmpty()) return emptyList()

        var maxRecall = 0.0
        for (value in fusedRecall.values) if (value > maxRecall) maxRecall = value
        var maxPpr = 0.0
        for (chunkId in fusedRecall.keys) {
            val value = docByChunk[chunkId]?.let { ppr[it] } ?: 0.0
            if (value > maxPpr) maxPpr = value
        }

        val weightTotal = config.recallWeight + config.pprWeight
        val out = ArrayList<ScoredChunk>(fusedRecall.size)
        for ((chunkId, recall) in fusedRecall) {
            val normalizedRecall = if (maxRecall > 0.0) recall / maxRecall else 0.0
            val rawPpr = docByChunk[chunkId]?.let { ppr[it] } ?: 0.0
            val normalizedPpr = if (maxPpr > 0.0) rawPpr / maxPpr else 0.0
            val score = (config.recallWeight * normalizedRecall + config.pprWeight * normalizedPpr) / weightTotal
            out += ScoredChunk(chunkId = chunkId, score = score)
        }
        out.sortWith(compareByDescending<ScoredChunk> { it.score }.thenBy { it.chunkId })
        return if (out.size <= k) out else out.subList(0, k).toList()
    }
}
