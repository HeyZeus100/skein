// The `E0.I12` contract suite for `RetrievalService` (plan §4.3, design spec
// §7.2): an abstract JUnit 4 test class that any implementation — the
// `FakeRetrievalService` here, and the production `RetrievalServiceImpl` in
// `E5.I13` (`RetrievalServiceImplTest : RetrievalServiceContractTest()`) —
// must satisfy.
//
// Kept in `src/main` (not `src/test`), matching `InferenceEngineContractTest`,
// `EmbedderContractTest` and the other `E0.I1x` contract suites in this
// package, so downstream modules can consume it as
// `testImplementation(project(":testing"))`.
//
// Scope note: the suite tests the *shape* of the contract — the `k` ceiling,
// descending-score ordering, "empty vault → empty list, never throws", and
// determinism. Relevance (does recall find the right chunk?) is not a
// contract property and is measured by `E5.I17`'s gold set instead. Persona
// *filtering* is likewise not asserted here: `Retrieved` carries no persona
// field, so the contract only fixes that `personaId` is accepted and does not
// disturb the other invariants (`E5.I13` owns the filtering semantics).

package us.aherrera.skein.testing

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import us.aherrera.skein.core.model.DocumentKind
import us.aherrera.skein.core.model.RecallSource
import us.aherrera.skein.core.model.RetrievalService
import us.aherrera.skein.core.model.Retrieved

/**
 * Contract suite for `RetrievalService` (plan §4.3). Concrete subclasses
 * return a service that is primed to surface exactly [results] (an
 * implementation backed by a real index seeds it however it likes, as long
 * as those are the retrievable chunks).
 */
public abstract class RetrievalServiceContractTest {
    /** A service whose entire retrievable corpus is [results]. Called once per test method. */
    protected abstract fun retrievalService(results: List<Retrieved>): RetrievalService

    // ------------------------------------------------------------------
    // plan §4.3 — "Returns at most k results".
    // ------------------------------------------------------------------

    @Test
    public fun retrieve_context_returns_at_most_k_results(): Unit =
        runTest {
            val service = retrievalService(corpus(10))

            val results = service.retrieveContext("anything", k = 3)

            assertEquals(3, results.size)
        }

    @Test
    public fun retrieve_context_returns_everything_when_k_exceeds_the_corpus(): Unit =
        runTest {
            val service = retrievalService(corpus(2))

            val results = service.retrieveContext("anything", k = 8)

            assertEquals(2, results.size)
        }

    @Test
    public fun retrieve_context_defaults_k_to_eight(): Unit =
        runTest {
            val service = retrievalService(corpus(20))

            val results = service.retrieveContext("anything")

            assertEquals("plan §4.3 pins the default k to 8", 8, results.size)
        }

    @Test
    public fun retrieve_context_returns_nothing_for_a_non_positive_k(): Unit =
        runTest {
            val service = retrievalService(corpus(10))

            assertTrue(service.retrieveContext("anything", k = 0).isEmpty())
        }

    // ------------------------------------------------------------------
    // plan §4.3 — "ordered by descending score".
    // ------------------------------------------------------------------

    @Test
    public fun retrieve_context_orders_results_by_descending_score(): Unit =
        runTest {
            val service = retrievalService(corpus(6))

            val scores = service.retrieveContext("anything", k = 6).map { it.score }

            assertEquals(scores.sortedDescending(), scores)
        }

    // ------------------------------------------------------------------
    // plan §4.3 — "Empty vault → empty list, never throws".
    // ------------------------------------------------------------------

    @Test
    public fun retrieve_context_returns_an_empty_list_for_an_empty_corpus(): Unit =
        runTest {
            val service = retrievalService(emptyList())

            assertTrue(service.retrieveContext("anything").isEmpty())
        }

    @Test
    public fun retrieve_context_does_not_throw_for_a_blank_query(): Unit =
        runTest {
            val service = retrievalService(corpus(3))

            service.retrieveContext("")
            service.retrieveContext("   ")
        }

    // ------------------------------------------------------------------
    // plan §4.3 — `personaId` is part of the signature and must not break
    // the other invariants. Filtering itself is `E5.I13`'s.
    // ------------------------------------------------------------------

    @Test
    public fun retrieve_context_accepts_a_persona_id_and_keeps_the_k_and_order_invariants(): Unit =
        runTest {
            val service = retrievalService(corpus(6))

            val results = service.retrieveContext("anything", k = 4, personaId = PERSONA_ID)

            assertTrue("k is still a ceiling when a persona is scoped", results.size <= 4)
            assertEquals(results.map { it.score }.sortedDescending(), results.map { it.score })
        }

    // ------------------------------------------------------------------
    // Determinism — the suite, `E5.I17`'s gold set, and `E10.I2`'s feature
    // tests all assume repeated retrieval of an unchanged corpus is stable.
    // ------------------------------------------------------------------

    @Test
    public fun retrieve_context_is_deterministic_across_repeated_calls(): Unit =
        runTest {
            val service = retrievalService(corpus(6))

            val first = service.retrieveContext("anything", k = 4)
            val second = service.retrieveContext("anything", k = 4)

            assertEquals(first.map { it.chunkId }, second.map { it.chunkId })
        }

    private fun corpus(size: Int): List<Retrieved> =
        (1..size).map { i ->
            Retrieved(
                chunkId = i.toLong(),
                docId = "doc-$i",
                docTitle = "Doc $i",
                text = "chunk body $i",
                score = 1.0 - i * 0.01,
                sourceKind = DocumentKind.NOTE,
                recalledBy = setOf(RecallSource.LEXICAL),
            )
        }

    private companion object {
        private const val PERSONA_ID: String = "018f2b6e-6c3a-7c3e-8f2a-6b1e2d3c4a5b"
    }
}
