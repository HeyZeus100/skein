package app.skein.core.designsystem.theme

import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private const val MOVED = "Colour tokens v2 (skein-xtov.23.2): read the role from SkeinColors.dark/light"

/**
 * Skein's colour (docs/ux/DESIGN_SYSTEM.md §6): every one of Material 3's 48
 * `ColorScheme` roles set explicitly in both themes, so nothing falls back to
 * Material's baseline purple, plus the Skein-only roles in [SkeinExtendedColors].
 * Dynamic colour is off by design (§12). Zero elevation, no gradients, no
 * shadows (enforced by `NoShadowOrGradientTest`).
 */
object SkeinColors {
    val dark: ColorScheme = colorScheme(SkeinColorHex.Dark)
    val light: ColorScheme = colorScheme(SkeinColorHex.Light)

    val darkExtended: SkeinExtendedColors =
        extendedColors(
            SkeinColorHex.Dark,
            dark,
            codeBlockContainer = dark.surfaceContainerLowest,
            codeInlineContainer = dark.surfaceContainerHighest,
        )
    val lightExtended: SkeinExtendedColors =
        extendedColors(
            SkeinColorHex.Light,
            light,
            codeBlockContainer = light.surfaceContainer,
            codeInlineContainer = light.surfaceContainerHigh,
        )

    // The v1 names, kept one wave so callers still compile (§13.2).

    @Deprecated(MOVED, ReplaceWith("SkeinColors.dark.background"))
    val DarkBackground get() = dark.background

    @Deprecated(MOVED, ReplaceWith("SkeinColors.dark.surface"))
    val DarkSurface get() = dark.surface

    @Deprecated(MOVED, ReplaceWith("SkeinColors.dark.surfaceContainerHighest"))
    val DarkSurfaceVariant get() = dark.surfaceVariant

    @Deprecated(MOVED, ReplaceWith("SkeinColors.dark.onBackground"))
    val DarkOnBackground get() = dark.onBackground

    @Deprecated(MOVED, ReplaceWith("SkeinColors.dark.onSurface"))
    val DarkOnSurface get() = dark.onSurface

    @Deprecated(MOVED, ReplaceWith("SkeinColors.dark.onSurfaceVariant"))
    val DarkOnSurfaceVariant get() = dark.onSurfaceVariant

    @Deprecated(MOVED, ReplaceWith("SkeinColors.dark.primary"))
    val DarkPrimary get() = dark.primary

    @Deprecated(MOVED, ReplaceWith("SkeinColors.dark.onPrimary"))
    val DarkOnPrimary get() = dark.onPrimary

    @Deprecated(MOVED, ReplaceWith("SkeinColors.dark.tertiary"))
    val DarkTertiary get() = dark.tertiary

    @Deprecated(MOVED, ReplaceWith("SkeinColors.dark.onTertiary"))
    val DarkOnTertiary get() = dark.onTertiary

    @Deprecated(MOVED, ReplaceWith("SkeinColors.dark.outline"))
    val DarkOutline get() = dark.outline

    @Deprecated(MOVED, ReplaceWith("SkeinColors.dark.error"))
    val DarkError get() = dark.error

    @Deprecated(MOVED, ReplaceWith("SkeinColors.dark.onError"))
    val DarkOnError get() = dark.onError

    @Deprecated(MOVED, ReplaceWith("SkeinColors.light.background"))
    val LightBackground get() = light.background

    @Deprecated(MOVED, ReplaceWith("SkeinColors.light.surface"))
    val LightSurface get() = light.surface

    @Deprecated(MOVED, ReplaceWith("SkeinColors.light.surfaceContainerHighest"))
    val LightSurfaceVariant get() = light.surfaceVariant

    @Deprecated(MOVED, ReplaceWith("SkeinColors.light.onBackground"))
    val LightOnBackground get() = light.onBackground

    @Deprecated(MOVED, ReplaceWith("SkeinColors.light.onSurface"))
    val LightOnSurface get() = light.onSurface

    @Deprecated(MOVED, ReplaceWith("SkeinColors.light.onSurfaceVariant"))
    val LightOnSurfaceVariant get() = light.onSurfaceVariant

    @Deprecated(MOVED, ReplaceWith("SkeinColors.light.primary"))
    val LightPrimary get() = light.primary

    @Deprecated(MOVED, ReplaceWith("SkeinColors.light.onPrimary"))
    val LightOnPrimary get() = light.onPrimary

    @Deprecated(MOVED, ReplaceWith("SkeinColors.light.tertiary"))
    val LightTertiary get() = light.tertiary

    @Deprecated(MOVED, ReplaceWith("SkeinColors.light.onTertiary"))
    val LightOnTertiary get() = light.onTertiary

    @Deprecated(MOVED, ReplaceWith("SkeinColors.light.outline"))
    val LightOutline get() = light.outline

