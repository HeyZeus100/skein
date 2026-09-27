// skein-xtov.23.4 (DS4) — Skein Sans / Skein Mono as the Roborazzi renderer
// (Robolectric native graphics) actually draws them: the variable `wght` axis
// is honoured, figures are tabular, and the whole type scale at font scale
// 2.0 lays out without clipping. Lives here rather than in
// `:core:designsystem` because this module already carries the Robolectric
// and Roborazzi test infrastructure.
package app.skein.feature.shell.screenshots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import app.skein.core.designsystem.theme.LocalSkeinMonoStyles
import app.skein.core.designsystem.theme.SkeinMono
import app.skein.core.designsystem.theme.SkeinSans
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.core.designsystem.theme.SkeinThemeMode
import app.skein.core.designsystem.theme.SkeinTypography
import app.skein.testing.ui.SkeinDevice
import app.skein.testing.ui.UxDeviceRule
import app.skein.testing.ui.UxSpec
import app.skein.testing.ui.captureUx
import app.skein.testing.ui.skeinComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SkeinTypeRenderingTest {
    // docs/ux/UX_TEST_PLAN.md §3.1 mapping table: font scale 2.0 "on fold-outer"
    // uses fold-outer-443, the narrowest of the two measured cover sizes
    // (skein-xtov.23.14 / UT-0 — the spike's single provisional FOLD_OUTER).
    private val spec = UxSpec(SkeinDevice.FOLD_OUTER_443, dark = false, fontScale = 2f)

    @get:Rule(order = 0)
    val deviceRule = UxDeviceRule(spec)

    @get:Rule(order = 1)
    val composeRule = skeinComposeRule()

    private val measurer by lazy {
        TextMeasurer(
            createFontFamilyResolver(ApplicationProvider.getApplicationContext()),
            Density(1f),
            LayoutDirection.Ltr,
        )
    }

    private fun width(
        text: String,
        style: TextStyle,
    ): Float = measurer.measure(text, style).getLineRight(0)

    /** The DS4 spike: 400/500/600 come from the one variable file's `wght` axis, so each renders wider than the last. */
    @Test
    fun `Skein Sans weights render distinctly`() {
        val widths =
            listOf(FontWeight.W400, FontWeight.W500, FontWeight.W600).map {
                width(SAMPLE, TextStyle(fontFamily = SkeinSans, fontWeight = it, fontSize = 100.sp))
            }
        assertTrue("400 < 500 < 600 expected, got $widths", widths[0] < widths[1] && widths[1] < widths[2])
    }

    @Test
    fun `figures are tabular in every role and in mono`() {
        val styles = SkeinTypography.run { listOf(bodyLarge, bodySmall, titleMedium, labelLarge, labelSmall) }
        for (style in styles + TextStyle(fontFamily = SkeinMono, fontSize = 14.sp)) {
            val widths = "0123456789".map { width("$it$it$it$it$it$it", style) }.distinct()
            assertEquals("digit widths differ for $style: $widths", 1, widths.size)
        }
    }

    /** §3.5 / §14.6: every role and mono style at font scale 2.0 on the Fold cover, no text node clipped. */
    @Test
    fun `type scale at font scale 2 renders without clipping`() {
        composeRule.setContent {
            SkeinTheme(mode = SkeinThemeMode.LIGHT) {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val type = MaterialTheme.typography
                        val mono = LocalSkeinMonoStyles.current
                        listOf(
                            "headlineMedium · Fold launch plan" to type.headlineMedium,
                            "headlineSmall · Set up Skein" to type.headlineSmall,
                            "titleLarge · Settings" to type.titleLarge,
                            "titleMedium · Why is the M2 ask path late?" to type.titleMedium,
                            "titleSmall · Privacy & security" to type.titleSmall,
                            "bodyLarge · Only the owner smoke test on the Fold is left before tagging 0:22." to
                                type.bodyLarge,
                            "bodyMedium · Targets M2 for the ask path · 1,024 tokens" to type.bodyMedium,
                            "bodySmall · Qwen 2.5 3B · ready" to type.bodySmall,
                            "labelLarge · Try again" to type.labelLarge,
                            "labelMedium · Skein" to type.labelMedium,
                            "labelSmall · 14:05 · 3 sources" to type.labelSmall,
                            "codeBlock · fun main() = println(\"ÄÖÜ ├── ok\")" to mono.codeBlock,
                            "monoBody · qwen2.5-3b-instruct-q4_k_m.gguf" to mono.monoBody,
                            "monoLabel · sha256 9f2c…41ab" to mono.monoLabel,
                        ).forEach { (text, style) -> Text(text, style = style) }
                    }
                }
            }
        }
        val texts = composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult))
        val layouts =
            texts.fetchSemanticsNodes().map { node ->
                val results = mutableListOf<TextLayoutResult>()
                val getLayout = node.config[SemanticsActions.GetTextLayoutResult].action
                getLayout?.invoke(results)
                results.single()
            }
        assertEquals(14, layouts.size)
        layouts.forEach { assertFalse("clipped: ${it.layoutInput.text}", it.didOverflowHeight) }
        composeRule.onRoot().captureUx(spec, "type-scale")
    }

    private companion object {
        const val SAMPLE = "Hamburgefonstiv 0123456789"
    }
}
