package app.skein.feature.shell.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/** Explicit theme selection for Settings' override (spec §8.1: "follows system, override in Settings"). */
enum class SkeinThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Root theme for the whole app (spec §8.1). Wraps [MaterialTheme] with the
 * terminal/editor [SkeinColors], all-monospace [SkeinTypography], 4 dp
 * [Shapes], and the non-Material [SkeinTokens] via [LocalSkeinTokens].
 * Elevation is not used anywhere in the shell — no gradients, no shadows.
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
    val editorColors =
        if (useDarkTheme) {
            SkeinEditorColors(
                surface = SkeinColors.DarkEditorSurface,
                onSurface = SkeinColors.DarkOnEditorSurface,
                onSurfaceMuted = SkeinColors.DarkOnEditorSurfaceMuted,
            )
        } else {
            SkeinEditorColors(
                surface = SkeinColors.LightEditorSurface,
                onSurface = SkeinColors.LightOnEditorSurface,
                onSurfaceMuted = SkeinColors.LightOnEditorSurfaceMuted,
            )
        }
    val shapes =
        Shapes(
            extraSmall = RoundedCornerShape(tokens.cornerRadius),
            small = RoundedCornerShape(tokens.cornerRadius),
            medium = RoundedCornerShape(tokens.cornerRadius),
            large = RoundedCornerShape(tokens.cornerRadius),
            extraLarge = RoundedCornerShape(tokens.cornerRadius),
        )

    CompositionLocalProvider(
        LocalSkeinTokens provides tokens,
        LocalSkeinEditorColors provides editorColors,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = SkeinTypography,
            shapes = shapes,
            content = content,
        )
    }
}
