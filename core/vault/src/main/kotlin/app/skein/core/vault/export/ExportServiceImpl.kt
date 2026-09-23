// `E2.I10` (bd `skein-90d`): Markdown export — single document and whole
// vault zip — implementing the `ExportService` contract locked by `E0.I14`
// (`core/model/.../Transfer.kt`, skein-18j). `exportDocx` (bd `skein-jq8`,
// `E2.I12`) delegates to `DocxWriter`, the hand-rolled OOXML writer in this
// same module's `export.docx` package (see that package's `DocxWriter.kt`
// for why it lives in `:core:vault` rather than `:core:export`, where the
// plan's file list originally put it).
//
// Design notes:
//   - This class depends only on `VaultRepository` (`core/model`), not the
//     SQLCipher-backed `VaultRepositoryImpl` (`E2.I4`) — so it works
//     against both the real vault and the JVM `InMemoryVaultRepository`
//     fake (`:testing`), which is what `MarkdownExportTest`/`VaultZipTest`
//     seed with `SyntheticVault`.
//   - `exportMarkdown`/`exportVaultZip` write directly to the
//     caller-supplied `OutputStream` (the locked contract's shape) rather
//     than returning a `File` — per `docs/design/POST_REVIEW_RESOLUTIONS.md`
//     §4.2, the v1 file-backed export flow is `ACTION_CREATE_DOCUMENT`
//     (the caller's `OutputStream` already points at the user-chosen SAF
//     destination), so no local plaintext staging happens inside this
//     class. §4's 10-minute `StagedPlaintextSweeper`/`BootReceiver`
//     machinery applies to *internal* staging (the PDF `PrintManager` spool
//     file, `E2.I11`) — not to this bead. See
//     `docs/design/export-plaintext-lifetime.md` for the sketch and
//     `bd show skein-90d` close notes for the filed follow-up.
//   - `exportVaultZip` fetches the whole vault in one `observeTimeline`
//     call (`limit = Int.MAX_VALUE`, all kinds) rather than paging by
//     `before` cursor — simpler, and correct for a single-user offline
//     vault's realistic scale; a `before`-cursor page loop risks silently
//     dropping documents that share an exact `updatedAt` millisecond at a
//     page boundary.
//   - Zip entries are written in `id`-sorted order with a fixed
//     `ZipEntry.time` (the DOS-epoch floor, 1980-01-01T00:00:00) so two
//     exports of an unchanged vault produce byte-identical output (bd
//     `skein-90d` acceptance criterion), matching the plan's "useful for
//     users diffing backups" rationale. `ZipEntry.setTimeLocal(LocalDateTime)`
//     would be a timezone-independent alternative but needs API 35+
//     (`minSdk` here is 30) — `setTime(long)` converts via the JVM/device's
//     default timezone, so byte-identical output holds per-device/per-CI-run
//     rather than across differently-configured timezones globally.
//   - `manifest.json`'s `personas` list is the distinct, sorted set of
//     `Document.personaId` values seen across the exported vault — there is
//     no dedicated `Persona` entity/repository in the codebase yet (only a
//     free-form `personaId: String?` on `Document`), so this is the best
//     available source for "listing personas" (plan `E2.I10` description).

package app.skein.core.vault.export

