// `IngestSteps.indexVectors` (skein-4uu, gap on plan `E5.I7`) and
// `IngestSteps.indexLexical` (skein-7v3 / skein-01ku) tests, against the JVM
// `FakeEmbedderService` / `InMemoryIndexStore` fakes from `:testing`.

package app.skein.core.rag.ingest

import app.skein.core.model.Capability
import app.skein.core.model.ChunkId
import app.skein.core.model.EmbedderService
import app.skein.core.model.IndexChange
import app.skein.core.model.IndexStore
import app.skein.core.model.Model
import app.skein.core.model.ModelFormat
import app.skein.core.model.NewChunk
import app.skein.core.rag.chunk.Chunk
import app.skein.testing.FakeEmbedderService
import app.skein.testing.InMemoryIndexStore
import app.skein.testing.SkeinLogCaptureRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class IngestStepsTest {
    /** Also fails the test if any captured `SkeinLog` entry carries content (spec §9). */
    @get:Rule
    val logCapture = SkeinLogCaptureRule()

    @Test
    fun `embedDocuments is called in batches of exactly 32 texts`() =
        runTest {
            for (n in listOf(0, 1, 32, 33, 100)) {
                val embedder = CountingEmbedderService(loadedEmbedder())
                val index = InMemoryIndexStore()
                val texts = (1..n).map { "chunk text number $it" }
                val chunkIds = registerChunks(index, texts)

                IngestSteps(index, embedder).indexVectors(chunkIds, texts)

                assertThat(embedder.embedDocumentsCallSizes).isEqualTo(expectedBatchSizes(n))
            }
        }

    @Test
    fun `putEmbeddings receives every chunk id across all batches`() =
        runTest {
            val embedder = loadedEmbedder()
            val index = InMemoryIndexStore()
            val texts = (1..100).map { "chunk text number $it" }
            val chunkIds = registerChunks(index, texts)

            IngestSteps(index, embedder).indexVectors(chunkIds, texts)

            val results = index.knn(embedder.embedQuery("chunk text number 1"), k = 100)
            assertThat(results.map { it.chunkId }.toSet()).isEqualTo(chunkIds.toSet())
        }

    @Test
    fun `each batch publishes one EmbeddingsUpdated change covering exactly that batch`() =
        runTest {
            val embedder = loadedEmbedder()
            val index = InMemoryIndexStore()
            val texts = (1..33).map { "chunk text number $it" }
            val chunkIds = registerChunks(index, texts)
            val seen = collectChanges(index)

            IngestSteps(index, embedder).indexVectors(chunkIds, texts)

            val embeddingEvents = seen.filterIsInstance<IndexChange.EmbeddingsUpdated>()
            assertThat(embeddingEvents).hasSize(2)
            assertThat(embeddingEvents[0].chunkIds).isEqualTo(chunkIds.subList(0, 32))
            assertThat(embeddingEvents[1].chunkIds).isEqualTo(chunkIds.subList(32, 33))
        }

    @Test
    fun `empty input calls neither embedDocuments nor putEmbeddings`() =
        runTest {
            val embedder = CountingEmbedderService(loadedEmbedder())
            val index = InMemoryIndexStore()
            val seen = collectChanges(index)

            IngestSteps(index, embedder).indexVectors(emptyList(), emptyList())

            assertThat(embedder.embedDocumentsCallSizes).isEmpty()
            assertThat(seen).isEmpty()
        }

    @Test
    fun `mismatched chunkIds and texts sizes throw`() =
        runTest {
            val embedder = loadedEmbedder()
            val index = InMemoryIndexStore()
            val chunkIds = registerChunks(index, listOf("only one chunk"))

            var thrown: IllegalArgumentException? = null
            try {
                IngestSteps(index, embedder).indexVectors(chunkIds, listOf("a", "b"))
            } catch (e: IllegalArgumentException) {
                thrown = e
            }
            assertThat(thrown).isNotNull()
        }

    // ---- indexLexical (skein-7v3 / skein-01ku) --------------------------------

    @Test
    fun `indexLexical writes one row per chunk in ord order with the embedding text and returns their ids`() =
        runTest {
            val index = InMemoryIndexStore()
            val chunks = listOf(chunk(0, "First paragraph here."), chunk(1, "Second paragraph here.", "# Title"))

            val ids = IngestSteps(index).indexLexical("doc-a", chunks)

            val rows = index.getChunks(ids)
            assertThat(ids).hasSize(2)
            assertThat(rows.getValue(ids[0]).ord).isEqualTo(0)
            assertThat(rows.getValue(ids[1]).text).isEqualTo("# Title\n\nSecond paragraph here.")
            assertThat(rows.getValue(ids[1]).tokenCount).isEqualTo(chunks[1].tokenCount)
        }

    @Test
    fun `indexLexical without an embedder stamps the pending embedder id and version`() =
        runTest {
            val index = InMemoryIndexStore()
            val steps = IngestSteps(index)

            val ids = steps.indexLexical("doc-a", listOf(chunk(0, "Body text here.")))

            val row = index.getChunks(ids).getValue(ids.single())
            assertThat(row.embedderId).isEqualTo(IngestSteps.PENDING_EMBEDDER_ID)
            assertThat(row.embedderVersion).isEqualTo(IngestSteps.PENDING_EMBEDDER_VERSION)
            assertThat(steps.canIndexVectors).isFalse()
        }

    @Test
    fun `indexLexical with an embedder stamps its id and version`() =
        runTest {
            val index = InMemoryIndexStore()
            val embedder = loadedEmbedder()
            val steps = IngestSteps(index, embedder)

            val ids = steps.indexLexical("doc-a", listOf(chunk(0, "Body text here.")))

            val row = index.getChunks(ids).getValue(ids.single())
            assertThat(row.embedderId).isEqualTo(embedder.embedderId)
            assertThat(row.embedderVersion).isEqualTo(embedder.embedderVersion)
            assertThat(steps.canIndexVectors).isTrue()
        }

    @Test
    fun `indexLexical replaces a document's earlier rows`() =
        runTest {
            val index = InMemoryIndexStore()
            val steps = IngestSteps(index)
            steps.indexLexical("doc-a", listOf(chunk(0, "Old body."), chunk(1, "Old tail.")))

            val ids = steps.indexLexical("doc-a", listOf(chunk(0, "New body.")))

            assertThat(
                index.chunksForDocs(setOf("doc-a"), limitPerDoc = 10).map { it.id },
            ).containsExactlyElementsIn(ids)
        }

    @Test
    fun `indexLexical stays silent when the FTS probe finds the new rows`() =
        runTest {
            val warnings = mutableListOf<String>()
            val steps = IngestSteps(InMemoryIndexStore(), warn = { warnings += it })

            steps.indexLexical("doc-a", listOf(chunk(0, "Searchable words in this chunk.")))

            assertThat(warnings).isEmpty()
        }

    @Test
    fun `indexLexical warns without content when the FTS probe finds none of the new rows`() =
        runTest {
            val warnings = mutableListOf<String>()
            val broken =
                object : IndexStore by InMemoryIndexStore() {
                    override suspend fun bm25(
                        query: String,
                        k: Int,
                    ) = emptyList<app.skein.core.model.ScoredChunk>()
                }
            val steps = IngestSteps(broken, warn = { warnings += it })

            steps.indexLexical("doc-a", listOf(chunk(0, "Confidential words in this chunk.")))

            assertThat(warnings).hasSize(1)
            assertThat(warnings.single()).contains("chunks_fts")
            assertThat(warnings.single()).doesNotContain("Confidential")
        }

    @Test
    fun `indexLexical's default warn sink is SkeinLog under the IngestSteps tag, with no content`() =
        runTest {
            val broken =
                object : IndexStore by InMemoryIndexStore() {
                    override suspend fun bm25(
                        query: String,
                        k: Int,
                    ) = emptyList<app.skein.core.model.ScoredChunk>()
                }

            IngestSteps(broken).indexLexical("doc-a", listOf(chunk(0, "Confidential words in this chunk.")))

            val entry = logCapture.captured().single { it.tag == IngestSteps.TAG }
            assertThat(entry.message).contains("chunks_fts")
            assertThat(entry.message).doesNotContain("Confidential")
            assertThat(entry.isSensitive).isFalse()
        }

    @Test
    fun `indexLexical with no chunks deletes and does not probe`() =
        runTest {
            val warnings = mutableListOf<String>()
            val index = InMemoryIndexStore()
            val steps = IngestSteps(index, warn = { warnings += it })
            steps.indexLexical("doc-a", listOf(chunk(0, "Old body.")))

            val ids = steps.indexLexical("doc-a", emptyList())

            assertThat(ids).isEmpty()
            assertThat(index.chunksForDocs(setOf("doc-a"), limitPerDoc = 10)).isEmpty()
            assertThat(warnings).isEmpty()
        }

    @Test
    fun `indexVectors without an embedder throws rather than silently writing nothing`() =
        runTest {
            val index = InMemoryIndexStore()
            val ids = registerChunks(index, listOf("a chunk"))

            val thrown = runCatching { IngestSteps(index).indexVectors(ids, listOf("a chunk")) }.exceptionOrNull()

            assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
        }

    private fun chunk(
        ord: Int,
        text: String,
        breadcrumb: String? = null,
    ): Chunk =
        Chunk(
            ord = ord,
            headingBreadcrumb = breadcrumb,
            text = text,
            start = 0,
            end = text.length,
            tokenCount = text.split(" ").size,
        )

    // ------------------------------------------------------------------

    private fun expectedBatchSizes(total: Int): List<Int> {
        val out = mutableListOf<Int>()
        var remaining = total
        while (remaining > 0) {
            val batch = minOf(IngestSteps.BATCH_SIZE, remaining)
            out += batch
            remaining -= batch
        }
        return out
    }

    private suspend fun registerChunks(
        index: InMemoryIndexStore,
        texts: List<String>,
    ): List<ChunkId> {
        if (texts.isEmpty()) return emptyList()
        return index.replaceChunks(
            docId = "doc-under-test",
            chunks = texts.mapIndexed { i, t -> NewChunk(ord = i, text = t, tokenCount = t.split(" ").size) },
            embedderId = "fake",
            embedderVersion = 1,
        )
    }

    private suspend fun loadedEmbedder(): EmbedderService {
        val embedder = FakeEmbedderService()
        embedder
            .load(
                embed =
                    Model(
                        id = "fake-embed-model",
                        name = "Fake Embed Model",
                        path = "/dev/null/fake-embed-model.onnx",
                        sha256 = "b".repeat(64),
                        format = ModelFormat.ONNX,
                        capabilities = setOf(Capability.EMBEDDING),
                        sizeBytes = 1_000L,
                    ),
                ner = null,
                rerank = null,
            ).getOrThrow()
        return embedder
    }

    /**
     * Mirrors `IndexStoreContractTest.collectChanges` (`:testing`):
     * subscribes with `UnconfinedTestDispatcher` so the collector is
     * guaranteed registered (via [runCurrent]) before the test body
     * mutates the index, with no replay to miss.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.collectChanges(idx: IndexStore): List<IndexChange> {
        val seen = mutableListOf<IndexChange>()
        val job: Job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                idx.observeChanges().collect { seen += it }
            }
        runCurrent()
        check(job.isActive) { "observeChanges collector died before the test body ran" }
        return seen
    }

    /** Counts `embedDocuments` call sizes without altering its behavior. */
    private class CountingEmbedderService(
        private val delegate: EmbedderService,
    ) : EmbedderService by delegate {
        val embedDocumentsCallSizes = mutableListOf<Int>()

        override suspend fun embedDocuments(texts: List<String>): List<ByteArray> {
            embedDocumentsCallSizes += texts.size
            return delegate.embedDocuments(texts)
        }
    }
}
