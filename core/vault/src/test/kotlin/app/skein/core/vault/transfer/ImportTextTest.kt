// JVM unit tests for `ImportServiceImpl.importText` (`E2.I7`, bd
// `skein-ad5`). Seeds a JVM-only `InMemoryVaultRepository` (`:testing`,
// `E0.I11`) rather than the SQLCipher-backed `VaultRepositoryImpl` —
// `ImportServiceImpl` depends only on the `VaultRepository` interface, so
// the fake is a faithful stand-in and these tests need no native `.so`.
//
// Acceptance criteria exercised here (bd `skein-ad5`):
//   - "Importing a `.md` with a known `id` updates the existing document
//     (`created=false`) and preserves `createdAt`"
//   - "Importing `main.kt` yields body ```kotlin\n<content>\n``` and title
//     `main.kt`"
//   - "Windows line endings normalized to `\n`; UTF-8 BOM stripped"
// The 10 MB memory smoke test (the fourth criterion) lives in
// `ImportTextMemorySmokeTest` so its methodology is documented in one place.

package app.skein.core.vault.transfer

import app.skein.core.vault.codec.Frontmatter
import app.skein.core.vault.export.ExportServiceImpl
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test
import us.aherrera.skein.core.model.DocumentKind
import us.aherrera.skein.core.model.FrontmatterKeys
import us.aherrera.skein.core.model.ImportResult
import us.aherrera.skein.core.model.NewDocument
import us.aherrera.skein.testing.InMemoryVaultRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant

public class ImportTextTest {
    private val fixedNow: Instant = Instant.parse("2026-09-20T12:00:00Z")

