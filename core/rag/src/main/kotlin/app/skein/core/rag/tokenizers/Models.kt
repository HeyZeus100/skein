package app.skein.core.rag.tokenizers

/** The `model` node of a `tokenizer.json`. */
internal interface TokenizerModel {
    val vocabSize: Int

    fun tokenize(normalized: NormalizedString): List<Token>

    fun idToToken(id: Int): String?

    fun tokenToId(token: String): Int?
}

/**
 * `{"type": "WordPiece"}` — longest-match-first greedy segmentation with a
 * `##` continuation prefix, as used by every BERT-uncased checkpoint
 * (nomic-embed-text-v1.5 and ms-marco-MiniLM-L-6-v2 here).
 */
internal class WordPieceModel(
    private val vocab: HashMap<String, Int>,
    private val reverseVocab: Array<String?>,
    private val unkToken: String,
    private val continuingSubwordPrefix: String,
    private val maxInputCharsPerWord: Int,
) : TokenizerModel {
    private val unkId: Int =
        vocab[unkToken] ?: throw UnsupportedTokenizerException("WordPiece vocab has no `$unkToken` token")

    override val vocabSize: Int get() = vocab.size

    override fun idToToken(id: Int): String? = reverseVocab.getOrNull(id)

    override fun tokenToId(token: String): Int? = vocab[token]

    override fun tokenize(normalized: NormalizedString): List<Token> {
        val text = normalized.get()
        if (text.codePointCount(0, text.length) > maxInputCharsPerWord) {
            return listOf(Token(unkId, unkToken, 0, text.length))
        }

        val out = ArrayList<Token>(4)
        val scratch = StringBuilder(text.length + continuingSubwordPrefix.length)
        var start = 0
        while (start < text.length) {
            var end = text.length
            var found: Token? = null
            while (start < end) {
                scratch.setLength(0)
                if (start > 0) scratch.append(continuingSubwordPrefix)
                scratch.append(text, start, end)
                val candidate = scratch.toString()
                val id = vocab[candidate]
                if (id != null) {
                    found = Token(id, candidate, start, end)
                    break
                }
                end -= Character.charCount(text.codePointBefore(end))
            }
            val token = found ?: return listOf(Token(unkId, unkToken, 0, text.length))
            out.add(token)
            start = token.end
        }
        return out
    }
}

/**
 * `{"type": "Unigram"}` — SentencePiece's unigram language model, decoded with
 * Viterbi over the vocabulary's log-probabilities. This is the model behind
 * DeBERTa-v3, and therefore behind GLiNER's span extraction.
 */
