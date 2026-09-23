// `IngestSteps` (skein-4uu, E5.I7; `indexLexical` added by skein-7v3, E5.I10,
// closing the skein-01ku gap). The ingest-time counterpart to `VectorRecall`
// (`app.skein.core.rag.recall`): where the recall sources turn a query into
// `IndexStore` reads, `IngestSteps` turns freshly-chunked text into
// `IndexStore` writes — `replaceChunks` (lexical; FTS5 rows follow via the
// `chunks_ai`/`chunks_ad` triggers in `001_initial.sql`) and `putEmbeddings`
// (vectors).
//
// Pure Kotlin over the `IndexStore` / `EmbedderService` interfaces from
// `:core:model` — no SQL of its own, no dependency on `:core:vault`,
// mirroring the recall sources' package shape (sibling `ingest` package
// alongside `recall`).
//
// ## `indexLexical` — the E5.I6 "defensive marker" (skein-01ku)
//
// `replaceChunks` is the whole lexical step: the FTS5 shadow rows are
// maintained by DB triggers, so there is nothing to write here beyond the
// chunk rows themselves. What this method adds is the check skein-01ku
// asked for — after the write it runs one `bm25` probe for a term taken
// from the first chunk and warns (via the injected [warn], never with
// content) when none of the freshly returned chunk ids comes back, which
// is the signature of a broken/missing `chunks_ai` trigger. The probe is
// deliberately loose (largest alphanumeric word, generous `k`) so it stays
// silent on a healthy index and only speaks up when the trigger is gone.
//
// The warning surface is an injectable `(String) -> Unit` so tests can
// capture it directly; its default is `SkeinLog.w` (skein-4je, `:core:model`)
// under [TAG], which is the call site skein-01ku asked for.
//
// ## Embedder-less mode (skein-7v3 coordinator note)
//
// The real embedder (`skein-079`) is M0-gated. [embedder] is therefore
// optional: with `null`, [indexLexical] still runs (stamping the chunk rows
// with [PENDING_EMBEDDER_ID]/[PENDING_EMBEDDER_VERSION] so the re-embed
// migration `E5.I18` can find them), [canIndexVectors] is `false`, and
// [indexVectors] refuses with an `IllegalStateException` rather than
// silently writing nothing — the pipeline checks [canIndexVectors] first
// and records "vectors pending" instead.
//
// ## `indexVectors` — skein-4uu's primary contract
//
// `embedDocuments` in batches of exactly 32 texts per call → one
// `IndexStore.putEmbeddings` call per batch, pairing each batch's
// `chunkIds` slice with the `ByteArray`s `embedDocuments` returned for it
// (same order — `EmbedderService.embedDocuments`'s kdoc guarantees "one
// [ByteArray] per text", positionally). 32 is the plan's fixed Binder-call
// batch size (`core/model/.../Embedder.kt`'s header: "batching (≤ 32 texts
// per Binder call)"), not a measurement — this class does not renegotiate
// it.

package app.skein.core.rag.ingest

import app.skein.core.model.ChunkId
import app.skein.core.model.DocId
import app.skein.core.model.EmbedderService
import app.skein.core.model.IndexStore
import app.skein.core.model.NewChunk
import app.skein.core.model.RevisionHash
import app.skein.core.model.SkeinLog
import app.skein.core.rag.chunk.Chunk

/**
 * Ingest-time indexing steps (spec §7.1 steps 2-3 as writes): [indexLexical]
 * (chunk rows → FTS via triggers) and [indexVectors] (embeddings). See the
 * file header for the embedder-less mode and the lexical probe.
 *
 * @param index backs the `replaceChunks`/`putEmbeddings` writes and the
 *   post-write `bm25` probe.
 * @param embedder backs the `embedDocuments` batch calls, or `null` while
 *   no embedder is available (vectors are then "pending").
 * @param warn content-free diagnostics sink; `SkeinLog.w` under [TAG] by default.
 */
