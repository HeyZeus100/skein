// E6.I18: Notification channels (VISIBILITY_SECRET) — indexing progress and model loading.
// Built through SecureNotification (E3.I8); tapping opens the app. Counts only.

package app.skein.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi

/** Notification channel IDs and constants. */
object Channels {
    /** "Indexing" channel for document indexing progress. Low importance. */
    const val INDEXING_CHANNEL_ID = "indexing"
    const val INDEXING_TAG = "indexing_progress"
    const val INDEXING_ID = 1

    /** "Models" channel for model loading/verification progress. Low importance. */
    const val MODELS_CHANNEL_ID = "models"
    const val MODELS_TAG = "models_progress"
    const val MODELS_ID = 2

    /**
     * Create notification channels (Android 8.0+). Idempotent: safe to call
     * multiple times. No-op on older OS versions (channels don't exist, but
     * importance field on the builder is respected).
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val indexing =
            NotificationChannel(
                INDEXING_CHANNEL_ID,
                "Indexing",
                NotificationManager.IMPORTANCE_LOW,
            )
        notificationManager.createNotificationChannel(indexing)

        val models =
            NotificationChannel(
                MODELS_CHANNEL_ID,
                "Models",
                NotificationManager.IMPORTANCE_LOW,
            )
        notificationManager.createNotificationChannel(models)
    }
}