    @Deprecated(MOVED, ReplaceWith("SkeinColors.light.error"))
    val LightError get() = light.error

    @Deprecated(MOVED, ReplaceWith("SkeinColors.light.onError"))
    val LightOnError get() = light.onError

    // The editor's own pair folds into the page (§6.4): it sits on `surface`.

    @Deprecated(MOVED, ReplaceWith("SkeinColors.dark.surface"))
    val DarkEditorSurface get() = dark.surface

    @Deprecated(MOVED, ReplaceWith("SkeinColors.dark.onSurface"))
    val DarkOnEditorSurface get() = dark.onSurface

    @Deprecated(MOVED, ReplaceWith("SkeinColors.dark.onSurfaceVariant"))
    val DarkOnEditorSurfaceMuted get() = dark.onSurfaceVariant

    @Deprecated(MOVED, ReplaceWith("SkeinColors.light.surface"))
    val LightEditorSurface get() = light.surface

    @Deprecated(MOVED, ReplaceWith("SkeinColors.light.onSurface"))
    val LightOnEditorSurface get() = light.onSurface

    @Deprecated(MOVED, ReplaceWith("SkeinColors.light.onSurfaceVariant"))
    val LightOnEditorSurfaceMuted get() = light.onSurfaceVariant
}

/**
 * §6.4's mapping, written once for both themes. The constructor (not
 * `darkColorScheme()`) has no defaults, so a role cannot be left unset.
 */
private fun colorScheme(p: SkeinPalette): ColorScheme =
    ColorScheme(
        primary = Color(p.primary),
        onPrimary = Color(p.onPrimary),
        primaryContainer = Color(p.primaryContainer),
        onPrimaryContainer = Color(p.onPrimaryContainer),
        inversePrimary = Color(p.inversePrimary),
        secondary = Color(p.secondary),
        onSecondary = Color(p.onSecondary),
        secondaryContainer = Color(p.secondaryContainer),
        onSecondaryContainer = Color(p.onSecondaryContainer),
        tertiary = Color(p.tertiary),
        onTertiary = Color(p.onTertiary),
        tertiaryContainer = Color(p.tertiaryContainer),
        onTertiaryContainer = Color(p.onTertiaryContainer),
        background = Color(p.surface),
        onBackground = Color(p.onSurface),
        surface = Color(p.surface),
        onSurface = Color(p.onSurface),
        surfaceVariant = Color(p.surfaceContainerHighest),
        onSurfaceVariant = Color(p.onSurfaceVariant),
        // = surface: neutralises Material's tonal elevation tint.
        surfaceTint = Color(p.surface),
        inverseSurface = Color(p.inverseSurface),
        inverseOnSurface = Color(p.inverseOnSurface),
        error = Color(p.error),
        onError = Color(p.onError),
        errorContainer = Color(p.errorContainer),
        onErrorContainer = Color(p.onErrorContainer),
        outline = Color(p.outline),
        outlineVariant = Color(p.outlineVariant),
        scrim = Color(p.scrim),
        surfaceBright = Color(p.surfaceBright),
        surfaceDim = Color(p.surfaceDim),
        surfaceContainer = Color(p.surfaceContainer),
        surfaceContainerHigh = Color(p.surfaceContainerHigh),
        surfaceContainerHighest = Color(p.surfaceContainerHighest),
        surfaceContainerLow = Color(p.surfaceContainerLow),
        surfaceContainerLowest = Color(p.surfaceContainerLowest),
        primaryFixed = Color(SkeinColorHex.PRIMARY_FIXED),
        primaryFixedDim = Color(SkeinColorHex.PRIMARY_FIXED_DIM),
        onPrimaryFixed = Color(SkeinColorHex.ON_PRIMARY_FIXED),
        onPrimaryFixedVariant = Color(SkeinColorHex.ON_PRIMARY_FIXED_VARIANT),
        secondaryFixed = Color(SkeinColorHex.SECONDARY_FIXED),
        secondaryFixedDim = Color(SkeinColorHex.SECONDARY_FIXED_DIM),
        onSecondaryFixed = Color(SkeinColorHex.ON_SECONDARY_FIXED),
        onSecondaryFixedVariant = Color(SkeinColorHex.ON_SECONDARY_FIXED_VARIANT),
        tertiaryFixed = Color(SkeinColorHex.TERTIARY_FIXED),
        tertiaryFixedDim = Color(SkeinColorHex.TERTIARY_FIXED_DIM),
        onTertiaryFixed = Color(SkeinColorHex.ON_TERTIARY_FIXED),
        onTertiaryFixedVariant = Color(SkeinColorHex.ON_TERTIARY_FIXED_VARIANT),
    )

