package app.skein.testing.eval

import app.skein.core.model.DocumentKind
import app.skein.core.model.Retrieved
import app.skein.core.model.RevisionHashing
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.pow

public data class GoldEvidence(
    val docId: String,
    val grade: Int,
    val evidence: String,
)

public data class RetrievalGoldQuery(
    val id: String,
    val category: String,
    val query: String,
    /** Fixture alias; null means the app's default Space, never All Spaces. */
    val personaAlias: String?,
    val relevant: List<GoldEvidence>,
    val forbiddenDocIds: Set<String>,
) {
    public val answerable: Boolean get() = relevant.any { it.grade == 3 }
}

public data class EvaluationDocument(
    val id: String,
    /** Resolved real Space ID, including the default owner of old unassigned notes. */
    val personaId: String,
    val revisionHash: String,
    val bodySnapshot: String,
    val kind: DocumentKind = DocumentKind.NOTE,
)

public data class RetrievalQueryScore(
    val coveredSpans: Int,
    val totalSpans: Int,
    val recall: Double?,
    val dcg: Double?,
    val idealDcg: Double?,
    val ndcg: Double?,
    val reciprocalRank: Double?,
    val grades: List<Int>,
    val rankedChunkIds: List<Long>,
    val duplicateCount: Int,
    val scopeViolationChunkIds: List<Long>,
    val invalidAnchorChunkIds: List<Long>,
    val provenanceViolationChunkIds: List<Long>,
    /** The current production evidence selection is its returned list, with no calibrated rejector. */
    val rejected: Boolean,
)

/** Exact revision/UTF-8-locator scoring. Gold labels never participate in retrieval. */
public object RetrievalMetrics {
    public const val K: Int = 8
    public const val RECALL_GATE: Double = 0.75
    public const val NDCG_GATE: Double = 0.60

    public fun goldQueries(): List<RetrievalGoldQuery> =
        RetrievalEvaluationVault.queries().map { value ->
            val q = value.jsonObject
            RetrievalGoldQuery(
                id = q.getValue("id").jsonPrimitive.content,
                category = q.getValue("category").jsonPrimitive.content,
                query = q.getValue("query").jsonPrimitive.content,
                personaAlias =
                    q
                        .getValue("persona_id")
                        .takeUnless { it == JsonNull }
                        ?.jsonPrimitive
                        ?.content,
                relevant =
                    q.getValue("relevant").jsonArray.map { row ->
                        val r = row.jsonObject
                        GoldEvidence(
                            r.getValue("doc_id").jsonPrimitive.content,
                            r.getValue("grade").jsonPrimitive.int,
                            r.getValue("evidence").jsonPrimitive.content,
                        )
                    },
                forbiddenDocIds =
                    q["forbidden_doc_ids"]
                        ?.jsonArray
                        ?.map { it.jsonPrimitive.content }
                        ?.toSet()
                        .orEmpty(),
            )
        }

