package app.skein.core.rag.retrieval

/**
 * Bounded English answer-shape checks, not entailment or a semantic classifier.
 * A topic hit containing no value of the explicitly requested kind is insufficient.
 * Values must occur in the same sentence as the supporting lexical terms; a price
 * elsewhere in the chunk cannot lend support to a duration or a different topic.
 * Unknown question forms retain the ordinary lexical policy.
 */
internal enum class RequestedValue {
    MONEY,
    DURATION,
    COUNT,
    ;

    fun appearsIn(sentence: String): Boolean =
        when (this) {
            MONEY ->
                MONEY_VALUE.containsMatchIn(sentence) ||
                    (UNITLESS_VALUE.containsMatchIn(sentence) && !DURATION_VALUE.containsMatchIn(sentence))
            DURATION -> DURATION_VALUE.containsMatchIn(sentence)
            COUNT -> NUMBER.containsMatchIn(sentence)
        }

    companion object {
        fun from(query: String): RequestedValue? =
            when {
                OTHER_QUESTION.containsMatchIn(query) -> null
                DURATION_QUESTION.containsMatchIn(query) -> DURATION
                COUNT_QUESTION.containsMatchIn(query) -> COUNT
                VALUE_QUESTION.containsMatchIn(query) && MONEY_QUESTION.containsMatchIn(query) -> MONEY
                else -> null
            }

        // Split prose without splitting decimal amounts. Work is capped by the
        // caller before regex matching, and no expressions contain nested repeats.
        val SENTENCE_BOUNDARY = Regex("[!?;\\n]+|(?<![0-9])\\.(?![0-9])|(?<=[0-9])\\.(?![0-9])")
        private val MONEY_QUESTION =
            Regex(
                "\\b(?:cost|costs|price|fee|fees|fare|premium|tuition|salary|rent|budget)\\b",
                RegexOption.IGNORE_CASE,
            )
        private val OTHER_QUESTION = Regex("^\\s*(?:where|who|when|why)\\b", RegexOption.IGNORE_CASE)
        private val VALUE_QUESTION = Regex("^\\s*(?:what (?:is|are|was|were)|how much)\\b", RegexOption.IGNORE_CASE)
        private val DURATION_QUESTION = Regex("^\\s*how long\\b", RegexOption.IGNORE_CASE)
        private val COUNT_QUESTION = Regex("^\\s*how many\\b", RegexOption.IGNORE_CASE)
        private const val NUMBER_TEXT =
            "(?:[0-9]+(?:[.,][0-9]+)*|zero|one|two|three|four|five|six|seven|eight|nine|ten|" +
                "eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|hundred|thousand)"
        private val NUMBER = Regex("\\b$NUMBER_TEXT\\b", RegexOption.IGNORE_CASE)
        private val MONEY_VALUE =
            Regex(
                "(?:[$€£¥]\\s*[0-9]|\\b$NUMBER_TEXT\\s*(?:dollars?|euros?|pounds?|yen|cents?|USD|EUR|GBP)\\b|" +
                    "\\b(?:free|complimentary|no charge)\\b)",
                RegexOption.IGNORE_CASE,
            )
        private val UNITLESS_VALUE =
            Regex("\\b(?:is|are|was|were|costs?|totals?|equals?)\\s+$NUMBER_TEXT\\b", RegexOption.IGNORE_CASE)
        private val DURATION_VALUE =
            Regex(
                "\\b$NUMBER_TEXT\\s*(?:milliseconds?|seconds?|minutes?|hours?|days?|weeks?|months?|years?)\\b",
                RegexOption.IGNORE_CASE,
            )
    }
}
