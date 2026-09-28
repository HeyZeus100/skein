// bd `skein-a0mm`: `ImportServiceImpl.importVaultZip` beyond the shared
// contract — a real `ExportServiceImpl` round trip (ids, kinds, bodies, a
// PDF note re-pointed at its re-imported attachment) and the untrusted-input
// caps, exercised with shrunken `VaultZipLimits` instead of gigabyte
// archives.

package app.skein.core.vault.transfer

import app.skein.core.model.DocumentKind
import app.skein.core.model.FrontmatterKeys
import app.skein.core.model.NewDocument
import app.skein.core.model.NewMessage
import app.skein.core.model.Role
import app.skein.core.model.TimelineFilter
import app.skein.core.model.VaultZipImportResult
import app.skein.core.vault.export.ExportServiceImpl
import app.skein.testing.InMemoryVaultRepository
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

public class VaultZipImportTest {
    private val pdfBytes = "%PDF-1.4 not really a pdf".toByteArray()

    private suspend fun export(repository: InMemoryVaultRepository): ByteArray =
        ByteArrayOutputStream().also { ExportServiceImpl(repository).exportVaultZip(it) }.toByteArray()

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private suspend fun InMemoryVaultRepository.all(kind: DocumentKind) =
        observeTimeline(TimelineFilter(kinds = setOf(kind)), limit = Int.MAX_VALUE).first()

    @Test
    public fun `export then import restores notes and AI outputs, re-points a PDF note, and skips chats`() =
        runTest {
            // In-app documents: no `kind` or `title` in their frontmatter, so both come from the manifest.
            val source = InMemoryVaultRepository()
            val alpha = source.createDocument(NewDocument(DocumentKind.NOTE, "Café notes", "Links [[Beta]]."))
            source.createDocument(NewDocument(DocumentKind.NOTE, "Beta", "Back to [[Café notes]]."))
            val summary = source.createDocument(NewDocument(DocumentKind.AIOUT, "Summary", "An answer."))
            val pdf = ImportServiceImpl(source).importPdf("paper.pdf", ByteArrayInputStream(pdfBytes))
            val chat = source.createDocument(NewDocument(DocumentKind.CHAT, "Chat", null))
            source.appendMessage(chat.id, NewMessage(role = Role.USER, contentMd = "hi"))
            val archive = export(source)

            val target = InMemoryVaultRepository()
            val result = ImportServiceImpl(target).importVaultZip(ByteArrayInputStream(archive), personaId = "space-1")

            assertThat(result).isEqualTo(VaultZipImportResult(imported = 5, skipped = 1, truncated = false))
            val restored = requireNotNull(target.getDocument(alpha.id))
            assertThat(restored.title).isEqualTo("Café notes")
            assertThat(restored.bodyMd).isEqualTo("Links [[Beta]].")
            assertThat(restored.personaId).isEqualTo("space-1")
            assertThat(target.getDocument(summary.id)?.kind).isEqualTo(DocumentKind.AIOUT)
            assertThat(target.getDocument(chat.id)).isNull()

            val note = requireNotNull(target.getDocument(pdf.documentId))
            val newAttachmentId = (note.frontmatter[FrontmatterKeys.SOURCE] as JsonPrimitive).content
            assertThat(newAttachmentId).isNotEqualTo(pdf.attachmentId)
            assertThat(target.openAttachment(newAttachmentId).use { it.readBytes() }).isEqualTo(pdfBytes)
            assertThat(target.attachmentMimeType(newAttachmentId)).isEqualTo("application/pdf")
            assertThat(target.getDocument(newAttachmentId)?.title).isEqualTo("paper.pdf")

            val again = ImportServiceImpl(target).importVaultZip(ByteArrayInputStream(archive), personaId = "space-1")
            assertThat(again).isEqualTo(VaultZipImportResult(imported = 0, skipped = 6, truncated = false))
            assertThat(target.all(DocumentKind.ATTACHMENT)).hasSize(1)
        }

    @Test
    public fun `ordinary nested Markdown archive resolves path links while keeping heading and frontmatter titles`() =
        runTest {
            val archive =
                zip(
                    "Source.md" to "[[sub/Filename]] [[Nickname]] [[sub\\Filename.md]]".toByteArray(),
                    "sub/Filename.md" to
                        "---\ntitle: Display title\naliases: [Nickname]\n---\n# Other heading".toByteArray(),
                )
            val target = InMemoryVaultRepository()
            val result = ImportServiceImpl(target).importVaultZip(archive.inputStream())
            val imported = requireNotNull(target.findByTitle("Display title"))
            val source = requireNotNull(target.findByTitle("Source"))

            assertThat(result).isEqualTo(VaultZipImportResult(2, 0, false))
            assertThat(imported.bodyMd).isEqualTo("# Other heading")
            assertThat(source.bodyMd).isEqualTo(
                "[[${imported.id}|sub/Filename]] [[${imported.id}|Nickname]] [[${imported.id}|sub\\Filename.md]]",
            )
        }

    @Test
    public fun `late and malformed manifests preserve restored wikilinks and frontmatter`() =
        runTest {
            for (leading in listOf(true, false)) {
                val metadata = ".skein/manifest.json" to "{malformed".toByteArray()
                val note = "Source.md" to "---\ncustom: keep\n---\n[[Target]]".toByteArray()
                val target = "Target.md" to "body".toByteArray()
                val archive = if (leading) zip(metadata, note, target) else zip(note, target, metadata)
                val repository = InMemoryVaultRepository()
                ImportServiceImpl(repository).importVaultZip(archive.inputStream())
                val source = requireNotNull(repository.findByTitle("Source"))

                assertThat(source.bodyMd).isEqualTo("[[Target]]")
                assertThat(source.frontmatter.keys).containsExactly("custom", "id")
            }
        }

