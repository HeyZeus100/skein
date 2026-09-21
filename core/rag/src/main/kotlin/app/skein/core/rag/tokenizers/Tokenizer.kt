package app.skein.core.rag.tokenizers

/**
 * The result of tokenizing one piece of text.
 *
 * All arrays are parallel and have [size] entries. [offsets] is flat and holds
 * `2 * size` entries — `[start0, end0, start1, end1, ...]`.
 *
 * Offsets are **UTF-16 code-unit indices into the original input string**, so
 * `text.substring(startOf(i), endOf(i))` yields the exact slice of the input a
 * token came from (see [surfaceOf]). Special tokens added by post-processing
 * (`[CLS]` / `[SEP]`) carry the empty offset `(0, 0)`, matching Hugging Face
 * `tokenizers`.
 *
 * Note that Python `tokenizers` reports offsets in *code point* indices; the
 * golden fixtures under `src/test/resources/tokenizers/golden/` are converted to
 * UTF-16 indices at generation time (see that directory's `README.md`) so that
 * they can be compared against this class without any conversion in the test.
 */
class Encoding internal constructor(
    val ids: IntArray,
    val tokens: List<String>,
    val offsets: IntArray,
    val typeIds: IntArray,
    val attentionMask: IntArray,
) {
    init {
        require(offsets.size == 2 * ids.size) { "offsets must hold 2 entries per token" }
    }

    /** Number of tokens, including any special tokens added by post-processing. */
    val size: Int get() = ids.size

    /** Start of token [index] as a UTF-16 index into the encoded text. */
    fun startOf(index: Int): Int = offsets[2 * index]

    /** End (exclusive) of token [index] as a UTF-16 index into the encoded text. */
    fun endOf(index: Int): Int = offsets[2 * index + 1]

    /**
     * The slice of [text] that token [index] covers. [text] must be the string
     * that produced this encoding.
     */
    fun surfaceOf(
        text: String,
        index: Int,
    ): String = text.substring(startOf(index), endOf(index))

    override fun toString(): String = "Encoding(size=$size, tokens=$tokens)"
}

/**
 * A Hugging Face `tokenizer.json`-compatible tokenizer.
 *
 * Implementations are immutable and safe to share between threads. Build one
 * with [TokenizerFactory.fromJson].
 */
interface Tokenizer {
    /** Size of the model vocabulary, excluding any added tokens not in it. */
    val vocabSize: Int

    /**
     * The maximum total number of tokens [encode] will emit (including the
     * special tokens added by post-processing), or `null` when untruncated.
     */
    val maxLength: Int?

    /** Tokenize [text], applying normalization, pre-tokenization and post-processing. */
    fun encode(text: String): Encoding

    /**
     * Reconstruct text from [ids]. Unknown ids are skipped. When
     * [skipSpecialTokens] is true (the default) the tokenizer's special tokens
     * (`[CLS]`, `[SEP]`, `[PAD]`, ...) are dropped.
     */
    fun decode(
        ids: IntArray,
        skipSpecialTokens: Boolean = true,
    ): String

    /**
     * The number of tokens [encode] would emit for [text]. This is what the
     * chunker (bd skein-92u) budgets against, so it deliberately goes through
     * the very same pipeline rather than approximating.
     */
    fun countTokens(text: String): Int = encode(text).size

    /**
     * A view of this tokenizer that truncates every encoding to at most
     * [maxLen] tokens *including* the special tokens added by post-processing —
     * the same accounting Hugging Face's `enable_truncation(max_length=…)` uses.
     */
    fun truncate(maxLen: Int): Tokenizer
}

/** Thrown when a `tokenizer.json` cannot be read or describes an unsupported pipeline. */
class UnsupportedTokenizerException(
    message: String,
) : IllegalArgumentException(message)
