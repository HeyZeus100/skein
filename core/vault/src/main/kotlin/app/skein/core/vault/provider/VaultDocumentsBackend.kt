// `E2.I6` (bd `skein-75x`): the Android-free core of `VaultDocumentsProvider`.
// Every provider entry point (`queryRoots`, `queryDocument`,
// `queryChildDocuments`, `isChildDocument`, `openDocument`) is a thin
// `MatrixCursor` / `ParcelFileDescriptor` shell over one method here, and
// every method here starts with [requireUnlocked] — so the lock gate the
// plan demands ("Requires the vault to be unlocked; otherwise throws
// `FileNotFoundException("vault locked")`") lives in exactly one place and
// is unit-tested on the host JVM against a fake `StateFlow<UnlockState>`
// and against a real `UnlockManager` (`VaultDocumentsBackendTest`).
//
// Depends only on the `VaultRepository` / `ExportService` contracts
// (`core/model`) plus `UnlockManager.state`'s type, exactly like
// `ExportServiceImpl` (`E2.I10`) — so it runs unchanged over the JVM
// `InMemoryVaultRepository` fake and the SQLCipher-backed
// `VaultRepositoryImpl`. `VaultRepositoryImpl`, `ExportServiceImpl` and
// `UnlockManager` are consumed, not modified.
//
// Read path: notes/chats/AI outputs stream through
// `ExportService.exportMarkdown` (bd: "content = `ExportService.exportMarkdown`"),
// attachments stream from `VaultRepository.openAttachment` in
// [COPY_BUFFER_BYTES] chunks with the lock re-checked before every chunk
// (`LOCK_POLICY_INDEXING.md` §4: locked = no plaintext-capable code runs).
// Nothing is ever staged on disk (`POST_REVIEW_RESOLUTIONS.md` §4.2).
//
// Write path (`NOTE` kind only): the bytes a client wrote are decoded as
// UTF-8 (BOM stripped, CRLF normalised to the vault's LF), split by
// `Frontmatter.parse`, and persisted via `updateBody` (title from the
// written `title:` key, else the existing title) followed by
// `updateFrontmatter` when a frontmatter block was present. The
// repository's own ingest trigger (`documents_au_ingest`, or the fake's
// explicit enqueue) covers bd's "enqueues ingest". The two calls are
// sequential rather than wrapped in `VaultRepository.transaction`: the
// `:testing` fake's `transaction` holds a non-reentrant `Mutex` that
// `updateBody` also takes, and a note written by an external editor is
// consistent after either step alone.

package app.skein.core.vault.provider

import app.skein.core.vault.codec.Frontmatter
import app.skein.core.vault.session.UnlockState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonPrimitive
import us.aherrera.skein.core.model.DocId
import us.aherrera.skein.core.model.Document
import us.aherrera.skein.core.model.DocumentKind
import us.aherrera.skein.core.model.ExportService
import us.aherrera.skein.core.model.FrontmatterKeys
import us.aherrera.skein.core.model.TimelineFilter
import us.aherrera.skein.core.model.VaultRepository
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

