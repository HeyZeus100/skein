// E2.I11 (bd skein-80m): the real `BlockMeasurer` `MarkdownPrintAdapter`
// hands to `Paginator` in production, backed by `android.text.StaticLayout`
// via `BlockLineLayout` (shared with `PagePainter` so measurement and
// drawing always agree).

package app.skein.core.export.pdf

import app.skein.core.export.pdf.layout.BlockMeasurer
import app.skein.core.export.pdf.layout.MeasuredBlock
import app.skein.core.export.pdf.layout.PrintBlock

public class AndroidBlockMeasurer(
    private val typography: PdfTypography = PdfTypography.Default,
) : BlockMeasurer {
    override fun measure(
        block: PrintBlock,
        contentWidthPx: Int,
    ): MeasuredBlock {
        val layout = BlockLineLayout.layoutFor(block, typography, contentWidthPx)
        val heights = layout.lines.map { it.heightPx }
        return MeasuredBlock(block, heights, BlockLineLayout.spacingAfter(block, typography))
    }
}
