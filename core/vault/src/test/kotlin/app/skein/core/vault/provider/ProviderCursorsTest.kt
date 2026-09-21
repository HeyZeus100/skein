// `E2.I6` (bd `skein-75x`): JVM unit tests for the pure row-building /
// projection layer behind `VaultDocumentsProvider`'s `MatrixCursor`s.
// `MatrixCursor` itself is an Android class (stubbed on the host JVM), so
// the document → row mapping is exercised here on plain `Map`s and the
// real cursor plumbing is covered by the instrumented
// `VaultDocumentsProviderTest`.

package app.skein.core.vault.provider

import android.provider.DocumentsContract
import app.skein.core.vault.codec.Frontmatter
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test
import us.aherrera.skein.core.model.Document
import us.aherrera.skein.core.model.DocumentKind
import us.aherrera.skein.core.model.FrontmatterKeys

public class ProviderCursorsTest {
    private val note =
        Document(
            id = "018f2b6e-6c3a-7c3e-8f2a-6b1e2d3c4a5b",
            kind = DocumentKind.NOTE,
            title = "Example Note",
            bodyMd = "body",
            createdAt = 1_000L,
            updatedAt = 2_000L,
            personaId = null,
            frontmatter =
                buildJsonObject {
                    put(
                        FrontmatterKeys.ID,
                        JsonPrimitive("018f2b6e-6c3a-7c3e-8f2a-6b1e2d3c4a5b"),
                    )
                },
            contentHash = null,
        )

    private val attachment =
        Document(
            id = "1c3d1b34-9f6c-4d9a-8b1e-0e2f3a4b5c6d",
            kind = DocumentKind.ATTACHMENT,
            title = "External 1",
            bodyMd = null,
            createdAt = 3_000L,
            updatedAt = 4_000L,
            personaId = null,
            frontmatter = buildJsonObject { put("size", JsonPrimitive(1234L)) },
            contentHash = null,
        )

    // ---- projection ---------------------------------------------------------

    @Test
    public fun `resolveProjection falls back to the default when none is requested`() {
        assertThat(ProviderCursors.resolveProjection(null, ProviderCursors.DEFAULT_DOCUMENT_PROJECTION))
            .isEqualTo(ProviderCursors.DEFAULT_DOCUMENT_PROJECTION)
    }

    @Test
    public fun `resolveProjection falls back to the default when an empty one is requested`() {
        assertThat(ProviderCursors.resolveProjection(emptyArray(), ProviderCursors.DEFAULT_ROOT_PROJECTION))
            .isEqualTo(ProviderCursors.DEFAULT_ROOT_PROJECTION)
    }

    @Test
    public fun `resolveProjection keeps a requested projection verbatim`() {
        val requested = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        assertThat(ProviderCursors.resolveProjection(requested, ProviderCursors.DEFAULT_DOCUMENT_PROJECTION))
            .isEqualTo(requested)
    }

    @Test
    public fun `project emits values in projection order and null for unknown columns`() {
        val row = ProviderCursors.markdownRow(note)
        val projection =
            arrayOf(
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                "not_a_column",
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            )
        assertThat(ProviderCursors.project(row, projection).toList())
            .containsExactly(ProviderCursors.MARKDOWN_MIME_TYPE, null, "note:${note.id}")
            .inOrder()
    }

    // ---- root row -----------------------------------------------------------

    @Test
    public fun `root row points at the root directory document`() {
        val row = ProviderCursors.rootRow(iconResId = 7)
        assertThat(row[DocumentsContract.Root.COLUMN_DOCUMENT_ID]).isEqualTo(ProviderIds.ROOT_DOCUMENT_ID)
    }

    @Test
    public fun `root row advertises only local-only and never recents, search, create or is-child`() {
        val flags = ProviderCursors.rootRow(iconResId = 7)[DocumentsContract.Root.COLUMN_FLAGS] as Int
        assertThat(flags).isEqualTo(DocumentsContract.Root.FLAG_LOCAL_ONLY)
    }

    @Test
    public fun `root row carries the supplied icon and the vault title`() {
        val row = ProviderCursors.rootRow(iconResId = 7)
        assertThat(row[DocumentsContract.Root.COLUMN_ICON] to row[DocumentsContract.Root.COLUMN_TITLE])
            .isEqualTo(7 to ProviderCursors.ROOT_TITLE)
    }

    // ---- directory rows -----------------------------------------------------

    @Test
    public fun `directory rows use the SAF directory mime type`() {
        val rows =
            listOf(
                ProviderCursors.rootDirectoryRow(),
                ProviderCursors.notesDirectoryRow(),
                ProviderCursors.attachmentsDirectoryRow(),
            )
        assertThat(rows.map { it[DocumentsContract.Document.COLUMN_MIME_TYPE] }.distinct())
            .containsExactly(DocumentsContract.Document.MIME_TYPE_DIR)
    }

