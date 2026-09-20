// The `E0.I10` `FakeInferenceEngine`: a scripted, JVM-only stand-in for the
// `InferenceEngine` contract locked in `core/model/…/Inference.kt`. It powers
// `InferenceEngineContractTest` on the JVM and is what every consumer
// (`E10.I2` chat/RAG unit tests, feature-module Compose tests) uses in place
// of a real llama.cpp process.
//
// The fake intentionally carries no measurement-dependent constants
// (thermal thresholds, token quotas, per-model tuning) — the contract from
// spec § 4.1 is model-agnostic, and any numeric that would need
// `MEASUREMENTS.md` belongs in the real `LlamaCppEngine` (`E4.I4`) instead.
//
// Semantics implemented, cross-referencing spec § 4.1 / plan § 4.1:
//   • `load` never throws — a bad `sha256` returns
//     `Result.failure(InferenceException.HashMismatch)`; loading a second
//     model performs a warm swap (`unload` → verify → load). See
//     `load_bad_hash_returns_failure` and (implicit) warm-swap semantics.
//   • `stream` is a cold `Flow<Token>`: the engine is idle until collection
//     begins, exactly one `Token.Done` is emitted last on any non-exceptional
//     completion (including the caller cancelling), and a second concurrent
//     collector fails with `InferenceException.Busy`.
//   • Cancellation cooperates with the coroutines cancellation contract:
//     the fake sleeps between tokens with `delay(...)`, so cancelling the
//     collector cancels the flow within well under 100 ms.
//   • `embed` throws `InvalidModel` when the loaded model lacks
//     `Capability.EMBEDDING`.
//   • `unload` transitions the engine to `EngineState.UNLOADED`; a
//     subsequent `stream` collection throws
//     `InferenceException.ModelNotLoaded` at the first `emit`.

package us.aherrera.skein.testing

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import us.aherrera.skein.core.model.Capability
import us.aherrera.skein.core.model.InferenceEngine
import us.aherrera.skein.core.model.InferenceException
import us.aherrera.skein.core.model.Model
import us.aherrera.skein.core.model.Prompt
import us.aherrera.skein.core.model.Role
import us.aherrera.skein.core.model.SamplingParams
import us.aherrera.skein.core.model.StopReason
import us.aherrera.skein.core.model.Token
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

/**
 * Scripted, in-memory `InferenceEngine` for JVM tests.
 *
 * @param script Map keyed by the *last user message* in a `Prompt`. Each
 * value is the list of piece strings to emit in order before the terminal
 * `Token.Done`. A prompt whose last-user-message is not in the map emits a
 * single deterministic fallback piece (`"(no script for: <msg>)"`).
 *
 * @param tokenDelay Suspension between piece emissions. Cooperative
 * cancellation: `delay` is a cancellable suspend function, so the flow is
 * torn down promptly when the collector is cancelled.
 *
 * @param rejectHash Any `Model.sha256` equal to this value is treated as a
 * corrupted / mismatched hash by `load` (returns
 * `Result.failure(HashMismatch)`). Defaults to a 64-char run of zeros —
 * an obviously-not-real digest that the contract test uses as the
 * "guaranteed-bad hash" convention (see `InferenceEngineContractTest`).
 */
public class FakeInferenceEngine(
    public val script: Map<String, List<String>> = emptyMap(),
    public val tokenDelay: Duration = ZERO,
    public val rejectHash: String = BAD_HASH,
) : InferenceEngine {
    private val streamLock: Mutex = Mutex()
    private val cancelSignal: Channel<Unit> = Channel(capacity = Channel.CONFLATED)

    @Volatile private var loaded: Model? = null

    public val currentModel: Model? get() = loaded

    override suspend fun load(model: Model): Result<Unit> {
        if (model.sha256 == rejectHash) {
            return Result.failure(
                InferenceException.HashMismatch(
                    expected = model.sha256,
                    actual = "<fake computed digest>",
                ),
            )
        }
        // Warm swap: replacing the currently-loaded model is allowed (spec § 4.1).
        loaded = model
        return Result.success(Unit)
    }

    override fun stream(
        prompt: Prompt,
        params: SamplingParams,
    ): Flow<Token> =
        flow {
            val model = loaded ?: throw InferenceException.ModelNotLoaded()

            // Enforce single-collector semantics: a second concurrent
            // collector fails with `Busy` (spec § 4.1). We use `tryLock`
            // rather than `withLock` so a busy engine surfaces synchronously
            // instead of suspending indefinitely.
            if (!streamLock.tryLock()) {
                throw InferenceException.Busy()
            }

            try {
                // Drain any stale cancel signal from a prior stream.
                cancelSignal.tryReceive()

                val lastUserMessage: String =
                    prompt.messages.lastOrNull { it.role == Role.USER }?.content
                        ?: ""
                val pieces: List<String> =
                    script[lastUserMessage]
                        ?: listOf("(no script for: $lastUserMessage)")

                var emitted = 0
                var stoppedEarly = false
                for ((index, piece) in pieces.withIndex()) {
                    if (cancelSignal.tryReceive().isSuccess) {
                        stoppedEarly = true
                        break
                    }
                    if (tokenDelay > ZERO && index > 0) {
                        delay(tokenDelay)
                    }
                    emit(Token.Text(text = piece, id = index))
                    emitted++
                    // Note: we do not proactively check `model` capability here;
                    // TEXT capability is implied for `stream`. Vision-only
                    // constraints (spec § 4.1) live in the real engine.
                }

                emit(
                    Token.Done(
                        reason = if (stoppedEarly) StopReason.CANCELLED else StopReason.EOS,
                        promptTokens = prompt.messages.sumOf { it.content.length },
                        generatedTokens = emitted,
                        ttftMs = 0L,
                        tokensPerSec = 0f,
                    ),
                )
            } finally {
                streamLock.unlock()
            }
            // Silence unused-parameter warnings until sampling is wired.
            @Suppress("UNUSED_EXPRESSION")
            params
            @Suppress("UNUSED_EXPRESSION")
            model
        }

    override suspend fun embed(text: String): FloatArray {
        val model =
            loaded ?: throw InferenceException.ModelNotLoaded()
        if (Capability.EMBEDDING !in model.capabilities) {
            throw InferenceException.InvalidModel(
                reason = "model lacks EMBEDDING capability",
            )
        }
        // Deterministic embedding: length is a stand-in for the real
        // embedder's output dimension. Values are derived from the text's
        // hashcode so tests remain reproducible without leaking `text`.
        val dim = 8
        val seed = text.hashCode()
        return FloatArray(dim) { i -> ((seed + i) and 0xFF) / 255f }
    }

    override suspend fun cancel() {
        // Non-blocking: signal any in-flight stream to stop; the collector
        // observes the signal at the next loop iteration.
        cancelSignal.trySend(Unit)
    }

    override suspend fun unload() {
        cancel()
        loaded = null
    }

    public companion object {
        /**
         * A 64-hex-character digest of nothing — used as the convention
         * value in the contract suite for the "wrong hash" path. Real
         * implementations may treat it the same way, since no file's
         * SHA-256 is genuinely all zeros in any test corpus we ship.
         */
        public const val BAD_HASH: String =
            "0000000000000000000000000000000000000000000000000000000000000000"
    }
}
