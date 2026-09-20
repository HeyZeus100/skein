package app.skein.core.markdown.render

import app.skein.core.markdown.MarkdownAst
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRendererTest {
    @Test
    fun plainParagraphRendersAsPlainText() {
        val doc = MarkdownAst.parse("Hello world.\n")
        val result = MarkdownRenderer.toAnnotatedString(doc)
        assertEquals("Hello world.", result.text)
    }

    @Test
    fun wikilinkGetsWikilinkAnnotationWithTarget() {
        val doc = MarkdownAst.parse("See [[Project Overview]] now.\n")
        val result = MarkdownRenderer.toAnnotatedString(doc)
        val annotations = result.getStringAnnotations(MarkdownRenderer.TAG_WIKILINK, 0, result.length)
        assertEquals(1, annotations.size)
        assertEquals("Project Overview", annotations[0].item)
        assertEquals("Project Overview", result.text.substring(annotations[0].start, annotations[0].end))
    }

    @Test
    fun wikilinkWithAliasDisplaysAliasButAnnotatesTarget() {
        val doc = MarkdownAst.parse("[[Project Overview|the overview]]\n")
        val result = MarkdownRenderer.toAnnotatedString(doc)
        assertEquals("the overview", result.text)
        val annotations = result.getStringAnnotations(MarkdownRenderer.TAG_WIKILINK, 0, result.length)
        assertEquals("Project Overview", annotations.single().item)
    }

    @Test
    fun wikilinkWithHeadingAnnotatesTargetHashHeading() {
        val doc = MarkdownAst.parse("[[Project Overview#Goals]]\n")
        val result = MarkdownRenderer.toAnnotatedString(doc)
        val annotations = result.getStringAnnotations(MarkdownRenderer.TAG_WIKILINK, 0, result.length)
        assertEquals("Project Overview#Goals", annotations.single().item)
    }

    @Test
    fun linkGetsLinkAnnotationWithDestination() {
        val doc = MarkdownAst.parse("[Skein](https://example.com)\n")
        val result = MarkdownRenderer.toAnnotatedString(doc)
        val annotations = result.getStringAnnotations(MarkdownRenderer.TAG_LINK, 0, result.length)
        assertEquals("https://example.com", annotations.single().item)
        assertEquals("Skein", result.text)
    }

    @Test
    fun imageRendersPlaceholderWithAltAndImageAnnotation() {
        val doc = MarkdownAst.parse("![a diagram](attachment:1234)\n")
        val result = MarkdownRenderer.toAnnotatedString(doc)
        assertTrue(result.text.contains("a diagram"))
        val annotations = result.getStringAnnotations(MarkdownRenderer.TAG_IMAGE, 0, result.length)
        assertEquals("attachment:1234", annotations.single().item)
    }

    @Test
    fun citationMarkerAnnotatedOnlyWhenCitationsProvided() {
        val doc = MarkdownAst.parse("The answer is 42 [1].\n")

        val withoutCitations = MarkdownRenderer.toAnnotatedString(doc)
        assertTrue(
            withoutCitations.getStringAnnotations(MarkdownRenderer.TAG_CITATION, 0, withoutCitations.length).isEmpty(),
        )

        val withCitations = MarkdownRenderer.toAnnotatedString(doc, citations = listOf("source-a"))
        val annotations = withCitations.getStringAnnotations(MarkdownRenderer.TAG_CITATION, 0, withCitations.length)
        assertEquals("1", annotations.single().item)
    }

    @Test
    fun citationMarkerOutOfRangeIsNotAnnotated() {
        val doc = MarkdownAst.parse("See [9] for more.\n")
        val result = MarkdownRenderer.toAnnotatedString(doc, citations = listOf("only-one"))
        assertTrue(result.getStringAnnotations(MarkdownRenderer.TAG_CITATION, 0, result.length).isEmpty())
        assertTrue(result.text.contains("[9]"))
    }

    @Test
    fun boldAndItalicProduceDistinctSpanStyles() {
        val doc = MarkdownAst.parse("*italic* and **bold**\n")
        val result = MarkdownRenderer.toAnnotatedString(doc)
        assertEquals("italic and bold", result.text)
        assertTrue(result.spanStyles.any { it.item.fontStyle != null })
        assertTrue(result.spanStyles.any { it.item.fontWeight != null })
    }

    @Test
    fun codeSpanGetsCodeSpanStyle() {
        val doc = MarkdownAst.parse("Use `val x = 1` here.\n")
        val result = MarkdownRenderer.toAnnotatedString(doc)
        val codeRange = result.text.indexOf("val x = 1")
        assertTrue(result.spanStyles.any { it.start <= codeRange && it.end >= codeRange + "val x = 1".length })
    }

    @Test
    fun bulletListRendersMarkerPerItem() {
        val doc = MarkdownAst.parse("- one\n- two\n")
        val result = MarkdownRenderer.toAnnotatedString(doc)
        assertEquals(2, Regex(MarkdownStyle.Default.bulletMarker).findAll(result.text).count())
    }

    @Test
    fun taskListRendersCheckedAndUncheckedGlyphs() {
        val doc = MarkdownAst.parse("- [ ] todo\n- [x] done\n")
        val result = MarkdownRenderer.toAnnotatedString(doc)
        assertTrue(result.text.contains(MarkdownStyle.Default.uncheckedMarker))
        assertTrue(result.text.contains(MarkdownStyle.Default.checkedMarker))
    }

    @Test
    fun tableDegradesToRawTextInAnnotatedString() {
        val doc = MarkdownAst.parse("| a | b |\n|---|---|\n| 1 | 2 |\n")
        val result = MarkdownRenderer.toAnnotatedString(doc)
        assertTrue(result.text.contains("a") && result.text.contains("b"))
    }

    @Test
    fun plainTextStripsAllSyntax() {
        val doc = MarkdownAst.parse("# Title\n\nSome **bold** and [[Wiki|alias]] text.\n")
        val plain = MarkdownRenderer.plainText(doc)
        assertEquals("Title\n\nSome bold and alias text.", plain)
    }

    @Test
    fun plainTextForImageIsAltText() {
        val doc = MarkdownAst.parse("![a photo](attachment:1)\n")
        assertEquals("a photo", MarkdownRenderer.plainText(doc))
    }
}
