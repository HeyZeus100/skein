package app.skein.core.inference.engine

import app.skein.core.model.InferenceException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Blocking model Binder calls own their descriptors until the transaction returns.
 * Their callers can stop waiting at lock without making a Binder thread a child
 * of the lock observer. The isolated process's lock handler terminates the remote
 * work; cancellation here does not pretend to interrupt a synchronous Binder call.
 */
internal class ModelTransactions(
    io: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + io)
    private val monitor = Any()
    private var revision = 0L
    private val pending = mutableSetOf<Pending>()

    fun current(): Long = synchronized(monitor) { revision }

    fun <T> publish(
        version: Long,
        block: () -> T,
    ): T =
        synchronized(monitor) {
            if (version != revision) throw InferenceException.SessionLocked()
            block()
        }

    fun revoke(failure: Throwable = InferenceException.SessionLocked()) {
        val calls =
            synchronized(monitor) {
                revision++
                pending.toList().also { pending.clear() }
            }
        calls.forEach { it.fail(failure) }
    }

    suspend fun <T> call(
        version: Long,
        close: () -> Unit,
        block: suspend () -> T,
    ): T =
        suspendCancellableCoroutine { continuation ->
            val completed = AtomicBoolean(false)
            val closed = AtomicBoolean(false)
            val closeOnce = {
                if (closed.compareAndSet(false, true)) runCatching(close)
                Unit
            }
            val worker =
                scope.launch(start = CoroutineStart.LAZY) {
                    val result =
                        try {
                            runCatching { block() }
                        } finally {
                            closeOnce()
                        }
                    if (completed.compareAndSet(false, true)) continuation.resumeWith(result)
                }
            val call =
                Pending { failure ->
                    if (completed.compareAndSet(false, true)) continuation.resumeWith(Result.failure(failure))
                    worker.cancel()
                }
            worker.invokeOnCompletion {
                // A cancelled LAZY/queued job may never enter its body.
                closeOnce()
                synchronized(monitor) { pending.remove(call) }
            }
            val admitted =
                synchronized(monitor) {
                    if (version != revision) false else pending.add(call)
                }
            if (!admitted) {
                call.fail(InferenceException.SessionLocked())
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation { worker.cancel() }
            worker.start()
        }

    private class Pending(
        val fail: (Throwable) -> Unit,
    )
}
