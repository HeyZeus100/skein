@file:Suppress("DEPRECATION")

package app.skein.feature.shell.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.skein.core.designsystem.theme.SkeinTheme as DesignSystemSkeinTheme
import app.skein.core.designsystem.theme.ThemeGallery as DesignSystemThemeGallery

// skein-xtov.23.1 (DS1): the theme moved to `:core:designsystem`
// (`app.skein.core.designsystem.theme`). These forwarders keep every
// `app.skein.feature.shell.theme.*` import compiling for one wave; delete this
// file once consumers import the design system directly.

@Deprecated(
    "Moved to :core:designsystem (skein-xtov.23.1)",
    ReplaceWith("SkeinColors", "app.skein.core.designsystem.theme.SkeinColors"),
)
typealias SkeinColors = app.skein.core.designsystem.theme.SkeinColors

@Deprecated(
    "Moved to :core:designsystem (skein-xtov.23.1)",
    ReplaceWith("SkeinEditorColors", "app.skein.core.designsystem.theme.SkeinEditorColors"),
)
typealias SkeinEditorColors = app.skein.core.designsystem.theme.SkeinEditorColors

@Deprecated(
    "Moved to :core:designsystem (skein-xtov.23.1)",
    ReplaceWith("LocalSkeinEditorColors", "app.skein.core.designsystem.theme.LocalSkeinEditorColors"),
)
val LocalSkeinEditorColors = app.skein.core.designsystem.theme.LocalSkeinEditorColors

@Deprecated(
    "Moved to :core:designsystem (skein-xtov.23.1)",
    ReplaceWith("SkeinThemeMode", "app.skein.core.designsystem.theme.SkeinThemeMode"),
)
typealias SkeinThemeMode = app.skein.core.designsystem.theme.SkeinThemeMode

@Deprecated(
    "Moved to :core:designsystem (skein-xtov.23.1)",
    ReplaceWith("SkeinTheme(mode, tokens, content)", "app.skein.core.designsystem.theme.SkeinTheme"),
)
@Composable
fun SkeinTheme(
    mode: SkeinThemeMode = SkeinThemeMode.SYSTEM,
    tokens: SkeinTokens = SkeinTokens(),
    content: @Composable () -> Unit,
) = DesignSystemSkeinTheme(mode, tokens, content)

@Deprecated(
    "Moved to :core:designsystem (skein-xtov.23.1)",
    ReplaceWith("SkeinTokens", "app.skein.core.designsystem.theme.SkeinTokens"),
)
typealias SkeinTokens = app.skein.core.designsystem.theme.SkeinTokens

@Deprecated(
    "Moved to :core:designsystem (skein-xtov.23.1)",
    ReplaceWith("LocalSkeinTokens", "app.skein.core.designsystem.theme.LocalSkeinTokens"),
)
val LocalSkeinTokens = app.skein.core.designsystem.theme.LocalSkeinTokens

@Deprecated(
    "Plex Mono is now Skein Mono in :core:designsystem (skein-xtov.23.4)",
    ReplaceWith("SkeinMono", "app.skein.core.designsystem.theme.SkeinMono"),
)
val PlexMonoFontFamily = app.skein.core.designsystem.theme.SkeinMono

@Deprecated(
    "Moved to :core:designsystem (skein-xtov.23.1)",
    ReplaceWith("SkeinTypography", "app.skein.core.designsystem.theme.SkeinTypography"),
)
val SkeinTypography = app.skein.core.designsystem.theme.SkeinTypography

@Deprecated(
    "Moved to :core:designsystem (skein-xtov.23.1)",
    ReplaceWith("ThemeGallery(modifier)", "app.skein.core.designsystem.theme.ThemeGallery"),
)
@Composable
fun ThemeGallery(modifier: Modifier = Modifier) = DesignSystemThemeGallery(modifier)
