package app.skein.core.rag.chunk

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MarkdownBlockScannerTest {
    @Test
    fun blankSourceProducesNoBlocks() {
        val blocks = MarkdownBlockScanner.scan("   \n\t\n  ")

        assertThat(blocks).isEmpty()
    }

    @Test
    fun blocksPartitionTheWholeSource() {
        val source = "# Title\n\nParagraph one.\n\n- item a\n- item b\n\n> quoted\n\nParagraph two."

        val blocks = MarkdownBlockScanner.scan(source)

        assertThat(blocks.first().start).isEqualTo(0)
        assertThat(blocks.last().end).isEqualTo(source.length)
        for (i in 1 until blocks.size) assertThat(blocks[i].start).isEqualTo(blocks[i - 1].end)
    }

    @Test
    fun everyBlockSpanIsAnExactSubstringOfTheSource() {
        val source = "# Title\n\nParagraph one.\n\n- item a\n- item b\n\n> quoted\n\nParagraph two."

        val blocks = MarkdownBlockScanner.scan(source)

        for (b in blocks) assertThat(source.substring(b.start, b.end)).isNotEmpty()
    }

    @Test
    fun atxHeadingIsRecognizedWithItsLevelAndText() {
        val blocks = MarkdownBlockScanner.scan("## Section Title\n\nbody")

        assertThat(blocks.first().kind).isEqualTo(BlockKind.HEADING)
        assertThat(blocks.first().headingLevel).isEqualTo(2)
        assertThat(blocks.first().headingText).isEqualTo("Section Title")
    }

    @Test
    fun setextLevelOneHeadingIsRecognized() {
        val blocks = MarkdownBlockScanner.scan("Title\n=====\n\nbody")

        assertThat(blocks.first().kind).isEqualTo(BlockKind.HEADING)
        assertThat(blocks.first().headingLevel).isEqualTo(1)
    }

    @Test
    fun fencedCodeBlockSpansFromOpenToCloseMarker() {
        val source = "```kotlin\nval x = 1\n\nval y = 2\n```"

        val blocks = MarkdownBlockScanner.scan(source)

        assertThat(blocks).hasSize(1)
        assertThat(blocks.single().kind).isEqualTo(BlockKind.FENCE)
        assertThat(source.substring(blocks.single().start, blocks.single().end)).isEqualTo(source)
    }

    @Test
    fun blankLinesInsideAFenceDoNotEndTheBlock() {
        val source = "```\na\n\nb\n```\n\nafter"

        val blocks = MarkdownBlockScanner.scan(source)

        assertThat(blocks).hasSize(2)
        assertThat(blocks[0].kind).isEqualTo(BlockKind.FENCE)
        assertThat(blocks[1].kind).isEqualTo(BlockKind.PARAGRAPH)
    }

    @Test
    fun consecutiveListItemsFormOneListBlock() {
        val source = "- one\n- two\n- three\n\nafter"

        val blocks = MarkdownBlockScanner.scan(source)

        assertThat(blocks).hasSize(2)
        assertThat(blocks[0].kind).isEqualTo(BlockKind.LIST)
        assertThat(source.substring(blocks[0].start, blocks[0].end)).contains("one")
        assertThat(source.substring(blocks[0].start, blocks[0].end)).contains("three")
    }

    @Test
    fun aHeadingInterruptsAParagraph() {
        val blocks = MarkdownBlockScanner.scan("some text\n# Heading\nmore text")

        assertThat(
            blocks.map { it.kind },
        ).isEqualTo(listOf(BlockKind.PARAGRAPH, BlockKind.HEADING, BlockKind.PARAGRAPH))
    }

    @Test
    fun blockquoteLinesFormOneQuoteBlock() {
        val source = "> line one\n> line two\n\nafter"

        val blocks = MarkdownBlockScanner.scan(source)

        assertThat(blocks[0].kind).isEqualTo(BlockKind.QUOTE)
    }

    @Test
    fun thematicBreakIsItsOwnBlock() {
        val blocks = MarkdownBlockScanner.scan("above\n\n---\n\nbelow")

        assertThat(blocks.map { it.kind }).isEqualTo(
            listOf(BlockKind.PARAGRAPH, BlockKind.THEMATIC_BREAK, BlockKind.PARAGRAPH),
        )
    }
}
