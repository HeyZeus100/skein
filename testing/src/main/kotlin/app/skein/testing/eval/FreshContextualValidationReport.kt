package app.skein.testing.eval

import app.skein.core.model.ContextualRetrievalResult
import app.skein.core.model.Retrieved
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

public enum class FreshExecutionStatus { EXECUTED, ERROR, TIMEOUT, UNEXECUTED }

public data class FreshValidationSample(
    val status: FreshExecutionStatus,
    val elapsedMillis: Double,
    val result: ContextualRetrievalResult? = null,
    val stageWarnings: List<String> = emptyList(),
    val failureType: String? = null,
)

public data class FreshValidationExecution(
    val status: FreshExecutionStatus,
    val warmup: FreshValidationSample? = null,
    val samples: List<FreshValidationSample> = emptyList(),
    val score: ExpandedQueryScore? = null,
) {
    init {
        require(status == FreshExecutionStatus.EXECUTED || score == null)
    }

    public val attempted: Boolean get() = warmup != null || samples.isNotEmpty()
    public val deterministic: Boolean
        get() =
            status == FreshExecutionStatus.EXECUTED &&
                samples.isNotEmpty() &&
                samples.map { it.result }.distinct().size == 1
    public val strictSuccess: Boolean get() = deterministic && score?.strictSuccess == true
}

public data class FreshValidationRow(
    val query: FreshValidationQuery,
    val primary: FreshValidationExecution,
    val componentDiagnostic: FreshValidationExecution? = null,
)

/** Synthetic-only report. Fixed denominators include every error and unexecuted context case. */
public object FreshContextualValidationReport {
    public fun mode(
        fixture: FreshValidationFixture,
        documents: Map<String, ExpandedValidationDocument>,
        rows: List<FreshValidationRow>,
        representation: ValidationRepresentation,
        repetitions: Int,
    ): JsonObject {
        require(rows.map { it.query.gold.id } == fixture.queries.map { it.gold.id })
        require(rows.map { it.query } == fixture.queries)
        require(
            rows.size == 40 &&
                rows.count { it.query.gold.answerable } == 22 &&
                rows.sumOf { it.query.gold.relevant.size } == 24,
        )
        require(repetitions in 2..10)
        for (row in rows) {
            if (row.query.requiresApplicationContext) {
                require(row.primary == FreshValidationExecution(FreshExecutionStatus.UNEXECUTED))
            } else {
                require(row.componentDiagnostic == null)
            }
            for (execution in listOfNotNull(row.primary, row.componentDiagnostic)) {
                if (execution.status == FreshExecutionStatus.EXECUTED) {
                    require(execution.warmup?.status == FreshExecutionStatus.EXECUTED)
                    require(
                        execution.samples.size == repetitions &&
                            execution.samples.all { it.status == FreshExecutionStatus.EXECUTED && it.result != null },
                    )
                    require(execution.score != null)
                }
            }
        }
        return buildJsonObject {
            put("representation", representation.name)
            put("summary", summary(rows))
            put(
                "queries",
                JsonArray(
                    rows.map { row ->
                        buildJsonObject {
                            val gold = row.query.gold
                            put("id", gold.id)
                            put("category", gold.category)
                            put("answerable", gold.answerable)
                            put("required_spans", gold.relevant.size)
                            put("raw_query", gold.query)
                            put("space_alias", gold.personaAlias)
                            put("execution_requirements", strings(row.query.requirements.sorted()))
                            put(
                                "required_span_anchors",
                                JsonArray(
                                    ExpandedRetrievalMetrics.requiredSpans(gold, documents).map { span ->
                                        buildJsonObject {
                                            put("doc_id", span.documentId)
                                            put("revision_hash", span.revisionHash)
                                            put("byte_start", span.start)
                                            put("byte_end", span.end)
                                        }
                                    },
                                ),
                            )
                            put(
                                "application_context_status",
                                if (row.query.requiresApplicationContext) "UNEXECUTED" else "NOT_APPLICABLE",
                            )
                            for ((key, value) in execution(row.primary)) put(key, value)
                            row.componentDiagnostic?.let { put("component_diagnostic", execution(it)) }
                        }
                    },
                ),
            )
        }
    }

