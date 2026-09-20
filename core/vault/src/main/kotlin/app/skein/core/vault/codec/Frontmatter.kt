// `E2.I3`: hand-rolled codec for the Obsidian-compatible YAML frontmatter
// subset (spec §2.10, plan §4.5's `FrontmatterKeys`). No third-party YAML
// library — supply chain / manifest guards forbid it, and the subset is
// intentionally small: scalars, quoted strings, ISO-8601 timestamps, and
// flat lists (`tags: [a, b]` flow-style or `- a` block-style).
//
// Anything a note's frontmatter contains outside that subset (a nested
// mapping, a block scalar indicator, etc.) is captured verbatim as an
// "opaque" value — the exact original text is stored in the JSON value for
// that key — so `render(parse(x))` never silently drops data even for
// frontmatter this codec cannot fully model. The only signal distinguishing
// an opaque value from an ordinary string scalar is an embedded `\n`: every
// value this codec itself *produces* from a recognized scalar/list is
// single-line by construction (`unescapeQuoted` never turns an escape
// sequence into a literal newline), so a `\n` inside a `JsonPrimitive`
// string unambiguously means "replay verbatim, don't re-encode".

package app.skein.core.vault.codec

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import us.aherrera.skein.core.model.FrontmatterKeys

public object Frontmatter {
    private val CANONICAL_ORDER =
        listOf(
            FrontmatterKeys.ID,
            FrontmatterKeys.KIND,
            FrontmatterKeys.TITLE,
            FrontmatterKeys.CREATED,
            FrontmatterKeys.UPDATED,
            FrontmatterKeys.PERSONA,
            FrontmatterKeys.TAGS,
            FrontmatterKeys.SOURCE,
        )
    private val CANONICAL_ORDER_SET = CANONICAL_ORDER.toSet()

    private val INTEGER_RE = Regex("^-?\\d+$")
    private val DOUBLE_RE = Regex("^-?\\d+\\.\\d+$")

    // Leading characters that are ambiguous or reserved for plain YAML
    // scalars (block/flow indicators, comment/anchor/tag markers, quotes).
    private val INDICATOR_CHARS = "-?:,[]{}#&*!|>'\"%@`".toCharArray()

    /**
     * Splits [text] into its frontmatter object and body. `text` without a
     * leading `---\n ... \n---\n` header (or with an unterminated one)
     * yields an empty [JsonObject] and [text] unchanged — never throws.
     */
    public fun parse(text: String): Pair<JsonObject, String> {
        val lines = text.split("\n")
        if (lines.isEmpty() || lines[0] != "---") {
            return JsonObject(emptyMap()) to text
        }

        val closingIndex = (1 until lines.size).firstOrNull { lines[it] == "---" }
        if (closingIndex == null) {
            return JsonObject(emptyMap()) to text
        }

        val frontmatter = parseFrontmatterLines(lines.subList(1, closingIndex))
        val body = lines.subList(closingIndex + 1, lines.size).joinToString("\n")
        return frontmatter to body
    }

    /**
     * Renders [frontmatter] (canonical key order, see [CANONICAL_ORDER]) as
     * a `---` header followed by [body]. Emits no header at all when
     * [frontmatter] is empty — the result is then exactly [body].
     */
    public fun render(
        frontmatter: JsonObject,
        body: String,
    ): String {
        if (frontmatter.isEmpty()) return body

        val known = CANONICAL_ORDER.filter { it in frontmatter }
        val rest = (frontmatter.keys - CANONICAL_ORDER_SET).sorted()

        return buildString {
            append("---\n")
            for (key in known + rest) {
                append(renderEntry(key, frontmatter.getValue(key)))
                append('\n')
            }
            append("---\n")
            append(body)
        }
    }

    // -------------------------------------------------------------------
    // parse internals
    // -------------------------------------------------------------------

