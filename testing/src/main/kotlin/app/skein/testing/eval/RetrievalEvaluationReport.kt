package app.skein.testing.eval

import app.skein.core.model.Retrieved
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

public data class RetrievalSample(
    val elapsedMillis: Double,
    val results: List<Retrieved>,
)

public data class EvaluatedQuery(
    val query: RetrievalGoldQuery,
    val score: RetrievalQueryScore,
    val samples: List<RetrievalSample>,
) {
    /** Includes order, exact floating scores, provenance, text and anchored identity. */
    public val deterministic: Boolean get() = samples.map { fingerprint(it.results) }.distinct().size == 1
}

/** Machine-readable artifacts use only synthetic fixture content and never app logs. */
public object RetrievalEvaluationReport {
    public fun mode(
        name: String,
        config: JsonObject,
        queries: List<EvaluatedQuery>,
    ): JsonObject =
        buildJsonObject {
            put("name", name)
            put("configuration", config)
            put("summary", aggregate(queries))
            put(
                "categories",
                buildJsonObject {
                    for ((category, rows) in queries.groupBy { it.query.category }.toSortedMap()) {
                        put(category, aggregate(rows))
                    }
                },
            )
            put("queries", JsonArray(queries.map(::query)))
        }

    private fun aggregate(rows: List<EvaluatedQuery>): JsonObject =
        buildJsonObject {
            val answerable = rows.filter { it.query.answerable }
            val absent = rows.filterNot { it.query.answerable }
            val recall = answerable.mapNotNull { it.score.recall }.meanOrNull()
            val ndcg = answerable.mapNotNull { it.score.ndcg }.meanOrNull()
            put("queries", rows.size)
            put("answerable_queries", answerable.size)
            put("absence_queries", absent.size)
            put("covered_evidence_spans", answerable.sumOf { it.score.coveredSpans })
            put("labelled_evidence_spans", answerable.sumOf { it.score.totalSpans })
            putNullable("macro_recall_at_8", recall)
            put("recall_queries_scored", answerable.count { it.score.recall != null })
            putNullable("macro_ndcg_at_8", ndcg)
            put("ndcg_queries_scored", answerable.count { it.score.ndcg != null })
            putNullable("mrr", answerable.mapNotNull { it.score.reciprocalRank }.meanOrNull())
            put("rejected_absence_queries", absent.count { it.score.rejected })
            putNullable(
                "absence_rejection_rate",
                if (absent.isEmpty()) {
                    null
                } else {
                    absent.count { it.score.rejected }.toDouble() /
                        absent.size
                },
            )
            put("falsely_rejected_answerable_queries", answerable.count { it.score.rejected })
            putNullable(
                "false_rejection_rate",
                if (answerable.isEmpty()) {
                    null
                } else {
                    answerable.count { it.score.rejected }.toDouble() /
                        answerable.size
                },
            )
            put("scope_violations", rows.sumOf { it.score.scopeViolationChunkIds.size })
            put("invalid_anchors", rows.sumOf { it.score.invalidAnchorChunkIds.size })
            put("provenance_violations", rows.sumOf { it.score.provenanceViolationChunkIds.size })
            put("duplicate_results", rows.sumOf { it.score.duplicateCount })
            put("nondeterministic_queries", rows.count { !it.deterministic })
            val latencies = rows.flatMap { it.samples }.map { it.elapsedMillis }
            put("timed_samples", latencies.size)
            putNullable("p50_ms", RetrievalMetrics.percentile(latencies, 0.5))
            putNullable("p95_ms", RetrievalMetrics.percentile(latencies, 0.95))
            put("recall_gate", RetrievalMetrics.RECALL_GATE)
            put("ndcg_gate", RetrievalMetrics.NDCG_GATE)
            put(
                "ranking_gate_status",
                when {
                    answerable.isEmpty() -> "NOT_APPLICABLE"
                    answerable.any { it.score.ndcg == null || it.score.recall == null } -> "INELIGIBLE"
                    checkNotNull(
                        recall,
                    ) >= RetrievalMetrics.RECALL_GATE &&
                        checkNotNull(ndcg) >= RetrievalMetrics.NDCG_GATE -> "PASS"
                    else -> "FAIL"
                },
            )
        }

