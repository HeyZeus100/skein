// M0.5 contract file (`E0.I10`): lands the design spec §4.1 / plan §4.1
// interface — `InferenceEngine` — plus the value types it speaks in.
//
// `:core:model` is pure Kotlin/JVM (no Android imports) so every process
// (`:app`, `:inference`, `:embedder`, `:testing`) can consume the same
// types. The only runtime dependency this file needs is
// `kotlinx.coroutines.flow.Flow` (see the module's `build.gradle.kts`).
//
// This file is intentionally free of measurement-derived numerics
// (thermal thresholds, per-model quantization, sampler tuning): the
// two numeric defaults present — `Model.contextLength = 16_384` and
// `SamplingParams.temperature/topK/topP/minP/repeatPenalty/maxTokens`
// — are copied verbatim from the design spec § 4.1 and are documented
// there as "unless revised" by `MEASUREMENTS.md` (`skein-5hr`). The
// contract itself is model-agnostic; implementations (`E4.I4`) may
// override defaults per model.

package us.aherrera.skein.core.model

import kotlinx.coroutines.flow.Flow

typealias ModelId = String

enum class ModelFormat(
    val db: String,
) {
    GGUF("gguf"),
    ONNX("onnx"),
}

enum class Capability(
    val db: String,
) {
    TEXT("text"),
    VISION("vision"),
    EMBEDDING("embedding"),
    NER("ner"),
    RERANK("rerank"),
}

enum class CompanionRole(
    val db: String,
) {
    MMPROJ("mmproj"),
    TOKENIZER("tokenizer"),
    CONFIG("config"),
}

/** One row of the `models` table plus resolved companion files. `path` is absolute, app-private. */
data class Model(
    val id: ModelId,
    val name: String,
    val path: String,
    val sha256: String,
    val format: ModelFormat,
    val capabilities: Set<Capability>,
    val sizeBytes: Long,
    val contextLength: Int = 16_384,
    val attestationUrl: String? = null,
    val companions: Map<CompanionRole, CompanionFile> = emptyMap(),
    val importedAt: Long = 0L,
)

data class CompanionFile(
    val path: String,
    val sha256: String,
)

enum class Role(
    val wire: String,
) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
}

data class ChatMessage(
    val role: Role,
    val content: String,
)

/** Chat-template application happens inside the engine (from GGUF metadata). Images require [Capability.VISION]. */
data class Prompt(
    val messages: List<ChatMessage>,
    val images: List<ByteArray> = emptyList(),
)

data class SamplingParams(
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.9f,
    val minP: Float = 0.05f,
    val repeatPenalty: Float = 1.1f,
    val maxTokens: Int = 1024,
    val seed: Long = -1L,
    val stop: List<String> = emptyList(),
)

enum class StopReason { EOS, LENGTH, STOP_STRING, CANCELLED }

sealed interface Token {
    data class Text(
        val text: String,
        val id: Int,
    ) : Token

    data class Done(
        val reason: StopReason,
        val promptTokens: Int,
        val generatedTokens: Int,
        val ttftMs: Long,
        val tokensPerSec: Float,
    ) : Token
}

sealed class InferenceException(
    message: String,
) : Exception(message) {
    class ModelNotLoaded : InferenceException("no model loaded")

    class HashMismatch(
        val expected: String,
        val actual: String,
    ) : InferenceException("sha256 mismatch")

    class InvalidModel(
        reason: String,
    ) : InferenceException("invalid model: $reason")

    class ServiceDied : InferenceException("inference process died")

    class OutOfMemory : InferenceException("out of memory")

    class Busy : InferenceException("a generation is already running")
}

/** Verbatim from the design spec §6. */
interface InferenceEngine {
    suspend fun load(model: Model): Result<Unit>

    fun stream(
        prompt: Prompt,
        params: SamplingParams,
    ): Flow<Token>

    suspend fun embed(text: String): FloatArray

    suspend fun cancel()

    suspend fun unload()
}

enum class EngineState { UNLOADED, LOADING, READY, GENERATING, ERROR }

data class ModelStatus(
    val modelId: ModelId?,
    val state: EngineState,
    val tokensPerSec: Float? = null,
    val thermalHeadroom: Float? = null,
)
