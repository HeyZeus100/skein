// `ScoreFusion` (skein-dxj, plan `E5.I12`) tests — pure arithmetic, so
// every expectation here is a closed form rather than an approximation:
// RRF at the plan's `k = 60` scores rank 1 at `1/61`, rank 2 at `1/62`,
// and so on.

package app.skein.core.rag.rank

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import us.aherrera.skein.core.model.ChunkId
import us.aherrera.skein.core.model.CitationSourceKind
import us.aherrera.skein.core.model.ScoredChunk

class ScoreFusionTest {
    @Test
    fun `fusion scores by rank, not by the source's own score scale`() {
        // Wildly different raw scores, adjacent ranks: RRF keeps only the
        // ordering.
        val sources =
            mapOf(
                CitationSourceKind.LEXICAL to
                    listOf(ScoredChunk(1L, 1.0), ScoredChunk(2L, 0.02), ScoredChunk(3L, 0.001)),
            )

        val fused = ScoreFusion().fuseRecall(sources)

        assertThat(fused.getValue(1L)).isWithin(1e-15).of(1.0 / 61.0)
        assertThat(fused.getValue(2L)).isWithin(1e-15).of(1.0 / 62.0)
        assertThat(fused.getValue(3L)).isWithin(1e-15).of(1.0 / 63.0)
    }

    @Test
    fun `a chunk two sources agree on outscores a chunk only one source found`() {
        val sources =
            mapOf(
                CitationSourceKind.LEXICAL to listOf(ScoredChunk(1L, 1.0), ScoredChunk(2L, 0.9), ScoredChunk(3L, 0.8)),
                CitationSourceKind.GRAPH to listOf(ScoredChunk(9L, 1.0), ScoredChunk(8L, 0.5), ScoredChunk(3L, 0.33)),
            )

        val fused = ScoreFusion().fuseRecall(sources)

        assertThat(fused.getValue(3L)).isWithin(1e-15).of(2.0 / 63.0)
        assertThat(fused.getValue(3L)).isGreaterThan(fused.getValue(1L))
    }

    @Test
    fun `a chunk repeated inside one source counts once, at its best rank`() {
        val sources =
            mapOf(
                CitationSourceKind.LEXICAL to
                    listOf(ScoredChunk(1L, 1.0), ScoredChunk(1L, 0.9), ScoredChunk(2L, 0.8)),
            )

        val fused = ScoreFusion().fuseRecall(sources)

        assertThat(fused.keys).containsExactly(1L, 2L).inOrder()
        assertThat(fused.getValue(1L)).isWithin(1e-15).of(1.0 / 61.0)
        // The duplicate did not consume rank 2 — chunk 2 is still second.
        assertThat(fused.getValue(2L)).isWithin(1e-15).of(1.0 / 62.0)
    }

    @Test
    fun `an absent stage contributes nothing and leaves the ordering intact`() {
        // "No vectors yet" (`VectorRecall` before skein-079): the vector
        // list is missing entirely in one map and empty in the other.
        val lexical = listOf(ScoredChunk(1L, 1.0), ScoredChunk(2L, 0.5))
        val withoutVectors = mapOf(CitationSourceKind.LEXICAL to lexical)
        val withEmptyVectors =
            mapOf(
                CitationSourceKind.VECTOR to emptyList<ScoredChunk>(),
                CitationSourceKind.LEXICAL to lexical,
            )

        val fusion = ScoreFusion()
        val a = fusion.fuseRecall(withoutVectors)
        val b = fusion.fuseRecall(withEmptyVectors)

        assertThat(b).isEqualTo(a)
        assertThat(a.getValue(1L)).isGreaterThan(a.getValue(2L))
    }

    @Test
    fun `the surviving scores renormalize so the best candidate is exactly one`() {
        // The missing stage lowers every raw RRF sum by the same term, so
        // max-normalization inside `combine` restores the recall stages'
        // own `[0, 1]`, top-is-1.0 convention.
        val fused = ScoreFusion().fuseRecall(mapOf(CitationSourceKind.LEXICAL to listOf(ScoredChunk(1L, 0.01))))
        val docByChunk = mapOf(1L to "doc-1")

        val ranked = ScoreFusion().combine(fused, docByChunk, mapOf("doc-1" to 0.9), k = 8)

        assertThat(ranked.single().score).isWithin(1e-12).of(1.0)
    }

    @Test
    fun `the blend applies the plan's 0-6 recall and 0-4 ppr weights`() {
        // chunk 1: recall 1.0, ppr 0.25 → 0.6·1.0 + 0.4·0.25 = 0.70
        // chunk 2: recall 0.5, ppr 1.00 → 0.6·0.5 + 0.4·1.00 = 0.70
        val ranked =
            ScoreFusion().combine(
                fusedRecall = linkedMapOf(1L to 2.0, 2L to 1.0),
                docByChunk = mapOf(1L to "doc-1", 2L to "doc-2"),
                ppr = mapOf("doc-1" to 0.25, "doc-2" to 1.0),
                k = 8,
            )

        assertThat(ranked).hasSize(2)
        for (scored in ranked) assertThat(scored.score).isWithin(1e-12).of(0.7)
    }

