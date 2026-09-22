// skein-0m1z (docs/design/POST_REVIEW_RESOLUTIONS.md §4.3) — the process-wide
// owner of staged-plaintext lifetime. Three jobs, one class:
//
//   1. `ExportStageRecorder` (the `:core:export` port): when a PDF export
//      spools plaintext to `cache/staging_export/`, persist the row and start
//      its 10-minute timer.
//   2. `StageSweep`: what `StagedPlaintextSweeper` calls when that timer
//      fires.
//   3. `LockObserver`: the on-lock sweep. §4.3 — "on lock (`UnlockManager` →
//      `Locked`), an immediate sweep runs regardless of `expires_at` (locked
//      = zero tolerance for staged plaintext)".
//
// ===== Why the lock sweep runs in onLocking, not onLocked =====
//
// §4.3's prose says "adds an `onLocked` hook that invokes
// `ExportStageRepository.sweepAll()` synchronously before the master key
// zeroes" — those two halves contradict each other under the `LockObserver`
// contract this repo actually has: `onLocked` fires AFTER the key is zeroed
// and the vault connection closed (see `LockObserver`'s KDoc), at which point
// `sweepAll()` could not write a single row. The binding half is "before the
// master key zeroes", so the sweep runs in `onLocking` at
// `LockObserverPriority.HIGH` — the same placement, and for the same reason,
// as `IngestScheduler`'s cancel-before-close.
//
// `onUnlocked` sweeps too. That is this design's answer to §4.3's "rescan
// `export_stages` on cold start": `BootReceiver` can only purge files (the
// vault is locked at boot), so unlock is the first moment a row can be
// reconciled. Any stage recorded in a previous session is orphaned by
// definition — the export that owned it did not survive the lock.

package app.skein.export.stage

import androidx.work.WorkManager
import app.skein.core.export.pdf.ExportStage
import app.skein.core.export.pdf.ExportStageRecorder
import app.skein.core.model.SkeinLog
import app.skein.core.vault.export.stage.ExportStageRepository
import app.skein.core.vault.export.stage.ExportStageRow
import app.skein.core.vault.export.stage.StagedPlaintextSweep
import app.skein.core.vault.export.stage.SweepOutcome
import app.skein.core.vault.session.LockObserver
import app.skein.core.vault.session.LockObserverPriority
import app.skein.core.vault.session.UnlockManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * See the file header.
 *
 * [repository] resolves the open vault's `ExportStageRepository`, or `null`
 * while locked — every method degrades to "nothing to do" in that case,
 * which is correct: the lock sweep has already deleted the files.
 */
class ExportStageCoordinator(
    private val unlockManager: UnlockManager,
    private val repository: () -> ExportStageRepository?,
    private val workManager: () -> WorkManager,
    private val stagingDir: File,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : ExportStageRecorder,
    StageSweep,
    LockObserver {
    init {
        unlockManager.addLockObserver(this)
    }

    // ---- ExportStageRecorder (:core:export port) -------------------------

    /**
     * Records [stage] and schedules its sweep.
     *
     * Non-suspending because `ExportStageRecorder` is called from the middle
     * of `MarkdownPrintAdapter`'s write path; the row insert is dispatched
     * onto [scope]. The WorkManager request is enqueued FIRST, before the
     * (asynchronous) insert, so a process death between the two leaves a
     * scheduled sweep with no row — which resolves harmlessly to
     * `SweepOutcome.UNKNOWN` — rather than a row with no sweep scheduled.
     * The boot purge and the lock sweep both cover the file either way.
     */
    override fun record(stage: ExportStage) {
        StagedPlaintextSweeper.enqueue(
            workManager = workManager(),
            stageId = stage.stageId,
            delayMillis = stage.expiresAt - clock(),
        )
        val repo = repository()
        if (repo == null) {
            // Staging plaintext while locked should be impossible (an export
            // needs an open vault to have content to export).
            SkeinLog.w(TAG, "staged export recorded with no open vault; file will be swept by timer or lock")
            return
        }
        scope.launch {
            repo.insertStage(
                ExportStageRow(
                    stageId = stage.stageId,
                    path = stage.path,
                    origin = stage.origin,
                    documentId = stage.documentId,
                    revisionHash = null,
                    createdAt = stage.createdAt,
                    expiresAt = stage.expiresAt,
                ),
            )
        }
    }

    // ---- StageSweep (the WorkManager timer) ------------------------------

    override suspend fun sweep(stageId: String): SweepOutcome {
        val sweep = sweepOrNull() ?: return SweepOutcome.UNKNOWN
        return sweep.sweepIfExpired(stageId)
    }

    // ---- LockObserver ----------------------------------------------------

    override val priority: LockObserverPriority = LockObserverPriority.HIGH

    /** The zero-tolerance sweep, while the key is still live and the DB still open. */
    override suspend fun onLocking(
        epoch: Long,
        budgetMillis: Long,
    ) {
        sweepAll()
    }

    /**
     * Post-zero, the vault is closed, so no row can be written — but the
     * FILES are still deletable and must not outlive the lock even if
     * [onLocking] was cut short by the observer budget
     * (`LOCK_POLICY_INDEXING.md` §4.2). This is the belt to that braces.
     */
    override fun onLocked(epoch: Long) {
        StagedPlaintextSweep.purge(stagingDir)
    }

    /** See the file header: unlock is the first moment a previous session's rows can be settled. */
    override fun onUnlocked(epoch: Long) {
        scope.launch { sweepAll() }
    }

    // ---- internals -------------------------------------------------------

    private suspend fun sweepAll() {
        sweepOrNull()?.sweepAll() ?: StagedPlaintextSweep.purge(stagingDir)
    }

    private fun sweepOrNull(): StagedPlaintextSweep? = repository()?.let { StagedPlaintextSweep(it, stagingDir, clock) }

    private companion object {
        const val TAG = "ExportStageCoordinator"
    }
}
