// skein-1uw (E4.I4) — the hand-written `IInferenceService` the JVM suite runs
// `LlamaCppEngine` against (plan E4.I4 Step 1: "JVM tests with a hand-written
// `IInferenceService.Stub` fake").
//
// It implements the AIDL INTERFACE rather than extending `Stub`: nothing here
// crosses a process, so the Binder plumbing would only add a marshalling step
// the tests do not assert on. What it does faithfully reproduce is the
// service's OBSERVABLE contract, because that is what the engine is written
// against:
//
//   * one request in flight, a second refused with `ErrorCode.BUSY`;
//   * `generate` reports failure through `IInferenceCallback.onError`, never by
//     throwing, and delivers exactly one terminal callback per requestId;
//   * `embed`/`tokenCount` have no error slot and throw the coded
//     `IllegalStateException` `ErrorCodes.asServiceFailure` defines (judgment
//     call J7);
//   * every fd it receives is read and CLOSED, on success and refusal alike
//     (POST_REVIEW_RESOLUTIONS.md §3.2 rule 3), which is what makes the
//     engine's own descriptor bookkeeping observable.
//
// Callbacks are delivered from a worker thread, like the real service's
// single worker — a fake that called back inline would let the engine pass
// tests a real service would fail, since nothing would ever be in flight.

package app.skein.core.inference.engine

import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
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
import app.skein.ipc.ModelInspection
import app.skein.ipc.TransportRules
import java.io.FileInputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One message as the service saw it: the role, and the content whether inline or spilled. */
internal data class SeenMessage(
    val role: String,
    val content: String,
    val spilled: Boolean,
)

internal class FakeInferenceService : IInferenceService {
    // ------------------------------------------------------------ scripting

    /** What `load` returns. */
    var loadCode: Int = ErrorCode.OK

    /** What `generate` streams before finishing, one `Token.Text` per entry. */
    var pieces: List<String> = listOf("he", "llo")

    /** When set, `generate` reports this instead of streaming. */
    var generateError: Pair<Int, String>? = null

    /** How long the worker waits before the terminal callback. */
    var completionDelayMillis: Long = 200L

    /** Runs inside `load`, before it returns — lets a test hold the engine in LOADING. */
    var beforeLoadReturns: (() -> Unit)? = null

    /** Runs on the worker, before the terminal callback — lets a test hold a generation in flight. */
    var beforeDone: (() -> Unit)? = null

    var tokenCountResult: Int = 7

    /** When set, `tokenCount` throws the coded failure instead of answering. */
    var tokenCountError: Int? = null

    var embedResult: FloatArray = floatArrayOf(0.1f, 0.2f)

    /** When set, `embed` throws the coded failure instead of answering. */
    var embedError: Int? = null

    var inspectResult: ModelInspection =
        ModelInspection(
            errorCode = ErrorCode.OK,
            architecture = "llama",
            quantization = "Q2_K",
            parameterCount = null,
            contextLength = 2_048,
            embeddingWidth = 576,
            hasVision = false,
            hasChatTemplate = true,
            chatTemplateOk = true,
            tokenizerModel = "gpt2",
        )

    var statusResult: EngineStatus =
        EngineStatus(state = "ready", modelSha256 = null, contextLength = 512, tokensPerSec = 0f)

    // ------------------------------------------------------------ recording

    val loads = CopyOnWriteArrayList<LoadRequest>()
    val generates = CopyOnWriteArrayList<GenerateRequest>()
    val inspects = CopyOnWriteArrayList<InspectRequest>()
    val cancels = CopyOnWriteArrayList<Int>()

    /** Lock-policy pushes, in call order (`LOCK_POLICY_INDEXING.md` §5.2/§5.3). */
    val unlockedPushes = CopyOnWriteArrayList<Long>()
    val lockingPushes = CopyOnWriteArrayList<Long>()
    val lockedPushes = CopyOnWriteArrayList<Long>()

    /** Every `sessionEpoch` this service was sent, in call order. */
    val epochs = CopyOnWriteArrayList<Long>()

    /** The marshalled size of the last `generate`, measured before its descriptors were closed. */
    @Volatile
    var lastGenerateSize: Int = 0

    /** The messages of the last `generate`, spill resolved. */
    @Volatile
    var seenMessages: List<SeenMessage> = emptyList()

    @Volatile
    var unloads: Int = 0

    private val active = AtomicReference<Worker?>(null)
    private val binder = Binder()

    private class Worker(
        val requestId: Int,
        val thread: Thread,
        val cancelled: AtomicBoolean,
    )

