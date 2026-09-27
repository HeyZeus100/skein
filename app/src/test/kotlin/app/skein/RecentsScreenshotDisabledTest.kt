package app.skein

import android.app.Activity
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.skein.system.SecurityPrefs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowActivity

/**
 * skein-xtov.24.7 (AL-08), SECURITY_REVIEW_D7.md M5a: on API 33+ the Recents
 * card never holds a snapshot of the pre-lock screen, whatever the user's
 * FLAG_SECURE setting (which watcher sessions turn off). M5b: the window title
 * stays the app name. Robolectric has no getter for the flag, so a shadow
 * records the call.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [RecentsScreenshotDisabledTest.RecordingShadowActivity::class])
class RecentsScreenshotDisabledTest {
    @Implements(Activity::class)
    class RecordingShadowActivity : ShadowActivity() {
        var recentsScreenshotEnabled: Boolean? = null

        @Implementation(minSdk = 33)
        fun setRecentsScreenshotEnabled(enabled: Boolean) {
            recentsScreenshotEnabled = enabled
        }
    }

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun launch(flagSecure: Boolean): MainActivity {
        runBlocking { SecurityPrefs(context).setFlagSecureEnabled(flagSecure) }
        return Robolectric.buildActivity(MainActivity::class.java).setup().get()
    }

    @Test
    fun `Recents screenshots are off with FLAG_SECURE on`() {
        val activity = launch(flagSecure = true)

        assertEquals(false, Shadow.extract<RecordingShadowActivity>(activity).recentsScreenshotEnabled)
    }

    @Test
    fun `Recents screenshots are off with FLAG_SECURE off`() {
        val activity = launch(flagSecure = false)

        assertEquals(false, Shadow.extract<RecordingShadowActivity>(activity).recentsScreenshotEnabled)
        assertEquals(context.getString(R.string.app_name), activity.title.toString())
    }
}
