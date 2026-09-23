// `E2.I8` (bd `skein-qdo`): `ImportServiceImpl.importPdf` tests. Robolectric
// (not plain JVM) because real text extraction needs `PDFBoxResourceLoader`
// backed by a real `AssetManager` — see `PdfImporter.kt`'s header and this
// module's `build.gradle.kts` (`isIncludeAndroidResources`).
//
// Acceptance criteria exercised here (bd `skein-qdo`):
//   - "A 3-page fixture PDF yields a note with three sections separated by
//     `---` and the expected strings"
//   - "A scanned-image fixture yields the notice body `_No text layer found
//     in <name>._`"
// Plus the plan's stated mechanism details (§4.5 E2.I8): FlateDecode is
// pdfbox-android's default compressed content-stream filter, so the
// single/multi-page tests already exercise it; the uncompressed and `TJ`
// kerning tests below exercise the remaining content-stream shapes.
// "Encrypted" and "malformed" both fall back to the same notice path (the
// bd description: "produce an attachment plus a NOTE containing a one-line
// notice, not an error") — the malformed case is also covered by
// `ImportServiceImplContractTest`'s now-unskipped
// `importPdf_creates_both_an_attachment_and_a_note`.

package app.skein.core.vault.transfer

import app.skein.core.model.DocumentKind
import app.skein.core.model.FrontmatterKeys
import app.skein.testing.InMemoryVaultRepository
import com.google.common.truth.Truth.assertThat
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
public class ImportPdfTest {
    @Before
    public fun initPdfBox() {
        // Fixture generation below (`showText`/`setFont`) needs real font
        // resources exactly as extraction does; initialized directly
        // (bypassing `PdfResourceLoader`) so this setup is independent of
        // the idempotence guard under test in `PdfResourceLoaderTest`.
        PDFBoxResourceLoader.init(RuntimeEnvironment.getApplication())
    }

    private fun service(repository: InMemoryVaultRepository = InMemoryVaultRepository()) =
        ImportServiceImpl(repository, context = RuntimeEnvironment.getApplication())

    private fun stream(bytes: ByteArray): ByteArrayInputStream = ByteArrayInputStream(bytes)

    // ------------------------------------------------------------------
    // Single page, title from PDF metadata
    // ------------------------------------------------------------------

    @Test
    public fun `single-page pdf with a Title extracts text and titles the note from metadata`(): Unit =
        runTest {
            val repo = InMemoryVaultRepository()
            val bytes = buildPdf(title = "Quarterly Report", pages = listOf(textPage("Hello, Skein.")))

            val result = service(repo).importPdf("report.pdf", stream(bytes), personaId = null)

            val note = repo.getDocument(result.documentId)!!
            assertThat(note.kind).isEqualTo(DocumentKind.NOTE)
            assertThat(note.title).isEqualTo("Quarterly Report")
            assertThat(note.bodyMd).isEqualTo("Hello, Skein.")
            assertThat((note.frontmatter[FrontmatterKeys.SOURCE] as JsonPrimitive).content)
                .isEqualTo(result.attachmentId)

            val attachment = repo.getDocument(result.attachmentId!!)!!
            assertThat(attachment.kind).isEqualTo(DocumentKind.ATTACHMENT)
            assertThat(repo.attachmentMimeType(attachment.id)).isEqualTo("application/pdf")
        }

    @Test
    public fun `a pdf with no Title metadata falls back to the file name`(): Unit =
        runTest {
            val repo = InMemoryVaultRepository()
            val bytes = buildPdf(pages = listOf(textPage("body text")))

            val result = service(repo).importPdf("no-metadata-title.pdf", stream(bytes), personaId = null)

            assertThat(repo.getDocument(result.documentId)!!.title).isEqualTo("no-metadata-title")
        }

    // ------------------------------------------------------------------
    // Multi-page order
    // ------------------------------------------------------------------

