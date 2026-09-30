package app.skein.testing.eval

import app.skein.core.model.Chunk
import app.skein.core.model.ContextualRetrievalResult
import app.skein.core.model.DocumentKind
import app.skein.core.model.FollowUpResolution
import app.skein.core.model.Locator
import app.skein.core.model.RecallSource
import app.skein.core.model.Retrieved
import app.skein.core.model.RevisionHashing
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Independently synthetic scorer adversaries; no held-out corpus or candidate execution. */
public class ExpandedRetrievalMetricsTest {
    private val body = "# Crates\n\nAmber crate holds nine cups.\n\nBlue crate holds six jars."
    private val frontmatter = JsonObject(emptyMap())
    private val document =
        ExpandedValidationDocument(
            "synthetic-doc",
            "space-a",
            RevisionHashing.compute(body, frontmatter),
            body,
            frontmatter,
            DocumentKind.NOTE,
            "Crates",
            body,
        )
    private val documents = mapOf(document.id to document)
    private val query =
        RetrievalGoldQuery(
            "synthetic-query",
            "answerable",
            "What does the amber crate hold?",
            "space-a",
            listOf(GoldEvidence(document.id, 3, "Amber crate holds nine cups.")),
            emptySet(),
        )
    private val first = member(1, "Amber crate holds nine cups.")
    private val second = member(2, "Blue crate holds six jars.")
    private val indexed = listOf(first, second).map(::indexed)

    @Test
    public fun enumeration_output_must_match_actual_stored_row_and_document_before_it_is_trusted() {
        val row = indexed.first()
        assertTrue(ExpandedRetrievalMetrics.validIndexedIdentity(row, document))
        val forged =
            listOf(
                first.copy(chunkId = 91),
                first.copy(docId = "different"),
                first.copy(text = "Invented metadata"),
                first.copy(revisionHash = "different"),
                first.copy(docTitle = "Different"),
                first.copy(sourceKind = DocumentKind.AIOUT),
                first.copy(locator = first.locator!!.copy(chunkOrd = 8)),
            )
        for (item in forged) {
            val bad = row.copy(assembled = item)
            assertFalse(ExpandedRetrievalMetrics.validIndexedIdentity(bad, document))
            val baseline = result(listOf(item), emptyList(), emptyMap())
            val measured = score(baseline, listOf(bad), representation = ValidationRepresentation.INDEXED_CHUNKS)
            assertEquals(0, measured.coveredSpans)
            assertTrue(measured.violations.any { "UNBOUND_STORED_IDENTITY" in it })
        }
        val forgedMember = first.copy(text = "Invented embedding breadcrumb\n\n${first.text}")
        val expanded = result(listOf(first), listOf(forgedMember), mapOf(1L to listOf(forgedMember)))
        assertEquals(0, score(expanded, listOf(row.copy(assembled = forgedMember))).coveredSpans)
    }

    @Test
    public fun stored_raw_offsets_are_independently_bound_to_canonical_unicode_locator() {
        val raw = "# Café\r\n\r\nCups."
        val canonical = RevisionHashing.canonicalBody(raw)
        val doc =
            document.copy(
                rawBody = raw,
                bodySnapshot = canonical,
                revisionHash = RevisionHashing.compute(raw, frontmatter),
            )
        val rawStart = raw.substringBefore("Cups.").toByteArray().size
        val canonicalStart = canonical.substringBefore("Cups.").toByteArray().size
        val item =
            first.copy(
                text = "Cups.",
                revisionHash = doc.revisionHash,
                locator =
                    Locator(
                        canonicalStart,
                        canonicalStart + 5,
                        0,
                    ),
            )
        val bound = indexed(item).copy(stored = indexed(item).stored.copy(byteStart = rawStart, byteEnd = rawStart + 5))
        assertTrue(ExpandedRetrievalMetrics.validIndexedIdentity(bound, doc))
        assertFalse(
            ExpandedRetrievalMetrics.validIndexedIdentity(
                bound.copy(
                    assembled =
                        item.copy(
                            locator =
                                Locator(
                                    rawStart,
                                    rawStart + 5,
                                    0,
                                ),
                        ),
                ),
                doc,
            ),
        )
        assertFalse(
            ExpandedRetrievalMetrics.validIndexedIdentity(bound.copy(stored = bound.stored.copy(byteStart = 7)), doc),
        )
    }

