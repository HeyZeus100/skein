package app.skein.feature.editor

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.skein.core.markdown.render.MarkdownStyle
import app.skein.feature.editor.autocomplete.EditorAutocompleteHost
import app.skein.feature.editor.autocomplete.Suggestion
import app.skein.feature.editor.autocomplete.WikilinkAutocompletePopup
import app.skein.feature.editor.autocomplete.rememberWikilinkAutocompleteState
import app.skein.feature.editor.autocomplete.wikilinkAutocompleteKeyEvents
import app.skein.feature.editor.frontmatter.FrontmatterBlock
import app.skein.feature.editor.frontmatter.FrontmatterChip
import app.skein.feature.editor.frontmatter.FrontmatterChipLabel
import app.skein.feature.shell.input.SecureBasicTextField
import kotlin.math.roundToInt

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
 * ## Wikilink autocomplete popup (`E7.I5`, bd `skein-zzu`)
 *
 * Passing a non-null [wikilinkSuggest] opts a caller into the `[[` popup:
 * an [app.skein.feature.editor.autocomplete.EditorAutocompleteHost] adapts
 * this [EditorState] to the shared, vault-free
 * [app.skein.feature.editor.autocomplete.AutocompleteHost] contract (no
 * change to `EditorState`'s public surface was needed for this — its
 * existing [EditorState.source]/[EditorState.cursor]/[EditorState.onValueChange]
 * were already enough). [wikilinkSuggest] is the caller's own title lookup
 * (typically `VaultRepository.searchTitles` ranked through `TitleMatcher`);
 * this module never calls a vault directly. Leaving [wikilinkSuggest] `null`
 * (the default) reproduces the exact pre-`E7.I5` behavior byte-for-byte —
 * no popup, no extra key handling, no layout capture.
 *
 * ## Frontmatter hide/show (`E7.I3`, bd `skein-6rr`)
 *
 * When [EditorState.source] opens with a well-formed `---…---` frontmatter
 * block, a [FrontmatterChip] is placed above the field. Collapsed (the
 * default — [EditorState.frontmatterExpanded]), the chip shows a one-line
 * summary and [LivePreviewTransformer] hides the block's raw lines from the
 * field entirely; tapping the chip expands it, revealing those lines
 * inline for editing (the `id:` line is still protected — see
 * [EditorState.idEditRejected]). A document with no frontmatter block never
 * shows a chip and the field behaves exactly as before this feature landed.
 *
 * ## Wikilink tap routing (bd `skein-pnqo`)
 *
 * [LivePreviewTransformer] emits a `TAG_WIKILINK` string annotation over
 * each rendered link's *transformed* text. A `pointerInput` on the field
 * (present in both branches below, keyed off the [TextLayoutResult] that
 * `onTextLayout` captures) peeks at every touch during
 * [PointerEventPass.Initial] — strictly before `BasicTextField`'s own
 * tap-to-place-cursor gesture, which runs on the default `Main` pass —
 * maps the touch to a transformed-text offset with
 * [TextLayoutResult.getOffsetForPosition], and looks up a `TAG_WIKILINK`
 * annotation there. Only when one is found does it consume the gesture and
 * call [EditorState.onLinkOpen]; otherwise the touch is left completely
 * untouched so the field's own cursor placement/selection behaves exactly
 * as if this modifier weren't there. Because [LivePreviewTransformer] never
 * emits `TAG_WIKILINK` on the caret's own (raw-source) line, a link is only
 * ever tappable on a *rendered* line — matching Obsidian.
 *
 * ## What this composable does not do (yet)
 *
 * - Slash commands — `E7.I6` / bd `skein-6sd`.
 * - Selection-menu inline AI — `E7.I7` / bd `skein-2cd`.
 */