    private fun stream(text: String): ByteArrayInputStream = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))

    private suspend fun ImportServiceImpl.importText(
        displayName: String,
        mimeType: String,
        text: String,
    ): ImportResult = importText(displayName, mimeType, stream(text), personaId = null)

    // ------------------------------------------------------------------
    // Markdown with frontmatter
    // ------------------------------------------------------------------

    @Test
    public fun `markdown exported by ExportServiceImpl round-trips through importText as an in-place update`() =
        runTest {
            var clock = 1_000L
            val repo = InMemoryVaultRepository(clock = { clock })
            val original =
                repo.createDocument(
                    NewDocument(
                        kind = DocumentKind.NOTE,
                        title = "Round Trip",
                        bodyMd = "first paragraph\n\nsecond paragraph",
                        frontmatter =
                            buildJsonObject {
                                put(FrontmatterKeys.TITLE, JsonPrimitive("Round Trip"))
                                put(FrontmatterKeys.TAGS, JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))))
                            },
                    ),
                )
            val exported = ByteArrayOutputStream().also { ExportServiceImpl(repo).exportMarkdown(original.id, it) }

            clock = 2_000L
            val result =
                ImportServiceImpl(repo, now = { fixedNow }).importText(
                    displayName = "Round Trip.md",
                    mimeType = "text/markdown",
                    input = ByteArrayInputStream(exported.toByteArray()),
                    personaId = null,
                )

            assertThat(result).isEqualTo(ImportResult(documentId = original.id, attachmentId = null, created = false))
            val updated = repo.getDocument(original.id)!!
            assertThat(updated.createdAt).isEqualTo(1_000L)
            assertThat(updated.updatedAt).isEqualTo(2_000L)
            assertThat(updated.title).isEqualTo("Round Trip")
            assertThat(updated.bodyMd).isEqualTo(original.bodyMd)
            assertThat(updated.frontmatter[FrontmatterKeys.ID]).isEqualTo(JsonPrimitive(original.id))
            assertThat(updated.frontmatter[FrontmatterKeys.TAGS]).isEqualTo(original.frontmatter[FrontmatterKeys.TAGS])
            assertThat(updated.frontmatter[FrontmatterKeys.KIND]).isEqualTo(JsonPrimitive("note"))
        }

    @Test
    public fun `markdown with an unknown frontmatter id is created under that id`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val service = ImportServiceImpl(repo, now = { fixedNow })
            val text =
                "---\nid: 018f2b6e-6c3a-7c3e-8f2a-6b1e2d3c4a5b\ntitle: Example Note\ntags: [x, y]\n---\nbody text"

            val result = service.importText("anything.md", "text/markdown", text)

            assertThat(result.created).isTrue()
            assertThat(result.documentId).isEqualTo("018f2b6e-6c3a-7c3e-8f2a-6b1e2d3c4a5b")
            val doc = repo.getDocument(result.documentId)!!
            assertThat(doc.kind).isEqualTo(DocumentKind.NOTE)
            assertThat(doc.title).isEqualTo("Example Note")
            assertThat(doc.bodyMd).isEqualTo("body text")
            assertThat(doc.frontmatter[FrontmatterKeys.TAGS])
                .isEqualTo(JsonArray(listOf(JsonPrimitive("x"), JsonPrimitive("y"))))
            assertThat(doc.frontmatter[FrontmatterKeys.CREATED]).isEqualTo(JsonPrimitive("2026-09-20T12:00:00Z"))
            assertThat(doc.frontmatter[FrontmatterKeys.UPDATED]).isEqualTo(JsonPrimitive("2026-09-20T12:00:00Z"))
        }

    @Test
    public fun `re-importing a known id twice keeps one document and preserves createdAt`() =
        runTest {
            var clock = 10L
            val repo = InMemoryVaultRepository(clock = { clock })
            val service = ImportServiceImpl(repo, now = { fixedNow })
            val text = "---\nid: fixed-id-123\n---\nversion one"

            val first = service.importText("note.md", "text/markdown", text)
            clock = 20L
            val second = service.importText("note.md", "text/markdown", text.replace("version one", "version two"))

            assertThat(first.created).isTrue()
            assertThat(second.created).isFalse()
            assertThat(second.documentId).isEqualTo(first.documentId)
            val doc = repo.getDocument(first.documentId)!!
            assertThat(doc.createdAt).isEqualTo(10L)
            assertThat(doc.updatedAt).isEqualTo(20L)
            assertThat(doc.bodyMd).isEqualTo("version two")
            assertThat(repo.searchTitles("", limit = 100)).hasSize(1)
        }

    @Test
    public fun `a frontmatter id that names an attachment is refused rather than overwritten`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val attachment = repo.createAttachment("blob", "application/octet-stream") { it.write(byteArrayOf(1)) }
            val service = ImportServiceImpl(repo, now = { fixedNow })

            try {
                service.importText("note.md", "text/markdown", "---\nid: ${attachment.id}\n---\nbody")
                throw AssertionError("expected IllegalStateException")
            } catch (expected: IllegalStateException) {
                // expected: an ATTACHMENT has no Markdown body to update in place.
            }
            assertThat(repo.getDocument(attachment.id)!!.kind).isEqualTo(DocumentKind.ATTACHMENT)
        }

    /**
     * Malformed frontmatter (an unterminated `---` header) falls back to
     * treating the whole file as the body. The locked `ImportResult`
     * (`Transfer.kt`) has no failure variant, plan E2.I7 only says "parse
     * frontmatter", and `Frontmatter.parse`'s own contract (Frontmatter.kt:
     * "`text` without a leading `---` header (or with an unterminated one)
     * yields an empty JsonObject and `text` unchanged — never throws") is
     * what `ImportServiceImpl` inherits, so no typed failure is raised.
     */
    @Test
    public fun `malformed frontmatter falls back to the whole file as the body`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val service = ImportServiceImpl(repo, now = { fixedNow })
            val text = "---\ntitle: never closed\nbody line"

            val result = service.importText("broken.md", "text/markdown", text)

            val doc = repo.getDocument(result.documentId)!!
            assertThat(doc.bodyMd).isEqualTo(text)
            assertThat(doc.title).isEqualTo("broken")
            assertThat(
                doc.frontmatter.keys,
            ).containsExactly(
                FrontmatterKeys.ID,
                FrontmatterKeys.KIND,
                FrontmatterKeys.TITLE,
                FrontmatterKeys.CREATED,
                FrontmatterKeys.UPDATED,
            )
        }

    @Test
    public fun `frontmatter splitting matches Frontmatter parse exactly`() {
        val samples =
            listOf(
                "",
                "---",
                "---\n",
                "---\nid: x\n---",
                "---\nid: x\n---\n",
                "---\nid: x\n---\nbody",
                "---\nid: x\n---\n\nbody\n---\nmore",
                "---\nid: x\nno closing",
                "--- \nid: x\n---\nbody",
                "not frontmatter\n---\nid: x\n---\n",
                "----\nid: x\n---\nbody",
            )
        for (sample in samples) {
            assertThat(ImportServiceImpl.splitFrontmatter(sample)).isEqualTo(Frontmatter.parse(sample))
        }
    }

    // ------------------------------------------------------------------
    // Plain text: derived title
    // ------------------------------------------------------------------

    @Test
    public fun `plain text with a level-one heading takes it as the title`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val service = ImportServiceImpl(repo, now = { fixedNow })

            val result = service.importText("notes.txt", "text/plain", "\n\n# Groceries #\n- eggs\n- milk\n")

            val doc = repo.getDocument(result.documentId)!!
            assertThat(doc.kind).isEqualTo(DocumentKind.NOTE)
            assertThat(doc.title).isEqualTo("Groceries")
            assertThat(doc.bodyMd).isEqualTo("\n\n# Groceries #\n- eggs\n- milk\n")
            assertThat(doc.frontmatter[FrontmatterKeys.TITLE]).isEqualTo(JsonPrimitive("Groceries"))
        }

    @Test
    public fun `plain text without a heading is titled from the file name without its extension`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val service = ImportServiceImpl(repo, now = { fixedNow })

            val result = service.importText("meeting notes.txt", "text/plain", "just some text\n## not level one")

            assertThat(repo.getDocument(result.documentId)!!.title).isEqualTo("meeting notes")
        }

    @Test
    public fun `frontmatter title wins over a heading`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val service = ImportServiceImpl(repo, now = { fixedNow })

            val result =
                service.importText(
                    "x.md",
                    "text/markdown",
                    "---\ntitle: From Frontmatter\n---\n# From Heading",
                )

            assertThat(repo.getDocument(result.documentId)!!.title).isEqualTo("From Frontmatter")
        }

    @Test
    public fun `persona id is recorded on the created document`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val service = ImportServiceImpl(repo, now = { fixedNow })

            val result = service.importText("p.txt", "text/plain", stream("hello"), personaId = "persona-1")

            assertThat(repo.getDocument(result.documentId)!!.personaId).isEqualTo("persona-1")
        }

    // ------------------------------------------------------------------
    // Source code
    // ------------------------------------------------------------------

    @Test
    public fun `a kotlin source file is wrapped in a fenced block and titled with the file name`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val service = ImportServiceImpl(repo, now = { fixedNow })
            val content = "fun main() {\n    println(\"hi\")\n}\n"

            val result = service.importText("main.kt", "application/octet-stream", content)

            val doc = repo.getDocument(result.documentId)!!
            assertThat(doc.kind).isEqualTo(DocumentKind.NOTE)
            assertThat(doc.title).isEqualTo("main.kt")
            assertThat(doc.bodyMd).isEqualTo("```kotlin\nfun main() {\n    println(\"hi\")\n}\n```")
        }

    @Test
    public fun `source without a trailing newline still closes the fence on its own line`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val service = ImportServiceImpl(repo, now = { fixedNow })

            val result = service.importText("q.py", "text/x-python", "print(1)")

            assertThat(repo.getDocument(result.documentId)!!.bodyMd).isEqualTo("```python\nprint(1)\n```")
        }

    @Test
    public fun `source containing a triple backtick uses a longer fence`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val service = ImportServiceImpl(repo, now = { fixedNow })

            val result = service.importText("doc.sh", "text/x-shellscript", "echo '```'\n")

            assertThat(repo.getDocument(result.documentId)!!.bodyMd).isEqualTo("````bash\necho '```'\n````")
        }

    @Test
    public fun `source code that happens to start with a yaml document marker is not parsed as frontmatter`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val service = ImportServiceImpl(repo, now = { fixedNow })
            val yaml = "---\nid: config-id\n---\nkey: value\n"

            val result = service.importText("config.yaml", "application/x-yaml", yaml)

            assertThat(result.created).isTrue()
            assertThat(result.documentId).isNotEqualTo("config-id")
            assertThat(repo.getDocument(result.documentId)!!.bodyMd).isEqualTo("```yaml\n$yaml```")
        }

    // ------------------------------------------------------------------
    // Decoding: BOM + line endings
    // ------------------------------------------------------------------

    @Test
    public fun `utf-8 bom is stripped and windows line endings are normalized`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val service = ImportServiceImpl(repo, now = { fixedNow })
            val bytes =
                byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
                    "---\r\nid: bom-id\r\n---\r\nline one\r\nline two\rline three".toByteArray()

            val result = service.importText("win.md", "text/markdown", ByteArrayInputStream(bytes), personaId = null)

            assertThat(result.documentId).isEqualTo("bom-id")
            assertThat(repo.getDocument("bom-id")!!.bodyMd).isEqualTo("line one\nline two\nline three")
        }

    @Test
    public fun `non-ascii text survives decoding`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val service = ImportServiceImpl(repo, now = { fixedNow })

            val result = service.importText("ü.txt", "text/plain", "# Grüße 🎉\nnaïve café")

            val doc = repo.getDocument(result.documentId)!!
            assertThat(doc.title).isEqualTo("Grüße 🎉")
            assertThat(doc.bodyMd).isEqualTo("# Grüße 🎉\nnaïve café")
        }

    // ------------------------------------------------------------------
    // Entry points owned by other beads
    // ------------------------------------------------------------------

    @Test
    public fun `importPdf is deferred to its owning bead`() =
        runTest {
            val service = ImportServiceImpl(InMemoryVaultRepository())
            try {
                service.importPdf("doc.pdf", stream("%PDF-1.4"), personaId = null)
                throw AssertionError("expected UnsupportedOperationException")
            } catch (expected: UnsupportedOperationException) {
                assertThat(expected).hasMessageThat().contains("skein-qdo")
            }
        }

    @Test
    public fun `importImage is deferred to its owning bead`() =
        runTest {
            val service = ImportServiceImpl(InMemoryVaultRepository())
            try {
                service.importImage("photo.png", "image/png", stream("png"), personaId = null)
                throw AssertionError("expected UnsupportedOperationException")
            } catch (expected: UnsupportedOperationException) {
                assertThat(expected).hasMessageThat().contains("skein-rni")
            }
        }
}
