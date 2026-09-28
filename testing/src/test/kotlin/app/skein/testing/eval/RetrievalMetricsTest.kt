package app.skein.testing.eval

import app.skein.core.model.DocumentKind
import app.skein.core.model.Locator
import app.skein.core.model.RecallSource
import app.skein.core.model.Retrieved
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

public class RetrievalMetricsTest {
    private val body = "雪🙂 prefix. Answer café. suffix."
    private val document = EvaluationDocument("doc", "default", "revision", body)
    private val docs = mapOf("doc" to document)
    private val query =
        RetrievalGoldQuery("one", "lexical", "where", null, listOf(GoldEvidence("doc", 3, "Answer café.")), emptySet())

    @Test
    public fun utf8_anchor_uses_revision_bytes_and_rejects_utf16_offsets() {
        val full = chunk("full", body)
        val answer = chunk("answer", "Answer café.")
        val scored = RetrievalMetrics.score(query, "default", docs, listOf(answer), listOf(answer))
        assertEquals(1.0, scored.recall!!, 0.0)
        assertEquals(1.0, scored.ndcg!!, 0.0)
        val utf16 =
            answer.copy(
                locator = Locator(body.indexOf("Answer"), body.indexOf("Answer") + "Answer café.".length, 0),
            )
        assertFalse(RetrievalMetrics.validAnchor(utf16, docs))
        assertFalse(RetrievalMetrics.validAnchor(answer.copy(text = "forged"), docs))
    }

    @Test
    public fun overlap_and_duplicate_ids_never_double_credit_evidence() {
        val full = chunk("full", body)
        val answer = chunk("answer", "Answer café.")
        val scored = RetrievalMetrics.score(query, "default", docs, listOf(full, answer), listOf(full, full, answer))
        assertEquals(1, scored.coveredSpans)
        assertEquals(1, scored.duplicateCount)
        assertEquals(listOf(3, 0, 2), scored.grades)
        assertTrue(scored.ndcg!! < 1.0)
    }

    @Test
    public fun duplicate_rank_one_never_promotes_rank_nine_and_raw_mrr_is_preserved() {
        val miss = chunk("miss", "prefix.")
        val answer = chunk("answer", "Answer café.")
        val ninth = RetrievalMetrics.score(query, "default", docs, listOf(miss, answer), List(8) { miss } + answer)
        assertEquals(0.0, ninth.recall!!, 0.0)
        assertEquals(0.0, ninth.reciprocalRank!!, 0.0)
        assertEquals(8, ninth.rankedChunkIds.size)
        assertEquals(listOf(2, 0, 0, 0, 0, 0, 0, 0), ninth.grades)
        val third = RetrievalMetrics.score(query, "default", docs, listOf(miss, answer), listOf(miss, miss, answer))
        assertEquals(listOf(2, 0, 3), third.grades)
        assertEquals(1.0 / 3.0, third.reciprocalRank!!, 0.0)
    }

    @Test
    public fun generated_kinds_are_provenance_failures_even_with_a_valid_note_shaped_anchor() {
        for (kind in listOf(DocumentKind.CHAT, DocumentKind.AIOUT)) {
            val hit = chunk("hit", body).copy(sourceKind = kind)
            val generated = mapOf("doc" to document.copy(kind = kind))
            val score = RetrievalMetrics.score(query, "default", generated, listOf(hit), listOf(hit))
            assertEquals(listOf(hit.chunkId), score.provenanceViolationChunkIds)
            assertEquals(0.0, score.recall!!, 0.0)
            assertNull(score.ndcg)
        }
    }

    @Test
    public fun breadcrumb_is_allowed_only_when_it_matches_the_indexed_row() {
        val raw = chunk("hit", body)
        val indexed = raw.copy(text = "# Recorded heading\n\n$body")
        assertTrue(RetrievalMetrics.validAnchor(indexed, docs))
        val good = RetrievalMetrics.score(query, "default", docs, listOf(indexed), listOf(indexed))
        assertEquals(1.0, good.recall!!, 0.0)
        val forged = indexed.copy(text = "forged heading\n\n$body")
        val bad = RetrievalMetrics.score(query, "default", docs, listOf(indexed), listOf(forged))
        assertEquals(0.0, bad.recall!!, 0.0)
        assertEquals(listOf(raw.chunkId), bad.invalidAnchorChunkIds)
        val splitCodePoint = raw.copy(locator = Locator(1, body.toByteArray(Charsets.UTF_8).size, 0))
        assertFalse(RetrievalMetrics.validAnchor(splitCodePoint, docs))
    }

    @Test
    public fun adjacent_chunks_complete_span_only_at_second_rank() {
        val first = chunk("first", "Answer ")
        val second = chunk("second", "café.")
        val score = RetrievalMetrics.score(query, "default", docs, listOf(first, second), listOf(first, second))
        assertEquals(listOf(2, 3), score.grades)
        assertEquals(1.0, score.recall!!, 0.0)
        assertEquals(0.5, score.reciprocalRank!!, 0.0)
        assertEquals(1.0, score.ndcg!!, 0.0)
        val gap = chunk("gap", "fé.")
        val missed = RetrievalMetrics.score(query, "default", docs, listOf(first, second), listOf(first, gap))
        assertEquals(0.0, missed.recall!!, 0.0)
    }