    private fun summary(rows: List<FreshValidationRow>): JsonObject =
        buildJsonObject {
            val scores = rows.mapNotNull { it.primary.score }
            val absent = rows.filterNot { it.query.gold.answerable }
            val answers = rows.filter { it.query.gold.answerable }
            put("queries", rows.size)
            put("answerable_queries", answers.size)
            put("absence_queries", absent.size)
            put("labelled_evidence_spans", rows.sumOf { it.query.gold.relevant.size })
            put("attempted", rows.count { it.primary.attempted })
            put("completed", rows.count { it.primary.status == FreshExecutionStatus.EXECUTED })
            put("scored", scores.size)
            put("covered_evidence_spans", scores.sumOf { it.coveredSpans })
            put("micro_span_coverage", scores.sumOf { it.coveredSpans }.toDouble() / 24)
            put(
                "macro_span_coverage",
                answers.sumOf { (it.primary.score?.coveredSpans ?: 0).toDouble() / it.query.gold.relevant.size } / 22,
            )
            put("any_span_successes", scores.count { it.coveredSpans > 0 })
            put("all_span_successes", answers.count { it.primary.strictSuccess })
            put(
                "complete_conflict_queries",
                rows.count {
                    it.query.gold.category == "contradiction_unresolved" &&
                        it.primary.strictSuccess
                },
            )
            put("conflict_queries", rows.count { it.query.gold.category == "contradiction_unresolved" })
            put("correct_absence_rejections", absent.count { it.primary.score?.correctAbsenceRejection == true })
            put("false_absence_admissions", absent.count { it.primary.score?.falseAbsenceAdmission == true })
            put(
                "absence_rejection_rate",
                absent.count { it.primary.score?.correctAbsenceRejection == true }.toDouble() / 18,
            )
            put("empty_answer_rejections", answers.count { it.primary.score?.rawEvidenceCount == 0 })
            put(
                "related_but_insufficient_admissions",
                answers.count {
                    it.primary.score?.let { s ->
                        s.rawEvidenceCount > 0 &&
                            !s.allSpansCovered
                    } ==
                        true
                },
            )
            put("strict_successes", rows.count { it.primary.strictSuccess })
            put("strict_success_rate", rows.count { it.primary.strictSuccess }.toDouble() / 40)
            put("provenance_violations", scores.sumOf { it.violations.size })
            put("execution_errors", rows.count { it.primary.status == FreshExecutionStatus.ERROR })
            put("timeouts", rows.count { it.primary.status == FreshExecutionStatus.TIMEOUT })
            put("unexecuted", rows.count { it.primary.status == FreshExecutionStatus.UNEXECUTED })
            put(
                "nondeterministic_queries",
                rows.count {
                    it.primary.status == FreshExecutionStatus.EXECUTED &&
                        !it.primary.deterministic
                },
            )
            put(
                "hard_provenance_gate",
                if (scores.any { it.violations.isNotEmpty() } ||
                    rows.any { it.primary.status == FreshExecutionStatus.EXECUTED && !it.primary.deterministic }
                ) {
                    "FAIL"
                } else if (scores.size ==
                    rows.size
                ) {
                    "PASS"
                } else {
                    "INCOMPLETE"
                },
            )
            put("expanded_ranking_gate", "INELIGIBLE")
        }

    private fun execution(value: FreshValidationExecution): JsonObject =
        buildJsonObject {
            put("status", value.status.name)
            put("attempted", value.attempted)
            put("deterministic", value.deterministic)
            put("strict_success", value.strictSuccess)
            put("warmup", value.warmup?.let(::sample) ?: JsonNull)
            put("samples", JsonArray(value.samples.map(::sample)))
            put(
                "score",
                value.score?.let { score ->
                    buildJsonObject {
                        put("covered_spans", score.coveredSpans)
                        put("total_spans", score.totalSpans)
                        put("all_spans_covered", score.allSpansCovered)
                        put("raw_evidence_count", score.rawEvidenceCount)
                        put("valid_ranks", JsonArray(score.validRanks.map(::JsonPrimitive)))
                        put("correct_absence_rejection", score.correctAbsenceRejection)
                        put("false_absence_admission", score.falseAbsenceAdmission)
                        put("provenance_violations", strings(score.violations))
                    }
                } ?: JsonNull,
            )
        }

    private fun sample(value: FreshValidationSample): JsonObject =
        buildJsonObject {
            put("status", value.status.name)
            put("elapsed_ms", number(value.elapsedMillis))
            put("stage_warnings", strings(value.stageWarnings))
            put("failure_type", value.failureType?.let(::JsonPrimitive) ?: JsonNull)
            val result = value.result
            put("result_available", result != null)
            put("original_query", result?.originalQuery?.let(::JsonPrimitive) ?: JsonNull)
            put("recall_query", result?.recallQuery?.let(::JsonPrimitive) ?: JsonNull)
            put("resolution", result?.resolution?.name?.let(::JsonPrimitive) ?: JsonNull)
            put("anchor_document_ids", strings(result?.anchorDocumentIds.orEmpty().sorted()))
            put("evidence", JsonArray(result?.evidence.orEmpty().map(::retrieved)))
            put("original_candidates", JsonArray(result?.originalCandidates.orEmpty().map(::retrieved)))
            put(
                "evidence_members",
                buildJsonObject {
                    for ((id, members) in result?.evidenceMembers.orEmpty().toSortedMap()) {
                        put(
                            id.toString(),
                            JsonArray(members.map(::retrieved)),
                        )
                    }
                },
            )
            put(
                "exclusion_counts",
                buildJsonObject {
                    for ((reason, count) in result?.exclusionCounts.orEmpty().toSortedMap()) put(reason, count)
                },
            )
        }

    public fun retrieved(item: Retrieved): JsonObject =
        buildJsonObject {
            put("chunk_id", item.chunkId)
            put("doc_id", item.docId)
            put("doc_title", item.docTitle)
            put("text", item.text)
            put("source_kind", item.sourceKind.name)
            put("revision_hash", item.revisionHash?.let(::JsonPrimitive) ?: JsonNull)
            put("byte_start", item.locator?.byteStart?.let(::JsonPrimitive) ?: JsonNull)
            put("byte_end", item.locator?.byteEnd?.let(::JsonPrimitive) ?: JsonNull)
            put("chunk_ord", item.locator?.chunkOrd?.let(::JsonPrimitive) ?: JsonNull)
            put("score", number(item.score))
            put("recalled_by", strings(item.recalledBy.map { it.name }.sorted()))
            put(
                "recall_scores",
                buildJsonObject {
                    for ((source, score) in item.recallScores.toSortedMap()) put(source.name, number(score))
                },
            )
        }

    private fun number(value: Double): JsonElement =
        if (value.isFinite()) JsonPrimitive(value) else JsonPrimitive(value.toString())

    private fun strings(values: List<String>): JsonArray = JsonArray(values.map(::JsonPrimitive))
}
