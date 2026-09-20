package app.skein.feature.shell.theme

/**
 * Raw `0xAARRGGBB` values backing [SkeinColors]. Kept in a plain Kotlin
 * object (no Compose/Android imports) so both the theme and the pure-JVM
 * [WcagContrast] tests use exactly the same numbers — no risk of the test
 * checking a value that has drifted from what actually ships.
 */
internal object SkeinColorHex {
    // Dark (default) palette — spec §8.1 / plan `E6.I1`.
    const val DARK_BACKGROUND = 0xFF0B0E11L
    const val DARK_SURFACE = 0xFF12161BL
    const val DARK_SURFACE_VARIANT = 0xFF1B2029L
    const val DARK_ON_BACKGROUND = 0xFFD6DEE7L
    const val DARK_ON_SURFACE = 0xFFD6DEE7L
    const val DARK_ON_SURFACE_VARIANT = 0xFF9AA7B4L
    const val DARK_PRIMARY = 0xFF5FD3F3L
    const val DARK_ON_PRIMARY = 0xFF00232BL
    const val DARK_TERTIARY = 0xFFB48CFFL
    const val DARK_ON_TERTIARY = 0xFF241547L
    const val DARK_OUTLINE = 0xFF3A4552L
    const val DARK_ERROR = 0xFFFF8A80L
    const val DARK_ON_ERROR = 0xFF3B0A08L

    // Light (mirrored) palette.
    const val LIGHT_BACKGROUND = 0xFFF4F6F8L
    const val LIGHT_SURFACE = 0xFFFFFFFFL
    const val LIGHT_SURFACE_VARIANT = 0xFFE7EBEFL
    const val LIGHT_ON_BACKGROUND = 0xFF12161BL
    const val LIGHT_ON_SURFACE = 0xFF12161BL
    const val LIGHT_ON_SURFACE_VARIANT = 0xFF454F5BL
    const val LIGHT_PRIMARY = 0xFF0E7FA3L
    const val LIGHT_ON_PRIMARY = 0xFFFFFFFFL
    const val LIGHT_TERTIARY = 0xFF6B3FCFL
    const val LIGHT_ON_TERTIARY = 0xFFFFFFFFL
    const val LIGHT_OUTLINE = 0xFFC3CBD3L
    const val LIGHT_ERROR = 0xFFB3261EL
    const val LIGHT_ON_ERROR = 0xFFFFFFFFL
}
