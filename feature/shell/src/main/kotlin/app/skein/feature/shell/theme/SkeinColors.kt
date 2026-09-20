package app.skein.feature.shell.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Terminal/editor-inspired color tokens (spec §8.1, plan `E6.I1`).
 *
 * Dark is the default surface. Both schemes use a restrained cyan/violet
 * accent pair — cyan for interactive elements, violet reserved for
 * AI-generated content markers — and carry zero elevation (no gradients,
 * no drop shadows: enforced by [app.skein.feature.shell.lint]).
 */
object SkeinColors {
    // Dark (default) palette.
    val DarkBackground = Color(SkeinColorHex.DARK_BACKGROUND)
    val DarkSurface = Color(SkeinColorHex.DARK_SURFACE)
    val DarkSurfaceVariant = Color(SkeinColorHex.DARK_SURFACE_VARIANT)
    val DarkOnBackground = Color(SkeinColorHex.DARK_ON_BACKGROUND)
    val DarkOnSurface = Color(SkeinColorHex.DARK_ON_SURFACE)
    val DarkOnSurfaceVariant = Color(SkeinColorHex.DARK_ON_SURFACE_VARIANT)
    val DarkPrimary = Color(SkeinColorHex.DARK_PRIMARY)
    val DarkOnPrimary = Color(SkeinColorHex.DARK_ON_PRIMARY)
    val DarkTertiary = Color(SkeinColorHex.DARK_TERTIARY)
    val DarkOnTertiary = Color(SkeinColorHex.DARK_ON_TERTIARY)
    val DarkOutline = Color(SkeinColorHex.DARK_OUTLINE)
    val DarkError = Color(SkeinColorHex.DARK_ERROR)
    val DarkOnError = Color(SkeinColorHex.DARK_ON_ERROR)

    // Light (mirrored) palette.
    val LightBackground = Color(SkeinColorHex.LIGHT_BACKGROUND)
    val LightSurface = Color(SkeinColorHex.LIGHT_SURFACE)
    val LightSurfaceVariant = Color(SkeinColorHex.LIGHT_SURFACE_VARIANT)
    val LightOnBackground = Color(SkeinColorHex.LIGHT_ON_BACKGROUND)
    val LightOnSurface = Color(SkeinColorHex.LIGHT_ON_SURFACE)
    val LightOnSurfaceVariant = Color(SkeinColorHex.LIGHT_ON_SURFACE_VARIANT)
    val LightPrimary = Color(SkeinColorHex.LIGHT_PRIMARY)
    val LightOnPrimary = Color(SkeinColorHex.LIGHT_ON_PRIMARY)
    val LightTertiary = Color(SkeinColorHex.LIGHT_TERTIARY)
    val LightOnTertiary = Color(SkeinColorHex.LIGHT_ON_TERTIARY)
    val LightOutline = Color(SkeinColorHex.LIGHT_OUTLINE)
    val LightError = Color(SkeinColorHex.LIGHT_ERROR)
    val LightOnError = Color(SkeinColorHex.LIGHT_ON_ERROR)

    val dark: ColorScheme =
        darkColorScheme(
            background = DarkBackground,
            onBackground = DarkOnBackground,
            surface = DarkSurface,
            onSurface = DarkOnSurface,
            surfaceVariant = DarkSurfaceVariant,
            onSurfaceVariant = DarkOnSurfaceVariant,
            primary = DarkPrimary,
            onPrimary = DarkOnPrimary,
            primaryContainer = DarkSurfaceVariant,
            onPrimaryContainer = DarkPrimary,
            tertiary = DarkTertiary,
            onTertiary = DarkOnTertiary,
            tertiaryContainer = DarkSurfaceVariant,
            onTertiaryContainer = DarkTertiary,
            outline = DarkOutline,
            outlineVariant = DarkOutline,
            error = DarkError,
            onError = DarkOnError,
            surfaceContainer = DarkSurface,
            surfaceContainerLow = DarkBackground,
            surfaceContainerLowest = DarkBackground,
            surfaceContainerHigh = DarkSurfaceVariant,
            surfaceContainerHighest = DarkSurfaceVariant,
        )

    val light: ColorScheme =
        lightColorScheme(
            background = LightBackground,
            onBackground = LightOnBackground,
            surface = LightSurface,
            onSurface = LightOnSurface,
            surfaceVariant = LightSurfaceVariant,
            onSurfaceVariant = LightOnSurfaceVariant,
            primary = LightPrimary,
            onPrimary = LightOnPrimary,
            primaryContainer = LightSurfaceVariant,
            onPrimaryContainer = LightPrimary,
            tertiary = LightTertiary,
            onTertiary = LightOnTertiary,
            tertiaryContainer = LightSurfaceVariant,
            onTertiaryContainer = LightTertiary,
            outline = LightOutline,
            outlineVariant = LightOutline,
            error = LightError,
            onError = LightOnError,
            surfaceContainer = LightSurface,
            surfaceContainerLow = LightBackground,
            surfaceContainerLowest = LightBackground,
            surfaceContainerHigh = LightSurfaceVariant,
            surfaceContainerHighest = LightSurfaceVariant,
        )
}
