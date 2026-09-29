package app.skein.core.rag.embed

import app.skein.core.model.ChunkId
import app.skein.core.model.EmbedderService
import app.skein.core.model.IndexStore
import app.skein.core.model.ScoredChunk
import app.skein.core.rag.ingest.IngestSteps
import app.skein.core.rag.recall.VectorRecall
import app.skein.testing.FakeEmbedderService
import app.skein.testing.InMemoryIndexStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

class EmbeddingBoundaryTest {
    @Test
    fun `short or surplus document responses never write a partial batch`() =
        runTest {
            for (count in listOf(0, 1, 3)) {
                val index = RecordingIndex()
                val embedder = DocumentsEmbedder { List(count) { validVector() } }

                val failure =
                    runCatching {
                        IngestSteps(index, embedder).indexVectors(listOf(1L, 2L), listOf("first", "second"))
                    }.exceptionOrNull()

                assertThat(failure).isInstanceOf(IllegalStateException::class.java)
                assertThat(index.writes).isEmpty()
            }
        }

    @Test
    fun `every document vector is validated before any batch row is written`() =
        runTest {
            for (invalid in invalidVectors()) {
                val index = RecordingIndex()
                val embedder = DocumentsEmbedder { listOf(validVector(), invalid) }

                val failure =
                    runCatching {
                        IngestSteps(index, embedder).indexVectors(listOf(1L, 2L), listOf("first", "second"))
                    }.exceptionOrNull()

                assertThat(failure).isInstanceOf(IllegalStateException::class.java)
                assertThat(index.writes).isEmpty()
            }
        }

    @Test
    fun `valid document batches preserve response order across the 32 row boundary`() =
        runTest {
            val index = RecordingIndex()
            val embedder = DocumentsEmbedder { texts -> texts.map { validVector(it.toInt().toByte()) } }
            val ids = (1L..33L).toList()

            IngestSteps(index, embedder).indexVectors(ids, (1..33).map { it.toString() })

            assertThat(embedder.calls.map { it.size }).containsExactly(32, 1).inOrder()
            assertThat(index.writes.flatten().map { it.first }).containsExactlyElementsIn(ids).inOrder()
            assertThat(index.writes.flatten().map { it.second[0].toInt() }).containsExactlyElementsIn(1..33).inOrder()
        }

    @Test
    fun `cancellation during document embedding prevents a completed backend response from being written`() =
        runTest {
            val index = RecordingIndex()
            val embedder =
                DocumentsEmbedder {
                    currentCoroutineContext().cancel(CancellationException("session locked"))
                    listOf(validVector())
                }
            var failure: Throwable? = null

            launch {
                failure =
                    runCatching { IngestSteps(index, embedder).indexVectors(listOf(1L), listOf("text")) }
                        .exceptionOrNull()
            }.join()

            assertThat(failure).isInstanceOf(CancellationException::class.java)
            assertThat(index.writes).isEmpty()
        }

    @Test
    fun `cancellation after a batch write prevents the next document embedding call`() =
        runTest {
            val index = RecordingIndex(onWrite = { currentCoroutineContext().cancel() })
            val embedder = DocumentsEmbedder { texts -> texts.map { validVector() } }
            var failure: Throwable? = null

            launch {
                failure =
                    runCatching {
                        IngestSteps(index, embedder).indexVectors((1L..33L).toList(), List(33) { "text" })
                    }.exceptionOrNull()
            }.join()

            assertThat(failure).isInstanceOf(CancellationException::class.java)
            assertThat(embedder.calls).hasSize(1)
            assertThat(index.writes).hasSize(1)
        }

    @Test
    fun `malformed query vectors never reach nearest neighbor search`() =
        runTest {
            for (invalid in invalidVectors()) {
                val index = RecordingIndex()
                val embedder = QueryEmbedder { invalid }

                val failure = runCatching { VectorRecall(index, embedder).recall("query") }.exceptionOrNull()

                assertThat(failure).isInstanceOf(IllegalStateException::class.java)
                assertThat(index.queries).isEmpty()
            }
        }

    @Test
    fun `cancellation during query embedding prevents nearest neighbor search`() =
        runTest {
            val index = RecordingIndex()
            val embedder =
                QueryEmbedder {
                    currentCoroutineContext().cancel(CancellationException("session locked"))
                    validVector()
                }
            var failure: Throwable? = null

            launch {
                failure = runCatching { VectorRecall(index, embedder).recall("query") }.exceptionOrNull()
            }.join()

            assertThat(failure).isInstanceOf(CancellationException::class.java)
            assertThat(index.queries).isEmpty()
        }

    @Test
    fun `valid query vector reaches nearest neighbor search unchanged`() =
        runTest {
            val index = RecordingIndex()
            val vector = validVector(-127)
            VectorRecall(index, QueryEmbedder { vector }).recall("query")
            assertThat(index.queries.single()).isSameInstanceAs(vector)
        }

    @Test
    fun `cancellation during nearest neighbor search propagates even when the index returns`() =
        runTest {
            val index = RecordingIndex(onQuery = { currentCoroutineContext().cancel() })
            var failure: Throwable? = null

            launch {
                failure =
                    runCatching { VectorRecall(index, QueryEmbedder { validVector() }).recall("query") }
                        .exceptionOrNull()
            }.join()

            assertThat(failure).isInstanceOf(CancellationException::class.java)
            assertThat(index.queries).hasSize(1)
        }

    private class DocumentsEmbedder(
        val response: suspend (List<String>) -> List<ByteArray>,
    ) : EmbedderService by FakeEmbedderService() {
        val calls = mutableListOf<List<String>>()

        override suspend fun embedDocuments(texts: List<String>): List<ByteArray> {
            calls += texts.toList()
            return response(texts)
        }
    }

    private class QueryEmbedder(
        val response: suspend () -> ByteArray,
    ) : EmbedderService by FakeEmbedderService() {
        override suspend fun embedQuery(text: String): ByteArray = response()
    }

    private class RecordingIndex(
        val onWrite: suspend () -> Unit = {},
        val onQuery: suspend () -> Unit = {},
    ) : IndexStore by InMemoryIndexStore() {
        val writes = mutableListOf<List<Pair<ChunkId, ByteArray>>>()
        val queries = mutableListOf<ByteArray>()

        override suspend fun putEmbeddings(embeddings: List<Pair<ChunkId, ByteArray>>) {
            writes += embeddings
            onWrite()
        }

        override suspend fun knn(
            queryInt8: ByteArray,
            k: Int,
        ): List<ScoredChunk> {
            queries += queryInt8
            onQuery()
            return emptyList()
        }
    }

    private fun invalidVectors(): List<ByteArray> =
        listOf(ByteArray(255), ByteArray(257), ByteArray(256), validVector(Byte.MIN_VALUE))

    private fun validVector(first: Byte = 127): ByteArray = ByteArray(256).apply { this[0] = first }
}
