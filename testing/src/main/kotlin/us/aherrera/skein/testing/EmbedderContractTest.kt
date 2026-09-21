// The `E0.I17` contract suite: an abstract JUnit 4 test class that any
// `EmbedderService` implementation — the `FakeEmbedderService` here, and the
// production `EmbedderServiceImpl` in `E5.I3` (`EmbedderServiceImplTest :
// EmbedderContractTest()`, plan § 5.3's "E5.I3" acceptance criteria) — must
// satisfy. The tests map 1:1 to `skein-1su`'s acceptance criteria and plan
// § 4.6's documented `EmbedderService` semantics.
//
// Kept in `src/main` (not `src/test`), matching `InferenceEngineContractTest`
// and the other `E0.I1x` contract suites in this package, so downstream
// modules can consume it as `testImplementation(project(":testing"))`.

package us.aherrera.skein.testing

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test
import us.aherrera.skein.core.model.EmbedderService
import us.aherrera.skein.core.model.Model

/**
 * Contract suite for `EmbedderService` (plan § 4.6). Concrete subclasses
 * provide an unloaded service and the models to load it with; each test
 * then exercises one semantic from `skein-1su`'s acceptance criteria.
 */
public abstract class EmbedderContractTest {
    /** The service under test. Called once per test method. */
    protected abstract fun embedder(): EmbedderService

    /** A `Model` carrying `Capability.EMBEDDING`, passed as `load`'s `embed` parameter. */
    protected abstract fun embedModel(): Model

    /** A `Model` carrying `Capability.RERANK`, passed as `load`'s `rerank` parameter. */
    protected abstract fun rerankModel(): Model

    // ------------------------------------------------------------------
    // plan § 4.6 — "256 bytes each" (`embedDocuments`/`embedQuery`).
    // ------------------------------------------------------------------

    @Test
    public fun embed_documents_returns_256_bytes_each(): Unit =
        runTest {
            val embedder = embedder()
            embedder.load(embedModel(), ner = null, rerank = null).getOrThrow()

            val vectors = embedder.embedDocuments(listOf("cats are great pets", "sqlite-vec"))

            assertEquals(2, vectors.size)
            for (vector in vectors) {
                assertEquals("expected exactly 256 int8 bytes per text", 256, vector.size)
            }
        }

    // ------------------------------------------------------------------
    // plan § 4.6 — `embedQuery` applies "search_query: ", `embedDocuments`
    // applies "search_document: "; the same text yields different vectors.
    // ------------------------------------------------------------------

    @Test
    public fun embed_query_differs_from_embed_documents_for_same_text(): Unit =
        runTest {
            val embedder = embedder()
            embedder.load(embedModel(), ner = null, rerank = null).getOrThrow()

            val documentVector = embedder.embedDocuments(listOf("x"))[0]
            val queryVector = embedder.embedQuery("x")

            assertFalse(
                "embedQuery(\"x\") must differ from embedDocuments([\"x\"])[0] due to the prefix",
                documentVector.contentEquals(queryVector),
            )
        }

    // ------------------------------------------------------------------
    // `skein-1su` description — "equal texts are equal": embedding the same
    // text twice (through the same prefix pipeline) is deterministic.
    // ------------------------------------------------------------------

    @Test
    public fun embed_documents_same_text_returns_same_vector(): Unit =
        runTest {
            val embedder = embedder()
            embedder.load(embedModel(), ner = null, rerank = null).getOrThrow()

            val first = embedder.embedDocuments(listOf("same text"))[0]
            val second = embedder.embedDocuments(listOf("same text"))[0]

            assertEquals(
                "embedding the same text twice must be deterministic",
                true,
                first.contentEquals(second),
            )
        }

    // ------------------------------------------------------------------
    // plan § 4.6 — "Throws [UnsupportedOperationException] if no rerank
    // model is loaded."
    // ------------------------------------------------------------------

    @Test
    public fun rerank_throws_when_unsupported(): Unit =
        runTest {
            val embedder = embedder()
            embedder.load(embedModel(), ner = null, rerank = null).getOrThrow()

            try {
                embedder.rerank("query", listOf("candidate one", "candidate two"))
                fail("expected UnsupportedOperationException")
            } catch (expected: UnsupportedOperationException) {
                assertNotNull(expected)
            }
        }
}
