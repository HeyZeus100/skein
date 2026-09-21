package app.skein.core.rag.tokenizers

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The subset of a Hugging Face `tokenizer.json` this module reads.
 *
 * Only the leaves whose *shape* is stable are typed. The polymorphic
 * `type`-tagged nodes (normalizer, pre-tokenizer, post-processor, decoder) stay
 * [JsonElement] and are interpreted in [TokenizerFactory]; they are a few
 * hundred bytes each, while the `model.vocab` that dominates the file is
 * converted straight into flat arrays and the tree released.
 */
@Serializable
internal data class TokenizerSpec(
    val version: String? = null,
    val truncation: TruncationSpec? = null,
    @SerialName("added_tokens") val addedTokens: List<AddedTokenSpec> = emptyList(),
    val normalizer: JsonElement? = null,
    @SerialName("pre_tokenizer") val preTokenizer: JsonElement? = null,
    @SerialName("post_processor") val postProcessor: JsonElement? = null,
    val decoder: JsonElement? = null,
    val model: ModelSpec,
)

@Serializable
internal data class TruncationSpec(
    @SerialName("max_length") val maxLength: Int? = null,
    val direction: String? = null,
    val strategy: String? = null,
)

@Serializable
internal data class AddedTokenSpec(
    val id: Int,
    val content: String,
    val special: Boolean = false,
    val normalized: Boolean = false,
)

@Serializable
internal data class ModelSpec(
    val type: String,
    @SerialName("unk_token") val unkToken: String? = null,
    @SerialName("continuing_subword_prefix") val continuingSubwordPrefix: String? = null,
    @SerialName("max_input_chars_per_word") val maxInputCharsPerWord: Int = 100,
    @SerialName("unk_id") val unkId: Int? = null,
    @SerialName("byte_fallback") val byteFallback: Boolean = false,
    val vocab: JsonElement? = null,
)
