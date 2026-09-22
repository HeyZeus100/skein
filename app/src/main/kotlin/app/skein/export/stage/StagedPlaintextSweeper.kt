// skein-0m1z (docs/design/POST_REVIEW_RESOLUTIONS.md §4.3) — the 10-minute
// timer that bounds a staged plaintext file's life.
//
// Thin on purpose, the same way `IngestWorker` is: WorkManager supplies the
// delayed, persisted, cancellable job; the decision of whether this stage is
// actually due (and the deletion itself) lives in `:core:vault`'s
// `StagedPlaintextSweep`, which is plain Kotlin and JVM-testable.
//
// This worker is a backstop, not the primary defence. A device lock sweeps
// every stage immediately regardless of `expires_at` (§4.3: "locked = zero
// tolerance for staged plaintext"), and a reboot purges the whole staging
// directory via `BootReceiver`. The timer only matters while the vault stays
// unlocked and the process stays alive for a full ten minutes after an
// export.
//
// Nothing vault-derived is ever put in `inputData`: WorkManager keeps its own
// UNENCRYPTED database, so the only value stored there is the stage id — a
// UUIDv7, not content and not a path.

package app.skein.export.stage

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.skein.core.vault.export.stage.SweepOutcome
import java.util.concurrent.TimeUnit

/** Sweeps one stage id. Supplied by [ExportStageCoordinator] in production. */
fun interface StageSweep {
    suspend fun sweep(stageId: String): SweepOutcome
}

/**
 * See the file header. Built by [Factory] (registered on `SkeinApplication`'s
 * WorkManager configuration); [sweep] is the process-wide
 * [ExportStageCoordinator].
 */
class StagedPlaintextSweeper(
    context: Context,
    params: WorkerParameters,
    private val sweep: StageSweep,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val stageId = inputData.getString(KEY_STAGE_ID) ?: return Result.failure()
        return when (sweep.sweep(stageId)) {
            // Gone, or never ours to sweep (already swept, cascaded away with
            // its document, or the vault is locked — in which case the lock
            // sweep has already deleted every staged file). Either way there
            // is nothing left to do and retrying would only keep a job alive.
            SweepOutcome.SWEPT, SweepOutcome.UNKNOWN -> Result.success()
            // §4.3's paranoid path: WorkManager ran us before `expires_at`.
            // Hand the request back so its backoff re-runs it rather than
            // deleting a file an export may still be writing.
            SweepOutcome.PREMATURE -> Result.retry()
        }
    }

    /**
     * Builds [StagedPlaintextSweeper]s over [sweep] (resolved lazily so the
     * `Application` can publish its WorkManager configuration before the
     * vault stack exists) and defers every other worker class by returning
     * `null`, exactly as `IngestWorker.Factory` does.
     */
    class Factory(
        private val sweep: () -> StageSweep,
    ) : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker? =
            if (workerClassName == StagedPlaintextSweeper::class.java.name) {
                StagedPlaintextSweeper(appContext, workerParameters, sweep())
            } else {
                null
            }
    }

    companion object {
        /** Input-data key: the `export_stages.stage_id` this request sweeps. */
        const val KEY_STAGE_ID: String = "stage_id"

        /** Tag every sweep request carries, per §4.3. */
        const val TAG: String = "staged-plaintext"

        /** §4.3's unique work name: one in-flight sweep per stage. */
        fun uniqueWorkName(stageId: String): String = "sweep-$stageId"

        /** Builds one stage's request; public so tests can assert its delay and input. */
        fun request(
            stageId: String,
            delayMillis: Long,
        ): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<StagedPlaintextSweeper>()
                .setInitialDelay(delayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_STAGE_ID to stageId))
                .addTag(TAG)
                .build()

        /**
         * Enqueues (or replaces) the sweep for [stageId].
         * `ExistingWorkPolicy.REPLACE` per §4.3: re-staging the same id must
         * restart its clock rather than queue a second deletion behind the
         * first.
         */
        fun enqueue(
            workManager: WorkManager,
            stageId: String,
            delayMillis: Long,
        ) {
            workManager.enqueueUniqueWork(
                uniqueWorkName(stageId),
                ExistingWorkPolicy.REPLACE,
                request(stageId, delayMillis),
            )
        }
    }
}
