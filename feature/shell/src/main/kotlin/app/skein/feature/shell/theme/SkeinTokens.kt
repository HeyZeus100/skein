package app.skein.feature.shell.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Layout and glyph tokens that are not part of Material's [androidx.compose.material3.ColorScheme]
 * / [androidx.compose.material3.Typography] / [androidx.compose.material3.Shapes] triad but are
 * still shared across the shell (spec §8.1–§8.3, plan `E6.I1`). Consumed via [LocalSkeinTokens].
 */
data class SkeinTokens(
    /** Width of the collapsed timeline icon rail (spec §8.2: "40 px icon rail"). */
    val railWidth: Dp = 40.dp,
    /** Fraction of the dual-pane width given to the timeline when expanded (spec §8.2: 30%). */
    val timelineShare: Float = 0.30f,
    /** Height of a preview tab (spec §8.3). */
    val tabHeight: Dp = 36.dp,
    /** Corner radius applied via [androidx.compose.material3.Shapes] (spec `E6.I1`: 4 dp corners). */
    val cornerRadius: Dp = 4.dp,
    /** Restrained status/action glyph set used across the command bar, tabs, and context panel. */
    val glyphs: Glyphs = Glyphs(),
) {
    /** Accent glyphs (spec §8.1–§8.4): `● ⏸ ◌ ! ◂ ⧉ ✦ ⚹`. */
    data class Glyphs(
        val modelActive: String = "●", // ●  model loaded / responding
        val modelPaused: String = "⏸", // ⏸  model idle / paused
        val pending: String = "◌", // ◌  in-flight / loading
        val alert: String = "!", //     needs attention
        val collapse: String = "◂", // ◂  collapse timeline to rail
        val split: String = "⧉", // ⧉  split view
        val graph: String = "✦", // ✦  local graph
        val context: String = "⚹", // ⚹  context panel toggle
    )
}

val LocalSkeinTokens = staticCompositionLocalOf { SkeinTokens() }
