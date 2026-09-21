package app.skein.feature.editor.share

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * bd `skein-fay` (plan `E6.I16`), reconciled to
 * `docs/design/POST_REVIEW_RESOLUTIONS.md` §4.2/§4.5: "share as text" is
 * `ACTION_SEND`/`EXTRA_TEXT`, in-memory only. This test pins the exact
 * intent shape and — the non-negotiable this bead exists to prove — that
 * nothing here carries a `Uri`, a `ClipData` `Uri`, or a `FLAG_GRANT_*`
 * flag; the v1.1-deferred "hand another app a `content://` grant" flow
 * (§4.2 candidate (a)) must never sneak back in through this helper.
 *
 * Pinned to SDK 34 (bd memory `robolectric-sdk37-needs-java21`) — building a
 * real `android.content.Intent` needs Robolectric's shadow; the compile
 * `android.jar` stub throws on every method body.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareIntentsTest {
    @Test
    fun `shareAsText builds an ACTION_SEND text-plain intent carrying title and body`() {
        val intent = ShareIntents.shareAsText(title = "My Note", bodyMd = "hello **world**")

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals("My Note", intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals("hello **world**", intent.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun `shareAsText never attaches a Uri`() {
        val intent = ShareIntents.shareAsText(title = "t", bodyMd = "b")

        assertNull(intent.data)
    }

    @Test
    fun `shareAsText never attaches ClipData`() {
        val intent = ShareIntents.shareAsText(title = "t", bodyMd = "b")

        assertNull(intent.clipData)
    }

    @Test
    fun `shareAsText never sets a FLAG_GRANT permission flag`() {
        val intent = ShareIntents.shareAsText(title = "t", bodyMd = "b")

        val grantFlags =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

        assertEquals(0, intent.flags and grantFlags)
    }

    @Test
    fun `chooser wraps the given intent as ACTION_CHOOSER carrying it under EXTRA_INTENT`() {
        val send = ShareIntents.shareAsText(title = "t", bodyMd = "b")

        val chosen = ShareIntents.chooser(send, title = "Share note")

        assertEquals(Intent.ACTION_CHOOSER, chosen.action)
        val wrapped = chosen.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        assertEquals(Intent.ACTION_SEND, wrapped?.action)
    }
}
