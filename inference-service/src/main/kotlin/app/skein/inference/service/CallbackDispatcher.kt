// skein-nxk (E4.I3): the outbound half of POST_REVIEW_RESOLUTIONS.md §3.2
// rule 4.
//
// `IInferenceCallback` is entirely `oneway`, so the service never blocks on the
// client's Binder thread pool — but `oneway` is not free: transactions queue in
// a per-process buffer, and a client that stops draining turns an unbounded
// producer (the sampler) into unbounded kernel-side memory. Rule 4's answer is
// a bounded queue on OUR side with the OLDEST batch shed first, and the count
// surfaced through `onTokens(…, dropped)`.
//
// Why oldest-first: the newest tokens are the ones the user is waiting to see.
// Dropping the newest to preserve the oldest would make a struggling client
// render a stale prefix and then jump; dropping the oldest makes it render a
// gap and catch up, which is what "output truncated for speed" means.

package app.skein.inference.service

import java.util.concurrent.atomic.AtomicInteger

/**
 * Delivers callback work on its own thread, tracking how much is outstanding.
 *
 * [inFlight] is what `TokenBatcher` consults: it is the number of batches
 * handed over but not yet sent, i.e. exactly the backlog rule 4 bounds.
 */
class CallbackDispatcher(
    private val worker: TaskRunner = InferenceWorker("skein-inference-cb"),
) {
    private val pending = AtomicInteger(0)

    /** Batches accepted but not yet delivered. */
    val inFlight: Int get() = pending.get()

    /**
     * Queues [delivery] for the client.
     *
     * Failures are swallowed deliberately: every delivery is a `oneway` Binder
     * call to a process we do not control, and a dead or wedged client must not
     * take the service's worker down with it. Death is observed through
     * `linkToDeath`, which cancels the request — that is the path that reacts,
     * not this one.
     */
    fun deliver(delivery: () -> Unit) {
        pending.incrementAndGet()
        worker.post {
            try {
                delivery()
            } catch (e: android.os.RemoteException) {
                // The client went away mid-stream. linkToDeath handles it.
                @Suppress("UNUSED_EXPRESSION")
                e
            } finally {
                pending.decrementAndGet()
            }
        }
    }

    fun shutdown() = worker.shutdown()
}