@Composable
public fun SkeinEditor(
    state: EditorState,
    modifier: Modifier = Modifier,
    markdownStyle: MarkdownStyle = MarkdownStyle.Default,
    testTag: String = SKEIN_EDITOR_TEST_TAG,
    wikilinkSuggest: (suspend (String) -> List<Suggestion>)? = null,
    onCreateWikilink: suspend (String) -> Unit = {},
) {
    val frontmatterExpanded = state.frontmatterExpanded.value
    val transformation =
        remember(state.value, markdownStyle, state.knownWikilinkTitles, frontmatterExpanded) {
            LivePreviewTransformation(
                cursor = state.cursor,
                style = markdownStyle,
                knownWikilinks = state.knownWikilinkTitles,
                frontmatterExpanded = frontmatterExpanded,
            )
        }
    val cursorColor = LocalContentColor.current

    val frontmatterEndLineIndex =
        remember(state.source) { FrontmatterBlock.endLineIndex(buildLines(state.source), state.source) }
    val idEditRejected = state.idEditRejected.value

    val chip: @Composable () -> Unit = {
        if (frontmatterEndLineIndex >= 0) {
            val label =
                remember(state.source, frontmatterEndLineIndex) {
                    FrontmatterChipLabel.build(state.source, buildLines(state.source), frontmatterEndLineIndex)
                }
            FrontmatterChip(
                label = label,
                expanded = frontmatterExpanded,
                onToggle = { state.toggleFrontmatter() },
                showIdProtectedHint = idEditRejected,
            )
        }
    }

    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    // bd skein-pnqo: shared by both branches below — see the class kdoc's
    // "Wikilink tap routing" section for why PointerEventPass.Initial is
    // what keeps this from stealing the field's own cursor-placement tap.
    val wikilinkTapModifier =
        Modifier.pointerInput(state) {
            awaitEachGesture {
                val down = awaitFirstDown(pass = PointerEventPass.Initial)
                val target =
                    textLayoutResult?.let { layout ->
                        val transformedOffset = layout.getOffsetForPosition(down.position)
                        layout.layoutInput.text
                            .getStringAnnotations(TAG_WIKILINK, transformedOffset, transformedOffset)
                            .firstOrNull()
                            ?.item
                    }
                if (target != null) {
                    down.consume()
                    val up = waitForUpOrCancellation(pass = PointerEventPass.Initial)
                    up?.consume()
                    if (up != null) {
                        state.onLinkOpen(WikilinkTarget(title = target))
                    }
                }
            }
        }

    if (wikilinkSuggest == null) {
        Column {
            chip()
            SecureBasicTextField(
                value = state.value,
                onValueChange = { newValue -> state.onValueChange(newValue) },
                modifier = modifier.padding(4.dp).testTag(testTag).then(wikilinkTapModifier),
                textStyle = MaterialTheme.typography.bodyLarge,
                visualTransformation = transformation,
                cursorBrush = SolidColor(cursorColor),
                onTextLayout = { layoutResult -> textLayoutResult = layoutResult },
            )
        }
        return
    }

    val host = remember(state) { EditorAutocompleteHost(state) }
    val autocompleteState = rememberWikilinkAutocompleteState(host, wikilinkSuggest, onCreateWikilink)
    LaunchedEffect(state.value) { autocompleteState.onTextChanged() }

    val transformedCursorOffset =
        remember(state.value, markdownStyle, state.knownWikilinkTitles, frontmatterExpanded) {
            transform(state.source, state.cursor, markdownStyle, state.knownWikilinkTitles, frontmatterExpanded)
                .offsetMapping
                .originalToTransformed(state.cursor)
        }
    val popupOffset =
        textLayoutResult?.let { layout ->
            if (transformedCursorOffset in 0..layout.layoutInput.text.length) {
                val rect = layout.getCursorRect(transformedCursorOffset)
                IntOffset(rect.left.roundToInt(), rect.bottom.roundToInt())
            } else {
                null
            }
        }

    Column {
        chip()
        Box {
            SecureBasicTextField(
                value = state.value,
                onValueChange = { newValue -> state.onValueChange(newValue) },
                modifier =
                    modifier
                        .padding(4.dp)
                        .testTag(testTag)
                        .wikilinkAutocompleteKeyEvents(autocompleteState)
                        .then(wikilinkTapModifier),
                textStyle = MaterialTheme.typography.bodyLarge,
                visualTransformation = transformation,
                cursorBrush = SolidColor(cursorColor),
                onTextLayout = { layoutResult -> textLayoutResult = layoutResult },
            )
            if (popupOffset != null) {
                WikilinkAutocompletePopup(state = autocompleteState, offset = popupOffset)
            }
        }
    }
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
    private val frontmatterExpanded: Boolean = false,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        transform(text.text, cursor, style, knownWikilinks, frontmatterExpanded)
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
