package app.skein.testing.fakes

/** A retrieval hit as returned by [FakeRetrievalService] — a simplification of the real `Retrieved` (plan §4.3). */
data class FakeRetrieved(
    val docId: String,
    val docTitle: String,
    val text: String,
    val score: Double,
)

/**
 * Deterministic, scripted stand-in for the (not-yet-landed) `RetrievalService`
 * contract (plan §4.3, `E0.I12`). Returns exactly the scripted results for a
 * given query (empty list for anything unscripted) — it never ranks or
 * scores anything itself.
 *
 * `E10.I2` (skein-0j1) re-targets this against the real `RetrievalService`
 * interface once `E0.I12` lands.
 */
class FakeRetrievalService(
    private val scriptedResults: Map<String, List<FakeRetrieved>> = emptyMap(),
) {
    var lastQuery: String? = null
        private set

    fun retrieveContext(
        query: String,
        k: Int = 8,
    ): List<FakeRetrieved> {
        lastQuery = query
        return (scriptedResults[query] ?: emptyList()).take(k)
    }
}
