// E2.I11 (bd skein-80m): pagination logic against the `PrintBlock` render
// tree (headings, paragraphs, code blocks, lists) with a fake page size —
// no Android dependency, per the task brief.

package app.skein.core.export.pdf.layout

import app.skein.core.markdown.layout.PrintBlock
import app.skein.core.markdown.layout.StyledText
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PaginatorTest {
    // FakeBlockMeasurer: 10px per line, 10px spacing after every block (so
    // any single-line block is 20px tall) unless overridden per test.
    private val measurer = FakeBlockMeasurer()
    private val paginator = Paginator(measurer)

    @Test
    fun `headings paragraphs and lists that fit within one page all land on a single page`() {
        val blocks =
            listOf(
                PrintBlock.Heading(1, StyledText.of("Title")),
                PrintBlock.Paragraph(StyledText.of("Body paragraph.")),
                PrintBlock.ListEntry(marker = "• ", text = StyledText.of("first item"), depth = 0),
                PrintBlock.ListEntry(marker = "• ", text = StyledText.of("second item"), depth = 0),
            )
        val geometry = PageGeometry(contentWidthPx = 500, contentHeightPx = 1_000)

        val pages = paginator.paginate(blocks, geometry)

        assertThat(pages).hasSize(1)
        assertThat(pages.single().slices.map { it.block }).isEqualTo(blocks)
    }

    @Test
    fun `a block that no longer fits in the remaining space on a page starts a new page`() {
        // Each paragraph is 20px (10 line + 10 spacing); a 45px page fits two, not three.
        val blocks = (1..3).map { PrintBlock.Paragraph(StyledText.of("p$it")) }
        val geometry = PageGeometry(contentWidthPx = 500, contentHeightPx = 45)

        val pages = paginator.paginate(blocks, geometry)

        assertThat(pages).hasSize(2)
        assertThat(pages[0].slices).hasSize(2)
        assertThat(pages[1].slices).hasSize(1)
    }

    @Test
    fun `a code block taller than a page is split across pages at source line boundaries`() {
        val codeLines = (1..25).map { "val line$it = $it" }
        val block = PrintBlock.Code(language = "kotlin", lines = codeLines)
        // 10px per source line, page fits 10 lines of pure content.
        val geometry = PageGeometry(contentWidthPx = 500, contentHeightPx = 100)

        val pages = paginator.paginate(listOf(block), geometry)

        assertThat(pages.size).isAtLeast(3)
        // Every slice stays within this one code block, and reassembling the
        // slices in page order reproduces every original source line, once
        // each, in order — "cuts at block boundaries, splitting long blocks
        // by line" without losing or duplicating content.
        val reconstructed =
            pages.flatMap { page ->
                page.slices.flatMap { slice ->
                    require(slice.block === block)
                    slice.lineRange.map { codeLines[it] }
                }
            }
        assertThat(reconstructed).isEqualTo(codeLines)
    }

    @Test
    fun `a block taller than a page starts on a fresh page when the current page already has content`() {
        val short = PrintBlock.Paragraph(StyledText.of("short"))
        val longCode = PrintBlock.Code("text", (1..20).map { "line $it" })
        // Page fits the short paragraph (20px) plus a little more, but nowhere near the whole code block.
        val geometry = PageGeometry(contentWidthPx = 500, contentHeightPx = 60)

        val pages = paginator.paginate(listOf(short, longCode), geometry)

        assertThat(pages.first().slices.map { it.block }).contains(short)
    }

    @Test
    fun `cancellation stops pagination before every block is placed`() {
        val blocks = (1..20).map { PrintBlock.Paragraph(StyledText.of("paragraph $it")) }
        val geometry = PageGeometry(contentWidthPx = 500, contentHeightPx = 40) // 2 blocks/page

        val fullPages = paginator.paginate(blocks, geometry)
        var calls = 0
        val cancelledPages = paginator.paginate(blocks, geometry, isCancelled = { (calls++) >= 4 })

        val fullBlockCount = fullPages.sumOf { it.slices.size }
        val cancelledBlockCount = cancelledPages.sumOf { it.slices.size }

        assertThat(cancelledBlockCount).isLessThan(fullBlockCount)
        assertThat(cancelledPages.size).isAtMost(fullPages.size)
    }

    @Test
    fun `an empty block list still produces exactly one (empty) page`() {
        val geometry = PageGeometry(contentWidthPx = 500, contentHeightPx = 100)

        val pages = paginator.paginate(emptyList(), geometry)

        assertThat(pages).hasSize(1)
        assertThat(pages.single().slices).isEmpty()
    }
}
