// JVM unit tests for `ExportServiceImpl.exportMarkdown` (`E2.I10`, bd
// `skein-90d`). Seeds a JVM-only `InMemoryVaultRepository` (`:testing`,
// `E0.I11`) directly rather than the SQLCipher-backed
// `VaultRepositoryImpl` (`E2.I4`) — `ExportServiceImpl` depends only on the
// `VaultRepository` interface (`core/model/.../Vault.kt`), so the fake is a
// faithful stand-in and these tests need no native `.so` / Android runtime.
//
// Acceptance criteria exercised here (bd `skein-90d`):
//   - "exportMarkdown of a note round-trips through importText to an
//     identical document (frontmatter + body)"
//   - "Chat documents export with their materialized transcript body"

package app.skein.core.vault.export

import app.skein.core.vault.codec.Frontmatter
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test
import us.aherrera.skein.core.model.DocumentKind
import us.aherrera.skein.core.model.FrontmatterKeys
import us.aherrera.skein.core.model.NewDocument
import us.aherrera.skein.core.model.NewMessage
import us.aherrera.skein.core.model.Role
import us.aherrera.skein.testing.FakeImportService
import us.aherrera.skein.testing.InMemoryVaultRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

public class MarkdownExportTest {
    @Test
    public fun `exportMarkdown writes the canonical frontmatter block followed by the body verbatim`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val service = ExportServiceImpl(repo)
            val doc =
                repo.createDocument(
                    NewDocument(
                        kind = DocumentKind.NOTE,
                        title = "Example Note",
                        bodyMd = "This is the body of the note.",
                        frontmatter =
                            buildJsonObject {
                                put(FrontmatterKeys.TITLE, JsonPrimitive("Example Note"))
                                put(FrontmatterKeys.TAGS, JsonArray(listOf(JsonPrimitive("example"))))
                            },
                    ),
                )

            val out = ByteArrayOutputStream()
            service.exportMarkdown(doc.id, out)
            val text = out.toByteArray().toString(Charsets.UTF_8)

            val (frontmatter, body) = Frontmatter.parse(text)
            assertThat(frontmatter[FrontmatterKeys.ID]).isEqualTo(JsonPrimitive(doc.id))
            assertThat(frontmatter[FrontmatterKeys.TITLE]).isEqualTo(JsonPrimitive("Example Note"))
            assertThat(body).isEqualTo("This is the body of the note.")
            // Exactly what Frontmatter.render(frontmatter, body) would produce —
            // no extra framing, no lost bytes.
            assertThat(text).isEqualTo(Frontmatter.render(doc.frontmatter, doc.bodyMd!!))
        }

    @Test
    public fun `exportMarkdown of a chat exports its materialized transcript as the body`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val service = ExportServiceImpl(repo)
            val chat = repo.createDocument(NewDocument(kind = DocumentKind.CHAT, title = "Chat", bodyMd = null))
            repo.appendMessage(chat.id, NewMessage(role = Role.USER, contentMd = "hello"))
            repo.appendMessage(chat.id, NewMessage(role = Role.ASSISTANT, contentMd = "hi there"))

            val out = ByteArrayOutputStream()
            service.exportMarkdown(chat.id, out)
            val text = out.toByteArray().toString(Charsets.UTF_8)

            assertThat(text).contains("**user:** hello")
            assertThat(text).contains("**assistant:** hi there")
        }

    @Test
    public fun `exportMarkdown throws for an unknown document id`() =
        runTest {
            val service = ExportServiceImpl(InMemoryVaultRepository())

            try {
                service.exportMarkdown("does-not-exist", ByteArrayOutputStream())
                throw AssertionError("expected NoSuchElementException")
            } catch (expected: NoSuchElementException) {
                // expected: nothing to export for an id the vault doesn't have.
            }
        }

    @Test
    public fun `exportMarkdown refuses to export an attachment document`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val service = ExportServiceImpl(repo)
            val attachment =
                repo.createAttachment(title = "blob", mimeType = "application/octet-stream") {
                    it.write(byteArrayOf(1, 2, 3))
                }

            try {
                service.exportMarkdown(attachment.id, ByteArrayOutputStream())
                throw AssertionError("expected IllegalStateException")
            } catch (expected: IllegalStateException) {
                // expected: attachments have no bodyMd/Markdown representation
                // (they land in the zip's attachments/ folder instead).
            }
        }

    @Test
    public fun `exportMarkdown round-trips through importText to the same document id and identical content`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val service = ExportServiceImpl(repo)
            val doc =
                repo.createDocument(
                    NewDocument(kind = DocumentKind.NOTE, title = "Round Trip", bodyMd = "round trip body"),
                )

            val out = ByteArrayOutputStream()
            service.exportMarkdown(doc.id, out)
            val exportedBytes = out.toByteArray()

            // Re-parsing the exported bytes with the same codec any real
            // ImportService (E2.I7) will use recovers identical frontmatter + body.
            val (parsedFrontmatter, parsedBody) =
                Frontmatter.parse(exportedBytes.toString(Charsets.UTF_8))
            assertThat(parsedFrontmatter).isEqualTo(doc.frontmatter)
            assertThat(parsedBody).isEqualTo(doc.bodyMd)

            // And the locked `ImportService.importText` contract (Transfer.kt)
            // recognizes the frontmatter id embedded by exportMarkdown.
            val importService = FakeImportService()
            val result =
                importService.importText(
                    displayName = "Round Trip.md",
                    mimeType = "text/markdown",
                    input = ByteArrayInputStream(exportedBytes),
                    personaId = null,
                )
            assertThat(result.documentId).isEqualTo(doc.id)
        }
}
