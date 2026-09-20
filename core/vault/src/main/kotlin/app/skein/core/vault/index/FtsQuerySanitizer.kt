// Sanitizer for user-supplied FTS5 MATCH queries (E2.I15).
//
// FTS5 has its own query grammar — bare punctuation, double-quotes, and
// parentheses in a raw user string can either throw a syntax error or,
// worse, silently mean something the user did not intend. This module
// normalizes an arbitrary user string into a safe FTS5 phrase-OR-list.
//
// Behaviour, matched to the acceptance test in the plan (`E2.I15`):
//   • Splits the input on any non-alphanumeric character.
//   • Skips empty tokens (so `"it's a \"quoted\" (weird) query"` becomes
//     the tokens `it`, `s`, `a`, `quoted`, `weird`, `query`).
//   • Wraps each token in double quotes (`"it"`) so FTS5 treats it as a
//     literal phrase — bypassing any accidental keyword collision.
//   • Joins with `OR`. Ranking is left to FTS5's own `bm25()` scoring.
//   • Appends a `*` to the last token's closing quote (`"query"*`) so a
//     partial word at the end of the query autocompletes ("comp" also
//     matches "computer"). Prefix on the last token only mirrors typical
//     search-as-you-type behavior.
//
// Never throws. An empty or whitespace-only query returns an empty string;
// the caller (`IndexStoreImpl.bm25`) short-circuits on that.

package app.skein.core.vault.index

internal object FtsQuerySanitizer {
    private val TOKEN_SPLIT: Regex = Regex("[^A-Za-z0-9]+")

    fun sanitize(query: String): String {
        val tokens =
            query
                .split(TOKEN_SPLIT)
                .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return ""
        val quoted =
            tokens.mapIndexed { index, token ->
                val quotedToken = "\"$token\""
                if (index == tokens.lastIndex) "$quotedToken*" else quotedToken
            }
        return quoted.joinToString(separator = " OR ")
    }
}
