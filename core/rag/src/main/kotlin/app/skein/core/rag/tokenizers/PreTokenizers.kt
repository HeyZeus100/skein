package app.skein.core.rag.tokenizers

/** A single token produced by a model, with offsets relative to its split's normalized text. */
internal class Token(
    val id: Int,
    val value: String,
    val start: Int,
    val end: Int,
)

/** A sub-part of the input, plus the tokens the model produced for it. */
internal class Split(
    val normalized: NormalizedString,
) {
    var tokens: List<Token>? = null
}

/**
 * Port of `PreTokenizedString`: holds the splits of one input and knows how to
 * turn them back into offsets relative to the original string.
 */
internal class PreTokenizedString(
    normalized: NormalizedString,
) {
    var splits: MutableList<Split> = mutableListOf(Split(normalized))
        private set

    fun split(splitFn: (NormalizedString) -> List<NormalizedString>) {
        val out = ArrayList<Split>(splits.size)
        for (split in splits) {
            if (split.tokens != null) {
                out.add(split)
                continue
            }
            for (piece in splitFn(split.normalized)) {
                if (!piece.isEmpty()) out.add(Split(piece))
            }
        }
        splits = out
    }

    fun tokenize(tokenizeFn: (NormalizedString) -> List<Token>) {
        for (split in splits) {
            if (split.tokens == null) split.tokens = tokenizeFn(split.normalized)
        }
    }

    /**
     * Port of `into_encoding`: re-expresses every token's offsets in the
     * coordinates of the outermost original string.
     */
    fun appendTo(
        sink: EncodingBuilder,
        typeId: Int,
    ) {
        for (split in splits) {
            val normalized = split.normalized
            val shift = normalized.originalStart()
            for (token in split.tokens.orEmpty()) {
                val converted = normalized.convertOffsetsToOriginal(token.start, token.end)
                sink.add(token.id, token.value, shift + converted[0], shift + converted[1], typeId)
            }
        }
    }
}

/** One stage of a `tokenizer.json` `pre_tokenizer` pipeline. */
internal fun interface PreTokenizer {
    fun preTokenize(input: PreTokenizedString)
}

/** `{"type": "Sequence", "pretokenizers": [...]}`. */
internal class SequencePreTokenizer(
    private val stages: List<PreTokenizer>,
) : PreTokenizer {
    override fun preTokenize(input: PreTokenizedString) {
        for (stage in stages) stage.preTokenize(input)
    }
}

/** `{"type": "BertPreTokenizer"}` — split on whitespace, then isolate punctuation. */
internal object BertPreTokenizer : PreTokenizer {
    override fun preTokenize(input: PreTokenizedString) {
        input.split { s -> s.split(findMatches(s.get()) { Unicode.isWhitespace(it) }, SplitDelimiterBehavior.REMOVED) }
        input.split { s ->
            s.split(findMatches(s.get()) { Unicode.isBertPunctuation(it) }, SplitDelimiterBehavior.ISOLATED)
        }
    }
}

/** `{"type": "Whitespace" | "WhitespaceSplit"}` — split on whitespace only. */
internal object WhitespaceSplitPreTokenizer : PreTokenizer {
    override fun preTokenize(input: PreTokenizedString) {
        input.split { s -> s.split(findMatches(s.get()) { Unicode.isWhitespace(it) }, SplitDelimiterBehavior.REMOVED) }
    }
}

/** `{"type": "Punctuation"}`. */
internal object PunctuationPreTokenizer : PreTokenizer {
    override fun preTokenize(input: PreTokenizedString) {
        input.split { s ->
            s.split(findMatches(s.get()) { Unicode.isBertPunctuation(it) }, SplitDelimiterBehavior.ISOLATED)
        }
    }
}

/** How a [MetaspacePreTokenizer] decides whether to prefix the replacement character. */
internal enum class PrependScheme { FIRST, NEVER, ALWAYS }

/**
 * `{"type": "Metaspace", "replacement": "▁", "prepend_scheme": ..., "split": ...}`
 * — the SentencePiece-style pre-tokenizer DeBERTa-v3 (and therefore GLiNER) uses.
 */
internal class MetaspacePreTokenizer(
    private val replacement: Char,
    private val prependScheme: PrependScheme,
    private val splitOnReplacement: Boolean,
) : PreTokenizer {
    private val replacementString = replacement.toString()

    override fun preTokenize(input: PreTokenizedString) {
        input.split { s ->
            s.replace(findMatches(s.get()) { it == ' '.code }, replacementString)
            when (prependScheme) {
                PrependScheme.ALWAYS ->
                    if (!s.get().startsWith(replacement)) s.prepend(replacementString)
                PrependScheme.FIRST ->
                    if (!s.get().startsWith(replacement) && s.originalStart() == 0) s.prepend(replacementString)
                PrependScheme.NEVER -> Unit
            }
            if (splitOnReplacement) {
                s.split(
                    findMatches(s.get()) { it == replacement.code },
                    SplitDelimiterBehavior.MERGED_WITH_NEXT,
                )
            } else {
                listOf(s)
            }
        }
    }
}
