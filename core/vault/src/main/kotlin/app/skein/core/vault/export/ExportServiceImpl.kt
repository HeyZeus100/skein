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
import app.skein.core.model.FrontmatterKeys
import app.skein.core.model.PersonaId
import app.skein.core.model.TimelineFilter
import app.skein.core.model.VaultRepository
import app.skein.core.vault.codec.Frontmatter
import app.skein.core.vault.export.docx.DocxWriter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
        personaId: PersonaId?,
        onProgress: (done: Int, total: Int) -> Unit,
    ) {
        val allDocuments = if (personaId == null) fetchAllDocuments() else fetchSpace(personaId)
        val (attachments, content) = allDocuments.partition { it.kind == DocumentKind.ATTACHMENT }
        // skein-a0mm: every path is chosen before anything is written so the
        // manifest can lead the archive — `importVaultZip` streams, and needs
        // each entry's kind and title (in-app documents carry neither in
        // their frontmatter) before it reaches the entry.
        val usedNames = mutableSetOf<String>()
        val documentEntries =
            content.sortedBy { it.id }.map { document ->
                ArchiveEntry(document, SafeFileName.uniqueName(SafeFileName.sanitize(document.title), "md", usedNames))
            }
        val attachmentEntries =
            attachments.sortedBy { it.id }.map { document ->
                val mimeType = repository.attachmentMimeType(document.id)
                ArchiveEntry(
                    document,
                    "attachments/${document.id}.${AttachmentExtensions.forMimeType(mimeType)}",
                    mimeType,
                )
            }
        val total = documentEntries.size + attachmentEntries.size
        var done = 0

        ZipOutputStream(out).use { zip ->
            val personas = allDocuments.mapNotNull { it.personaId }.distinct().sorted()
            val manifest = buildManifest(documentEntries, attachmentEntries, personas)
            val manifestJson = MANIFEST_JSON.encodeToString(JsonObject.serializer(), manifest)
            writeEntry(zip, MANIFEST_PATH, manifestJson.toByteArray(Charsets.UTF_8))

            for (entry in documentEntries) {
                currentCoroutineContext().ensureActive()
                writeEntry(zip, entry.path, renderMarkdown(entry.document))
                done += 1
                onProgress(done, total)
            }

            for (entry in attachmentEntries) {
                currentCoroutineContext().ensureActive()
                val bytes = repository.openAttachment(entry.document.id).use(InputStream::readBytes)
                writeEntry(zip, entry.path, bytes)
                done += 1
                onProgress(done, total)
            }
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
        Frontmatter.renderDocument(document).toByteArray(Charsets.UTF_8)

    private suspend fun fetchAllDocuments(personaId: PersonaId? = null): List<Document> =
        repository
            .observeTimeline(
                filter = TimelineFilter(personaId = personaId, kinds = DocumentKind.entries.toSet()),
                limit = Int.MAX_VALUE,
            ).first()

    /** A Space's documents plus the attachments they cite via `source:` (attachments have no persona). */
    private suspend fun fetchSpace(personaId: PersonaId): List<Document> {
        val documents = fetchAllDocuments(personaId)
        val cited =
            documents
                .mapNotNull { (it.frontmatter[FrontmatterKeys.SOURCE] as? JsonPrimitive)?.contentOrNull }
                .distinct()
                .mapNotNull { id -> repository.getDocument(id)?.takeIf { it.kind == DocumentKind.ATTACHMENT } }
        return (documents + cited).distinctBy { it.id }
    }

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

    /** One archive entry: the document, its path in the zip, and (attachments only) its MIME type. */
    private class ArchiveEntry(
        val document: Document,
        val path: String,
        val mimeType: String? = null,
    )

    private fun buildManifest(
        documents: List<ArchiveEntry>,
        attachments: List<ArchiveEntry>,
        personas: List<String>,
    ): JsonObject =
        buildJsonObject {
            put(MANIFEST_KEY_SCHEMA_VERSION, JsonPrimitive(MANIFEST_SCHEMA_VERSION))
            putJsonArray(MANIFEST_KEY_DOCUMENTS) {
                for (entry in documents) {
                    addJsonObject {
                        put("id", JsonPrimitive(entry.document.id))
                        put("kind", JsonPrimitive(entry.document.kind.db))
                        put("path", JsonPrimitive(entry.path))
                        put("title", JsonPrimitive(entry.document.title))
                    }
                }
            }
            putJsonArray(MANIFEST_KEY_ATTACHMENTS) {
                for (entry in attachments) {
                    addJsonObject {
                        put("id", JsonPrimitive(entry.document.id))
                        put("path", JsonPrimitive(entry.path))
                        put("mime", JsonPrimitive(entry.mimeType))
                        put("title", JsonPrimitive(entry.document.title))
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
