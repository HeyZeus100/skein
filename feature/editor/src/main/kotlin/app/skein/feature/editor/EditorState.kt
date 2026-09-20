package app.skein.feature.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Host state for [SkeinEditor]. Holds the raw Markdown [value] (source of
 * truth; passed unchanged to autosave in `E7.I4`) plus the wikilink open
 * callback the caller uses to route `[[Note]]` clicks through the vault.
 *
 * ## Why the caller resolves wikilinks
 *
 * `:feature:editor` is deliberately vault-free (bd `skein-03f` guardrails,
 * spec §8.5): a note titled `[[some-secret]]` typed into the editor should
 * never trigger a `VaultRepository.searchTitles(...)` fetch as a side
 * effect of a keystroke. Instead this state exposes an [onLinkOpen]
 * callback the parent — a note-tab host that already holds a repository
 * reference (`E6.I9`) — wires in. The state hands the parent a
 * [WikilinkTarget] and the parent decides whether to open, prompt, or
 * ignore.
 *
 * ## Broken-link dimming
 *
 * When [knownWikilinkTitles] is non-null it is passed to the transformer;
 * wikilinks whose title is NOT in the set render dimmed (broken-link
 * cue). `null` (the default) means "don't dim anything" — a caller that
 * doesn't yet have vault access can still use the editor; nothing renders
 * broken by mistake.
 */
public class EditorState(
    initial: TextFieldValue = TextFieldValue(""),
    public val onLinkOpen: (WikilinkTarget) -> Unit = {},
    public val knownWikilinkTitles: Set<String>? = null,
) {
    public var value: TextFieldValue by mutableStateOf(initial)
        internal set

    /**
     * Updates [value] from user input, or from a programmatic edit. Kept
     * as a member function (not just direct field mutation) so callers
     * have one hook to observe every text change — used by `E7.I4`
     * autosave to snapshot the raw Markdown on debounce.
     */
    public fun onValueChange(newValue: TextFieldValue) {
        value = newValue
    }

    /** The current raw Markdown source. Handed unchanged to autosave. */
    public val source: String get() = value.text

    /** The current caret offset (or the start of the selection). */
    public val cursor: Int get() = value.selection.start

    public companion object {
        /** For `rememberSaveable`: persists [TextFieldValue.text] and selection. */
        public val Saver: Saver<EditorState, *> =
            listSaver(
                save = { state ->
                    listOf(state.value.text, state.value.selection.start, state.value.selection.end)
                },
                restore = { saved ->
                    EditorState(
                        initial =
                            TextFieldValue(
                                text = saved[0] as String,
                                selection = TextRange(saved[1] as Int, saved[2] as Int),
                            ),
                    )
                },
            )
    }
}
