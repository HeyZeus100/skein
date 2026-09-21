package app.skein.feature.editor.autocomplete

import app.skein.feature.editor.FenceKind
import app.skein.feature.editor.buildLines

/**
 * An open, not-yet-closed `[[` immediately before the caret. [start] is the
 * source offset of the first `[`; [query] is the partial title typed so far
 * (used to call a host's `suggest` callback); [alias] is whatever the user
 * typed after a `|`, preserved verbatim into the inserted link but never
 * part of the title search.
 */
internal data class WikilinkTrigger(
    val start: Int,
    val query: String,
    val alias: String?,
)

/**
 * Looks for an open `[[` in [textBeforeCursor] — the only thing an
 * [app.skein.feature.editor.autocomplete.AutocompleteHost] exposes — per
 * plan `E7.I5`. Returns `null` when:
 *  - there is no `[[` before the cursor at all;
 *  - the nearest `[[` is already closed by a `]]` before the cursor;
 *  - the caret sits inside a fenced code block (`` ``` ``) — wikilinks
 *    never parse there (bd `skein-03f`), so the popup must not either;
 *  - a space was typed immediately after the (still-empty) `[[`, or a
 *    newline appears anywhere in the partial — plan `E7.I5`: "`Esc`/space
 *    closes". A space *inside* an already-started query/alias does not
 *    close the popup: titles are routinely multi-word ("Quantum notes"),
 *    so a continued word-by-word search must keep it open.
 */
internal fun findWikilinkTrigger(textBeforeCursor: String): WikilinkTrigger? {
    if (isCursorInsideFence(textBeforeCursor)) return null

    val openIdx = textBeforeCursor.lastIndexOf("[[")
    if (openIdx < 0) return null

    val partial = textBeforeCursor.substring(openIdx + 2)
    if (partial.contains("]]")) return null
    if (partial.contains('\n')) return null
    if (partial.startsWith(' ')) return null

    val pipeIdx = partial.indexOf('|')
    val query = if (pipeIdx >= 0) partial.substring(0, pipeIdx) else partial
    val alias = if (pipeIdx >= 0) partial.substring(pipeIdx + 1) else null
    return WikilinkTrigger(start = openIdx, query = query, alias = alias)
}

/**
 * True when the last line of [textBeforeCursor] falls inside, or on the
 * marker of, a fenced code block — mirrors `LivePreviewTransformer`'s own
 * fence handling (every fence-tagged line skips inline-syntax matching
 * entirely, wikilinks included) so the popup and the renderer never
 * disagree about where `[[` is literal text.
 */
internal fun isCursorInsideFence(textBeforeCursor: String): Boolean {
    val lastLine = buildLines(textBeforeCursor).lastOrNull() ?: return false
    return lastLine.fence != FenceKind.NONE
}
