package app.skein.feature.editor.share

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * bd `skein-fay` v1 scope item 2 ("save as..."):
 * `docs/design/POST_REVIEW_RESOLUTIONS.md` §4.2 puts file-backed export on
 * `ACTION_CREATE_DOCUMENT` (a user-chosen SAF destination) rather than a
 * granted `content://` `Uri` — this pins the exact intent shape for both
 * formats `NoteTab`'s "Save as..." menu offers, and that the intent never
 * carries a `Uri`/grant flag of its own (the system picker supplies the
 * destination `Uri` back to the caller; nothing here hands one out).
 *
 * Pinned to SDK 34 (bd memory `robolectric-sdk37-needs-java21`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SaveAsIntentsTest {
    @Test
    fun `createDocument for MARKDOWN is ACTION_CREATE_DOCUMENT with the markdown mime type and a sanitized md title`() {
        val intent = SaveAsIntents.createDocument(SaveAsFormat.MARKDOWN, suggestedTitle = "My Note")

        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals("text/markdown", intent.type)
        assertTrue(intent.categories.orEmpty().contains(Intent.CATEGORY_OPENABLE))
        assertEquals("My Note.md", intent.getStringExtra(Intent.EXTRA_TITLE))
    }

    @Test
    fun `createDocument for DOCX is ACTION_CREATE_DOCUMENT with the docx mime type and a sanitized docx title`() {
        val intent = SaveAsIntents.createDocument(SaveAsFormat.DOCX, suggestedTitle = "My Note")

        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            intent.type,
        )
        assertEquals("My Note.docx", intent.getStringExtra(Intent.EXTRA_TITLE))
    }

    @Test
    fun `createDocument sanitizes a title containing path separators`() {
        val intent = SaveAsIntents.createDocument(SaveAsFormat.MARKDOWN, suggestedTitle = "a/b\\c")

        assertEquals("abc.md", intent.getStringExtra(Intent.EXTRA_TITLE))
    }

    @Test
    fun `createDocument never attaches a Uri of its own`() {
        val intent = SaveAsIntents.createDocument(SaveAsFormat.MARKDOWN, suggestedTitle = "t")

        assertNull(intent.data)
    }
}
