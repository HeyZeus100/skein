// JVM unit tests for `ExportServiceImpl.exportVaultZip` (`E2.I10`, bd
// `skein-90d`). See `MarkdownExportTest`'s header for why an
// `InMemoryVaultRepository` (`:testing`) stand-in is sufficient here.
//
// Acceptance criteria exercised here (bd `skein-90d`):
//   - "Vault zip contains every document and attachment; manifest.json
//     validates the id→path map; duplicate titles disambiguated"
//   - "Two exports of the same vault produce identical bytes"

package app.skein.core.vault.export

import app.skein.core.model.DocumentKind
import app.skein.core.model.NewDocument
import app.skein.core.model.NewMessage
import app.skein.core.model.Role
import app.skein.testing.InMemoryVaultRepository
import app.skein.testing.fixtures.SyntheticVault
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

public class VaultZipTest {
    @Test
    public fun `exportVaultZip writes one md per document, attachments folder, and a manifest`() =
        runTest {
            val repo = InMemoryVaultRepository()
            repo.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "Note One", bodyMd = "body one"))
            val chat = repo.createDocument(NewDocument(kind = DocumentKind.CHAT, title = "Chat One", bodyMd = null))
            repo.appendMessage(chat.id, NewMessage(role = Role.USER, contentMd = "hi"))
            val attachment =
                repo.createAttachment(title = "img", mimeType = "image/png") {
                    it.write(byteArrayOf(1, 2, 3, 4))
                }

            val entries = readZipEntries(exportZip(repo))

            assertThat(entries.keys).containsExactly(
                "Note One.md",
                "Chat One.md",
                "attachments/${attachment.id}.png",
                ".skein/manifest.json",
            )
            assertThat(entries.getValue("Note One.md").toString(Charsets.UTF_8)).contains("body one")
            assertThat(entries.getValue("Chat One.md").toString(Charsets.UTF_8)).contains("**user:** hi")
            assertThat(entries.getValue("attachments/${attachment.id}.png")).isEqualTo(byteArrayOf(1, 2, 3, 4))
        }

    @Test
    public fun `manifest json lists every document and attachment id with its path, plus personas`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val note =
                repo.createDocument(
                    NewDocument(kind = DocumentKind.NOTE, title = "Note", bodyMd = "b", personaId = "work"),
                )
            val attachment =
                repo.createAttachment(title = "a", mimeType = "text/plain") { it.write("hi".toByteArray()) }

            val manifest = readManifest(exportZip(repo))

            val documents = manifest.getValue("documents").jsonArray
            val docIds =
                documents.map {
                    it.jsonObject
                        .getValue("id")
                        .jsonPrimitive.content
                }
            val docPaths =
                documents.map {
                    it.jsonObject
                        .getValue("path")
                        .jsonPrimitive.content
                }
            val attachments = manifest.getValue("attachments").jsonArray
            val attIds =
                attachments.map {
                    it.jsonObject
                        .getValue("id")
                        .jsonPrimitive.content
                }
            val attPaths =
                attachments.map {
                    it.jsonObject
                        .getValue("path")
                        .jsonPrimitive.content
                }
            val personas = manifest.getValue("personas").jsonArray.map { it.jsonPrimitive.content }

            assertThat(docIds).containsExactly(note.id)
            assertThat(docPaths).containsExactly("Note.md")
            assertThat(attIds).containsExactly(attachment.id)
            assertThat(attPaths).containsExactly("attachments/${attachment.id}.txt")
            assertThat(personas).containsExactly("work")
        }

    @Test
    public fun `duplicate titles are disambiguated with a numeric suffix`() =
        runTest {
            val repo = InMemoryVaultRepository()
            repo.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "Same Title", bodyMd = "one"))
            repo.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "Same Title", bodyMd = "two"))

            val entries = readZipEntries(exportZip(repo))

            assertThat(entries.keys).containsAtLeast("Same Title.md", "Same Title (2).md")
        }

    @Test
    public fun `attachment documents do not get a markdown entry of their own`() =
        runTest {
            val repo = InMemoryVaultRepository()
            repo.createAttachment(title = "blob", mimeType = "application/pdf") { it.write(byteArrayOf(9)) }

            val entries = readZipEntries(exportZip(repo))

            assertThat(entries.keys).doesNotContain("blob.md")
        }

    @Test
    public fun `two exports of the same vault produce byte identical zips`() =
        runTest {
            val repo = InMemoryVaultRepository()
            SyntheticVault.seed(repo, size = SyntheticVault.Preset.SMALL)

            val first = exportZip(repo)
            val second = exportZip(repo)

            assertThat(second).isEqualTo(first)
        }

    @Test
    public fun `onProgress is called once per exported item and reaches the total`() =
        runTest {
            val repo = InMemoryVaultRepository()
            repo.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "A", bodyMd = "a"))
            repo.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "B", bodyMd = "b"))
            repo.createAttachment(title = "c", mimeType = "text/plain") { it.write("c".toByteArray()) }

            val progress = mutableListOf<Pair<Int, Int>>()
            ExportServiceImpl(repo).exportVaultZip(ByteArrayOutputStream()) { done, total ->
                progress += done to total
            }

            assertThat(progress.last()).isEqualTo(3 to 3)
            assertThat(progress.map { it.first }).isEqualTo(listOf(1, 2, 3))
        }

    private suspend fun exportZip(repo: InMemoryVaultRepository): ByteArray {
        val out = ByteArrayOutputStream()
        ExportServiceImpl(repo).exportVaultZip(out)
        return out.toByteArray()
    }

    private fun readZipEntries(bytes: ByteArray): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                result[entry.name] = zip.readBytes()
                entry = zip.nextEntry
            }
        }
        return result
    }

    private fun readManifest(bytes: ByteArray): JsonObject {
        val manifestBytes = readZipEntries(bytes).getValue(".skein/manifest.json")
        return Json.parseToJsonElement(manifestBytes.toString(Charsets.UTF_8)).jsonObject
    }
}
