// `RetrievalServiceImplTest` (skein-do6, E5.I13). Bead-specific behaviour
// beyond the locked `RetrievalServiceContractTest` shape (that suite runs
// separately as `RetrievalServiceImplContractTest`): the embedder-optional
// degradation modes, the per-source hard timeout, the persona filter's
// wiring through to `PprRanker`, and `Retrieved.recalledBy` provenance.

package app.skein.core.rag.retrieval

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Test
import us.aherrera.skein.core.model.Capability
import us.aherrera.skein.core.model.DocumentKind
import us.aherrera.skein.core.model.EmbedderService
import us.aherrera.skein.core.model.EntitySpan
import us.aherrera.skein.core.model.Model
import us.aherrera.skein.core.model.ModelFormat
import us.aherrera.skein.core.model.NewChunk
import us.aherrera.skein.core.model.NewDocument
import us.aherrera.skein.core.model.RecallSource
import us.aherrera.skein.testing.FakeEmbedderService
import us.aherrera.skein.testing.InMemoryIndexStore
import us.aherrera.skein.testing.InMemoryVaultRepository

class RetrievalServiceImplTest {
    // ------------------------------------------------------------------
    // Embedder-optional degradation (plan `E5.I13`: "Empty vault or
    // embedder unavailable → degrade to lexical + graph only").
    // ------------------------------------------------------------------

    @Test
    fun `no embedder still returns lexical results and never claims vector provenance`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            seedNote(repo, index, title = "Gizmo note", body = "a gizmo is a small mechanical device")

            val service = RetrievalServiceImpl(index = index, repository = repo, embedder = null)
            val results = service.retrieveContext("gizmo")

