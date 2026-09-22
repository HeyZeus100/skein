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

/**
 * Role of a non-main file that belongs to a model.
 *
 * The first three entries are the spec §4.1 originals. The last four were
 * added additively by `skein-3v9` (E0.I15) under coordinator decision
 * `skein-cqiu`, to match the seven roles
 * `docs/design/POST_REVIEW_RESOLUTIONS.md` §2.3 requires a manifest to be able
 * to cover: §2.2 rule 3 says every file the loader opens must carry its own
 * sha256 in the manifest, and a role the enum cannot name is a file the
 * manifest cannot cover. `license` is here for the same reason even though
 * nothing loads it — §2.3 keeps LICENSE texts hashed for the audit surface.
 *
 * Additive only: no existing entry was renamed, reordered or removed, so the
 * locked contract stays source- and behavior-compatible (same precedent as
 * `skein-uo5n`'s additive `VaultRepository` methods).
 */
enum class CompanionRole(
    val db: String,
) {
    MMPROJ("mmproj"),
    TOKENIZER("tokenizer"),
    CONFIG("config"),
    TOKENIZER_CONFIG("tokenizer_config"),
    GENERATION_CONFIG("generation_config"),
    LICENSE("license"),
    SPECIAL_TOKENS_MAP("special_tokens_map"),
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

/**
 * Every way the inference contract can fail, as one sealed hierarchy.
 *
 * The first six subclasses are the spec §4.1 / plan §4.1 originals and are
 * LOCKED (`E0.I10`): no rename, no message change, no reordering.
 *
 * The last six were added additively by `skein-k7e9`, because `:core:ipc`'s
 * `ErrorCode` (`E0.I16`, `skein-mfw`) has twelve constants and only six of them
 * had a subclass to map onto — `docs/design/POST_REVIEW_RESOLUTIONS.md` §3.4
 * requires `ErrorMappingTest` to assert that "every new `ErrorCode` maps to a
 * distinct `InferenceException` subclass", which was not satisfiable until this
 * hierarchy could name them. Until then `:core:ipc`'s KDoc recorded a stopgap
 * (two codes collapsed onto `HashMismatch`, one onto `Busy`, two onto plain
 * `java.lang` exceptions); those stopgaps are now gone.
 *
 * Additive only: no existing subclass was renamed, removed or had its message
 * changed, so the locked contract stays source- and behavior-compatible (same
 * precedent as `skein-uo5n`'s additive `VaultRepository` methods and
 * `skein-3v9`'s additive `CompanionRole` entries). The hierarchy is `sealed`,
 * but the repository contains no exhaustive `when` over it — every consumer
 * catches a specific subclass — so nothing downstream had to change.
 *
 * The new subclasses each take an optional `detail`: the free-form diagnostic
 * string the service sends alongside the code on the wire
 * (`IInferenceCallback.onError(requestId, code, message)`). It is appended to a
 * fixed prefix, following the `InvalidModel(reason)` precedent. The request
 * itself is never in scope here, so no prompt, document body or attachment can
 * reach an exception message.
 */
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

    // ------------------------------------------------------------------
    // Added additively by `skein-k7e9`. Everything below this line is new.
    // ------------------------------------------------------------------

    /**
     * `ErrorCode.HASH_MISMATCH_POST_MMAP` (7).
     *
     * `POST_REVIEW_RESOLUTIONS.md` §2.3: the digest recomputed over the MAPPED
     * bytes, with a distinct algorithm, differed from the pre-mmap digest — the
     * file was mutated between verification and use. Distinct from
     * [HashMismatch], which is the pre-mmap gate: this one means an active
     * TOCTOU attempt rather than a stale or corrupt download.
     */
    class PostMmapHashMismatch(
        detail: String = "",
    ) : InferenceException(withDetail("sha256 mismatch after mmap", detail))

    /**
     * `ErrorCode.COMPANION_HASH_MISMATCH` (10).
     *
     * `POST_REVIEW_RESOLUTIONS.md` §2.3: a COMPANION file's digest differed
     * (tokenizer, mmproj, config, ...) while the main file verified. The
     * failing `ManifestFileRef.role` arrives in [detail].
     */
    class CompanionHashMismatch(
        detail: String = "",
    ) : InferenceException(withDetail("companion sha256 mismatch", detail))

    /**
     * `ErrorCode.TX_TOO_LARGE` (8).
     *
     * `POST_REVIEW_RESOLUTIONS.md` §3.2: the marshalled payload exceeded the
     * inline budget. Normally unreachable — `TransportRules` refuses
     * client-side before touching Binder — so it means a client skipped the
     * guard. Previously surfaced as a plain `IllegalArgumentException`, which
     * no caller could tell apart from an ordinary argument bug.
     */
    class TransactionTooLarge(
        detail: String = "",
    ) : InferenceException(withDetail("transaction exceeds the inline budget", detail))

    /**
     * `ErrorCode.MODEL_IN_USE` (9).
     *
     * `POST_REVIEW_RESOLUTIONS.md` §2.3: the shared read lock on the main model
     * file could not be taken (`FileChannel.tryLock` returned null), so another
     * holder is using it; also what `ModelManager.delete` reports. Distinct
     * from [Busy], which is "this service already has a request in flight" —
     * the caller's remedy differs (retry later vs. release the other holder).
     */
    class ModelInUse(
        detail: String = "",
    ) : InferenceException(withDetail("model file is in use", detail))

    /**
     * `ErrorCode.SESSION_LOCKED` (11).
     *
     * `LOCK_POLICY_INDEXING.md` §5.2/§5.3: `IsolatedSessionGate.guard()`
     * refused the call because the request's `sessionEpoch` is not the epoch
     * the service is authorized for — the vault locked underneath it.
     *
     * This is a lock event, not an engine fault, and a client is expected to
     * treat it as one: cancel the caller's flow and prompt for unlock rather
     * than render an error. It still gets its own type because plan `E4.I4`
     * requires it ("maps to a new `InferenceException.SessionLocked` subtype in
     * `ErrorMapping`, distinct from `ServiceDied` — the service is alive and
     * refusing, not dead"), and because a mapping that returned nothing would
     * leave the caller unable to tell a refusal from a completed call.
     */
    class SessionLocked(
        detail: String = "",
    ) : InferenceException(withDetail("session locked", detail))

    /**
     * `ErrorCode.INTERNAL` (99), and any code this build does not recognise.
     *
     * Unclassified service-side failure. Previously surfaced as a plain
     * `IllegalStateException`, which the `stream` contract ("errors close the
     * flow with an `InferenceException`", plan §4.1) did not permit.
     */
    class Internal(
        detail: String = "",
    ) : InferenceException(withDetail("internal service failure", detail))
}

/**
 * Appends the service's diagnostic [detail] to an exception's fixed prefix,
 * following the `InferenceException.InvalidModel(reason)` precedent. Blank
 * detail leaves the prefix alone rather than producing a dangling separator.
 */
private fun withDetail(
    base: String,
    detail: String,
): String = if (detail.isBlank()) base else "$base: $detail"

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
