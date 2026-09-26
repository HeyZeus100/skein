package app.skein.core.designsystem.theme

/**
 * One theme's raw `0xAARRGGBB` palette (docs/ux/DESIGN_SYSTEM.md §6.2).
 * Names match `TOKENS` in `docs/ux/tools/contrast.py` one for one;
 * `SkeinColorContrastTest` fails if either side drifts. [SkeinColors] maps
 * these onto every Material 3 role and onto [SkeinExtendedColors].
 */
internal class SkeinPalette(
    val surfaceContainerLowest: Long,
    val surface: Long,
    val surfaceContainerLow: Long,
    val surfaceContainer: Long,
    val surfaceContainerHigh: Long,
    val surfaceContainerHighest: Long,
    val surfaceBright: Long,
    val surfaceDim: Long,
    val outlineVariant: Long,
    val outline: Long,
    val onSurfaceVariant: Long,
    val onSurface: Long,
    val inverseSurface: Long,
    val inverseOnSurface: Long,
    val inversePrimary: Long,
    val primary: Long,
    val onPrimary: Long,
    val primaryContainer: Long,
    val onPrimaryContainer: Long,
    val secondary: Long,
    val onSecondary: Long,
    val secondaryContainer: Long,
    val onSecondaryContainer: Long,
    val tertiary: Long,
    val onTertiary: Long,
    val tertiaryContainer: Long,
    val onTertiaryContainer: Long,
    val error: Long,
    val onError: Long,
    val errorContainer: Long,
    val onErrorContainer: Long,
    val success: Long,
    val onSuccess: Long,
    val successContainer: Long,
    val onSuccessContainer: Long,
    val warning: Long,
    val onWarning: Long,
    val warningContainer: Long,
    val onWarningContainer: Long,
    /** `#000000` at the theme's scrim alpha (§6.2: 48 % dark, 32 % light). */
    val scrim: Long,
)

/**
 * The colour tokens v2 (skein-xtov.23.2, DESIGN_SYSTEM.md §6): graphite
 * neutrals, a restrained cyan accent (owner decision, UX_MIGRATION_PLAN §2.0),
 * a cool-neutral secondary and violet reserved for "made by AI". Values are
 * byte-for-byte `contrast.py`'s; change both together.
 */
internal object SkeinColorHex {
    val Dark =
        SkeinPalette(
            surfaceContainerLowest = 0xFF070A0C,
            surface = 0xFF0D1012,
            surfaceContainerLow = 0xFF131719,
            surfaceContainer = 0xFF1A1D20,
            surfaceContainerHigh = 0xFF212527,
            surfaceContainerHighest = 0xFF2A2D30,
            surfaceBright = 0xFF323639,
            surfaceDim = 0xFF0D1012,
            outlineVariant = 0xFF363A3D,
            outline = 0xFF7B8186,
            onSurfaceVariant = 0xFFB0B7BC,
            onSurface = 0xFFE4E8EB,
            inverseSurface = 0xFFDDE1E5,
            inverseOnSurface = 0xFF171B1F,
            inversePrimary = 0xFF0D6880,
            primary = 0xFF7ACCE0,
            onPrimary = 0xFF012630,
            primaryContainer = 0xFF113B47,
            onPrimaryContainer = 0xFFC4E9F2,
            secondary = 0xFFACBAC3,
            onSecondary = 0xFF172026,
            secondaryContainer = 0xFF2A343B,
            onSecondaryContainer = 0xFFDFE8ED,
            tertiary = 0xFFBEB0EC,
            onTertiary = 0xFF261D42,
            tertiaryContainer = 0xFF37304C,
            onTertiaryContainer = 0xFFE2DDF7,
            error = 0xFFF29891,
            onError = 0xFF420F0D,
            errorContainer = 0xFF542523,
            onErrorContainer = 0xFFFBD8D4,
            success = 0xFF85CE9E,
            onSuccess = 0xFF0C2B17,
            successContainer = 0xFF1C3A27,
            onSuccessContainer = 0xFFCDEAD6,
            warning = 0xFFE9C67D,
            onWarning = 0xFF2E2206,
            warningContainer = 0xFF403419,
            onWarningContainer = 0xFFF1E3C7,
            scrim = 0x7A000000,
        )

    val Light =
        SkeinPalette(
            surfaceContainerLowest = 0xFFFFFFFF,
            surface = 0xFFF8FAFC,
            surfaceContainerLow = 0xFFF1F4F6,
            surfaceContainer = 0xFFEBEEF0,
            surfaceContainerHigh = 0xFFE4E8EB,
            surfaceContainerHighest = 0xFFDDE1E5,
            surfaceBright = 0xFFF8FAFC,
            surfaceDim = 0xFFD7DBDF,
            outlineVariant = 0xFFCED3D7,
            outline = 0xFF72787D,
            onSurfaceVariant = 0xFF4D5459,
            onSurface = 0xFF171B1F,
            inverseSurface = 0xFF2A2D30,
            inverseOnSurface = 0xFFEEF1F3,
            inversePrimary = 0xFF7ACCE0,
            primary = 0xFF0D6880,
            onPrimary = 0xFFFFFFFF,
            primaryContainer = 0xFFD1ECF3,
            onPrimaryContainer = 0xFF003444,
            secondary = 0xFF505D65,
            onSecondary = 0xFFFFFFFF,
            secondaryContainer = 0xFFD7E1E8,
            onSecondaryContainer = 0xFF1B2328,
            tertiary = 0xFF61489B,
            onTertiary = 0xFFFFFFFF,
            tertiaryContainer = 0xFFE9E4FE,
            onTertiaryContainer = 0xFF38255F,
            error = 0xFFB72D29,
            onError = 0xFFFFFFFF,
            errorContainer = 0xFFFFE1DE,
            onErrorContainer = 0xFF6B1E1B,
            success = 0xFF246E3A,
            onSuccess = 0xFFFFFFFF,
            successContainer = 0xFFD8F2DC,
            onSuccessContainer = 0xFF163F21,
            warning = 0xFF875814,
            onWarning = 0xFFFFFFFF,
            warningContainer = 0xFFFBE9C6,
            onWarningContainer = 0xFF54360B,
            scrim = 0x52000000,
        )

    // Material's "fixed" roles are the same in both themes (§6.4).
    const val PRIMARY_FIXED = 0xFFD1ECF3
    const val PRIMARY_FIXED_DIM = 0xFF7ACCE0
    const val ON_PRIMARY_FIXED = 0xFF003444
    const val ON_PRIMARY_FIXED_VARIANT = 0xFF113B47
    const val SECONDARY_FIXED = 0xFFD7E1E8
    const val SECONDARY_FIXED_DIM = 0xFFACBAC3
    const val ON_SECONDARY_FIXED = 0xFF1B2328
    const val ON_SECONDARY_FIXED_VARIANT = 0xFF2A343B
    const val TERTIARY_FIXED = 0xFFE9E4FE
    const val TERTIARY_FIXED_DIM = 0xFFBEB0EC
    const val ON_TERTIARY_FIXED = 0xFF38255F
    const val ON_TERTIARY_FIXED_VARIANT = 0xFF37304C
}
