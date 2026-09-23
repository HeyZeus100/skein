package app.skein.system

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.skein.feature.shell.theme.SkeinThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * bd `skein-l9oi`: [AppearancePrefs] round-trip, mirroring
 * `SecurityPrefsTest`'s shape (same `preferencesDataStore`-is-a-JVM-wide-
 * singleton reason for the `@Before` reset).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppearancePrefsTest {
    private val prefs: AppearancePrefs
        get() = AppearancePrefs(ApplicationProvider.getApplicationContext<Context>())

    @Before
    fun clearAppearancePrefs() {
        runBlocking { prefs.clearAllForTest() }
    }

    @Test
    fun `themeMode defaults to SYSTEM`() {
        assertEquals(SkeinThemeMode.SYSTEM, runBlocking { prefs.themeMode.first() })
    }

    @Test
    fun `themeMode persists LIGHT`() {
        runBlocking { prefs.setThemeMode(SkeinThemeMode.LIGHT) }

        assertEquals(SkeinThemeMode.LIGHT, runBlocking { prefs.themeMode.first() })
    }

    @Test
    fun `themeMode persists DARK`() {
        runBlocking { prefs.setThemeMode(SkeinThemeMode.DARK) }

        assertEquals(SkeinThemeMode.DARK, runBlocking { prefs.themeMode.first() })
    }

    @Test
    fun `themeMode round-trips back to SYSTEM after an explicit override`() {
        runBlocking { prefs.setThemeMode(SkeinThemeMode.DARK) }
        runBlocking { prefs.setThemeMode(SkeinThemeMode.SYSTEM) }

        assertEquals(SkeinThemeMode.SYSTEM, runBlocking { prefs.themeMode.first() })
    }
}
