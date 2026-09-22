package app.skein.notify

import android.app.Notification
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import app.skein.ingest.IngestProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNotificationManager

/**
 * E6.I18 (skein-fsn): [IndexingNotifier] over a controlled [IngestProgress]
 * source and Robolectric's [ShadowNotificationManager], asserting:
 *  - exactly one notification is posted on the `indexing` channel, with
 *    `VISIBILITY_SECRET` and counts-only text (`"12 of 40 documents"`), and
 *    no document title/content anywhere in its extras (only [Notification]'s
 *    own `EXTRA_TEXT`, never `EXTRA_TITLE`/`EXTRA_BIG_TEXT`/`EXTRA_SUB_TEXT`);
 *  - when the injected `hasPermission` check reports denied (the same seam
 *    `MainActivity` wires to `ContextCompat.checkSelfPermission` in
 *    production — see [IndexingNotifier]'s constructor doc), no notification
 *    is posted and no exception is thrown.
 *
 * Pinned to SDK 34, matching every other Robolectric test in `:app` (bd
 * memory `robolectric-sdk37-needs-java21`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IndexingNotifierTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val shadowNotificationManager: ShadowNotificationManager
        get() =
            shadowOf(
                context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as NotificationManager,
            )

    @After
    fun tearDown() {
        scope.cancel()
    }

    private suspend fun awaitNotificationPosted() =
        withTimeout(5_000L) {
            while (shadowNotificationManager.size() == 0) delay(10L)
        }

    @Test
    fun `posts exactly one VISIBILITY_SECRET notification with counts-only text`() =
        runBlocking {
            val progressFlow =
                MutableStateFlow(
                    IngestProgress(running = true, processed = 12, vectorsPending = 28),
                )
            val notifier =
                IndexingNotifier(
                    context = context,
                    progressFlow = progressFlow,
                    hasPermission = { true },
                )
            scope.launch { notifier.observeAndNotify() }

            awaitNotificationPosted()

            assertEquals(1, shadowNotificationManager.size())
            val notification =
                shadowNotificationManager.getNotification(Channels.INDEXING_TAG, Channels.INDEXING_ID)
            assertEquals(NotificationCompat.VISIBILITY_SECRET, notification.visibility)

            val extras = notification.extras
            assertEquals(
                "12 of 40 documents",
                extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            )

            // No document title/content anywhere in the extras: only the
            // counts-only EXTRA_TEXT above may carry text; the rest of the
            // fields a title/body would land in must be absent.
            assertNull(extras.getCharSequence(Notification.EXTRA_TITLE))
            assertNull(extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
            assertNull(extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
            assertNull(extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES))
        }

    @Test
    fun `permission denied posts no notification and throws nothing`() =
        runBlocking {
            val progressFlow =
                MutableStateFlow(
                    IngestProgress(running = true, processed = 12, vectorsPending = 28),
                )
            val notifier =
                IndexingNotifier(
                    context = context,
                    progressFlow = progressFlow,
                    // The same seam MainActivity wires to
                    // ContextCompat.checkSelfPermission(POST_NOTIFICATIONS) in
                    // production (see IndexingNotifier's ctor doc) — reporting
                    // denied here is the unit-level equivalent of Robolectric's
                    // shadowOf(instrumentation).denyPermissions(POST_NOTIFICATIONS).
                    hasPermission = { false },
                )
            val job = scope.launch { notifier.observeAndNotify() }

            // There is no notification to await (none should ever be
            // posted), so give the collector a fixed grace period to run
            // and settle. A crash inside it would fail this test via an
            // uncaught exception propagating out of the scope.
            delay(200L)

            assertEquals(0, shadowNotificationManager.size())
            assertTrue("expected the collector job to still be running, not crashed", job.isActive)
        }
}
