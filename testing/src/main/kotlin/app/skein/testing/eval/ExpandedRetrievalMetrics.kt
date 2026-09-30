package app.skein.testing.eval

import app.skein.core.model.Chunk
import app.skein.core.model.ContextualRetrievalResult
import app.skein.core.model.DocumentKind
import app.skein.core.model.Locator
import app.skein.core.model.Retrieved
import app.skein.core.model.RevisionHashing
import kotlinx.serialization.json.JsonObject

/** Actual immutable revision and current owner; frontmatter participates in the revision hash. */
public data class ExpandedValidationDocument(
    val id: String,
    val personaId: String,
    val revisionHash: String,
    val bodySnapshot: String,
    val frontmatterSnapshot: JsonObject,
    val kind: DocumentKind,
    val title: String,
    val rawBody: String,
)

/** Enumeration scores are deliberately not query ranking signals. */
public data class ExpandedIndexedChunk(
    val stored: Chunk,
    val assembled: Retrieved,
)

public enum class ValidationRepresentation { INDEXED_CHUNKS, EXPANDED_UNITS }

public data class RequiredEvidenceSpan(
    val documentId: String,
    val revisionHash: String,
    val start: Int,
    val end: Int,
)

public data class ExpandedQueryScore(
    val coveredSpans: Int,
    val totalSpans: Int,
    val rawEvidenceCount: Int,
    val validRanks: List<Int>,
    val violations: List<String>,
) {
    public val allSpansCovered: Boolean get() = totalSpans > 0 && coveredSpans == totalSpans
    public val correctAbsenceRejection: Boolean get() = totalSpans == 0 && rawEvidenceCount == 0 && violations.isEmpty()
    public val falseAbsenceAdmission: Boolean get() = totalSpans == 0 && rawEvidenceCount > 0
    public val strictSuccess: Boolean get() = violations.isEmpty() && (allSpansCovered || correctAbsenceRejection)
}

/**
 * Additive expanded-unit scorer. No legacy nDCG, gate or returned-only ideal universe is reused.
 * Invalid results consume raw rank; all supplied evidence and member maps remain auditable.
 */
public object ExpandedRetrievalMetrics {
    public const val K: Int = 8

    /** Bind enumeration output to the independently read row and document, not another assembler call. */
    public fun validIndexedIdentity(
        item: ExpandedIndexedChunk,
        document: ExpandedValidationDocument?,
    ): Boolean {
        if (document == null) return false
        val row = item.stored
        val assembled = item.assembled
        val start = row.byteStart ?: return false
        val end = row.byteEnd ?: return false
        val raw = document.rawBody.toByteArray(Charsets.UTF_8)
        if (start < 0 || end <= start || end > raw.size || !boundary(raw, start) || !boundary(raw, end)) return false
        if (RevisionHashing.canonicalBody(document.rawBody) != document.bodySnapshot ||
            RevisionHashing.compute(document.bodySnapshot, document.frontmatterSnapshot) != document.revisionHash
        ) {
            return false
        }

        fun canonicalOffset(offset: Int): Int =
            offset -
                (0 until offset).count {
                    raw[it] == 13.toByte() && it + 1 < raw.size && raw[it + 1] == 10.toByte()
                }
        return row.docId == document.id &&
            row.revisionHash == document.revisionHash &&
            assembled.chunkId == row.id &&
            assembled.docId == row.docId &&
            assembled.text == row.text &&
            assembled.revisionHash == row.revisionHash &&
            assembled.docTitle == document.title &&
            assembled.sourceKind == document.kind &&
            assembled.locator == Locator(canonicalOffset(start), canonicalOffset(end), row.ord)
    }

    /** Conservative repeated-sample credit: every run must cover; any nonempty run admits. */
    public fun acrossSamples(scores: List<ExpandedQueryScore>): ExpandedQueryScore {
        require(scores.isNotEmpty() && scores.map { it.totalSpans }.distinct().size == 1)
        return ExpandedQueryScore(
            scores.minOf { it.coveredSpans },
            scores.first().totalSpans,
            scores.maxOf { it.rawEvidenceCount },
            scores.first().validRanks.filter { rank -> scores.all { rank in it.validRanks } },
            scores.flatMap { it.violations }.distinct(),
        )
    }

