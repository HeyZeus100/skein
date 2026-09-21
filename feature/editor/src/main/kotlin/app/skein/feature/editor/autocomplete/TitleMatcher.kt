package app.skein.feature.editor.autocomplete

/**
 * Ranks candidate document titles for the `[[` popup — plan `E7.I5`: "fuzzy:
 * prefix first, then substring, <= 8 rows". Deliberately a pure function
 * over `List<String>` (no `VaultRepository`/`Document` dependency): a
 * caller's `suggest` callback fetches candidate titles however it likes
 * (e.g. `VaultRepository.searchTitles`) and hands them here to rank, which
 * keeps this object trivially JVM-testable and reusable by any host.
 */
public object TitleMatcher {
    /** Plan `E7.I5`: "<= 8 rows". */
    public const val DEFAULT_LIMIT: Int = 8

    /**
     * Returns the subset of [candidates] (case-insensitively) matching
     * [query], prefix matches first then substring matches, each group in
     * its original relative order, de-duplicated, capped at [limit]. A
     * blank [query] short-circuits to the first [limit] de-duplicated
     * [candidates] unranked.
     */
    public fun rank(
        candidates: List<String>,
        query: String,
        limit: Int = DEFAULT_LIMIT,
    ): List<String> {
        val seen = HashSet<String>()
        val deduped = candidates.filter { seen.add(it) }
        if (query.isBlank()) return deduped.take(limit)

        val needle = query.lowercase()
        val prefixMatches = ArrayList<String>()
        val substringMatches = ArrayList<String>()
        for (candidate in deduped) {
            val haystack = candidate.lowercase()
            when {
                haystack.startsWith(needle) -> prefixMatches += candidate
                haystack.contains(needle) -> substringMatches += candidate
            }
        }
        return (prefixMatches + substringMatches).take(limit)
    }
}
