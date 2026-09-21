// `Chunk` (bd skein-92u, E5.I5). The chunker's own output type — deliberately
// distinct from `us.aherrera.skein.core.model.Chunk`/`NewChunk` (the
// persisted-row shapes `IndexStore.replaceChunks` speaks in), because those
// types are plan-locked (`core/model/.../Vault.kt`, not to be edited here)
// and carry no offset/heading-path metadata. `IngestSteps` (skein-4uu/7v3)
// is expected to map this to a `NewChunk(ord, text = embeddingText,
// tokenCount)` at insert time, and to derive `Locator.byteStart`/`byteEnd`
// (`docs/design/POST_REVIEW_RESOLUTIONS.md` §1.3) from [Chunk.start]/[Chunk.end].

package app.skein.core.rag.chunk

/**
 * One packed chunk of a document body, produced by [Chunker].
 *
 * [text] is always exactly `bodyMd.substring(start, end)` — see
 * [Chunker.chunk]'s offset contract. [headingBreadcrumb] is the
 * `"# Title › ## Section"` breadcrumb (bd skein-92u) for the heading path
 * active where this chunk begins, or `null` when the chunk starts before
 * any heading in the document. The breadcrumb is metadata carried
 * alongside [text], not part of it — [embeddingText] is the string a
 * caller should actually tokenize/embed, and [tokenCount] already reflects
 * that combined string (breadcrumb included) so packing budgets its
 * overhead correctly.
 */
public data class Chunk(
    /** Sequential from 0 in document order. */
    val ord: Int,
    val headingBreadcrumb: String?,
    val text: String,
    /** `[start, end)` char offsets into the `bodyMd` this chunk was cut from. */
    val start: Int,
    val end: Int,
    /** Token count of [embeddingText] (breadcrumb + [text]), against which packing was budgeted. */
    val tokenCount: Int,
) {
    /** [headingBreadcrumb] followed by a blank line and [text] — what to hand the embedder. */
    public val embeddingText: String
        get() = if (headingBreadcrumb == null) text else "$headingBreadcrumb\n\n$text"
}
