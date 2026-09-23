// E2.I12 (bd skein-jq8): `ExportServiceImpl.exportDocx` end-to-end — wiring
// (parses the document's Markdown body, delegates to `DocxWriter`) plus a
// round-trip sanity check over a `SyntheticVault` note, mirroring
// `MarkdownExportTest`'s style for `exportMarkdown` (`skein-90d`).

package app.skein.core.vault.export

import app.skein.core.model.DocumentKind
import app.skein.core.model.NewDocument
import app.skein.core.model.TimelineFilter
import app.skein.testing.InMemoryVaultRepository
import app.skein.testing.fixtures.SyntheticVault
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class ExportServiceDocxTest {
    @Test
    fun `exportDocx of a note produces a well-formed docx package containing its title and body text`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val service = ExportServiceImpl(repo)
            val doc =
                repo.createDocument(
                    NewDocument(
                        kind = DocumentKind.NOTE,
                        title = "Meeting Notes",
                        bodyMd =
                            "# Meeting Notes\n\nWe discussed **the roadmap** and [[Other Note]]." +
                                "\n\n- item one\n- item two",
                    ),
                )

            val out = ByteArrayOutputStream()
            service.exportDocx(doc.id, out)
            val parts = readZip(out.toByteArray())

            assertThat(parts.keys).containsAtLeast("word/document.xml", "word/styles.xml", "[Content_Types].xml")
            val documentXml = parts.getValue("word/document.xml").toString(Charsets.UTF_8)
            parseXml(parts.getValue("word/document.xml")) // well-formed
            assertThat(documentXml).contains("Meeting Notes")
            assertThat(documentXml).contains("the roadmap")
            assertThat(documentXml).contains("Other Note")
            assertThat(documentXml).contains("item one")
        }

    @Test
    fun `exportDocx throws for an unknown document id`() =
        runTest {
            val service = ExportServiceImpl(InMemoryVaultRepository())

            try {
                service.exportDocx("does-not-exist", ByteArrayOutputStream())
                throw AssertionError("expected NoSuchElementException")
            } catch (expected: NoSuchElementException) {
                // expected
            }
        }

    @Test
    fun `exportDocx refuses to export an attachment document`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val service = ExportServiceImpl(repo)
            val attachment =
                repo.createAttachment(title = "blob", mimeType = "application/octet-stream") {
                    it.write(byteArrayOf(1, 2, 3))
                }

            try {
                service.exportDocx(attachment.id, ByteArrayOutputStream())
                throw AssertionError("expected IllegalStateException")
            } catch (expected: IllegalStateException) {
                // expected
            }
        }

    @Test
    fun `exportDocx round-trips a SyntheticVault note into a well-formed docx`() =
        runTest {
            val repo = InMemoryVaultRepository()
            SyntheticVault.seed(repo, size = SyntheticVault.Preset.SMALL)
            val service = ExportServiceImpl(repo)
            val note =
                repo
                    .observeTimeline(filter = TimelineFilter(kinds = setOf(DocumentKind.NOTE)), limit = 1)
                    .first()
                    .first()

            val out = ByteArrayOutputStream()
            service.exportDocx(note.id, out)
            val parts = readZip(out.toByteArray())

            assertThat(parts.keys).contains("word/document.xml")
            val documentXml = parseXml(parts.getValue("word/document.xml"))
            assertThat(documentXml.documentElement.localName).isEqualTo("document")
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

    private fun parseXml(bytes: ByteArray) =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(
            ByteArrayInputStream(bytes),
        )
}
