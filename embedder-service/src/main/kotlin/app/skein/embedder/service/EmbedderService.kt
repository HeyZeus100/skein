package app.skein.embedder.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import app.skein.ipc.EmbedRequest
import app.skein.ipc.EmbedResult
import app.skein.ipc.EmbedderLoadRequest
import app.skein.ipc.EmbedderTokenCountRequest
import app.skein.ipc.EntitySpanParcel
import app.skein.ipc.ErrorCode
import app.skein.ipc.ErrorCodes
import app.skein.ipc.ExtractEntitiesRequest
import app.skein.ipc.IEmbedderService
import app.skein.ipc.RerankRequest
import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference

/** Isolated Binder boundary. Backend activation remains gated by skein-5hr. */
class EmbedderService : Service() {
    private val endpoint =
        EmbedderEndpoint(
            backendFactory = null,
            hardStop = { Process.killProcess(Process.myPid()) },
        )

    override fun onBind(intent: Intent?): IBinder = endpoint

    override fun onDestroy() {
        endpoint.close()
        super.onDestroy()
    }
}

/** No synthetic vectors or approximate tokenizer fallback exist in this boundary. */
internal class EmbedderEndpoint(
    private val backendFactory: EmbedderBackendFactory?,
    private val hardStop: () -> Unit,
    private val gate: EmbedderSessionGate = EmbedderSessionGate(),
    timeoutMillis: Long = 30_000L,
) : IEmbedderService.Stub(),
    Closeable {
    private val worker = EmbedderRequestWorker(gate, timeoutMillis)
    private val loaded = AtomicReference<Loaded?>()

    override fun load(req: EmbedderLoadRequest): Int =
        try {
            worker.run(
                req.sessionEpoch,
                req.requestId,
                closeInput = { VerifiedEmbedderModels.closeReceived(req) },
                discardResult = { it.release() },
                publishResult = { candidate ->
                    if (!loaded.compareAndSet(null, candidate)) throw EmbedderModelException(ErrorCode.MODEL_IN_USE)
                },
            ) { cancellation ->
                EmbedderTransport.checkParcel(req)
                if (loaded.get() != null) throw EmbedderModelException(ErrorCode.MODEL_IN_USE)
                if (req.threads !in 1..16 || req.embedFormat !in setOf("onnx", "gguf")) {
                    throw EmbedderModelException(ErrorCode.INVALID_MODEL)
                }
                val factory = backendFactory ?: throw EmbedderModelException(ErrorCode.NOT_LOADED)
                val models = VerifiedEmbedderModels.verify(req, cancellation)
                try {
                    val backend = factory.create(models, req.embedFormat, req.threads, cancellation)
                    Loaded(req.sessionEpoch, backend, models)
                } catch (failure: Throwable) {
                    models.close()
                    throw failure
                }
            }
            ErrorCode.OK
        } catch (failure: Throwable) {
            failureCode(failure)
        }

    override fun embed(req: EmbedRequest): EmbedResult =
        call {
            worker.run(req.sessionEpoch, req.requestId, closeInput = {
                req.inputFd?.fd?.close()
            }, discardResult = { it.flat.fill(0) }) { cancellation ->
                EmbedderTransport.checkParcel(req)
                val texts = EmbedderTransport.texts(req.texts, req.inputFd, cancellation)
                withBackend(req.sessionEpoch) { backend ->
                    val flat = backend.embed(texts, req.isQuery, cancellation)
                    if (flat.size != texts.size * 256) {
                        flat.fill(0)
                        throw EmbedderModelException(ErrorCode.INVALID_MODEL)
                    }
                    EmbedResult(flat, 0)
                }
            }
        }

    override fun extractEntities(req: ExtractEntitiesRequest): List<EntitySpanParcel> =
        call {
            worker.run(req.sessionEpoch, req.requestId, closeInput = { req.inputFd?.fd?.close() }) { cancellation ->
                EmbedderTransport.checkParcel(req)
                if (req.inputFd != null &&
                    req.text.isNotEmpty()
                ) {
                    throw EmbedderRequestException(EmbedderRequestFailure.INVALID_REQUEST)
                }
                val text =
                    EmbedderTransport
                        .texts(
                            if (req.inputFd ==
                                null
                            ) {
                                listOf(req.text)
                            } else {
                                emptyList()
                            },
                            req.inputFd,
                            cancellation,
                            1,
                        ).single()
                withBackend(req.sessionEpoch) { it.extractEntities(text, req.labels, cancellation) }
            }
        }

    override fun rerank(req: RerankRequest): FloatArray =
        call {
            worker.run(req.sessionEpoch, req.requestId, closeInput = {
                req.inputFd?.fd?.close()
            }, discardResult = { it.fill(0f) }) { cancellation ->
                EmbedderTransport.checkParcel(req)
                val texts = EmbedderTransport.texts(req.candidates, req.inputFd, cancellation)
                withBackend(req.sessionEpoch) { backend ->
                    val scores = backend.rerank(req.query, texts, cancellation)
                    if (scores.size != texts.size || scores.any { !it.isFinite() }) {
                        scores.fill(0f)
                        throw EmbedderModelException(ErrorCode.INVALID_MODEL)
                    }
                    scores
                }
            }
        }

    override fun tokenCountForSession(req: EmbedderTokenCountRequest): Int =
        call {
            worker.run(req.sessionEpoch, req.requestId, closeInput = { req.inputFd?.fd?.close() }) { cancellation ->
                EmbedderTransport.checkParcel(req)
                if (req.inputFd != null &&
                    req.text.isNotEmpty()
                ) {
                    throw EmbedderRequestException(EmbedderRequestFailure.INVALID_REQUEST)
                }
                val text =
                    EmbedderTransport
                        .texts(
                            if (req.inputFd ==
                                null
                            ) {
                                listOf(req.text)
                            } else {
                                emptyList()
                            },
                            req.inputFd,
                            cancellation,
                            1,
                        ).single()
                withBackend(req.sessionEpoch) { backend ->
                    backend.tokenCount(text, cancellation).also {
                        if (it <
                            0
                        ) {
                            throw EmbedderModelException(ErrorCode.INVALID_MODEL)
                        }
                    }
                }
            }
        }

    override fun tokenCount(text: String): Int =
        throw ErrorCodes.asServiceFailure(ErrorCode.SESSION_LOCKED, "explicit session request required")

    override fun cancel(requestId: Int) = Unit

    override fun unload() = Unit

    override fun cancelForSession(
        sessionEpoch: Long,
        requestId: Int,
    ) = worker.cancel(sessionEpoch, requestId)

    override fun unloadForSession(sessionEpoch: Long) {
        var previous: Loaded? = null
        gate.whileAuthorized(sessionEpoch) {
            worker.cancelEpoch(sessionEpoch)
            previous = loaded.getAndSet(null)
        }
        previous?.release()
    }

    override fun onSessionUnlocked(epoch: Long) {
        // A previous epoch's uncooperative call must unwind (or be killed) before reauthorization.
        var previous: Loaded? = null
        gate.authorize(epoch) {
            if (worker.isBusy()) throw ErrorCodes.asServiceFailure(ErrorCode.BUSY)
            if (loaded.get()?.epoch != epoch) previous = loaded.getAndSet(null)
        }
        previous?.release()
    }

    override fun onSessionLocking(
        epoch: Long,
        budgetMillis: Long,
    ) {
        if (!gate.revoke(epoch)) return
        worker.cancelThrough(epoch)
        clearThrough(epoch)
    }

    override fun onSessionLocked(epoch: Long) {
        if (!gate.revoke(epoch)) return
        worker.cancelThrough(epoch)
        clearThrough(epoch)
        // Never concurrently free native state still executing. Only this isolated process
        // is terminated at the hard lock backstop; idle models can close normally.
        if (worker.isBusyThrough(epoch)) hardStop()
    }

    private fun clearThrough(epoch: Long) {
        val previous = loaded.get()?.takeIf { it.epoch <= epoch } ?: return
        if (loaded.compareAndSet(previous, null)) previous.release()
    }

    private fun <T> withBackend(
        epoch: Long,
        action: (EmbedderBackend) -> T,
    ): T {
        val model =
            loaded.get()?.takeIf { it.epoch == epoch && it.acquire() }
                ?: throw EmbedderModelException(ErrorCode.NOT_LOADED)
        return try {
            action(model.backend)
        } finally {
            model.release()
        }
    }

    override fun close() {
        worker.close()
        loaded.getAndSet(null)?.release()
        if (worker.isBusy()) hardStop()
    }

    private class Loaded(
        val epoch: Long,
        val backend: EmbedderBackend,
        private val models: VerifiedEmbedderModels,
    ) {
        private var references = 1

        @Synchronized
        fun acquire(): Boolean {
            if (references == 0) return false
            references++
            return true
        }

        fun release() {
            val dispose = synchronized(this) { --references == 0 }
            if (dispose) {
                try {
                    backend.close()
                } finally {
                    models.close()
                }
            }
        }
    }

    private fun <T> call(action: () -> T): T =
        try {
            action()
        } catch (failure: Throwable) {
            throw ErrorCodes.asServiceFailure(failureCode(failure), "embedder request refused")
        }

    private fun failureCode(failure: Throwable): Int =
        when (failure) {
            is EmbedderModelException -> failure.code
            is EmbedderRequestException ->
                when (failure.failure) {
                    EmbedderRequestFailure.SESSION_LOCKED -> ErrorCode.SESSION_LOCKED
                    EmbedderRequestFailure.BUSY -> ErrorCode.BUSY
                    EmbedderRequestFailure.CANCELLED, EmbedderRequestFailure.TIMED_OUT -> ErrorCode.CANCELLED
                    EmbedderRequestFailure.UNAVAILABLE -> ErrorCode.NOT_LOADED
                    EmbedderRequestFailure.INVALID_REQUEST -> ErrorCode.TX_TOO_LARGE
                }
            is OutOfMemoryError -> ErrorCode.OOM
            else -> ErrorCode.INTERNAL
        }
}