import app.skein.core.markdown.MarkdownAst
import app.skein.core.model.DocId
import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.ExportService
import app.skein.core.model.TimelineFilter
import app.skein.core.model.VaultRepository
import app.skein.core.vault.codec.Frontmatter
import app.skein.core.vault.export.docx.DocxWriter
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** `ExportService` implementation backing Markdown (single document + whole vault zip) export. */
public class ExportServiceImpl(
    private val repository: VaultRepository,
) : ExportService {
    override suspend fun exportMarkdown(
        docId: DocId,
        out: OutputStream,
    ) {
        val document =
            repository.getDocument(docId)
                ?: throw NoSuchElementException("no document with id=$docId")
        check(document.kind != DocumentKind.ATTACHMENT) {
            "cannot export an ATTACHMENT document as markdown: $docId"
        }
        out.write(renderMarkdown(document))
        out.flush()
    }

    override suspend fun exportVaultZip(
        out: OutputStream,
        onProgress: (done: Int, total: Int) -> Unit,
    ) {
        val allDocuments = fetchAllDocuments()
        val (attachments, content) = allDocuments.partition { it.kind == DocumentKind.ATTACHMENT }
        val sortedContent = content.sortedBy { it.id }
        val sortedAttachments = attachments.sortedBy { it.id }
        val total = sortedContent.size + sortedAttachments.size
        var done = 0

        val usedNames = mutableSetOf<String>()
        val documentManifestEntries = mutableListOf<Triple<DocId, String, String>>()
        val attachmentManifestEntries = mutableListOf<Triple<DocId, String, String>>()

        ZipOutputStream(out).use { zip ->
            for (document in sortedContent) {
                val fileName = SafeFileName.uniqueName(SafeFileName.sanitize(document.title), "md", usedNames)
                writeEntry(zip, fileName, renderMarkdown(document))
                documentManifestEntries += Triple(document.id, document.kind.db, fileName)
                done += 1
                onProgress(done, total)
            }

            for (document in sortedAttachments) {
                val mimeType = repository.attachmentMimeType(document.id)
                val path = "attachments/${document.id}.${AttachmentExtensions.forMimeType(mimeType)}"
                val bytes = repository.openAttachment(document.id).use(InputStream::readBytes)
                writeEntry(zip, path, bytes)
                attachmentManifestEntries += Triple(document.id, path, mimeType)
                done += 1
                onProgress(done, total)
            }

            val personas = allDocuments.mapNotNull { it.personaId }.distinct().sorted()
            val manifest = buildManifest(documentManifestEntries, attachmentManifestEntries, personas)
            val manifestBytes =
                MANIFEST_JSON
                    .encodeToString(
                        JsonObject.serializer(),
                        manifest,
                    ).toByteArray(Charsets.UTF_8)
            writeEntry(zip, MANIFEST_PATH, manifestBytes)
        }
    }

    override suspend fun exportDocx(
        docId: DocId,
        out: OutputStream,
        template: InputStream?,
    ) {
        val document =
            repository.getDocument(docId)
                ?: throw NoSuchElementException("no document with id=$docId")
        check(document.kind != DocumentKind.ATTACHMENT) {
            "cannot export an ATTACHMENT document as docx: $docId"
        }
        val tree = MarkdownAst.parse(document.bodyMd.orEmpty())
        DocxWriter.write(document.title, tree, out, template)
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun renderMarkdown(document: Document): ByteArray =
        Frontmatter.render(document.frontmatter, document.bodyMd.orEmpty()).toByteArray(Charsets.UTF_8)

    private suspend fun fetchAllDocuments(): List<Document> =
        repository
            .observeTimeline(
                filter = TimelineFilter(kinds = DocumentKind.entries.toSet()),
                limit = Int.MAX_VALUE,
            ).first()

    private fun writeEntry(
        zip: ZipOutputStream,
        path: String,
        bytes: ByteArray,
    ) {
        val entry = ZipEntry(path)
        entry.time = FIXED_ENTRY_TIME_MILLIS
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun buildManifest(
        documents: List<Triple<DocId, String, String>>,
        attachments: List<Triple<DocId, String, String>>,
        personas: List<String>,
    ): JsonObject =
        buildJsonObject {
            put(MANIFEST_KEY_SCHEMA_VERSION, JsonPrimitive(MANIFEST_SCHEMA_VERSION))
            putJsonArray(MANIFEST_KEY_DOCUMENTS) {
                for ((id, kind, path) in documents) {
                    addJsonObject {
                        put("id", JsonPrimitive(id))
                        put("kind", JsonPrimitive(kind))
                        put("path", JsonPrimitive(path))
                    }
                }
            }
            putJsonArray(MANIFEST_KEY_ATTACHMENTS) {
                for ((id, path, mime) in attachments) {
                    addJsonObject {
                        put("id", JsonPrimitive(id))
                        put("path", JsonPrimitive(path))
                        put("mime", JsonPrimitive(mime))
                    }
                }
            }
            putJsonArray(MANIFEST_KEY_PERSONAS) {
                for (persona in personas) add(JsonPrimitive(persona))
            }
        }

    private companion object {
        /** 1980-01-01T00:00:00 UTC epoch millis — the DOS-date floor; see the file header for the timezone caveat. */
        const val FIXED_ENTRY_TIME_MILLIS: Long = 315_532_800_000L
        const val MANIFEST_PATH: String = ".skein/manifest.json"
        const val MANIFEST_SCHEMA_VERSION: Int = 1
        const val MANIFEST_KEY_SCHEMA_VERSION: String = "schemaVersion"
        const val MANIFEST_KEY_DOCUMENTS: String = "documents"
        const val MANIFEST_KEY_ATTACHMENTS: String = "attachments"
        const val MANIFEST_KEY_PERSONAS: String = "personas"
        val MANIFEST_JSON: Json = Json { prettyPrint = true }
    }
}
