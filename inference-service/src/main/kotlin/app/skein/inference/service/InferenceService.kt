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
import app.skein.ipc.ChatMessageParcel
import app.skein.ipc.EmbedRequest
import app.skein.ipc.EngineStatus
import app.skein.ipc.ErrorCode
import app.skein.ipc.ErrorCodes
import app.skein.ipc.GenStats
import app.skein.ipc.GenerateRequest
import app.skein.ipc.IInferenceCallback
import app.skein.ipc.IInferenceService
import app.skein.ipc.LoadRequest
import app.skein.ipc.ManifestFileRef
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

    /** Raised while an `unload` is waiting for a verification to notice. */
    private val unloadRequested = AtomicBoolean(false)

    private class LoadedModel(
        val pinned: PinnedModel,
        val model: Long,
        val context: Long,
        val contextLength: Int,
        val modelSha256: String,
        val embeddingMode: Boolean,
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
            closeAll(req.binding.files)
            req.binding.attestation?.let { closeQuietly(it.bundleFd) }
            return ErrorCode.SESSION_LOCKED
        }

        // Warm swap: a second load while loaded unloads first. Done before
        // pinning so the old model's descriptors and KV cache are gone before
        // the new model's allocation is attempted — the device may not have
        // room for both.
        unload()

        // Origin trust is a soft gate (POST_REVIEW_RESOLUTIONS.md §2.5) and
        // E3.I6 has not landed, so the bundle is closed unread rather than
        // leaked. It must never block a load the digest gate would allow.
        req.binding.attestation?.let { closeQuietly(it.bundleFd) }

        val pins = mutableMapOf<ModelFileRole, PinnedModelFile>()
        var refusal: ModelVerification.Refusal? = null
        for (ref in req.binding.files) {
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
        closeAll(req.binding.files)

        if (refusal != null) {
            pins.values.forEach { it.close() }
            return refuse(refusal)
        }
        val mainPin = pins[ModelFileRole.MAIN]
        if (mainPin == null) {
            pins.values.forEach { it.close() }
            return refuse(ModelVerification.CompanionMissing(ModelFileRole.MAIN, "main"))
        }

        val binding =
            verifyBinding(req.binding.files) ?: run {
                pins.values.forEach { it.close() }
                return refuse(ModelVerification.MalformedManifest("binding does not describe a loadable model"))
            }
        val pinnedModel = PinnedModel(mainPin, pins - ModelFileRole.MAIN)

        synchronized(lock) { state = EngineState.VERIFYING }
        unloadRequested.set(false)
        val verified =
            ModelVerifier.verifyPinned(
                model = pinnedModel,
                binding = binding,
                cancellation = VerifyCancellation { unloadRequested.get() },
                progress = VerifyProgress { /* state is already "verifying"; see status() */ },
            )
        when (verified) {
            is PinnedLoad.Refused -> {
                // verifyPinned already closed every descriptor on refusal, so
                // there is nothing left that could reach llama.cpp even if this
                // code were wrong about the rest.
                return refuse(verified.refusal)
            }

            is PinnedLoad.Ready -> Unit
        }

        synchronized(lock) { state = EngineState.LOADING }
        return try {
            val handles =
                worker.submitBlocking {
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
                    )
                state = EngineState.READY
            }
            SkeinLog.i(TAG, "model loaded files=${req.binding.files.size} ctx=${req.contextLength}")
            ErrorCode.OK
        } catch (e: LlamaException) {
            pinnedModel.close()
            synchronized(lock) { state = EngineState.UNLOADED }
            SkeinLog.w(TAG, "load failed: ${ServiceErrorMapping.diagnostic(e)}")
            ServiceErrorMapping.toErrorCode(e)
        }
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
            val rendered =
                backend.applyChatTemplate(
                    model.model,
                    req.messages.map { it.role }.toTypedArray(),
                    contents.toTypedArray(),
                    true,
                )
            // skein-0ztk: scaffolding with parseSpecial=true, CONTENT with
            // parseSpecial=false, so a note containing the model's own control
            // token text cannot forge a chat turn.
            val promptIds = ChatTemplating.tokenize(backend, model.model, ChatTemplating.segment(rendered, contents))

            var nPast = 0
            var index = 0
            while (index < promptIds.size) {
                if (request.cancelled.get()) {
                    complete(request, StopReasons.CANCELLED, promptIds.size, 0, 0L, startedAt)
                    return
                }
                val end = minOf(index + PROMPT_BATCH_TOKENS, promptIds.size)
                nPast = backend.decodePrompt(model.context, promptIds.copyOfRange(index, end), nPast)
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
    }
}
