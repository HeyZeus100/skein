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
    /**
     * *Minimum* height of the persistent top command bar (spec §8.2, applied
     * via `Modifier.heightIn(min = ...)` in
     * [app.skein.feature.shell.nav.CommandBar], not `Modifier.height(...)`).
     * Not fixed by the spec; `56.dp` matches Material 3
     * [androidx.compose.material3.TextField]'s own minimum height with no
     * label (16 dp top/bottom content padding around a `bodyMedium` line) —
     * larger than [tabHeight] (36 dp) since this is the primary
     * search/command entry point and hosts a tappable hamburger +
     * model-status affordance, but still meant to read as a compact
     * single-line strip rather than a full toolbar.
     *
     * `skein-wr7m` (hardware-verified on the Pixel 9 Pro Fold): the earlier
     * `44.dp`, applied as an *exact* height, was smaller than
     * `SecureTextField`'s natural minimum, so its decoration box was coerced
     * below the space its placeholder line needed and "search or /command"
     * was vertically clipped — only the top half of its letters rendered.
     * Raising the token alone isn't sufficient on its own: a fixed *exact*
     * height reintroduces the same class of bug the moment any input
     * (locale, font-scale, or — as this bug's own regression test found under
     * Robolectric — the bundled monospace font's measured line metrics)
     * needs more than this number. `heightIn(min = ...)` keeps this value as
     * the everyday resting height while never coercing content shorter than
     * it actually needs, without touching `SecureTextField`'s secure-input
     * guarantees (`skein-yb3m`) — `commandBarHeight` has exactly one call
     * site.
     */
    val commandBarHeight: Dp = 56.dp,
    /** Corner radius applied via [androidx.compose.material3.Shapes] (spec `E6.I1`: 4 dp corners). */
    val cornerRadius: Dp = 4.dp,
    /** Restrained status/action glyph set used across the command bar, tabs, and context panel. */
    val glyphs: Glyphs = Glyphs(),
) {
    /** Accent glyphs (spec §8.1–§8.4): `≡ $ ◐ ▤ ✦ ◈ ⚹ ● ⏸ ◌ ! ◂ ⧉`. */
    data class Glyphs(
        val modelActive: String = "●", // ●  model loaded / responding
        val modelPaused: String = "⏸", // ⏸  model idle / paused
        val pending: String = "◌", // ◌  in-flight / loading
        val alert: String = "!", //     needs attention
        val collapse: String = "◂", // ◂  collapse timeline to rail
        val split: String = "⧉", // ⧉  split view
        val graph: String = "✦", // ✦  local graph
        val context: String = "⚹", // ⚹  context panel toggle (also reused for the Settings nav entry)
        val hamburger: String = "≡", // ≡  open the nav drawer (spec §8.2)
        val searchPrompt: String = "$", // $  command bar prompt style (spec §8.4)
        val timeline: String = "◐", // ◐  Timeline nav destination
        val notes: String = "▤", // ▤  Notes nav destination
        val personas: String = "◈", // ◈  Personas nav destination
    )
}

val LocalSkeinTokens = staticCompositionLocalOf { SkeinTokens() }
