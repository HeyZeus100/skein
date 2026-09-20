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

package us.aherrera.skein.testing

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import us.aherrera.skein.core.model.ImportService
import java.io.ByteArrayInputStream

/**
 * Contract suite for `ImportService` (plan §4.5). Concrete subclasses
 * provide a fresh implementation; each test exercises one semantic of the
 * locked contract's KDoc.
 */
public abstract class ImportServiceContractTest {
    /** A fresh implementation with no prior import history. */
    protected abstract fun service(): ImportService

    private fun stream(text: String): ByteArrayInputStream = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))

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
        }

    // ------------------------------------------------------------------
    // `importText`: "If the content has frontmatter with an `id` that
    // exists, updates it (created=false)."
    // ------------------------------------------------------------------

    @Test
    public fun importText_with_a_known_frontmatter_id_updates_instead_of_creating(): Unit =
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

            val second = service.importText("note.md", "text/markdown", stream(content), personaId = null)
            assertTrue("re-importing a known frontmatter id must update, not create", !second.created)
            assertEquals("update must resolve to the same document", first.documentId, second.documentId)
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
}
