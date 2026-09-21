// bd `skein-6rr` (plan `E7.I3`): the editor's raw buffer is
// `Frontmatter.render(document.frontmatter, document.bodyMd)` — the
// `---\n…\n---\n` header followed by the body, exactly what `:core:vault`'s
// `Frontmatter.parse` (skein-3fn) would split back apart on save (see
// `notetab/NoteTabState.kt`). `LivePreviewTransformer` needs to know where
// that header ends so it can hide it behind the collapsed chip, but it is
// deliberately a shallow, line-scoped, per-keystroke renderer that must
// not pull in `:core:vault` just to answer "is a frontmatter block here" —
// so this is a second, narrower recognizer kept in lockstep with
// `Frontmatter.parse`'s own "no header" fallback (first line must be
// exactly `---`; an unterminated block is not a block at all).

package app.skein.feature.editor.frontmatter

import app.skein.feature.editor.FenceKind
import app.skein.feature.editor.Line

/**
 * Locates the leading YAML frontmatter block of a document already split
 * into [Line]s by `buildLines`.
 */
internal object FrontmatterBlock {
    private const val DELIMITER = "---"

    /**
     * 0-based index into [lines] of the line holding the closing `---`, or
     * `-1` when [source] does not open with a well-formed frontmatter
     * block — no leading `---` line, or no matching closing `---` before
     * the document ends. Mirrors `Frontmatter.parse`'s own header
     * detection so the transformer never hides text that `parse` would not
     * itself treat as frontmatter on save.
     */
    fun endLineIndex(
        lines: List<Line>,
        source: String,
    ): Int {
        if (lines.isEmpty()) return -1
        val first = lines[0]
        if (first.fence != FenceKind.NONE || first.text(source) != DELIMITER) return -1
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.fence != FenceKind.NONE) continue
            if (line.text(source) == DELIMITER) return i
        }
        return -1
    }
}
