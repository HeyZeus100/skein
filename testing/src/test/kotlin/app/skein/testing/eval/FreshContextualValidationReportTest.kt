package app.skein.testing.eval

import app.skein.core.model.ContextualRetrievalResult
import app.skein.core.model.DocumentKind
import app.skein.core.model.FollowUpResolution
import app.skein.core.model.RevisionHashing
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fabricated report-state tests only. No retriever is called and no candidate output is read. */
public class FreshContextualValidationReportTest {
    private val fixture = FreshContextualValidationFixture.load()
    private val documents =
        fixture.documents.associate { source ->
            val frontmatter = JsonObject(emptyMap())
            source.id to
                ExpandedValidationDocument(
                    source.id,
                    source.spaceAlias,
                    RevisionHashing.compute(source.body, frontmatter),
                    source.body,
                    frontmatter,
                    DocumentKind.NOTE,
                    source.title,
                    source.body,
                )
        }

    @Test
    public fun all_forty_rows_and_all_denominators_survive_errors_timeouts_and_unexecuted_context() {
        val rows = rows().toMutableList()
        rows[0] = rows[0].copy(primary = failed(FreshExecutionStatus.ERROR))
        rows[1] = rows[1].copy(primary = failed(FreshExecutionStatus.TIMEOUT))
        val report = report(rows)
        val summary = report.getValue("summary").jsonObject
        assertEquals(40, summary.int("queries"))
        assertEquals(22, summary.int("answerable_queries"))
        assertEquals(18, summary.int("absence_queries"))
        assertEquals(24, summary.int("labelled_evidence_spans"))
        assertEquals(34, summary.int("attempted"))
        assertEquals(32, summary.int("scored"))
        assertEquals(1, summary.int("execution_errors"))
        assertEquals(1, summary.int("timeouts"))
        assertEquals(6, summary.int("unexecuted"))
        assertEquals(40, report.getValue("queries").jsonArray.size)
    }

    @Test
    public fun perfect_component_diagnostics_never_credit_primary_context_cases() {
        val rows =
            rows().map { row ->
                if (row.query.requiresApplicationContext) {
                    row.copy(
                        componentDiagnostic = success(row.query, perfect = true),
                    )
                } else {
                    row
                }
            }
        val report = report(rows)
        val contexts =
            report
                .getValue(
                    "queries",
                ).jsonArray
                .map { it.jsonObject }
                .filter { "component_diagnostic" in it }
        assertEquals(6, contexts.size)
        for (row in contexts) {
            assertEquals("UNEXECUTED", row.getValue("status").jsonPrimitive.content)
            assertEquals(JsonNull, row.getValue("score"))
            assertFalse(row.getValue("attempted").jsonPrimitive.boolean)
            assertTrue(row.getValue("samples").jsonArray.isEmpty())
        }
        assertEquals(0, report.getValue("summary").jsonObject.int("covered_evidence_spans"))
        assertEquals(34, report.getValue("summary").jsonObject.int("scored"))
    }

    @Test
    public fun an_empty_error_has_no_absence_credit_and_cannot_carry_a_score() {
        val rows = rows().toMutableList()
        val position = rows.indexOfFirst { !it.query.gold.answerable && !it.query.requiresApplicationContext }
        val before = report(rows).getValue("summary").jsonObject.int("correct_absence_rejections")
        rows[position] = rows[position].copy(primary = failed(FreshExecutionStatus.ERROR))
        assertEquals(before - 1, report(rows).getValue("summary").jsonObject.int("correct_absence_rejections"))
        assertThrows(IllegalArgumentException::class.java) {
            FreshValidationExecution(
                FreshExecutionStatus.ERROR,
                score = ExpandedQueryScore(0, 0, 0, emptyList(), emptyList()),
            )
        }
    }

