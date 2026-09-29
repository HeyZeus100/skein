// skein-nxk (E4.I3): the single thread that owns the llama.cpp context.
//
// llama.cpp contexts are not thread-safe and `LlamaNative`'s threading notes
// say so call by call: `decodePrompt`, `sampleNext`, `embed` and `kvClear` may
// only run on the thread that owns the context, and `loadModel*`/`newContext`/
// the frees belong there too. A `HandlerThread` makes that structural — every
// native call is posted here, so there is exactly one place the rule can be
// broken and it is visible in one file.
//
// `setCancelFlag` is the deliberate exception: it is called FROM a binder
// thread WHILE this thread is inside `llama_decode`, which is the entire point
// of it being a `std::atomic<bool>` on the native side.

package app.skein.inference.service

import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The two ways work reaches the thread that owns the llama.cpp context.
 *
 * An interface, not just the class below, so the service's entry-point logic
 * can be driven from a JVM test by a runner that executes inline. A test that
 * had to pump a real `Looper` to observe "`load` returned `HASH_MISMATCH`"
 * would be testing Robolectric's scheduler as much as the service.
 */
interface TaskRunner {
    fun <T> submitBlocking(block: () -> T): T

    fun post(block: () -> Unit)

    fun clearPending()

    fun shutdown()
}

/** Owns the inference `HandlerThread` and the two ways work reaches it. */
class InferenceWorker(
    name: String = "skein-inference",
) : TaskRunner {
    private val thread = HandlerThread(name).apply { start() }
    private val handler = Handler(thread.looper)
    private val stopped = AtomicBoolean(false)

    /** True while this thread is running a posted task. */
    @Volatile
    var busy: Boolean = false
        private set

    /**
     * Runs [block] on the worker and waits for it.
     *
     * For the synchronous AIDL entry points (`load`, `unload`, `embed`,
     * `tokenCount`): Binder's contract is that the call returns when the work
     * is done, and the work has to happen here. An exception thrown by [block]
     * is rethrown to the caller, so the entry point's own `try`/`catch` sees
     * the `LlamaException` it expects.
     */
    override fun <T> submitBlocking(block: () -> T): T {
        // Recovery can call unload from runGeneration on this same thread.
        // Posting and waiting there would prevent the queued cleanup from
        // ever running. Inline execution preserves the native owner's thread
        // and propagates the original result/exception without a Future.
        if (Thread.currentThread() === thread) return block()
        val task = FutureTask(Callable { block() })
        if (!handler.post(task)) throw IllegalStateException("inference worker is not running")
        return awaitWorkerResult(task)
    }

    /** Posts [block] without waiting — `generate`, which streams back on the callback. */
    override fun post(block: () -> Unit) {
        handler.post {
            busy = true
            try {
                block()
            } finally {
                busy = false
            }
        }
    }

    /** Drops every task not yet started. In-flight work is cancelled through the abort flag, not here. */
    override fun clearPending() {
        handler.removeCallbacksAndMessages(null)
    }

    /**
     * Stops the thread, waiting for the current task to finish.
     *
     * Called from `Service.onDestroy`. Waiting matters: the process may be
     * about to be torn down, and a decode still running against a context
     * another thread is freeing is a native crash.
     */
    override fun shutdown() {
        if (!stopped.compareAndSet(false, true)) return
        val drained = CountDownLatch(1)
        if (handler.post { drained.countDown() }) {
            drained.await()
        }
        thread.quitSafely()
    }
}

/**
 * An interrupt cannot transfer native-resource ownership back to the caller:
 * the queued/running task still owns the handles and may be duplicating its fd.
 * Observe completion before callers run their cleanup, then restore the flag.
 * Session lock never waits here; its hard stop terminates the isolated process
 * directly. A failed task preserves the original cause, including Errors.
 */
internal fun <T> awaitWorkerResult(task: FutureTask<T>): T {
    var interrupted = false
    try {
        while (true) {
            try {
                return task.get()
            } catch (_: InterruptedException) {
                interrupted = true
            } catch (failure: ExecutionException) {
                throw failure.cause ?: failure
            }
        }
    } finally {
        if (interrupted) Thread.currentThread().interrupt()
    }
}