    @Test
    public fun `truncated ordinary archive never rewrites a link to a file not imported`() =
        runTest {
            val archive = zip("Source.md" to "[[Target]]".toByteArray(), "Target.md" to "body".toByteArray())
            val repository = InMemoryVaultRepository()
            val result =
                ImportServiceImpl(
                    repository,
                ).importVaultZip(archive.inputStream(), null, VaultZipLimits(maxEntries = 1))
            val source = requireNotNull(repository.findByTitle("Source"))

            assertThat(result).isEqualTo(VaultZipImportResult(1, 0, true))
            assertThat(source.bodyMd).isEqualTo("[[Target]]")
            assertThat(ImportedLinkTargets.isUnresolved(source.frontmatter, "Target")).isTrue()
        }

    @Test
    public fun `the entry count cap stops the import`() =
        runTest {
            val archive = zip(*Array(5) { "Note $it.md" to "body $it".toByteArray() })
            val target = InMemoryVaultRepository()

            val result =
                ImportServiceImpl(
                    target,
                ).importVaultZip(ByteArrayInputStream(archive), null, VaultZipLimits(maxEntries = 3))

            assertThat(result).isEqualTo(VaultZipImportResult(imported = 3, skipped = 0, truncated = true))
        }

    @Test
    public fun `an entry over the size cap stops the import and leaves no partial attachment`() =
        runTest {
            val archive = zip("attachments/big.pdf" to ByteArray(4_096), "After.md" to "x".toByteArray())
            val target = InMemoryVaultRepository()

            val result =
                ImportServiceImpl(
                    target,
                ).importVaultZip(ByteArrayInputStream(archive), null, VaultZipLimits(maxEntryBytes = 1_024))

            assertThat(result.truncated).isTrue()
            assertThat(result.imported).isEqualTo(0)
            assertThat(target.all(DocumentKind.ATTACHMENT)).isEmpty()
        }

    @Test
    public fun `skipped entries count toward the total cap`() =
        runTest {
            val archive =
                zip(
                    "junk/a.bin" to ByteArray(600),
                    "junk/b.bin" to ByteArray(600),
                    "Late.md" to "x".toByteArray(),
                )

            val result =
                ImportServiceImpl(InMemoryVaultRepository())
                    .importVaultZip(ByteArrayInputStream(archive), null, VaultZipLimits(maxTotalBytes = 1_000))

            assertThat(result).isEqualTo(VaultZipImportResult(imported = 0, skipped = 2, truncated = true))
        }

    @Test
    public fun `a document over its cap is skipped and the import goes on`() =
        runTest {
            val archive = zip("Big.md" to ByteArray(2_048) { 'a'.code.toByte() }, "Small.md" to "ok".toByteArray())
            val target = InMemoryVaultRepository()

            val result =
                ImportServiceImpl(target)
                    .importVaultZip(ByteArrayInputStream(archive), null, VaultZipLimits(maxDocumentBytes = 1_024))

            assertThat(result).isEqualTo(VaultZipImportResult(imported = 1, skipped = 1, truncated = false))
            assertThat(target.findByTitle("Small")).isNotNull()
        }

    @Test
    public fun `invalid UTF-8 imports with replacement characters`() =
        runTest {
            val garbled = byteArrayOf(0xC3.toByte(), 0x28, 0xFF.toByte()) + "ok".toByteArray()
            val target = InMemoryVaultRepository()

            val result = ImportServiceImpl(target).importVaultZip(ByteArrayInputStream(zip("Garbled.md" to garbled)))

            assertThat(result.imported).isEqualTo(1)
            assertThat(target.findByTitle("Garbled")?.bodyMd).isEqualTo("\uFFFD(\uFFFDok")
        }

    @Test
    public fun `a malformed manifest is ignored, not fatal`() =
        runTest {
            val archive =
                zip(
                    ".skein/manifest.json" to "{\"documents\": [".toByteArray(),
                    "Note.md" to "fine".toByteArray(),
                )

            val result = ImportServiceImpl(InMemoryVaultRepository()).importVaultZip(ByteArrayInputStream(archive))

            assertThat(result).isEqualTo(VaultZipImportResult(imported = 1, skipped = 0, truncated = false))
        }

    @Test
    public fun `a truncated archive stops cleanly and the caller's stream stays open`() =
        runTest {
            val archive = zip("First.md" to "ok".toByteArray(), "Second.md" to Random(0).nextBytes(10_000))
            var closed = false
            val input =
                object : ByteArrayInputStream(archive.copyOf(archive.size / 2)) {
                    override fun close() {
                        closed = true
                    }
                }

            val result = ImportServiceImpl(InMemoryVaultRepository()).importVaultZip(input)

            assertThat(result).isEqualTo(VaultZipImportResult(imported = 1, skipped = 0, truncated = true))
            assertThat(closed).isFalse()
        }

    @Test
    public fun `entry names that would escape a directory are unsafe`() {
        for (name in listOf(
            "../x.md",
            "a/../../x.md",
            "/etc/x.md",
            "\\x.md",
            "C:x.md",
            "c:\\x.md",
            "a\\..\\x.md",
            "",
            "a\u0000.md",
        )) {
            assertWithMessage(name).that(VaultZipImporter.isSafeEntryName(name)).isFalse()
        }
        for (name in listOf("Note.md", "...md", "attachments/abc.pdf", "a..b.md", ".skein/manifest.json")) {
            assertWithMessage(name).that(VaultZipImporter.isSafeEntryName(name)).isTrue()
        }
    }
}
