// E2.I11 (bd skein-80m): the fonts/sizes/spacing shared by `AndroidBlockMeasurer`
// (onLayout) and `PagePainter` (onWrite) — both read from the same instance
// so a pagination decision always matches what gets drawn.

package app.skein.core.export.pdf

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import kotlin.math.roundToInt

/**
 * Point sizes converted to device pixels at [dpi] (the resolution
 * `MarkdownPrintAdapter.onLayout` reads from `PrintAttributes.resolution`),
 * mirroring how the rest of the print/PDF frameworks size things in points
 * rather than raw pixels.
 */
public class PdfTypography(
    private val dpi: Float = DEFAULT_DPI,
) {
    private fun pt(points: Float): Float = points * dpi / POINTS_PER_INCH

    public val bodyTextSizePx: Float = pt(BODY_POINT_SIZE)
    public val codeTextSizePx: Float = pt(CODE_POINT_SIZE)
    public val footerTextSizePx: Float = pt(FOOTER_POINT_SIZE)
    public val paragraphSpacingPx: Int = pt(PARAGRAPH_SPACING_POINTS).roundToInt()
    public val listItemSpacingPx: Int = pt(LIST_ITEM_SPACING_POINTS).roundToInt()
    public val listIndentPx: Int = pt(LIST_INDENT_POINTS).roundToInt()
    public val quoteIndentPx: Int = pt(QUOTE_INDENT_POINTS).roundToInt()
    public val codeIndentPx: Int = pt(CODE_INDENT_POINTS).roundToInt()
    public val thematicBreakHeightPx: Int = pt(THEMATIC_BREAK_HEIGHT_POINTS).roundToInt()
    public val footerReservedPx: Int = (footerTextSizePx * FOOTER_RESERVED_MULTIPLIER).roundToInt()

    /**
     * Serif is the "sans/serif fallback for prose" the plan calls for.
     * IBM Plex Mono is bundled by `:feature:shell` (`res/font/ibm_plex_mono_*.ttf`)
     * for the app's UI chrome — pulling that resource into `:core:export`
     * would invert the feature -> core dependency direction, so code text
     * below uses the platform's built-in monospace family instead of the
     * bundled Plex Mono file. Swapping in the real font (e.g. a `Typeface`
     * passed in by the caller, once it's exposed from a module `:core:export`
     * can depend on) is a follow-up, not a functional gap in pagination.
     */
    public val bodyPaint: TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = bodyTextSizePx
            typeface = Typeface.SERIF
        }

    public val quotePaint: TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            set(bodyPaint)
            typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
        }

    public val codePaint: TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = codeTextSizePx
            typeface = Typeface.MONOSPACE
        }

    public val footerPaint: TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = footerTextSizePx
            typeface = Typeface.SERIF
            color = Color.DKGRAY
        }

    public fun headingPaint(level: Int): TextPaint {
        val scale = HEADING_SCALE[level] ?: 1f
        return TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            set(bodyPaint)
            textSize = bodyTextSizePx * scale
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }
    }

    public companion object {
        public val Default: PdfTypography by lazy { PdfTypography() }

        private const val DEFAULT_DPI = 300f
        private const val POINTS_PER_INCH = 72f
        private const val BODY_POINT_SIZE = 10.5f
        private const val CODE_POINT_SIZE = 9.5f
        private const val FOOTER_POINT_SIZE = 8f
        private const val PARAGRAPH_SPACING_POINTS = 6f
        private const val LIST_ITEM_SPACING_POINTS = 2f
        private const val LIST_INDENT_POINTS = 18f
        private const val QUOTE_INDENT_POINTS = 18f
        private const val CODE_INDENT_POINTS = 10f
        private const val THEMATIC_BREAK_HEIGHT_POINTS = 14f
        private const val FOOTER_RESERVED_MULTIPLIER = 3f
        private val HEADING_SCALE = mapOf(1 to 1.8f, 2 to 1.5f, 3 to 1.3f, 4 to 1.15f, 5 to 1.05f, 6 to 1f)
    }
}
