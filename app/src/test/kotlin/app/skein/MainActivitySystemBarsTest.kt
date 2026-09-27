package app.skein

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.core.app.ApplicationProvider
import app.skein.core.designsystem.theme.SkeinThemeMode
import app.skein.system.AppearancePrefs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DS3 (skein-xtov.23.3, §6.6, §14 item 4): status/navigation bar appearance
 * must follow the *resolved* Skein theme — the user's System/Light/Dark
 * Settings › Appearance choice — not just the OS's own day/night setting.
 * [edgeToEdgeStyleFor] is `MainActivity.kt`'s own mapping (extracted to a
 * top-level function so it's directly testable, DS3): SYSTEM delegates to
 * `SystemBarStyle.auto` (the OS's own night-mode detection), LIGHT/DARK are
 * unconditional (verified against the real `androidx.activity` bytecode:
 * `SystemBarStyle.light`/`.dark`'s `detectDarkMode` lambdas are `{ false }`/
 * `{ true }`, never consulting `Resources`).
 *
 * Applied here against a disposable `ComponentActivity` (not `MainActivity`)
 * with a fresh `Robolectric.buildActivity(...)` per case — deterministic and
 * fast, unlike driving the same assertion through `MainActivity`'s own
 * `onCreate`, which pulls in `AppearancePrefs`' file-backed DataStore and the
 * whole vault/Compose bring-up (`MainActivityAppearanceTest` already proves
 * that whole pipeline honours `AppearancePrefs.themeMode` end to end; this
 * test's job is only the bar-appearance mapping itself, in both directions,
 * including when the in-app choice disagrees with the OS's own night-mode
 * qualifier — the scenario the device pass actually hit, Settings showing an
 * explicit theme while the status bar icons were wrong,
 * `DEVICE_BEFORE_PASS.md` row 01).
 *
 * `ON_RESUME`/post-unlock re-application (the other half of §6.6's rule) is
 * a real device behavior — a `BiometricPrompt`/keyguard round trip resetting
 * window decor flags — that Robolectric cannot simulate; see this bead's
 * final report for the instrumented/device steps that cover it.
 *
 * Pinned to SDK 34 (bd memory `robolectric-sdk37-needs-java21`), matching
 * every other Robolectric test in this repo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivitySystemBarsTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    // `preferencesDataStore` is a JVM-wide singleton keyed by file name
    // (`AppearancePrefsTest`'s own `@Before` doc) — harmless for the
    // mapping-only tests above (they never touch `AppearancePrefs`), but
    // required for the cold-start wiring test below to start from a known
    // (default `SYSTEM`) state.
    @Before
    fun clearAppearancePrefs() {
        runBlocking { AppearancePrefs(context).clearAllForTest() }
    }

    private fun apply(mode: SkeinThemeMode): WindowInsetsControllerCompat {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val style = edgeToEdgeStyleFor(mode)
        activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        return WindowInsetsControllerCompat(activity.window, activity.window.decorView)
    }

    @Test
    @Config(sdk = [34], qualifiers = "night")
    fun `LIGHT mode gives dark status and navigation bar icons, even with the OS in night mode`() {
        val insetsController = apply(SkeinThemeMode.LIGHT)

        assertEquals(
            "LIGHT must force dark (visible-on-light) status bar icons regardless of the OS's night mode",
            true,
            insetsController.isAppearanceLightStatusBars,
        )
        assertEquals(
            "LIGHT must force dark (visible-on-light) navigation bar icons regardless of the OS's night mode",
            true,
            insetsController.isAppearanceLightNavigationBars,
        )
    }

    @Test
    @Config(sdk = [34], qualifiers = "notnight")
    fun `DARK mode gives light status and navigation bar icons, even with the OS in day mode`() {
        val insetsController = apply(SkeinThemeMode.DARK)

        assertEquals(
            "DARK must force light (visible-on-dark) status bar icons regardless of the OS's day mode",
            false,
            insetsController.isAppearanceLightStatusBars,
        )
        assertEquals(
            "DARK must force light (visible-on-dark) navigation bar icons regardless of the OS's day mode",
            false,
            insetsController.isAppearanceLightNavigationBars,
        )
    }

    @Test
    @Config(sdk = [34], qualifiers = "night")
    fun `SYSTEM mode follows the OS night mode`() {
        val insetsController = apply(SkeinThemeMode.SYSTEM)

        assertEquals(
            "SYSTEM in OS night mode must resolve to dark, i.e. light status bar icons",
            false,
            insetsController.isAppearanceLightStatusBars,
        )
    }

    @Test
    @Config(sdk = [34], qualifiers = "notnight")
    fun `SYSTEM mode follows the OS day mode`() {
        val insetsController = apply(SkeinThemeMode.SYSTEM)

        assertEquals(
            "SYSTEM in OS day mode must resolve to light, i.e. dark status bar icons",
            true,
            insetsController.isAppearanceLightStatusBars,
        )
    }

    /**
     * The one end-to-end wiring check that stays in this file (kept minimal
     * — cold start only, no qualifier flip, to hold the assertion surface
     * down to exactly one thing): `MainActivity.onCreate` actually reads
     * [AppearancePrefs.themeMode] and applies it, rather than only ever
     * seeing its own default. `MainActivityAppearanceTest` already proves
     * the live/Compose-driven half of this same pipeline (flipping the
     * setting after launch); this proves the synchronous cold-start read.
     */
    @Test
    fun `MainActivity applies an explicit LIGHT theme choice read from AppearancePrefs at cold start`() {
        runBlocking { AppearancePrefs(context).setThemeMode(SkeinThemeMode.LIGHT) }

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val insetsController = WindowInsetsControllerCompat(activity.window, activity.window.decorView)

        assertEquals(
            "expected MainActivity's cold start to read AppearancePrefs.themeMode = LIGHT and force " +
                "dark status bar icons",
            true,
            insetsController.isAppearanceLightStatusBars,
        )
    }
}