    public fun requiredSpans(
        query: RetrievalGoldQuery,
        documents: Map<String, ExpandedValidationDocument>,
    ): List<RequiredEvidenceSpan> =
        query.relevant
            .map { label ->
                require(label.grade == 3)
                val doc = documents.getValue(label.docId)
                val text = doc.bodySnapshot
                val start = text.indexOf(label.evidence)
                require(label.evidence.isNotBlank() && start >= 0 && start == text.lastIndexOf(label.evidence))
                RequiredEvidenceSpan(
                    doc.id,
                    doc.revisionHash,
                    text.substring(0, start).toByteArray(Charsets.UTF_8).size,
                    text.substring(0, start + label.evidence.length).toByteArray(Charsets.UTF_8).size,
                )
            }.also { require(it.distinct().size == it.size) }

    public fun score(
        query: RetrievalGoldQuery,
        personaId: String,
        documents: Map<String, ExpandedValidationDocument>,
        indexed: List<ExpandedIndexedChunk>,
        result: ContextualRetrievalResult,
        representation: ValidationRepresentation,
    ): ExpandedQueryScore {
        require(indexed.map { it.stored.id }.distinct().size == indexed.size)
        val violations = linkedSetOf<String>()
        val byId =
            indexed
                .filter { item ->
                    validIndexedIdentity(item, documents[item.stored.docId]).also {
                        if (!it) violations += "index:${item.stored.id}:UNBOUND_STORED_IDENTITY"
                    }
                }.associate { it.stored.id to it.assembled }
        val evidence = result.evidence
        val spans = requiredSpans(query, documents)
        val invalidRanks = mutableSetOf<Int>()

        fun reject(
            rank: Int,
            reason: String,
        ) {
            invalidRanks += rank
            violations += "$rank:$reason"
        }
        if (result.originalQuery != query.query) violations += "request:ORIGINAL_QUERY_CHANGED"
        if (evidence.size > K) violations += "result:OVER_LIMIT"
        val candidateIds = result.originalCandidates.map { it.chunkId }
        if (candidateIds.size > K) violations += "candidates:OVER_LIMIT"
        if (candidateIds.distinct().size != candidateIds.size) violations += "candidates:DUPLICATE"
        val candidates = result.originalCandidates.associateBy { it.chunkId }
        if (representation == ValidationRepresentation.EXPANDED_UNITS) {
            for (candidate in result.originalCandidates) {
                val stored = byId[candidate.chunkId]
                if (stored == null || !sameIndexedIdentity(candidate, stored) || !finite(candidate)) {
                    violations += "candidate:${candidate.chunkId}:UNVERIFIED"
                }
            }
            if (result.evidenceMembers.keys != evidence.map { it.chunkId }.toSet()) violations += "members:KEY_SET"
        } else if (result.evidenceMembers.isNotEmpty() || result.originalCandidates.isNotEmpty()) {
            violations += "baseline:UNEXPECTED_EXPANDED_METADATA"
        }
        val firstRank = mutableMapOf<Long, Int>()
        val usedMembers = mutableMapOf<Long, Int>()
        for ((position, unit) in evidence.withIndex()) {
            val rank = position + 1
            val first = firstRank.putIfAbsent(unit.chunkId, rank)
            if (first != null) reject(rank, "DUPLICATE")
            val doc = documents[unit.docId]
            if (doc == null) {
                reject(rank, "MISSING_SOURCE")
                continue
            }
            if (doc.kind != DocumentKind.NOTE || unit.sourceKind != doc.kind) reject(rank, "UNSUPPORTED_SOURCE")
            if (doc.personaId != personaId || unit.docId in query.forbiddenDocIds) reject(rank, "OUT_OF_SCOPE")
            if (unit.revisionHash != doc.revisionHash ||
                RevisionHashing.compute(doc.bodySnapshot, doc.frontmatterSnapshot) != doc.revisionHash
            ) {
                reject(rank, "STALE_REVISION")
            }
            if (unit.docTitle != doc.title || !finite(unit)) reject(rank, "IDENTITY_OR_SIGNAL")
            val anchor = unit.locator
            if (!validBounds(anchor, doc.bodySnapshot)) {
                reject(rank, "INVALID_ANCHOR")
                continue
            }
            if (representation == ValidationRepresentation.INDEXED_CHUNKS) {
                val stored = byId[unit.chunkId]
                if (stored == null || !sameIndexedIdentity(unit, stored)) reject(rank, "UNINDEXED")
                continue
            }
            val exact = doc.bodySnapshot.toByteArray(Charsets.UTF_8).copyOfRange(anchor!!.byteStart, anchor.byteEnd)
            if (!exact.contentEquals(unit.text.toByteArray(Charsets.UTF_8))) reject(rank, "SOURCE_BYTES")
            val members = result.evidenceMembers[unit.chunkId].orEmpty()
            if (members.isEmpty()) reject(rank, "NO_MEMBERS")
            val memberIds = members.map { it.chunkId }
            if (memberIds.distinct().size != memberIds.size) reject(rank, "DUPLICATE_MEMBER")
            val order = memberIds.map { candidateIds.indexOf(it) }
            if (order.any { it < 0 } || order != order.sorted()) reject(rank, "MEMBER_ORDER")
            val representative = members.minByOrNull { candidateIds.indexOf(it.chunkId) }
            if (representative == null ||
                representative.chunkId != unit.chunkId ||
                !sameSignals(unit, representative)
            ) {
                reject(rank, "REPRESENTATIVE")
            }
            for (member in members) {
                val prior = usedMembers.putIfAbsent(member.chunkId, rank)
                if (prior != null && prior != rank) {
                    reject(prior, "REUSED_MEMBER")
                    reject(rank, "REUSED_MEMBER")
                }
                val stored = byId[member.chunkId]
                val memberAnchor = member.locator
                if (stored == null || !sameIndexedIdentity(member, stored) || member != candidates[member.chunkId]) {
                    reject(rank, "UNVERIFIED_MEMBER")
                }
                if (member.docId != unit.docId ||
                    member.revisionHash != unit.revisionHash ||
                    memberAnchor == null ||
                    memberAnchor.byteStart < anchor.byteStart ||
                    memberAnchor.byteEnd > anchor.byteEnd
                ) {
                    reject(rank, "MEMBER_OUTSIDE_UNIT")
                }
            }
        }
        if (representation == ValidationRepresentation.EXPANDED_UNITS) {
            for (i in evidence.indices) {
                for (j in 0 until i) {
                    val a = evidence[i]
                    val b = evidence[j]
                    val x = a.locator ?: continue
                    val y = b.locator ?: continue
                    if (a.docId == b.docId &&
                        a.revisionHash == b.revisionHash &&
                        x.byteStart < y.byteEnd &&
                        y.byteStart < x.byteEnd
                    ) {
                        reject(j + 1, "OVERLAP")
                        reject(i + 1, "OVERLAP")
                    }
                }
            }
        }
        val valid = evidence.withIndex().filter { it.index < K && it.index + 1 !in invalidRanks }
        val covered =
            spans.count { span ->
                val intervals =
                    valid
                        .map { it.value }
                        .filter {
                            it.docId == span.documentId &&
                                it.revisionHash == span.revisionHash
                        }.mapNotNull { it.locator }
                        .sortedBy { it.byteStart }
                var end = span.start
                for (range in intervals) {
                    if (range.byteEnd <= end) continue
                    if (range.byteStart > end) break
                    end = maxOf(end, range.byteEnd)
                }
                end >= span.end
            }
        return ExpandedQueryScore(covered, spans.size, evidence.size, valid.map { it.index + 1 }, violations.toList())
    }

