// Proves `FakeRetrievalService` satisfies `RetrievalServiceContractTest` on
// the JVM (`E0.I12`), plus the recording behaviour that is the fake's own
// (not the contract's).

package app.skein.testing

import app.skein.core.model.DocumentKind
import app.skein.core.model.RecallSource
import app.skein.core.model.RetrievalService
import app.skein.core.model.Retrieved
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

public class FakeRetrievalServiceTest : RetrievalServiceContractTest() {
    override fun retrievalService(results: List<Retrieved>): RetrievalService = FakeRetrievalService(results)

    @Test
    public fun records_the_arguments_of_the_most_recent_call(): Unit =
        runTest {
            val service = FakeRetrievalService(listOf(hit(1, score = 0.5)))

            service.retrieveContext("what did I decide?", k = 4, personaId = "persona-1")

            assertEquals("what did I decide?", service.lastQuery)
            assertEquals(4, service.lastK)
            assertEquals("persona-1", service.lastPersonaId)
            assertEquals(1, service.callCount)
        }

    @Test
    public fun records_nothing_before_the_first_call() {
        val service = FakeRetrievalService()

        assertNull(service.lastQuery)
        assertNull(service.lastK)
        assertEquals(0, service.callCount)
    }

    @Test
    public fun sorts_an_unordered_fixture_list_by_descending_score(): Unit =
        runTest {
            val service = FakeRetrievalService(listOf(hit(1, score = 0.2), hit(2, score = 0.9), hit(3, score = 0.5)))

            val results = service.retrieveContext("anything", k = 3)

            assertEquals(listOf(2L, 3L, 1L), results.map { it.chunkId })
        }

    @Test
    public fun breaks_score_ties_by_ascending_chunk_id(): Unit =
        runTest {
            val service = FakeRetrievalService(listOf(hit(7, score = 0.5), hit(2, score = 0.5), hit(5, score = 0.5)))

            val results = service.retrieveContext("anything", k = 3)

            assertEquals(listOf(2L, 5L, 7L), results.map { it.chunkId })
        }

    private fun hit(
        chunkId: Long,
        score: Double,
    ): Retrieved =
        Retrieved(
            chunkId = chunkId,
            docId = "doc-$chunkId",
            docTitle = "Doc $chunkId",
            text = "chunk body $chunkId",
            score = score,
            sourceKind = DocumentKind.NOTE,
            recalledBy = setOf(RecallSource.VECTOR),
        )
}
