package app.skein.feature.shell.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Acceptance criterion (`E6.I1`): every Material typography slot — not just
 * `bodyLarge` — resolves to the bundled Plex Mono family. No downloadable
 * fonts, no network: [PlexMonoFontFamily] is built entirely from `res/font`
 * [androidx.compose.ui.text.font.Font] entries.
 */
class SkeinTypographyTest {
    @Test
    fun `bodyLarge uses Plex Mono`() {
        assertEquals(PlexMonoFontFamily, SkeinTypography.bodyLarge.fontFamily)
    }

    @Test
    fun `every type scale slot uses Plex Mono`() {
        val slots =
            listOf(
                SkeinTypography.displayLarge,
                SkeinTypography.displayMedium,
                SkeinTypography.displaySmall,
                SkeinTypography.headlineLarge,
                SkeinTypography.headlineMedium,
                SkeinTypography.headlineSmall,
                SkeinTypography.titleLarge,
                SkeinTypography.titleMedium,
                SkeinTypography.titleSmall,
                SkeinTypography.bodyLarge,
                SkeinTypography.bodyMedium,
                SkeinTypography.bodySmall,
                SkeinTypography.labelLarge,
                SkeinTypography.labelMedium,
                SkeinTypography.labelSmall,
            )
        slots.forEach { style ->
            assertEquals(PlexMonoFontFamily, style.fontFamily)
        }
    }

    @Test
    fun `Plex Mono family is distinct from the platform default`() {
        assertTrue(PlexMonoFontFamily != androidx.compose.ui.text.font.FontFamily.Default)
    }
}
