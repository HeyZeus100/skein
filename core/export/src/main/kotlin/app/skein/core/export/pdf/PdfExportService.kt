// E2.I11 (bd skein-80m): `Transfer.kt`'s locked `ExportService` contract
// (`core/model`, skein-18j) has no PDF method — its own KDoc says why:
// "PDF export is Android-bound (`android.print.PrintManager`) and lives in
// `core/export` as `PdfExportService` ... it is intentionally not part of
// this pure-Kotlin contract." This class is that exposure point, matching
// the plan's `E2.I11` "Produces" signature verbatim:
// `PdfExportService.printAdapter(docId: DocId): PrintDocumentAdapter`.
//
// `ExportServiceImpl` (`core/vault`, skein-90d) is NOT modified or extended
// by inheritance here — this is a standalone class consuming the same
// `VaultRepository` contract, per the task's "extend via a new class"
// instruction (there is nothing on `ExportService` to override; PDF was
// never part of it).

package app.skein.core.export.pdf

import android.content.Context
import android.print.PrintDocumentAdapter
import app.skein.core.markdown.MarkdownAst
import app.skein.core.markdown.layout.MarkdownFlattener
import kotlinx.coroutines.runBlocking
import us.aherrera.skein.core.model.DocId
import us.aherrera.skein.core.model.DocumentKind
import us.aherrera.skein.core.model.VaultRepository

/**
 * Renders a vault document to PDF via [android.print.PrintManager]. The
 * caller (e.g. the "Share as PDF"/"Export" UI action) is responsible for
 * `PrintManager.print("Skein – <title>", printAdapter(docId), attributes)`;
 * this class only builds the [PrintDocumentAdapter].
 */
public class PdfExportService(
    private val context: Context,
    private val repository: VaultRepository,
    private val stageRecorder: ExportStageRecorder = NoOpExportStageRecorder,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Fetches [docId] and parses its Markdown body into the `:core:markdown`
     * render tree (`E7.I2`) synchronously — [PrintDocumentAdapter] has no
     * suspending construction hook, so the document is loaded eagerly here
     * rather than lazily inside [android.print.PrintDocumentAdapter.onLayout].
     * Mirrors `SyntheticVault.seed`'s `runBlocking` bridge from this
     * codebase's suspending `VaultRepository` to a synchronous call site.
     */
    public fun printAdapter(docId: DocId): PrintDocumentAdapter {
        val document =
            runBlocking { repository.getDocument(docId) }
                ?: throw NoSuchElementException("no document with id=$docId")
        check(document.kind != DocumentKind.ATTACHMENT) {
            "cannot render an ATTACHMENT document as PDF: $docId"
        }
        val renderTree = MarkdownAst.parse(document.bodyMd.orEmpty())
        val blocks = MarkdownFlattener.flatten(renderTree)
        return MarkdownPrintAdapter(
            context = context,
            documentId = docId,
            title = document.title,
            blocks = blocks,
            stageRecorder = stageRecorder,
            clock = clock,
        )
    }
}
