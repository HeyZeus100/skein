// skein-0m1z (docs/design/POST_REVIEW_RESOLUTIONS.md §4.3) — the actual
// "delete the plaintext" step, shared by all three triggers:
//
//   • the 10-minute timer      — `StagedPlaintextSweeper` (`:app`, WorkManager)
//   • lock                     — the HIGH-priority `LockObserver` (`:app`)
//   • boot / crash recovery    — `BootReceiver` (`:app`), which can only use
//                                [purgeDirectory] because the vault is still
//                                locked at BOOT_COMPLETED.
//
// Plain Kotlin + `java.io.File`: no `android.*` import, so every branch below
// is exercised by ordinary JVM unit tests rather than only on a device.
//
// Logging discipline (spec §9): counts only. A staged file's path is
// content-free but still identifies what the user exported and when, so it
// is never logged at info level — and its CONTENT never at any level.

package app.skein.core.vault.export.stage

import app.skein.core.model.SkeinLog
import java.io.File

/** What [StagedPlaintextSweep.sweepIfExpired] did with one stage. */
public enum class SweepOutcome {
    /** The file is gone (or was already gone) and the row is marked swept. */
    SWEPT,

    /** No such unswept row — already swept, or cascaded away with its document. */
    UNKNOWN,

    /**
     * `expires_at` has not been reached yet. The caller should reschedule
     * rather than delete: this is §4.3's "paranoid path", reached only when
     * WorkManager runs a request before its initial delay elapsed.
     */
    PREMATURE,
}

/**
 * Deletes staged plaintext and keeps `export_stages` in step.
 *
 * [stagingDir] is `cache/staging_export/` — the one directory an export is
 * allowed to spool plaintext into (`PdfStaging.stagingDir`), and the
 * directory both backup rule files exclude (§4.3).
 */
public class StagedPlaintextSweep(
    private val repository: ExportStageRepository,
    private val stagingDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Sweeps one stage if it has reached its `expires_at`, per §4.3's
     * `StagedPlaintextSweeper.doWork`.
     */
    public suspend fun sweepIfExpired(stageId: String): SweepOutcome {
        val row = repository.getStage(stageId) ?: return SweepOutcome.UNKNOWN
        if (row.swept) return SweepOutcome.UNKNOWN
        if (clock() < row.expiresAt) return SweepOutcome.PREMATURE
        deleteFile(row.path)
        repository.markStageSwept(stageId)
        return SweepOutcome.SWEPT
    }

    /**
     * §4.3's on-lock sweep: "an immediate sweep runs regardless of
     * `expires_at` (locked = zero tolerance for staged plaintext)".
     *
     * Sweeps the DIRECTORY, not just the rows. A row can be cascaded away by
     * `ON DELETE CASCADE` while its file is still on disk (see 005's header),
     * and a crash can leave a file that was never recorded at all, so
     * row-driven deletion alone would leave plaintext behind. Rows are then
     * all marked swept, which also settles any row whose file had already
     * gone.
     *
     * Returns the number of files deleted.
     */
    public suspend fun sweepAll(): Int {
        val deleted = purgeDirectory()
        val rows = repository.markAllStagesSwept()
        SkeinLog.d(TAG, "swept staged plaintext: files=$deleted rows=$rows")
        return deleted
    }

    /** [purge] over this instance's [stagingDir]. Touches no database. */
    public fun purgeDirectory(): Int = purge(stagingDir)

    private fun deleteFile(path: String): Boolean = deleteFile(File(path))

    public companion object {
        private const val TAG = "StagedPlaintextSweep"

        /**
         * Deletes every file under [stagingDir]. Takes no repository at all,
         * because the one caller that needs it — `BootReceiver` — runs while
         * the vault is still locked: at `BOOT_COMPLETED` the SQLCipher
         * database cannot be opened (§4.3's own note: the receiver runs
         * "only after the user reaches the Unlock screen", which is not an
         * unlock), so `export_stages` is unreadable there.
         *
         * It is also the correct sweep at boot: every staged file exists to
         * feed an in-flight `PrintManager` job, a reboot kills that job, so
         * after a boot every file here is orphaned by definition.
         *
         * Returns the number of entries deleted.
         */
        public fun purge(stagingDir: File): Int {
            val entries = stagingDir.listFiles().orEmpty()
            var deleted = 0
            for (entry in entries) {
                if (entry.isDirectory) {
                    if (entry.deleteRecursively()) deleted++
                } else if (deleteFile(entry)) {
                    deleted++
                }
            }
            return deleted
        }

        private fun deleteFile(file: File): Boolean {
            val gone = runCatching { !file.exists() || file.delete() }.getOrDefault(false)
            if (!gone) {
                // Path deliberately absent: a failure to delete is worth a
                // warning, but naming the file would persist what the user
                // exported into logcat (spec §9).
                SkeinLog.w(TAG, "failed to delete a staged plaintext file")
            }
            return gone
        }
    }
}
