// skein-0m1z (docs/design/POST_REVIEW_RESOLUTIONS.md §4.3) — crash/kill/reboot
// recovery for staged plaintext.
//
// ===== Why this purges files instead of reading `export_stages` =====
//
// §4.3's illustrative receiver body calls `repo.listUnswept()` and
// re-enqueues the unexpired stages. That is not reachable from a boot
// broadcast: `export_stages` lives INSIDE the SQLCipher vault, and §4.3's own
// note establishes that the vault is still locked here — "the vault is
// credential-encrypted and cannot be unlocked in direct-boot mode, so we run
// only after the user reaches the Unlock screen". Reaching the Unlock screen
// is not unlocking; there is no master key at this point and no readable row.
//
// What IS reachable is the staging directory itself, which is ordinary
// credential-encrypted app-private storage. So this receiver deletes every
// file in `cache/staging_export/` outright. That is not a weaker substitute
// for §4.3's rescan, it is a stronger one:
//
//   • Every staged file exists to be handed to an in-flight `PrintManager`
//     job. A reboot kills that job, so after a boot EVERY staged file is
//     orphaned by definition — there is no "unexpired stage" worth keeping
//     and nothing to re-enqueue a timer for.
//   • It also catches files whose row was never written (a crash between
//     spooling and recording) and files whose row was cascaded away with its
//     document — neither of which a row-driven rescan would ever see.
//
// The row side is reconciled at the first moment it is possible: the
// `ExportStageCoordinator` lock observer sweeps on unlock as well as on lock,
// so any row left unswept by this purge is settled as soon as the vault opens.
//
// ===== Why LOCKED_BOOT_COMPLETED is declared but never fires =====
//
// The manifest registers both `BOOT_COMPLETED` and `LOCKED_BOOT_COMPLETED`
// with `directBootAware="false"`, exactly as §4.3 specifies. A receiver that
// is not direct-boot-aware is not delivered `LOCKED_BOOT_COMPLETED` at all —
// the platform only dispatches it to direct-boot-aware components — so that
// action is inert today, and deliberately so: our storage is
// credential-encrypted, so a direct-boot-aware receiver could not read the
// staging directory before first unlock either. The declaration is kept
// because §4.3 specifies it and because a future direct-boot-aware variant
// would need it. Both actions are handled identically here, and a duplicate
// delivery sweeps only once (see [swept]).

package app.skein.export.stage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.skein.core.export.pdf.PdfStaging
import app.skein.core.model.SkeinLog
import app.skein.core.vault.export.stage.StagedPlaintextSweep
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action !in HANDLED_ACTIONS) return
        // §4.4's BootReceiverTest: LOCKED_BOOT_COMPLETED followed by
        // BOOT_COMPLETED must trigger exactly one sweep pass. The purge is
        // idempotent anyway; this keeps the second broadcast from re-walking
        // a directory that is already empty.
        if (!swept.compareAndSet(false, true)) return

        // Synchronous and fast: a directory walk plus unlinks, well inside a
        // receiver's main-thread budget. No `goAsync()` — there is no
        // suspending work here now that the database is out of the picture,
        // and a `goAsync` continuation that outlives the broadcast is one
        // more window in which the process can be killed mid-sweep.
        val deleted = StagedPlaintextSweep.purge(stagingDir(context))
        SkeinLog.i(TAG, "boot sweep removed $deleted staged file(s)")
    }

    /** `cache/staging_export/` — the same directory `PdfStaging` spools into. */
    private fun stagingDir(context: Context): File =
        File(context.applicationContext.cacheDir, PdfStaging.STAGING_DIR_NAME)

    companion object {
        private const val TAG = "BootReceiver"

        val HANDLED_ACTIONS: Set<String> =
            setOf(
                Intent.ACTION_BOOT_COMPLETED,
                // Declared per §4.3; never actually delivered while
                // directBootAware="false" (see the file header).
                Intent.ACTION_LOCKED_BOOT_COMPLETED,
            )

        /** Process-wide: boot broadcasts arrive once per process, and both actions share one pass. */
        private val swept = AtomicBoolean(false)

        /** Test seam — lets a test observe a fresh process's first-broadcast behaviour. */
        internal fun resetForTest() = swept.set(false)
    }
}