    private fun parseFrontmatterLines(lines: List<String>): JsonObject {
        val result = LinkedHashMap<String, JsonElement>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val colonIndex = line.indexOf(':')
            if (isIndented(line) || line.isBlank() || colonIndex < 0) {
                // Stray line with no owning top-level key — outside the
                // subset with nothing addressable to key it under; skip
                // defensively rather than throwing.
                i += 1
                continue
            }

            val key = line.substring(0, colonIndex).trim()
            val rawRest = line.substring(colonIndex + 1)
            val restTrimmed = rawRest.trim()

            val children = mutableListOf<String>()
            var next = i + 1
            while (next < lines.size && isIndented(lines[next])) {
                children.add(lines[next])
                next += 1
            }

            result[key] =
                when {
                    children.isEmpty() -> parseInlineValue(restTrimmed)
                    restTrimmed.isEmpty() && children.all { isBlockListItem(it) } ->
                        JsonArray(children.map { parseScalarToken(blockListItemContent(it)) })
                    else ->
                        // Outside the subset (e.g. a nested mapping) — keep
                        // the exact original text so render() can replay it.
                        JsonPrimitive(rawRest + children.joinToString("") { "\n$it" })
                }
            i = next
        }
        return JsonObject(result)
    }

    private fun isIndented(line: String): Boolean = line.isNotEmpty() && (line[0] == ' ' || line[0] == '\t')

    private fun isBlockListItem(line: String): Boolean = line.trim().let { it == "-" || it.startsWith("- ") }

    private fun blockListItemContent(line: String): String {
        val trimmed = line.trim()
        return if (trimmed == "-") "" else trimmed.removePrefix("- ").trim()
    }

    private fun parseInlineValue(restTrimmed: String): JsonElement =
        when {
            restTrimmed.isEmpty() -> JsonPrimitive("")
            restTrimmed.startsWith("[") && restTrimmed.endsWith("]") -> parseFlowList(restTrimmed)
            else -> parseScalarToken(restTrimmed)
        }

    private fun parseFlowList(restTrimmed: String): JsonArray {
        val inner = restTrimmed.substring(1, restTrimmed.length - 1).trim()
        if (inner.isEmpty()) return JsonArray(emptyList())
        return JsonArray(splitTopLevelCommas(inner).map { parseScalarToken(it.trim()) })
    }

    /** Splits on commas outside of `"..."` quoted spans. */
    private fun splitTopLevelCommas(s: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' && (i == 0 || s[i - 1] != '\\') -> {
                    inQuotes = !inQuotes
                    current.append(c)
                }
                c == ',' && !inQuotes -> {
                    parts.add(current.toString())
                    current.setLength(0)
                }
                else -> current.append(c)
            }
            i += 1
        }
        parts.add(current.toString())
        return parts
    }

    private fun parseScalarToken(token: String): JsonElement =
        if (token.length >= 2 && token.startsWith("\"") && token.endsWith("\"")) {
            JsonPrimitive(unescapeQuoted(token))
        } else {
            parseBareScalar(token)
        }

    private fun unescapeQuoted(quoted: String): String {
        val inner = quoted.substring(1, quoted.length - 1)
        val sb = StringBuilder(inner.length)
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            if (c == '\\' && i + 1 < inner.length && (inner[i + 1] == '"' || inner[i + 1] == '\\')) {
                sb.append(inner[i + 1])
                i += 2
            } else {
                sb.append(c)
                i += 1
            }
        }
        return sb.toString()
    }

    private fun parseBareScalar(token: String): JsonElement =
        when {
            token == "true" -> JsonPrimitive(true)
            token == "false" -> JsonPrimitive(false)
            INTEGER_RE.matches(token) -> JsonPrimitive(token.toLong())
            DOUBLE_RE.matches(token) -> JsonPrimitive(token.toDouble())
            // Includes ISO-8601 timestamps: they contain no `:` immediately
            // followed by a space, so they never trip needsQuoting() on the
            // way back out either.
            else -> JsonPrimitive(token)
        }

    // -------------------------------------------------------------------
    // render internals
    // -------------------------------------------------------------------

    private fun renderEntry(
        key: String,
        value: JsonElement,
    ): String =
        when {
            value is JsonArray -> "$key: [" + value.joinToString(", ") { renderScalar(it as JsonPrimitive) } + "]"
            value is JsonPrimitive && value.isString && value.content.contains('\n') ->
                // Opaque block captured verbatim by parse(); never produced
                // by renderScalar()/the flow-list encoder above.
                "$key:" + value.content
            value is JsonPrimitive -> "$key: " + renderScalar(value)
            else -> "$key: $value"
        }

    private fun renderScalar(value: JsonPrimitive): String {
        if (!value.isString) return value.content
        val content = value.content
        return if (needsQuoting(content)) quoteScalar(content) else content
    }

    private fun needsQuoting(s: String): Boolean {
        if (s.isEmpty() || s != s.trim()) return true
        if (s == "true" || s == "false") return true
        if (INTEGER_RE.matches(s) || DOUBLE_RE.matches(s)) return true
        if (s.contains(": ") || s.endsWith(":")) return true
        if (s[0] in INDICATOR_CHARS) return true
        return s.any { it == ',' || it == '[' || it == ']' || it == '{' || it == '}' || it == '"' || it == '\n' }
    }

    private fun quoteScalar(s: String): String {
        val escaped = s.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }
}
