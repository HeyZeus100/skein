// E2.I11 (bd skein-80m): draws one `Page` (the `Paginator`'s output) onto a
// `PdfDocument` page's `Canvas`, plus the "title · page N/M" footer the plan
// requires. Re-derives line geometry via `BlockLineLayout` using the same
// `PdfTypography` `AndroidBlockMeasurer` used at layout time, so what gets
// drawn always matches the page the block was placed on.

package app.skein.core.export.pdf

import android.graphics.Canvas
import app.skein.core.export.pdf.layout.Page
import app.skein.core.export.pdf.layout.PageGeometry

public class PagePainter(
    private val typography: PdfTypography = PdfTypography.Default,
) {
    public fun paint(
        canvas: Canvas,
        page: Page,
        geometry: PageGeometry,
        contentLeftPx: Float,
        contentTopPx: Float,
        title: String,
        pageNumber: Int,
        pageCount: Int,
    ) {
        var y = contentTopPx
        for (slice in page.slices) {
            val layout = BlockLineLayout.layoutFor(slice.block, typography, geometry.contentWidthPx)
            val x = contentLeftPx + layout.indentPx
            for (i in slice.lineRange) {
                val line = layout.lines[i]
                drawLine(canvas, line, x, y)
                y += line.heightPx
            }
        }
        drawFooter(canvas, geometry, contentLeftPx, contentTopPx, title, pageNumber, pageCount)
    }

    private fun drawLine(
        canvas: Canvas,
        line: RenderableLine,
        leftPx: Float,
        topPx: Float,
    ) {
        val layout = line.layout
        val lineTopInLayout = layout.getLineTop(line.lineIndexInLayout)
        canvas.save()
        canvas.clipRect(leftPx, topPx, leftPx + layout.width, topPx + line.heightPx)
        canvas.translate(leftPx, topPx - lineTopInLayout)
        layout.draw(canvas)
        canvas.restore()
    }

    /** Plan `E2.I11` acceptance criterion: "page footer shows `title · page N/M`". */
    private fun drawFooter(
        canvas: Canvas,
        geometry: PageGeometry,
        contentLeftPx: Float,
        contentTopPx: Float,
        title: String,
        pageNumber: Int,
        pageCount: Int,
    ) {
        val footerBaselineY = contentTopPx + geometry.contentHeightPx + typography.footerTextSizePx
        val text = "$title · page $pageNumber/$pageCount"
        canvas.drawText(text, contentLeftPx, footerBaselineY, typography.footerPaint)
    }
}
