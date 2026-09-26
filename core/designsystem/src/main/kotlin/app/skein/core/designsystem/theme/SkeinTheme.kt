package app.skein.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/** Explicit theme selection for Settings' override (spec §8.1: "follows system, override in Settings"). */
enum class SkeinThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Root theme for the whole app (spec §8.1). Wraps [MaterialTheme] with the
 * terminal/editor [SkeinColors], all-monospace [SkeinTypography], the
 * radius-scaled [SkeinShapes] (§5.1), and the non-Material [SkeinTokens] via
 * [LocalSkeinTokens]. Also provides [LocalReducedMotion] (§8.3). Elevation
 * is not used anywhere in the shell — no gradients, no shadows.
 */
@Composable
fun SkeinTheme(
    mode: SkeinThemeMode = SkeinThemeMode.SYSTEM,
    tokens: SkeinTokens = SkeinTokens(),
    content: @Composable () -> Unit,
) {
    val useDarkTheme =
        when (mode) {
            SkeinThemeMode.SYSTEM -> isSystemInDarkTheme()
            SkeinThemeMode.LIGHT -> false
            SkeinThemeMode.DARK -> true
        }
    val colorScheme = if (useDarkTheme) SkeinColors.dark else SkeinColors.light
    val extendedColors = if (useDarkTheme) SkeinColors.darkExtended else SkeinColors.lightExtended

    CompositionLocalProvider(
        LocalSkeinTokens provides tokens,
        LocalSkeinColors provides extendedColors,
        LocalSkeinEditorColors provides colorScheme.editorColors(),
        // skein-xtov.23.5 (DS5, spec §8.3): the non-animation-API reduced-motion signal.
        LocalReducedMotion provides rememberSkeinReducedMotion(),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = SkeinTypography,
            // skein-xtov.23.5 (DS5, spec §5.1): SkeinRadius's scale, not one radius everywhere.
            shapes = SkeinShapes,
        ) {
            // Inside MaterialTheme, which otherwise provides primary @ 40 %.
            CompositionLocalProvider(LocalTextSelectionColors provides extendedColors.textSelectionColors, content)
        }
    }
}
