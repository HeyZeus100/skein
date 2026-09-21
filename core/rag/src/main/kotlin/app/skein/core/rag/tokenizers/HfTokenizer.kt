package app.skein.core.rag.tokenizers

/**
 * Tokens declared in `added_tokens` are matched against the raw input before
 * normalization, so that a literal `[CLS]` in a note maps to its own id rather
 * than being decomposed by the model.
 *
 * Hugging Face keeps two tries here — one matched against the original text and
 * one against the normalized text, depending on each entry's `normalized` flag.
 * Both of our tokenizers only declare entries whose spelling is invariant under
 * their own normalizer, so a single scan over the original text is equivalent;
 * this is documented in `src/test/resources/tokenizers/README.md`.
 */
internal class AddedVocabulary(
    entries: List<AddedTokenEntry>,
) {
    private val sorted = entries.sortedWith(compareByDescending<AddedTokenEntry> { it.content.length }.thenBy { it.id })
    private val firstChars: Set<Char> = sorted.mapNotNull { it.content.firstOrNull() }.toSet()
    private val byId: Map<Int, AddedTokenEntry> = entries.associateBy { it.id }

    fun idToToken(id: Int): String? = byId[id]?.content

    fun isSpecial(id: Int): Boolean = byId[id]?.special == true

    /**
     * Walks [text], handing [sink] alternating runs: `addedId >= 0` marks a
     * literal added token, `addedId < 0` an ordinary stretch to be normalized
     * and tokenized by the model.
     */
    inline fun forEachSegment(
        text: String,
        sink: (Int, Int, Int) -> Unit,
    ) {
        if (isEmpty() || text.isEmpty()) {
            if (text.isNotEmpty()) sink(0, text.length, -1)
            return
        }
        var segmentStart = 0
        var i = 0
        while (i < text.length) {
            val hit = if (mayStartAt(text[i])) matchAt(text, i) else null
            if (hit == null) {
                i++
                continue
            }
            if (segmentStart < i) sink(segmentStart, i, -1)
            sink(i, i + hit.content.length, hit.id)
            i += hit.content.length
            segmentStart = i
        }
        if (segmentStart < text.length) sink(segmentStart, text.length, -1)
    }

    fun isEmpty(): Boolean = sorted.isEmpty()

    fun mayStartAt(c: Char): Boolean = firstChars.contains(c)

    fun matchAt(
        text: String,
        index: Int,
    ): AddedTokenEntry? {
        for (entry in sorted) {
            if (text.startsWith(entry.content, index)) return entry
        }
        return null
    }
}

/** One `added_tokens` entry. */
internal class AddedTokenEntry(
    val id: Int,
    val content: String,
    val special: Boolean,
)

/**
 * The `tokenizer.json` pipeline: added tokens, normalizer, pre-tokenizer,
 * model, post-processor, decoder.
 */
internal class HfTokenizer(
    private val normalizer: Normalizer?,
    private val preTokenizer: PreTokenizer?,
    private val model: TokenizerModel,
    private val postProcessor: TemplateProcessing?,
    private val decoder: TokenDecoder?,
    private val addedVocabulary: AddedVocabulary,
    override val maxLength: Int?,
) : Tokenizer {
    override val vocabSize: Int get() = model.vocabSize

    override fun encode(text: String): Encoding {
        val builder = EncodingBuilder(text.length / 3 + 8)
        addedVocabulary.forEachSegment(text) { start, end, addedId ->
            if (addedId >= 0) {
                builder.add(addedId, text.substring(start, end), start, end, 0)
            } else {
                encodeSegment(text, start, end, builder)
            }
        }

        val addedByTemplate = postProcessor?.addedTokens ?: 0
        val limit = maxLength
        if (limit != null) builder.truncateTo(limit - addedByTemplate)
        val processed = postProcessor?.apply(builder) ?: builder
        if (limit != null) processed.truncateTo(limit)
        return processed.build()
    }

    private fun encodeSegment(
        text: String,
        start: Int,
        end: Int,
        sink: EncodingBuilder,
    ) {
        val normalized = NormalizedString.of(text.substring(start, end), start)
        normalizer?.normalize(normalized)
        val preTokenized = PreTokenizedString(normalized)
        preTokenizer?.preTokenize(preTokenized)
        preTokenized.tokenize { model.tokenize(it) }
        preTokenized.appendTo(sink, 0)
    }

    override fun decode(
        ids: IntArray,
        skipSpecialTokens: Boolean,
    ): String {
        val pieces = ArrayList<String>(ids.size)
        for (id in ids) {
            if (skipSpecialTokens && addedVocabulary.isSpecial(id)) continue
            val token = addedVocabulary.idToToken(id) ?: model.idToToken(id) ?: continue
            pieces.add(token)
        }
        return decoder?.decode(pieces) ?: pieces.joinToString(" ")
    }

    override fun truncate(maxLen: Int): Tokenizer {
        require(maxLen > 0) { "maxLen must be positive, was $maxLen" }
        if (maxLen == maxLength) return this
        return HfTokenizer(normalizer, preTokenizer, model, postProcessor, decoder, addedVocabulary, maxLen)
    }
}
