package app.skein.models

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.Service
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ModelImportServiceTest {
    @Test
    fun `notification denial still enters real foreground with secret generic notification and no restart`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val controller = Robolectric.buildService(ModelImportService::class.java).create()
        val service = controller.get()
        val notification = shadowOf(service).lastForegroundNotification
        assertThat(notification).isNotNull()
        assertThat(notification.visibility).isEqualTo(Notification.VISIBILITY_SECRET)
        assertThat(
            notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
        ).isEqualTo("Importing model")
        assertThat(
            notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
        ).isEqualTo("Preparing a local model")
        assertThat(service.onStartCommand(Intent(), 0, 1)).isEqualTo(Service.START_NOT_STICKY)
        assertThat(shadowOf(service).isStoppedBySelf).isTrue()
        controller.destroy()
    }
}