internal class VaultDocumentsBackend(
    private val repository: VaultRepository,
    private val exportService: ExportService,
    private val unlockState: StateFlow<UnlockState>,
) {
    /** What `openDocument` may hand out for a given (document, mode) pair. */
    enum class Access { READ, WRITE }

    val isUnlocked: Boolean
        get() = unlockState.value is UnlockState.Unlocked

    /** The single lock gate. `FileNotFoundException` is what the SAF contract lets a provider throw. */
    fun requireUnlocked() {
        if (!isUnlocked) throw FileNotFoundException(VAULT_LOCKED)
    }

    // ---- queries ------------------------------------------------------------------

    suspend fun roots(iconResId: Int): List<ProviderRow> {
        requireUnlocked()
        return listOf(ProviderCursors.rootRow(iconResId))
    }

    suspend fun document(documentId: String): ProviderRow {
        requireUnlocked()
        return when (val id = parse(documentId)) {
            ProviderIds.Parsed.Root -> ProviderCursors.rootDirectoryRow()
            ProviderIds.Parsed.Notes -> ProviderCursors.notesDirectoryRow()
            ProviderIds.Parsed.Attachments -> ProviderCursors.attachmentsDirectoryRow()
            is ProviderIds.Parsed.Note -> ProviderCursors.markdownRow(requireMarkdownDocument(id.docId))
            is ProviderIds.Parsed.Attachment -> {
                val attachment = requireAttachmentDocument(id.docId)
                ProviderCursors.attachmentRow(attachment, repository.attachmentMimeType(attachment.id))
            }
        }
    }

    suspend fun children(parentDocumentId: String): List<ProviderRow> {
        requireUnlocked()
        return when (parse(parentDocumentId)) {
            ProviderIds.Parsed.Root ->
                listOf(ProviderCursors.notesDirectoryRow(), ProviderCursors.attachmentsDirectoryRow())
            ProviderIds.Parsed.Notes -> markdownDocuments().map(ProviderCursors::markdownRow)
            ProviderIds.Parsed.Attachments ->
                attachmentDocuments().map { ProviderCursors.attachmentRow(it, repository.attachmentMimeType(it.id)) }
            is ProviderIds.Parsed.Note, is ProviderIds.Parsed.Attachment ->
                throw FileNotFoundException("not a directory: $parentDocumentId")
        }
    }

    /** Locked ⇒ nothing is related to anything (the framework turns `false` into a `SecurityException`). */
    fun isChildDocument(
        parentDocumentId: String,
        documentId: String,
    ): Boolean = isUnlocked && ProviderIds.isChild(parentDocumentId, documentId)

    // ---- open -----------------------------------------------------------------------

    /**
     * Validates [mode] against the document before any pipe is created:
     * `r` on any file, `w`/`wt` on a `NOTE`; everything else (`rw`,
     * `rwt`, `wa`, …) is refused because a pipe cannot seek and a
     * read-write handle over a virtual file would have no sane semantics.
     */
    suspend fun resolveAccess(
        documentId: String,
        mode: String,
    ): Access {
        requireUnlocked()
        val access =
            when (mode) {
                "r" -> Access.READ
                "w", "wt" -> Access.WRITE
                else -> throw UnsupportedOperationException("unsupported open mode '$mode'")
            }
        when (val id = parse(documentId)) {
            is ProviderIds.Parsed.Note -> {
                val document = requireMarkdownDocument(id.docId)
                if (access == Access.WRITE && document.kind != DocumentKind.NOTE) {
                    throw UnsupportedOperationException(
                        "only NOTE documents are writable; ${document.kind} is read-only",
                    )
                }
            }
            is ProviderIds.Parsed.Attachment -> {
                requireAttachmentDocument(id.docId)
                if (access == Access.WRITE) throw UnsupportedOperationException("attachments are read-only")
            }
            ProviderIds.Parsed.Root, ProviderIds.Parsed.Notes, ProviderIds.Parsed.Attachments ->
                throw FileNotFoundException("not a file: $documentId")
        }
        return access
    }

    /** Streams [documentId]'s content into [out]. Throws before writing a byte when locked. */
    suspend fun read(
        documentId: String,
        out: OutputStream,
    ) {
        requireUnlocked()
        when (val id = parse(documentId)) {
            is ProviderIds.Parsed.Note -> {
                requireMarkdownDocument(id.docId)
                exportService.exportMarkdown(id.docId, out)
            }
            is ProviderIds.Parsed.Attachment -> {
                requireAttachmentDocument(id.docId)
                repository.openAttachment(id.docId).use { copyWhileUnlocked(it, out) }
            }
            ProviderIds.Parsed.Root, ProviderIds.Parsed.Notes, ProviderIds.Parsed.Attachments ->
                throw FileNotFoundException("not a file: $documentId")
        }
    }

    /** Persists [bytes] (a whole Markdown file) over the `NOTE` behind [documentId]; see the file header. */
    suspend fun writeNote(
        documentId: String,
        bytes: ByteArray,
    ): Document {
        requireUnlocked()
        val id =
            parse(documentId) as? ProviderIds.Parsed.Note
                ?: throw FileNotFoundException("not a writable note: $documentId")
        val existing = requireMarkdownDocument(id.docId)
        if (existing.kind != DocumentKind.NOTE) {
            throw UnsupportedOperationException("only NOTE documents are writable; ${existing.kind} is read-only")
        }
        val (frontmatter, body) = Frontmatter.parse(decodeNoteText(bytes))
        val title = frontmatterTitle(frontmatter) ?: existing.title
        val updated = repository.updateBody(id.docId, title, body)
        return if (frontmatter.isEmpty()) updated else repository.updateFrontmatter(id.docId, frontmatter)
    }

    // ---- internals -----------------------------------------------------------------

    private fun parse(documentId: String): ProviderIds.Parsed =
        ProviderIds.parse(documentId) ?: throw FileNotFoundException("unknown document id: $documentId")

    private suspend fun requireMarkdownDocument(docId: DocId): Document {
        val document = repository.getDocument(docId) ?: throw FileNotFoundException("no such note: $docId")
        if (document.kind == DocumentKind.ATTACHMENT) throw FileNotFoundException("no such note: $docId")
        return document
    }

    private suspend fun requireAttachmentDocument(docId: DocId): Document {
        val document = repository.getDocument(docId) ?: throw FileNotFoundException("no such attachment: $docId")
        if (document.kind != DocumentKind.ATTACHMENT) throw FileNotFoundException("no such attachment: $docId")
        return document
    }

    private suspend fun markdownDocuments(): List<Document> =
        repository
            .observeTimeline(
                filter = TimelineFilter(kinds = DocumentKind.entries.toSet() - DocumentKind.ATTACHMENT),
                limit = Int.MAX_VALUE,
            ).first()

    private suspend fun attachmentDocuments(): List<Document> =
        repository
            .observeTimeline(filter = TimelineFilter(kinds = setOf(DocumentKind.ATTACHMENT)), limit = Int.MAX_VALUE)
            .first()

    private fun copyWhileUnlocked(
        input: InputStream,
        out: OutputStream,
    ) {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            requireUnlocked()
            out.write(buffer, 0, n)
        }
        out.flush()
    }

    private fun decodeNoteText(bytes: ByteArray): String =
        bytes
            .toString(Charsets.UTF_8)
            .removePrefix(UTF8_BOM)
            .replace("\r\n", "\n")

    private fun frontmatterTitle(frontmatter: Map<String, Any?>): String? =
        (frontmatter[FrontmatterKeys.TITLE] as? JsonPrimitive)
            ?.content
            ?.takeIf { it.isNotBlank() && '\n' !in it }

    companion object {
        /** The exact message the plan specifies for every locked-vault refusal. */
        const val VAULT_LOCKED: String = "vault locked"

        const val COPY_BUFFER_BYTES: Int = 8 * 1024

        /** Upper bound on a single note written through the provider; a pipe is unbounded otherwise. */
        const val MAX_NOTE_BYTES: Int = 16 * 1024 * 1024

        /** U+FEFF, built from its code point so no editor/tool can silently drop the escape. */
        private val UTF8_BOM: String = 0xFEFF.toChar().toString()

        /** Reads [input] to EOF, refusing (with [IOException]) anything longer than [limit] bytes. */
        fun readBounded(
            input: InputStream,
            limit: Int,
        ): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (out.size() + n > limit) throw IOException("note exceeds $limit bytes")
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }
    }
}
