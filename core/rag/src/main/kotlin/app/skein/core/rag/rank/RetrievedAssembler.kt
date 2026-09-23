// `RetrievedAssembler` (skein-wqli, E5.I13 prep). Bridges the fused,
// ranked `List<ScoredChunk>` that [PprRanker.rank] / [ScoreFusion.combine]
// return onto `app.skein.core.model.Retrieved` (locked verbatim by
// `E0.I12`/skein-x4f), so `RetrievalServiceImpl` (`E5.I13`, skein-do6) can
// call `PprRanker.rank` and this assembler and be done — no re-deriving a
// document id, title or provenance set from a chunk id by hand.
//
// This is additive: it does not touch `PprRanker`, `ScoreFusion` or any
// recall stage's public signature. It lives in `app.skein.core.rag.rank`
// (not `app.skein.core.model`) — bridging `core/rag`'s producer
// types to `core/model`'s locked consumer type is exactly the "namespace"
// note on skein-wqli; the two packages stay where they are.
//
// ## Inputs, and why no new store call is invented
//
// [assemble] takes the same two things `RetrievalServiceImpl` already has
// once it has called `PprRanker.rank`:
//   - `ranked`: that call's own return value, already ordered (descending
//     score, ascending `chunkId` tie-break) and capped at `k` — this class
//     preserves that order and count exactly; it never re-sorts, re-caps
//     or drops a survivor except when a store lookup below comes back
//     empty.
//   - `sources`: the `Map<CitationSourceKind, List<ScoredChunk>>` the
//     caller already built to *pass to* `PprRanker.rank` (one entry per
//     recall stage that ran). [Retrieved.recalledBy] is derived from it via
//     [RecallSource.citationSourceKind] (`core/model/.../Retrieval.kt`) run
//     in reverse — the one existing bridge between the two enums, not a
//     second one invented here. `CitationSourceKind.RERANK` has no
//     [RecallSource] counterpart (a rerank stage reorders, it does not
//     recall — see that enum's kdoc) and contributes nothing.
//
// ## Hydration, batched
//
// `Retrieved.docTitle`/`sourceKind` need each hit's `Document`, and
// `Retrieved.text`/`docId` need its `Chunk` row. `PprRanker.rank` already
// looked both up internally (`docByChunk`) but does not expose them —
// exposing internal state was not what plan `E5.I12` locked, and re-deriving
// it externally is exactly `E5.I13`'s job per that class's own file header
// ("`E5.I13` … will map this list onto [`Retrieved`] — with the document
// id, locator and source kind it hydrates from the store at that point").
// So this class re-fetches, the same way `PprRanker.docByChunk` and
// `RetrievalServiceImplTest`'s spec both do it: one batched
// `IndexStore.getChunks(chunkIds)` call for every chunk, then one
// `VaultRepository.getDocument` call per *distinct* document id (`ranked`
// is already ≤ `k`, typically 8, and multiple chunks routinely share a
// document, so deduping here is a real savings) — never one store call per
// ranked chunk.
//
// ## locator / revisionHash (skein-g32i)
//
// `Retrieved.revisionHash` is `chunk.revisionHash` verbatim — `chunks.revision_hash`
// (migration 003, populated as of migration 008 / skein-zx15) stamped at
// ingest time from `VaultRepository.currentRevision(docId)?.revisionHash`.
// Null exactly when the chunk row predates 008 or was written with no
// revision to report — `Retrieved.revisionHash` degrades the same way.
//
// `Retrieved.locator` is built from `chunk.byteStart`/`chunk.byteEnd`
// (migration 008), but those columns anchor to the RAW `documents.body_md`
// the chunker sliced at ingest time — NOT the canonicalized
// `document_revisions.body_md_snapshot` that `docs/design/POST_REVIEW_RESOLUTIONS.md`
// §1.3 defines a citation locator against (skein-zx15's deviation 6 /
// skein-g32i's note). `RevisionHashing.canonicalBody` only ever (a) deletes
// the `\r` of a `\r\n` pair, shifting every following byte offset left by
// one, or (b) rewrites a lone `\r` to `\n` in place, which shifts nothing
// (both are single-byte ASCII). So [locatorFor] remaps a raw byte offset to
// its canonical counterpart by counting, over the *raw* bytes strictly
// before it, how many are the `\r` of a `\r\n` pair — a deterministic,
// read-time-only fix that needs no new store call (this class already
// hydrates `document.bodyMd`, the exact raw bytes the offsets index). For
// an LF-only body the count is always zero, so the remap is a no-op and
// the raw/canonical offsets agree, matching every fixture without a CRLF.
//
// A chunk missing either offset, or a document with no `bodyMd` (an
// attachment is never chunked, so should not occur, but is not assumed),
// degrades `locator` to `null` rather than throwing — the item is still
// assembled, just without a byte-anchored citation.