    @Test
    public fun stale_revision_cannot_cover_or_gain_and_is_reported() {
        val fresh = chunk("fresh", body)
        val stale = fresh.copy(chunkId = 999L, revisionHash = "old-revision")
        val score = RetrievalMetrics.score(query, "default", docs, listOf(fresh), listOf(stale))
        assertEquals(0.0, score.recall!!, 0.0)
        assertEquals(0.0, score.ndcg!!, 0.0)
        assertEquals(listOf(999L), score.invalidAnchorChunkIds)
    }

    @Test
    public fun ideal_uses_indexed_corpus_even_when_ranker_misses_answer() {
        val full = chunk("full", body)
        val other = chunk("other", "suffix.")
        val score = RetrievalMetrics.score(query, "default", docs, listOf(full, other), listOf(other))
        assertEquals(listOf(2), score.grades)
        assertTrue(score.ndcg!! < 0.5)
        assertEquals(0.0, score.reciprocalRank!!, 0.0)
    }

    @Test
    public fun scope_and_explicit_forbidden_rows_are_hard_failure_inputs() {
        val hit = chunk("hit", body)
        val scoped = RetrievalMetrics.score(query, "work", docs, listOf(hit), listOf(hit))
        assertEquals(listOf(hit.chunkId), scoped.scopeViolationChunkIds)
        val forbidden =
            RetrievalMetrics.score(
                query.copy(forbiddenDocIds = setOf("doc")),
                "default",
                docs,
                listOf(hit),
                listOf(hit),
            )
        assertEquals(listOf(hit.chunkId), forbidden.scopeViolationChunkIds)
    }

    @Test
    public fun absent_queries_have_undefined_ranking_metrics_and_real_rejection_only() {
        val absent = query.copy(category = "weak_only", relevant = emptyList())
        val hit = chunk("hit", body)
        val returned = RetrievalMetrics.score(absent, "default", docs, listOf(hit), listOf(hit))
        assertNull(returned.recall)
        assertNull(returned.ndcg)
        assertNull(returned.reciprocalRank)
        assertFalse(returned.rejected)
        assertTrue(RetrievalMetrics.score(absent, "default", docs, listOf(hit), emptyList()).rejected)
    }

    @Test
    public fun corpus_without_coverable_evidence_cannot_claim_perfect_normalized_score() {
        val score = RetrievalMetrics.score(query, "default", docs, emptyList(), emptyList())
        assertEquals(0.0, score.idealDcg!!, 0.0)
        assertNull(score.ndcg)
        assertEquals(0.0, score.recall!!, 0.0)
    }

    @Test(expected = IllegalStateException::class)
    public fun missing_stored_evidence_is_fixture_failure_not_a_retrieval_miss() {
        RetrievalMetrics.score(
            query,
            "default",
            mapOf("doc" to document.copy(bodySnapshot = "changed")),
            emptyList(),
            emptyList(),
        )
    }

    @Test
    public fun gold_counts_and_nearest_rank_percentiles_preserve_denominators() {
        val queries = RetrievalMetrics.goldQueries()
        assertEquals(76, queries.size)
        assertEquals(60, queries.count { it.answerable })
        assertEquals(8, queries.count { it.category == "weak_only" })
        assertEquals(8, queries.count { it.category == "no_match" })
        assertEquals(50.0, RetrievalMetrics.percentile((1..100).map { it.toDouble() }, 0.5)!!, 0.0)
        assertEquals(95.0, RetrievalMetrics.percentile((1..100).map { it.toDouble() }, 0.95)!!, 0.0)
        assertNull(RetrievalMetrics.percentile(emptyList(), 0.95))
    }

    @Test
    public fun determinism_includes_order_scores_and_provenance_not_just_ids() {
        val hit = chunk("hit", body)
        val score = RetrievalMetrics.score(query, "default", docs, listOf(hit), listOf(hit))

        fun evaluation(second: Retrieved): EvaluatedQuery =
            EvaluatedQuery(
                query,
                score,
                listOf(RetrievalSample(1.0, listOf(hit)), RetrievalSample(2.0, listOf(second))),
            )
        assertTrue(evaluation(hit).deterministic)
        assertFalse(evaluation(hit.copy(score = 0.5)).deterministic)
        assertFalse(evaluation(hit.copy(recalledBy = setOf(RecallSource.GRAPH))).deterministic)
    }

    private fun chunk(
        id: String,
        text: String,
    ): Retrieved {
        val start = body.indexOf(text)
        require(start >= 0)
        val byteStart = body.substring(0, start).toByteArray(Charsets.UTF_8).size
        return Retrieved(
            id.hashCode().toLong(),
            "doc",
            "title",
            text,
            1.0,
            DocumentKind.NOTE,
            setOf(RecallSource.LEXICAL),
            "revision",
            Locator(
                byteStart,
                byteStart + text.toByteArray(Charsets.UTF_8).size,
                0,
            ),
        )
    }
}
