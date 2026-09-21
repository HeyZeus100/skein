// `E2.I6` (bd `skein-75x`): the pure document → cursor-row mapping behind
// `VaultDocumentsProvider`. Rows are plain `Map<column, value>`s rather
// than `MatrixCursor`s so the mapping is unit-testable on the host JVM
// (`MatrixCursor` is an Android class the unit-test `android.jar` stubs
// out); the provider turns a row into a `MatrixCursor` with [project].
//
// Flags are deliberately minimal (plan `E2.I6`, spec §9 "tight
// DocumentsProvider grants"): the root advertises only
// `Root.FLAG_LOCAL_ONLY`; directories advertise nothing (no
// `FLAG_DIR_SUPPORTS_CREATE`); a `NOTE` advertises `FLAG_SUPPORTS_WRITE`
// and every other document nothing. `FLAG_SUPPORTS_DELETE`,
// `FLAG_SUPPORTS_MOVE`, `FLAG_SUPPORTS_RENAME`, `FLAG_SUPPORTS_COPY`,
// `FLAG_SUPPORTS_REMOVE`, `Root.FLAG_SUPPORTS_RECENTS`,
// `Root.FLAG_SUPPORTS_SEARCH`, `Root.FLAG_SUPPORTS_CREATE` are never set.
// `Root.FLAG_SUPPORTS_IS_CHILD` is also withheld on purpose — see the
// `VaultDocumentsProvider` class header (tree grants are refused by the
// manifest's `<grant-uri-permission>` subsets, so the root must not be
// offered to `ACTION_OPEN_DOCUMENT_TREE` pickers).

package app.skein.core.vault.provider

import android.provider.DocumentsContract
import app.skein.core.vault.codec.Frontmatter
import app.skein.core.vault.export.AttachmentExtensions
import app.skein.core.vault.export.SafeFileName
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import us.aherrera.skein.core.model.Document
import us.aherrera.skein.core.model.DocumentKind

/** One provider cursor row: column name → `String` / `Long` / `Int` / `null`. */
internal typealias ProviderRow = Map<String, Any?>

internal object ProviderCursors {
    const val MARKDOWN_MIME_TYPE: String = "text/markdown"
    const val MARKDOWN_EXTENSION: String = "md"

    const val ROOT_TITLE: String = "Skein vault"
    const val NOTES_DISPLAY_NAME: String = "Notes"
    const val ATTACHMENTS_DISPLAY_NAME: String = "Attachments"

    const val ROOT_FLAGS: Int = DocumentsContract.Root.FLAG_LOCAL_ONLY
    const val DIRECTORY_FLAGS: Int = 0
    const val WRITABLE_NOTE_FLAGS: Int = DocumentsContract.Document.FLAG_SUPPORTS_WRITE
    const val READ_ONLY_FLAGS: Int = 0

    /** Frontmatter key `VaultRepository.createAttachment` records the byte length under. */
    private const val ATTACHMENT_SIZE_KEY: String = "size"

