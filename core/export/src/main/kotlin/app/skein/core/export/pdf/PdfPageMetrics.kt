// E2.I11 (bd skein-80m): converts a `PrintAttributes` (mils + dpi, the
// framework's units) into the pixel geometry `Paginator`/`PagePainter` work
// in. Isolated in its own file so `MarkdownPrintAdapter.onLayout` stays
// readable.

package app.skein.core.export.pdf

import android.print.PrintAttributes
import kotlin.math.roundToInt

internal data class PdfPageMetrics(
    val pageWidthPx: Int,
    val pageHeightPx: Int,
    val marginLeftPx: Float,
    val marginTopPx: Float,
    val contentWidthPx: Int,
    val contentHeightPx: Int,
) {
    companion object {
        private const val MILS_PER_INCH = 1000f
        private const val DEFAULT_DPI = 300

        fun from(attributes: PrintAttributes): PdfPageMetrics {
            val mediaSize = attributes.mediaSize ?: PrintAttributes.MediaSize.NA_LETTER
            val resolution =
                attributes.resolution
                    ?: PrintAttributes.Resolution("skein_default", "default", DEFAULT_DPI, DEFAULT_DPI)
            val dpi = resolution.horizontalDpi.toFloat()

            val pageWidthPx = milsToPx(mediaSize.widthMils, dpi)
            val pageHeightPx = milsToPx(mediaSize.heightMils, dpi)

            val margins = attributes.minMargins ?: PrintAttributes.Margins(0, 0, 0, 0)
            val marginLeftPx = margins.leftMils / MILS_PER_INCH * dpi
            val marginTopPx = margins.topMils / MILS_PER_INCH * dpi
            val marginRightPx = margins.rightMils / MILS_PER_INCH * dpi
            val marginBottomPx = margins.bottomMils / MILS_PER_INCH * dpi

            val contentWidthPx = (pageWidthPx - marginLeftPx - marginRightPx).roundToInt().coerceAtLeast(1)
            val contentHeightPx = (pageHeightPx - marginTopPx - marginBottomPx).roundToInt().coerceAtLeast(1)

            return PdfPageMetrics(
                pageWidthPx = pageWidthPx,
                pageHeightPx = pageHeightPx,
                marginLeftPx = marginLeftPx,
                marginTopPx = marginTopPx,
                contentWidthPx = contentWidthPx,
                contentHeightPx = contentHeightPx,
            )
        }

        private fun milsToPx(
            mils: Int,
            dpi: Float,
        ): Int = (mils / MILS_PER_INCH * dpi).roundToInt().coerceAtLeast(1)
    }
}
