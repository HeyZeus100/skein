package app.skein.feature.editor.autocomplete

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import app.skein.feature.editor.EditorState

/**
 * Adapts [EditorState] to [AutocompleteHost] so `SkeinEditor` can host
 * [WikilinkAutocompleteState] — bd `skein-zzu`. `EditorState` itself needed
 * no change to make this possible: [EditorState.source] and
 * [EditorState.cursor] were already public, and the replacement below is
 * expressed entirely through the existing [EditorState.onValueChange].
 */
internal class EditorAutocompleteHost(
    private val state: EditorState,
) : AutocompleteHost {
    override val textBeforeCursor: String
        get() = state.source.substring(0, state.cursor)

    override fun replaceRange(
        start: Int,
        end: Int,
        with: String,
    ) {
        val text = state.source
        val newText = text.substring(0, start) + with + text.substring(end)
        val newCursor = start + with.length
        state.onValueChange(TextFieldValue(text = newText, selection = TextRange(newCursor)))
    }
}
