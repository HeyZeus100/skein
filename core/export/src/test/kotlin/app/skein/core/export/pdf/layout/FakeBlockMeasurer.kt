// E2.I11 (bd skein-80m): a deterministic, Android-free stand-in for
// `AndroidBlockMeasurer` (`StaticLayout`) so `PaginatorTest` can assert
// pagination decisions without a device or Robolectric — "JVM: layout/
// pagination logic against the render tree ... with a fake page size" per
// the task brief.

package app.skein.core.export.pdf.layout

import app.skein.core.markdown.layout.PrintBlock
import kotlin.math.ceil

/**
 * Every block reports [lineHeightPx]-tall lines, wrapping text blocks every
 * [charsPerLine] characters (a crude but deterministic stand-in for real
 * text wrapping) and reporting exactly one line per source line for
 * [PrintBlock.Code] — matching the real measurer's "cut code at source
 * line boundaries" behavior without needing font metrics.
 */
class FakeBlockMeasurer(
    private val lineHeightPx: Int = LINE_HEIGHT_PX,
    private val charsPerLine: Int = CHARS_PER_LINE,
    private val spacingAfterPx: Int = lineHeightPx,
) : BlockMeasurer {
    override fun measure(
        block: PrintBlock,
        contentWidthPx: Int,
    ): MeasuredBlock =
        when (block) {
            is PrintBlock.Heading -> wrappedText(block, block.text.plain)
            is PrintBlock.Paragraph -> wrappedText(block, block.text.plain)
            is PrintBlock.Quote -> wrappedText(block, block.text.plain)
            is PrintBlock.ListEntry -> wrappedText(block, block.marker + block.text.plain)
            is PrintBlock.Code -> MeasuredBlock(block, block.lines.map { lineHeightPx }, spacingAfterPx)
            PrintBlock.ThematicBreak -> MeasuredBlock(block, listOf(lineHeightPx), spacingAfterPx)
        }

    private fun wrappedText(
        block: PrintBlock,
        text: String,
    ): MeasuredBlock {
        val lineCount = ceil(text.length.coerceAtLeast(1) / charsPerLine.toDouble()).toInt().coerceAtLeast(1)
        return MeasuredBlock(block, List(lineCount) { lineHeightPx }, spacingAfterPx)
    }

    companion object {
        private const val LINE_HEIGHT_PX = 10
        private const val CHARS_PER_LINE = 1_000
    }
}
