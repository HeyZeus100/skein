// `ApproximateTokenizer` (bd skein-7v3, E5.I10). A stand-in `Tokenizer` for
// the `Chunker` while no embedder — and therefore no embedder `tokenizer.json`
// — is available on the device (the nomic backend is skein-079, M0-gated).
//
// It is NOT a vocabulary tokenizer: it has no ids worth decoding and no
// special tokens. What it does provide is the two things the chunker's
// packing needs — an offset-exact `Encoding` over the input and a token
// count that tracks a WordPiece tokenizer closely enough to keep chunks
// near the ~512-token target: each alphanumeric word contributes
// `ceil(length / CHARS_PER_PIECE)` pieces (long words split into subwords,
// short words are one token), and every other non-space character is one
// token. Once skein-079 lands, `:app` swaps in `TokenizerFactory.fromJson`
// over the model's own `tokenizer.json`; the re-embed pass (`E5.I18`) then
// re-chunks every document against the real tokenizer, so chunk boundaries
// cut with this approximation are never load-bearing for citations
// (locators are byte offsets into a revision, not chunk ids —
// `docs/design/POST_REVIEW_RESOLUTIONS.md` §1).

package app.skein.core.rag.tokenizers

import kotlin.math.ceil
import kotlin.math.min

/** See the file header. Stateless and thread-safe. */
public object ApproximateTokenizer : Tokenizer {
    override val vocabSize: Int = 0
    override val maxLength: Int? = null

    override fun encode(text: String): Encoding = encode(text, limit = Int.MAX_VALUE)

    /** Not a vocabulary tokenizer — there is no text to reconstruct from ids. */
    override fun decode(
        ids: IntArray,
        skipSpecialTokens: Boolean,
    ): String = throw UnsupportedOperationException("ApproximateTokenizer has no vocabulary to decode with")

    override fun truncate(maxLen: Int): Tokenizer {
        require(maxLen > 0) { "maxLen must be positive, was $maxLen" }
        return Truncated(maxLen)
    }

    private fun encode(
        text: String,
        limit: Int,
    ): Encoding {
        val offsets = ArrayList<Int>()
        for (match in TOKEN.findAll(text)) {
            val start = match.range.first
            val end = match.range.last + 1
            val pieces =
                if (text[start].isLetterOrDigit()) {
                    ceil((end - start).toDouble() / CHARS_PER_PIECE).toInt().coerceAtLeast(1)
                } else {
                    1
                }
            val width = (end - start + pieces - 1) / pieces
            var cursor = start
            repeat(pieces) {
                if (offsets.size / 2 >= limit) return build(offsets)
                val pieceEnd = min(cursor + width, end)
                offsets += cursor
                offsets += pieceEnd
                cursor = pieceEnd
            }
        }
        return build(offsets)
    }

    private fun build(offsets: List<Int>): Encoding {
        val size = offsets.size / 2
        return Encoding(
            ids = IntArray(size) { PLACEHOLDER_ID },
            tokens = List(size) { PLACEHOLDER_TOKEN },
            offsets = offsets.toIntArray(),
            typeIds = IntArray(size),
            attentionMask = IntArray(size) { 1 },
        )
    }

    private class Truncated(
        private val limit: Int,
    ) : Tokenizer {
        override val vocabSize: Int = 0
        override val maxLength: Int = limit

        override fun encode(text: String): Encoding = ApproximateTokenizer.encode(text, limit)

        override fun decode(
            ids: IntArray,
            skipSpecialTokens: Boolean,
        ): String = ApproximateTokenizer.decode(ids, skipSpecialTokens)

        override fun truncate(maxLen: Int): Tokenizer = ApproximateTokenizer.truncate(min(maxLen, limit))
    }

    /** A run of letters/digits (one word), or any single other non-space character. */
    private val TOKEN: Regex = Regex("""[\p{L}\p{N}]+|[^\p{L}\p{N}\s]""")

    /** WordPiece over English prose averages roughly one piece per ~5 characters of a word. */
    private const val CHARS_PER_PIECE: Int = 5
    private const val PLACEHOLDER_ID: Int = 0
    private const val PLACEHOLDER_TOKEN: String = "<piece>"
}
