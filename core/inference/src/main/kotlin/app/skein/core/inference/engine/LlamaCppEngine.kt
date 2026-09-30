// skein-1uw (E4.I4) — the app-side `InferenceEngine`, over the bound
// `:inference` isolated process. Design spec §4.1/§6, plan `E4.I4`
// (amended by `docs/design/LOCK_POLICY_INDEXING.md` §7.7, 2026-09-20).
//
// ============================================================================
// WHAT THIS CLASS IS
// ============================================================================
//
// Everything Skein knows about a model happens in another process. This class
// is the whole of `:app`'s side of that boundary, and nothing else in `:app`
// is allowed to talk to `:inference` (design spec §4.1: `:app` has the vault,
// `:inference` has the parser; `docs/design/NORTH_STAR_REVIEW.md` §3.4 keeps
// even `feature/chat` on the `InferenceEngine` contract and off `:core:ipc`).
// So this file carries five responsibilities and no more:
//
//   1. BIND — lazily, through [ServiceConnector], with
//      `BIND_AUTO_CREATE | BIND_IMPORTANT`; rebind after a death.
//   2. HAND OVER DESCRIPTORS, NEVER PATHS — `load` sends the pinned, read-only
//      fds of a manifest-checked binding (POST_REVIEW_RESOLUTIONS.md §2.3).
//      `:app` never opens a GGUF except to copy and hash opaque bytes, and
//      never parses one.
//   3. SPILL PER MESSAGE — a prompt over the inline budget travels as one
//      descriptor per message, roles intact ([PromptSpiller]).
//   4. TRANSLATE FAILURE — every `ErrorCode` becomes the
//      `InferenceException` subclass `:core:ipc` names for it, and process
//      death becomes `ServiceDied`. A caller never sees a Binder type.
//   5. REPORT STATE — one `StateFlow<ModelStatus>`, UNLOADED → LOADING →
//      READY → GENERATING → READY.
//
// ============================================================================
// WHAT THIS CLASS IS NOT
// ============================================================================
//
// * It does NOT notify. `ModelNotifier` (`E6.I18`/`skein-fsn`) lives in
//   `:app`, and `:core:inference` must not reach into `:app`. The wiring bead
//   `skein-whg8` observes [status] and calls `notifyModelLoading()` /
//   `notifyModelLoaded()` / `dismissModelNotification()` from there. Keeping
//   the calls out of here is also what keeps this class testable on the JVM.
// * It does NOT decide policy. Thread count, GPU layers and the context cap
//   come from [InferenceConfig]; sampling defaults from [SamplingDefaults];
//   thermal backoff from `ThermalGovernor` (`E4.I9`), which is a separate
//   collaborator the caller paces with.
// * It does NOT own the model registry. The pinned binding arrives through
//   [ModelPinSource]; `skein-cyq`'s `ModelManager` is what produces one.
// * It does NOT retry. A refused load is returned, not re-attempted: the user
//   asked for one thing once.
//
// ============================================================================
// THE `backendReport` SEAM (OfflineLLM OL-05, bead `skein-gg11.2`)
// ============================================================================
//
// `skein-gg11.2` adds a backend/CPU-feature report to the service — the
// instrument that proves `skein-gg11.1`'s CPU-only fix on the Fold. Whether it
// lands as extra `EngineStatus` fields or as a new AIDL method, the client
// surface it needs already exists here: [engineStatus] is a straight
// pass-through of `IInferenceService.status()`, so a widened `EngineStatus`
// reaches callers with NO change to this class, and a new method needs one
// forwarding function beside it. Deliberately not modelled on
// [app.skein.core.model.ModelStatus]: that type is the locked `E0.I10`
// contract and has no field for a backend report — widening it is a contract
// change, not an engine change.
//
// ============================================================================
// LOGGING
// ============================================================================
//
// Every `SkeinLog` call in this file passes FIXED text plus, at most, counts,
// codes and state names (spec §9: no prompt, answer or model content in logs).
// No message content, no model path, no digest, no service diagnostic reaches
// a log line — a service diagnostic is untrusted (`sanitizeDiagnostic`) and
// belongs in the exception the caller receives, not in logcat.

package app.skein.core.inference.engine

