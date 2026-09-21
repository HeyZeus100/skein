// `E3.I10` (`skein-xhi`), part 2 of the guard: the citation allow-list.
//
// Design spec §7.3 says "Model may cite `[N]`; citations render as clickable
// in the UI, opening the source as a preview tab" — so a `[N]` is the one
// piece of model output that becomes a tappable affordance. That makes it an
// injection target: a chunk can contain "as shown in [7]" and the model will
// happily repeat it. `AssembledPrompt.citations` (`E0.I12`) is the authority
// on which markers exist, and this file is where output is checked against
// it, exactly as `Retrieval.kt`'s KDoc promises: "a `[N]` the model emits
// outside that set is rendered as inert text, never as a chip".

package us.aherrera.skein.security.prompt

/**
 * Parses `[N]` / `[N, M]` citation markers out of model output and keeps
 * only those the assembled prompt actually offered.
 *
 * Split out of [PromptGuard] per plan `E3.I10`'s file list; call it through
 * [PromptGuard.citationsAllowed].
 */
public object CitationFilter {
    /**
     * Returns [text] unchanged, paired with the subset of its `[N]` markers
     * that appear in [allowed], in order of first appearance.
     *
     * The text is never rewritten: the model's prose renders as written, and
     * a marker missing from the returned set simply never becomes a chip —
     * it stays the literal characters `[`, `7`, `]`. Nothing here throws; a
     * malformed or absurd marker yields nothing.
     */
    public fun citationsAllowed(
        text: String,
        allowed: Set<Int>,
    ): Pair<String, Set<Int>> {
        if (text.isEmpty() || allowed.isEmpty()) return text to emptySet()
        val cited = LinkedHashSet<Int>(INITIAL_CAPACITY)
        var index = 0
        while (index < text.length) {
            if (text[index] != '[') {
                index += 1
                continue
            }
            val close = text.indexOf(']', index + 1)
            if (close < 0) break
            var start = index + 1
            while (start < close) {
                val comma = text.indexOf(',', start).let { if (it < 0 || it > close) close else it }
                val marker = markerOrNull(text, start, comma)
                if (marker != null && marker in allowed) cited.add(marker)
                start = comma + 1
            }
            index = close + 1
        }
        return text to cited
    }

    /** `text[from, to)` as a citation marker, or null when it is not a plain small integer. */
    private fun markerOrNull(
        text: String,
        from: Int,
        to: Int,
    ): Int? {
        var begin = from
        var end = to
        while (begin < end && text[begin] == ' ') begin += 1
        while (end > begin && text[end - 1] == ' ') end -= 1
        if (begin >= end || end - begin > MAX_MARKER_DIGITS) return null
        var value = 0
        for (index in begin..<end) {
            val digit = text[index]
            if (digit < '0' || digit > '9') return null
            value = value * DECIMAL_BASE + (digit - '0')
        }
        return value
    }

    /** Wide enough for any real marker; anything longer is an injected decoy, not a citation. */
    private const val MAX_MARKER_DIGITS: Int = 9
    private const val DECIMAL_BASE: Int = 10
    private const val INITIAL_CAPACITY: Int = 4
}
