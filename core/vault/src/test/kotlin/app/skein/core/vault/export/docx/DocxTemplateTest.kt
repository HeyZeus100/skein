// E2.I12 (bd skein-jq8): template mode — a user-supplied `.docx` template's
// `styles.xml`/page size (`w:sectPr`) is reused instead of this writer's own
// defaults; an invalid template (not a zip, or a zip missing
// `word/document.xml`) is a typed [DocxTemplateException] rather than a
// generic/unchecked failure.

package app.skein.core.vault.export.docx

import app.skein.core.markdown.ast.Paragraph
import app.skein.core.markdown.ast.SkeinDocument
import app.skein.core.markdown.ast.Text
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class DocxTemplateTest {
    @Test
    fun `template mode preserves the template's styles xml byte-for-byte`() {
        val templateStyles =
            "<w:styles xmlns:w=\"$W_NS\"><w:style w:type=\"paragraph\" w:styleId=\"Custom\"/></w:styles>"
        val template = buildFakeTemplate(stylesXml = templateStyles, sectPrXml = CUSTOM_SECT_PR)

        val zip = writeDocxWithTemplate(template)

        assertThat(zip.getValue(DocxParts.STYLES_PATH).toString(Charsets.UTF_8)).isEqualTo(templateStyles)
    }

    @Test
    fun `template mode preserves the template's page size from its sectPr`() {
        val template = buildFakeTemplate(stylesXml = MINIMAL_STYLES, sectPrXml = CUSTOM_SECT_PR)

        val zip = writeDocxWithTemplate(template)
        val documentXml = zip.getValue(DocxParts.DOCUMENT_PATH).toString(Charsets.UTF_8)

        assertThat(documentXml).contains("w:w=\"11906\"")
        assertThat(documentXml).contains("w:h=\"16838\"")
    }

    @Test
    fun `template mode carries over settings xml when present and references it`() {
        val template =
            buildFakeTemplate(
                stylesXml = MINIMAL_STYLES,
                sectPrXml = CUSTOM_SECT_PR,
                settingsXml = "<w:settings xmlns:w=\"$W_NS\"/>",
            )

        val zip = writeDocxWithTemplate(template)

        assertThat(zip.keys).contains(DocxParts.SETTINGS_PATH)
        assertThat(zip.getValue(DocxParts.CONTENT_TYPES_PATH).toString(Charsets.UTF_8)).contains("settings.xml")
        assertThat(zip.getValue(DocxParts.DOCUMENT_RELS_PATH).toString(Charsets.UTF_8)).contains("settings.xml")
    }

    @Test
    fun `a non-zip template is rejected with a typed DocxTemplateException`() {
        val notAZip = "this is not a zip file at all".toByteArray()

        val error =
            assertThrows<DocxTemplateException> {
                DocxWriter.write(
                    "Title",
                    SkeinDocument(emptyList()),
                    ByteArrayOutputStream(),
                    ByteArrayInputStream(notAZip),
                )
            }
        assertThat(error).isNotNull()
    }

    @Test
    fun `a zip missing word document xml is rejected with a typed DocxTemplateException`() {
        val zipBytes =
            ByteArrayOutputStream()
                .also { out ->
                    ZipOutputStream(out).use { zip ->
                        zip.putNextEntry(ZipEntry("word/styles.xml"))
                        zip.write(MINIMAL_STYLES.toByteArray())
                        zip.closeEntry()
                    }
                }.toByteArray()

        assertThrows<DocxTemplateException> {
            DocxWriter.write(
                "Title",
                SkeinDocument(emptyList()),
                ByteArrayOutputStream(),
                ByteArrayInputStream(zipBytes),
            )
        }
    }

    @Test
    fun `a zip missing word styles xml is rejected with a typed DocxTemplateException`() {
        val zipBytes =
            ByteArrayOutputStream()
                .also { out ->
                    ZipOutputStream(out).use { zip ->
                        zip.putNextEntry(ZipEntry("word/document.xml"))
                        zip.write(minimalDocumentXml(CUSTOM_SECT_PR).toByteArray())
                        zip.closeEntry()
                    }
                }.toByteArray()

        assertThrows<DocxTemplateException> {
            DocxWriter.write(
                "Title",
                SkeinDocument(emptyList()),
                ByteArrayOutputStream(),
                ByteArrayInputStream(zipBytes),
            )
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun writeDocxWithTemplate(template: ByteArray): Map<String, ByteArray> {
        val out = ByteArrayOutputStream()
        DocxWriter.write(
            "Title",
            SkeinDocument(listOf(Paragraph(listOf(Text("body"))))),
            out,
            ByteArrayInputStream(template),
        )
        return readZip(out.toByteArray())
    }

    private fun buildFakeTemplate(
        stylesXml: String,
        sectPrXml: String,
        settingsXml: String? = null,
    ): ByteArray =
        ByteArrayOutputStream()
            .also { out ->
                ZipOutputStream(out).use { zip ->
                    zip.putNextEntry(ZipEntry("word/document.xml"))
                    zip.write(minimalDocumentXml(sectPrXml).toByteArray())
                    zip.closeEntry()

                    zip.putNextEntry(ZipEntry("word/styles.xml"))
                    zip.write(stylesXml.toByteArray())
                    zip.closeEntry()

                    if (settingsXml != null) {
                        zip.putNextEntry(ZipEntry("word/settings.xml"))
                        zip.write(settingsXml.toByteArray())
                        zip.closeEntry()
                    }
                }
            }.toByteArray()

    private fun minimalDocumentXml(sectPrXml: String): String =
        "<w:document xmlns:w=\"$W_NS\"><w:body><w:p/>$sectPrXml</w:body></w:document>"

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

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
        try {
            block()
        } catch (t: Throwable) {
            if (t is T) return t
            throw AssertionError("expected ${T::class.simpleName} but got ${t::class.simpleName}: ${t.message}", t)
        }
        throw AssertionError("expected ${T::class.simpleName} but nothing was thrown")
    }

    private companion object {
        const val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        const val MINIMAL_STYLES = "<w:styles xmlns:w=\"$W_NS\"/>"

        // A4 in twentieths-of-a-point (210mm x 297mm), deliberately different from
        // DocxParts.defaultSectPrXml's US Letter default so the assertion can't
        // pass by coincidence.
        const val CUSTOM_SECT_PR = "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/></w:sectPr>"
    }
}