    @Test
    public fun legitimate_expansion_has_all_span_credit_without_legacy_chunk_identity() {
        val expanded = first.copy(text = body, locator = Locator(0, body.toByteArray().size))
        val score = score(result(listOf(expanded), listOf(first), mapOf(1L to listOf(first))))
        assertTrue(score.strictSuccess)
        assertEquals(1, score.coveredSpans)
        val legacy =
            RetrievalMetrics.score(
                query,
                "space-a",
                mapOf(document.id to EvaluationDocument(document.id, document.personaId, document.revisionHash, body)),
                listOf(first),
                listOf(expanded),
            )
        assertEquals(0, legacy.coveredSpans)
    }

    @Test
    public fun metadata_prefix_in_index_does_not_legitimize_prefix_in_returned_evidence() {
        val stored = first.copy(text = "# Crates\n\n${first.text}")
        val good = result(listOf(first), listOf(stored), mapOf(1L to listOf(stored)))
        assertTrue(score(good, listOf(indexed(stored))).strictSuccess)
        val forged = good.copy(evidence = listOf(stored))
        assertFalse(score(forged, listOf(indexed(stored))).strictSuccess)
        assertTrue(score(forged, listOf(indexed(stored))).violations.any { "SOURCE_BYTES" in it })
    }

    @Test
    public fun query_scores_compare_to_original_candidates_and_not_enumeration_scores() {
        val enumeration =
            indexed.map {
                it.copy(
                    assembled = it.assembled.copy(score = 800.0, recallScores = emptyMap()),
                )
            }
        assertTrue(score(result(), enumeration).strictSuccess)
        val alteredMember = first.copy(score = first.score + 1)
        assertFalse(score(result(members = mapOf(1L to listOf(alteredMember)))).strictSuccess)
        assertFalse(
            score(
                result(evidence = listOf(first.copy(recallScores = mapOf(RecallSource.LEXICAL to 70.0)))),
            ).strictSuccess,
        )
    }

    @Test
    public fun scope_staleness_unknown_rows_and_forged_bytes_cannot_earn_coverage() {
        val variants =
            listOf(
                first.copy(text = "Invented answer."),
                first.copy(revisionHash = "stale"),
                first.copy(chunkId = 99),
                first.copy(docId = "missing"),
                first.copy(sourceKind = DocumentKind.AIOUT),
            )
        for (bad in variants) {
            val measured = score(result(evidence = listOf(bad), members = mapOf(bad.chunkId to listOf(first))))
            assertEquals(0, measured.coveredSpans)
            assertFalse(measured.strictSuccess)
        }
        val out =
            ExpandedRetrievalMetrics.score(
                query,
                "space-b",
                documents,
                indexed,
                result(),
                ValidationRepresentation.EXPANDED_UNITS,
            )
        assertEquals(0, out.coveredSpans)
        val forbidden = query.copy(forbiddenDocIds = setOf(document.id))
        assertEquals(0, score(result(), gold = forbidden).coveredSpans)
    }

    @Test
    public fun missing_extra_and_reused_members_fail_closed() {
        assertFalse(score(result(members = emptyMap())).strictSuccess)
        assertFalse(score(result(members = mapOf(1L to listOf(first), 55L to listOf(second)))).strictSuccess)
        assertFalse(score(result(members = mapOf(1L to listOf(first, first)))).strictSuccess)
        val reused =
            result(listOf(first, second), listOf(first, second), mapOf(1L to listOf(first), 2L to listOf(first)))
        assertFalse(score(reused).strictSuccess)
        assertTrue(score(reused).violations.any { "REUSED_MEMBER" in it })
    }

    @Test
    public fun representative_is_first_original_member_and_must_cover_full_member_anchor() {
        val whole = first.copy(text = body, locator = Locator(0, body.toByteArray().size))
        val members = mapOf(1L to listOf(first, second))
        assertTrue(score(result(listOf(whole), listOf(first, second), members)).strictSuccess)
        assertFalse(score(result(listOf(whole), listOf(second, first), members)).strictSuccess)
        assertFalse(score(result(listOf(first), listOf(first, second), members)).strictSuccess)
        val unknown = first.copy(chunkId = 88)
        assertFalse(score(result(originals = listOf(unknown), members = mapOf(1L to listOf(unknown)))).strictSuccess)
    }

    @Test
    public fun overlapping_units_are_both_invalid_and_duplicate_rank_does_not_backfill() {
        val whole = second.copy(text = body, locator = Locator(0, body.toByteArray().size))
        val overlap =
            result(
                listOf(first, whole),
                listOf(first, second),
                mapOf(
                    1L to listOf(first),
                    2L to listOf(second),
                ),
            )
        assertEquals(0, score(overlap).coveredSpans)
        val raw = List(8) { second } + first
        val baseline = result(raw, emptyList(), emptyMap())
        val measured = score(baseline, representation = ValidationRepresentation.INDEXED_CHUNKS)
        assertEquals(0, measured.coveredSpans)
        assertTrue(measured.violations.any { "OVER_LIMIT" in it })
    }