    @Test
    public fun `directory rows advertise no flags so nothing can be created, deleted or moved`() {
        val rows =
            listOf(
                ProviderCursors.rootDirectoryRow(),
                ProviderCursors.notesDirectoryRow(),
                ProviderCursors.attachmentsDirectoryRow(),
            )
        assertThat(rows.map { it[DocumentsContract.Document.COLUMN_FLAGS] }.distinct()).containsExactly(0)
    }

    // ---- markdown rows ------------------------------------------------------

    @Test
    public fun `markdown row id is the note-prefixed document id`() {
        assertThat(ProviderCursors.markdownRow(note)[DocumentsContract.Document.COLUMN_DOCUMENT_ID])
            .isEqualTo("note:${note.id}")
    }

    @Test
    public fun `markdown row display name is the sanitized title with an md extension`() {
        assertThat(
            ProviderCursors.markdownRow(note.copy(title = "a/b\\c"))[DocumentsContract.Document.COLUMN_DISPLAY_NAME],
        ).isEqualTo("abc.md")
    }

    @Test
    public fun `markdown row size equals the rendered frontmatter plus body byte count`() {
        val expected =
            Frontmatter
                .render(note.frontmatter, note.bodyMd!!)
                .toByteArray(Charsets.UTF_8)
                .size
                .toLong()
        assertThat(ProviderCursors.markdownRow(note)[DocumentsContract.Document.COLUMN_SIZE]).isEqualTo(expected)
    }

    @Test
    public fun `markdown row last modified is updatedAt`() {
        assertThat(ProviderCursors.markdownRow(note)[DocumentsContract.Document.COLUMN_LAST_MODIFIED]).isEqualTo(2_000L)
    }

    @Test
    public fun `a NOTE row is writable and nothing else`() {
        assertThat(ProviderCursors.markdownRow(note)[DocumentsContract.Document.COLUMN_FLAGS])
            .isEqualTo(DocumentsContract.Document.FLAG_SUPPORTS_WRITE)
    }

    @Test
    public fun `a CHAT row is read-only`() {
        assertThat(
            ProviderCursors.markdownRow(note.copy(kind = DocumentKind.CHAT))[DocumentsContract.Document.COLUMN_FLAGS],
        ).isEqualTo(0)
    }

    @Test
    public fun `an AIOUT row is read-only`() {
        assertThat(
            ProviderCursors.markdownRow(note.copy(kind = DocumentKind.AIOUT))[DocumentsContract.Document.COLUMN_FLAGS],
        ).isEqualTo(0)
    }

    @Test
    public fun `markdown row refuses an attachment document`() {
        val error = runCatching { ProviderCursors.markdownRow(attachment) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    // ---- attachment rows ----------------------------------------------------

    @Test
    public fun `attachment row id is the att-prefixed document id`() {
        assertThat(
            ProviderCursors.attachmentRow(attachment, "application/pdf")[DocumentsContract.Document.COLUMN_DOCUMENT_ID],
        ).isEqualTo("att:${attachment.id}")
    }

    @Test
    public fun `attachment row mime type is the stored mime type`() {
        assertThat(
            ProviderCursors.attachmentRow(attachment, "application/pdf")[DocumentsContract.Document.COLUMN_MIME_TYPE],
        ).isEqualTo("application/pdf")
    }

    @Test
    public fun `attachment row appends an extension derived from the mime type when the title has none`() {
        assertThat(
            ProviderCursors.attachmentRow(
                attachment,
                "application/pdf",
            )[DocumentsContract.Document.COLUMN_DISPLAY_NAME],
        ).isEqualTo("External 1.pdf")
    }

    @Test
    public fun `attachment row keeps a title that already carries an extension`() {
        val titled = attachment.copy(title = "photo.jpeg")
        assertThat(ProviderCursors.attachmentRow(titled, "image/jpeg")[DocumentsContract.Document.COLUMN_DISPLAY_NAME])
            .isEqualTo("photo.jpeg")
    }

    @Test
    public fun `attachment row size comes from the stored size frontmatter key`() {
        assertThat(ProviderCursors.attachmentRow(attachment, "application/pdf")[DocumentsContract.Document.COLUMN_SIZE])
            .isEqualTo(1234L)
    }

    @Test
    public fun `attachment row size is null when the size key is absent`() {
        val sizeless = attachment.copy(frontmatter = buildJsonObject { })
        assertThat(
            ProviderCursors.attachmentRow(sizeless, "application/pdf")[DocumentsContract.Document.COLUMN_SIZE],
        ).isNull()
    }

    @Test
    public fun `attachment row is read-only`() {
        assertThat(
            ProviderCursors.attachmentRow(attachment, "application/pdf")[DocumentsContract.Document.COLUMN_FLAGS],
        ).isEqualTo(0)
    }

    @Test
    public fun `attachment row refuses a non-attachment document`() {
        val error = runCatching { ProviderCursors.attachmentRow(note, "text/plain") }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }
}
