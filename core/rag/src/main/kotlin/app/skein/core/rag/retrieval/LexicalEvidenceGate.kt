package app.skein.core.rag.retrieval

import app.skein.core.model.RecallSource
import app.skein.core.model.Retrieved
import java.text.Normalizer
import java.util.Locale

/**
 * Query-level abstention for lexical/graph retrieval without a semantic model.
 * A normalized rank winner is not evidence of relevance. Require one returned
 * chunk to cover a configured fraction of distinct, non-grammatical query terms.
 * Preserve the original ranking and complete chunks when that condition holds.
 *
 * The opt-in experiment for explicit money, duration and count questions requires a value of that
 * kind in the same supporting sentence. These bounded English checks remain
 * heuristics: they do not establish which entity or attribute a value belongs to.
 * A development-only coverage cutoff permits typed-value paraphrases. Development
 * counterexamples falsify this experiment, so production keeps it disabled.
 * Other paraphrases can still be falsely rejected; related facts can still pass.
 * Vector results bypass this rule until a production embedder can be calibrated;
 * applying a lexical overlap threshold to semantic recall would be unsupported.
 * No source/query text is logged. Calibration and limits: docs/RETRIEVAL_EVAL.md.
 */
public class LexicalEvidenceGate(
    public val minimumCoverage: Double = DEFAULT_MINIMUM_COVERAGE,
    public val minimumValueCoverage: Double = DEFAULT_MINIMUM_VALUE_COVERAGE,
    public val requestedValueChecks: Boolean = false,
) {
    init {
        require(minimumCoverage.isFinite() && minimumCoverage > 0.0 && minimumCoverage <= 1.0)
        require(minimumValueCoverage.isFinite() && minimumValueCoverage > 0.0 && minimumValueCoverage <= 1.0)
    }

    public fun select(
        query: String,
        candidates: List<Retrieved>,
    ): List<Retrieved> {
        if (candidates.any { RecallSource.VECTOR in it.recalledBy }) return candidates
        val boundedQuery = if (requestedValueChecks) query.take(MAX_QUERY_CHARS) else query
        val wanted = terms(boundedQuery)
        if (wanted.isEmpty()) return emptyList()
        val requested = if (requestedValueChecks) RequestedValue.from(boundedQuery) else null
        val supported =
            candidates.any { candidate ->
                val text = if (requestedValueChecks) candidate.text.take(MAX_SOURCE_CHARS) else candidate.text
                if (requested == null) {
                    coverage(wanted, text) >= minimumCoverage
                } else {
                    RequestedValue.SENTENCE_BOUNDARY.splitToSequence(text).any { sentence ->
                        val overlap = coverage(wanted, sentence)
                        requested.appearsIn(sentence) &&
                            (
                                overlap >= minimumCoverage ||
                                    (
                                        overlap >= minimumValueCoverage &&
                                            wanted.intersect(terms(sentence)).size >= MIN_VALUE_SHARED_TERMS
                                    )
                            )
                    }
                }
            }
        return if (supported) candidates else emptyList()
    }

    private fun coverage(
        wanted: Set<String>,
        text: String,
    ): Double = wanted.intersect(terms(text)).size.toDouble() / wanted.size

    private fun terms(text: String): Set<String> =
        TOKEN
            .findAll(Normalizer.normalize(text, Normalizer.Form.NFC).lowercase(Locale.ROOT))
            .map { it.value }
            .filterNot { it in STOP_WORDS }
            .toSet()

    public companion object {
        public const val DEFAULT_MINIMUM_COVERAGE: Double = 0.5
        public const val DEFAULT_MINIMUM_VALUE_COVERAGE: Double = 0.25
        public const val VERSION: String = "lexical-query-coverage-v1"
        public const val CANDIDATE_VERSION: String = "lexical-fact-shape-v2-experimental"

        private const val MIN_VALUE_SHARED_TERMS = 2
        private const val MAX_QUERY_CHARS = 8_192
        private const val MAX_SOURCE_CHARS = 32_768

        private val TOKEN = Regex("[\\p{L}\\p{N}]+")

        // Grammatical function words only: no fixture topics, IDs or answer terms.
        private val STOP_WORDS =
            (
                "a an the of for to in on at by with from is are was were " +
                    "be been being do does did can could would should will shall may might must i me " +
                    "my we our you your he she it they them their this that these those what where " +
                    "when who why how which and or but if as than then there here not no"
            ).split(' ').toSet()
    }
}