    private fun sameIndexedIdentity(
        a: Retrieved,
        b: Retrieved,
    ): Boolean =
        a.chunkId == b.chunkId &&
            a.docId == b.docId &&
            a.docTitle == b.docTitle &&
            a.text == b.text &&
            a.sourceKind == b.sourceKind &&
            a.revisionHash == b.revisionHash &&
            a.locator == b.locator

    private fun sameSignals(
        a: Retrieved,
        b: Retrieved,
    ): Boolean = a.score == b.score && a.recallScores == b.recallScores && a.recalledBy == b.recalledBy

    private fun finite(item: Retrieved): Boolean =
        item.score.isFinite() && item.recallScores.values.all { it.isFinite() }

    private fun validBounds(
        anchor: Locator?,
        body: String,
    ): Boolean {
        if (anchor == null) return false
        val bytes = body.toByteArray(Charsets.UTF_8)
        return anchor.byteStart >= 0 &&
            anchor.byteEnd > anchor.byteStart &&
            anchor.byteEnd <= bytes.size &&
            boundary(bytes, anchor.byteStart) &&
            boundary(bytes, anchor.byteEnd)
    }

    private fun boundary(
        bytes: ByteArray,
        offset: Int,
    ): Boolean = offset == bytes.size || bytes[offset].toInt() and 0xc0 != 0x80
}
