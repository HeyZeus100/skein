package app.skein.system

import android.content.Context
import androidx.core.app.NotificationCompat

/**
 * E3.I8 (spec §9): every notification this app posts must go through this
 * helper. `VISIBILITY_SECRET` keeps the notification off a locked, non-secure
 * lock screen entirely (not just redacted) — the correct default for an app
 * whose whole purpose is vault/chat content. Callers are still responsible
 * for never putting document/chat text into the builder's content fields
 * (title, text, big text, etc.) — this only sets the visibility posture,
 * counts-only progress notifications (`E6.I18`) are the expected use.
 */
object Notifications {
    fun newBuilder(
        context: Context,
        channelId: String,
    ): NotificationCompat.Builder =
        NotificationCompat
            .Builder(context, channelId)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
}
