package app.skein.core.markdown

import app.skein.core.markdown.ast.BulletList
import app.skein.core.markdown.ast.Heading
import app.skein.core.markdown.ast.Image
import app.skein.core.markdown.ast.ImageTarget
import app.skein.core.markdown.ast.OrderedList
import app.skein.core.markdown.ast.Paragraph
import app.skein.core.markdown.ast.Quote
import app.skein.core.markdown.ast.Text
import app.skein.core.markdown.ast.UnsupportedBlock
import app.skein.core.markdown.ast.WikiLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused, human-readable assertions for bd skein-ujn's specific acceptance
 * criteria on top of [GoldenFixtureTest] (structural snapshot coverage) and
 * [MarkdownAstRoundTripPropertyTest] (round-trip losslessness).
 */
class MarkdownAstTest {
    @Test
    fun wikilinkSimpleForm() {
        val doc = MarkdownAst.parse("[[Project Overview]]\n")
        val link = ((doc.blocks.single() as Paragraph).inlines.single()) as WikiLink
        assertEquals("Project Overview", link.target)
        assertNull(link.alias)
        assertNull(link.heading)
    }

    @Test
    fun wikilinkWithAlias() {
        val doc = MarkdownAst.parse("[[Project Overview|the overview]]\n")
        val link = ((doc.blocks.single() as Paragraph).inlines.single()) as WikiLink
        assertEquals("Project Overview", link.target)
        assertEquals("the overview", link.alias)
        assertNull(link.heading)
    }

    @Test
    fun wikilinkWithHeading() {
        val doc = MarkdownAst.parse("[[Project Overview#Goals]]\n")
        val link = ((doc.blocks.single() as Paragraph).inlines.single()) as WikiLink
        assertEquals("Project Overview", link.target)
        assertEquals("Goals", link.heading)
        assertNull(link.alias)
    }

    @Test
    fun wikilinkWithHeadingAndAlias() {
        val doc = MarkdownAst.parse("[[Project Overview#Goals|our goals]]\n")
        val link = ((doc.blocks.single() as Paragraph).inlines.single()) as WikiLink
        assertEquals("Project Overview", link.target)
        assertEquals("Goals", link.heading)
        assertEquals("our goals", link.alias)
    }

    @Test
    fun wikilinkNotRecognizedInsideInlineCode() {
        val doc = MarkdownAst.parse("`[[not a link]]`\n")
        val paragraph = doc.blocks.single() as Paragraph
        assertTrue(paragraph.inlines.none { it is WikiLink })
    }

    @Test
    fun nestedBulletListStructure() {
        val doc = MarkdownAst.parse("- top\n  - child a\n  - child b\n")
        val list = doc.blocks.single() as BulletList
        assertEquals(1, list.items.size)
        val nested = list.items.single().blocks[1] as BulletList
        assertEquals(2, nested.items.size)
    }

    @Test
    fun orderedListCustomStartNumber() {
        val doc = MarkdownAst.parse("5. five\n6. six\n")
        val list = doc.blocks.single() as OrderedList
        assertEquals(5, list.startNumber)
        assertEquals(2, list.items.size)
    }

    @Test
    fun taskListItemsCarryCheckedState() {
        val doc = MarkdownAst.parse("- [ ] todo\n- [x] done\n")
        val list = doc.blocks.single() as BulletList
        assertEquals(false, list.items[0].checked)
        assertEquals(true, list.items[1].checked)
    }

    @Test
    fun plainListItemHasNullChecked() {
        val doc = MarkdownAst.parse("- plain\n")
        val list = doc.blocks.single() as BulletList
        assertNull(list.items.single().checked)
    }

    @Test
    fun imageWithAttachmentReference() {
        val doc = MarkdownAst.parse("![diagram](attachment:8b3f1c2a-0000-4000-8000-000000000000)\n")
        val image = ((doc.blocks.single() as Paragraph).inlines.single()) as Image
        assertEquals("diagram", image.alt)
        assertEquals(ImageTarget.AttachmentRef("8b3f1c2a-0000-4000-8000-000000000000"), image.target)
    }

    @Test
    fun imageWithExternalUrl() {
        val doc = MarkdownAst.parse("![logo](https://example.com/logo.png)\n")
        val image = ((doc.blocks.single() as Paragraph).inlines.single()) as Image
        assertEquals(ImageTarget.ExternalUrl("https://example.com/logo.png"), image.target)
    }

    @Test
    fun codeBlockCapturesLanguageHint() {
        val doc = MarkdownAst.parse("```kotlin\nval x = 1\n```\n")
        val code = doc.blocks.single() as app.skein.core.markdown.ast.CodeBlock
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1", code.text)
    }

    @Test
    fun headingLevelsOneThroughSix() {
        for (level in 1..6) {
            val doc = MarkdownAst.parse("#".repeat(level) + " Title\n")
            val heading = doc.blocks.single() as Heading
            assertEquals(level, heading.level)
        }
    }

    @Test
    fun blockquoteNestsBlocks() {
        val doc = MarkdownAst.parse("> quoted paragraph\n")
        val quote = doc.blocks.single() as Quote
        val text = ((quote.blocks.single() as Paragraph).inlines.single()) as Text
        assertEquals("quoted paragraph", text.value)
    }

    @Test
    fun tableDoesNotBecomeFirstClassNode() {
        val doc = MarkdownAst.parse("| a | b |\n|---|---|\n| 1 | 2 |\n")
        assertTrue(doc.blocks.single() is UnsupportedBlock)
    }

    @Test
    fun htmlBlockDoesNotBecomeFirstClassNode() {
        val doc = MarkdownAst.parse("<div>raw</div>\n")
        assertTrue(doc.blocks.single() is UnsupportedBlock)
    }

    @Test
    fun thematicBreakParses() {
        val doc = MarkdownAst.parse("---\n")
        assertEquals(app.skein.core.markdown.ast.ThematicBreak, doc.blocks.single())
    }
}
