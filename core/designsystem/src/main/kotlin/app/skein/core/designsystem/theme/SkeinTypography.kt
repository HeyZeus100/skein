package app.skein.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.skein.core.designsystem.R

// skein-xtov.23.4 (DS4, docs/ux/DESIGN_SYSTEM.md §3). Both faces are Latin
// subsets of IBM Plex (SIL OFL 1.1), renamed because a subset is a Modified
// Version and "Plex" is a Reserved Font Name. `tools/fonts/build-skein-fonts.sh`
// rebuilds the files from pinned upstream releases; the licence text ships at
// `assets/fonts/OFL.txt`. Bundled in `res/font` only: no downloadable fonts.

private fun skeinSans(weight: FontWeight) =
    Font(
        R.font.skein_sans,
        weight,
        FontStyle.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
    )

/**
 * Skein Sans (IBM Plex Sans): navigation, titles, messages, notes, controls
 * and numbers. One variable roman file serves 400, 500 and 600; `**strong**`
 * (700) resolves to the real 600, never a synthesised bold. Figures are
 * tabular by default.
 */
val SkeinSans: FontFamily =
    FontFamily(
        skeinSans(FontWeight.W400),
        skeinSans(FontWeight.W500),
        skeinSans(FontWeight.W600),
        Font(R.font.skein_sans_italic, FontWeight.W400, FontStyle.Italic),
    )

/**
 * Skein Mono (IBM Plex Mono), 400 only: machine text — code, file paths, ids,
 * hashes, commands, logs, model technical details, keycaps (IA decision D4).
 * Shares Skein Sans' vertical metrics, so inline code keeps the baseline.
 */
val SkeinMono: FontFamily = FontFamily(Font(R.font.skein_mono, FontWeight.W400))

/** A role at [size] sp with an `em` line height ([lineHeight] / [size]) that font scaling cannot shrink (§3.5). */
private fun TextStyle.skein(
    size: Int,
    lineHeight: Int,
    weight: FontWeight,
    tracking: Double = 0.0,
    family: FontFamily = SkeinSans,
) = copy(
    fontFamily = family,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = (lineHeight.toFloat() / size).em,
    letterSpacing = tracking.sp,
)

/** The Material 3 type scale (§3.2), every role in [SkeinSans]; tracking in sp. */
val SkeinTypography: Typography =
    Typography().run {
        Typography(
            displayLarge = displayLarge.skein(45, 52, FontWeight.W400, tracking = -0.25),
            displayMedium = displayMedium.skein(36, 44, FontWeight.W400),
            displaySmall = displaySmall.skein(32, 40, FontWeight.W500),
            headlineLarge = headlineLarge.skein(28, 36, FontWeight.W500),
            headlineMedium = headlineMedium.skein(24, 32, FontWeight.W500),
            headlineSmall = headlineSmall.skein(22, 28, FontWeight.W500),
            titleLarge = titleLarge.skein(20, 28, FontWeight.W600),
            titleMedium = titleMedium.skein(17, 24, FontWeight.W600),
            titleSmall = titleSmall.skein(14, 20, FontWeight.W600, tracking = 0.1),
            bodyLarge = bodyLarge.skein(16, 24, FontWeight.W400),
            bodyMedium = bodyMedium.skein(14, 20, FontWeight.W400, tracking = 0.1),
            bodySmall = bodySmall.skein(13, 18, FontWeight.W400, tracking = 0.1),
            labelLarge = labelLarge.skein(14, 20, FontWeight.W600, tracking = 0.1),
            labelMedium = labelMedium.skein(13, 18, FontWeight.W500, tracking = 0.2),
            labelSmall = labelSmall.skein(12, 16, FontWeight.W500, tracking = 0.3),
        )
    }

/** The monospace roles (§3.3), all [SkeinMono] 400. Read through [LocalSkeinMonoStyles]. */
@Immutable
data class SkeinMonoStyles(
    /** Code blocks in messages and notes; logs. */
    val codeBlock: TextStyle,
    /** Inline `code` in prose: 0.9 em of the surrounding style, which it otherwise inherits. */
    val codeInline: SpanStyle,
    /** Model details values, file paths in file info, slash syntax in palette descriptions. */
    val monoBody: TextStyle,
    /** Ids and hashes (middle-ellipsised), keyboard-shortcut keycaps, the code-block language label. */
    val monoLabel: TextStyle,
)

val SkeinMonoTypography: SkeinMonoStyles =
    SkeinTypography.bodyMedium.run {
        SkeinMonoStyles(
            codeBlock = skein(13, 20, FontWeight.W400, family = SkeinMono),
            codeInline = SpanStyle(fontFamily = SkeinMono, fontWeight = FontWeight.W400, fontSize = 0.9.em),
            monoBody = skein(14, 20, FontWeight.W400, family = SkeinMono),
            monoLabel = skein(12, 16, FontWeight.W400, family = SkeinMono),
        )
    }

val LocalSkeinMonoStyles = staticCompositionLocalOf { SkeinMonoTypography }