    @Test
    fun `tied scores break on ascending chunk id`() {
        val ranked =
            ScoreFusion().combine(
                fusedRecall = linkedMapOf(7L to 1.0, 3L to 1.0, 5L to 1.0),
                docByChunk = mapOf(7L to "doc-a", 3L to "doc-a", 5L to "doc-a"),
                ppr = mapOf("doc-a" to 1.0),
                k = 8,
            )

        assertThat(ranked.map { it.chunkId }).containsExactly(3L, 5L, 7L).inOrder()
    }

    @Test
    fun `results are ordered by descending score`() {
        val ranked =
            ScoreFusion().combine(
                fusedRecall = linkedMapOf(5L to 1.0, 6L to 4.0, 7L to 2.0),
                docByChunk = mapOf(5L to "d", 6L to "d", 7L to "d"),
                ppr = mapOf("d" to 1.0),
                k = 8,
            )

        assertThat(ranked.map { it.chunkId }).containsExactly(6L, 7L, 5L).inOrder()
        assertThat(ranked.map { it.score }).isEqualTo(ranked.map { it.score }.sortedDescending())
    }

    @Test
    fun `k caps the ranked list`() {
        val ranked =
            ScoreFusion().combine(
                fusedRecall = linkedMapOf(1L to 4.0, 2L to 3.0, 3L to 2.0, 4L to 1.0),
                docByChunk = (1L..4L).associateWith { "doc-$it" },
                ppr = emptyMap(),
                k = 2,
            )

        assertThat(ranked.map { it.chunkId }).containsExactly(1L, 2L).inOrder()
    }

    @Test
    fun `a non-positive k and an empty candidate set rank to nothing`() {
        val fusion = ScoreFusion()
        val fused = linkedMapOf<ChunkId, Double>(1L to 1.0)

        assertThat(fusion.combine(fused, mapOf(1L to "d"), mapOf("d" to 1.0), k = 0)).isEmpty()
        assertThat(fusion.combine(emptyMap(), emptyMap(), emptyMap(), k = 8)).isEmpty()
        assertThat(fusion.fuseRecall(emptyMap())).isEmpty()
        assertThat(fusion.fuseRecall(mapOf(CitationSourceKind.VECTOR to emptyList()))).isEmpty()
    }

    @Test
    fun `a chunk whose document has no ppr entry keeps its recall component`() {
        val ranked =
            ScoreFusion().combine(
                fusedRecall = linkedMapOf(1L to 1.0),
                docByChunk = mapOf(1L to "doc-1"),
                ppr = emptyMap(),
                k = 8,
            )

        assertThat(ranked.single().chunkId).isEqualTo(1L)
        assertThat(ranked.single().score).isWithin(1e-12).of(RankerConfig.DEFAULT_RECALL_WEIGHT)
    }

    @Test
    fun `personalization sums each document's chunks and normalizes to one`() {
        val personalization =
            ScoreFusion().personalization(
                fusedRecall = linkedMapOf(1L to 0.5, 2L to 0.25, 3L to 0.25),
                docByChunk = mapOf(1L to "doc-a", 2L to "doc-a", 3L to "doc-b"),
            )

        assertThat(personalization.getValue("doc-a")).isWithin(1e-12).of(0.75)
        assertThat(personalization.getValue("doc-b")).isWithin(1e-12).of(0.25)
        assertThat(personalization.values.sum()).isWithin(1e-12).of(1.0)
    }

    @Test
    fun `personalization skips a chunk whose document is unknown`() {
        val personalization =
            ScoreFusion().personalization(
                fusedRecall = linkedMapOf(1L to 1.0, 2L to 3.0),
                docByChunk = mapOf(1L to "doc-a"),
            )

        assertThat(personalization.keys).containsExactly("doc-a")
        assertThat(personalization.getValue("doc-a")).isWithin(1e-12).of(1.0)
    }

    @Test
    fun `fusion is independent of the caller's map iteration order`() {
        val lexical = listOf(ScoredChunk(1L, 1.0), ScoredChunk(2L, 0.5))
        val vector = listOf(ScoredChunk(2L, 0.9), ScoredChunk(3L, 0.4))
        val graph = listOf(ScoredChunk(3L, 1.0), ScoredChunk(1L, 0.5))

        val a =
            ScoreFusion().fuseRecall(
                linkedMapOf(
                    CitationSourceKind.LEXICAL to lexical,
                    CitationSourceKind.VECTOR to vector,
                    CitationSourceKind.GRAPH to graph,
                ),
            )
        val b =
            ScoreFusion().fuseRecall(
                linkedMapOf(
                    CitationSourceKind.GRAPH to graph,
                    CitationSourceKind.LEXICAL to lexical,
                    CitationSourceKind.VECTOR to vector,
                ),
            )

        assertThat(b.keys.toList()).isEqualTo(a.keys.toList())
        for (chunkId in a.keys) assertThat(b.getValue(chunkId)).isEqualTo(a.getValue(chunkId))
    }
}