import android.os.ParcelFileDescriptor
import android.os.RemoteException
import app.skein.core.inference.InferenceConfig
import app.skein.core.inference.TokenCounter
import app.skein.core.inference.models.ManifestBinding
import app.skein.core.inference.models.WireBindings
import app.skein.core.model.Capability
import app.skein.core.model.EngineState
import app.skein.core.model.InferenceEngine
import app.skein.core.model.InferenceException
import app.skein.core.model.Model
import app.skein.core.model.ModelStatus
import app.skein.core.model.Prompt
import app.skein.core.model.PromptMeasurement
import app.skein.core.model.SamplingParams
import app.skein.core.model.SkeinLog
import app.skein.core.model.StopReason
import app.skein.core.model.Token
import app.skein.ipc.EmbedRequest
import app.skein.ipc.EngineStatus
import app.skein.ipc.ErrorCode
import app.skein.ipc.ErrorCodes
import app.skein.ipc.GenStats
import app.skein.ipc.GenerateRequest
import app.skein.ipc.IInferenceCallback
import app.skein.ipc.IInferenceService
import app.skein.ipc.InspectRequest
import app.skein.ipc.LoadRequest
import app.skein.ipc.ModelInspection
import app.skein.ipc.SamplingParcel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import app.skein.ipc.ManifestBinding as WireManifestBinding

/**
 * The production [InferenceEngine]: llama.cpp, in the isolated `:inference`
 * process, reached only over the `:core:ipc` AIDL contract.
 *
 * Also implements [TokenCounter] (the seam `skein-4c7`/`E4.I7` declared)
 * rather than introducing a second class for one method: `ContextBudget`
 * needs "how many tokens does THIS model make of this text", which only a
 * loaded model can answer, and this object is the one that has one.
 *
 * @param connector binds the service; see [ServiceConnector] for why the bind
 *   is a seam. `AndroidServiceConnector(context)` in production.
 * @param pins resolves a [Model] to its pinned, manifest-checked binding —
 *   `skein-cyq`'s `ModelManager`, or [StoreModelPinSource] over the immutable
 *   store plus a manifest lookup.
 * @param spillDir app-private directory for staging a spilled message body,
 *   `context.cacheDir` in production. Nothing is left in it: see
 *   [PromptSpiller]'s header.
 * @param sessionEpoch the vault's current `SessionEpoch`, read fresh for EVERY
 *   request (`LOCK_POLICY_INDEXING.md` §7.7, plan `E4.I4`'s 2026-09-20
 *   amendment). A cached epoch is the bug this parameter's shape prevents: the
 *   vault can lock between building a request and sending it, and the service
 *   must be able to refuse the stale one with
 *   [InferenceException.SessionLocked].
 * @param config thread count, GPU layers and the context cap — see
 *   [InferenceConfig].
 * @param io the dispatcher every blocking Binder transaction runs on.
 */
