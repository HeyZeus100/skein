package app.skein.core.vault.eval

import app.skein.core.model.ChunkId
import app.skein.core.model.DocId
import app.skein.core.model.IndexStore
import app.skein.core.model.NewChunk
import app.skein.core.model.RevisionHash
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Diagnostic-only comparison of the retired ranked probe with real row MATCH
 * at the original observation point, immediately after each committed write.
 * Intended for the isolated evaluation harness's sequential ingest only.
 *
 * This deliberately preserves the old ASCII extraction, first input row,
 * prefix query and global cap of 50. A legacy miss with a row match reproduces
 * rank crowding. A miss without a row match remains unresolved: the old word
 * can be an unindexable ASCII fragment inside a Unicode token, and this is not
 * a full posting-integrity check. No source text, probe words or IDs are kept.
 *
 * Work is bounded by one ranked query and at most one row MATCH per newly
 * written row per document. Audit queries increase ingest time, so reports
 * explicitly mark the audit active; audited ingest timing is not comparable
 * to an unaudited run. The delegate still performs every real write.
 */
internal class LexicalProbeDiagnostics(
    private val delegate: IndexStore,
) : IndexStore by delegate {
    private var documentsWritten = 0
    private var documentsWithoutRows = 0
    private var unprobeableDocuments = 0
    private var legacyProbes = 0
    private var legacyTop50Misses = 0
    private var legacyMissesWithRowMatch = 0
    private var legacyMissesWithoutRowMatch = 0
    private var legacyHitsWithoutRowMatch = 0
    private var rowMatchQueries = 0

    override suspend fun replaceChunks(
        docId: DocId,
        chunks: List<NewChunk>,
        embedderId: String,
        embedderVersion: Int,
        revisionHash: RevisionHash?,
    ): List<ChunkId> {
        val ids = delegate.replaceChunks(docId, chunks, embedderId, embedderVersion, revisionHash)
        documentsWritten++
        if (ids.isEmpty()) {
            documentsWithoutRows++
            return ids
        }
        val probe = LEGACY_WORD.findAll(chunks.first().text).map { it.value }.maxByOrNull { it.length }
        if (probe == null) {
            unprobeableDocuments++
            return ids
        }
        legacyProbes++
        val rankedIds = delegate.bm25(probe, 50).map { it.chunkId }.toSet()
        val legacyMiss = ids.none { it in rankedIds }
        var rowMatch = false
        for (id in ids) {
            rowMatchQueries++
            if (delegate.hasLexicalMatch(id, probe)) rowMatch = true
        }
        if (legacyMiss) {
            legacyTop50Misses++
            if (rowMatch) legacyMissesWithRowMatch++ else legacyMissesWithoutRowMatch++
        } else if (!rowMatch) {
            legacyHitsWithoutRowMatch++
        }
        return ids
    }

    fun report(): JsonObject =
        buildJsonObject {
            put("audit_active", true)
            put("observation_point", "immediately_after_replaceChunks_before_production_row_probes")
            put("ingest_timing", "includes_legacy_rank_and_row_match_audit_queries")
            put("documents_written", documentsWritten)
            put("documents_without_rows", documentsWithoutRows)
            put("legacy_unprobeable_documents", unprobeableDocuments)
            put("legacy_probes", legacyProbes)
            put("legacy_top50_misses", legacyTop50Misses)
            put("legacy_misses_with_row_match", legacyMissesWithRowMatch)
            put("legacy_misses_without_row_match", legacyMissesWithoutRowMatch)
            put("legacy_hits_without_row_match", legacyHitsWithoutRowMatch)
            put("row_match_queries", rowMatchQueries)
            put("scope", "sampled_legacy_term_postings_not_full_index_integrity")
        }

    private companion object {
        val LEGACY_WORD: Regex = Regex("[A-Za-z0-9]{3,}")
    }
}
