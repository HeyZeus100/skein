// E2.I11 (bd skein-80m): the record `MarkdownPrintAdapter` produces once it
// spools a rendered PDF to `cache/staging_export/` — the "internal staging"
// case `docs/design/export-plaintext-lifetime.md` and
// `docs/design/POST_REVIEW_RESOLUTIONS.md` §4.3 carve out from the
// zero-staging `ACTION_CREATE_DOCUMENT` Markdown/zip path (`ExportServiceImpl`,
// skein-90d, NOT modified by this bead).
//
// `skein-0m1z` has LANDED: `app.skein.export.stage.ExportStageCoordinator`
// (`:app`) is the real [ExportStageRecorder] — it writes the `export_stages`
// row (Migration 005) and enqueues the `StagedPlaintextSweeper` that deletes
// the file after [ExportStage.expiresAt], alongside the `BootReceiver` and
// on-lock sweeps. [NoOpExportStageRecorder] remains the DEFAULT only because
// nothing constructs [PdfExportService] yet (E2.I11's PDF export UI is not
// wired); whoever wires it MUST pass the coordinator instead. See
// `bd show skein-0m1z`.
//
// No `android.*` import — [ExportStageFactory.create] is plain-JUnit-testable.

package app.skein.core.export.pdf

import app.skein.core.model.DocId

/** One row `skein-0m1z`'s `export_stages` table (Migration 005) will eventually persist. */
public data class ExportStage(
    val stageId: String,
    val path: String,
    val origin: String,
    val documentId: DocId,
    val createdAt: Long,
    val expiresAt: Long,
)

public fun interface ExportStageRecorder {
    public fun record(stage: ExportStage)
}

/**
 * Inert [ExportStageRecorder], the default only because no production call
 * site constructs [PdfExportService] yet. Recording nothing leaves the file
 * on disk under the directory §4.3 excludes from backups, and the boot purge
 * and on-lock sweep still delete it — but nothing bounds its life to ten
 * minutes. The real recorder is `ExportStageCoordinator` in `:app`
 * (skein-0m1z); E2.I11's PDF export UI must pass that, not this.
 */
public object NoOpExportStageRecorder : ExportStageRecorder {
    override fun record(stage: ExportStage) {
        // See the class KDoc above and `docs/design/export-plaintext-lifetime.md`.
    }
}

/** Builds the [ExportStage] row for a freshly-staged PDF. Pure function of its arguments — see the file header for why this is kept separate from the Android-only file-writing code path in `MarkdownPrintAdapter`. */
public object ExportStageFactory {
    public const val ORIGIN_PDF: String = "pdf_export"

    /** `docs/design/POST_REVIEW_RESOLUTIONS.md` §4.2/§4.3: a fixed 10-minute lifetime unless a device lock sweeps it sooner. */
    public const val DEFAULT_LIFETIME_MILLIS: Long = 10L * 60L * 1000L

    public fun create(
        stageId: String,
        documentId: DocId,
        path: String,
        createdAt: Long,
        lifetimeMillis: Long = DEFAULT_LIFETIME_MILLIS,
    ): ExportStage =
        ExportStage(
            stageId = stageId,
            path = path,
            origin = ORIGIN_PDF,
            documentId = documentId,
            createdAt = createdAt,
            expiresAt = createdAt + lifetimeMillis,
        )
}
