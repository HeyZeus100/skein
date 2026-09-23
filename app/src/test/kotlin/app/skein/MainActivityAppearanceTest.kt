package app.skein

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.skein.feature.shell.testing.ShellTestTags
import app.skein.feature.shell.theme.SkeinThemeMode
import app.skein.system.AppearancePrefs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * bd `skein-l9oi`: end-to-end proof that flipping Settings › Appearance
 * actually changes what [MainActivity] renders — not just that
 * [AppearancePrefs] round-trips (`AppearancePrefsTest`) or that
 * [app.skein.feature.settings.SettingsViewModel] mirrors a flow
 * (`SettingsViewModelTest`), but that `MainActivity`'s live collection of
 * [AppearancePrefs.themeMode] actually reaches [app.skein.feature.shell.SkeinApp]'s
 * `SkeinTheme` and recomposes the real shell. Same launch shape as
 * `MainActivityComposeTest` (a fresh `ActivityScenario` scripted through
 * [TestSkeinApplication]'s [app.skein.vault.ScriptedVaultKeyProvider]) — a
 * new file per this bead's guardrails, `MainActivityComposeTest.kt` itself is
 * untouched. `NATIVE` graphics mode is required for [captureToImage] to
 * rasterize anything under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestSkeinApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainActivityAppearanceTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val app: TestSkeinApplication
        get() = ApplicationProvider.getApplicationContext()

    private fun awaitTag(tag: String) {
        try {
            composeRule.waitUntil(timeoutMillis = WAIT_MILLIS) {
                composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("timed out waiting for tag \"$tag\"", e)
        }
    }

    @Test
    fun `flipping appearance from DARK to LIGHT changes the rendered shell background`() {
        app.keyProvider.initialised = false
        val prefs = AppearancePrefs(app)
        runBlocking { prefs.setThemeMode(SkeinThemeMode.DARK) }

        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag(ShellTestTags.VAULT_SETUP_ROOT)
            composeRule.onNodeWithTag(ShellTestTags.VAULT_SETUP_BEGIN_BUTTON).performClick()
            awaitTag(ShellTestTags.SKEIN_SHELL_ROOT)

            val darkLuminance = averageLuminance()
            assertTrue("expected a dark shell before flipping, luminance was $darkLuminance", darkLuminance < 0.5)

            runBlocking { prefs.setThemeMode(SkeinThemeMode.LIGHT) }
            composeRule.waitForIdle()

            val lightLuminance = averageLuminance()
            assertTrue(
                "expected the shell to visibly lighten after switching to LIGHT " +
                    "(dark=$darkLuminance, light=$lightLuminance)",
                lightLuminance > darkLuminance + LUMINANCE_DELTA,
            )
            assertTrue("expected a light shell after flipping, luminance was $lightLuminance", lightLuminance > 0.5)
        }
    }

    /** Average relative luminance sampled across [ShellTestTags.SKEIN_SHELL_ROOT]'s rendered bitmap. */
    private fun averageLuminance(): Double {
        val bitmap = composeRule.onNodeWithTag(ShellTestTags.SKEIN_SHELL_ROOT).captureToImage()
        val pixelMap = bitmap.toPixelMap()
        var total = 0.0
        var count = 0
        for (x in 0 until bitmap.width step 4) {
            for (y in 0 until bitmap.height step 4) {
                val argb = pixelMap[x, y].toArgb()
                total += relativeLuminance(argb)
                count++
            }
        }
        return total / count
    }

    private fun relativeLuminance(argb: Int): Double {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF

        fun channel(value: Int): Double {
            val c = value / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
    }

    private companion object {
        const val WAIT_MILLIS = 30_000L
        const val LUMINANCE_DELTA = 0.3
    }
}