public class LlamaCppEngine(
    private val connector: ServiceConnector,
    private val pins: ModelPinSource,
    private val spillDir: File,
    private val sessionEpoch: () -> Long,
    private val config: InferenceConfig = InferenceConfig(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : InferenceEngine,
    TokenCounter {
    private val spiller = PromptSpiller(spillDir)
    private val modelTransactions = ModelTransactions(io)
    private val lockControl = CoroutineScope(SupervisorJob() + io)
    private val lifecycle = Any()
    private val revokedEpoch = AtomicLong(EPOCH_NONE)
    private val bindingId = AtomicLong(0L)

    /** Serialises `load` / `unload` / `embed` against each other. */
    private val gate = Mutex()

    /** Serialises binding, so two callers cannot open two bindings. */
    private val bindGate = Mutex()

    private val _status = MutableStateFlow(ModelStatus(modelId = null, state = EngineState.UNLOADED))

    /**
     * The engine's observable state.
     *
     * UNLOADED → LOADING → READY → GENERATING → READY, with ERROR for a
     * refused load and UNLOADED again for `unload` and for process death.
     *
     * `skein-whg8` observes this and drives `ModelNotifier`; `skein-12c` (the
     * command-bar chip) observes it too. Neither is this class's business.
     */
    public val status: StateFlow<ModelStatus> = _status.asStateFlow()

    private val connection = AtomicReference<IInferenceService?>(null)
    private val pinned = AtomicReference<ModelPin?>(null)
    private val loadedModel = AtomicReference<Model?>(null)
    private val activeStream = AtomicReference<ActiveStream?>(null)
    private val nextRequestId = AtomicInteger(1)

    /**
     * The epoch the service has been AUTHORIZED for, or [EPOCH_NONE].
     *
     * `LOCK_POLICY_INDEXING.md` §6.1 invariant I6: the service refuses every
     * request until an explicit `onSessionUnlocked`, and §5.3: ":app re-sends
     * `onUnlocked` on every fresh bind". The engine holds the binding, so the
     * engine is the only thing placed to honour the second half — see [connect].
     */
    private val authorizedEpoch = AtomicLong(EPOCH_NONE)

    /** One in-flight `stream`, and the handle the death callback closes it with. */
    private class ActiveStream(
        val requestId: Int,
        val fail: (Throwable) -> Unit,
    )

    // ---------------------------------------------------------------- load

    /**
     * Verifies nothing itself, and that is the design: the pinned descriptors
     * carry EXPECTED digests and the isolated process is the only place that
     * checks them against the bytes it maps (POST_REVIEW_RESOLUTIONS.md §2.3).
     *
     * The one comparison made here touches no model bytes: the registry's
     * [Model.sha256] must equal the digest the manifest binding expects for the
     * main file. They are two records of the same decision — the row `:app`
     * shows the user, and the manifest the importer accepted — and a
     * disagreement between them means the caller is about to load something
     * other than what it thinks it is. That is [InferenceException.HashMismatch]
     * with both sides filled in, which is the only place in this codebase where
     * an `expected`/`actual` pair is actually known: the service reports a
     * digest refusal by CODE and never echoes the digest it observed (spec §9).
     *
     * Never throws (spec §4.1): every failure is `Result.failure`.
     */
    override suspend fun load(model: Model): Result<Unit> =
        gate.withLock {
            val version = modelTransactions.current()
            releaseLoaded()
            _status.value = ModelStatus(modelId = model.id, state = EngineState.LOADING)
            try {
                loadLocked(model, version)
                modelTransactions.publish(version) {
                    loadedModel.set(model)
                    _status.value = ModelStatus(modelId = model.id, state = EngineState.READY)
                }
                SkeinLog.i(TAG, "model loaded")
                Result.success(Unit)
            } catch (cancellation: CancellationException) {
                // The CALLER walked away. Not a failure of the model, and not
                // something `Result.failure` may swallow: structured
                // concurrency needs it to propagate.
                releaseLoaded()
                _status.value = ModelStatus(modelId = null, state = EngineState.UNLOADED)
                throw cancellation
            } catch (failure: Throwable) {
                releaseLoaded()
                _status.value =
                    ModelStatus(
                        modelId = null,
                        state =
                            if (failure is InferenceException.SessionLocked) {
                                EngineState.UNLOADED
                            } else {
                                EngineState.ERROR
                            },
                    )
                // The class name, never the message: a service diagnostic is
                // untrusted text and belongs in the exception, not in logcat.
                SkeinLog.w(TAG, "load refused: ${failure.javaClass.simpleName}")
                Result.failure(failure)
            }
        }

    private suspend fun loadLocked(
        model: Model,
        version: Long,
    ) {
        val pin = pins.pin(model).getOrThrow()
        pinned.set(pin)

        val expected = pin.binding.main.expectedSha256
        if (!model.sha256.equals(expected, ignoreCase = true)) {
            throw InferenceException.HashMismatch(expected = model.sha256, actual = expected)
        }

        val service = connect()
        val request =
            LoadRequest(
                binding = WireBindings.toWire(pin.binding, pin.attestation, pin.attestationBundle),
                contextLength = minOf(model.contextLength, config.contextLengthCap),
                threads = config.threads,
                gpuLayers = config.gpuLayers,
                // The service allocates an embedding-capable context only when
                // the model is one; `embed` refuses otherwise.
                embeddingMode = Capability.EMBEDDING in model.capabilities,
                sessionEpoch = sessionEpoch(),
            )
        val code =
            modelTransactions.call(version, close = { closeLocalCopies(request) }) {
                runRemote { service.load(request) }
            }
        if (code != ErrorCode.OK) {
            throw loadException(code, expected)
        }
    }

    /**
     * A refused `load` returns a bare code — there is no diagnostic slot on
     * `int load(in LoadRequest)` — so the exception is built from the code
     * alone, except for [ErrorCode.HASH_MISMATCH], where the client knows what
     * it asked the service to expect and fills that side in.
     */
    private fun loadException(
        code: Int,
        expectedSha256: String,
    ): InferenceException =
        if (code == ErrorCode.HASH_MISMATCH) {
            // `actual` stays empty deliberately: the service refuses by code and
            // never sends back the digest it computed over the file's bytes.
            InferenceException.HashMismatch(expected = expectedSha256, actual = "")
        } else {
            ErrorCodes.toException(code) ?: InferenceException.Internal("load returned $code")
        }

    // -------------------------------------------------------------- stream

    /**
     * One turn, token by token.
     *
     * Sampling: [params] is used as given UNLESS it is the contract's own
     * `SamplingParams()` — the value a caller passes when it has no opinion —
     * in which case [SamplingDefaults] for the loaded model applies (plan
     * `E4.I6`). Equality against the default instance is what "the caller
     * supplied sampling" means here; a caller that genuinely wants the spec
     * baseline for a Qwen model passes `SamplingDefaults.FALLBACK.copy(seed =
     * …)` or any other explicit value.
     *
     * Failure modes, all as `InferenceException`: no model loaded
     * ([InferenceException.ModelNotLoaded]), a second concurrent collector
     * ([InferenceException.Busy]), a service refusal (whatever `onError`'s code
     * maps to, [InferenceException.SessionLocked] among them), and process
     * death ([InferenceException.ServiceDied]).
     */
    override fun stream(
        prompt: Prompt,
        params: SamplingParams,
    ): Flow<Token> =
        callbackFlow {
            val model = loadedModel.get() ?: throw InferenceException.ModelNotLoaded()
            val service = connection.get() ?: throw InferenceException.ModelNotLoaded()

            val requestId = nextRequestId.getAndIncrement()
            val holder = ActiveStream(requestId) { cause -> close(cause) }
            synchronized(lifecycle) {
                checkSessionAdmission()
                if (connection.get() !== service ||
                    loadedModel.get() !== model
                ) {
                    throw InferenceException.SessionLocked()
                }
                if (!activeStream.compareAndSet(null, holder)) throw InferenceException.Busy()
            }

            var submissionAttempted = false
            try {
                val sampling = if (params == NO_OPINION) SamplingDefaults.forModel(model) else params
                val request =
                    spiller.encode(
                        requestId = requestId,
                        messages = prompt.messages,
                        sampling = sampling.toParcel(),
                        sessionEpoch = sessionEpoch(),
                        expectedModelSha256 = prompt.expectedModelSha256,
                    )

                try {
                    val callback =
                        object : IInferenceCallback.Stub() {
                            override fun onTokens(
                                id: Int,
                                pieces: Array<out String>?,
                                ids: IntArray?,
                                dropped: Int,
                            ): Unit =
                                synchronized(lifecycle) {
                                    if (id != requestId || activeStream.get() !== holder) return@synchronized
                                    if (dropped > 0) {
                                        // A count, not content. `skein-6as` renders the
                                        // "output truncated for speed" state from the
                                        // partial turn it already has; the locked
                                        // `Token` hierarchy has no variant to carry it.
                                        SkeinLog.w(TAG, "service shed $dropped token batches under backpressure")
                                    }
                                    val texts = pieces ?: return@synchronized
                                    val tokenIds = ids
                                    texts.forEachIndexed { index, piece ->
                                        trySend(Token.Text(text = piece, id = tokenIds?.getOrNull(index) ?: 0))
                                    }
                                }

                            override fun onDone(
                                id: Int,
                                stats: GenStats?,
                            ): Unit =
                                synchronized(lifecycle) {
                                    if (id != requestId || activeStream.get() !== holder) return@synchronized
                                    stats?.let {
                                        // Numbers only (spec §9) — the smoke's TTFT and tok/s record.
                                        SkeinLog.i(
                                            TAG,
                                            "generation done: reason=${it.stopReason} n_in=${it.promptTokens} " +
                                                "n_out=${it.generatedTokens} ttft=${it.ttftMs}ms rate=${"%.2f".format(
                                                    it.tokensPerSec,
                                                )}/s",
                                        )
                                        trySend(it.toToken())
                                        _status.update { current ->
                                            current.copy(state = EngineState.READY, tokensPerSec = it.tokensPerSec)
                                        }
                                    }
                                    close()
                                }

                            override fun onError(
                                id: Int,
                                code: Int,
                                message: String?,
                            ): Unit =
                                synchronized(lifecycle) {
                                    if (id != requestId || activeStream.get() !== holder) return@synchronized
                                    // `toException` sanitizes the service's untrusted
                                    // diagnostic; a null return is OK/CANCELLED, which
                                    // is a normal end of stream, not a failure.
                                    close(ErrorCodes.toException(code, message.orEmpty()))
                                }
                        }

                    withContext(io) {
                        synchronized(lifecycle) {
                            if (activeStream.get() !== holder) throw InferenceException.SessionLocked()
                            _status.update { it.copy(modelId = model.id, state = EngineState.GENERATING) }
                        }
                        // A throwing transaction can still have been delivered. Its
                        // original service/request pair therefore owns cancellation too.
                        submissionAttempted = true
                        runRemote { service.generate(request, callback) }
                    }
                } finally {
                    // This scope starts immediately after encode, outside the IO
                    // lambda: prompt cancellation can prevent that lambda from entering.
                    closeLocalCopies(request)
                }

                awaitClose { }
            } finally {
                synchronized(lifecycle) {
                    if (activeStream.compareAndSet(holder, null)) {
                        _status.update { current ->
                            if (current.state == EngineState.GENERATING) {
                                current.copy(state = EngineState.READY)
                            } else {
                                current
                            }
                        }
                    }
                }
                // Registration of awaitClose is not guaranteed: the collector may
                // cancel on a token before withContext resumes from generate. Keep
                // cleanup outside that registration, scoped to the captured request.
                // A completed/refused request's cancel is harmless and cleanup failure
                // must not replace the caller's original result or cancellation.
                if (submissionAttempted) runCatching { service.cancel(requestId) }
            }
            // Bounded by `SamplingParams.maxTokens` pieces for one generation,
            // so "unlimited" is a few thousand small objects at worst. The
            // alternative — the default rendezvous buffer — would make
            // `trySend` from a binder thread fail whenever the UI is a frame
            // behind, and silently losing a token is worse than holding it.
        }.buffer(Channel.UNLIMITED)

    // ---------------------------------------------------------------- embed

    /**
     * Requires [Capability.EMBEDDING] on the loaded model (spec §4.1); without
     * it the answer would be a vector from a model that was never loaded in
     * embedding mode, which is worse than a refusal.
     */
    override suspend fun embed(text: String): FloatArray =
        gate.withLock {
            val model = loadedModel.get() ?: throw InferenceException.ModelNotLoaded()
            if (Capability.EMBEDDING !in model.capabilities) {
                throw InferenceException.InvalidModel("model has no embedding capability")
            }
            val service = connection.get() ?: throw InferenceException.ModelNotLoaded()
            withContext(io) {
                runRemote {
                    service.embed(
                        EmbedRequest(
                            texts = listOf(text),
                            inputFd = null,
                            isQuery = false,
                            sessionEpoch = sessionEpoch(),
                        ),
                    )
                }
            }
        }

    // --------------------------------------------------------------- cancel

    /** Cancels the in-flight generation, if any. Idempotent and never throws. */
    override suspend fun cancel() {
        val active = activeStream.get() ?: return
        val service = connection.get() ?: return
        withContext(io) { runCatching { service.cancel(active.requestId) } }
    }

    // --------------------------------------------------------------- unload

    /**
     * Releases the model, the KV cache and the store's read lock.
     *
     * Keeps the BINDING: "locking the vault unloads the model and kills
     * nothing in `:app`" (epic `skein-gg11` DoD step 5), and an unbind would
     * tear down the isolated process, making the next unlock pay a fresh
     * process start on top of the reload.
     */
    override suspend fun unload() {
        gate.withLock {
            val service = connection.get()
            if (service != null) {
                withContext(io) { runCatching { service.unload() } }
            }
            releaseLoaded()
            _status.value = ModelStatus(modelId = null, state = EngineState.UNLOADED)
        }
    }

    /** Service-side count of the exact template/tokenization path used by [stream]. */
    public suspend fun measurePrompt(
        prompt: Prompt,
        params: SamplingParams,
    ): PromptMeasurement {
        val model = loadedModel.get() ?: throw InferenceException.ModelNotLoaded()
        val service = connection.get() ?: throw InferenceException.ModelNotLoaded()
        if (activeStream.get() != null) throw InferenceException.Busy()
        val sampling = if (params == NO_OPINION) SamplingDefaults.forModel(model) else params
        return withContext(io) {
            val request =
                spiller.encode(
                    requestId = nextRequestId.getAndIncrement(),
                    messages = prompt.messages,
                    sampling = sampling.toParcel(),
                    sessionEpoch = sessionEpoch(),
                    expectedModelSha256 = prompt.expectedModelSha256 ?: model.sha256,
                )
            try {
                val result = runRemote { service.measurePrompt(request) }
                if (!result.modelSha256.equals(
                        request.expectedModelSha256,
                        ignoreCase = true,
                    )
                ) {
                    throw InferenceException.ModelChanged()
                }
                if (result.promptTokens < 0 || result.contextLength <= 0) {
                    throw InferenceException.Internal("invalid prompt measurement")
                }
                PromptMeasurement(result.promptTokens, result.contextLength, result.modelSha256)
            } finally {
                closeLocalCopies(request)
            }
        }
    }

    // ---------------------------------------------------- TokenCounter (4c7)

    /**
     * `IInferenceService.tokenCount` — the model's own tokenizer, not an
     * estimate. `ContextBudget` (`E4.I7`) caches the answers; this does not.
     */
    override suspend fun count(text: String): Int {
        val service = connection.get() ?: throw InferenceException.ModelNotLoaded()
        return withContext(io) { runRemote { service.tokenCount(text) } }
    }

    // ------------------------------------------------------------ H2 inspect

    /**
     * H2 (`skein-ktvz`), `docs/design/SKEIN_HUB.md` §3.3: read a model's
     * metadata WITHOUT accepting it, inside the isolated process.
     *
     * The alternative the Hub design deleted was a pure-Kotlin
     * `GgufMetadataProbe` in `:app` — a parser for attacker-controlled length
     * fields running in the process that holds the vault. This method is that
     * decision made callable: `:app` hands over descriptors and gets back ten
     * scalars. No `llama_context` is created and no model stays loaded, so a
     * model already loaded is still loaded, with the same handles, afterwards.
     *
     * **Nothing returned here is trusted as identity.** `ModelInspection`'s
     * KDoc is explicit that a hostile GGUF can lie in every field;
     * `skein-cyq`'s `ModelManager` compares `architecture` against its own
     * allowlist and clamps `contextLength` against
     * [InferenceConfig.contextLengthCap], and size/digests/id stay
     * Core-derived.
     *
     * Refusals are NOT exceptions: the outcome is
     * [ModelInspection.errorCode] ([ErrorCode.BUSY] while a generation is in
     * flight, [ErrorCode.SESSION_LOCKED] when the vault locked underneath,
     * the same digest refusals `load` returns). A caller reads the code first.
     * Only a dead service throws, because then there is no answer at all.
     *
     * The wiring bead adapts this to the `ModelInspector` seam the model
     * manager declares — `ModelInspector { engine.inspect(it) }` — which is why
     * this is a plain public method and not an interface implementation.
     *
     * @param binding the store-side, manifest-checked binding. Converted to
     *   descriptors here; the service owns and closes every one of them.
     */
    public suspend fun inspect(binding: ManifestBinding): ModelInspection {
        val version = modelTransactions.current()
        val service =
            try {
                connect()
            } catch (_: InferenceException.SessionLocked) {
                return ModelInspection.refused(ErrorCode.SESSION_LOCKED)
            }
        val request =
            InspectRequest(
                binding = WireBindings.toWire(binding),
                sessionEpoch = sessionEpoch(),
            )
        return try {
            val result =
                modelTransactions.call(version, close = { closeLocalCopies(request.binding) }) {
                    runRemote { service.inspect(request) }
                }
            modelTransactions.publish(version) { result }
        } catch (_: InferenceException.SessionLocked) {
            ModelInspection.refused(ErrorCode.SESSION_LOCKED)
        }
    }

    // --------------------------------------------------------- lock policy

    /**
     * `IInferenceService.onSessionUnlocked` — the push without which the
     * service refuses everything (`LOCK_POLICY_INDEXING.md` §6.1 invariant I6).
     *
     * SUSPENDS UNTIL THE SERVICE HAS APPLIED IT (bd skein-gg11.8). The AIDL
     * method is two-way, so the transaction returns only once the isolated
     * process's `IsolatedSessionGate` holds [epoch]; when this function
     * returns, the next [load] or [stream] — from this coroutine or any other —
     * carries an epoch the gate already admits. While the push was `oneway`,
     * Binder was free to dispatch that next call on a different thread of the
     * service's pool ahead of the still-queued push, and the first request
     * after an unlock could be refused `SESSION_LOCKED` on a perfectly
     * unlocked vault. Nothing in the service's handler blocks, so the cost is
     * one round trip.
     *
     * The engine remembers [epoch] and RE-SENDS it on every fresh bind, which
     * is what §5.3 requires and what nothing else is placed to do: `:app` holds
     * the session, but the engine holds the binding and is the only thing that
     * knows when a new one was made.
     *
     * A failed push is swallowed rather than raised, and that stays correct
     * now that the call is two-way: the only way it fails is that the service
     * died, the remembered epoch outlives the binding, and [connect] re-sends
     * it — synchronously — on the rebind. An epoch the vault has since locked
     * is forgotten by [onSessionLocked], so the re-send cannot resurrect it.
     *
     * Called by the vault's lock-policy observer (`skein-whg8`), never by this
     * class.
     */
    public suspend fun onSessionUnlocked(epoch: Long) {
        synchronized(lifecycle) {
            if (epoch <= revokedEpoch.get() || epoch < authorizedEpoch.get()) return
            authorizedEpoch.set(epoch)
        }
        val version = modelTransactions.current()
        val service = connection.get() ?: return
        try {
            modelTransactions.call(version, close = {}) { runCatching { service.onSessionUnlocked(epoch) } }
        } catch (_: InferenceException.SessionLocked) {
            // Revocation has already scheduled the terminal push independently.
        }
    }

    /**
     * `IInferenceService.onSessionLocking` — the cancel budget starts (§5.2).
     *
     * Still `oneway`, deliberately: `:app` sends this while tearing the session
     * down and about to zero the master key, and a wedged or compromised
     * isolated process must not be able to block or delay that. Refusal does
     * not depend on the round trip — the service revokes admission as the first
     * act of its handler, and every request independently carries its epoch.
     * See the note above the three methods in `IInferenceService.aidl`.
     */
    public suspend fun onSessionLocking(
        epoch: Long,
        budgetMillis: Long,
    ) {
        val service =
            synchronized(lifecycle) {
                if (!revokeSession(epoch)) return
                connection.get()
            } ?: return
        lockControl.launch { runCatching { service.onSessionLocking(epoch, budgetMillis) } }
    }

    /**
     * `IInferenceService.onSessionLocked` — the budget has elapsed. `oneway`,
     * for the reason [onSessionLocking] gives.
     *
     * Also FORGETS the authorized epoch, so a later rebind cannot re-authorize
     * a session the vault has locked. Without that, "the vault is locked" would
     * last exactly until the isolated process next restarted. Forgetting
     * happens BEFORE the push, so it holds even if the push never lands.
     */
    public suspend fun onSessionLocked(epoch: Long) {
        val service =
            synchronized(lifecycle) {
                if (!revokeSession(epoch)) return
                connection.getAndSet(null)
            } ?: return
        // Keep the important binding until the death callback. Unbinding before
        // this oneway message executes can freeze an outstanding native call.
        lockControl.launch { runCatching { service.onSessionLocked(epoch) } }
    }

    private fun revokeSession(epoch: Long): Boolean =
        synchronized(lifecycle) {
            val authorized = authorizedEpoch.get()
            if (authorized != EPOCH_NONE && authorized != epoch) return@synchronized false
            if (epoch < revokedEpoch.get()) return@synchronized false
            authorizedEpoch.compareAndSet(epoch, EPOCH_NONE)
            revokedEpoch.set(epoch)
            modelTransactions.revoke()
            releaseLoaded()
            _status.value = ModelStatus(modelId = null, state = EngineState.UNLOADED)
            activeStream.getAndSet(null)?.fail(InferenceException.SessionLocked())
            true
        }

    // -------------------------------------------------------- engine status

    /**
     * The service's own view of itself.
     *
     * The `backendReport` seam (OfflineLLM OL-05, `skein-gg11.2`) — see this
     * file's header. Never call it while a generation is in flight; the
     * contract says it answers `BUSY` then.
     */
    public suspend fun engineStatus(): EngineStatus {
        val service = connection.get() ?: throw InferenceException.ModelNotLoaded()
        return withContext(io) { runRemote { service.status() } }
    }

    // ------------------------------------------------------------- internals

    /** The live service, binding first if necessary. The "next `load` rebinds" path. */
    private suspend fun connect(): IInferenceService {
        checkSessionAdmission()
        connection.get()?.let { return it }
        return bindGate.withLock {
            checkSessionAdmission()
            connection.get() ?: run {
                val version = modelTransactions.current()
                val id = synchronized(lifecycle) { bindingId.incrementAndGet() }
                val bindingAuthorization = authorizedEpoch.get()
                val bindingEpoch = bindingAuthorization.takeIf { it != EPOCH_NONE } ?: sessionEpoch()
                modelTransactions.call(version, close = {}) {
                    var service: IInferenceService? = null
                    var published = false
                    try {
                        val bound = connector.connect { onServiceDeath(id) }
                        service = bound
                        currentCoroutineContext().ensureActive()
                        if (bindingAuthorization != EPOCH_NONE) {
                            runCatching { bound.onSessionUnlocked(bindingAuthorization) }
                        }
                        currentCoroutineContext().ensureActive()
                        modelTransactions.publish(version) {
                            checkSessionAdmission()
                            connection.set(bound)
                            published = true
                        }
                        bound
                    } finally {
                        if (!published) {
                            val bound = service
                            if (bound != null) {
                                val terminalEpoch =
                                    synchronized(lifecycle) {
                                        val revoked = revokedEpoch.get()
                                        if (revoked != EPOCH_NONE && revoked >= bindingEpoch) {
                                            bindingEpoch.takeIf { it != EPOCH_NONE } ?: revoked
                                        } else {
                                            // Caller cancellation is not session revocation.
                                            // A newer attempt in the same epoch may already
                                            // use this process; an old locked push would kill it.
                                            if (bindingId.get() == id) connector.disconnect()
                                            null
                                        }
                                    }
                                if (terminalEpoch != null) {
                                    // Only a genuinely revoked epoch may terminate a late
                                    // connection. The service ignores this after a newer unlock.
                                    lockControl.launch { runCatching { bound.onSessionLocked(terminalEpoch) } }
                                }
                            } else {
                                synchronized(lifecycle) {
                                    if (bindingId.get() == id) connector.disconnect()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun checkSessionAdmission() {
        if (revokedEpoch.get() != EPOCH_NONE && authorizedEpoch.get() == EPOCH_NONE) {
            throw InferenceException.SessionLocked()
        }
    }

    /**
     * Process death, on a binder thread.
     *
     * Order matters: the in-flight flow is failed LAST, after the engine has
     * already forgotten the connection and the model, so a collector that
     * reacts to `ServiceDied` by calling `load` again finds a clean engine
     * rather than one still holding a dead binder.
     */
    private fun onServiceDeath(id: Long): Unit =
        synchronized(lifecycle) {
            if (!bindingId.compareAndSet(id, id + 1)) return@synchronized
            connector.disconnect()
            modelTransactions.revoke(InferenceException.ServiceDied())
            SkeinLog.w(TAG, "inference process died")
            connection.set(null)
            loadedModel.set(null)
            pinned.getAndSet(null)?.close()
            _status.value = ModelStatus(modelId = null, state = EngineState.UNLOADED)
            activeStream.getAndSet(null)?.fail(InferenceException.ServiceDied())
        }

    /** Drops the model handle and the store's shared read lock. */
    private fun releaseLoaded() {
        loadedModel.set(null)
        pinned.getAndSet(null)?.close()
    }

    /**
     * Runs one Binder transaction, turning the two things Binder can throw
     * into contract failures.
     *
     * `DeadObjectException` (a `RemoteException`) is the race `linkToDeath`
     * cannot win: the process can die between the check and the call.
     * A coded `IllegalStateException` is the convention `ErrorCodes`
     * documents for the sync entry points with no code slot (`embed`,
     * `tokenCount`) — judgment call J7.
     */
    private fun <T> runRemote(block: () -> T): T =
        try {
            block()
        } catch (e: RemoteException) {
            SkeinLog.w(TAG, "binder transaction failed: ${e.javaClass.simpleName}")
            throw InferenceException.ServiceDied()
        } catch (e: IllegalStateException) {
            throw ErrorCodes.toExceptionOrNull(e) ?: e
        }

    /**
     * Closes THIS process's copy of every descriptor a request carried.
     *
     * Binder DUPLICATES a `ParcelFileDescriptor` when it marshals one with
     * flags `0`, which is what an AIDL `in` parameter uses: the receiver gets
     * its own descriptor (which it owns and closes — POST_REVIEW_RESOLUTIONS.md
     * §3.2 rule 3) and the sender's stays open. Without this the engine would
     * leak one descriptor per model file per `load` and one per spilled
     * message per turn, and a long chat would end at the per-process fd limit.
     *
     * `finally`, on every path: a transaction that threw may still have been
     * delivered, and closing our copy is safe either way.
     */
    private fun closeLocalCopies(request: LoadRequest) = closeLocalCopies(request.binding)

    private fun closeLocalCopies(binding: WireManifestBinding) {
        binding.files.forEach { closeQuietly(it.fd) }
        binding.attestation?.let { closeQuietly(it.bundleFd) }
    }

    private fun closeLocalCopies(request: GenerateRequest) {
        request.messages.forEach { message -> message.contentFd?.let { closeQuietly(it.fd) } }
        request.attachmentFds.forEach { closeQuietly(it.fd) }
    }

    private fun closeQuietly(fd: ParcelFileDescriptor) {
        runCatching { fd.close() }
    }

    private fun SamplingParams.toParcel(): SamplingParcel =
        SamplingParcel(
            temperature = temperature,
            topK = topK,
            topP = topP,
            minP = minP,
            repeatPenalty = repeatPenalty,
            maxTokens = maxTokens,
            seed = seed,
            stop = stop,
        )

    /**
     * `GenStats.stopReason` is a string from the untrusted service.
     * `StopReason.valueOf` would throw on an unrecognised one and turn a
     * finished generation into a crash, so an unknown reason reads as
     * [StopReason.EOS] — the stream did end, and the reason is the only thing
     * in doubt.
     */
    private fun GenStats.toToken(): Token.Done =
        Token.Done(
            reason = StopReason.entries.firstOrNull { it.name == stopReason } ?: StopReason.EOS,
            promptTokens = promptTokens,
            generatedTokens = generatedTokens,
            ttftMs = ttftMs,
            tokensPerSec = tokensPerSec,
        )

    private companion object {
        const val TAG = "LlamaCppEngine"

        /** The value a caller passes when it has no sampling opinion. See [stream]. */
        val NO_OPINION = SamplingParams()

        /**
         * "No session has been authorized". The same sentinel the service's own
         * `SessionEpoch.NONE` uses (`inference-service`), restated rather than
         * imported: `:core:inference` may not depend on that module.
         */
        const val EPOCH_NONE: Long = 0L
    }
}
