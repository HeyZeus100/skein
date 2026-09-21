// Shared line-splitting/fence-tracking helper for `WikilinkExtractor` and
// `TagExtractor` (skein-ys2, E5.I8). Mirrors the fenced-code-block detection
// in `feature/editor/.../LineModel.kt` (bd skein-03f / E5.I3's live-preview
// tokenizer) so extraction agrees with the editor about what counts as
// "inside a fenced code block": recognizes only the three-backtick fence
// form, no tilde-fence alt-form.
//
// `:core:vault` does not depend on `:feature:editor` (and should not, to
// avoid a UI module leaking into the RAG/indexing pipeline), so this is a
// deliberately small, independent re-implementation rather than a shared
// import — the contract ("what is inside a fence") is what must agree, not
// the code.

package app.skein.core.vault.extract

/** One physical line's `[start, end)` byte offsets into the source plus whether it falls inside a fenced code block. */
internal data class MdLine(
    val start: Int,
    val end: Int,
    val insideFence: Boolean,
)

/** Splits [source] into [MdLine]s, marking fence membership. Empty source yields no lines. */
internal fun splitMarkdownLines(source: String): List<MdLine> {
    if (source.isEmpty()) return emptyList()
    val out = ArrayList<MdLine>()
    var i = 0
    var openFence = false
    while (i <= source.length) {
        val nl = source.indexOf('\n', i).let { if (it < 0) source.length else it }
        val lineText = source.substring(i, nl)
        val isFenceMarker = lineText.trimStart().startsWith("```")
        val insideFence = isFenceMarker || openFence
        out += MdLine(start = i, end = nl, insideFence = insideFence)
        if (isFenceMarker) openFence = !openFence
        if (nl == source.length) break
        i = nl + 1
    }
    return out
}

/** Index of the first occurrence of [marker] at or after [from] within [text], or -1 when absent. */
internal fun findClose(
    text: String,
    from: Int,
    marker: String,
): Int {
    var i = from
    val stop = text.length - marker.length + 1
    while (i < stop) {
        if (text.regionMatches(i, marker, 0, marker.length)) return i
        i++
    }
    return -1
}
