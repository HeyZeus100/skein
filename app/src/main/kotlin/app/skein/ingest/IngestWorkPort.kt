// E5.I10 (skein-7v3) — `IngestScheduler`'s only view of WorkManager, in the
// same shape as `VaultBootstrap`'s `DocumentsProviderPort`: production wraps
// the real `WorkManager`, tests record calls so they can assert *order*
// (cancel-before-close, `LOCK_POLICY_INDEXING.md` §4.2/§4.4) without a
// WorkManager instance.

package app.skein.ingest

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/** Enqueues / cancels the unique ingest work. Both calls are cheap and non-suspending. */
interface IngestWorkPort {
    /** Enqueues one ingest pass authorised for [sessionEpoch] (`AuthorizationToken.epoch` of the open session). */
    fun enqueue(sessionEpoch: Long)

    /** Cancels every pending or running ingest pass. Idempotent. */
    fun cancel()
}

/**
 * The real port. One unique one-time request per pass
 * ([UNIQUE_WORK_NAME], `ExistingWorkPolicy.APPEND_OR_REPLACE` — the amended
 * `E5.I10`, LOCK_POLICY_INDEXING.md §7.1: no periodic work, no
 * `Constraints` gate at all), exponential backoff for `Result.retry()`
 * (thermal `Paused`), and the session epoch as the only input — a counter,
 * never content, so WorkManager's own unencrypted database holds nothing
 * vault-derived.
 *
 * Not expedited: on API 30 (this app's `minSdk`) expedited work runs as a
 * foreground service, which needs a notification and the FOREGROUND_SERVICE
 * permission the manifest strips; a plain unconstrained request is
 * dispatched immediately by WorkManager's in-process scheduler while the
 * app is in the foreground, which is the only time the vault is unlocked.
 */
class WorkManagerIngestWorkPort(
    private val workManager: WorkManager,
) : IngestWorkPort {
    override fun enqueue(sessionEpoch: Long) {
        workManager.enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request(sessionEpoch))
    }

    override fun cancel() {
        workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    companion object {
        /** Unique work name (bd skein-7v3) — also the tag every request carries. */
        const val UNIQUE_WORK_NAME: String = "skein-ingest"

        /** Builds one pass's request; public so tests can assert its constraints and backoff. */
        fun request(sessionEpoch: Long): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<IngestWorker>()
                .addTag(UNIQUE_WORK_NAME)
                .setInputData(workDataOf(IngestWorker.KEY_SESSION_EPOCH to sessionEpoch))
                .setConstraints(Constraints.NONE)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.DEFAULT_BACKOFF_DELAY_MILLIS,
                    TimeUnit.MILLISECONDS,
                ).build()
    }
}
