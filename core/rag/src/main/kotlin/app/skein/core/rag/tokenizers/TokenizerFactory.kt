package app.skein.core.rag.tokenizers

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream
import java.text.Normalizer as JavaNormalizer
import java.util.regex.Pattern as JavaPattern

/**
 * Builds a [Tokenizer] from a Hugging Face `tokenizer.json`.
 *
 * The stream is read but never hashed here: per `POST_REVIEW_RESOLUTIONS` §2
 * the caller hands us a file whose SHA-256 it has already verified against the
 * model manifest, so integrity is the app layer's job and this stays a pure
 * parser.
 *
 * Only the two model types Skein's on-device models need are implemented:
 * `WordPiece` (nomic-embed-text-v1.5, ms-marco-MiniLM-L-6-v2) and `Unigram`
 * (DeBERTa-v3, i.e. GLiNER). `BPE` is deliberately out of scope for v1 — the
 * chat LLM tokenizes inside llama.cpp — and is rejected loudly.
 */
object TokenizerFactory {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parses [stream] and returns a ready tokenizer. The stream is fully
     * consumed but not closed.
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun fromJson(stream: InputStream): Tokenizer {
        val spec: TokenizerSpec =
            try {
                json.decodeFromStream(TokenizerSpec.serializer(), stream)
            } catch (e: kotlinx.serialization.SerializationException) {
                throw UnsupportedTokenizerException("Not a readable tokenizer.json: ${e.message}")
            }
        return build(spec)
    }

    internal fun build(spec: TokenizerSpec): Tokenizer {
        val model = buildModel(spec.model)
        val addedTokens =
            AddedVocabulary(spec.addedTokens.map { AddedTokenEntry(it.id, it.content, it.special) })
        return HfTokenizer(
            normalizer = buildNormalizer(spec.normalizer),
            preTokenizer = buildPreTokenizer(spec.preTokenizer),
            model = model,
            postProcessor = buildPostProcessor(spec.postProcessor),
            decoder = buildDecoder(spec.decoder),
            addedVocabulary = addedTokens,
            maxLength = spec.truncation?.maxLength,
        )
    }

    // ------------------------------------------------------------------ model

    private fun buildModel(spec: ModelSpec): TokenizerModel =
        when (spec.type) {
            "WordPiece" -> buildWordPiece(spec)
            "Unigram" -> buildUnigram(spec)
            else ->
                throw UnsupportedTokenizerException(
                    "Unsupported tokenizer model type `${spec.type}`; " +
                        "this module implements WordPiece and Unigram only",
                )
        }

    private fun buildWordPiece(spec: ModelSpec): TokenizerModel {
        val vocabNode =
            spec.vocab?.takeUnless { it is JsonNull }?.jsonObject
                ?: throw UnsupportedTokenizerException("WordPiece model has no `vocab`")
        val vocab = HashMap<String, Int>(vocabNode.size * 2)
        var maxId = -1
        for ((token, idNode) in vocabNode) {
            val id = idNode.jsonPrimitive.int
            vocab[token] = id
            if (id > maxId) maxId = id
        }
        val reverse = arrayOfNulls<String>(maxId + 1)
        for ((token, id) in vocab) reverse[id] = token
        return WordPieceModel(
            vocab = vocab,
            reverseVocab = reverse,
            unkToken = spec.unkToken ?: "[UNK]",
            continuingSubwordPrefix = spec.continuingSubwordPrefix ?: "##",
            maxInputCharsPerWord = spec.maxInputCharsPerWord,
        )
    }

