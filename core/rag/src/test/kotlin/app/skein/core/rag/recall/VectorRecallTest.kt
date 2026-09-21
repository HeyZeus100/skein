// `VectorRecall` (skein-4uu, gap on plan `E5.I7`) tests, against the JVM
// `FakeEmbedderService` / `InMemoryIndexStore` fakes from `:testing`.
//
// The fake embedder's vectors are a documented **approximation** — a
// deterministic, character-hash-based unit vector, not a real nomic-embed
// output (see the fake's own file header: "texts that share more
// characters land closer, which is a byproduct of summing one
// pseudo-random vector per character rather than a learned encoder"). That
// is sufficient to prove `VectorRecall`'s own contract (score mapping,
// ordering, k-cap, tie-break); the acceptance criterion's literal
// `recall("feline pets")`-ranks-cats-first example against a *real*
// embedder is additionally covered by the compile-only androidTest
// companion in `:core:vault` (`VectorRecallAcceptanceTest`, gated on
// skein-k3b2 like `LexicalRecallAcceptanceTest`), which drives real
// `IndexStoreImpl.knn` with hand-built orthogonal int8 vectors instead.

package app.skein.core.rag.recall

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import us.aherrera.skein.core.model.Capability
import us.aherrera.skein.core.model.ChunkId
import us.aherrera.skein.core.model.DocId
import us.aherrera.skein.core.model.EmbedderService
import us.aherrera.skein.core.model.Model
import us.aherrera.skein.core.model.ModelFormat
import us.aherrera.skein.core.model.NewChunk
import us.aherrera.skein.testing.FakeEmbedderService
import us.aherrera.skein.testing.InMemoryIndexStore

class VectorRecallTest {
    @Test
    fun `chunk whose text matches the query text ranks first`() =
        runTest {
            val embedder = loadedEmbedder()
            val index = InMemoryIndexStore()
            val catsText = "cats are wonderful feline pets that purr contentedly"
            val sqliteText = "sqlite is a small fast embedded database engine library"
            val financeText = "quarterly finance report on revenue expenses and budget"

            val catsId = addChunk(index, embedder, docId(1), catsText)
            addChunk(index, embedder, docId(2), sqliteText)
            addChunk(index, embedder, docId(3), financeText)

            val results = VectorRecall(index, embedder).recall(catsText)

            assertThat(results).isNotEmpty()
            assertThat(results.first().chunkId).isEqualTo(catsId)
        }

    @Test
    fun `scores are mapped into 0,1`() =
        runTest {
            val embedder = loadedEmbedder()
            val index = InMemoryIndexStore()
            addChunk(index, embedder, docId(1), "alpha beta gamma delta")
            addChunk(index, embedder, docId(2), "epsilon zeta eta theta")

            val results = VectorRecall(index, embedder).recall("alpha beta gamma delta")

            assertThat(results).isNotEmpty()
            for (r in results) {
                assertThat(r.score).isAtLeast(0.0)
                assertThat(r.score).isAtMost(1.0)
            }
        }

    @Test
    fun `k caps the number of results`() =
        runTest {
            val embedder = loadedEmbedder()
            val index = InMemoryIndexStore()
            for (i in 1..5) {
                addChunk(index, embedder, docId(i), "chunk number $i carries unique filler text")
            }

            val results = VectorRecall(index, embedder).recall("chunk number query", k = 2)

            assertThat(results).hasSize(2)
        }

    @Test
    fun `non-positive k short-circuits to empty without touching the store`() =
        runTest {
            val embedder = loadedEmbedder()
            val index = InMemoryIndexStore()
            addChunk(index, embedder, docId(1), "anything at all")

            assertThat(VectorRecall(index, embedder).recall("anything", k = 0)).isEmpty()
            assertThat(VectorRecall(index, embedder).recall("anything", k = -1)).isEmpty()
        }

    @Test
    fun `empty index returns empty list`() =
        runTest {
            val embedder = loadedEmbedder()
            val index = InMemoryIndexStore()

            val results = VectorRecall(index, embedder).recall("anything")

            assertThat(results).isEmpty()
        }

    @Test
    fun `empty query does not throw and stays within score bounds`() =
        runTest {
            val embedder = loadedEmbedder()
            val index = InMemoryIndexStore()
            addChunk(index, embedder, docId(1), "some ordinary content")

            val results = VectorRecall(index, embedder).recall("")

            assertThat(results).isNotEmpty()
            for (r in results) {
                assertThat(r.score).isAtLeast(0.0)
                assertThat(r.score).isAtMost(1.0)
            }
        }

    @Test
    fun `ties break by ascending chunkId regardless of store iteration order`() =
        runTest {
            val embedder = loadedEmbedder()
            val index = InMemoryIndexStore()
            val ids =
                index.replaceChunks(
                    docId = docId(1),
                    chunks =
                        listOf(
                            NewChunk(ord = 0, text = "identical text", tokenCount = 2),
                            NewChunk(ord = 1, text = "identical text", tokenCount = 2),
                        ),
                    embedderId = embedder.embedderId,
                    embedderVersion = embedder.embedderVersion,
                )
            val (lowerId, higherId) = ids.sorted().let { it[0] to it[1] }
            val vector = embedder.embedDocuments(listOf("identical text")).single()
            // Insert into the fake's (insertion-ordered) embeddings map with
            // the higher chunkId first, so a plain stable sort over store
            // iteration order would put the higher id first. `VectorRecall`
            // must re-sort by chunkId to recover ascending order on a tie.
            index.putEmbeddings(listOf(higherId to vector, lowerId to vector))

            val results = VectorRecall(index, embedder).recall("identical text")

            assertThat(results.map { it.chunkId }).containsExactly(lowerId, higherId).inOrder()
        }

    // ------------------------------------------------------------------

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

    private suspend fun addChunk(
        index: InMemoryIndexStore,
        embedder: EmbedderService,
        docId: DocId,
        text: String,
    ): ChunkId {
        val ids =
            index.replaceChunks(
                docId = docId,
                chunks = listOf(NewChunk(ord = 0, text = text, tokenCount = text.split(" ").size)),
                embedderId = embedder.embedderId,
                embedderVersion = embedder.embedderVersion,
            )
        val id = ids.single()
        val vector = embedder.embedDocuments(listOf(text)).single()
        index.putEmbeddings(listOf(id to vector))
        return id
    }

    private fun docId(n: Int): DocId = "doc-$n"
}