    @Test
    public fun `a 3-page pdf yields a note with three sections separated by the page break`(): Unit =
        runTest {
            val repo = InMemoryVaultRepository()
            val bytes =
                buildPdf(
                    pages =
                        listOf(
                            textPage("Page One"),
                            textPage("Page Two"),
                            textPage("Page Three"),
                        ),
                )

            val result = service(repo).importPdf("three-pages.pdf", stream(bytes), personaId = null)

            val sections = repo.getDocument(result.documentId)!!.bodyMd!!.split("\n\n---\n\n")
            assertThat(sections).hasSize(3)
            assertThat(sections[0]).isEqualTo("Page One")
            assertThat(sections[1]).isEqualTo("Page Two")
            assertThat(sections[2]).isEqualTo("Page Three")
        }

    // ------------------------------------------------------------------
    // Uncompressed content stream (FlateDecode is exercised by every other
    // test here — pdfbox-android compresses by default).
    // ------------------------------------------------------------------

    @Test
    public fun `an uncompressed content stream still extracts text`(): Unit =
        runTest {
            val repo = InMemoryVaultRepository()
            val bytes = buildPdf(compress = false, pages = listOf(textPage("Not compressed.")))

            val result = service(repo).importPdf("uncompressed.pdf", stream(bytes), personaId = null)

            assertThat(repo.getDocument(result.documentId)!!.bodyMd).isEqualTo("Not compressed.")
        }

    // ------------------------------------------------------------------
    // `TJ` array kerning
    // ------------------------------------------------------------------

    @Test
    public fun `a TJ kerning array extracts as one continuous word`(): Unit =
        runTest {
            val repo = InMemoryVaultRepository()
            // Positive `TJ` numbers move the next glyph *left* (PDF spec §9.4.3) —
            // i.e. tighter kerning, not a word gap — so these do not read as a space.
            val bytes = buildPdf(pages = listOf(kernedTextPage("Hel", 120f, "lo, Ske", 80f, "in.")))

            val result = service(repo).importPdf("kerned.pdf", stream(bytes), personaId = null)

            assertThat(repo.getDocument(result.documentId)!!.bodyMd).isEqualTo("Hello, Skein.")
        }

    // ------------------------------------------------------------------
    // No text layer ("scanned image")
    // ------------------------------------------------------------------

    @Test
    public fun `a scanned pdf with no text layer yields the notice body`(): Unit =
        runTest {
            val repo = InMemoryVaultRepository()
            val bytes = buildPdf(pages = listOf(blankPage()))

            val result = service(repo).importPdf("scanned.pdf", stream(bytes), personaId = null)

            assertThat(repo.getDocument(result.documentId)!!.bodyMd)
                .isEqualTo("_No text layer found in scanned.pdf._")
            assertThat(result.attachmentId).isNotNull()
            assertThat(repo.getDocument(result.attachmentId!!)).isNotNull()
        }

    // ------------------------------------------------------------------
    // Encrypted
    // ------------------------------------------------------------------

    @Test
    public fun `an encrypted pdf yields the notice body, not an error`(): Unit =
        runTest {
            val repo = InMemoryVaultRepository()
            val bytes =
                buildPdf(
                    ownerPassword = "owner-secret",
                    userPassword = "user-secret",
                    pages = listOf(textPage("secret contents")),
                )

            val result = service(repo).importPdf("locked.pdf", stream(bytes), personaId = null)

            assertThat(repo.getDocument(result.documentId)!!.bodyMd)
                .isEqualTo("_No text layer found in locked.pdf._")
            assertThat(result.attachmentId).isNotNull()
        }

    // ------------------------------------------------------------------
    // Malformed (not a parseable PDF at all)
    // ------------------------------------------------------------------

    @Test
    public fun `a malformed pdf yields the notice body and still stores the attachment`(): Unit =
        runTest {
            val repo = InMemoryVaultRepository()
            val bytes = "this is not a pdf".toByteArray(Charsets.UTF_8)

            val result = service(repo).importPdf("garbage.pdf", stream(bytes), personaId = null)

            assertThat(repo.getDocument(result.documentId)!!.bodyMd)
                .isEqualTo("_No text layer found in garbage.pdf._")
            val attachment = repo.getDocument(result.attachmentId!!)!!
            assertThat(repo.openAttachment(attachment.id).readBytes()).isEqualTo(bytes)
        }
}