    private fun buildUnigram(spec: ModelSpec): TokenizerModel {
        val vocabNode =
            spec.vocab?.takeUnless { it is JsonNull }?.jsonArray
                ?: throw UnsupportedTokenizerException("Unigram model has no `vocab`")
        val tokens = arrayOfNulls<String>(vocabNode.size)
        val scores = DoubleArray(vocabNode.size)
        val byToken = HashMap<String, Int>(vocabNode.size * 2)
        for (i in vocabNode.indices) {
            val entry = vocabNode[i].jsonArray
            if (entry.size != 2) {
                throw UnsupportedTokenizerException("Unigram vocab entry $i is not a [token, score] pair")
            }
            val token = entry[0].jsonPrimitive.content
            tokens[i] = token
            scores[i] = entry[1].jsonPrimitive.double
            byToken.putIfAbsent(token, i)
        }
        @Suppress("UNCHECKED_CAST")
        return UnigramModel(
            tokens = tokens as Array<String>,
            scores = scores,
            tokenToIds = byToken,
            unkId = spec.unkId,
            byteFallback = spec.byteFallback,
        )
    }

    // ------------------------------------------------------------- normalizer

    internal fun buildNormalizer(node: JsonElement?): Normalizer? {
        val obj = node.asObjectOrNull() ?: return null
        return when (val type = obj.typeTag()) {
            "Sequence" ->
                SequenceNormalizer(obj["normalizers"]?.jsonArray.orEmpty().mapNotNull { buildNormalizer(it) })
            "BertNormalizer" ->
                BertNormalizer(
                    cleanText = obj.bool("clean_text") ?: true,
                    handleChineseChars = obj.bool("handle_chinese_chars") ?: true,
                    stripAccents = obj.bool("strip_accents"),
                    lowercase = obj.bool("lowercase") ?: true,
                )
            "NFC" -> UnicodeNormalizer(JavaNormalizer.Form.NFC)
            "NFD" -> UnicodeNormalizer(JavaNormalizer.Form.NFD)
            "NFKC" -> UnicodeNormalizer(JavaNormalizer.Form.NFKC)
            "NFKD" -> UnicodeNormalizer(JavaNormalizer.Form.NFKD)
            "Lowercase" -> LowercaseNormalizer
            "StripAccents" -> StripAccentsNormalizer
            "Strip" ->
                StripNormalizer(obj.bool("strip_left") ?: true, obj.bool("strip_right") ?: true)
            "Replace" -> buildReplace(obj)
            "Prepend" -> PrependNormalizer(obj.string("prepend") ?: "")
            else -> throw UnsupportedTokenizerException("Unsupported normalizer type `$type`")
        }
    }

    private fun buildReplace(obj: JsonObject): Normalizer {
        val pattern =
            obj["pattern"]?.asObjectOrNull()
                ?: throw UnsupportedTokenizerException("Replace normalizer has no `pattern`")
        val regex =
            when {
                pattern.containsKey("Regex") ->
                    JavaPattern.compile(pattern["Regex"]!!.jsonPrimitive.content)
                pattern.containsKey("String") ->
                    JavaPattern.compile(JavaPattern.quote(pattern["String"]!!.jsonPrimitive.content))
                else -> throw UnsupportedTokenizerException("Replace pattern must be `Regex` or `String`")
            }
        return ReplaceNormalizer(regex, obj.string("content") ?: "")
    }

    // ---------------------------------------------------------- pre-tokenizer

    internal fun buildPreTokenizer(node: JsonElement?): PreTokenizer? {
        val obj = node.asObjectOrNull() ?: return null
        return when (val type = obj.typeTag()) {
            "Sequence" ->
                SequencePreTokenizer(obj["pretokenizers"]?.jsonArray.orEmpty().mapNotNull { buildPreTokenizer(it) })
            "BertPreTokenizer" -> BertPreTokenizer
            "WhitespaceSplit" -> WhitespaceSplitPreTokenizer
            "Punctuation" -> PunctuationPreTokenizer
            "Metaspace" ->
                MetaspacePreTokenizer(
                    replacement = (obj.string("replacement") ?: "▁").first(),
                    prependScheme = prependScheme(obj),
                    splitOnReplacement = obj.bool("split") ?: true,
                )
            else -> throw UnsupportedTokenizerException("Unsupported pre-tokenizer type `$type`")
        }
    }

    private fun prependScheme(obj: JsonObject): PrependScheme {
        if (obj.bool("add_prefix_space") == false) return PrependScheme.NEVER
        return when (obj.string("prepend_scheme")) {
            "never" -> PrependScheme.NEVER
            "first" -> PrependScheme.FIRST
            else -> PrependScheme.ALWAYS
        }
    }

