package app.skein.models

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.skein.notify.Channels
import app.skein.system.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Foreground execution for a user-selected local model copy; never starts from boot or redelivery. */
public class ModelImportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val imports by lazy { ModelImportCoordinator.forApplication(this) }
    private val lifetime by lazy { ModelImportServiceLifetime(imports, ::stopSelf) }
    private var foregroundReady = false

    @SuppressLint("MissingPermission") // Ordinary updates are guarded by canNotify; FGS notification is mandatory.
    override fun onCreate() {
        super.onCreate()
        Channels.createChannels(this)
        try {
            // POST_NOTIFICATIONS denial does not forbid an FGS. Android requires this notification
            // regardless, and suppresses the drawer entry itself when permission is denied.
            startForeground(NOTIFICATION_ID, notification(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            foregroundReady = true
        } catch (_: RuntimeException) {
            stopSelf()
            return
        }
        scope.launch {
            imports.state.collect { state ->
                if (state is ModelImportState.Running && canNotify()) {
                    NotificationManagerCompat
                        .from(
                            this@ModelImportService,
                        ).notify(NOTIFICATION_ID, notification(state.fraction))
                }
            }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val token = intent?.getLongExtra(EXTRA_REQUEST_TOKEN, 0L) ?: 0L
        if (foregroundReady) {
            lifetime.started(token, startId) { finished -> scope.launch { finished() } }
        } else {
            imports.executionStopped(token)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        lifetime.stopped()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        lifetime.stopped()
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun notification(fraction: Float?) =
        Notifications
            .newBuilder(this, Channels.MODELS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Importing model")
            .setContentText("Preparing a local model")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, ((fraction ?: 0f) * 100).toInt(), fraction == null)
            .build()

    private fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    internal companion object {
        const val EXTRA_REQUEST_TOKEN = "app.skein.models.IMPORT_REQUEST_TOKEN"

        // Distinct from the tagged load-progress notification.
        const val NOTIFICATION_ID = 3
    }
}
