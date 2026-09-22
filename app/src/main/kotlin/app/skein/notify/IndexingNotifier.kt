// E6.I18: IndexingNotifier observes IngestScheduler.progress and posts
// VISIBILITY_SECRET notifications with counts-only text. Built through
// Notifications.newBuilder(). Respects POST_NOTIFICATIONS permission.

package app.skein.notify

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.skein.MainActivity
import app.skein.core.model.SkeinLog
import app.skein.ingest.IngestProgress
import app.skein.system.Notifications
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest

/**
 * Observes [IngestScheduler]'s progress and posts VISIBILITY_SECRET
 * notifications showing counts only: "N of M documents" (never titles).
 * Tapping opens the app via a deep link.
 *
 * @param progressFlow the IngestScheduler's progress StateFlow
 * @param hasPermission lambda returning true if POST_NOTIFICATIONS is granted
 */
class IndexingNotifier(
    private val context: Context,
    private val progressFlow: StateFlow<IngestProgress>,
    private val hasPermission: () -> Boolean,
) {
    /**
     * Collect progress updates and post/update/dismiss notifications.
     * This is a long-lived coroutine, safe to launch in the vault scope.
     */
    suspend fun observeAndNotify() {
        // Ensure channels exist (idempotent, safe to call always)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Channels.createChannels(context)
        }

        progressFlow
            .collectLatest { progress ->
                if (progress.running) {
                    postProgressNotification(progress)
                } else {
                    dismissProgressNotification()
                }
            }
    }

    @SuppressLint("MissingPermission")
    private fun postProgressNotification(progress: IngestProgress) {
        if (!hasPermission()) {
            SkeinLog.d("IndexingNotifier", "POST_NOTIFICATIONS denied, skipping notification")
            return
        }

        val total = progress.processed + progress.vectorsPending
        val contentText =
            if (total == 0) {
                "Indexing…"
            } else {
                "${progress.processed} of $total documents"
            }

        val deepLink =
            Intent(Intent.ACTION_MAIN)
                .setClass(context, MainActivity::class.java)
                .setData(Uri.parse("app://skein/ingest"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        val pendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                deepLink,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val notification =
            Notifications
                .newBuilder(context, Channels.INDEXING_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentText(contentText)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pendingIntent)
                .build()

        NotificationManagerCompat.from(context).notify(Channels.INDEXING_TAG, Channels.INDEXING_ID, notification)
        SkeinLog.d("IndexingNotifier", "Posted: $contentText")
    }

    private fun dismissProgressNotification() {
        if (!hasPermission()) return
        NotificationManagerCompat.from(context).cancel(Channels.INDEXING_TAG, Channels.INDEXING_ID)
        SkeinLog.d("IndexingNotifier", "Dismissed")
    }
}
