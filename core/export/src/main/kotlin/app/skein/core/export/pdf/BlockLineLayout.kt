// E2.I11 (bd skein-80m): the single place that turns a `PrintBlock` into
// real `android.text.StaticLayout` line geometry, shared by
// `AndroidBlockMeasurer` (onLayout) and `PagePainter` (onWrite) so a
// pagination decision and the pixels actually drawn always agree — both
// call this object with the same `PdfTypography` and content width.

package app.skein.core.export.pdf

import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import app.skein.core.export.pdf.layout.PrintBlock
import app.skein.core.export.pdf.layout.StyledText
import app.skein.core.export.pdf.layout.TextSpan

/** One visual (wrapped) line: which [StaticLayout] it belongs to and which line index within it, plus its precomputed height. */
internal data class RenderableLine(
    val layout: StaticLayout,
    val lineIndexInLayout: Int,
    val heightPx: Int,
)

/** A block's laid-out lines plus the left indent (quotes/lists/code) [PagePainter] must apply that [AndroidBlockMeasurer] already baked into the narrower content width used to wrap [lines]. */
internal data class BlockLayout(
    val indentPx: Int,
    val lines: List<RenderableLine>,
)

internal object BlockLineLayout {
    private const val THEMATIC_BREAK_CHAR_COUNT = 24

    fun layoutFor(
        block: PrintBlock,
        typography: PdfTypography,
        contentWidthPx: Int,
    ): BlockLayout =
        when (block) {
            is PrintBlock.Heading ->
                BlockLayout(0, linesForText(block.text, typography.headingPaint(block.level), contentWidthPx))
            is PrintBlock.Paragraph ->
                BlockLayout(0, linesForText(block.text, typography.bodyPaint, contentWidthPx))
            is PrintBlock.Quote -> {
                val indent = typography.quoteIndentPx
                val width = (contentWidthPx - indent).coerceAtLeast(1)
                BlockLayout(indent, linesForText(block.text, typography.quotePaint, width))
            }
            is PrintBlock.ListEntry -> {
                val indent = typography.listIndentPx * (block.depth + 1)
                val width = (contentWidthPx - indent).coerceAtLeast(1)
                val combined = StyledText(listOf(TextSpan(block.marker)) + block.text.spans)
                BlockLayout(indent, linesForText(combined, typography.bodyPaint, width))
            }
            is PrintBlock.Code -> {
                val indent = typography.codeIndentPx
                val width = (contentWidthPx - indent).coerceAtLeast(1)
                BlockLayout(indent, linesForCode(block.lines, typography.codePaint, width))
            }
            PrintBlock.ThematicBreak -> BlockLayout(0, listOf(thematicBreakLine(typography, contentWidthPx)))
        }

    /** Extra vertical gap after the whole block — never split across a page break. */
    fun spacingAfter(
        block: PrintBlock,
        typography: PdfTypography,
    ): Int = if (block is PrintBlock.ListEntry) typography.listItemSpacingPx else typography.paragraphSpacingPx

    private fun linesForText(
        text: StyledText,
        paint: TextPaint,
        widthPx: Int,
    ): List<RenderableLine> {
        val layout = buildStaticLayout(toSpanned(text), paint, widthPx)
        return (0 until layout.lineCount).map { i ->
            RenderableLine(
                layout,
                i,
                layout.getLineBottom(i) - layout.getLineTop(i),
            )
        }
    }

    /** One [StaticLayout] per *source* line so a too-long source line wraps (plan acceptance criterion: "long code lines wrap rather than clip") while [Paginator] still cuts between source lines first. */
    private fun linesForCode(
        sourceLines: List<String>,
        paint: TextPaint,
        widthPx: Int,
    ): List<RenderableLine> =
        sourceLines.flatMap { line ->
            val layout = buildStaticLayout(line.ifEmpty { " " }, paint, widthPx)
            (0 until layout.lineCount).map { i ->
                RenderableLine(
                    layout,
                    i,
                    layout.getLineBottom(i) - layout.getLineTop(i),
                )
            }
        }

    private fun thematicBreakLine(
        typography: PdfTypography,
        widthPx: Int,
    ): RenderableLine {
        val layout = buildStaticLayout("─".repeat(THEMATIC_BREAK_CHAR_COUNT), typography.bodyPaint, widthPx)
        return RenderableLine(layout, 0, typography.thematicBreakHeightPx)
    }

    private fun toSpanned(text: StyledText): CharSequence {
        if (text.spans.isEmpty()) return " "
        val builder = SpannableStringBuilder()
        for (span in text.spans) {
            if (span.text.isEmpty()) continue
            val start = builder.length
            builder.append(span.text)
            val end = builder.length
            applyEmphasis(builder, span, start, end)
        }
        return if (builder.isEmpty()) " " else builder
    }

    private fun applyEmphasis(
        builder: SpannableStringBuilder,
        span: TextSpan,
        start: Int,
        end: Int,
    ) {
        val style =
            when {
                span.bold && span.italic -> Typeface.BOLD_ITALIC
                span.bold -> Typeface.BOLD
                span.italic -> Typeface.ITALIC
                else -> null
            }
        if (style != null) builder.setSpan(StyleSpan(style), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (span.strikethrough) builder.setSpan(StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (span.underline) builder.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (span.monospace) builder.setSpan(TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun buildStaticLayout(
        text: CharSequence,
        paint: TextPaint,
        widthPx: Int,
    ): StaticLayout {
        val safeWidth = widthPx.coerceAtLeast(1)
        return StaticLayout.Builder
            .obtain(text, 0, text.length, paint, safeWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()
    }
}
