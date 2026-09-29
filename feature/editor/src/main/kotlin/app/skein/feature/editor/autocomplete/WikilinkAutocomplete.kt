package app.skein.feature.editor.autocomplete

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import app.skein.core.designsystem.components.LocalSkeinWindowPartitions

/**
 * Public test tag on [WikilinkAutocompletePopup]'s surface; per-row tags
 * come from [wikilinkSuggestionTestTag].
 */
public const val WIKILINK_AUTOCOMPLETE_TEST_TAG: String = "app.skein.feature.editor.autocomplete.WikilinkAutocomplete"

/** Test tag for the row at [index] (0-based, in [WikilinkAutocompleteState.suggestions] order). */
public fun wikilinkSuggestionTestTag(index: Int): String = "$WIKILINK_AUTOCOMPLETE_TEST_TAG.row.$index"

/**
 * Builds a [WikilinkAutocompleteState] scoped to this composition. [suggest]
 * and [onCreate] are read through [rememberUpdatedState] so callers can pass
 * a fresh lambda every recomposition (e.g. one closing over a changing
 * `VaultRepository`/persona) without tearing down in-flight query state.
 */
@Composable
public fun rememberWikilinkAutocompleteState(
    host: AutocompleteHost,
    suggest: suspend (String) -> List<Suggestion>,
    onCreate: suspend (String) -> Unit = {},
): WikilinkAutocompleteState {
    val scope = rememberCoroutineScope()
    val currentSuggest = rememberUpdatedState(suggest)
    val currentOnCreate = rememberUpdatedState(onCreate)
    return remember(host) {
        WikilinkAutocompleteState(
            host = host,
            suggest = { query -> currentSuggest.value(query) },
            scope = scope,
            onCreate = { title -> currentOnCreate.value(title) },
        )
    }
}

/**
 * Routes hardware-keyboard navigation (arrow keys select, `Enter` confirms,
 * `Escape` dismisses) to [state] while its popup is visible — plan `E7.I5`.
 * Consumes the key event (returns `true`) only when [state] is showing and
 * the key is one it handles, so normal typing (including a hardware `Enter`
 * with no popup open) reaches the text field untouched.
 */
public fun Modifier.wikilinkAutocompleteKeyEvents(state: WikilinkAutocompleteState): Modifier =
    this.onPreviewKeyEvent { event ->
        if (!state.isVisible || event.type != KeyEventType.KeyDown) {
            false
        } else {
            when (event.key) {
                Key.DirectionDown -> {
                    state.moveDown()
                    true
                }
                Key.DirectionUp -> {
                    state.moveUp()
                    true
                }
                Key.Enter, Key.NumPadEnter -> {
                    state.confirmSelected()
                    true
                }
                Key.Escape -> {
                    state.dismiss()
                    true
                }
                else -> false
            }
        }
    }

/**
 * The `[[` popup itself: a small anchored list of [WikilinkAutocompleteState.suggestions],
 * highlighting [WikilinkAutocompleteState.selectedIndex], each row tappable
 * to confirm it. Renders nothing while [WikilinkAutocompleteState.isVisible]
 * is false or there are no rows yet.
 *
 * [offset] positions the popup relative to its anchor's top-left, in pixels
 * — callers pass the caret's on-screen rect (e.g. from
 * `TextLayoutResult.getCursorRect`) so the popup tracks the caret as the
 * host scrolls or the line wraps.
 */
@Composable
public fun WikilinkAutocompletePopup(
    state: WikilinkAutocompleteState,
    modifier: Modifier = Modifier,
    offset: IntOffset = IntOffset.Zero,
) {
    if (!state.isVisible || state.suggestions.isEmpty()) return
    val density = LocalDensity.current
    val windowPartitions = LocalSkeinWindowPartitions.current
    val partitions =
        remember(windowPartitions, density) {
            windowPartitions?.anchors.orEmpty().mapNotNull { bounds ->
                with(density) {
                    IntRect(
                        bounds.left.roundToPx(),
                        bounds.top.roundToPx(),
                        bounds.right.roundToPx(),
                        bounds.bottom.roundToPx(),
                    ).takeIf { it.width > 0 && it.height > 0 }
                }
            }
        }
    var selectedPartition by remember(partitions) { mutableStateOf<IntRect?>(null) }
    val maximumWidth = selectedPartition?.width ?: partitions.minOfOrNull { it.width }
    val maximumHeight = selectedPartition?.height ?: partitions.minOfOrNull { it.height }
    val position = remember(offset, partitions) { WikilinkPopupPosition(offset, partitions) { selectedPartition = it } }
    Popup(
        popupPositionProvider = position,
        onDismissRequest = { state.dismiss() },
    ) {
        Surface(
            modifier =
                modifier
                    .testTag(WIKILINK_AUTOCOMPLETE_TEST_TAG)
                    .widthIn(max = maximumWidth?.let { with(density) { it.toDp() } } ?: Dp.Infinity)
                    .heightIn(max = maximumHeight?.let { with(density) { it.toDp() } } ?: Dp.Infinity)
                    .width(240.dp),
            // skein-xtov.23.7 (DS7, DESIGN_SYSTEM.md §5.3): no tonal
            // elevation — was the `pending-DS7` allow-list entry in
            // `NoShadowOrGradientTest`. This popup had no explicit `color`,
            // so it relied entirely on the (now-removed) tonal tint to read
            // as distinct from the page underneath; an explicit
            // `surfaceContainer` plus the spec's 1 dp `outlineVariant`
            // floating-layer border (§5.3 "floating layers... get their
            // level's container colour plus a 1 dp outlineVariant border")
            // keeps that same separation without elevation.
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            tonalElevation = 0.dp,
        ) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                state.suggestions.forEachIndexed { index, suggestion ->
                    val bringIntoView = remember { BringIntoViewRequester() }
                    LaunchedEffect(state.selectedIndex) {
                        if (index == state.selectedIndex) {
                            withFrameNanos { }
                            bringIntoView.bringIntoView()
                        }
                    }
                    val label = if (suggestion.isCreate) "Create \"${suggestion.title}\"" else suggestion.title
                    val rowBackground =
                        if (index == state.selectedIndex) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        }
                    Text(
                        text = label,
                        modifier =
                            Modifier
                                .testTag(wikilinkSuggestionTestTag(index))
                                .bringIntoViewRequester(bringIntoView)
                                .background(rowBackground)
                                .clickable { state.confirm(suggestion) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}
