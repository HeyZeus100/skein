// skein-nxk (E4.I3): the `:inference` isolated service.
//
// PROCESS SHAPE (spec §4.1, plan §2.4). `android:isolatedProcess="true"`,
// `android:exported="false"`, its own `:inference` process. An isolated process
// has no permissions at all, no app data access, and a uid in the 99000–99999
// range — so a bug in llama.cpp reached through a hostile GGUF has nothing to
// steal and nowhere to write. Everything it needs arrives over Binder: file
// descriptors and request payloads, never a path and never a key.
//
// WHAT THIS FILE OWNS:
//
//   * the load gate — pin every received descriptor, run
//     POST_REVIEW_RESOLUTIONS.md §2's dual-hash verification over the PINNED
//     descriptors, and hand llama.cpp the descriptor itself (`skein-lnp2`: this
//     process cannot open a path, not even `/proc/self/fd/<n>`);
//   * the lock gate — `IsolatedSessionGate.guard()` is the first statement of
//     every entry point that touches plaintext (LOCK_POLICY_INDEXING.md §5.3);
//   * fd ownership — §3.2 rule 3, "the service OWNS every fd it receives and
//     MUST close() it, success or failure". Every entry point below closes what
//     it was handed, including on the refusal paths, which are the ones that
//     leak in practice;
//   * one request at a time, and exactly one terminal callback per request.
//
// WHAT IT NEVER DOES: log prompt, token, piece or document text at any level.
// Not at DEBUG, not behind a flag. `tools/ci/no-content-logging.sh` fails the
// build on a `SkeinLog` call in this module whose arguments name a content
// variable, because "we would never ship that" is not a control.

package app.skein.inference.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.SystemClock
import app.skein.core.model.SkeinLog
import app.skein.core.verify.DupedDescriptor
import app.skein.core.verify.ModelFileRole
import app.skein.core.verify.ModelVerification
import app.skein.core.verify.ModelVerifier
import app.skein.core.verify.PinResult
import app.skein.core.verify.PinnedLoad
import app.skein.core.verify.PinnedModel
import app.skein.core.verify.PinnedModelFile
import app.skein.core.verify.VerifyBinding
import app.skein.core.verify.VerifyCancellation
import app.skein.core.verify.VerifyFile
import app.skein.core.verify.VerifyProgress
import app.skein.ipc.BackendDeviceParcel
import app.skein.ipc.BackendReport
import app.skein.ipc.BackendReportRequest
import app.skein.ipc.ChatMessageParcel
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
import app.skein.ipc.ManifestBinding
import app.skein.ipc.ManifestFileRef
import app.skein.ipc.ModelInspection
import app.skein.ipc.SharedMemRef
import app.skein.ipc.TransportRules
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "InferenceService"

/** `EngineStatus.state` values. The client renders these; they are contract. */
internal object EngineState {
    const val UNLOADED = "unloaded"
    const val VERIFYING = "verifying"
    const val LOADING = "loading"
    const val READY = "ready"
    const val GENERATING = "generating"
}

/** `GenStats.stopReason` values — `app.skein.core.model.StopReason` names. */
internal object StopReasons {
    const val EOS = "EOS"
    const val LENGTH = "LENGTH"
    const val STOP_STRING = "STOP_STRING"
    const val CANCELLED = "CANCELLED"
}

class InferenceService : Service() {
    private val backend: LlamaBackend = NativeLlamaBackend
    private lateinit var worker: InferenceWorker
    private lateinit var callbacks: CallbackDispatcher
    private lateinit var engine: InferenceEngineState

    override fun onCreate() {
        super.onCreate()
        worker = InferenceWorker()
        callbacks = CallbackDispatcher()
        engine = InferenceEngineState(backend, worker, callbacks)
        // Process-global llama.cpp state: exactly once, before any worker task.
        worker.submitBlocking {
            backend.backendInit()
            backend.setLogCallback()
        }
        SkeinLog.i(TAG, "inference service created")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        engine.shutdown()
        worker.shutdown()
        callbacks.shutdown()
        super.onDestroy()
    }

    private val binder =
        object : IInferenceService.Stub() {
            override fun load(req: LoadRequest): Int = engine.load(req)

            override fun inspect(req: InspectRequest): ModelInspection = engine.inspect(req)

            override fun backendReport(req: BackendReportRequest): BackendReport = engine.backendReport(req)

            override fun generate(
                req: GenerateRequest,
                cb: IInferenceCallback,
            ) = engine.generate(req, cb)

            override fun cancel(requestId: Int) = engine.cancel(requestId)

            override fun unload() = engine.unload()

            override fun embed(req: EmbedRequest): FloatArray = engine.embed(req)

            override fun tokenCount(text: String?): Int = engine.tokenCount(text.orEmpty())

            override fun status(): EngineStatus = engine.status()

            override fun onSessionLocking(
                epoch: Long,
                budgetMillis: Long,
            ) = engine.onSessionLocking(epoch, budgetMillis)

            override fun onSessionLocked(epoch: Long) = engine.onSessionLocked(epoch)

            override fun onSessionUnlocked(epoch: Long) = engine.onSessionUnlocked(epoch)
        }
}

