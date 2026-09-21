// E2.I12 (bd skein-jq8): `DocxWriter` against hand-built `:core:markdown`
// AST fragments — mirrors `MarkdownFlattenerTest`'s style (`:core:markdown`)
// but asserts on the emitted OOXML rather than the intermediate
// `PrintBlock` model. Covers the bd acceptance criteria this bead owns:
// structure (every required part present), well-formedness (`document.xml`/
// `styles.xml`/`numbering.xml` parse), every `w:pStyle` referenced exists in
// `styles.xml`, determinism (byte-identical repeat exports), and special
// character escaping.

package app.skein.core.vault.export.docx

import app.skein.core.markdown.ast.BulletList
import app.skein.core.markdown.ast.Code
import app.skein.core.markdown.ast.CodeBlock
import app.skein.core.markdown.ast.Emph
import app.skein.core.markdown.ast.Heading
import app.skein.core.markdown.ast.Link
import app.skein.core.markdown.ast.ListItem
import app.skein.core.markdown.ast.OrderedList
import app.skein.core.markdown.ast.Paragraph
import app.skein.core.markdown.ast.Quote
import app.skein.core.markdown.ast.SkeinDocument
import app.skein.core.markdown.ast.Strong
import app.skein.core.markdown.ast.Text
import app.skein.core.markdown.ast.WikiLink
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.w3c.dom.Document
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class DocxWriterTest {
    // ------------------------------------------------------------------
    // Structure
    // ------------------------------------------------------------------

    @Test
    fun `writes every required OOXML part`() {
        val zip = writeDocx(SkeinDocument(listOf(Paragraph(listOf(Text("hello"))))))

        assertThat(zip.keys).containsAtLeast(
            DocxParts.CONTENT_TYPES_PATH,
            DocxParts.ROOT_RELS_PATH,
            DocxParts.DOCUMENT_PATH,
            DocxParts.STYLES_PATH,
            DocxParts.NUMBERING_PATH,
            DocxParts.DOCUMENT_RELS_PATH,
            DocxParts.CORE_PATH,
        )
    }

    @Test
    fun `does not emit a settings part when no template is supplied`() {
        val zip = writeDocx(SkeinDocument(emptyList()))

        assertThat(zip.keys).doesNotContain(DocxParts.SETTINGS_PATH)
    }

    // ------------------------------------------------------------------
    // Well-formedness
    // ------------------------------------------------------------------

    @Test
    fun `document xml styles xml and numbering xml are all well-formed`() {
        val zip =
            writeDocx(
                SkeinDocument(
                    listOf(
                        Heading(1, listOf(Text("Title"))),
                        Paragraph(listOf(Strong(listOf(Text("bold"))))),
                        CodeBlock("kotlin", "val a = 1"),
                        BulletList(listOf(ListItem(listOf(Paragraph(listOf(Text("item"))))))),
                        Quote(listOf(Paragraph(listOf(Text("quoted"))))),
                    ),
                ),
            )

        parseXml(zip.getValue(DocxParts.DOCUMENT_PATH))
        parseXml(zip.getValue(DocxParts.STYLES_PATH))
        parseXml(zip.getValue(DocxParts.NUMBERING_PATH))
        parseXml(zip.getValue(DocxParts.CONTENT_TYPES_PATH))
        parseXml(zip.getValue(DocxParts.ROOT_RELS_PATH))
        parseXml(zip.getValue(DocxParts.DOCUMENT_RELS_PATH))
        parseXml(zip.getValue(DocxParts.CORE_PATH))
    }

    @Test
    fun `every w-pStyle referenced in document xml exists as a styleId in styles xml`() {
        val zip =
            writeDocx(
                SkeinDocument(
                    listOf(
                        Heading(1, listOf(Text("H1"))),
                        Heading(6, listOf(Text("H6"))),
                        Paragraph(listOf(Text("body"))),
                        CodeBlock(null, "code"),
                        Quote(listOf(Paragraph(listOf(Text("q"))))),
                        BulletList(listOf(ListItem(listOf(Paragraph(listOf(Text("item"))))))),
                    ),
                ),
            )
        val documentXml = zip.getValue(DocxParts.DOCUMENT_PATH).toString(Charsets.UTF_8)
        val stylesXml = zip.getValue(DocxParts.STYLES_PATH).toString(Charsets.UTF_8)

        val referenced = Regex("""w:pStyle w:val="([^"]+)"""").findAll(documentXml).map { it.groupValues[1] }.toSet()
        val defined = Regex("""w:styleId="([^"]+)"""").findAll(stylesXml).map { it.groupValues[1] }.toSet()

        assertThat(referenced).isNotEmpty()
        assertThat(defined).containsAtLeastElementsIn(referenced)
    }

    // ------------------------------------------------------------------
    // Mapping
    // ------------------------------------------------------------------

    @Test
    fun `maps a heading to its Heading-N paragraph style`() {
        val xml =
            writeDocx(SkeinDocument(listOf(Heading(3, listOf(Text("Section"))))))
                .getValue(DocxParts.DOCUMENT_PATH)
                .toString(Charsets.UTF_8)

        assertThat(xml).contains("w:pStyle w:val=\"Heading3\"")
        assertThat(xml).contains("Section")
    }

    @Test
    fun `maps bold italic and inline code runs to their rPr toggles`() {
        val body =
            Paragraph(
                listOf(
                    Strong(listOf(Text("bold"))),
                    Emph(listOf(Text("italic"))),
                    Code("code"),
                ),
            )
        val xml = writeDocx(SkeinDocument(listOf(body))).getValue(DocxParts.DOCUMENT_PATH).toString(Charsets.UTF_8)

        assertThat(xml).containsMatch("<w:rPr><w:b/></w:rPr><w:t[^>]*>bold</w:t>")
        assertThat(xml).containsMatch("<w:rPr><w:i/></w:rPr><w:t[^>]*>italic</w:t>")
        assertThat(xml).contains("w:rFonts w:ascii=\"Consolas\"")
        assertThat(xml).contains(">code<")
    }

    @Test
    fun `maps a fenced code block to a Code-styled paragraph preserving every line`() {
        val xml =
            writeDocx(SkeinDocument(listOf(CodeBlock("kotlin", "val a = 1\nval b = 2"))))
                .getValue(DocxParts.DOCUMENT_PATH)
                .toString(Charsets.UTF_8)

        assertThat(xml).contains("w:pStyle w:val=\"Code\"")
        assertThat(xml).contains("val a = 1")
        assertThat(xml).contains("val b = 2")
        assertThat(xml).contains("<w:br/>")
    }

    @Test
    fun `maps a bullet list item using native OOXML numbering with numId 1`() {
        val doc =
            SkeinDocument(
                listOf(
                    BulletList(
                        listOf(
                            ListItem(listOf(Paragraph(listOf(Text("first"))))),
                            ListItem(listOf(Paragraph(listOf(Text("second"))))),
                        ),
                    ),
                ),
            )
        val xml = writeDocx(doc).getValue(DocxParts.DOCUMENT_PATH).toString(Charsets.UTF_8)

        assertThat(xml).contains("w:numId w:val=\"1\"")
        assertThat(xml).contains("first")
        assertThat(xml).contains("second")
        // The bullet glyph is drawn by OOXML numbering, not baked into the run text.
        assertThat(xml).doesNotContain("• first")
    }

    @Test
    fun `maps an ordered list item using native OOXML numbering with numId 2`() {
        val doc =
            SkeinDocument(
                listOf(
                    OrderedList(
                        items = listOf(ListItem(listOf(Paragraph(listOf(Text("one")))))),
                        startNumber = 1,
                    ),
                ),
            )
        val xml = writeDocx(doc).getValue(DocxParts.DOCUMENT_PATH).toString(Charsets.UTF_8)

        assertThat(xml).contains("w:numId w:val=\"2\"")
    }

    @Test
    fun `maps a block quote to a Quote-styled paragraph`() {
        val xml =
            writeDocx(SkeinDocument(listOf(Quote(listOf(Paragraph(listOf(Text("wise words"))))))))
                .getValue(
                    DocxParts.DOCUMENT_PATH,
                ).toString(Charsets.UTF_8)

        assertThat(xml).contains("w:pStyle w:val=\"Quote\"")
        assertThat(xml).contains("wise words")
    }

    @Test
    fun `maps a wikilink to plain underlined text with no hyperlink relationship`() {
        val doc = SkeinDocument(listOf(Paragraph(listOf(WikiLink(target = "Other Note")))))
        val zip = writeDocx(doc)
        val documentXml = zip.getValue(DocxParts.DOCUMENT_PATH).toString(Charsets.UTF_8)
        val relsXml = zip.getValue(DocxParts.DOCUMENT_RELS_PATH).toString(Charsets.UTF_8)

        assertThat(documentXml).contains("Other Note")
        assertThat(documentXml).contains("<w:u w:val=\"single\"/>")
        assertThat(documentXml).doesNotContain("w:hyperlink")
        assertThat(relsXml).doesNotContain("hyperlink")
    }

    @Test
    fun `maps a real link to a w-hyperlink element with an external relationship`() {
        val doc =
            SkeinDocument(
                listOf(Paragraph(listOf(Link(listOf(Text("click here")), destination = "https://example.com")))),
            )
        val zip = writeDocx(doc)
        val documentXml = zip.getValue(DocxParts.DOCUMENT_PATH).toString(Charsets.UTF_8)
        val relsXml = zip.getValue(DocxParts.DOCUMENT_RELS_PATH).toString(Charsets.UTF_8)

        val relId = Regex("""<w:hyperlink r:id="([^"]+)">""").find(documentXml)?.groupValues?.get(1)
        assertThat(relId).isNotNull()
        assertThat(documentXml).contains("click here")
        assertThat(relsXml).contains("Id=\"$relId\"")
        assertThat(relsXml).contains("Target=\"https://example.com\"")
        assertThat(relsXml).contains("TargetMode=\"External\"")
    }

    // ------------------------------------------------------------------
    // Escaping
    // ------------------------------------------------------------------

    @Test
    fun `escapes angle brackets and ampersands in run text while remaining well-formed`() {
        val doc = SkeinDocument(listOf(Paragraph(listOf(Text("a < b & c > d")))))
        val documentBytes = writeDocx(doc).getValue(DocxParts.DOCUMENT_PATH)
        val xml = documentBytes.toString(Charsets.UTF_8)

        assertThat(xml).contains("a &lt; b &amp; c &gt; d")
        val parsed = parseXml(documentBytes)
        assertThat(parsed.documentElement.textContent).contains("a < b & c > d")
    }

    @Test
    fun `escapes emoji and RTL text correctly and round-trips through XML parsing`() {
        val text = "party 🎉 مرحبا"
        val doc = SkeinDocument(listOf(Paragraph(listOf(Text(text)))))
        val documentBytes = writeDocx(doc).getValue(DocxParts.DOCUMENT_PATH)

        val parsed = parseXml(documentBytes)
        assertThat(parsed.documentElement.textContent).contains(text)
    }

    // ------------------------------------------------------------------
    // Determinism
    // ------------------------------------------------------------------

    @Test
    fun `two exports of the same document are byte-identical`() {
        val doc =
            SkeinDocument(
                listOf(
                    Heading(1, listOf(Text("Title"))),
                    Paragraph(listOf(Strong(listOf(Text("bold"))), Text(" and "), Emph(listOf(Text("italic"))))),
                    CodeBlock("kotlin", "val a = 1"),
                    BulletList(listOf(ListItem(listOf(Paragraph(listOf(Text("item"))))))),
                ),
            )

        val first = ByteArrayOutputStream().also { DocxWriter.write("Title", doc, it) }.toByteArray()
        val second = ByteArrayOutputStream().also { DocxWriter.write("Title", doc, it) }.toByteArray()

        assertThat(second).isEqualTo(first)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun writeDocx(
        document: SkeinDocument,
        title: String = "Test Document",
    ): Map<String, ByteArray> {
        val out = ByteArrayOutputStream()
        DocxWriter.write(title, document, out)
        return readZip(out.toByteArray())
    }

    private fun readZip(bytes: ByteArray): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                result[entry.name] = zip.readBytes()
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return result
    }

    private fun parseXml(bytes: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }
}
