package app.skein.testing.fakes

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FakeRetrievalServiceTest {
    @Test
    fun `retrieveContext returns the scripted results for a known query`() {
        val hit = FakeRetrieved("doc-1", "Title", "text", score = 1.0)
        val service = FakeRetrievalService(scriptedResults = mapOf("query" to listOf(hit)))

        val results = service.retrieveContext("query")

        assertThat(results).containsExactly(hit)
    }

    @Test
    fun `retrieveContext returns an empty list for an unscripted query`() {
        val service = FakeRetrievalService()

        val results = service.retrieveContext("nothing scripted")

        assertThat(results).isEmpty()
    }

    @Test
    fun `retrieveContext truncates to k results`() {
        val hits = (1..10).map { FakeRetrieved("doc-$it", "Title $it", "text", score = it.toDouble()) }
        val service = FakeRetrievalService(scriptedResults = mapOf("query" to hits))

        val results = service.retrieveContext("query", k = 3)

        assertThat(results).hasSize(3)
    }

    @Test
    fun `retrieveContext records the last query`() {
        val service = FakeRetrievalService()

        service.retrieveContext("what was asked")

        assertThat(service.lastQuery).isEqualTo("what was asked")
    }
}
