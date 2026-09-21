package app.skein.core.rag.tokenizers

/** Accumulates tokens while an input is encoded, before post-processing. */
internal class EncodingBuilder(
    initialCapacity: Int = 32,
) {
    val ids = IntList(initialCapacity)
    val tokens = ArrayList<String>(initialCapacity)
    val offsets = IntList(initialCapacity * 2)
    val typeIds = IntList(initialCapacity)

    val size: Int get() = tokens.size

    fun add(
        id: Int,
        token: String,
        start: Int,
        end: Int,
        typeId: Int,
    ) {
        ids.add(id)
        tokens.add(token)
        offsets.add(start)
        offsets.add(end)
        typeIds.add(typeId)
    }

    /** Drops everything past [maxTokens] (right truncation — the only direction we need). */
    fun truncateTo(maxTokens: Int) {
        val kept = maxOf(maxTokens, 0)
        if (size <= kept) return
        ids.shrinkTo(kept)
        offsets.shrinkTo(kept * 2)
        typeIds.shrinkTo(kept)
        while (tokens.size > kept) tokens.removeAt(tokens.size - 1)
    }

    fun build(): Encoding =
        Encoding(
            ids = ids.toIntArray(),
            tokens = ArrayList(tokens),
            offsets = offsets.toIntArray(),
            typeIds = typeIds.toIntArray(),
            attentionMask = IntArray(size) { 1 },
        )
}

/** One entry of a `TemplateProcessing` template. */
internal sealed interface TemplatePiece {
    val typeId: Int

    class SpecialToken(
        val id: String,
        override val typeId: Int,
    ) : TemplatePiece

    class Sequence(
        val id: String,
        override val typeId: Int,
    ) : TemplatePiece
}

/** A `special_tokens` entry: the token strings and ids one template slot expands to. */
internal class SpecialTokenExpansion(
    val ids: IntArray,
    val tokens: List<String>,
)

/**
 * `{"type": "TemplateProcessing"}` — wraps a sequence in whatever special
 * tokens the checkpoint expects (`[CLS] $A [SEP]` for both of ours).
 */
internal class TemplateProcessing(
    private val single: List<TemplatePiece>,
    private val specialTokens: Map<String, SpecialTokenExpansion>,
) {
    /** Number of tokens this template adds to a single sequence. */
    val addedTokens: Int =
        single.sumOf { piece ->
            if (piece is TemplatePiece.SpecialToken) specialTokens[piece.id]?.ids?.size ?: 0 else 0
        }

    fun apply(sequence: EncodingBuilder): EncodingBuilder {
        val out = EncodingBuilder(sequence.size + addedTokens)
        for (piece in single) {
            when (piece) {
                is TemplatePiece.SpecialToken -> {
                    val expansion = specialTokens[piece.id] ?: continue
                    for (i in expansion.ids.indices) {
                        out.add(expansion.ids[i], expansion.tokens[i], 0, 0, piece.typeId)
                    }
                }
                is TemplatePiece.Sequence -> {
                    val ids = sequence.ids
                    val offsets = sequence.offsets
                    for (i in 0 until sequence.size) {
                        out.add(
                            ids.data[i],
                            sequence.tokens[i],
                            offsets.data[2 * i],
                            offsets.data[2 * i + 1],
                            piece.typeId,
                        )
                    }
                }
            }
        }
        return out
    }
}

/** The `decoder` node of a `tokenizer.json`. */
internal fun interface TokenDecoder {
    fun decode(tokens: List<String>): String
}

/** `{"type": "WordPiece", "prefix": "##", "cleanup": true}`. */
internal class WordPieceDecoder(
    private val prefix: String,
    private val cleanup: Boolean,
) : TokenDecoder {
    override fun decode(tokens: List<String>): String {
        val out = StringBuilder()
        for ((i, raw) in tokens.withIndex()) {
            var token = raw
            if (i != 0) {
                token = if (token.startsWith(prefix)) token.removePrefix(prefix) else " $token"
            }
            if (cleanup) token = cleanupArtifacts(token)
            out.append(token)
        }
        return out.toString()
    }

    private fun cleanupArtifacts(input: String): String =
        input
            .replace(" .", ".")
            .replace(" ?", "?")
            .replace(" !", "!")
            .replace(" ,", ",")
            .replace(" ' ", "'")
            .replace(" n't", "n't")
            .replace(" 'm", "'m")
            .replace(" do not", " don't")
            .replace(" 's", "'s")
            .replace(" 've", "'ve")
            .replace(" 're", "'re")
}

/** `{"type": "Metaspace", "replacement": "▁", ...}`. */
internal class MetaspaceDecoder(
    private val replacement: Char,
    private val prependScheme: PrependScheme,
) : TokenDecoder {
    override fun decode(tokens: List<String>): String {
        val out = StringBuilder()
        for ((i, token) in tokens.withIndex()) {
            for (c in token) {
                if (c == replacement) {
                    if (i == 0 && prependScheme != PrependScheme.NEVER) continue
                    out.append(' ')
                } else {
                    out.append(c)
                }
            }
        }
        return out.toString()
    }
}
