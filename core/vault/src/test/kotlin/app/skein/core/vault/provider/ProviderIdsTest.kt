// `E2.I6` (bd `skein-75x`): JVM unit tests for the SAF document-id grammar.
// bd acceptance criterion: "`isChildDocument` correctly scopes; document ids
// are `note:<uuid>` / `att:<uuid>` and never leak file paths".

package app.skein.core.vault.provider

import com.google.common.truth.Truth.assertThat
import org.junit.Test

public class ProviderIdsTest {
    private val uuid = "018f2b6e-6c3a-7c3e-8f2a-6b1e2d3c4a5b"

    // ---- formatting -------------------------------------------------------

    @Test
    public fun `note id is the note prefix followed by the document id`() {
        assertThat(ProviderIds.note(uuid)).isEqualTo("note:$uuid")
    }

    @Test
    public fun `attachment id is the att prefix followed by the document id`() {
        assertThat(ProviderIds.attachment(uuid)).isEqualTo("att:$uuid")
    }

    @Test
    public fun `no fixed id contains a path separator`() {
        val fixed =
            listOf(
                ProviderIds.ROOT_ID,
                ProviderIds.ROOT_DOCUMENT_ID,
                ProviderIds.NOTES_DOCUMENT_ID,
                ProviderIds.ATTACHMENTS_DOCUMENT_ID,
                ProviderIds.note(uuid),
                ProviderIds.attachment(uuid),
            )
        assertThat(fixed.filter { '/' in it || '\\' in it }).isEmpty()
    }

    // ---- parsing ----------------------------------------------------------

    @Test
    public fun `parse recognises the three directory ids`() {
        assertThat(
            listOf(
                ProviderIds.parse(ProviderIds.ROOT_DOCUMENT_ID),
                ProviderIds.parse(ProviderIds.NOTES_DOCUMENT_ID),
                ProviderIds.parse(ProviderIds.ATTACHMENTS_DOCUMENT_ID),
            ),
        ).containsExactly(ProviderIds.Parsed.Root, ProviderIds.Parsed.Notes, ProviderIds.Parsed.Attachments)
            .inOrder()
    }

    @Test
    public fun `parse round-trips a note id`() {
        assertThat(ProviderIds.parse(ProviderIds.note(uuid))).isEqualTo(ProviderIds.Parsed.Note(uuid))
    }

    @Test
    public fun `parse round-trips an attachment id`() {
        assertThat(ProviderIds.parse(ProviderIds.attachment(uuid))).isEqualTo(ProviderIds.Parsed.Attachment(uuid))
    }

    @Test
    public fun `parse rejects an unknown id`() {
        assertThat(ProviderIds.parse("something-else")).isNull()
    }

    @Test
    public fun `parse rejects a bare prefix with no document id`() {
        assertThat(listOf(ProviderIds.parse("note:"), ProviderIds.parse("att:"))).containsExactly(null, null)
    }

    @Test
    public fun `parse rejects a document id that looks like a path`() {
        assertThat(ProviderIds.parse("note:../../vault.db")).isNull()
    }

    @Test
    public fun `parse rejects a document id containing whitespace or control characters`() {
        assertThat(listOf(ProviderIds.parse("note:a b"), ProviderIds.parse("att:a\u0000b"))).containsExactly(null, null)
    }

    // ---- isChild ----------------------------------------------------------

    @Test
    public fun `root is the parent of both directories and of every document`() {
        val children =
            listOf(
                ProviderIds.NOTES_DOCUMENT_ID,
                ProviderIds.ATTACHMENTS_DOCUMENT_ID,
                ProviderIds.note(uuid),
                ProviderIds.attachment(uuid),
            )
        assertThat(children.all { ProviderIds.isChild(ProviderIds.ROOT_DOCUMENT_ID, it) }).isTrue()
    }

    @Test
    public fun `root is not its own child`() {
        assertThat(ProviderIds.isChild(ProviderIds.ROOT_DOCUMENT_ID, ProviderIds.ROOT_DOCUMENT_ID)).isFalse()
    }

    @Test
    public fun `notes directory owns note ids only`() {
        assertThat(
            listOf(
                ProviderIds.isChild(ProviderIds.NOTES_DOCUMENT_ID, ProviderIds.note(uuid)),
                ProviderIds.isChild(ProviderIds.NOTES_DOCUMENT_ID, ProviderIds.attachment(uuid)),
                ProviderIds.isChild(ProviderIds.NOTES_DOCUMENT_ID, ProviderIds.ATTACHMENTS_DOCUMENT_ID),
            ),
        ).containsExactly(true, false, false).inOrder()
    }

    @Test
    public fun `attachments directory owns attachment ids only`() {
        assertThat(
            listOf(
                ProviderIds.isChild(ProviderIds.ATTACHMENTS_DOCUMENT_ID, ProviderIds.attachment(uuid)),
                ProviderIds.isChild(ProviderIds.ATTACHMENTS_DOCUMENT_ID, ProviderIds.note(uuid)),
                ProviderIds.isChild(ProviderIds.ATTACHMENTS_DOCUMENT_ID, ProviderIds.NOTES_DOCUMENT_ID),
            ),
        ).containsExactly(true, false, false).inOrder()
    }

    @Test
    public fun `a document is never a parent`() {
        assertThat(ProviderIds.isChild(ProviderIds.note(uuid), ProviderIds.note(uuid))).isFalse()
    }

    @Test
    public fun `an unparseable parent or child is never related`() {
        assertThat(
            listOf(
                ProviderIds.isChild("bogus", ProviderIds.note(uuid)),
                ProviderIds.isChild(ProviderIds.ROOT_DOCUMENT_ID, "bogus"),
            ),
        ).containsExactly(false, false)
    }
}
