package app.skein.feature.shell.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.skein.feature.shell.R

/**
 * IBM Plex Mono (SIL OFL 1.1), bundled as `res/font` — no downloadable fonts,
 * no network. Four static styles are enough for the whole type scale
 * (spec §8.1: "monospace primary type"). License text ships at
 * `assets/fonts/OFL.txt` (`res/font` only accepts `.ttf`/`.ttc`/`.otf`/`.xml`).
 */
val PlexMonoFontFamily =
    FontFamily(
        Font(R.font.ibm_plex_mono_regular, FontWeight.Normal, FontStyle.Normal),
        Font(R.font.ibm_plex_mono_italic, FontWeight.Normal, FontStyle.Italic),
        Font(R.font.ibm_plex_mono_bold, FontWeight.Bold, FontStyle.Normal),
        Font(R.font.ibm_plex_mono_bolditalic, FontWeight.Bold, FontStyle.Italic),
    )

/**
 * Material 3 [Typography] built entirely on [PlexMonoFontFamily] — display,
 * headline, title, body, and label scales all use the same monospace family
 * per spec §8.1; only size/weight/tracking vary.
 */
val SkeinTypography: Typography =
    run {
        fun style(
            size: Int,
            lineHeight: Int,
            weight: FontWeight = FontWeight.Normal,
            tracking: Double = 0.0,
        ) = TextStyle(
            fontFamily = PlexMonoFontFamily,
            fontWeight = weight,
            fontSize = size.sp,
            lineHeight = lineHeight.sp,
            letterSpacing = tracking.sp,
        )

        Typography(
            displayLarge = style(size = 34, lineHeight = 42, weight = FontWeight.Bold),
            displayMedium = style(size = 30, lineHeight = 38, weight = FontWeight.Bold),
            displaySmall = style(size = 26, lineHeight = 34, weight = FontWeight.Bold),
            headlineLarge = style(size = 24, lineHeight = 30, weight = FontWeight.Bold),
            headlineMedium = style(size = 22, lineHeight = 28, weight = FontWeight.Bold),
            headlineSmall = style(size = 20, lineHeight = 26, weight = FontWeight.Bold),
            titleLarge = style(size = 18, lineHeight = 24, weight = FontWeight.Bold),
            titleMedium = style(size = 16, lineHeight = 22, weight = FontWeight.Bold, tracking = 0.1),
            titleSmall = style(size = 14, lineHeight = 20, weight = FontWeight.Bold, tracking = 0.1),
            bodyLarge = style(size = 15, lineHeight = 22, tracking = 0.1),
            bodyMedium = style(size = 14, lineHeight = 20, tracking = 0.1),
            bodySmall = style(size = 12, lineHeight = 16, tracking = 0.1),
            labelLarge = style(size = 13, lineHeight = 18, weight = FontWeight.Bold, tracking = 0.2),
            labelMedium = style(size = 12, lineHeight = 16, weight = FontWeight.Bold, tracking = 0.2),
            labelSmall = style(size = 11, lineHeight = 14, weight = FontWeight.Bold, tracking = 0.2),
        )
    }
