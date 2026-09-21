// E2.I12 (bd skein-jq8): the hand-rolled OOXML writer (`DocxWriter`) builds
// every part's XML by string concatenation rather than `javax.xml.stream`/a
// DOM builder (per the task brief: "string-built XML, no third-party lib").
// This is the one place that turns arbitrary user content (titles, note
// bodies, link URLs) into safe XML text/attribute content — every other
// file in this package only ever interpolates fixed, hand-typed XML
// literals (style names, namespace URIs, `w:pStyle` values) that never need
// escaping.

package app.skein.core.vault.export.docx

/**
 * Minimal XML 1.0 text/attribute-value escaping. `escapeText` is for
 * character data (`w:t` content, `dc:title`); `escapeAttribute` is for
 * quoted attribute values (a hyperlink's `Target=".."`) and additionally
 * escapes the quote character used to delimit the attribute plus
 * whitespace that XML attribute-value normalization would otherwise
 * mangle.
 */
internal object XmlEscaper {
    fun escapeText(value: String): String {
        val sb = StringBuilder(value.length)
        for (ch in value) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '\r' -> sb.append("&#13;")
                else -> if (!isDisallowedXmlChar(ch)) sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun escapeAttribute(value: String): String {
        val sb = StringBuilder(value.length)
        for (ch in value) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                '\r' -> sb.append("&#13;")
                '\n' -> sb.append("&#10;")
                '\t' -> sb.append("&#9;")
                else -> if (!isDisallowedXmlChar(ch)) sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * XML 1.0 forbids most C0 control characters in content, even as
     * numeric character references (`&#1;` is not well-formed XML) — they
     * are stripped defensively rather than ever emitted. Tab/LF are always
     * allowed literally; CR is handled by the caller (escaped to `&#13;`)
     * before this check runs. Emoji and other astral-plane characters are
     * UTF-16 surrogate pairs — ordinary `Char`s from this loop's point of
     * view — and pass through untouched; `String.toByteArray(Charsets.UTF_8)`
     * at the call site re-encodes the pair as one 4-byte UTF-8 sequence.
     */
    private fun isDisallowedXmlChar(ch: Char): Boolean {
        val code = ch.code
        return code < 0x20 && ch != '\t' && ch != '\n'
    }
}
