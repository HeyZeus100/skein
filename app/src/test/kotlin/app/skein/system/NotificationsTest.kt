package app.skein.system

import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * E3.I8 (spec §9): every notification in the app must go through
 * [Notifications.newBuilder] so lock-screen previews never leak vault/chat
 * content — `VISIBILITY_SECRET` hides the notification entirely on a
 * locked, non-secure lock screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationsTest {
    @Test
    fun `newBuilder produces VISIBILITY_SECRET`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val notification =
            Notifications
                .newBuilder(context, "test-channel")
                .setContentTitle("Indexing")
                .build()

        assertEquals(NotificationCompat.VISIBILITY_SECRET, notification.visibility)
    }

    @Test
    fun `newBuilder does not itself put any document content into the notification`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val notification = Notifications.newBuilder(context, "test-channel").build()

        val contentText = notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)
        assertTrue(
            "a bare newBuilder() notification must carry no content text, got '$contentText'",
            contentText.isNullOrEmpty(),
        )
    }
}
