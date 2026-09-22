// E2.I11 (bd skein-80m): where the rendered PDF is spooled before being
// copied into the print framework's destination `ParcelFileDescriptor` —
// `cache/staging_export/`, the exact directory `docs/design/
// POST_REVIEW_RESOLUTIONS.md` §4.3 excludes from both `data_extraction_rules.xml`
// (API 31+) and `backup_rules_legacy.xml` (API 30), and the directory
// `skein-0m1z`'s `StagedPlaintextSweep`/`BootReceiver` now sweep (via
// [STAGING_DIR_NAME], which `:app` reads rather than re-declaring). Always
// under `context.cacheDir` — never a user-writable path.

package app.skein.core.export.pdf

import android.content.Context
import java.io.File

public object PdfStaging {
    public const val STAGING_DIR_NAME: String = "staging_export"

    public fun stagingDir(context: Context): File = File(context.cacheDir, STAGING_DIR_NAME).apply { mkdirs() }

    /** [stageId] prefixes the file name so two concurrent exports of the same (or identically-titled) document never collide. */
    public fun stagedFile(
        context: Context,
        stageId: String,
        title: String,
    ): File = File(stagingDir(context), "$stageId-${PdfFileNaming.fileName(title)}")
}
