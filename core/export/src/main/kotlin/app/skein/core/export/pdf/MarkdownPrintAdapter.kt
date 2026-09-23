// E2.I11 (bd skein-80m): `android.print.PrintDocumentAdapter` shell. Owns
// nothing but Android glue — pagination (`Paginator`) and text geometry
// (`BlockLineLayout`/`AndroidBlockMeasurer`/`PagePainter`) all live in
// separately unit-testable classes; this file's job is only to satisfy the
// `PrintDocumentAdapter` contract and stage the rendered PDF per
// `docs/design/export-plaintext-lifetime.md`.

package app.skein.core.export.pdf

import android.content.Context
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import app.skein.core.export.pdf.layout.Page
import app.skein.core.export.pdf.layout.PageGeometry
import app.skein.core.export.pdf.layout.Paginator
import app.skein.core.markdown.layout.PrintBlock
import app.skein.core.model.DocId
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/**
 * Renders [blocks] (already flattened from the document's Markdown render
 * tree by [PdfExportService]) to PDF via [PagePainter]/[PdfDocument],
 * paginating with [Paginator] on [onLayout] and drawing on [onWrite] — the
 * shape the plan (`E2.I11`) specifies. The UI is responsible for calling
 * `PrintManager.print("Skein – $title", adapter, attributes)`; this class
 * only implements the adapter contract.
 */
public class MarkdownPrintAdapter(
    private val context: Context,
    private val documentId: DocId,
    private val title: String,
    private val blocks: List<PrintBlock>,
    private val typography: PdfTypography = PdfTypography.Default,
    private val painter: PagePainter = PagePainter(typography),
    private val stageRecorder: ExportStageRecorder = NoOpExportStageRecorder,
    private val clock: () -> Long = System::currentTimeMillis,
    private val measurerFactory: () -> AndroidBlockMeasurer = { AndroidBlockMeasurer(typography) },
) : PrintDocumentAdapter() {
    private var pages: List<Page> = emptyList()
    private var geometry: PageGeometry = PageGeometry(1, 1)
    private var pageWidthPx: Int = 1
    private var pageHeightPx: Int = 1
    private var marginLeftPx: Float = 0f
    private var marginTopPx: Float = 0f

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal.isCanceled) {
            callback.onLayoutCancelled()
            return
        }

        val metrics = PdfPageMetrics.from(newAttributes)
        pageWidthPx = metrics.pageWidthPx
        pageHeightPx = metrics.pageHeightPx
        marginLeftPx = metrics.marginLeftPx
        marginTopPx = metrics.marginTopPx
        val contentHeightPx =
            (metrics.contentHeightPx - typography.footerReservedPx).coerceAtLeast(
                MIN_CONTENT_HEIGHT_PX,
            )
        geometry = PageGeometry(metrics.contentWidthPx, contentHeightPx)

        val paginator = Paginator(measurerFactory())
        pages = paginator.paginate(blocks, geometry, isCancelled = cancellationSignal::isCanceled)

        if (cancellationSignal.isCanceled) {
            callback.onLayoutCancelled()
            return
        }

        val changed = oldAttributes != newAttributes
        val info =
            PrintDocumentInfo
                .Builder(PdfFileNaming.fileName(title))
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(pages.size)
                .build()
        callback.onLayoutFinished(info, changed)
    }

    override fun onWrite(
        pageRanges: Array<PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        callback: WriteResultCallback,
    ) {
        val stageId = UUID.randomUUID().toString()
        val stagedFile = PdfStaging.stagedFile(context, stageId, title)
        val document = PdfDocument()
        try {
            for ((index, page) in pages.withIndex()) {
                if (cancellationSignal.isCanceled) {
                    document.close()
                    callback.onWriteCancelled()
                    return
                }
                if (!pageRanges.includesPage(index)) continue
                val pdfPage =
                    document.startPage(
                        PdfDocument.PageInfo.Builder(pageWidthPx, pageHeightPx, index).create(),
                    )
                painter.paint(pdfPage.canvas, page, geometry, marginLeftPx, marginTopPx, title, index + 1, pages.size)
                document.finishPage(pdfPage)
            }

            if (cancellationSignal.isCanceled) {
                document.close()
                callback.onWriteCancelled()
                return
            }

            FileOutputStream(stagedFile).use { document.writeTo(it) }

            // Stage first, record second: the file must exist on disk before
            // a sweeper (skein-0m1z, not implemented by this bead) could
            // possibly act on the row below.
            val createdAt = clock()
            stageRecorder.record(
                ExportStageFactory.create(
                    stageId = stageId,
                    documentId = documentId,
                    path = stagedFile.absolutePath,
                    createdAt = createdAt,
                ),
            )

            FileInputStream(stagedFile).use { input ->
                FileOutputStream(destination.fileDescriptor).use { output -> input.copyTo(output) }
            }

            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (error: IOException) {
            callback.onWriteFailed(error.message)
        } finally {
            document.close()
        }
    }

    private fun Array<PageRange>.includesPage(index: Int): Boolean = any { index >= it.start && index <= it.end }

    private companion object {
        const val MIN_CONTENT_HEIGHT_PX = 1
    }
}
