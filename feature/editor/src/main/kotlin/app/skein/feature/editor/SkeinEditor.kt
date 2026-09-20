package app.skein.feature.editor

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.skein.core.markdown.render.MarkdownStyle
import app.skein.feature.shell.input.SecureBasicTextField

/**
 * Obsidian-style Markdown editor: the caret line shows raw source, every
 * other line shows the styled render — bd `skein-03f`, spec §8.5. Backed
 * by [SecureBasicTextField] so threat-model §9's IME hardening
 * (no-suggestions / no-personalized-learning) still applies; the raw
 * Markdown in [EditorState.value] is the source of truth that autosave
 * (`E7.I4`) and export both consume unchanged.
 *
 * Wikilink clicks reach the caller via [EditorState.onLinkOpen] — this
 * module never fetches a note or reads a vault (bd `skein-03f`
 * guardrails). Callers that want broken-link dimming supply the set of
 * known titles through [EditorState.knownWikilinkTitles].
 *
 * The [MarkdownStyle] argument threads the theme's color/typography
 * tokens through — the shell hands `SkeinColors` here, the chat bubble
 * renderer hands its own; `MarkdownStyle.Default` is the fallback used by
 * `SkeinEditorPreview`.
 *
 * ## What this composable does not do (yet)
 *
 * - Wikilink autocomplete popup — `E7.I5` / bd `skein-zzu`.
 * - Slash commands — `E7.I6` / bd `skein-6sd`.
 * - Selection-menu inline AI — `E7.I7` / bd `skein-2cd`.
 * - Frontmatter fold — `E7.I3` / bd `skein-6rr`. The frontmatter block is
 *   rendered as raw text like any other content until that issue lands.
 * - Wikilink tap routing — the transformer already emits a `TAG_WIKILINK`
 *   string annotation over each rendered link (see
 *   [LivePreviewTransformer]), but wiring a tap detector on top of a
 *   `VisualTransformation`-driven `BasicTextField` needs a
 *   [androidx.compose.foundation.text.ClickableText]-style overlay
 *   `Layout` that `E7.I5`'s autocomplete popup issue is the natural home
 *   for. [EditorState.onLinkOpen] is defined and passes through untouched
 *   so that later wiring is a pure-additive change to this file.
 */
@Composable
public fun SkeinEditor(
    state: EditorState,
    modifier: Modifier = Modifier,
    markdownStyle: MarkdownStyle = MarkdownStyle.Default,
    testTag: String = SKEIN_EDITOR_TEST_TAG,
) {
    val transformation =
        remember(state.value, markdownStyle, state.knownWikilinkTitles) {
            LivePreviewTransformation(
                cursor = state.cursor,
                style = markdownStyle,
                knownWikilinks = state.knownWikilinkTitles,
            )
        }
    val cursorColor = LocalContentColor.current
    SecureBasicTextField(
        value = state.value,
        onValueChange = { newValue -> state.onValueChange(newValue) },
        modifier = modifier.padding(4.dp).testTag(testTag),
        textStyle = MaterialTheme.typography.bodyLarge,
        visualTransformation = transformation,
        cursorBrush = SolidColor(cursorColor),
    )
}

/**
 * Public test tag so instrumented tests can locate the editor's text
 * field (matches the `ShellTestTags`/screaming-snake convention).
 */
public const val SKEIN_EDITOR_TEST_TAG: String = "app.skein.feature.editor.SkeinEditor"

/**
 * `VisualTransformation` adapter: Compose caches by identity across
 * recompositions, so the enclosing `remember` recreates this whenever
 * the source or cursor changes — the trigger for "cursor moved off a
 * line → flip that line to styled" and vice versa.
 */
internal class LivePreviewTransformation(
    private val cursor: Int,
    private val style: MarkdownStyle,
    private val knownWikilinks: Set<String>?,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText = transform(text.text, cursor, style, knownWikilinks)
}

/**
 * Read-only rendering of the "caret outside every line" styled preview,
 * used by the `@Preview` file below (Android Studio design-time panel).
 * Not part of the public editor API — chat/timeline callers use
 * `:core:markdown`'s `MarkdownRenderer` for their own AnnotatedString.
 */
@Composable
internal fun SkeinEditorReadOnlyPreview(text: String) {
    val transformed = remember(text) { transform(text, cursor = -1, style = MarkdownStyle.Default) }
    Text(text = transformed.text, style = MaterialTheme.typography.bodyLarge)
}