    @Test
    public fun omitted_duplicate_context_credited_and_incomplete_sample_rows_are_rejected() {
        val rows = rows()
        assertThrows(IllegalArgumentException::class.java) { report(rows.dropLast(1)) }
        assertThrows(IllegalArgumentException::class.java) { report(rows.dropLast(1) + rows.first()) }
        val contextIndex = rows.indexOfFirst { it.query.requiresApplicationContext }
        val credited = rows.toMutableList()
        credited[contextIndex] = credited[contextIndex].copy(primary = success(credited[contextIndex].query))
        assertThrows(IllegalArgumentException::class.java) { report(credited) }
        val short = rows.toMutableList()
        short[0] = short[0].copy(primary = short[0].primary.copy(samples = short[0].primary.samples.take(2)))
        assertThrows(IllegalArgumentException::class.java) { report(short) }
    }

    @Test
    public fun nondeterminism_hard_fails_and_never_earns_strict_or_all_span_success() {
        val rows = rows().toMutableList()
        val execution = success(rows[0].query, perfect = true)
        val changed =
            execution.samples.last().copy(
                result =
                    execution.samples
                        .last()
                        .result!!
                        .copy(recallQuery = "Different synthetic recall"),
            )
        rows[0] = rows[0].copy(primary = execution.copy(samples = execution.samples.dropLast(1) + changed))
        val summary = report(rows).getValue("summary").jsonObject
        assertEquals("FAIL", summary.getValue("hard_provenance_gate").jsonPrimitive.content)
        assertEquals(1, summary.int("nondeterministic_queries"))
        assertEquals(0, summary.int("all_span_successes"))
    }

    @Test
    public fun complete_raw_samples_and_null_error_result_are_both_retained() {
        val rows = rows().toMutableList()
        rows[0] = rows[0].copy(primary = failed(FreshExecutionStatus.TIMEOUT))
        val values = report(rows).getValue("queries").jsonArray.map { it.jsonObject }
        val failed = values[0].getValue("warmup").jsonObject
        assertFalse(failed.getValue("result_available").jsonPrimitive.boolean)
        assertEquals("TIMEOUT", failed.getValue("status").jsonPrimitive.content)
        val sample =
            values[1]
                .getValue("samples")
                .jsonArray
                .first()
                .jsonObject
        assertTrue(
            sample.keys.containsAll(
                listOf("evidence", "original_candidates", "evidence_members", "original_query", "recall_query"),
            ),
        )
        assertEquals(
            "INELIGIBLE",
            report(rows)
                .getValue("summary")
                .jsonObject
                .getValue("expanded_ranking_gate")
                .jsonPrimitive.content,
        )
    }

    private fun rows(): List<FreshValidationRow> =
        fixture.queries.map { query ->
            if (query.requiresApplicationContext) {
                FreshValidationRow(query, FreshValidationExecution(FreshExecutionStatus.UNEXECUTED))
            } else {
                FreshValidationRow(query, success(query))
            }
        }

    private fun success(
        query: FreshValidationQuery,
        perfect: Boolean = false,
    ): FreshValidationExecution {
        val result =
            ContextualRetrievalResult(
                query.gold.query,
                query.gold.query,
                FollowUpResolution.DIRECT,
                emptySet(),
                emptyList(),
                emptyList(),
            )
        val sample = FreshValidationSample(FreshExecutionStatus.EXECUTED, 1.0, result)
        val spans = query.gold.relevant.size
        return FreshValidationExecution(
            FreshExecutionStatus.EXECUTED,
            sample,
            List(3) { sample },
            ExpandedQueryScore(if (perfect) spans else 0, spans, 0, emptyList(), emptyList()),
        )
    }

    private fun failed(status: FreshExecutionStatus): FreshValidationExecution =
        FreshValidationExecution(status, FreshValidationSample(status, 1.0))

    private fun report(rows: List<FreshValidationRow>): JsonObject =
        FreshContextualValidationReport.mode(fixture, documents, rows, ValidationRepresentation.EXPANDED_UNITS, 3)

    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int
}
