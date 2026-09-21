// E2.I11 (bd skein-80m): the seam between the Android-free `Paginator` and
// real text measurement. `AndroidBlockMeasurer` (same module,
// `app.skein.core.export.pdf` package) implements this with
// `android.text.StaticLayout`; JVM tests use a small deterministic fake
// (see `PaginatorTest`) so pagination decisions can be asserted without a
// device, an emulator, or Robolectric.

package app.skein.core.export.pdf.layout

import app.skein.core.markdown.layout.PrintBlock

/**
 * The height, in device pixels, of every atomic paintable unit inside one
 * [PrintBlock] at a given content width — a wrapped visual line for
 * [PrintBlock.Heading]/[PrintBlock.Paragraph]/[PrintBlock.Quote]/
 * [PrintBlock.ListEntry], or a source line for [PrintBlock.Code]. A block
 * with a single indivisible unit (e.g. [PrintBlock.ThematicBreak], or any
 * block that fits on one visual line) reports a one-element list.
 *
 * [lineHeightsPx] lets [Paginator] cut a block that doesn't fit on one page
 * at a unit boundary ("splitting long blocks by line" per the plan) without
 * itself knowing anything about font metrics or text wrapping.
 */
public data class MeasuredBlock(
    val block: PrintBlock,
    val lineHeightsPx: List<Int>,
    /** Extra vertical space after the block (paragraph/list spacing), not part of any single line and never split across a page break. */
    val spacingAfterPx: Int = 0,
) {
    init {
        require(lineHeightsPx.isNotEmpty()) { "a measured block must report at least one line" }
    }

    public val contentHeightPx: Int get() = lineHeightsPx.sum()
    public val totalHeightPx: Int get() = contentHeightPx + spacingAfterPx
}

public fun interface BlockMeasurer {
    public fun measure(
        block: PrintBlock,
        contentWidthPx: Int,
    ): MeasuredBlock
}