package app.skein.core.rag.rank

import app.skein.core.model.Chunk
import app.skein.core.model.ChunkId
import app.skein.core.model.CitationSourceKind
import app.skein.core.model.DocId
import app.skein.core.model.Document
import app.skein.core.model.IndexStore
import app.skein.core.model.Locator
import app.skein.core.model.RecallSource
import app.skein.core.model.Retrieved
import app.skein.core.model.ScoredChunk
import app.skein.core.model.VaultRepository

/**
 * Bridges [PprRanker]/[ScoreFusion]'s `List<ScoredChunk>` output to
 * `List<Retrieved>`. See the file header for the mapping, and for how
 * `revisionHash`/`locator` are populated (or degrade to `null`).
 *
 * @param index backs the batched chunk hydration (`getChunks`).
 * @param repo backs the per-document hydration (`getDocument`, deduped).
 */
public class RetrievedAssembler(
    private val index: IndexStore,
    private val repo: VaultRepository,
) {
    /**
     * Maps [ranked] onto [Retrieved], preserving its order and size exactly
     * except for a ranked chunk whose `Chunk` row or `Document` can no
     * longer be read back (an orphan, or a document deleted between rank
     * and assemble) — that entry is dropped rather than surfaced half-null
     * or thrown.
     *
     * [sources] is the per-recall-stage map the caller already built for
     * [PprRanker.rank]; it drives [Retrieved.recalledBy] only and need not
     * contain every id in [ranked] (a chunk [PprRanker] surfaced via the
     * PPR blend alone, with no recall stage of its own — impossible under
     * the current pipeline since every candidate originates in `sources`,
     * but not assumed here — simply gets an empty [Retrieved.recalledBy]).
     *
     * An empty [ranked] returns `emptyList()` without calling the store.
     */
    public suspend fun assemble(
        ranked: List<ScoredChunk>,
        sources: Map<CitationSourceKind, List<ScoredChunk>>,
    ): List<Retrieved> {
        if (ranked.isEmpty()) return emptyList()

        val recalledByChunk = recalledBy(sources)
        val chunksById = index.getChunks(ranked.map { it.chunkId })
        val documentsByDocId = hydrateDocuments(chunksById.values.map { it.docId })

        val out = ArrayList<Retrieved>(ranked.size)
        for (scored in ranked) {
            val chunk = chunksById[scored.chunkId] ?: continue
            val document = documentsByDocId[chunk.docId] ?: continue
            out +=
                Retrieved(
                    chunkId = scored.chunkId,
                    docId = chunk.docId,
                    docTitle = document.title,
                    text = chunk.text,
                    score = scored.score,
                    sourceKind = document.kind,
                    recalledBy = recalledByChunk[scored.chunkId] ?: emptySet(),
                    revisionHash = chunk.revisionHash,
                    locator = locatorFor(chunk, document),
                )
        }
        return out
    }

    /**
     * Builds [chunk]'s [Locator] by remapping its RAW `documents.body_md`
     * byte offsets onto [document]'s canonical `body_md` — see the file
     * header for why the remap is needed and why it is correct. Returns
     * `null` when [chunk] has no offsets to report, or when they no longer
     * fit [document]'s current `bodyMd` (a stale chunk row that predates
     * the document's latest edit) — a defensive degrade, never a throw.
     */
    private fun locatorFor(
        chunk: Chunk,
        document: Document,
    ): Locator? {
        val byteStart = chunk.byteStart
        val byteEnd = chunk.byteEnd
        if (byteStart == null || byteEnd == null) return null
        val rawBytes = (document.bodyMd ?: return null).toByteArray(Charsets.UTF_8)
        if (byteStart < 0 || byteEnd < byteStart || byteEnd > rawBytes.size) return null
        return Locator(
            byteStart = canonicalByteOffset(rawBytes, byteStart),
            byteEnd = canonicalByteOffset(rawBytes, byteEnd),
            chunkOrd = chunk.ord,
        )
    }

    /**
     * Maps a UTF-8 byte offset into [rawBytes] (the RAW `documents.body_md`
     * `chunks.byte_start`/`byte_end` index) onto the matching offset into
     * `RevisionHashing.canonicalBody`'s output, by counting how many bytes
     * strictly before [rawOffset] are the `\r` of a `\r\n` pair — the only
     * byte `canonicalBody` ever deletes (see the file header). A lone `\r`
     * is rewritten to `\n` in place and shifts nothing. Both `\r` and `\n`
     * are single-byte ASCII, so this scan never misreads a multi-byte UTF-8
     * sequence's continuation bytes as either.
     */
    private fun canonicalByteOffset(
        rawBytes: ByteArray,
        rawOffset: Int,
    ): Int {
        var deleted = 0
        for (i in 0 until rawOffset) {
            if (rawBytes[i] == CARRIAGE_RETURN && i + 1 < rawBytes.size && rawBytes[i + 1] == LINE_FEED) {
                deleted++
            }
        }
        return rawOffset - deleted
    }

    /** One [VaultRepository.getDocument] call per distinct id in [docIds], never per chunk. */
    private suspend fun hydrateDocuments(docIds: Collection<DocId>): Map<DocId, Document> {
        val out = LinkedHashMap<DocId, Document>()
        for (docId in docIds.toSet()) {
            repo.getDocument(docId)?.let { out[docId] = it }
        }
        return out
    }

    /**
     * The reverse of [RecallSource.citationSourceKind]: which [RecallSource]
     * values, if any, listed each chunk id. [CitationSourceKind.RERANK] has
     * no [RecallSource] counterpart and is skipped.
     */
    private fun recalledBy(sources: Map<CitationSourceKind, List<ScoredChunk>>): Map<ChunkId, Set<RecallSource>> {
        if (sources.isEmpty()) return emptyMap()
        val out = HashMap<ChunkId, MutableSet<RecallSource>>()
        for ((kind, list) in sources) {
            val recallSource = RECALL_SOURCE_BY_CITATION_KIND[kind] ?: continue
            for (scored in list) {
                out.getOrPut(scored.chunkId) { mutableSetOf() } += recallSource
            }
        }
        return out
    }

    private companion object {
        /** Built once from the single locked bridge, [RecallSource.citationSourceKind] — not re-specified here. */
        val RECALL_SOURCE_BY_CITATION_KIND: Map<CitationSourceKind, RecallSource> =
            RecallSource.entries.associateBy { it.citationSourceKind }

        /** ASCII `\r` — single UTF-8 byte, see [canonicalByteOffset]. */
        val CARRIAGE_RETURN: Byte = '\r'.code.toByte()

        /** ASCII `\n` — single UTF-8 byte, see [canonicalByteOffset]. */
        val LINE_FEED: Byte = '\n'.code.toByte()
    }
}
