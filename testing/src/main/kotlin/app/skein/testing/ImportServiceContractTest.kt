// The `E0.I14` contract suite: an abstract JUnit 4 test class any
// `ImportService` implementation — the `FakeImportService` here, and the
// eventual `VaultRepository`-backed implementation (`E2.I7`) — must
// satisfy.
//
// JUnit 4 (not 5) matches the surrounding codebase (see
// `InferenceEngineContractTest`, `VaultRepositoryContractTest`) and keeps
// this suite consumable by downstream modules via
// `testImplementation(project(":testing"))` without pulling the Vintage
// engine. Kept in `src/main` for the same reason those two are.

package app.skein.testing

import app.skein.core.model.DocId
import app.skein.core.model.ImportService
import app.skein.core.model.PersonaId
import app.skein.core.model.VaultZipImportResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Contract suite for `ImportService` (plan §4.5). Concrete subclasses
 * provide a fresh implementation; each test exercises one semantic of the
 * locked contract's KDoc.
 */
public abstract class ImportServiceContractTest {
    /** A fresh implementation with no prior import history. */
    protected abstract fun service(): ImportService

    /** The persona (Space) document [id] landed in, read back from whatever [service] wrote to. */
    protected abstract suspend fun personaOf(id: DocId): PersonaId?

