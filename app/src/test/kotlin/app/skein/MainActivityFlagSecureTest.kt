package app.skein

import android.content.Context
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import app.skein.system.SecurityPrefs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * E3.I8 (spec §9): `MainActivity` is the app's single Activity, and the
 * whole thing is a vault surface, so it must set `FLAG_SECURE` — blocking
 * screen recording and recents thumbnails — whenever the `SecurityPrefs`
 * setting says to. Default is on; a user who needs screen recording for
 * accessibility can flip it off, but the *default* posture is secure.
 *
 * Pinned to SDK 34 (bd memory `robolectric-sdk37-needs-java21`), matching
 * `ManifestPolicyTest`/`MainActivityComposeTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityFlagSecureTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `FLAG_SECURE is set on MainActivity by default`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        val flags = activity.window.attributes.flags
        assertEquals(
            "expected FLAG_SECURE to be set by default",
            WindowManager.LayoutParams.FLAG_SECURE,
            flags and WindowManager.LayoutParams.FLAG_SECURE,
        )
    }

    @Test
    fun `FLAG_SECURE is unset when the setting is disabled`() {
        runBlocking { SecurityPrefs(context).setFlagSecureEnabled(false) }

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        val flags = activity.window.attributes.flags
        assertEquals(
            "expected FLAG_SECURE to be cleared when the pref is disabled",
            0,
            flags and WindowManager.LayoutParams.FLAG_SECURE,
        )
    }

    @Test
    fun `FLAG_SECURE stays set when the setting is explicitly enabled`() {
        runBlocking { SecurityPrefs(context).setFlagSecureEnabled(true) }

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        val flags = activity.window.attributes.flags
        assertEquals(
            WindowManager.LayoutParams.FLAG_SECURE,
            flags and WindowManager.LayoutParams.FLAG_SECURE,
        )
    }
}
