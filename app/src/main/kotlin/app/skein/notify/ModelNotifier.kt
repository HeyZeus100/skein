// E6.I18 (skein-fsn): ModelNotifier stub for model load/verification progress.
// No producer yet (E1.I18 skein-1uw will call this once it lands).
// Built through Notifications.newBuilder(). Respects POST_NOTIFICATIONS.

package app.skein.notify

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.skein.MainActivity
import app.skein.core.model.SkeinLog
import app.skein.system.Notifications

/**
 * Posts VISIBILITY_SECRET notifications for model loading/verification progress.
 * Counts only, no model names. Tapping opens the app.
 *
 * TODO (skein-1uw): wire a producer that calls [notifyModelLoading]/[notifyModelLoaded]/[dismissModelNotification].
 */
class ModelNotifier(
    private val context: Context,
    private val hasPermission: () -> Boolean,
) {
    /**
     * Post a notification showing model loading progress (e.g., "Verifying model hash…").
     * Use counts only, never the model name or path.
     */
    @SuppressLint("MissingPermission")
    fun notifyModelLoading(message: String) {
        if (!hasPermission()) {
            SkeinLog.d("ModelNotifier", "POST_NOTIFICATIONS denied")
            return
        }

        val deepLink =
            Intent(Intent.ACTION_MAIN)
                .setClass(context, MainActivity::class.java)
                .setData(Uri.parse("app://skein/models"))
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
                .newBuilder(context, Channels.MODELS_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentText(message)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pendingIntent)
                .build()

        NotificationManagerCompat.from(context).notify(Channels.MODELS_TAG, Channels.MODELS_ID, notification)
        SkeinLog.d("ModelNotifier", "Posted: $message")
    }

    /**
     * Update or dismiss the model notification with a completion message, then auto-dismiss after a short delay.
     */
    @SuppressLint("MissingPermission")
    fun notifyModelLoaded(message: String) {
        if (!hasPermission()) return

        val notification =
            Notifications
                .newBuilder(context, Channels.MODELS_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentText(message)
                .setOngoing(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

        NotificationManagerCompat.from(context).notify(Channels.MODELS_TAG, Channels.MODELS_ID, notification)
        SkeinLog.d("ModelNotifier", "Posted (completion): $message")
    }

    /**
     * Dismiss the model notification immediately.
     */
    fun dismissModelNotification() {
        if (!hasPermission()) return
        NotificationManagerCompat.from(context).cancel(Channels.MODELS_TAG, Channels.MODELS_ID)
        SkeinLog.d("ModelNotifier", "Dismissed")
    }
}
