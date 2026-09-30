package app.skein.embedder.service

import java.io.Closeable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal enum class EmbedderRequestFailure {
    SESSION_LOCKED,
    INVALID_REQUEST,
    BUSY,
    CANCELLED,
    TIMED_OUT,
    UNAVAILABLE,
}

/** Fixed classifications only: no request text or backend exception message crosses IPC. */
internal class EmbedderRequestException(
    val failure: EmbedderRequestFailure,
) : RuntimeException(failure.name)

internal class EmbedderCancellation {
    private val cancelled = AtomicBoolean(false)

    fun cancel() {
        cancelled.set(true)
    }

    fun isCancelled(): Boolean = cancelled.get()

    fun check() {
        if (isCancelled()) throw EmbedderRequestException(EmbedderRequestFailure.CANCELLED)
    }
}

/**
 * One admitted request, no unbounded queue. Cancellation revokes the reply immediately,
 * but ownership and the busy slot remain with the worker until its action actually exits.
 * A native backend must also poll [EmbedderCancellation]; interrupt is only a wake-up.
 */
internal class EmbedderRequestWorker(
    private val gate: EmbedderSessionGate,
    private val timeoutMillis: Long = 30_000L,
) : Closeable {
    private val executor = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private var active: Ticket<*>? = null
    private var closed = false
    private var requestEpoch = 0L
    private var lastRequestId = 0

    fun <T> run(
        epoch: Long,
        requestId: Int,
        closeInput: () -> Unit = {},
        discardResult: (T) -> Unit = {},
        publishResult: (T) -> Unit = {},
        action: (EmbedderCancellation) -> T,
    ): T {
        val ticket = Ticket<T>(epoch, requestId)
        try {
            val admitted =
                gate.whileAuthorized(epoch) {
                    synchronized(lock) {
                        if (closed) fail(EmbedderRequestFailure.UNAVAILABLE)
                        if (active != null) fail(EmbedderRequestFailure.BUSY)
                        if (epoch != requestEpoch) {
                            requestEpoch = epoch
                            lastRequestId = 0
                        }
                        if (requestId <= lastRequestId || requestId <= 0) fail(EmbedderRequestFailure.INVALID_REQUEST)
                        lastRequestId = requestId
                        active = ticket
                        try {
                            executor.execute {
                                ticket.thread.set(Thread.currentThread())
                                var outcome =
                                    runCatching {
                                        ticket.cancellation.check()
                                        if (!gate.admits(epoch)) fail(EmbedderRequestFailure.SESSION_LOCKED)
                                        action(ticket.cancellation)
                                    }
                                try {
                                    closeInput()
                                } catch (failure: Throwable) {
                                    outcome.onSuccess(discardResult)
                                    outcome = Result.failure(failure)
                                } finally {
                                    ticket.thread.set(null)
                                    Thread.interrupted()
                                    synchronized(lock) {
                                        ticket.workerFinished = true
                                        release(ticket)
                                    }
                                }
                                outcome.fold(
                                    onSuccess = { if (!ticket.reply.complete(it)) discardResult(it) },
                                    onFailure = { ticket.reply.completeExceptionally(it) },
                                )
                            }
                        } catch (failure: Throwable) {
                            active = null
                            throw failure
                        }
                    }
                }
            if (!admitted) fail(EmbedderRequestFailure.SESSION_LOCKED)
        } catch (failure: Throwable) {
            closeInput()
            throw failure
        }

        try {
            val result =
                try {
                    ticket.reply.get(timeoutMillis, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    ticket.cancel(EmbedderRequestFailure.TIMED_OUT)
                    discardCompleted(ticket, discardResult)
                    fail(EmbedderRequestFailure.TIMED_OUT)
                } catch (_: InterruptedException) {
                    ticket.cancel(EmbedderRequestFailure.CANCELLED)
                    discardCompleted(ticket, discardResult)
                    Thread.currentThread().interrupt()
                    fail(EmbedderRequestFailure.CANCELLED)
                } catch (failure: ExecutionException) {
                    throw (failure.cause ?: failure)
                }
            var accepted = false
            try {
                gate.whileAuthorized(epoch) {
                    synchronized(lock) {
                        if (!ticket.cancellation.isCancelled()) {
                            publishResult(result)
                            accepted = true
                        }
                    }
                }
            } catch (failure: Throwable) {
                discardResult(result)
                throw failure
            }
            if (!accepted) {
                discardResult(result)
                fail(
                    if (gate.admits(epoch)) EmbedderRequestFailure.CANCELLED else EmbedderRequestFailure.SESSION_LOCKED,
                )
            }
            return result
        } finally {
            synchronized(lock) {
                ticket.callerFinished = true
                release(ticket)
            }
        }
    }

    private fun release(ticket: Ticket<*>) {
        if (ticket.workerFinished && ticket.callerFinished && active === ticket) active = null
    }

    fun cancel(
        epoch: Long,
        requestId: Int,
    ) {
        synchronized(lock) {
            active?.takeIf { it.epoch == epoch && it.requestId == requestId }?.cancel(EmbedderRequestFailure.CANCELLED)
        }
    }

    fun cancelEpoch(epoch: Long) {
        synchronized(lock) {
            active?.takeIf { it.epoch == epoch }?.cancel(EmbedderRequestFailure.SESSION_LOCKED)
        }
    }

    fun isBusy(): Boolean = synchronized(lock) { active != null }

    fun isBusy(epoch: Long): Boolean = synchronized(lock) { active?.epoch == epoch }

    override fun close() {
        synchronized(lock) {
            closed = true
            active?.cancel(EmbedderRequestFailure.CANCELLED)
            // Do not remove an admitted runnable before its finally can close owned inputs.
            executor.shutdown()
        }
    }

    private fun <T> discardCompleted(
        ticket: Ticket<T>,
        discard: (T) -> Unit,
    ) {
        if (!ticket.reply.isCompletedExceptionally) discard(ticket.reply.join())
    }

    private class Ticket<T>(
        val epoch: Long,
        val requestId: Int,
    ) {
        var workerFinished = false
        var callerFinished = false
        val cancellation = EmbedderCancellation()
        val reply = CompletableFuture<T>()
        val thread = AtomicReference<Thread?>()

        fun cancel(failure: EmbedderRequestFailure) {
            cancellation.cancel()
            reply.completeExceptionally(EmbedderRequestException(failure))
            thread.get()?.interrupt()
        }
    }

    private companion object {
        fun fail(failure: EmbedderRequestFailure): Nothing = throw EmbedderRequestException(failure)
    }
}