/**
 * Everything the binder does, with no Android `Service` in it.
 *
 * Split out so the entry-point logic — gates, fd ownership, warm swap, BUSY,
 * the stop conditions — is reachable from a JVM test with a
 * [FakeLlamaBackend][LlamaBackend], instead of only from an instrumented test
 * that needs a device and a real GGUF.
 */
internal class InferenceEngineState(
    private val backend: LlamaBackend,
    private val worker: TaskRunner,
    private val callbacks: CallbackDispatcher,
) {
    private val lock = Any()

    private val gate =
        IsolatedSessionGate(
            onCancelRequests = { cancelInFlight() },
            onReleaseState = { releaseOnLock() },
        )

    private var loaded: LoadedModel? = null
    private var active: ActiveRequest? = null
    private var state: String = EngineState.UNLOADED
    private var lastTokensPerSec: Float = 0f

    /** `E4.I6`'s ChatML fallback (skein-5oi): whether the most recent generation used it. Reset on every fresh load. */
    private var lastUsedChatTemplateFallback: Boolean = false

    /** Raised while an `unload` is waiting for a verification to notice. */
    private val unloadRequested = AtomicBoolean(false)

    private class LoadedModel(
        val pinned: PinnedModel,
        val model: Long,
        val context: Long,
        val contextLength: Int,
        val modelSha256: String,
        val embeddingMode: Boolean,
        /**
         * bd skein-gg11.2: the value this model was ACTUALLY loaded with —
         * `backendReport` re-derives the device list from it.
         */
        val gpuLayers: Int,
    )

    private class ActiveRequest(
        val requestId: Int,
        val callback: IInferenceCallback,
        val epoch: Long,
        val context: Long,
    ) {
        val cancelled = AtomicBoolean(false)
        var deathRecipient: IBinder.DeathRecipient? = null
    }

    // ---------------------------------------------------------------- load

    fun load(req: LoadRequest): Int {
        if (gate.guard(req.sessionEpoch) is GateResult.Refuse) {
            closeReceived(req.binding)
            return ErrorCode.SESSION_LOCKED
        }

        // Warm swap: a second load while loaded unloads first. Done before
        // pinning so the old model's descriptors and KV cache are gone before
        // the new model's allocation is attempted — the device may not have
        // room for both.
        unload()

        synchronized(lock) { state = EngineState.VERIFYING }
        unloadRequested.set(false)
        // `unload` aborts a verification in progress: a ten-second hash of a
        // model the user has navigated away from must not pin the worker.
        val prepared =
            when (val outcome = pinAndVerify(req.binding, VerifyCancellation { unloadRequested.get() })) {
                is VerifyOutcome.Refused -> return refuse(outcome.refusal)
                is VerifyOutcome.Verified -> outcome
            }
        val pinnedModel = prepared.pinned
        val binding = prepared.binding

        synchronized(lock) { state = EngineState.LOADING }
        return try {
            val handles =
                worker.submitBlocking {
                    // bd skein-gg11.2 (OL-19): start capturing WARN/ERROR log
                    // lines immediately before the native load call, so a
                    // failure below can attach the first few, redacted.
                    backend.beginLoadLogCapture()
                    val model =
                        backend.loadModelFromFd(
                            fd = pinnedModel.main.descriptorNumber,
                            nGpuLayers = req.gpuLayers,
                            useMmap = true,
                        )
                    val context =
                        try {
                            backend.newContext(
                                model = model,
                                nCtx = req.contextLength,
                                nThreads = req.threads,
                                nBatch = PROMPT_BATCH_TOKENS,
                                embeddings = req.embeddingMode,
                            )
                        } catch (e: LlamaException) {
                            backend.freeModel(model)
                            throw e
                        }
                    model to context
                }
            synchronized(lock) {
                loaded =
                    LoadedModel(
                        pinned = pinnedModel,
                        model = handles.first,
                        context = handles.second,
                        contextLength = req.contextLength,
                        modelSha256 = binding.main.expectedSha256,
                        embeddingMode = req.embeddingMode,
                        gpuLayers = req.gpuLayers,
                    )
                state = EngineState.READY
                lastUsedChatTemplateFallback = false
            }
            SkeinLog.i(TAG, "model loaded files=${req.binding.files.size} ctx=${req.contextLength}")
            ErrorCode.OK
        } catch (e: LlamaException) {
            pinnedModel.close()
            synchronized(lock) { state = EngineState.UNLOADED }
            val detail = ServiceErrorMapping.loadFailureDetail(e, backend.drainLoadLogLines())
            SkeinLog.w(TAG, "load failed: $detail")
            ServiceErrorMapping.toErrorCode(e)
        }
    }

    // ----------------------------------------------------------- inspect

    /**
     * H1 (`skein-91yy`), `docs/design/SKEIN_HUB.md` §3.3.
     *
     * Verifies the binding through exactly the code path [load] verifies with
     * — [pinAndVerify] is that path, and it is called from both, so "the same
     * gate" is structural rather than a promise two functions make separately
     * — then loads the MODEL ONLY, reads its metadata, and frees it.
     *
     * NO CONTEXT. `newContext` is the expensive half of a load: it allocates a
     * KV cache sized to `contextLength`, hundreds of megabytes, for a model
     * the user has not yet agreed to use. An OOM there would also be
     * indistinguishable from an OOM during a real load. `gpuLayers` is 0 for
     * the same reason — offloading weights to the GPU for a metadata read
     * would reserve VRAM and return nothing for it.
     *
     * LEAVES THE SERVICE AS IT FOUND IT. A model loaded before this call is
     * still loaded, with the same handles and the same KV cache, after it: the
     * inspection's own model handle is separate and is freed here. [state] is
     * never touched, so a concurrent `status()` does not see an inspection as
     * a load. What it will not do is queue behind a generation — the single
     * worker thread owns every native call, so an inspection posted during one
     * would hold a binder thread for the whole generation. It answers
     * [ErrorCode.BUSY] instead, which is the same answer a second `generate`
     * gets and for the same reason.
     *
     * Refusals are a [ModelInspection.errorCode], never an exception: this
     * call's whole purpose is to let `ModelManager` decide whether to accept a
     * file, and "it did not verify" is an answer to that question.
     */
    fun inspect(req: InspectRequest): ModelInspection {
        if (gate.guard(req.sessionEpoch) is GateResult.Refuse) {
            closeReceived(req.binding)
            return ModelInspection.refused(ErrorCode.SESSION_LOCKED)
        }
        if (synchronized(lock) { active != null }) {
            closeReceived(req.binding)
            return ModelInspection.refused(ErrorCode.BUSY)
        }

        // Not `unloadRequested`: that flag belongs to the load path, where it
        // stays raised after an `unload` until the next load lowers it, and an
        // inspection holds no engine state for an `unload` to reclaim.
        val prepared =
            when (val outcome = pinAndVerify(req.binding, VerifyCancellation { false })) {
                is VerifyOutcome.Refused -> {
                    SkeinLog.w(TAG, "inspect refused: ${ServiceErrorMapping.diagnostic(outcome.refusal)}")
                    return ModelInspection.refused(ServiceErrorMapping.toErrorCode(outcome.refusal))
                }

                is VerifyOutcome.Verified -> outcome
            }

        return try {
            worker.submitBlocking { readInspection(prepared.pinned) }
        } catch (e: LlamaException) {
            // bd skein-gg11.2 (OL-19): `readInspection` began capturing
            // before its own `loadModelFromFd`, so the same redacted-detail
            // treatment applies to an inspection that fails to load.
            val detail = ServiceErrorMapping.loadFailureDetail(e, backend.drainLoadLogLines())
            SkeinLog.w(TAG, "inspect failed: $detail")
            ModelInspection.refused(ServiceErrorMapping.toErrorCode(e))
        } finally {
            // The inspection's descriptors die with it. Nothing outlives the
            // call — that is what "leaves the service in whatever state it was
            // in" means for fds as well as for handles.
            prepared.pinned.close()
        }
    }

    /**
     * Loads [pinned]'s main file as a model, reads every field
     * [ModelInspection] carries, and frees the model.
     *
     * Runs on the worker: `loadModelFromFd` and `freeModel` are the worker's
     * calls (`LlamaNative`'s threading notes), and doing the reads there too
     * keeps the model's whole lifetime on one thread.
     */
    private fun readInspection(pinned: PinnedModel): ModelInspection {
        // bd skein-gg11.2 (OL-19): see the identical comment in `load`.
        backend.beginLoadLogCapture()
        val model =
            backend.loadModelFromFd(
                fd = pinned.main.descriptorNumber,
                nGpuLayers = 0,
                useMmap = true,
            )
        return try {
            val architecture = backend.modelMeta(model, KEY_ARCHITECTURE)
            ModelInspection(
                errorCode = ErrorCode.OK,
                architecture = architecture,
                quantization = backend.modelMeta(model, KEY_FILE_TYPE)?.let { GgufFileType.render(it) },
                // Opportunistic: llama.cpp's own writer emits no such key, and
                // the real count needs `llama_model_n_params`, which has no
                // JNI entry point. H1 adds no native code — see skein-91yy.
                parameterCount = backend.modelMeta(model, KEY_PARAMETER_COUNT)?.trim()?.toLongOrNull(),
                contextLength =
                    architecture
                        ?.let { backend.modelMeta(model, "$it$KEY_SUFFIX_CONTEXT_LENGTH") }
                        ?.trim()
                        ?.toIntOrNull(),
                embeddingWidth = backend.modelNEmbd(model),
                hasVision = backend.modelHasVision(model),
                hasChatTemplate = backend.modelMeta(model, KEY_CHAT_TEMPLATE) != null,
                chatTemplateOk = chatTemplateApplies(model),
                tokenizerModel = backend.modelMeta(model, KEY_TOKENIZER_MODEL),
            )
        } finally {
            runCatching { backend.freeModel(model) }
        }
    }

    /**
     * Whether `llama_chat_apply_template` can render a two-turn conversation.
     *
     * The probe is two fixed ASCII strings. It must never be built from
     * anything the caller supplied: this runs before the model is accepted, so
     * the one thing it may not do is hand attacker-influenced text to a
     * template renderer it is trying to decide it can trust.
     */
    private fun chatTemplateApplies(model: Long): Boolean =
        try {
            backend
                .applyChatTemplate(model, TEMPLATE_PROBE_ROLES, TEMPLATE_PROBE_TURNS, true)
                .isNotEmpty()
        } catch (e: LlamaException) {
            // TEMPLATE_UNSUPPORTED is the expected outcome for a GGUF with no
            // template, and E4.I6's ChatML fallback covers it — a false here,
            // not a refusal (§3.3's structural-checks table).
            SkeinLog.i(TAG, "chat template probe declined: ${ServiceErrorMapping.diagnostic(e)}")
            false
        }

    // ------------------------------------------------------------ generate

    fun generate(
        req: GenerateRequest,
        cb: IInferenceCallback,
    ) {
        if (gate.guard(req.sessionEpoch) is GateResult.Refuse) {
            closeAttachments(req)
            fail(cb, req.requestId, ErrorCode.SESSION_LOCKED, "session is locked")
            return
        }

        val model =
            synchronized(lock) { loaded } ?: run {
                closeAttachments(req)
                fail(cb, req.requestId, ErrorCode.NOT_LOADED, "no model is loaded")
                return
            }

        val request = ActiveRequest(req.requestId, cb, req.sessionEpoch, model.context)
        synchronized(lock) {
            if (active != null) {
                // Only the SECOND request is refused; the first keeps streaming.
                closeAttachments(req)
                fail(cb, req.requestId, ErrorCode.BUSY, "a request is already in flight")
                return
            }
            active = request
            state = EngineState.GENERATING
        }
        linkToDeath(request)

        // Messages are read (and their spill descriptors closed) before the
        // worker starts, so the fds do not outlive a rejected request.
        val contents =
            try {
                req.messages.map { readContent(it) }
            } catch (e: IOException) {
                finish(request)
                closeAttachments(req)
                fail(cb, req.requestId, ErrorCode.INTERNAL, "could not read a spilled message")
                return
            }
        // E4.I11 owns images; until then attachments are consumed and closed
        // rather than leaked, per §3.2 rule 3.
        closeAttachments(req)

        worker.post { runGeneration(request, req, model, contents) }
    }

    private fun runGeneration(
        request: ActiveRequest,
        req: GenerateRequest,
        model: LoadedModel,
        contents: List<String>,
    ) {
        val startedAt = SystemClock.elapsedRealtime()
        var sampler = 0L
        try {
            // E-4 (OfflineLLM review, `research/upstream/offlinellm/KV_CACHE_ANALYSIS.md`).
            // Every request starts its prompt at position 0, so every cell the
            // previous request left in the cache is stale. Correctness rested
            // on llama.cpp purging a cell when a later decode overwrites its
            // position — an invariant of somebody else's implementation, never
            // asserted on a device, and one whose failure mode is the previous
            // conversation leaking into this answer. Clearing is explicit,
            // costs one `llama_memory_clear`, and also means the KV pages do
            // not hold the last session's plaintext token state between
            // requests (LOCK_POLICY_INDEXING.md §4.5's reasoning, applied
            // between turns rather than only at free).
            backend.kvClear(model.context)

            // E4.I6 (skein-5oi): the model's own template, or the ChatML
            // fallback when the GGUF embeds none llama.cpp can apply. A
            // missing template is a warning `status()` surfaces, not a
            // refusal.
            val renderedPrompt =
                ChatTemplating.render(
                    backend,
                    model.model,
                    req.messages.map { it.role }.toTypedArray(),
                    contents.toTypedArray(),
                    addAssistantPrefix = true,
                )
            synchronized(lock) { lastUsedChatTemplateFallback = renderedPrompt.usedFallback }
            val rendered = renderedPrompt.text
            // skein-0ztk: scaffolding with parseSpecial=true, CONTENT with
            // parseSpecial=false, so a note containing the model's own control
            // token text cannot forge a chat turn.
            val layout = ChatTemplating.segmentDetailed(rendered, contents)
            val tokenized = ChatTemplating.tokenizeDetailed(backend, model.model, layout.segments)
            val promptIds = tokenized.ids
            // skein-gg11.28, numbers only (spec §9): how the render was split
            // and how many ids each side produced. `closed=true` is
            // ChatTemplating's fail-closed path — the chrome went in as
            // ordinary text and the answer will read like a raw continuation.
            val chrome = layout.scaffoldSpans
            val data = layout.contentSpans
            val nChrome = tokenized.scaffoldIds
            val nData = tokenized.contentIds
            val closed = layout.failedClosed
            val tmplFallback = renderedPrompt.usedFallback
            SkeinLog.i(
                TAG,
                "prefill layout: chrome=$chrome data=$data n_chrome=$nChrome n_data=$nData closed=$closed tmpl_fallback=$tmplFallback",
            )

            var nPast = 0
            var index = 0
            // Numbers only (spec §9): the Fold's first real prompt sat in this
            // loop for ten minutes with nothing in logcat to say how big it
            // was or how fast a chunk went (skein-gg11.24).
            val chunkCount = (promptIds.size + PROMPT_BATCH_TOKENS - 1) / PROMPT_BATCH_TOKENS
            SkeinLog.i(
                TAG,
                "prefill start: n_in=${promptIds.size} batches=$chunkCount ctx=${model.contextLength}",
            )
            var chunkIndex = 0
            while (index < promptIds.size) {
                if (request.cancelled.get()) {
                    complete(request, StopReasons.CANCELLED, promptIds.size, 0, 0L, startedAt)
                    return
                }
                val end = minOf(index + PROMPT_BATCH_TOKENS, promptIds.size)
                val chunkStarted = SystemClock.elapsedRealtime()
                nPast = backend.decodePrompt(model.context, promptIds.copyOfRange(index, end), nPast)
                chunkIndex++
                val chunkMs = SystemClock.elapsedRealtime() - chunkStarted
                SkeinLog.i(TAG, "prefill batch $chunkIndex/$chunkCount: n=${end - index} ms=$chunkMs")
                index = end
            }

            sampler =
                backend.newSampler(
                    temp = req.sampling.temperature,
                    topK = req.sampling.topK,
                    topP = req.sampling.topP,
                    minP = req.sampling.minP,
                    repeatPenalty = req.sampling.repeatPenalty,
                    seed = req.sampling.seed,
                )

            val batcher =
                TokenBatcher(
                    nowNanos = System::nanoTime,
                    inFlightBatches = { callbacks.inFlight },
                    emit = { batch -> send(request, batch) },
                )
            val utf8 = Utf8Buffer()
            val stopper = StopStringMatcher(req.sampling.stop)
            val held = mutableListOf<Pair<String, Int>>()

            var generated = 0
            var ttftMs = 0L
            var stopReason = StopReasons.LENGTH

            while (generated < req.sampling.maxTokens) {
                if (request.cancelled.get()) {
                    stopReason = StopReasons.CANCELLED
                    break
                }
                val id = backend.sampleNext(model.context, sampler)
                if (backend.isEog(model.model, id)) {
                    stopReason = StopReasons.EOS
                    break
                }
                generated++
                if (ttftMs == 0L) ttftMs = SystemClock.elapsedRealtime() - startedAt

                val text = utf8.append(backend.tokenToPieceBytes(model.model, id))
                if (text.isNotEmpty()) {
                    if (stopper.append(text) != null) {
                        // Nothing of the marker has been emitted: everything
                        // since the match began is still in `held`, which we
                        // drop. The client never sees the marker it stopped on.
                        held.clear()
                        stopReason = StopReasons.STOP_STRING
                        break
                    }
                    held += text to id
                    if (!stopper.mayBeMidMatch()) {
                        held.forEach { (piece, pieceId) -> batcher.offer(piece, pieceId) }
                        held.clear()
                    }
                }
                nPast = backend.decodePrompt(model.context, intArrayOf(id), nPast)
            }

            if (stopReason != StopReasons.STOP_STRING) {
                held.forEach { (piece, pieceId) -> batcher.offer(piece, pieceId) }
                val tail = utf8.flush()
                if (tail.isNotEmpty()) batcher.offer(tail, 0)
            }
            batcher.flush()
            complete(request, stopReason, promptIds.size, generated, ttftMs, startedAt)
        } catch (e: LlamaException) {
            when (e.code) {
                LlamaErrorCode.CANCELLED ->
                    complete(request, StopReasons.CANCELLED, 0, 0, 0L, startedAt)

                LlamaErrorCode.CONTEXT_FULL ->
                    complete(request, StopReasons.LENGTH, 0, 0, 0L, startedAt)

                LlamaErrorCode.OUT_OF_MEMORY -> {
                    // The bead is explicit: an allocation failure during decode
                    // is answered with OOM AND an unload. Staying loaded after
                    // the device refused us memory only guarantees the next
                    // request fails the same way, more slowly.
                    finish(request)
                    fail(request.callback, request.requestId, ErrorCode.OOM, ServiceErrorMapping.diagnostic(e))
                    unload()
                    return
                }

                else -> {
                    finish(request)
                    fail(
                        request.callback,
                        request.requestId,
                        ServiceErrorMapping.toErrorCode(e),
                        ServiceErrorMapping.diagnostic(e),
                    )
                    return
                }
            }
        } finally {
            if (sampler != 0L) runCatching { backend.freeSampler(sampler) }
            runCatching { backend.setCancelFlag(model.context, false) }
        }
    }

    // -------------------------------------------------------------- cancel

    fun cancel(requestId: Int) {
        val request = synchronized(lock) { active?.takeIf { it.requestId == requestId } } ?: return
        request.cancelled.set(true)
        // From a binder thread, concurrently with the worker inside
        // llama_decode. That is what the native atomic flag is for.
        runCatching { backend.setCancelFlag(request.context, true) }
    }

    private fun cancelInFlight() {
        val request = synchronized(lock) { active } ?: return
        request.cancelled.set(true)
        runCatching { backend.setCancelFlag(request.context, true) }
    }

    // -------------------------------------------------------------- unload

    fun unload() {
        unloadRequested.set(true)
        cancelInFlight()
        val model =
            synchronized(lock) {
                val current = loaded ?: return@synchronized null
                loaded = null
                state = EngineState.UNLOADED
                current
            }
        if (model == null) {
            synchronized(lock) { if (loaded == null) state = EngineState.UNLOADED }
            return
        }
        worker.submitBlocking {
            // freeContextSecure, never freeContext: llama_free returns the KV
            // pages to the allocator with this session's plaintext token state
            // still in them (LOCK_POLICY_INDEXING.md §4.5).
            runCatching { backend.freeContextSecure(model.context) }
            runCatching { backend.freeModel(model.model) }
        }
        model.pinned.close()
        SkeinLog.i(TAG, "model unloaded")
    }

    private fun releaseOnLock() {
        unload()
    }

    // ---------------------------------------------------------- embed etc.

    fun embed(req: EmbedRequest): FloatArray {
        if (gate.guard(req.sessionEpoch) is GateResult.Refuse) {
            req.inputFd?.let { closeQuietly(it.fd) }
            throw ErrorCodes.asServiceFailure(ErrorCode.SESSION_LOCKED, "session is locked")
        }
        val texts =
            try {
                readTexts(req)
            } catch (e: IOException) {
                throw ErrorCodes.asServiceFailure(ErrorCode.INTERNAL, "could not read the embed inputs")
            }
        val model =
            synchronized(lock) { loaded }
                ?: throw ErrorCodes.asServiceFailure(ErrorCode.NOT_LOADED, "no model is loaded")
        if (!model.embeddingMode) {
            throw ErrorCodes.asServiceFailure(ErrorCode.INVALID_MODEL, "the loaded model has no embedding capability")
        }
        if (texts.size > TransportRules.MAX_EMBED_TEXTS) {
            throw ErrorCodes.asServiceFailure(ErrorCode.TX_TOO_LARGE, "too many texts in one embed call")
        }
        return try {
            worker.submitBlocking {
                val out = mutableListOf<Float>()
                for (text in texts) {
                    val tokens = backend.tokenize(model.model, text, addBos = true, parseSpecial = false)
                    backend.embed(model.context, tokens).forEach { out += it }
                }
                out.toFloatArray()
            }
        } catch (e: LlamaException) {
            throw ErrorCodes.asServiceFailure(ServiceErrorMapping.toErrorCode(e), ServiceErrorMapping.diagnostic(e))
        }
    }

    /**
     * `tokenCount` has no `sessionEpoch` — its AIDL signature is
     * `int tokenCount(String)` and widening it would not be additive. It still
     * touches plaintext, so it is gated on "some session is authorized", which
     * is the strongest check the signature admits. Recorded on skein-nxk.
     */
    fun tokenCount(text: String): Int {
        if (gate.authorized == SessionEpoch.NONE) {
            throw ErrorCodes.asServiceFailure(ErrorCode.SESSION_LOCKED, "session is locked")
        }
        val model =
            synchronized(lock) { loaded }
                ?: throw ErrorCodes.asServiceFailure(ErrorCode.NOT_LOADED, "no model is loaded")
        return try {
            worker.submitBlocking {
                backend.tokenize(model.model, text, addBos = false, parseSpecial = false).size
            }
        } catch (e: LlamaException) {
            throw ErrorCodes.asServiceFailure(ServiceErrorMapping.toErrorCode(e), ServiceErrorMapping.diagnostic(e))
        }
    }

    fun status(): EngineStatus =
        synchronized(lock) {
            EngineStatus(
                state = state,
                modelSha256 = loaded?.modelSha256,
                contextLength = loaded?.contextLength ?: 0,
                tokensPerSec = lastTokensPerSec,
                usedChatTemplateFallback = lastUsedChatTemplateFallback,
            )
        }

    // ------------------------------------------------------- backend report

    /**
     * bd skein-gg11.2 (OL-05, `docs/design/SKEIN_HUB.md` §12). Works with a
     * loaded model and without one: with nothing loaded, `model`/`context`
     * are `0` and the native side reports the compile-time CPU feature list
     * only (see `LlamaNative.backendReport`'s KDoc).
     *
     * Routed through the worker like every other native call touching a
     * live context — deliberately NOT refused with BUSY: unlike `inspect`,
     * this reads existing state rather than allocating, so it is safe to
     * let it wait behind an in-flight generation rather than adding a
     * second refusal path.
     */
    fun backendReport(req: BackendReportRequest): BackendReport {
        if (gate.guard(req.sessionEpoch) is GateResult.Refuse) {
            return BackendReport.refused(ErrorCode.SESSION_LOCKED)
        }
        val current = synchronized(lock) { loaded }
        val native =
            worker.submitBlocking {
                backend.backendReport(
                    model = current?.model ?: 0L,
                    context = current?.context ?: 0L,
                    gpuLayers = current?.gpuLayers ?: 0,
                )
            }
        return BackendReport(
            errorCode = ErrorCode.OK,
            devices = native.devices.map { BackendDeviceParcel(type = it.type, name = it.name) },
            cpuFeatures = native.cpuFeatures,
            gpuLayersOffloaded = native.gpuLayersOffloaded,
            nOutputsMax = native.nOutputsMax,
            nBatch = native.nBatch,
            nUbatch = native.nUbatch,
        )
    }

    // ------------------------------------------------------------ lock gate

    fun onSessionLocking(
        epoch: Long,
        budgetMillis: Long,
    ) = gate.onLocking(epoch, budgetMillis)

    fun onSessionLocked(epoch: Long) = gate.onLocked(epoch)

    fun onSessionUnlocked(epoch: Long) = gate.onUnlocked(epoch)

    fun shutdown() {
        unload()
    }

    // ------------------------------------------------------------- helpers

    /** What [pinAndVerify] decided. */
    private sealed interface VerifyOutcome {
        /** Every file pinned and verified; [pinned] is open and the caller owns it. */
        class Verified(
            val pinned: PinnedModel,
            val binding: VerifyBinding,
        ) : VerifyOutcome

        /** Nothing is open: every descriptor was closed before this was returned. */
        class Refused(
            val refusal: ModelVerification.Refusal,
        ) : VerifyOutcome
    }

    /**
     * POST_REVIEW_RESOLUTIONS.md §2's load gate, once, for both entry points
     * that open a model file.
     *
     * `load` and `inspect` MUST verify identically — §3.3's whole argument for
     * inspecting inside the isolated process is that the acceptance decision
     * and the load run the same gate over the same descriptors — so they call
     * this rather than each carrying a copy that could drift.
     *
     * On [VerifyOutcome.Refused] nothing is left open: the pin loop closes the
     * descriptors it was handed either way, and `verifyPinned` closes the pins
     * on refusal, so no file can reach llama.cpp after a mismatch even if the
     * caller mishandles the result.
     */
    private fun pinAndVerify(
        binding: ManifestBinding,
        cancellation: VerifyCancellation,
    ): VerifyOutcome {
        // Origin trust is a soft gate (POST_REVIEW_RESOLUTIONS.md §2.5) and
        // E3.I6 has not landed, so the bundle is closed unread rather than
        // leaked. It must never block a load the digest gate would allow.
        binding.attestation?.let { closeQuietly(it.bundleFd) }

        val pins = mutableMapOf<ModelFileRole, PinnedModelFile>()
        var refusal: ModelVerification.Refusal? = null
        for (ref in binding.files) {
            val role = ModelFileRole.fromWire(ref.role)
            if (role == null) {
                refusal = ModelVerification.CompanionMissing(null, ref.role)
                break
            }
            when (val pin = pinDescriptor(ref.fd)) {
                is PinResult.Refused -> {
                    refusal = pin.refusal
                    break
                }

                is PinResult.Pinned -> pins[role] = pin.file
            }
        }
        // §3.2 rule 3: we own every descriptor we were handed, and the pins
        // hold duplicates. Close the originals whether or not pinning worked.
        closeAll(binding.files)

        if (refusal != null) {
            pins.values.forEach { it.close() }
            return VerifyOutcome.Refused(refusal)
        }
        val mainPin = pins[ModelFileRole.MAIN]
        if (mainPin == null) {
            pins.values.forEach { it.close() }
            return VerifyOutcome.Refused(ModelVerification.CompanionMissing(ModelFileRole.MAIN, "main"))
        }
        val verifyBinding =
            verifyBinding(binding.files) ?: run {
                pins.values.forEach { it.close() }
                return VerifyOutcome.Refused(
                    ModelVerification.MalformedManifest("binding does not describe a loadable model"),
                )
            }

        val pinnedModel = PinnedModel(mainPin, pins - ModelFileRole.MAIN)
        return when (
            val verified =
                ModelVerifier.verifyPinned(
                    model = pinnedModel,
                    binding = verifyBinding,
                    cancellation = cancellation,
                    progress = VerifyProgress { /* `load` already set state to "verifying"; see status() */ },
                )
        ) {
            // verifyPinned already closed every descriptor on refusal, so
            // there is nothing left that could reach llama.cpp even if this
            // code were wrong about the rest.
            is PinnedLoad.Refused -> VerifyOutcome.Refused(verified.refusal)
            is PinnedLoad.Ready -> VerifyOutcome.Verified(pinnedModel, verifyBinding)
        }
    }

    /** §3.2 rule 3 for a binding we are refusing before we pin anything. */
    private fun closeReceived(binding: ManifestBinding) {
        closeAll(binding.files)
        binding.attestation?.let { closeQuietly(it.bundleFd) }
    }

    private fun refuse(refusal: ModelVerification.Refusal): Int {
        synchronized(lock) { state = EngineState.UNLOADED }
        SkeinLog.w(TAG, "load refused: ${ServiceErrorMapping.diagnostic(refusal)}")
        return ServiceErrorMapping.toErrorCode(refusal)
    }

    private fun pinDescriptor(received: ParcelFileDescriptor): PinResult =
        PinnedModelFile.pin {
            val duplicate = received.dup()
            DupedDescriptor(duplicate.fileDescriptor, duplicate.fd) { duplicate.close() }
        }

    /**
     * The verifier's view of the wire binding.
     *
     * `expectedBlake3` is null for every file: `ManifestFileRef` carries no
     * BLAKE3, so the post-mmap gate compares the mapping against the BLAKE3 the
     * pre-mmap pass observed over the same descriptor. `path` is null because
     * this process has none and must never acquire one.
     */
    private fun verifyBinding(files: List<ManifestFileRef>): VerifyBinding? {
        val verifyFiles =
            files.mapNotNull { ref ->
                val role = ModelFileRole.fromWire(ref.role) ?: return@mapNotNull null
                VerifyFile(
                    role = role,
                    expectedSha256 = ref.expectedSha256,
                    expectedSizeBytes = ref.expectedSizeBytes,
                    expectedBlake3 = null,
                    path = null,
                )
            }
        if (verifyFiles.size != files.size) return null
        return runCatching { VerifyBinding(verifyFiles) }.getOrNull()
    }

    private fun linkToDeath(request: ActiveRequest) {
        val recipient =
            IBinder.DeathRecipient {
                // The client is gone. Cancel rather than keep burning the
                // device's battery producing tokens nobody will read.
                request.cancelled.set(true)
                runCatching { backend.setCancelFlag(request.context, true) }
            }
        request.deathRecipient = recipient
        runCatching { request.callback.asBinder().linkToDeath(recipient, 0) }
    }

    private fun finish(request: ActiveRequest) {
        request.deathRecipient?.let { recipient ->
            runCatching { request.callback.asBinder().unlinkToDeath(recipient, 0) }
        }
        synchronized(lock) {
            if (active === request) {
                active = null
                if (loaded != null) state = EngineState.READY
            }
        }
    }

    private fun complete(
        request: ActiveRequest,
        stopReason: String,
        promptTokens: Int,
        generated: Int,
        ttftMs: Long,
        startedAt: Long,
    ) {
        val elapsed = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1L)
        val rate = generated * MILLIS_PER_SECOND / elapsed.toFloat()
        synchronized(lock) { lastTokensPerSec = rate }
        SkeinLog.i(
            TAG,
            "generation done: reason=$stopReason n_in=$promptTokens n_out=$generated " +
                "ttft=${ttftMs}ms elapsed=${elapsed}ms rate=${"%.2f".format(rate)}/s",
        )
        finish(request)
        val stats =
            GenStats(
                stopReason = stopReason,
                promptTokens = promptTokens,
                generatedTokens = generated,
                ttftMs = ttftMs,
                tokensPerSec = rate,
            )
        callbacks.deliver { request.callback.onDone(request.requestId, stats) }
    }

    private fun send(
        request: ActiveRequest,
        batch: TokenBatch,
    ) = callbacks.deliver {
        request.callback.onTokens(request.requestId, batch.pieces, batch.ids, batch.dropped)
    }

    private fun fail(
        callback: IInferenceCallback,
        requestId: Int,
        code: Int,
        message: String,
    ) = callbacks.deliver {
        try {
            callback.onError(requestId, code, message)
        } catch (e: RemoteException) {
            @Suppress("UNUSED_EXPRESSION")
            e
        }
    }

    /** Inline content, or the spilled content the message's descriptor carries (J5). */
    @Throws(IOException::class)
    private fun readContent(message: ChatMessageParcel): String {
        val spill = message.contentFd ?: return message.content
        return readUtf8(spill)
    }

    @Throws(IOException::class)
    private fun readTexts(req: EmbedRequest): List<String> {
        val spill = req.inputFd ?: return req.texts
        return readUtf8(spill).split('\u0000').filter { it.isNotEmpty() }
    }

    /** Reads and CLOSES [ref] — §3.2 rule 3, whatever happens next. */
    @Throws(IOException::class)
    private fun readUtf8(ref: SharedMemRef): String =
        try {
            FileInputStream(ref.fd.fileDescriptor).use { stream ->
                val limit = ref.sizeBytes.coerceAtMost(TransportRules.INLINE_REFUSE_BYTES.toLong()).toInt()
                val bytes = ByteArray(limit)
                var read = 0
                while (read < limit) {
                    val n = stream.read(bytes, read, limit - read)
                    if (n < 0) break
                    read += n
                }
                String(bytes, 0, read, Charsets.UTF_8)
            }
        } finally {
            closeQuietly(ref.fd)
        }

    private fun closeAttachments(req: GenerateRequest) {
        req.attachmentFds.forEach { closeQuietly(it.fd) }
        req.messages.forEach { message -> message.contentFd?.let { closeQuietly(it.fd) } }
    }

    private fun closeAll(files: List<ManifestFileRef>) = files.forEach { closeQuietly(it.fd) }

    private fun closeQuietly(fd: ParcelFileDescriptor) {
        runCatching { fd.close() }
    }

    private companion object {
        /** Plan E4.I3: the prompt is decoded in batches of 512. */
        const val PROMPT_BATCH_TOKENS = 512

        const val MILLIS_PER_SECOND = 1_000f

        // GGUF metadata keys (`gguf-py/gguf/constants.py`'s `Keys.General` /
        // `Keys.LLM` / `Keys.Tokenizer`), read through `modelMeta`. H1.
        const val KEY_ARCHITECTURE = "general.architecture"
        const val KEY_FILE_TYPE = "general.file_type"

        /** Non-standard; see [readInspection]. Null for llama.cpp's own output. */
        const val KEY_PARAMETER_COUNT = "general.parameter_count"

        /** Appended to the architecture: `llama.context_length`, `gemma3.context_length`, … */
        const val KEY_SUFFIX_CONTEXT_LENGTH = ".context_length"
        const val KEY_CHAT_TEMPLATE = "tokenizer.chat_template"
        const val KEY_TOKENIZER_MODEL = "tokenizer.ggml.model"

        /** The two-turn probe behind `ModelInspection.chatTemplateOk`. Fixed strings, never input. */
        val TEMPLATE_PROBE_ROLES = arrayOf("user", "assistant")
        val TEMPLATE_PROBE_TURNS = arrayOf("probe", "probe")
    }
}