    private fun stream(text: String): ByteArrayInputStream = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))

    /** A vault zip in `exportVaultZip`'s layout, entries in the given order. */
    private fun vaultZip(vararg entries: Pair<String, String>): ByteArrayInputStream {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, text) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return ByteArrayInputStream(out.toByteArray())
    }

    /** Two notes, one citing a PDF through `source:`, the PDF, and the manifest — what an export of them writes. */
    private fun sampleVaultZip(): ByteArrayInputStream =
        vaultZip(
            "Alpha.md" to "---\nid: zip-alpha\nkind: note\ntitle: Alpha\n---\nSee [[Beta]].",
            "Beta.md" to "---\nid: zip-beta\nkind: note\ntitle: Beta\nsource: zip-pdf\n---\nExtracted text.",
            "attachments/zip-pdf.pdf" to "%PDF-1.4 fake bytes",
            ".skein/manifest.json" to "{}",
        )

    // ------------------------------------------------------------------
    // `importText` of content with no frontmatter `id` always creates.
    // ------------------------------------------------------------------

    @Test
    public fun importText_without_frontmatter_id_creates_a_new_document(): Unit =
        runTest {
            val service = service()

            val result =
                service.importText(
                    displayName = "note.md",
                    mimeType = "text/markdown",
                    input = stream("# hello\n\nno frontmatter here"),
                    personaId = null,
                )

            assertTrue("expected a freshly-created document", result.created)
            assertNotNull("expected a non-null documentId", result.documentId)
            assertNull("no frontmatter id means no possible conflict", result.conflictWith)
        }

    // ------------------------------------------------------------------
    // `importText` (bd skein-ddpt): a frontmatter `id` that already names a
    // document is never an update target — it always creates a *new*
    // document and reports the collision via `ImportResult.conflictWith`.
    // ------------------------------------------------------------------

    @Test
    public fun importText_with_a_known_frontmatter_id_creates_a_new_document_and_reports_the_conflict(): Unit =
        runTest {
            val service = service()
            val content =
                """
                ---
                id: fixed-id-123
                ---
                first version
                """.trimIndent()

            val first = service.importText("note.md", "text/markdown", stream(content), personaId = null)
            assertTrue("first import of a not-yet-seen id must create", first.created)
            assertEquals("fixed-id-123", first.documentId)
            assertNull("first import of a not-yet-seen id has no conflict", first.conflictWith)

            val second = service.importText("note.md", "text/markdown", stream(content), personaId = null)
            assertTrue("re-importing a known frontmatter id must still create, never update", second.created)
            assertNotEquals(
                "a colliding import must never resolve to the existing document",
                first.documentId,
                second.documentId,
            )
            assertEquals(
                "the collision must be reported so the caller can surface it",
                first.documentId,
                second.conflictWith,
            )
        }

    // ------------------------------------------------------------------
    // `importPdf`: "Stores the PDF as an attachment and creates a NOTE
    // with the extracted text and `source:` frontmatter."
    // ------------------------------------------------------------------

    @Test
    public fun importPdf_creates_both_an_attachment_and_a_note(): Unit =
        runTest {
            val service = service()

            val result = service.importPdf("doc.pdf", stream("%PDF-1.4 fake bytes"), personaId = null)

            assertTrue("importing a not-yet-seen PDF must create", result.created)
            assertNotNull("expected a stored attachment id", result.attachmentId)
            assertNotEquals(
                "the note and its source attachment must be distinct documents",
                result.attachmentId,
                result.documentId,
            )
        }

    // ------------------------------------------------------------------
    // `importImage`: "Stores the image as an attachment; if a VISION
    // model is loaded, creates an AIOUT description linked with a CITE
    // edge." Without a vision model, no separate description exists.
    // ------------------------------------------------------------------

    @Test
    public fun importImage_without_a_vision_model_creates_only_the_attachment(): Unit =
        runTest {
            val service = service()

            val result = service.importImage("photo.png", "image/png", stream("fake png bytes"), personaId = null)

            assertTrue("importing a not-yet-seen image must create", result.created)
            assertNotNull("expected the stored attachment id as the document id", result.documentId)
            assertNull(
                "no VISION model is loaded for the default fixture, so no separate description document should exist",
                result.attachmentId,
            )
        }

    // ------------------------------------------------------------------
    // bd skein-a0mm: `personaId` is the Space the created documents land
    // in; omitted, they stay unassigned.
    // ------------------------------------------------------------------

    @Test
    public fun importText_lands_in_the_target_persona(): Unit =
        runTest {
            val service = service()

            val targeted = service.importText("a.md", "text/markdown", stream("# a"), personaId = "space-1")
            val unassigned = service.importText("b.md", "text/markdown", stream("# b"))

            assertEquals("space-1", personaOf(targeted.documentId))
            assertNull("the default keeps today's unassigned import", personaOf(unassigned.documentId))
        }

    @Test
    public fun importPdf_lands_its_note_in_the_target_persona(): Unit =
        runTest {
            val service = service()

            val result = service.importPdf("doc.pdf", stream("%PDF-1.4 fake bytes"), personaId = "space-1")

            assertEquals("space-1", personaOf(result.documentId))
        }

    // ------------------------------------------------------------------
    // `importVaultZip`: the inverse of `exportVaultZip`.
    // ------------------------------------------------------------------

    @Test
    public fun importVaultZip_imports_every_document_and_attachment_into_the_target_persona(): Unit =
        runTest {
            val service = service()

            val result = service.importVaultZip(sampleVaultZip(), personaId = "space-1")

            assertEquals(VaultZipImportResult(imported = 3, skipped = 0, truncated = false), result)
            assertEquals("space-1", personaOf("zip-alpha"))
            assertEquals("space-1", personaOf("zip-beta"))
        }

    @Test
    public fun importVaultZip_of_the_same_archive_twice_imports_nothing_the_second_time(): Unit =
        runTest {
            val service = service()
            service.importVaultZip(sampleVaultZip())

            val again = service.importVaultZip(sampleVaultZip())

            assertEquals(
                "known ids are skipped, and so is the PDF only a skipped note cites",
                VaultZipImportResult(imported = 0, skipped = 3, truncated = false),
                again,
            )
        }

    @Test
    public fun importVaultZip_never_overwrites_a_document_with_the_same_id(): Unit =
        runTest {
            val service = service()
            service.importText(
                "mine.md",
                "text/markdown",
                stream("---\nid: zip-alpha\n---\nmine"),
                personaId = "space-2",
            )

            val result = service.importVaultZip(sampleVaultZip(), personaId = "space-1")

            assertEquals(VaultZipImportResult(imported = 2, skipped = 1, truncated = false), result)
            assertEquals("the existing document keeps its Space", "space-2", personaOf("zip-alpha"))
        }

    @Test
    public fun importVaultZip_manifest_restoration_keeps_top_level_layout(): Unit =
        runTest {
            val result =
                service().importVaultZip(
                    vaultZip(
                        ".skein/manifest.json" to "{}",
                        "nested/Note.md" to "---\nid: nested-restored\n---\nbody",
                        "Note.md" to "body",
                    ),
                )
            assertEquals(VaultZipImportResult(imported = 1, skipped = 1, truncated = false), result)
        }

    @Test
    public fun importVaultZip_imports_nested_markdown_and_skips_unsafe_names_and_chats(): Unit =
        runTest {
            val service = service()

            val result =
                service.importVaultZip(
                    vaultZip(
                        "../evil.md" to "---\nid: evil-1\n---\nx",
                        "/absolute.md" to "---\nid: evil-2\n---\nx",
                        "C:\\drive.md" to "---\nid: evil-3\n---\nx",
                        "attachments/../../escape.pdf" to "x",
                        "Chat.md" to "---\nid: zip-chat\nkind: chat\n---\n**user:** hi",
                        "notes/nested.md" to "---\nid: zip-nested\n---\nx",
                        "Good.md" to "---\nid: zip-good\n---\nfine",
                    ),
                    personaId = "nested-space",
                )

            assertEquals(VaultZipImportResult(imported = 2, skipped = 5, truncated = false), result)
            assertEquals("nested-space", personaOf("zip-nested"))
            assertNull("a skipped entry creates nothing", personaOf("evil-1"))
        }
}
