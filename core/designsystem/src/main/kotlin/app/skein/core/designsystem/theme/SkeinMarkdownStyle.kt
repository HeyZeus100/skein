package app.skein.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontWeight
import app.skein.core.markdown.render.MarkdownStyle

/**
 * The themed [MarkdownStyle] chat and note Markdown render with
 * (`docs/ux/DESIGN_SYSTEM.md` §10.16 Markdown rendering, §10.17 code blocks,
 * §10.18 citations; skein-xtov.23.10, DS10). Call inside [SkeinTheme] —
 * `MessageList`/`AssistantBubble` (`:feature:chat`) and `NoteTab`/
 * `SkeinEditor` (`:feature:editor`) both do.
 *
 * ## Why this lives here, not in `:core:markdown`
 *
 * `:core:markdown` (which owns [MarkdownStyle]) must stay pure Kotlin/JVM —
 * no Android Gradle plugin, enforced by `checkIsolationGuards`
 * (`build-logic/guards`, E7.I2) — so it stays unit-testable on the JVM and
 * shared by `:app`, the editor, chat rendering, and the PDF/DOCX exporters
 * without pulling the Android SDK onto their test classpaths. Reading
 * [MaterialTheme]/[LocalSkeinColors]/[LocalSkeinMonoStyles] needs Compose's
 * Android artifacts and this module's own theme types, so a function that
 * does both cannot live in `:core:markdown` without either breaking that
 * guard or duplicating the theme there. `:core:designsystem` carries no such
 * restriction and already depends on Compose, so it takes the dependency the
 * other way: `api(project(":core:markdown"))` (`core/designsystem/build.gradle.kts`)
 * for [MarkdownStyle] itself. Since `:core:markdown` declares zero project
 * dependencies of its own, that direction cannot cycle.
 */
@Composable
fun rememberSkeinMarkdownStyle(): MarkdownStyle {
    val colorScheme = MaterialTheme.colorScheme
    val extended = LocalSkeinColors.current
    val typography = MaterialTheme.typography
    val mono = LocalSkeinMonoStyles.current
    return remember(colorScheme, extended, typography, mono) {
        skeinMarkdownStyle(colorScheme, extended, typography, mono)
    }
}

/**
 * The token → [MarkdownStyle] mapping behind [rememberSkeinMarkdownStyle],
 * pulled out as a plain function so it's unit-testable against
 * [SkeinColors.dark]/[SkeinColors.light] without a composition.
 */
internal fun skeinMarkdownStyle(
    colorScheme: ColorScheme,
    extended: SkeinExtendedColors,
    typography: Typography,
    mono: SkeinMonoStyles,
): MarkdownStyle =
    MarkdownStyle(
        bodyColor = colorScheme.onSurface,
        mutedColor = colorScheme.onSurfaceVariant,
        // §10.16: inline `code` on `codeInlineContainer`.
        codeColor = extended.onCodeInline,
        codeBackground = extended.codeInlineContainer,
        // §10.17: the code block's own recessed well, distinct from inline.
        codeBlockColor = extended.onCodeBlock,
        codeBlockBackground = extended.codeBlockContainer,
        // §10.16: "Links | primary, underlined... Wikilinks... same style."
        linkColor = extended.link,
        wikilinkColor = extended.link,
        // §10.16: block quotes are `onSurfaceVariant`, not italic (quoteStyle
        // itself no longer applies italics — see MarkdownStyle.kt).
        quoteColor = colorScheme.onSurfaceVariant,
        // §10.18: the citation marker's number-on-chip pair.
        citationColor = extended.onCitation,
        citationBackground = extended.citationContainer,
        // IA decision D4 (skein-xtov.23.4): code stays in Skein Mono while
        // the surrounding prose is Skein Sans.
        codeFontFamily = SkeinMono,
        // §3.3: inline code is 0.9 em of the surrounding style; the code
        // block is its own absolute 13 sp role. Both come from
        // `SkeinMonoTypography`, not a literal repeated here.
        codeInlineFontSize = mono.codeInline.fontSize,
        codeBlockFontSize = mono.codeBlock.fontSize,
        // §3.2: heading levels scale off `bodyLarge` (16 sp), the type
        // scale's own value, not a literal baked into `:core:markdown`.
        headingBaseSize = typography.bodyLarge.fontSize,
        // §3.2: "Bold inside prose (`**strong**`) is weight 600, not 700
        // (Skein Sans stops at 600; 700 would be synthesised and looks
        // smeared)" — the same rule applies to headings.
        headingWeight = FontWeight.W600,
    )