    /**
     * [indexed] is the entire real indexed corpus, hydrated by the same production
     * assembler as [returned]. It supplies the ideal ranking, never the candidates.
     * A grade-3 gain occurs at the first rank completing an exact evidence span;
     * overlapping chunks cannot repeat it. Other chunks from that doc grade 2.
     * Union coverage permits an evidence sentence to straddle two chunks.
     */
    public fun score(
        query: RetrievalGoldQuery,
        personaId: String,
        documents: Map<String, EvaluationDocument>,
        indexed: List<Retrieved>,
        returned: List<Retrieved>,
    ): RetrievalQueryScore {
        // Validate seed labels too, although only answering spans contribute recall.
        val labelledSpans = query.relevant.map { it to evidenceSpan(it, documents) }
        val spans = labelledSpans.filter { it.first.grade == 3 }.map { it.second }.distinct()
        val labels = query.relevant.groupBy { it.docId }.mapValues { (_, rows) -> rows.maxOf { it.grade } }
        // Preserve raw positions: a duplicate consumes its rank with zero gain.
        // It must never promote a ninth result into the @8 evaluation window.
        val ranked = returned.take(K)
        val byId = indexed.associateBy { it.chunkId }

        fun validResult(item: Retrieved): Boolean {
            val stored = byId[item.chunkId] ?: return false
            return validAnchor(item, documents) &&
                eligible(item.sourceKind) &&
                eligible(documents.getValue(item.docId).kind) &&
                item.docId == stored.docId &&
                item.revisionHash == stored.revisionHash &&
                item.locator == stored.locator &&
                item.text == stored.text
        }
        val seen = mutableSetOf<Long>()
        val checked =
            ranked.map {
                if (seen.add(it.chunkId) && validResult(it)) it else it.copy(revisionHash = null)
            }
        val valid = checked.filter { it.revisionHash != null }
        val grades =
            grades(checked, spans, labels, documents)
        val idealCandidates =
            indexed.distinctBy { it.chunkId }.filter {
                eligible(it.sourceKind) &&
                    eligible(documents.getValue(it.docId).kind) &&
                    labels.containsKey(it.docId) &&
                    documents[it.docId]?.personaId == personaId &&
                    it.docId !in query.forbiddenDocIds &&
                    validAnchor(it, documents)
            }
        val ideal = if (query.answerable) idealDcg(idealCandidates, spans, labels, documents) else null
        val dcg = if (query.answerable) dcg(grades) else null
        val covered = spans.count { covered(it, valid) }
        return RetrievalQueryScore(
            coveredSpans = covered,
            totalSpans = spans.size,
            recall = if (spans.isEmpty()) null else covered.toDouble() / spans.size,
            dcg = dcg,
            idealDcg = ideal,
            ndcg = if (ideal != null && ideal > 0.0) checkNotNull(dcg) / ideal else null,
            reciprocalRank =
                if (!query.answerable) {
                    null
                } else {
                    grades.indexOfFirst { it == 3 }.let {
                        if (it <
                            0
                        ) {
                            0.0
                        } else {
                            1.0 / (it + 1)
                        }
                    }
                },
            grades = grades,
            rankedChunkIds = ranked.map { it.chunkId },
            duplicateCount = returned.size - returned.distinctBy { it.chunkId }.size,
            scopeViolationChunkIds =
                returned
                    .filter {
                        documents[it.docId]?.personaId != personaId || it.docId in query.forbiddenDocIds
                    }.map { it.chunkId }
                    .distinct(),
            invalidAnchorChunkIds = returned.filterNot(::validResult).map { it.chunkId }.distinct(),
            provenanceViolationChunkIds =
                returned
                    .filter {
                        !eligible(it.sourceKind) || documents[it.docId]?.kind?.let(::eligible) != true
                    }.map { it.chunkId }
                    .distinct(),
            rejected = ranked.isEmpty(),
        )
    }

    public fun eligible(kind: DocumentKind): Boolean = kind == DocumentKind.NOTE || kind == DocumentKind.ATTACHMENT

    /** Nearest-rank percentile, with no interpolation or fabricated empty-sample zero. */
    public fun percentile(
        values: List<Double>,
        percentile: Double,
    ): Double? {
        require(percentile > 0.0 && percentile <= 1.0)
        require(values.all { it.isFinite() && it >= 0.0 })
        if (values.isEmpty()) return null
        return values.sorted()[ceil(percentile * values.size).toInt() - 1]
    }

    /** Reject stale revisions, out-of-bounds byte spans, and text/locator mismatches. */
    public fun validAnchor(
        item: Retrieved,
        documents: Map<String, EvaluationDocument>,
    ): Boolean {
        val doc = documents[item.docId] ?: return false
        val locator = item.locator ?: return false
        if (item.revisionHash != doc.revisionHash) return false
        val bytes = doc.bodySnapshot.toByteArray(Charsets.UTF_8)
        if (locator.byteStart < 0 || locator.byteEnd <= locator.byteStart || locator.byteEnd > bytes.size) return false
        // Source bytes must begin and end on UTF-8 code-point boundaries.
        if (bytes[locator.byteStart].toInt() and 0xc0 == 0x80 ||
            (locator.byteEnd < bytes.size && bytes[locator.byteEnd].toInt() and 0xc0 == 0x80)
        ) {
            return false
        }
        val source = bytes.copyOfRange(locator.byteStart, locator.byteEnd).toString(Charsets.UTF_8)
        val excerpt = RevisionHashing.canonicalBody(item.text)
        // IngestSteps stores Chunk.embeddingText: optional heading breadcrumb,
        // blank line, then the exact source slice. The harness separately checks
        // that metadata against production Chunker output for every indexed row.
        return excerpt == source || excerpt.endsWith("\n\n$source")
    }

