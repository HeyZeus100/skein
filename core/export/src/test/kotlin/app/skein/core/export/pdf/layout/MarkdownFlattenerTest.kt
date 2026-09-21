// E2.I11 (bd skein-80m): `MarkdownFlattener` against hand-built
// `:core:markdown` AST fragments (headings, paragraphs, code blocks, lists,
// wikilinks/links) — the render tree the PDF layout is driven from.

package app.skein.core.export.pdf.layout

import app.skein.core.markdown.ast.BulletList
import app.skein.core.markdown.ast.CodeBlock
import app.skein.core.markdown.ast.Heading
import app.skein.core.markdown.ast.Link
import app.skein.core.markdown.ast.ListItem
import app.skein.core.markdown.ast.OrderedList
import app.skein.core.markdown.ast.Paragraph
import app.skein.core.markdown.ast.SkeinDocument
import app.skein.core.markdown.ast.Strong
import app.skein.core.markdown.ast.Text
import app.skein.core.markdown.ast.WikiLink
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MarkdownFlattenerTest {
    @Test
    fun `flattens a heading preserving its level and text`() {
        val doc = SkeinDocument(listOf(Heading(level = 2, inlines = listOf(Text("Section")))))

        val blocks = MarkdownFlattener.flatten(doc)

        assertThat(blocks).containsExactly(PrintBlock.Heading(2, StyledText.of("Section")))
    }

    @Test
    fun `flattens a paragraph with bold text into a bold span`() {
        val doc = SkeinDocument(listOf(Paragraph(listOf(Strong(listOf(Text("bold")))))))

        val blocks = MarkdownFlattener.flatten(doc)

        val paragraph = blocks.single() as PrintBlock.Paragraph
        assertThat(paragraph.text.spans).containsExactly(TextSpan("bold", bold = true))
    }

    @Test
    fun `flattens a code block preserving every source line`() {
        val doc = SkeinDocument(listOf(CodeBlock(language = "kotlin", text = "val a = 1\nval b = 2")))

        val blocks = MarkdownFlattener.flatten(doc)

        assertThat(blocks).containsExactly(PrintBlock.Code("kotlin", listOf("val a = 1", "val b = 2")))
    }

    @Test
    fun `flattens a bullet list into one ListEntry per item with a non-blank marker`() {
        val doc =
            SkeinDocument(
                listOf(
                    BulletList(
                        items =
                            listOf(
                                ListItem(blocks = listOf(Paragraph(listOf(Text("first"))))),
                                ListItem(blocks = listOf(Paragraph(listOf(Text("second"))))),
                            ),
                    ),
                ),
            )

        val blocks = MarkdownFlattener.flatten(doc)

        val entries = blocks.map { it as PrintBlock.ListEntry }
        assertThat(entries.map { it.text.plain }).containsExactly("first", "second").inOrder()
        entries.forEach { assertThat(it.marker).isNotEmpty() }
    }

    @Test
    fun `flattens an ordered list with an incrementing numeric marker`() {
        val doc =
            SkeinDocument(
                listOf(
                    OrderedList(
                        items =
                            listOf(
                                ListItem(blocks = listOf(Paragraph(listOf(Text("one"))))),
                                ListItem(blocks = listOf(Paragraph(listOf(Text("two"))))),
                            ),
                        startNumber = 1,
                    ),
                ),
            )

        val entries = MarkdownFlattener.flatten(doc).map { it as PrintBlock.ListEntry }

        assertThat(entries.map { it.marker }).containsExactly("1. ", "2. ").inOrder()
    }

    @Test
    fun `renders wikilinks and links as plain underlined text`() {
        val doc =
            SkeinDocument(
                listOf(
                    Paragraph(
                        listOf(
                            WikiLink(target = "Other Note", alias = null, heading = null),
                            Text(" and "),
                            Link(inlines = listOf(Text("a link")), destination = "https://example.com"),
                        ),
                    ),
                ),
            )

        val paragraph = MarkdownFlattener.flatten(doc).single() as PrintBlock.Paragraph

        assertThat(paragraph.text.spans.first()).isEqualTo(TextSpan("Other Note", underline = true))
        assertThat(paragraph.text.spans.last()).isEqualTo(TextSpan("a link", underline = true))
    }
}
