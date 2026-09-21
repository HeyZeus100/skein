package app.skein.system

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
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

    /**
     * `SecurityPrefs`'s backing DataStore is a JVM-wide singleton (a
     * top-level `preferencesDataStore` delegate, keyed by this file's
     * static init, not by the [Context] instance) — it outlives any one
     * Robolectric "fresh Application per test" reset and bleeds state
     * across test methods (observed: a "persists false" test polluting a
     * later "defaults to true" assertion). Every "defaults to ..."
     * assertion below needs a genuinely empty DataStore, so clear it before
     * each test via [SecurityPrefs.clearAllForTest] rather than relying on
     * ambient never-been-set state.
     */
    @Before
    fun clearSecurityPrefs() {
        runBlocking { prefs.clearAllForTest() }
    }

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

    // ---- E3.I14 (skein-up0) — lock policy settings ------------------------

    @Test
    fun `idleTimeoutMinutes defaults to 5`() {
        assertEquals(
            SecurityPrefs.DEFAULT_IDLE_TIMEOUT_MINUTES,
            runBlocking { prefs.idleTimeoutMinutes.first() },
        )
    }

    @Test
    fun `idleTimeoutMinutes persists an allowed value`() {
        runBlocking { prefs.setIdleTimeoutMinutes(30) }

        assertEquals(30, runBlocking { prefs.idleTimeoutMinutes.first() })
    }

    @Test
    fun `idleTimeoutMinutes clamps above the plan ceiling`() {
        runBlocking { prefs.setIdleTimeoutMinutes(999) }

        assertEquals(
            SecurityPrefs.MAX_IDLE_TIMEOUT_MINUTES,
            runBlocking { prefs.idleTimeoutMinutes.first() },
        )
    }

    @Test
    fun `idleTimeoutMinutes clamps below the plan floor`() {
        runBlocking { prefs.setIdleTimeoutMinutes(0) }

        assertEquals(
            SecurityPrefs.MIN_IDLE_TIMEOUT_MINUTES,
            runBlocking { prefs.idleTimeoutMinutes.first() },
        )
    }

    @Test
    fun `lockOnScreenOff defaults to true`() {
        assertTrue(runBlocking { prefs.lockOnScreenOff.first() })
    }

    @Test
    fun `lockOnScreenOff persists false`() {
        runBlocking { prefs.setLockOnScreenOff(false) }

        assertFalse(runBlocking { prefs.lockOnScreenOff.first() })
    }

    @Test
    fun `lockOnBackground defaults to false`() {
        assertFalse(runBlocking { prefs.lockOnBackground.first() })
    }

    @Test
    fun `lockOnBackground persists true`() {
        runBlocking { prefs.setLockOnBackground(true) }

        assertTrue(runBlocking { prefs.lockOnBackground.first() })
    }
}