    @Test
    public fun every_competing_fact_is_required_even_when_one_is_rank_one() {
        val conflict =
            query.copy(
                category = "contradiction_unresolved",
                relevant =
                    query.relevant + GoldEvidence(document.id, 3, second.text),
            )
        assertFalse(score(result(), gold = conflict).allSpansCovered)
        val both =
            result(listOf(first, second), listOf(first, second), mapOf(1L to listOf(first), 2L to listOf(second)))
        assertTrue(score(both, gold = conflict).allSpansCovered)
        assertEquals(2, score(both, gold = conflict).coveredSpans)
    }

    @Test
    public fun adjacent_units_can_jointly_cover_one_span_without_duplicate_credit() {
        val split = "Amber crate ".toByteArray().size
        val start = first.locator!!.byteStart
        val left = first.copy(text = "Amber crate ", locator = Locator(start, start + split, 0))
        val right =
            first.copy(
                chunkId = 3,
                text = "holds nine cups.",
                locator = Locator(start + split, first.locator!!.byteEnd, 2),
            )
        val both = result(listOf(left, right), listOf(left, right), mapOf(1L to listOf(left), 3L to listOf(right)))
        assertEquals(1, score(both, listOf(indexed(left), indexed(right))).coveredSpans)
    }

    @Test
    public fun split_utf8_and_nonfinite_signals_are_refused() {
        val text = "Café cups."
        val doc =
            document.copy(
                bodySnapshot = text,
                rawBody = text,
                revisionHash = RevisionHashing.compute(text, frontmatter),
            )
        val bad = first.copy(text = "�", revisionHash = doc.revisionHash, locator = Locator(4, 5))
        val absent = query.copy(relevant = emptyList())
        val measured =
            ExpandedRetrievalMetrics.score(
                absent,
                "space-a",
                mapOf(doc.id to doc),
                listOf(indexed(bad)),
                result(listOf(bad), listOf(bad), mapOf(1L to listOf(bad))),
                ValidationRepresentation.EXPANDED_UNITS,
            )
        assertTrue(measured.violations.any { "INVALID_ANCHOR" in it })
        assertFalse(score(result(evidence = listOf(first.copy(score = Double.NaN)))).strictSuccess)
    }

    @Test
    public fun repetition_aggregation_never_calls_one_empty_sample_a_correct_rejection() {
        val absent = query.copy(relevant = emptyList())
        val empty = score(result(emptyList(), emptyList(), emptyMap()), gold = absent)
        val admission = score(result(), gold = absent)
        val combined = ExpandedRetrievalMetrics.acrossSamples(listOf(empty, admission, empty))
        assertFalse(combined.correctAbsenceRejection)
        assertTrue(combined.falseAbsenceAdmission)
        assertTrue(ExpandedRetrievalMetrics.acrossSamples(listOf(empty, empty, empty)).correctAbsenceRejection)
    }

    private fun member(
        id: Long,
        text: String,
    ): Retrieved {
        val start = body.substring(0, body.indexOf(text)).toByteArray().size
        return Retrieved(
            id,
            document.id,
            document.title,
            text,
            1.0 / id,
            DocumentKind.NOTE,
            setOf(RecallSource.LEXICAL),
            document.revisionHash,
            Locator(start, start + text.toByteArray().size, (id - 1).toInt()),
            mapOf(
                RecallSource.LEXICAL to 0.01 / id,
            ),
        )
    }

    private fun indexed(item: Retrieved): ExpandedIndexedChunk =
        ExpandedIndexedChunk(
            Chunk(
                item.chunkId,
                item.docId,
                item.locator!!.chunkOrd ?: 0,
                item.text,
                10,
                "pending",
                0,
                item.revisionHash,
                item.locator!!.byteStart,
                item.locator!!.byteEnd,
            ),
            item,
        )

    private fun result(
        evidence: List<Retrieved> = listOf(first),
        originals: List<Retrieved> = listOf(first),
        members: Map<Long, List<Retrieved>> = mapOf(1L to listOf(first)),
    ): ContextualRetrievalResult =
        ContextualRetrievalResult(
            query.query,
            query.query,
            FollowUpResolution.DIRECT,
            emptySet(),
            evidence,
            originals,
            members,
        )

    private fun score(
        result: ContextualRetrievalResult,
        index: List<ExpandedIndexedChunk> = indexed,
        gold: RetrievalGoldQuery = query,
        representation: ValidationRepresentation = ValidationRepresentation.EXPANDED_UNITS,
    ): ExpandedQueryScore = ExpandedRetrievalMetrics.score(gold, "space-a", documents, index, result, representation)
}