            assertThat(results).isNotEmpty()
            assertThat(results.all { RecallSource.VECTOR !in it.recalledBy }).isTrue()
        }

    @Test
    fun `an embedder present adds vector recall provenance`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            val embedder = loadedEmbedder()
            val text = "a widget is a small configurable component"
            val doc = seedNote(repo, index, title = "Widget note", body = text, chunkText = text)
            index.putEmbeddings(listOf(doc.chunkId to embedder.embedDocuments(listOf(text)).single()))

            val service = RetrievalServiceImpl(index = index, repository = repo, embedder = embedder)
            val results = service.retrieveContext(text)

            assertThat(results).isNotEmpty()
            assertThat(results.any { RecallSource.VECTOR in it.recalledBy }).isTrue()
        }

    // ------------------------------------------------------------------
    // Hard per-source degradation (plan `E5.I13`: "Hard timeout 2 s per
    // recall source (returns what is available)"). Bead AC: "degradation
    // when the vector recall throws".
    // ------------------------------------------------------------------

    @Test
    fun `a vector recall that throws degrades to lexical plus graph results`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            seedNote(repo, index, title = "Sprocket note", body = "a sprocket meshes with a chain")

            val service =
                RetrievalServiceImpl(index = index, repository = repo, embedder = ThrowingEmbedderService())
            val results = service.retrieveContext("sprocket")

            assertThat(results).isNotEmpty()
            assertThat(results.first().recalledBy).containsExactly(RecallSource.LEXICAL)
        }

    @Test
    fun `a vector recall that exceeds the hard timeout degrades to lexical plus graph results`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            seedNote(repo, index, title = "Flywheel note", body = "a flywheel stores rotational energy")

            val service =
                RetrievalServiceImpl(
                    index = index,
                    repository = repo,
                    embedder = SlowEmbedderService(delayMillis = 500L),
                    recallTimeoutMillis = 30L,
                )
            val results = service.retrieveContext("flywheel")

            assertThat(results).isNotEmpty()
            assertThat(results.first().recalledBy).containsExactly(RecallSource.LEXICAL)
        }

    // ------------------------------------------------------------------
    // Persona filter — `PprRanker`'s rule (persona-less documents are
    // shared; a persona-scoped document is visible only under its own
    // persona), forwarded unchanged by `RetrievalServiceImpl`.
    // ------------------------------------------------------------------

    @Test
    fun `persona filter admits shared documents and excludes another persona's document`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            val shared = seedNote(repo, index, title = "Shared widget", body = "widget shared across personas")
            val private =
                seedNote(
                    repo,
                    index,
                    title = "Alpha widget",
                    body = "widget private to persona alpha",
                    personaId = "alpha",
                )

            val service = RetrievalServiceImpl(index = index, repository = repo, embedder = null)

            val underNoPersona = service.retrieveContext("widget", personaId = null)
            assertThat(underNoPersona.map { it.docId }).containsExactly(shared.docId)

            val underOtherPersona = service.retrieveContext("widget", personaId = "beta")
            assertThat(underOtherPersona.map { it.docId }).containsExactly(shared.docId)

            val underOwningPersona = service.retrieveContext("widget", personaId = "alpha")
            assertThat(underOwningPersona.map { it.docId }).containsAtLeast(shared.docId, private.docId)
        }

    // ------------------------------------------------------------------
    // `recalledBy` provenance — bead AC: "recalledBy sets correct".
    // ------------------------------------------------------------------

    @Test
    fun `recalledBy is exactly LEXICAL for a chunk only a lexical match finds`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            seedNote(repo, index, title = "Marker", body = "zzz-filler marker-only-term appears here")

            val service = RetrievalServiceImpl(index = index, repository = repo, embedder = null)
            val results = service.retrieveContext("marker-only-term")

            assertThat(results).isNotEmpty()
            assertThat(results.first().recalledBy).containsExactly(RecallSource.LEXICAL)
        }

    @Test
    fun `recalledBy includes GRAPH for a document whose title seeds the graph search`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            seedNote(repo, index, title = "Marker", body = "zzz-filler marker-only-term appears here")

            val service = RetrievalServiceImpl(index = index, repository = repo, embedder = null)
            // "Marker" is capitalized, so `QueryNgrams` yields it as a
            // candidate title seed and `GraphRecall` finds the document's
            // own chunk at hop 0 — the same chunk BM25 also matches on the
            // substring "marker", so this exercises a multi-source union.
            val results = service.retrieveContext("Marker")

            assertThat(results).isNotEmpty()
            assertThat(results.first().recalledBy).containsAtLeast(RecallSource.LEXICAL, RecallSource.GRAPH)
        }

    // ------------------------------------------------------------------
    // Determinism.
    // ------------------------------------------------------------------

    @Test
    fun `repeated calls with the same arguments return an identical list`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            seedNote(repo, index, title = "Doohickey note", body = "a doohickey is any small device")

            val service = RetrievalServiceImpl(index = index, repository = repo, embedder = null)
            val first = service.retrieveContext("doohickey")
            val second = service.retrieveContext("doohickey")

            assertThat(second.map { it.chunkId }).isEqualTo(first.map { it.chunkId })
            assertThat(second.map { it.score }).isEqualTo(first.map { it.score })
        }

    // ------------------------------------------------------------------

    private class SeededNote(
        val docId: String,
        val chunkId: Long,
    )

    private suspend fun seedNote(
        repo: InMemoryVaultRepository,
        index: InMemoryIndexStore,
        title: String,
        body: String,
        chunkText: String = body,
        personaId: String? = null,
    ): SeededNote {
        val document =
            repo.createDocument(
                NewDocument(kind = DocumentKind.NOTE, title = title, bodyMd = body, personaId = personaId),
            )
        val chunkId =
            index
                .replaceChunks(
                    docId = document.id,
                    chunks = listOf(NewChunk(ord = 0, text = chunkText, tokenCount = chunkText.split(" ").size)),
                    embedderId = "fake-embedder",
                    embedderVersion = 1,
                ).single()
        return SeededNote(docId = document.id, chunkId = chunkId)
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

    /** Throws from [embedQuery] only — the one method `VectorRecall` calls. */
    private class ThrowingEmbedderService : EmbedderService {
        override suspend fun load(
            embed: Model,
            ner: Model?,
            rerank: Model?,
        ): Result<Unit> = Result.success(Unit)

        override suspend fun embedDocuments(texts: List<String>): List<ByteArray> =
            throw UnsupportedOperationException("not used by this test")

        override suspend fun embedQuery(text: String): ByteArray = error("simulated embedder failure")

        override suspend fun extractEntities(
            text: String,
            labels: List<String>,
        ): List<EntitySpan> = throw UnsupportedOperationException("not used by this test")

        override suspend fun rerank(
            query: String,
            candidates: List<String>,
        ): FloatArray = throw UnsupportedOperationException("not used by this test")

        override suspend fun countTokens(text: String): Int = throw UnsupportedOperationException("not used")

        override suspend fun unload() = Unit

        override val embedderId: String = "throwing-fake"
        override val embedderVersion: Int = 1
    }

    /** Delays past a short [recallTimeoutMillis] before returning from [embedQuery]. */
    private class SlowEmbedderService(
        private val delayMillis: Long,
    ) : EmbedderService {
        override suspend fun load(
            embed: Model,
            ner: Model?,
            rerank: Model?,
        ): Result<Unit> = Result.success(Unit)

        override suspend fun embedDocuments(texts: List<String>): List<ByteArray> =
            throw UnsupportedOperationException("not used by this test")

        override suspend fun embedQuery(text: String): ByteArray {
            delay(delayMillis)
            return ByteArray(EMBED_DIM)
        }

        override suspend fun extractEntities(
            text: String,
            labels: List<String>,
        ): List<EntitySpan> = throw UnsupportedOperationException("not used by this test")

        override suspend fun rerank(
            query: String,
            candidates: List<String>,
        ): FloatArray = throw UnsupportedOperationException("not used by this test")

        override suspend fun countTokens(text: String): Int = throw UnsupportedOperationException("not used")

        override suspend fun unload() = Unit

        override val embedderId: String = "slow-fake"
        override val embedderVersion: Int = 1

        private companion object {
            const val EMBED_DIM = 256
        }
    }
}
