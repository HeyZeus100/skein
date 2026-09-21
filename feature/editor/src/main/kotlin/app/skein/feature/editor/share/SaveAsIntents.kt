// bd `skein-fay` v1 scope, item 2: "Save as..." for file-backed formats.
// `ACTION_CREATE_DOCUMENT` (SAF) hands the caller a user-chosen destination
// `Uri`; `NoteTabState.writeSaveAs` opens an `OutputStream` on it and
// streams bytes straight from `ExportServiceImpl` — no app-private staging
// file for either format (see `ExportServiceImpl`'s own header: "no local
// plaintext staging happens inside this class" per
// `docs/design/POST_REVIEW_RESOLUTIONS.md` §4.2).
//
// PDF is deliberately NOT one of [SaveAsFormat]'s values: per
// POST_REVIEW_RESOLUTIONS §4.5, PDF stays on its already-shipped
// `PrintManager` path (`E2.I11`, bd `skein-80m`, `PdfExportService`), which
// drives the system print/"save as PDF" UI itself rather than
// `ACTION_CREATE_DOCUMENT`. `NoteTab`'s "Export as PDF" menu item calls
// `PdfExportService` directly (needs an Android `Context` `PdfExportService`
// already requires, which this pure-Kotlin object deliberately avoids).

package app.skein.feature.editor.share

import android.content.Intent
import app.skein.core.vault.export.SafeFileName

/**
 * File-backed export formats offered by the v1 "Save as..." menu (bd
 * `skein-fay`). PDF is intentionally excluded — see file header.
 */
public enum class SaveAsFormat(
    public val mimeType: String,
    public val extension: String,
) {
    MARKDOWN("text/markdown", "md"),
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
}

/** Builds the `ACTION_CREATE_DOCUMENT` intent for a "Save as [SaveAsFormat]" action. */
public object SaveAsIntents {
    /**
     * `ACTION_CREATE_DOCUMENT` + `CATEGORY_OPENABLE`, [Intent.EXTRA_TITLE] set
     * to a filesystem-safe `"<title>.<extension>"` — [SafeFileName.sanitize]
     * is the same sanitizer `ExportServiceImpl.exportVaultZip` already uses
     * for its zip entry names, reused here rather than re-implemented. No
     * `Uri` is attached to the returned intent — the system picker supplies
     * one to the caller's activity-result callback; this function never
     * sees it and never grants anything.
     */
    public fun createDocument(
        format: SaveAsFormat,
        suggestedTitle: String,
    ): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = format.mimeType
            putExtra(Intent.EXTRA_TITLE, "${SafeFileName.sanitize(suggestedTitle)}.${format.extension}")
        }
}