    private fun query(row: EvaluatedQuery): JsonObject =
        buildJsonObject {
            val score = row.score
            put("id", row.query.id)
            put("category", row.query.category)
            put("space_alias", row.query.personaAlias ?: "default")
            put("answerable", row.query.answerable)
            put("covered_spans", score.coveredSpans)
            put("labelled_spans", score.totalSpans)
            putNullable("recall_at_8", score.recall)
            putNullable("dcg_at_8", score.dcg)
            putNullable("ideal_dcg_at_8", score.idealDcg)
            putNullable("ndcg_at_8", score.ndcg)
            putNullable("reciprocal_rank", score.reciprocalRank)
            put("grades", JsonArray(score.grades.map(::JsonPrimitive)))
            put("duplicate_results", score.duplicateCount)
            put("scope_violation_chunk_ids", JsonArray(score.scopeViolationChunkIds.map(::JsonPrimitive)))
            put("invalid_anchor_chunk_ids", JsonArray(score.invalidAnchorChunkIds.map(::JsonPrimitive)))
            put("provenance_violation_chunk_ids", JsonArray(score.provenanceViolationChunkIds.map(::JsonPrimitive)))
            put("rejected", score.rejected)
            put("deterministic", row.deterministic)
            put(
                "runs",
                JsonArray(
                    row.samples.map { sample ->
                        buildJsonObject {
                            put("elapsed_ms", sample.elapsedMillis)
                            put("fingerprint", fingerprint(sample.results))
                            put("results", JsonArray(sample.results.map(::retrieved)))
                        }
                    },
                ),
            )
        }

    private fun retrieved(item: Retrieved): JsonObject =
        buildJsonObject {
            put("chunk_id", item.chunkId)
            put("doc_id", item.docId)
            // This reporter is restricted to explicit synthetic evaluations, never application logs.
            put("doc_title", item.docTitle)
            put("text", item.text)
            put("revision_hash", item.revisionHash?.let(::JsonPrimitive) ?: JsonNull)
            put("byte_start", item.locator?.byteStart?.let(::JsonPrimitive) ?: JsonNull)
            put("byte_end", item.locator?.byteEnd?.let(::JsonPrimitive) ?: JsonNull)
            put("score", item.score)
            put(
                "recall_scores",
                buildJsonObject {
                    for ((source, value) in item.recallScores.toSortedMap()) put(source.name, value)
                },
            )
            put("recalled_by", strings(item.recalledBy.map { it.name }.sorted()))
        }

    public fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun List<Double>.meanOrNull(): Double? = if (isEmpty()) null else average()

    private fun strings(values: List<String>): JsonElement = JsonArray(values.map(::JsonPrimitive))

    private fun JsonObjectBuilder.putNullable(
        key: String,
        value: Double?,
    ) {
        put(key, value?.let(::JsonPrimitive) ?: JsonNull)
    }
}

private fun fingerprint(results: List<Retrieved>): String =
    RetrievalEvaluationReport.sha256(
        // Length-prefix each component so fixture delimiters cannot alias a different list.
        results
            .joinToString("") { item ->
                listOf(
                    item.chunkId.toString(),
                    item.docId,
                    item.docTitle,
                    item.sourceKind.name,
                    item.revisionHash.orEmpty(),
                    item.locator.toString(),
                    item.score.toBits().toString(),
                    item.recallScores
                        .toSortedMap()
                        .entries
                        .joinToString(",") { "${it.key}:${it.value.toBits()}" },
                    item.recalledBy
                        .map { it.name }
                        .sorted()
                        .joinToString(","),
                    item.text,
                ).joinToString("") { "${it.length}:$it" }
            }.toByteArray(Charsets.UTF_8),
    )
