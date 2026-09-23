// The `E0.I12` `FakeRetrievalService`: a fixed-list, JVM-only stand-in for
// the `RetrievalService` contract locked in `core/model/…/Retrieval.kt`.
// Powers `RetrievalServiceContractTest` on the JVM and is what every
// consumer (`E10.I2` chat/editor unit tests, `E6.I5`'s context panel,
// `E7.I6`'s `/link related`) uses in place of the real recall → rank
// pipeline.
//
// Faithful to the contract:
//   • Returns at most `k` results, ordered by descending `score` (plan §4.3).
//   • Never throws — an empty fixture list, a blank query, a non-positive
//     `k`, and an unknown `personaId` all yield an empty or truncated list.
//   • Deterministic: equal ties are broken by ascending `chunkId`, so two
//     calls with the same arguments return identical lists.
//
// NOT faithful (approximation only — see `skein-0j1`'s KDoc mandate):
//   • The query is ignored entirely. There is no vector, lexical or graph
//     recall and no Personalized PageRank; the fixture list is the answer to
//     every query. Relevance behaviour belongs to `RetrievalServiceImpl`
//     (`E5.I13`) and is tested there.
//   • `personaId` is recorded but does not filter. Persona scoping is a
//     query-side concern of `E5.I13`; `Retrieved` carries no persona field,
//     so a fake cannot honour it without inventing one.
//   • `score` is whatever the fixture says; it is not comparable across
//     fixtures and carries no calibration.

package app.skein.testing

import app.skein.core.model.PersonaId
import app.skein.core.model.RetrievalService
import app.skein.core.model.Retrieved

/**
 * Returns [results] (highest score first) for every query, truncated to `k`.
 *
 * The most recent call's arguments are exposed as [lastQuery], [lastK] and
 * [lastPersonaId] so a test can assert what the surface under test asked for
 * — e.g. `E6.I5`'s send pipeline is specified to call
 * `retrieveContext(query, 8, persona)`.
 */
public class FakeRetrievalService(
    results: List<Retrieved> = emptyList(),
) : RetrievalService {
    /** Sorted once at construction: the contract's ordering is an invariant, not a per-call computation. */
    private val ordered: List<Retrieved> =
        results.sortedWith(compareByDescending<Retrieved> { it.score }.thenBy { it.chunkId })

    /** The `query` of the most recent [retrieveContext] call, or null if it has never been called. */
    public var lastQuery: String? = null
        private set

    /** The `k` of the most recent [retrieveContext] call, or null if it has never been called. */
    public var lastK: Int? = null
        private set

    /** The `personaId` of the most recent [retrieveContext] call. Null both before any call and when none was passed. */
    public var lastPersonaId: PersonaId? = null
        private set

    /** How many times [retrieveContext] has been called. */
    public var callCount: Int = 0
        private set

    override suspend fun retrieveContext(
        query: String,
        k: Int,
        personaId: PersonaId?,
    ): List<Retrieved> {
        lastQuery = query
        lastK = k
        lastPersonaId = personaId
        callCount += 1
        if (k <= 0) return emptyList()
        return ordered.take(k)
    }
}
