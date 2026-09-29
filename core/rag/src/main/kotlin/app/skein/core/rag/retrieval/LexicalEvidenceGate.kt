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
 * This is lexical support, not a claim that a source contains the requested
 * fact. Related sources can still pass and paraphrases can be falsely rejected.
 * Vector results bypass this rule until a production embedder can be calibrated;
 * applying a lexical overlap threshold to semantic recall would be unsupported.
 * No source/query text is logged. Calibration and limits: docs/RETRIEVAL_EVAL.md.
 */
public class LexicalEvidenceGate(
    public val minimumCoverage: Double = DEFAULT_MINIMUM_COVERAGE,
) {
    init {
        require(minimumCoverage.isFinite() && minimumCoverage > 0.0 && minimumCoverage <= 1.0)
    }

    public fun select(
        query: String,
        candidates: List<Retrieved>,
    ): List<Retrieved> {
        if (candidates.any { RecallSource.VECTOR in it.recalledBy }) return candidates
        val wanted = terms(query)
        if (wanted.isEmpty()) return emptyList()
        return if (candidates.any { coverage(wanted, it.text) >= minimumCoverage }) candidates else emptyList()
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
        public const val VERSION: String = "lexical-query-coverage-v1"

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
