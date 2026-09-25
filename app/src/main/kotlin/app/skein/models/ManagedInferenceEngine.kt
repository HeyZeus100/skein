// skein-whg8 — the composition-root decorator that makes an `InferenceEngine`
// (real `LlamaCppEngine` in production, `FakeInferenceEngine` in tests) safe
// to hand to `feature/chat`'s `SendPipeline` without `SendPipeline` (or
// `ChatViewModel`, which this bead may not edit — feature/chat is out of
// scope) ever having to know that a model might not be loaded yet.
//
// THREE JOBS, all decided here rather than by editing feature/chat:
//
//  1. LAZY LOAD ON FIRST SEND (DoD item 3, "loads the default model lazily
//     on the first send"). `SendPipeline.send` calls `engine.stream(...)`
//     directly — there is no hook in feature/chat to intercept "the user
//     just pressed send for the first time" other than the one seam every
//     `InferenceEngine` implementation shares: `stream` itself. [stream]
//     below resolves and loads [defaultModel] the first time it is called
//     while nothing is loaded, then delegates.
//  2. A `StateFlow<ModelStatus>` this composition root can show honestly in
//     the command-bar chip and drive `ModelNotifier` from — LOADING while
//     [load] is in flight, READY once it succeeds, GENERATING while a
//     [stream] collection is live, back to READY after, UNLOADED after
//     [unload]. This mirrors `LlamaCppEngine.status`'s own state machine
//     (core/inference, out of this bead's scope) rather than reading it
//     directly, because `FakeInferenceEngine` (the substitution the DoD's
//     own JVM tests require) exposes no such property — deriving [status]
//     here, from calls this class already intercepts, is what makes the
//     SAME status machinery work over either engine. Recorded as a
//     deliberate deviation from the coordinator note's literal "observing
//     engine.status" (see the hand-back).
//  3. AN UNLOAD GUARD for the lock path (DoD item 2 / item 5): [unload] is a
//     no-op — never calls the delegate — once [status] already reads
//     `UNLOADED`, which is what lets a `LockObserver` call [unload]
//     unconditionally on every lock cycle while still satisfying "never
//     [call the delegate's unload] after a lock with no model loaded".
package app.skein.models

import app.skein.core.model.EngineState
import app.skein.core.model.InferenceEngine
import app.skein.core.model.InferenceException
import app.skein.core.model.Model
import app.skein.core.model.ModelStatus
import app.skein.core.model.Prompt
import app.skein.core.model.SamplingParams
import app.skein.core.model.Token
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Wraps [delegate] so the chat surface can call [stream] before any model
 * has been explicitly loaded, and so the lock path can call [unload]
 * unconditionally. See the file header for why this exists rather than a
 * direct reference to the concrete engine's own status.
 *
 * @param delegate the real `InferenceEngine` — `LlamaCppEngine` in
 *   production, `FakeInferenceEngine` in tests.
 * @param defaultModel resolves the model to load on first [stream] when
 *   nothing is loaded yet — `registry.default()` + `registry.get(id)` in
 *   production. `null` means "no default configured", which [stream] then
 *   surfaces as [InferenceException.ModelNotLoaded] (DoD item 3: "a `/chat`
 *   with no default model must ... never crash" — this is the typed refusal
 *   the chat surface's existing error banner already renders).
 */
public class ManagedInferenceEngine(
    private val delegate: InferenceEngine,
    private val defaultModel: suspend () -> Model?,
) : InferenceEngine {
    private val _status = MutableStateFlow(ModelStatus(modelId = null, state = EngineState.UNLOADED))

    /** Observed by the command-bar chip and by `ModelNotifier` (both `:app`, never `:core:inference`). */
    public val status: StateFlow<ModelStatus> = _status.asStateFlow()

    /** Serialises [load] against itself so two racing first-sends load at most once. */
    private val loadGate = Mutex()

    override suspend fun load(model: Model): Result<Unit> = loadGate.withLock { loadLocked(model) }

    private suspend fun loadLocked(model: Model): Result<Unit> {
        _status.value = ModelStatus(modelId = model.id, state = EngineState.LOADING)
        val result = delegate.load(model)
        _status.value =
            result.fold(
                onSuccess = { ModelStatus(modelId = model.id, state = EngineState.READY) },
                onFailure = { ModelStatus(modelId = null, state = EngineState.ERROR) },
            )
        return result
    }

    override fun stream(
        prompt: Prompt,
        params: SamplingParams,
    ): Flow<Token> =
        flow {
            ensureLoaded()
            _status.update { it.copy(state = EngineState.GENERATING) }
            emitAll(
                delegate.stream(prompt, params).onCompletion {
                    _status.update { current ->
                        if (current.state ==
                            EngineState.GENERATING
                        ) {
                            current.copy(state = EngineState.READY)
                        } else {
                            current
                        }
                    }
                },
            )
        }

    /**
     * Loads the registry default if nothing is loaded — the same lazy load
     * [stream] performs, exposed so `SendPipeline` can run it BEFORE prompt
     * assembly (whose token counting needs the bound service). Throws
     * [InferenceException.ModelNotLoaded] when there is no default, and
     * whatever the delegate's load failed with otherwise.
     */
    public suspend fun warmUp(): Unit = ensureLoaded()

    private suspend fun ensureLoaded() {
        val current = _status.value.state
        if (current == EngineState.READY || current == EngineState.GENERATING) return
        val model = defaultModel() ?: throw InferenceException.ModelNotLoaded()
        loadGate.withLock {
            // Re-check inside the gate: another collector may have already
            // loaded it while this one was resolving `defaultModel()`.
            if (_status.value.state == EngineState.READY || _status.value.state == EngineState.GENERATING) return
            loadLocked(model).getOrThrow()
        }
    }

    override suspend fun embed(text: String): FloatArray = delegate.embed(text)

    override suspend fun cancel() {
        delegate.cancel()
    }

    /**
     * The unload guard (see file header, job 3). A model that was never
     * loaded — a lock during a session that opened a chat tab but never
     * sent a message — leaves [delegate] untouched.
     */
    override suspend fun unload() {
        loadGate.withLock {
            if (_status.value.state == EngineState.UNLOADED) return@withLock
            delegate.unload()
            _status.value = ModelStatus(modelId = null, state = EngineState.UNLOADED)
        }
    }
}
