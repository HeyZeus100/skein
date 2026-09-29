package app.skein.core.vault.index

import app.skein.core.model.LexicalQueryLimits

/**
 * Quotes already-tokenized SQLite terms as a bounded literal OR query. The
 * caller obtains terms from the connection's real unicode61 tokenizer, so
 * punctuation, case folding and diacritics match the index. No JVM regex
 * claims tokenizer equivalence. Only the final retained term gets a prefix.
 */
internal object FtsQuerySanitizer {
    fun sanitize(tokens: List<String>): String {
        val bounded =
            tokens
                .asSequence()
                .filter {
                    it.isNotEmpty() &&
                        it.toByteArray(Charsets.UTF_8).size <= LexicalQueryLimits.MAX_TERM_UTF8_BYTES
                }.take(LexicalQueryLimits.MAX_TERMS)
                .toList()
        return bounded
            .mapIndexed { index, token ->
                val quoted = "\"${token.replace("\"", "\"\"")}\""
                if (index == bounded.lastIndex) "$quoted*" else quoted
            }.joinToString(" OR ")
    }
}
