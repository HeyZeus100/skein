// E5.I10 (skein-7v3) — the WorkManager entry point for one ingest pass. Thin
// on purpose: WorkManager gives us retry/backoff and a cancellable job; the
// authorisation check and the pipeline live in `IngestScheduler`
// (`IngestRunner`) so they can be tested without a `WorkerParameters`.
//
// Bail-out semantics (`LOCK_POLICY_INDEXING.md` §3.2, §6.1 invariant I2):
// the pass is authorised for exactly the `sessionEpoch` it was enqueued
// with. If the vault is not `Unlocked`, or is unlocked under a different
// epoch (lock + unlock between enqueue and run), the runner reports
// `Locked` and this worker returns `Result.failure()` — never `retry()`,
// which would keep a stale pass alive while locked; the next unlock
// enqueues a fresh pass anyway. Thermal `Paused` maps to `retry()` with
// the request's exponential backoff.
//
// A lock cancels the unique work (`IngestScheduler.onLocking`), which
// cancels this worker's coroutine; the pipeline's per-step checkpoints
// unwind it promptly and `CancellationException` is never caught here.

package app.skein.ingest

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import app.skein.core.rag.ingest.IngestOutcome

/** What [IngestWorker] needs from `IngestScheduler`. */
interface IngestRunner {
    /** Runs one pass if the vault is unlocked under [sessionEpoch]; otherwise reports [IngestOutcome.Locked]. */
    suspend fun runPending(sessionEpoch: Long): IngestOutcome
}

/**
 * See the file header. Constructed by [Factory] (`SkeinApplication`'s
 * WorkManager configuration); the `runner` is the process-wide
 * `IngestScheduler`.
 */
class IngestWorker(
    context: Context,
    params: WorkerParameters,
    private val runner: IngestRunner,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val sessionEpoch = inputData.getLong(KEY_SESSION_EPOCH, NO_EPOCH)
        if (sessionEpoch == NO_EPOCH) return Result.failure()
        return when (runner.runPending(sessionEpoch)) {
            is IngestOutcome.Drained -> Result.success()
            is IngestOutcome.Paused -> Result.retry()
            is IngestOutcome.Locked -> Result.failure()
        }
    }

    /**
     * Builds [IngestWorker]s over [runner] (resolved lazily so the
     * `Application` can hand out its WorkManager configuration before the
     * vault stack exists) and defers every other worker class to
     * WorkManager's default reflection factory by returning `null`.
     */
    class Factory(
        private val runner: () -> IngestRunner,
    ) : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker? =
            if (workerClassName == IngestWorker::class.java.name) {
                IngestWorker(appContext, workerParameters, runner())
            } else {
                null
            }
    }

    companion object {
        /** Input-data key: the `AuthorizationToken.epoch` this pass is authorised for. */
        const val KEY_SESSION_EPOCH: String = "sessionEpoch"

        /** Sentinel for a request that carries no epoch (never enqueued by `WorkManagerIngestWorkPort`). */
        const val NO_EPOCH: Long = -1L
    }
}
