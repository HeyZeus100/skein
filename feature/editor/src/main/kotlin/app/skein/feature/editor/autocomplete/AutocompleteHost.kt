package app.skein.feature.editor.autocomplete

/**
 * Minimal seam a text-editing surface implements so [WikilinkAutocompleteState]
 * can drive it without depending on that surface's own state type — bd
 * `skein-zzu` / plan `E7.I5` ("Works on any `EditorState`-like host via a
 * small `AutocompleteHost` interface implemented by the editor and the chat
 * bottom bar").
 *
 * [textBeforeCursor] is exactly the source text up to (and not including)
 * the caret. Trigger detection ([findWikilinkTrigger]) only ever looks
 * backward from the caret, and every offset [WikilinkAutocompleteState]
 * hands to [replaceRange] is bounded by `textBeforeCursor.length`, so an
 * implementor never needs to expose the text *after* the cursor. This keeps
 * the contract host-agnostic: `:feature:editor`'s `EditorState` and a future
 * chat bottom-bar draft holder can both implement it without either one
 * knowing about the other, and without this popup ever touching a
 * `VaultRepository` directly (bd `skein-03f` guardrail extended here).
 */
public interface AutocompleteHost {
    /** The raw source text from offset 0 up to the current caret position. */
    public val textBeforeCursor: String

    /**
     * Replaces `text[start, end)` — always within `[0, textBeforeCursor.length]`
     * — with [with], and moves the caret to the end of the inserted text.
     */
    public fun replaceRange(
        start: Int,
        end: Int,
        with: String,
    )
}

/**
 * One row offered by the `[[` popup. A plain title suggestion has
 * [isCreate] `false`; the trailing "Create '<text>'" row (plan `E7.I5`) sets
 * it `true` so [WikilinkAutocompleteState.confirm] knows to also invoke the
 * caller's `onCreate` callback after inserting the link.
 */
public data class Suggestion(
    val title: String,
    val isCreate: Boolean = false,
)
