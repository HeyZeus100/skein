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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
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

    // bd skein-hacu (hardware-verified): Compose Foundation's own hardware
    // Up/Down handling (`BaseTextPreparedSelection.jumpByLinesOffset`) has
    // a shortcut for when the cached horizontal position overshoots the
    // target line's width — it returns `TextLayoutResult.getLineEnd` in
    // *transformed* coordinates directly, skipping `OffsetMapping
    // .transformedToOriginal` entirely. Every inactive line in this
    // editor's live preview is shorter than its raw source (hidden
    // `#`/`**`/`[[…]]` markers), so moving into a shorter inactive line
    // routinely overshoots and hits that shortcut, landing the caret at a
    // transformed offset used as if it were already raw (device symptom:
    // "arrow keys do nothing / move the wrong thing"). This modifier
    // replaces vertical hardware-key movement with one that always
    // converts through the real `OffsetMapping` and always clamps to the
    // target line's own bounds first, so the framework's shortcut is never
    // reached. See [horizontalArrowKeyModifier] below for the matching
    // Left/Right fix (bd skein-ex7d) — Home/End are still left to the
    // framework's own handling, which is not device-reported as broken.
    val verticalArrowKeyModifier =
        Modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val lineDelta =
                when (event.key) {
                    Key.DirectionDown -> 1
                    Key.DirectionUp -> -1
                    else -> return@onPreviewKeyEvent false
                }
            val layout = textLayoutResult ?: return@onPreviewKeyEvent false
            val mapping =
                transform(state.source, state.cursor, markdownStyle, state.knownWikilinkTitles, frontmatterExpanded)
                    .offsetMapping
            val transformedLen = layout.layoutInput.text.length
            val transformedOffset = mapping.originalToTransformed(state.cursor).coerceIn(0, transformedLen)
            val currentLine = layout.getLineForOffset(transformedOffset)
            val targetLine = currentLine + lineDelta
            val newOriginalOffset =
                when {
                    targetLine < 0 -> 0
                    targetLine >= layout.lineCount -> state.source.length
                    else -> {
                        val x = layout.getCursorRect(transformedOffset).left
                        val y = (layout.getLineTop(targetLine) + layout.getLineBottom(targetLine)) / 2f
                        val hit = layout.getOffsetForPosition(Offset(x, y))
                        val clampedHit =
                            hit.coerceIn(layout.getLineStart(targetLine), layout.getLineEnd(targetLine, true))
                        mapping.transformedToOriginal(clampedHit)
                    }
                }
            state.onValueChange(state.value.copy(selection = TextRange(newOriginalOffset)))
            true
        }

    // bd skein-ex7d (hardware-verified, owner report 2026-09-23): with a
    // Bluetooth keyboard paired to a Pixel 9 Pro Fold, hardware Left/Right
    // stop moving the caret after a touch tap, while Up/Down (this file's
    // `verticalArrowKeyModifier`, skein-hacu) keep working. Investigation
    // (bd note, this bead):
    //  - Ruled out: the `OffsetMapping` itself. `OffsetMappingTest` already
    //    proves a 200-line mixed-syntax fixture round-trips and stays
    //    monotone, and `SkeinEditorInteractionTest` now drives a full
    //    character-by-character Right traversal from offset 0 to the end
    //    of a 3-line heading/bold/wikilink fixture (and Left the other way)
    //    asserting the caret is *never* stuck — it passes even against the
    //    framework's own unmodified Left/Right handling under Robolectric.
    //  - Ruled out: `wikilinkAutocompleteKeyEvents` and this file's own
    //    `verticalArrowKeyModifier` — neither one's `when` branches on
    //    `Key.DirectionLeft`/`Key.DirectionRight`, so neither can be
    //    pre-empting them (bd skein-hacu's suspect (b) test above,
    //    `arrow_keys_still_move_the_caret_when_the_wikilink_autocomplete
    //    _popup_is_not_visible`, already covers this for the popup case).
    //  - Not reproducible under Robolectric: a *real* `performTouchInput`
    //    tap (not the `requestFocus()` semantics action the rest of this
    //    suite uses) followed by a hardware Right press still moves the
    //    caret in Robolectric's simulated focus/input pipeline
    //    (`right_arrow_moves_the_caret_after_a_real_touch_tap_not_just
    //    _requestFocus`) — Robolectric cannot fully model the physical
    //    device's touch-then-hardware-key focus handoff, so this suspect
    //    (d) cannot be conclusively confirmed or excluded from a JVM test.
    //
    // The one real asymmetry between the working keys and the broken ones
    // is architectural: Up/Down are handled by this editor's own
    // `onPreviewKeyEvent` (fires for any KeyDown while a *descendant* of
    // this modifier chain holds focus — the same node Up/Down already
    // proves reliable on-device), while Left/Right were left entirely to
    // `BasicTextField`'s own internal key routing, which is implemented
    // deeper in Foundation's `CoreTextField` and requires its own specific
    // internal focus target to hold focus. Whatever the exact on-device
    // failure mode is (suspect (b)'s modifier-state mapping or suspect
    // (d)'s deeper touch-focus target — Robolectric can rule out neither),
    // removing the editor's dependence on that internal routing removes
    // the asymmetry entirely: this modifier now computes Left/Right
    // through the very same `OffsetMapping` plumbing as `verticalArrowKey
    // Modifier` and always consumes the key, exactly like the vertical
    // fix. A non-collapsed selection collapses to its near edge (Right ->
    // `selection.max`, Left -> `selection.min`) rather than stepping from
    // `selection.start`, matching ordinary text-field behavior.
    val horizontalArrowKeyModifier =
        Modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val delta =
                when (event.key) {
                    Key.DirectionRight -> 1
                    Key.DirectionLeft -> -1
                    else -> return@onPreviewKeyEvent false
                }
            val selection = state.value.selection
            val newOriginalOffset =
                if (!selection.collapsed) {
                    if (delta > 0) selection.max else selection.min
                } else {
                    val mapping =
                        transform(
                            state.source,
                            state.cursor,
                            markdownStyle,
                            state.knownWikilinkTitles,
                            frontmatterExpanded,
                        ).offsetMapping
                    val transformedLen = mapping.originalToTransformed(state.source.length)
                    val currentTransformed = mapping.originalToTransformed(selection.start).coerceIn(0, transformedLen)
                    val targetTransformed = (currentTransformed + delta).coerceIn(0, transformedLen)
                    val stepped = mapping.transformedToOriginal(targetTransformed)
                    // Defensive monotonicity guard (bd skein-ex7d suspect
                    // (a)): if the transformed step somehow resolved back
                    // to the same original offset — a hidden-marker
                    // boundary reading as "stuck" — fall back to stepping
                    // by one character in original coordinates so the
                    // caret always moves rather than appearing frozen.
                    if (stepped == selection.start) {
                        (selection.start + delta).coerceIn(0, state.source.length)
                    } else {
                        stepped
                    }
                }
            state.onValueChange(state.value.copy(selection = TextRange(newOriginalOffset)))
            true
        }

    if (wikilinkSuggest == null) {
        Column {
            chip()
            SecureBasicTextField(
                value = state.value,
                onValueChange = { newValue -> state.onValueChange(newValue) },
                modifier =
                    modifier
                        .padding(4.dp)
                        .testTag(testTag)
                        .then(verticalArrowKeyModifier)
                        .then(horizontalArrowKeyModifier)
                        .then(wikilinkTapModifier),
                // bd `skein-jit3`: `bodyLarge` carries no color, and
                // `BasicTextField` (unlike `Text`) does not fall back to
                // `LocalContentColor` for an unspecified one — it silently
                // paints opaque black. `markdownStyle.bodyColor` is the
                // caller's theme-derived color (`NoteTab` resolves it from
                // the editor's own surface token); this is the caret/raw
                // line's color, so it must be explicit here, not just on the
                // styled spans `LivePreviewTransformation` applies elsewhere.
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = markdownStyle.bodyColor),
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
                        .then(verticalArrowKeyModifier)
                        .then(horizontalArrowKeyModifier)
                        .then(wikilinkTapModifier),
                // bd `skein-jit3`: see the other `SecureBasicTextField` call
                // above (the `wikilinkSuggest == null` branch) for why this
                // must be explicit rather than left unspecified.
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = markdownStyle.bodyColor),
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
