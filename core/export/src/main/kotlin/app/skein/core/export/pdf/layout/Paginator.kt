// E2.I11 (bd skein-80m): pure-Kotlin pagination over the `PrintBlock`
// render tree. No `android.*` import — see the header comment on
// `PrintBlock.kt` for why this file, `PrintBlock.kt`, `BlockMeasurer.kt`,
// and `MarkdownFlattener.kt` are kept Android-free and JVM-unit-testable.

package app.skein.core.export.pdf.layout

/** The printable content area of one page, in device pixels (page size minus margins), as `MarkdownPrintAdapter.onLayout` computes it from `PrintAttributes`. */
public data class PageGeometry(
    val contentWidthPx: Int,
    val contentHeightPx: Int,
) {
    init {
        require(contentWidthPx > 0) { "contentWidthPx must be positive, was $contentWidthPx" }
        require(contentHeightPx > 0) { "contentHeightPx must be positive, was $contentHeightPx" }
    }
}

/**
 * A contiguous slice of one [PrintBlock] placed on a page. [lineRange] is a
 * 0-based, end-exclusive range into the block's `MeasuredBlock.lineHeightsPx`
 * (and, for [PrintBlock.Code], into `lines`) — usually the whole block
 * (`0 until lineCount`), narrower only when the block was cut across a page
 * boundary.
 */
public data class PageSlice(
    val block: PrintBlock,
    val lineRange: IntRange,
    val heightPx: Int,
)

public data class Page(
    val slices: List<PageSlice>,
)

/**
 * Packs [PrintBlock]s into [Page]s that each fit within [PageGeometry],
 * measuring every block with [measurer]. A block taller than a full page
 * (e.g. a long code listing) is cut at a line boundary and continues on the
 * following page(s), per the plan's "cuts at block boundaries, splitting
 * long blocks by line".
 */
public class Paginator(
    private val measurer: BlockMeasurer,
) {
    /**
     * @param isCancelled polled once per page boundary (i.e. at least once
     *   per page, before starting the next one); when it returns `true`,
     *   pagination stops immediately and the pages produced so far are
     *   returned — `MarkdownPrintAdapter.onLayout` wires this to the
     *   `CancellationSignal` it's handed so "cancellation stops layout
     *   within one page" (acceptance criterion) holds without the adapter
     *   needing to know anything about how pagination works internally.
     */
    public fun paginate(
        blocks: List<PrintBlock>,
        geometry: PageGeometry,
        isCancelled: () -> Boolean = { false },
    ): List<Page> {
        val pages = mutableListOf<Page>()
        var current = mutableListOf<PageSlice>()
        var usedHeightPx = 0

        fun flushPage() {
            pages += Page(current)
            current = mutableListOf()
            usedHeightPx = 0
        }

        blockLoop@ for (block in blocks) {
            if (isCancelled()) break@blockLoop
            val measured = measurer.measure(block, geometry.contentWidthPx)

            if (measured.totalHeightPx <= geometry.contentHeightPx) {
                // Whole block fits on a page — start a new one first if it
                // doesn't fit in what's left of the current page.
                if (current.isNotEmpty() && usedHeightPx + measured.totalHeightPx > geometry.contentHeightPx) {
                    flushPage()
                }
                current += PageSlice(block, measured.lineHeightsPx.indices, measured.contentHeightPx)
                usedHeightPx += measured.totalHeightPx
                continue@blockLoop
            }

            // Block is taller than one full page on its own: cut it by line.
            var lineStart = 0
            val lines = measured.lineHeightsPx
            while (lineStart < lines.size) {
                if (current.isNotEmpty() && usedHeightPx >= geometry.contentHeightPx) flushPage()
                var lineEnd = lineStart
                var sliceHeight = 0
                while (lineEnd < lines.size &&
                    usedHeightPx + sliceHeight + lines[lineEnd] <= geometry.contentHeightPx
                ) {
                    sliceHeight += lines[lineEnd]
                    lineEnd++
                }
                if (lineEnd == lineStart) {
                    // A single line is itself taller than the whole page
                    // (pathological content) — place it alone rather than
                    // looping forever; it will visually overflow the page.
                    sliceHeight = lines[lineEnd]
                    lineEnd++
                }
                current += PageSlice(block, lineStart until lineEnd, sliceHeight)
                usedHeightPx += sliceHeight
                lineStart = lineEnd
                if (lineStart < lines.size) flushPage()
                if (isCancelled()) break@blockLoop
            }
            usedHeightPx += measured.spacingAfterPx
        }

        if (current.isNotEmpty() || pages.isEmpty()) flushPage()
        return pages
    }
}