    // ---------------------------------------------------------- post-processor

    internal fun buildPostProcessor(node: JsonElement?): TemplateProcessing? {
        val obj = node.asObjectOrNull() ?: return null
        return when (val type = obj.typeTag()) {
            "TemplateProcessing" -> buildTemplate(obj)
            "BertProcessing" -> buildBertProcessing(obj)
            else -> throw UnsupportedTokenizerException("Unsupported post-processor type `$type`")
        }
    }

    private fun buildTemplate(obj: JsonObject): TemplateProcessing {
        val single =
            obj["single"]?.jsonArray.orEmpty().mapNotNull { entry ->
                val piece = entry.asObjectOrNull() ?: return@mapNotNull null
                val special = piece["SpecialToken"]?.asObjectOrNull()
                val sequence = piece["Sequence"]?.asObjectOrNull()
                when {
                    special != null ->
                        TemplatePiece.SpecialToken(
                            special.string("id") ?: return@mapNotNull null,
                            special.int("type_id") ?: 0,
                        )
                    sequence != null ->
                        TemplatePiece.Sequence(
                            sequence.string("id") ?: "A",
                            sequence.int("type_id") ?: 0,
                        )
                    else -> null
                }
            }
        val specialTokens = HashMap<String, SpecialTokenExpansion>()
        for ((key, value) in obj["special_tokens"]?.asObjectOrNull().orEmpty()) {
            val entry = value.asObjectOrNull() ?: continue
            val ids =
                entry["ids"]
                    ?.jsonArray
                    .orEmpty()
                    .map { it.jsonPrimitive.int }
                    .toIntArray()
            val tokens = entry["tokens"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
            specialTokens[key] = SpecialTokenExpansion(ids, tokens)
        }
        return TemplateProcessing(single, specialTokens)
    }

    private fun buildBertProcessing(obj: JsonObject): TemplateProcessing {
        val sep = obj["sep"]?.jsonArray ?: throw UnsupportedTokenizerException("BertProcessing has no `sep`")
        val cls = obj["cls"]?.jsonArray ?: throw UnsupportedTokenizerException("BertProcessing has no `cls`")
        val clsToken = cls[0].jsonPrimitive.content
        val sepToken = sep[0].jsonPrimitive.content
        val specialTokens =
            mapOf(
                clsToken to SpecialTokenExpansion(intArrayOf(cls[1].jsonPrimitive.int), listOf(clsToken)),
                sepToken to SpecialTokenExpansion(intArrayOf(sep[1].jsonPrimitive.int), listOf(sepToken)),
            )
        return TemplateProcessing(
            listOf(
                TemplatePiece.SpecialToken(clsToken, 0),
                TemplatePiece.Sequence("A", 0),
                TemplatePiece.SpecialToken(sepToken, 0),
            ),
            specialTokens,
        )
    }

    // ----------------------------------------------------------------- decoder

    private fun buildDecoder(node: JsonElement?): TokenDecoder? {
        val obj = node.asObjectOrNull() ?: return null
        // Unlike the id-affecting stages, an unrecognised decoder degrades to a
        // plain join rather than failing the load: `decode` is a convenience for
        // debugging and export, not part of the embedding path.
        return when (obj.typeTag()) {
            "WordPiece" ->
                WordPieceDecoder(obj.string("prefix") ?: "##", obj.bool("cleanup") ?: true)
            "Metaspace" ->
                MetaspaceDecoder((obj.string("replacement") ?: "▁").first(), prependScheme(obj))
            else -> null
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun JsonElement?.asObjectOrNull(): JsonObject? =
        if (this == null || this is JsonNull) null else this as? JsonObject

    private fun JsonObject.typeTag(): String? = this["type"]?.jsonPrimitive?.content

    private fun JsonObject.string(key: String): String? =
        this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private fun JsonObject.bool(key: String): Boolean? =
        this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.booleanOrNull

    private fun JsonObject.int(key: String): Int? = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.int

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()
}
