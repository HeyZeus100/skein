// `IngestSteps` (skein-4uu, E5.I7). The ingest-time counterpart to
// `VectorRecall` (`app.skein.core.rag.recall`, same bead): where
// `VectorRecall` turns a query into `IndexStore.knn` hits, `IngestSteps`
// turns freshly-chunked text into `IndexStore.putEmbeddings` writes.
//
// Pure Kotlin over the `IndexStore` / `EmbedderService` interfaces from
// `:core:model` — no SQL of its own, no dependency on `:core:vault`,
// mirroring the recall sources' package shape (sibling `ingest` package
// alongside `recall`).
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
//
// ## `indexLexical` — skein-01ku gap, deliberately NOT added here
//
// skein-01ku (E5.I6 gap) asks for an `IngestSteps.indexLexical` "defensive
// marker" that verifies FTS5 rows exist after `replaceChunks`, "surfacing
// `SkeinLog.w` if triggers break" (plan §, `2026-09-19-skein-v1-plan.md`
// line 3376, verbatim). Two things block doing that *as worded* here:
//   1. `SkeinLog` does not exist yet — it is a separate, not-yet-landed
//      bead (skein-4je, E1.I11) whose file the plan places in
//      `core/model` (`docs/superpowers/plans/2026-09-19-skein-v1-plan.md`
//      line 94: "core/model/ ... Int8Quantizer, SkeinLog"). skein-4uu's
//      non-negotiables bar editing `core/model`, so this class cannot add
//      a stand-in `SkeinLog.w` call site without either (a) inventing a
//      throwaway logging facade that will need to be torn out and
//      rewired the moment skein-4je lands, or (b) editing the barred
//      module. Neither is a defensible "one-liner".
//   2. The check itself — "FTS rows exist after `replaceChunks`" — has no
//      surface on the `IndexStore` contract to observe directly (no
//      "row count" or "raw FTS probe" method); the only indirect proxy
//      available (running `IndexStore.bm25` against a term drawn from the
//      chunk's own text and checking the chunk id comes back) is a design
//      decision 01ku's own wording doesn't specify, and guessing at it
//      risks landing something that has to be reworked to match the real
//      spec once 01ku is actually picked up.
// So skein-01ku is left OPEN rather than closed opportunistically — see
// skein-4uu's close reason for the same note.

package app.skein.core.rag.ingest

import us.aherrera.skein.core.model.ChunkId
import us.aherrera.skein.core.model.EmbedderService
import us.aherrera.skein.core.model.IndexStore

/**
 * Ingest-time indexing steps (spec ingest pipeline). See file header for
 * scope — currently just `indexVectors`; `indexLexical` is intentionally
 * not present (see file header, skein-01ku).
 *
 * @param index backs the `putEmbeddings` write.
 * @param embedder backs the `embedDocuments` batch calls.
 */
public class IngestSteps(
    private val index: IndexStore,
    private val embedder: EmbedderService,
) {
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
     */
    public suspend fun indexVectors(
        chunkIds: List<ChunkId>,
        texts: List<String>,
    ) {
        require(chunkIds.size == texts.size) {
            "indexVectors: chunkIds.size=${chunkIds.size} != texts.size=${texts.size}"
        }
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
        /** `EmbedderService`'s fixed Binder-call batch size (`core/model/.../Embedder.kt`). */
        public const val BATCH_SIZE: Int = 32
    }
}