    private data class Span(
        val docId: String,
        val revision: String,
        val start: Int,
        val end: Int,
    )

    private fun evidenceSpan(
        label: GoldEvidence,
        documents: Map<String, EvaluationDocument>,
    ): Span {
        val doc = checkNotNull(documents[label.docId]) { "Gold document is absent from the stored corpus" }
        require(label.evidence.isNotEmpty()) { "Gold evidence must not be empty" }
        val start = doc.bodySnapshot.indexOf(label.evidence)
        check(start >= 0) { "Gold evidence is absent from the stored revision" }
        check(doc.bodySnapshot.indexOf(label.evidence, start + 1) < 0) { "Gold evidence has an ambiguous stored span" }
        val byteStart =
            doc.bodySnapshot
                .substring(0, start)
                .toByteArray(Charsets.UTF_8)
                .size
        return Span(doc.id, doc.revisionHash, byteStart, byteStart + label.evidence.toByteArray(Charsets.UTF_8).size)
    }

    private fun covered(
        span: Span,
        chunks: List<Retrieved>,
    ): Boolean {
        var end = span.start
        val intervals =
            chunks
                .filter { it.docId == span.docId && it.revisionHash == span.revision }
                .mapNotNull { it.locator }
                .sortedBy { it.byteStart }
        for (interval in intervals) {
            if (interval.byteStart > end) break
            if (interval.byteEnd > end) end = interval.byteEnd
            if (end >= span.end) return true
        }
        return false
    }

    private fun grades(
        chunks: List<Retrieved>,
        spans: List<Span>,
        labels: Map<String, Int>,
        documents: Map<String, EvaluationDocument>,
    ): List<Int> {
        val prefix = mutableListOf<Retrieved>()
        val credited = mutableSetOf<Span>()
        return chunks.map { chunk ->
            if (!validAnchor(chunk, documents)) return@map 0
            prefix += chunk
            val newlyCovered = spans.filter { it !in credited && covered(it, prefix) }
            credited += newlyCovered
            if (newlyCovered.isNotEmpty()) 3 else minOf(labels[chunk.docId] ?: 0, 2)
        }
    }

    private fun gain(
        grade: Int,
        rank: Int,
    ): Double = (2.0.pow(grade) - 1.0) / (ln(rank + 1.0) / ln(2.0))

    private fun dcg(grades: List<Int>): Double = grades.mapIndexed { i, grade -> gain(grade, i + 1) }.sum()

    /**
     * Exact subset dynamic program over positive indexed chunks (not retrieved
     * candidates). This development corpus has only 1-2 per query. Fail loudly
     * if a future corpus exceeds the bounded oracle; never silently approximate
     * IDCG or normalize against only the returned results.
     */
    private fun idealDcg(
        chunks: List<Retrieved>,
        spans: List<Span>,
        labels: Map<String, Int>,
        documents: Map<String, EvaluationDocument>,
    ): Double {
        check(chunks.size <= 18) { "Exact IDCG oracle exceeds 18 positive chunks; extend the evaluator explicitly" }
        val cache = mutableMapOf<Int, Double>()

        fun best(mask: Int): Double {
            val rank = Integer.bitCount(mask) + 1
            if (rank > K || rank > chunks.size) return 0.0
            return cache.getOrPut(mask) {
                val selected = chunks.filterIndexed { i, _ -> mask and (1 shl i) != 0 }
                val before = spans.filter { covered(it, selected) }.toSet()
                chunks.indices.filter { mask and (1 shl it) == 0 }.maxOfOrNull { i ->
                    val chunk = chunks[i]
                    val completed = spans.any { it !in before && covered(it, selected + chunk) }
                    val grade = if (completed) 3 else minOf(labels[chunk.docId] ?: 0, 2)
                    check(validAnchor(chunk, documents))
                    gain(grade, rank) + best(mask or (1 shl i))
                } ?: 0.0
            }
        }
        return best(0)
    }
}
