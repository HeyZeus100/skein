// `WikilinkExtractor` (skein-ys2, E5.I8, spec §7.1 step 5). Pure Markdown
// body -> data classes; no I/O. Mirrors the wikilink tokenization in
// `feature/editor/.../LivePreviewTransformer.kt` (bd skein-03f, E5.I3): the
// same three forms (`[[Title]]`, `[[Title|alias]]`, `[[Title#heading]]`,
// plus the combined `[[Title#heading|alias]]`), and the same rule that a
// wikilink inside a fenced code block or an inline code span is literal
// text, never a link.

package app.skein.core.vault.extract

/**
 * One `[[Target]]` occurrence in a document body.
 *
 * @param start offset of the opening `[[` in the source text.
 * @param end offset just past the closing `]]` in the source text.
 * @param target the link target title, trimmed, with any `#heading` and
 *   `|alias` suffix removed. This is what gets resolved against
 *   `VaultRepository.findByTitle` — the heading anchor is not part of edge
 *   resolution (spec §7.1 step 5: "heading ignored for the edge").
 * @param alias the `|alias` display text, or null when absent.
 * @param heading the `#heading` anchor, or null when absent.
 */
public data class Wikilink(
    val start: Int,
    val end: Int,
    val target: String,
    val alias: String?,
    val heading: String?,
)

/**
 * Extracts every wikilink from a Markdown document body. Pure function: no
 * vault/database access, no target resolution — see `EdgeUpserter` for
 * turning these into `edges` rows.
 */
public object WikilinkExtractor {
    public fun extract(bodyMd: String): List<Wikilink> {
        val out = ArrayList<Wikilink>()
        for (line in splitMarkdownLines(bodyMd)) {
            if (line.insideFence) continue
            val text = bodyMd.substring(line.start, line.end)
            var i = 0
            val n = text.length
            while (i < n) {
                val c = text[i]
                if (c == '`') {
                    // Inline code span: everything up to the matching
                    // backtick on this line is literal, including any
                    // `[[...]]` inside it.
                    val close = findClose(text, i + 1, "`")
                    i = if (close >= 0) close + 1 else n
                    continue
                }
                if (c == '[' && i + 1 < n && text[i + 1] == '[') {
                    val close = findClose(text, i + 2, "]]")
                    if (close in (i + 2)..(n - 2)) {
                        parseWikilink(inner = text.substring(i + 2, close))?.let { (target, heading, alias) ->
                            out +=
                                Wikilink(
                                    start = line.start + i,
                                    end = line.start + close + 2,
                                    target = target,
                                    alias = alias,
                                    heading = heading,
                                )
                        }
                        i = close + 2
                        continue
                    }
                }
                i++
            }
        }
        return out
    }

    /** Parses `Target`, `Target|alias`, `Target#heading`, or `Target#heading|alias`. Null when the target is blank. */
    private fun parseWikilink(inner: String): Triple<String, String?, String?>? {
        val pipe = inner.indexOf('|')
        val beforePipe = if (pipe >= 0) inner.substring(0, pipe) else inner
        val alias = if (pipe >= 0) inner.substring(pipe + 1).trim().ifEmpty { null } else null

        val hash = beforePipe.indexOf('#')
        val target = (if (hash >= 0) beforePipe.substring(0, hash) else beforePipe).trim()
        val heading = if (hash >= 0) beforePipe.substring(hash + 1).trim().ifEmpty { null } else null

        if (target.isEmpty()) return null
        return Triple(target, heading, alias)
    }
}
