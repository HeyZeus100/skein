// `QueryNgrams` (skein-7tw, E5.I11, spec §7.2 recall step 3). Pure text
// helper for `GraphRecall`'s seed lookup: pulls candidate proper-noun-shaped
// phrases (entity canonical names, document titles) out of a free-text
// query.
//
// The heuristic is deliberately narrow — only runs of consecutive
// "capitalized" tokens (first character uppercase) are considered, and only
// contiguous sub-spans of length 1..3 within each run are emitted. This
// keeps `GraphRecall.recall` from firing an `IndexStore.findEntitiesByName`
// / `VaultRepository.findByTitle` lookup per arbitrary substring of the
// query — a lowercase query (or a query with no capitalized phrase at all)
// yields zero n-grams and, per the plan's acceptance criterion, must not
// touch the index at all.

package app.skein.core.rag.recall

/**
 * Extracts candidate entity/title n-grams (length 1..3 tokens) from a query
 * string. Only sequences of consecutive tokens that each start with an
 * uppercase letter are considered — see file header.
 */
public object QueryNgrams {
    /** Spec §7.2: "the query's capitalized n-grams up to 3". */
    public const val MAX_LENGTH: Int = 3

    /** Matches a run of letters/digits, allowing an internal apostrophe or hyphen (e.g. "O'Brien", "co-op"). */
    private val TOKEN_REGEX: Regex = Regex("[\\p{L}\\p{Nd}]+(?:['\\-][\\p{L}\\p{Nd}]+)*")

    /**
     * Returns the distinct candidate phrases, longest-first within each run
     * so a caller that de-dupes by first-match prefers the more specific
     * (longer) phrase. Iteration order is otherwise the order phrases first
     * appear in [query] — deterministic for a given input.
     */
    public fun extract(query: String): Set<String> {
        val tokens = TOKEN_REGEX.findAll(query).map { it.value }.toList()
        if (tokens.isEmpty()) return emptySet()

        val out = LinkedHashSet<String>()
        var i = 0
        while (i < tokens.size) {
            if (!isCapitalized(tokens[i])) {
                i++
                continue
            }
            var runEnd = i
            while (runEnd < tokens.size && isCapitalized(tokens[runEnd])) runEnd++

            for (start in i until runEnd) {
                val maxLen = minOf(MAX_LENGTH, runEnd - start)
                for (len in maxLen downTo 1) {
                    out += tokens.subList(start, start + len).joinToString(separator = " ")
                }
            }
            i = runEnd
        }
        return out
    }

    private fun isCapitalized(token: String): Boolean = token.isNotEmpty() && token[0].isUpperCase()
}
