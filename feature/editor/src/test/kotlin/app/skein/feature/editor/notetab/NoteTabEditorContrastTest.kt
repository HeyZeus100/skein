package app.skein.feature.editor.notetab

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import app.skein.feature.editor.SKEIN_EDITOR_TEST_TAG
import app.skein.feature.shell.theme.SkeinColors
import app.skein.feature.shell.theme.SkeinTheme
import app.skein.feature.shell.theme.SkeinThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import us.aherrera.skein.core.model.DocumentKind
import us.aherrera.skein.core.model.NewDocument
import us.aherrera.skein.testing.InMemoryIndexStore
import us.aherrera.skein.testing.InMemoryVaultRepository
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * bd `skein-jit3`: establishes — empirically, by rendering the real
 * composition tree and sampling actual pixels, not by trusting what a
 * `ColorScheme` value says should happen — the pair `SkeinEditor`'s text
 * actually renders against, in the same tab-pane nesting the device uses
 * (`SkeinApp`'s outer `Box(background(colorScheme.background))` around
 * `TabHost`'s content `Box`, which has no `Surface`/background of its own —
 * see `TabHost.kt`). Before this bead's fix, that pair was opaque black
 * (`BasicTextField`'s own default for an unspecified `textStyle` color,
 * confirmed by an earlier probe of this exact composition) on
 * `DARK_BACKGROUND` — nowhere near AA. `SkeinColorContrastTest` in
 * `:feature:shell` guards the numeric pair; this test guards that `NoteTab`
 * actually wires it up in the real tree.
 *
 * `NATIVE` graphics mode is required for [captureToImage] to rasterize text
 * under Robolectric — without it every pixel comes back the same flat color
 * and this test would pass vacuously.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoteTabEditorContrastTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `the editor's rendered text is not opaque black on the dark pane background`() {
        setContent(mode = SkeinThemeMode.DARK)

        val (surfaceArgb, textArgb) = sampleSurfaceAndTextColors()

        assertTrue(
            "expected the editor surface to be the dedicated editor token " +
                "(0x%08X), was 0x%08X".format(SkeinColors.DarkEditorSurface.toArgb(), surfaceArgb),
            surfaceArgb == SkeinColors.DarkEditorSurface.toArgb(),
        )
        assertTrue(
            "the pre-fix bug: editor text rendered as opaque black (0xFF000000) " +
                "on a near-black pane background",
            textArgb != BLACK_ARGB,
        )
        val ratio = contrastRatio(surfaceArgb, textArgb)
        assertTrue("editor surface/text contrast was $ratio, need >= 7.0 (dark)", ratio >= 7.0)
    }

    @Test
    fun `the editor's rendered text meets AAA contrast on the light pane background`() {
        setContent(mode = SkeinThemeMode.LIGHT)

        val (surfaceArgb, textArgb) = sampleSurfaceAndTextColors()

        assertTrue(
            "expected the editor surface to be the dedicated editor token " +
                "(0x%08X), was 0x%08X".format(SkeinColors.LightEditorSurface.toArgb(), surfaceArgb),
            surfaceArgb == SkeinColors.LightEditorSurface.toArgb(),
        )
        val ratio = contrastRatio(surfaceArgb, textArgb)
        assertTrue("editor surface/text contrast was $ratio, need >= 7.0 (light)", ratio >= 7.0)
    }

    private fun setContent(mode: SkeinThemeMode) {
        val repo = InMemoryVaultRepository()
        val index = InMemoryIndexStore()
        val doc =
            runBlocking {
                repo.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "Contrast", bodyMd = ""))
            }

        composeRule.setContent {
            SkeinTheme(mode = mode) {
                // Mirrors `SkeinApp`'s outer `Box(background(colorScheme.background))`
                // around `TabHost`'s content `Box` (`TabHost.kt`), which has no
                // `Surface`/background of its own — this is the real pane
                // background `NoteTab` renders against on the device.
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    NoteTab(docId = doc.id, vaultRepository = repo, indexStore = index)
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SKEIN_EDITOR_TEST_TAG).performTextInput(SAMPLE_TEXT)
        composeRule.waitForIdle()
    }

    /**
     * Returns (dominant color, most-different-from-dominant color) sampled
     * from the editor field's rendered bitmap — the dominant color is the
     * field's surface/background, the outlier is a glyph stroke's ink color.
     */
    private fun sampleSurfaceAndTextColors(): Pair<Int, Int> {
        val bitmap = composeRule.onNodeWithTag(SKEIN_EDITOR_TEST_TAG).captureToImage()
        val pixelMap = bitmap.toPixelMap()
        val counts = HashMap<Int, Int>()
        for (x in 0 until bitmap.width step 2) {
            for (y in 0 until bitmap.height step 2) {
                val argb = pixelMap[x, y].toArgb()
                counts[argb] = (counts[argb] ?: 0) + 1
            }
        }
        val dominant = counts.entries.maxByOrNull { it.value }?.key ?: error("no pixels sampled")
        val mostDifferent =
            counts.keys
                .filter { it != dominant }
                .maxByOrNull { contrastRatio(dominant, it) }
                ?: dominant
        return dominant to mostDifferent
    }

    private fun contrastRatio(
        a: Int,
        b: Int,
    ): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val lighter = max(la, lb)
        val darker = min(la, lb)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun relativeLuminance(argb: Int): Double {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF

        fun channel(value: Int): Double {
            val c = value / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
    }

    private companion object {
        const val SAMPLE_TEXT = "MMMMMMMMMM"
        const val BLACK_ARGB = 0xFF000000.toInt()
    }
}