    /** Blocks until the in-flight generation's worker has finished. */
    fun awaitIdle(timeoutMillis: Long = 5_000L) {
        active.get()?.thread?.join(timeoutMillis)
    }

    // -------------------------------------------------------------- service

    override fun load(req: LoadRequest): Int {
        loads += req
        epochs += req.sessionEpoch
        closeAll(req.binding)
        beforeLoadReturns?.invoke()
        return loadCode
    }

    override fun inspect(req: InspectRequest): ModelInspection {
        inspects += req
        epochs += req.sessionEpoch
        closeAll(req.binding)
        return inspectResult
    }

    override fun generate(
        req: GenerateRequest,
        cb: IInferenceCallback,
    ) {
        generates += req
        epochs += req.sessionEpoch
        lastGenerateSize = TransportRules.marshalledSize(req)
        seenMessages =
            req.messages.map { message ->
                val spill = message.contentFd
                if (spill == null) {
                    SeenMessage(message.role, message.content, spilled = false)
                } else {
                    SeenMessage(message.role, readUtf8(spill.fd, spill.sizeBytes.toInt()), spilled = true)
                }
            }
        req.messages.forEach { message -> message.contentFd?.let { close(it.fd) } }

        if (active.get() != null) {
            cb.onError(req.requestId, ErrorCode.BUSY, "a request is already in flight")
            return
        }

        val cancelled = AtomicBoolean(false)
        val thread =
            Thread {
                runWorker(req.requestId, cb, cancelled)
                active.updateAndGet { current -> if (current?.requestId == req.requestId) null else current }
            }
        active.set(Worker(req.requestId, thread, cancelled))
        thread.isDaemon = true
        thread.start()
    }

    private fun runWorker(
        requestId: Int,
        cb: IInferenceCallback,
        cancelled: AtomicBoolean,
    ) {
        val error = generateError
        if (error != null) {
            cb.onError(requestId, error.first, error.second)
            return
        }
        if (pieces.isNotEmpty()) {
            cb.onTokens(requestId, pieces.toTypedArray(), IntArray(pieces.size) { it + 1 }, 0)
        }
        // Long enough that a test which needs the request to still be in
        // flight (a second `stream`, a cancel, a service death) is not racing
        // the worker.
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(completionDelayMillis)
        while (!cancelled.get() && System.nanoTime() < deadline) {
            Thread.sleep(1L)
        }
        beforeDone?.invoke()
        cb.onDone(
            requestId,
            GenStats(
                stopReason = if (cancelled.get()) "CANCELLED" else "EOS",
                promptTokens = 3,
                generatedTokens = pieces.size,
                ttftMs = 1L,
                tokensPerSec = 12.5f,
            ),
        )
    }

    override fun cancel(requestId: Int) {
        cancels += requestId
        active
            .get()
            ?.takeIf { it.requestId == requestId }
            ?.cancelled
            ?.set(true)
    }

    override fun unload() {
        unloads += 1
    }

    override fun embed(req: EmbedRequest): FloatArray {
        epochs += req.sessionEpoch
        embedError?.let { throw ErrorCodes.asServiceFailure(it, "embed refused") }
        return embedResult
    }

    override fun tokenCount(text: String?): Int {
        tokenCountError?.let { throw ErrorCodes.asServiceFailure(it, "token count refused") }
        return tokenCountResult
    }

    override fun status(): EngineStatus = statusResult

    override fun onSessionLocking(
        epoch: Long,
        budgetMillis: Long,
    ) {
        lockingPushes += epoch
    }

    override fun onSessionLocked(epoch: Long) {
        lockedPushes += epoch
    }

    override fun onSessionUnlocked(epoch: Long) {
        unlockedPushes += epoch
    }

    override fun asBinder(): IBinder = binder

    // -------------------------------------------------------------- helpers

    private fun closeAll(binding: ManifestBinding) {
        binding.files.forEach { close(it.fd) }
        binding.attestation?.let { close(it.bundleFd) }
    }

    private fun close(fd: ParcelFileDescriptor) {
        runCatching { fd.close() }
    }

    private fun readUtf8(
        fd: ParcelFileDescriptor,
        size: Int,
    ): String =
        FileInputStream(fd.fileDescriptor).use { stream ->
            val bytes = ByteArray(size)
            var read = 0
            while (read < size) {
                val n = stream.read(bytes, read, size - read)
                if (n < 0) break
                read += n
            }
            String(bytes, 0, read, Charsets.UTF_8)
        }
}
