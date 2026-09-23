// `E10.I2` (skein-0j1): proves `CountingIndexStore` forwards every call
// unchanged to its delegate while recording how many times each method was
// called, for behavioural assertions ("did retrieval call `bm25` exactly
// once per query").

package app.skein.testing

import app.skein.core.model.NewChunk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

public class CountingIndexStoreTest {
    @Test
    public fun counts_start_at_zero_for_a_method_never_called() {
        val counting = CountingIndexStore(InMemoryIndexStore())

        assertEquals(0, counting.countOf("bm25"))
        assertEquals(emptyMap<String, Int>(), counting.counts)
    }

    @Test
    public fun records_one_call_per_invocation_keyed_by_method_name(): Unit =
        runTest {
            val counting = CountingIndexStore(InMemoryIndexStore())

            counting.replaceChunks(
                docId = "doc-1",
                chunks = listOf(NewChunk(ord = 0, text = "hello world", tokenCount = 2)),
                embedderId = "fake-embedder",
                embedderVersion = 1,
            )
            counting.bm25(query = "hello", k = 5)
            counting.bm25(query = "world", k = 5)

            assertEquals(1, counting.countOf("replaceChunks"))
            assertEquals(2, counting.countOf("bm25"))
            assertEquals(0, counting.countOf("knn"))
        }

    @Test
    public fun forwards_results_from_the_delegate_unchanged(): Unit =
        runTest {
            val delegate = InMemoryIndexStore()
            val counting = CountingIndexStore(delegate)

            val ids =
                counting.replaceChunks(
                    docId = "doc-1",
                    chunks = listOf(NewChunk(ord = 0, text = "hello world", tokenCount = 2)),
                    embedderId = "fake-embedder",
                    embedderVersion = 1,
                )
            val hits = counting.bm25(query = "hello", k = 5)

            assertEquals(1, ids.size)
            assertEquals(ids, hits.map { it.chunkId })
        }

    @Test
    public fun reset_clears_recorded_counts_but_not_delegate_state(): Unit =
        runTest {
            val counting = CountingIndexStore(InMemoryIndexStore())
            counting.bm25(query = "hello", k = 5)

            counting.reset()

            assertEquals(0, counting.countOf("bm25"))
        }
}