public class IngestSteps(
    private val index: IndexStore,
    private val embedder: EmbedderService? = null,
    private val warn: (String) -> Unit = { SkeinLog.w(TAG, it) },
) {
    /** `true` when an [EmbedderService] was supplied, i.e. [indexVectors] may be called. */
    public val canIndexVectors: Boolean get() = embedder != null

    /** What [indexLexical] stamps into `chunks.embedder_id` — the real embedder's id, or [PENDING_EMBEDDER_ID]. */
    public val embedderId: String get() = embedder?.embedderId ?: PENDING_EMBEDDER_ID

    /** What [indexLexical] stamps into `chunks.embedder_version` — the real version, or [PENDING_EMBEDDER_VERSION]. */
    public val embedderVersion: Int get() = embedder?.embedderVersion ?: PENDING_EMBEDDER_VERSION

    /**
     * Replaces [docId]'s chunk rows with [chunks] (in `ord` order; `text` is
     * each chunk's [Chunk.embeddingText], so the heading breadcrumb is
     * searchable and embedded alongside the body slice) and returns the new
     * chunk ids positionally paired with [chunks].
     *
     * [revisionHash] is stamped verbatim into every row's `chunks.revision_hash`
     * (migration 003's reverse pointer, populated as of migration 008 —
     * skein-zx15) — callers pass `VaultRepository.currentRevision(docId)?.revisionHash`.
     * [body] is the exact `document.bodyMd` [chunks] were cut from
     * (`Chunk.start`/[Chunk.end] are UTF-16 char offsets into it); when
     * supplied, each row's `chunks.byte_start`/`chunks.byte_end` are the
     * matching **UTF-8 byte** offsets (skein-s9hm, folded into skein-zx15 —
     * `core/rag`'s char offsets disagree with byte offsets for any non-ASCII
     * body). Both parameters are optional and default to `null` — an empty
     * [chunks] still deletes the document's old rows either way.
     *
     * After the write, one `bm25` probe verifies the FTS5 shadow rows exist
     * (file header) and calls [warn] — without content — if they do not.
     */
    public suspend fun indexLexical(
        docId: DocId,
        chunks: List<Chunk>,
        revisionHash: RevisionHash? = null,
        body: String? = null,
    ): List<ChunkId> {
        val rows =
            chunks.map { c ->
                val offsets = body?.let { byteOffsetsUtf8(it, c.start, c.end) }
                NewChunk(
                    ord = c.ord,
                    text = c.embeddingText,
                    tokenCount = c.tokenCount,
                    byteStart = offsets?.first,
                    byteEnd = offsets?.second,
                )
            }
        val ids = index.replaceChunks(docId, rows, embedderId, embedderVersion, revisionHash)
        verifyLexicalRows(chunks, ids)
        return ids
    }

    private suspend fun verifyLexicalRows(
        chunks: List<Chunk>,
        ids: List<ChunkId>,
    ) {
        if (ids.isEmpty()) return
        val probe =
            PROBE_WORD
                .findAll(chunks.first().embeddingText)
                .map { it.value }
                .maxByOrNull { it.length }
                ?: return
        val hits = index.bm25(probe, PROBE_K).map { it.chunkId }
        if (hits.none { it in ids }) {
            warn(
                "indexLexical: bm25 probe returned none of the ${ids.size} chunk row(s) just written; " +
                    "the chunks_fts triggers may be broken",
            )
        }
    }

    /**
     * Embeds [texts] in batches of [BATCH_SIZE] (via `embedder.embedDocuments`)
     * and writes each batch's vectors with one `index.putEmbeddings` call —
     * i.e. for `n` chunks this calls `embedDocuments` `ceil(n / 32)` times
     * and `putEmbeddings` the same number of times, never once for the
     * whole set.
     *
     * [chunkIds] and [texts] must be the same size and positionally
     * paired (`chunkIds[i]` is the id of `texts[i]`) — the same convention
     * `IndexStore.replaceChunks`'s return value already establishes. An
     * empty [chunkIds] is a no-op: neither `embedDocuments` nor
     * `putEmbeddings` is called.
     *
     * @throws IllegalArgumentException if [chunkIds] and [texts] differ in size.
     * @throws IllegalStateException if no embedder was supplied ([canIndexVectors] is `false`).
     */
    public suspend fun indexVectors(
        chunkIds: List<ChunkId>,
        texts: List<String>,
    ) {
        require(chunkIds.size == texts.size) {
            "indexVectors: chunkIds.size=${chunkIds.size} != texts.size=${texts.size}"
        }
        val embedder = checkNotNull(embedder) { "indexVectors: no EmbedderService available (vectors pending)" }
        if (chunkIds.isEmpty()) return

        var start = 0
        while (start < chunkIds.size) {
            val end = minOf(start + BATCH_SIZE, chunkIds.size)
            val idBatch = chunkIds.subList(start, end)
            val textBatch = texts.subList(start, end)

            val vectors = embedder.embedDocuments(textBatch)
            index.putEmbeddings(idBatch.zip(vectors))

            start = end
        }
    }

    public companion object {
        /** `SkeinLog` tag for the default [warn] sink. */
        public const val TAG: String = "IngestSteps"

        /** `EmbedderService`'s fixed Binder-call batch size (`core/model/.../Embedder.kt`). */
        public const val BATCH_SIZE: Int = 32

        /** `chunks.embedder_id` while no embedder exists; `E5.I18` re-embeds rows carrying it. */
        public const val PENDING_EMBEDDER_ID: String = "pending"

        /** `chunks.embedder_version` while no embedder exists — below any real embedder's version (>= 1). */
        public const val PENDING_EMBEDDER_VERSION: Int = 0

        /** Alphanumeric words of three or more characters — what the FTS5 `unicode61` tokenizer indexes whole. */
        private val PROBE_WORD: Regex = Regex("[A-Za-z0-9]{3,}")

        /** Generous: the fresh chunk only has to appear somewhere in the top-k, not first. */
        private const val PROBE_K: Int = 50

        /**
         * `[charStart, charEnd)` UTF-16 char offsets into [body] → the
         * matching UTF-8 byte offsets (skein-s9hm). Encodes the `[0,
         * charStart)` prefix to get the byte start, plus the chunk's own
         * `[charStart, charEnd)` slice for its byte length — never [body]'s
         * tail past [charEnd]. Called once per chunk, so for `n` chunks
         * this is `O(n)` encodes of overlapping prefixes rather than one
         * cumulative pass; accepted for correctness-first here (chunk
         * counts per document are small — tens, not thousands) and not
         * worth the bookkeeping to make incremental unless a document with
         * enough chunks makes it measurable.
         */
        private fun byteOffsetsUtf8(
            body: String,
            charStart: Int,
            charEnd: Int,
        ): Pair<Int, Int> {
            val byteStart = body.substring(0, charStart).toByteArray(Charsets.UTF_8).size
            val byteEnd = byteStart + body.substring(charStart, charEnd).toByteArray(Charsets.UTF_8).size
            return byteStart to byteEnd
        }
    }
}