    val DEFAULT_ROOT_PROJECTION: Array<String> =
        arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
        )

    val DEFAULT_DOCUMENT_PROJECTION: Array<String> =
        arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
        )

    // A title that already ends in a short alphanumeric extension is left
    // alone; anything else gets the mime-derived one appended.
    private val TRAILING_EXTENSION = Regex("\\.[A-Za-z0-9]{1,5}$")

    // ---- rows -----------------------------------------------------------------

    fun rootRow(iconResId: Int): ProviderRow =
        mapOf(
            DocumentsContract.Root.COLUMN_ROOT_ID to ProviderIds.ROOT_ID,
            DocumentsContract.Root.COLUMN_FLAGS to ROOT_FLAGS,
            DocumentsContract.Root.COLUMN_ICON to iconResId,
            DocumentsContract.Root.COLUMN_TITLE to ROOT_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY to null,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID to ProviderIds.ROOT_DOCUMENT_ID,
            // null = every mime type; attachments can be anything.
            DocumentsContract.Root.COLUMN_MIME_TYPES to null,
        )

    fun rootDirectoryRow(): ProviderRow = directoryRow(ProviderIds.ROOT_DOCUMENT_ID, ROOT_TITLE)

    fun notesDirectoryRow(): ProviderRow = directoryRow(ProviderIds.NOTES_DOCUMENT_ID, NOTES_DISPLAY_NAME)

    fun attachmentsDirectoryRow(): ProviderRow =
        directoryRow(ProviderIds.ATTACHMENTS_DOCUMENT_ID, ATTACHMENTS_DISPLAY_NAME)

    /** A NOTE / CHAT / AIOUT document as a virtual `<title>.md` file. */
    fun markdownRow(document: Document): ProviderRow {
        require(document.kind != DocumentKind.ATTACHMENT) { "not a markdown document: ${document.id}" }
        return mapOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID to ProviderIds.note(document.id),
            DocumentsContract.Document.COLUMN_MIME_TYPE to MARKDOWN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME to markdownDisplayName(document.title),
            DocumentsContract.Document.COLUMN_LAST_MODIFIED to document.updatedAt,
            DocumentsContract.Document.COLUMN_FLAGS to
                if (document.kind == DocumentKind.NOTE) WRITABLE_NOTE_FLAGS else READ_ONLY_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE to markdownSize(document),
        )
    }

    /** An ATTACHMENT document as its original blob, with the stored [mimeType]. */
    fun attachmentRow(
        document: Document,
        mimeType: String,
    ): ProviderRow {
        require(document.kind == DocumentKind.ATTACHMENT) { "not an attachment document: ${document.id}" }
        return mapOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID to ProviderIds.attachment(document.id),
            DocumentsContract.Document.COLUMN_MIME_TYPE to mimeType,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME to attachmentDisplayName(document.title, mimeType),
            DocumentsContract.Document.COLUMN_LAST_MODIFIED to document.updatedAt,
            DocumentsContract.Document.COLUMN_FLAGS to READ_ONLY_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE to
                (document.frontmatter[ATTACHMENT_SIZE_KEY] as? JsonPrimitive)?.longOrNull,
        )
    }

    // ---- naming / sizing --------------------------------------------------------

    /**
     * `<sanitized title>.md`. Deliberately no ` (2)` collision suffix
     * (unlike `ExportServiceImpl.exportVaultZip`, where names *are* the
     * identity): SAF identifies documents by id, duplicates in a listing
     * are legal, and suffixing would force every `queryDocument` to fetch
     * the whole listing just to number itself consistently.
     */
    fun markdownDisplayName(title: String): String = "${SafeFileName.sanitize(title)}.$MARKDOWN_EXTENSION"

    fun attachmentDisplayName(
        title: String,
        mimeType: String,
    ): String {
        val base = SafeFileName.sanitize(title)
        return if (TRAILING_EXTENSION.containsMatchIn(
                base,
            )
        ) {
            base
        } else {
            "$base.${AttachmentExtensions.forMimeType(mimeType)}"
        }
    }

    /**
     * Byte length of exactly what `ExportServiceImpl.exportMarkdown` streams
     * for [document] (`Frontmatter.render(frontmatter, body)` as UTF-8) —
     * `VaultDocumentsBackendTest` pins the two together.
     */
    fun markdownSize(document: Document): Long =
        Frontmatter
            .render(document.frontmatter, document.bodyMd.orEmpty())
            .toByteArray(Charsets.UTF_8)
            .size
            .toLong()

    // ---- projection ---------------------------------------------------------------

    /** The framework passes `null` (or, from some clients, an empty array) to mean "the default columns". */
    fun resolveProjection(
        requested: Array<String>?,
        default: Array<String>,
    ): Array<String> = requested?.takeIf { it.isNotEmpty() } ?: default

    /** [row]'s values in [projection] order; a column the row lacks becomes `null`, as `MatrixCursor` expects. */
    fun project(
        row: ProviderRow,
        projection: Array<String>,
    ): Array<Any?> = Array(projection.size) { row[projection[it]] }

    private fun directoryRow(
        documentId: String,
        displayName: String,
    ): ProviderRow =
        mapOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID to documentId,
            DocumentsContract.Document.COLUMN_MIME_TYPE to DocumentsContract.Document.MIME_TYPE_DIR,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME to displayName,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED to null,
            DocumentsContract.Document.COLUMN_FLAGS to DIRECTORY_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE to null,
        )
}