/** §6.3: success and warning are new colours; everything else aliases a role. */
private fun extendedColors(
    p: SkeinPalette,
    s: ColorScheme,
    codeBlockContainer: Color,
    codeInlineContainer: Color,
) = SkeinExtendedColors(
    success = Color(p.success),
    onSuccess = Color(p.onSuccess),
    successContainer = Color(p.successContainer),
    onSuccessContainer = Color(p.onSuccessContainer),
    warning = Color(p.warning),
    onWarning = Color(p.onWarning),
    warningContainer = Color(p.warningContainer),
    onWarningContainer = Color(p.onWarningContainer),
    destructive = s.error,
    onDestructive = s.onError,
    destructiveContainer = s.errorContainer,
    info = s.primary,
    onInfo = s.onPrimary,
    infoContainer = s.primaryContainer,
    onInfoContainer = s.onPrimaryContainer,
    disabledContent = s.onSurface.copy(alpha = 0.38f),
    disabledContainer = s.onSurface.copy(alpha = 0.12f),
    selectionBackground = s.primary.copy(alpha = 0.30f),
    selectionHandle = s.primary,
    focusRing = s.primary,
    link = s.primary,
    codeBlockContainer = codeBlockContainer,
    codeBlockBorder = s.outlineVariant,
    onCodeBlock = s.onSurface,
    codeBlockLabel = s.onSurfaceVariant,
    codeInlineContainer = codeInlineContainer,
    onCodeInline = s.onSurface,
    citationContainer = s.primaryContainer,
    onCitation = s.onPrimaryContainer,
    activityText = s.onSurfaceVariant,
    activityRunning = s.primary,
    activityDone = s.onSurfaceVariant,
    activityFailed = s.error,
    reasoningContainer = s.surfaceContainerLow,
    reasoningBorder = s.outlineVariant,
    onReasoning = s.onSurfaceVariant,
    userMessageContainer = s.secondaryContainer,
    onUserMessage = s.onSecondaryContainer,
    graphNote = s.primary,
    graphChat = s.secondary,
    graphFile = s.onSurfaceVariant,
    graphAiOutput = s.tertiary,
    graphTag = s.outline,
    graphEdge = s.outline,
    graphLabelPlate = s.surface.copy(alpha = 0.90f),
)

/**
 * Skein's roles outside Material 3 (DESIGN_SYSTEM.md §6.3), read through
 * [LocalSkeinColors]. Only success and warning are new colours; the rest name
 * a role by intent (`codeBlockContainer`, not "whichever surface looks right").
 */
@Immutable
data class SkeinExtendedColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val destructive: Color,
    val onDestructive: Color,
    val destructiveContainer: Color,
    val info: Color,
    val onInfo: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,
    /** Exempt from contrast; a disabled control must say why (§7.2). */
    val disabledContent: Color,
    val disabledContainer: Color,
    val selectionBackground: Color,
    val selectionHandle: Color,
    val focusRing: Color,
    /** Always underlined. */
    val link: Color,
    val codeBlockContainer: Color,
    val codeBlockBorder: Color,
    val onCodeBlock: Color,
    val codeBlockLabel: Color,
    val codeInlineContainer: Color,
    val onCodeInline: Color,
    val citationContainer: Color,
    val onCitation: Color,
    val activityText: Color,
    val activityRunning: Color,
    val activityDone: Color,
    val activityFailed: Color,
    val reasoningContainer: Color,
    val reasoningBorder: Color,
    val onReasoning: Color,
    val userMessageContainer: Color,
    val onUserMessage: Color,
    /** Graph kinds are always paired with a distinct shape (§10.26). */
    val graphNote: Color,
    val graphChat: Color,
    val graphFile: Color,
    val graphAiOutput: Color,
    val graphTag: Color,
    val graphEdge: Color,
    val graphLabelPlate: Color,
) {
    /** What [SkeinTheme] provides as `LocalTextSelectionColors`. */
    val textSelectionColors: TextSelectionColors
        get() = TextSelectionColors(handleColor = selectionHandle, backgroundColor = selectionBackground)
}

/**
 * Provided by [SkeinTheme] for the resolved theme. The dark default only
 * matters for a `@Preview`/test composed outside [SkeinTheme].
 */
val LocalSkeinColors = staticCompositionLocalOf { SkeinColors.darkExtended }

/**
 * The editor's own pair (bd `skein-jit3`), folded into the page colours by
 * DS2 (§6.4): the editor now sits on `surface` like every other pane. Kept,
 * and provided by [SkeinTheme], until the content-colour work (§6.7, DS3)
 * gives the editor a real `Surface`.
 */
data class SkeinEditorColors(
    val surface: Color,
    val onSurface: Color,
    val onSurfaceMuted: Color,
)

internal fun ColorScheme.editorColors() = SkeinEditorColors(surface, onSurface, onSurfaceVariant)

val LocalSkeinEditorColors = staticCompositionLocalOf { SkeinColors.dark.editorColors() }
