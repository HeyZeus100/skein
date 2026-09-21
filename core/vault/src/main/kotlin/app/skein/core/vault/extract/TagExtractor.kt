// `TagExtractor` (skein-ys2, E5.I8, spec §7.1 step 5). Pure Markdown text ->
// data classes; no I/O. Extracts `#tag` from a document body plus the
// `tags:` frontmatter array (via `Frontmatter.parse`, skein-3fn / E2.I3),
// merged and deduplicated case-insensitively (lowercased, matching the
// `tag:<lowercased-name>` edge-target convention documented on
// `us.aherrera.skein.core.model.Edge`).

package app.skein.core.vault.extract

import app.skein.core.vault.codec.Frontmatter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import us.aherrera.skein.core.model.FrontmatterKeys

/**
 * Extracts the tag set for a document: `#tag` occurrences in the Markdown
 * body plus the frontmatter `tags:` array, merged and lowercased.
 *
 * A body `#tag` is word-boundary-anchored (not preceded by a letter, digit,
 * or underscore — this is what excludes a URL fragment like
 * `...page#section`, since `#` there is preceded by a word character) and
 * excluded when it falls inside a fenced code block or an inline code span,
 * or when its body looks like a hex color literal (`#fff`, `#ffffff`,
 * `#ffffffff` — 3/4/6/8 hex digits, matching CSS short/long/alpha color
 * forms).
 */
public object TagExtractor {
    private val HEX_COLOR_LENGTHS = setOf(3, 4, 6, 8)

    /**
     * [rawText] is a whole note file: an optional `---` frontmatter header
     * (split off via [Frontmatter.parse]) followed by the Markdown body.
     */
    public fun extract(rawText: String): Set<String> {
        val (frontmatter, body) = Frontmatter.parse(rawText)
        return extract(body, frontmatter)
    }

    /**
     * [bodyMd] is body-only Markdown and [frontmatter] its already-parsed
     * header — the shape a `Document` row carries (`bodyMd` + `frontmatter`
     * are separate columns), so a caller indexing from the vault need not
     * re-render a header just to split it again.
     */
    public fun extract(
        bodyMd: String,
        frontmatter: JsonObject,
    ): Set<String> {
        val out = LinkedHashSet<String>()
        out += extractFromFrontmatter(frontmatter)
        out += extractFromBody(bodyMd)
        return out
    }

    private fun extractFromFrontmatter(frontmatter: JsonObject): Set<String> {
        val tagsElement = frontmatter[FrontmatterKeys.TAGS] as? JsonArray ?: return emptySet()
        val out = LinkedHashSet<String>()
        for (element in tagsElement) {
            val raw = (element as? JsonPrimitive)?.contentOrNull ?: continue
            val normalized = raw.removePrefix("#").trim().lowercase()
            if (normalized.isNotEmpty()) out += normalized
        }
        return out
    }

    private fun extractFromBody(body: String): Set<String> {
        val out = LinkedHashSet<String>()
        for (line in splitMarkdownLines(body)) {
            if (line.insideFence) continue
            val text = body.substring(line.start, line.end)
            var i = 0
            val n = text.length
            while (i < n) {
                val c = text[i]
                if (c == '`') {
                    val close = findClose(text, i + 1, "`")
                    i = if (close >= 0) close + 1 else n
                    continue
                }
                if (c == '#') {
                    val boundaryOk = i == 0 || !isWordChar(text[i - 1])
                    var j = i + 1
                    while (j < n && isTagChar(text[j])) j++
                    val tagBody = text.substring(i + 1, j)
                    if (boundaryOk && tagBody.isNotEmpty() && !isHexColor(tagBody)) {
                        out += tagBody.lowercase()
                    }
                    i = if (j > i + 1) j else i + 1
                    continue
                }
                i++
            }
        }
        return out
    }

    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    /** Tag-body characters: alphanumerics, underscore, hyphen, and `/` for nested tags (`#parent/child`). */
    private fun isTagChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '-' || c == '/'

    private fun isHexColor(tagBody: String): Boolean =
        tagBody.length in HEX_COLOR_LENGTHS &&
            tagBody.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
}