internal class UnigramModel(
    private val tokens: Array<String>,
    private val scores: DoubleArray,
    private val tokenToIds: HashMap<String, Int>,
    private val unkId: Int?,
    private val byteFallback: Boolean,
) : TokenizerModel {
    private val prefixIndex = PrefixIndex(tokens)
    private val unkScore: Double = (scores.minOrNull() ?: 0.0) - UNK_PENALTY

    override val vocabSize: Int get() = tokens.size

    override fun idToToken(id: Int): String? = tokens.getOrNull(id)

    override fun tokenToId(token: String): Int? = tokenToIds[token]

    override fun tokenize(normalized: NormalizedString): List<Token> {
        val text = normalized.get()
        if (text.isEmpty()) return emptyList()

        val pieces = viterbi(text)
        val out = ArrayList<Token>(pieces.size)
        var offset = 0
        for (piece in pieces) {
            val length = piece.length
            val id = tokenToIds[piece]
            if (id == null && byteFallback) {
                val bytes = piece.toByteArray(Charsets.UTF_8)
                val byteIds = IntArray(bytes.size)
                var resolved = true
                for (i in bytes.indices) {
                    val name = "<0x%02X>".format(bytes[i].toInt() and 0xFF)
                    val byteId = tokenToIds[name]
                    if (byteId == null) {
                        resolved = false
                        break
                    }
                    byteIds[i] = byteId
                }
                if (resolved) {
                    for (i in bytes.indices) {
                        val name = "<0x%02X>".format(bytes[i].toInt() and 0xFF)
                        out.add(Token(byteIds[i], name, offset, offset + length))
                    }
                    offset += length
                    continue
                }
            }
            val resolvedId =
                id ?: unkId
                    ?: throw UnsupportedTokenizerException("Unigram model has no `unk_id` but hit an unknown piece")
            out.add(Token(resolvedId, piece, offset, offset + length))
            offset += length
        }
        return out
    }

    /**
     * Port of `encode_optimized`: a forward Viterbi pass keeping, for every
     * position, the best-scoring path that ends there, then a backward pass
     * that fuses runs of unknown pieces into one (`fuse_unk` is always on for
     * a `tokenizer.json`-loaded Unigram).
     */
    private fun viterbi(text: String): List<String> {
        val size = text.length
        val bestScore = DoubleArray(size + 1)
        val bestStartsAt = IntArray(size + 1) { -1 }
        val bestId = IntArray(size + 1)

        var startsAt = 0
        while (startsAt < size) {
            val scoreTillHere = bestScore[startsAt]
            val width = Character.charCount(text.codePointAt(startsAt))
            var hasSingleNode = false
            prefixIndex.forEachPrefix(text, startsAt) { length, id ->
                val keyPos = startsAt + length
                val candidate = scores[id] + scoreTillHere
                if (bestStartsAt[keyPos] < 0 || candidate > bestScore[keyPos]) {
                    bestScore[keyPos] = candidate
                    bestStartsAt[keyPos] = startsAt
                    bestId[keyPos] = id
                }
                if (!hasSingleNode && length == width) hasSingleNode = true
            }
            if (!hasSingleNode) {
                val keyPos = startsAt + width
                val candidate = unkScore + scoreTillHere
                if (bestStartsAt[keyPos] < 0 || candidate > bestScore[keyPos]) {
                    bestScore[keyPos] = candidate
                    bestStartsAt[keyPos] = startsAt
                    bestId[keyPos] = unkId
                        ?: throw UnsupportedTokenizerException("Unigram model has no `unk_id` but hit an unknown piece")
                }
            }
            startsAt += width
        }

        val results = ArrayList<String>()
        val pending = ArrayList<String>()
        var endsAt = size
        while (endsAt > 0) {
            val from = bestStartsAt[endsAt]
            if (from < 0) break
            if (unkId != null && bestId[endsAt] == unkId) {
                pending.add(text.substring(from, endsAt))
            } else {
                if (pending.isNotEmpty()) {
                    pending.reverse()
                    results.add(pending.joinToString(""))
                    pending.clear()
                }
                results.add(text.substring(from, endsAt))
            }
            endsAt = from
        }
        if (pending.isNotEmpty()) {
            pending.reverse()
            results.add(pending.joinToString(""))
        }
        results.reverse()
        return results
    }

    private companion object {
        /** SentencePiece's `kUnkPenalty`, mirrored by `tokenizers`. */
        const val UNK_PENALTY = 10.0
    }
}

/**
 * Common-prefix search over the vocabulary, the operation Viterbi needs at
 * every position.
 *
 * Rather than materialising a trie (which for DeBERTa-v3's 128 000-entry
 * vocabulary costs several megabytes of nodes on a phone), this keeps the
 * vocabulary indices sorted lexicographically and descends one UTF-16 unit at a
 * time, narrowing a `[lo, hi)` window by binary search. Allocation-free per
 * query.
 */
internal class PrefixIndex(
    private val tokens: Array<String>,
) {
    private val order: IntArray =
        tokens.indices.sortedWith(compareBy { tokens[it] }).toIntArray()

    /**
     * Calls [sink] with `(length, tokenIndex)` for every vocabulary entry that
     * is a prefix of [text] starting at [from].
     */
    inline fun forEachPrefix(
        text: String,
        from: Int,
        sink: (Int, Int) -> Unit,
    ) {
        var lo = 0
        var hi = size()
        var depth = 0
        while (lo < hi) {
            val candidate = tokenAt(lo)
            if (candidate.length == depth) {
                if (depth > 0) sink(depth, indexAt(lo))
                lo++
                if (lo >= hi) return
            }
            val position = from + depth
            if (position >= text.length) return
            val target = text[position]
            val newLo = lowerBound(lo, hi, depth, target)
            val newHi = upperBound(newLo, hi, depth, target)
            lo = newLo
            hi = newHi
            depth++
        }
    }

    fun size(): Int = order.size

    fun indexAt(slot: Int): Int = order[slot]

    fun tokenAt(slot: Int): String = tokens[order[slot]]

    /** First slot in `[lo, hi)` whose token has `token[depth] >= target`. */
    fun lowerBound(
        lo: Int,
        hi: Int,
        depth: Int,
        target: Char,
    ): Int {
        var low = lo
        var high = hi
        while (low < high) {
            val mid = (low + high) ushr 1
            if (tokenAt(mid)[depth] < target) low = mid + 1 else high = mid
        }
        return low
    }

    /** First slot in `[lo, hi)` whose token has `token[depth] > target`. */
    fun upperBound(
        lo: Int,
        hi: Int,
        depth: Int,
        target: Char,
    ): Int {
        var low = lo
        var high = hi
        while (low < high) {
            val mid = (low + high) ushr 1
            if (tokenAt(mid)[depth] <= target) low = mid + 1 else high = mid
        }
        return low
    }
}
