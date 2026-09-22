// skein-nxk (E4.I3): a [TaskRunner] that runs everything on the calling thread.
//
// The service's threading rule — one thread owns the context — is a property of
// the PRODUCTION runner, and `InferenceWorker` is where it is enforced and
// where the instrumented tests exercise it. What the JVM tests are about is the
// entry-point logic layered on top, and pumping a real `Looper` to observe
// "`load` returned HASH_MISMATCH" would mean testing the scheduler as much as
// the service.

package app.skein.inference.service

class InlineTaskRunner : TaskRunner {
    override fun <T> submitBlocking(block: () -> T): T = block()

    override fun post(block: () -> Unit) = block()

    override fun clearPending() = Unit

    override fun shutdown() = Unit
}
