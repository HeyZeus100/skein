package app.skein.system

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * skein-ank2: the StrongBox-fallback flag `MainActivity` records from
 * `SetupResult` round-trips through the same DataStore as the FLAG_SECURE
 * toggle. Pinned to SDK 34 like the other `:app` Robolectric tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecurityPrefsTest {
    private val prefs: SecurityPrefs
        get() = SecurityPrefs(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun `strongBoxUnavailableFallback persists true`() {
        runBlocking { prefs.setStrongBoxUnavailableFallback(true) }

        assertTrue(runBlocking { prefs.strongBoxUnavailableFallback.first() })
    }

    @Test
    fun `strongBoxUnavailableFallback persists false`() {
        runBlocking { prefs.setStrongBoxUnavailableFallback(false) }

        assertFalse(runBlocking { prefs.strongBoxUnavailableFallback.first() })
    }

    @Test
    fun `the fallback flag does not disturb the FLAG_SECURE setting`() {
        runBlocking { prefs.setFlagSecureEnabled(true) }

        runBlocking { prefs.setStrongBoxUnavailableFallback(true) }

        assertTrue(runBlocking { prefs.flagSecureEnabled.first() })
    }
}
