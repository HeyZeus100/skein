package app.skein.core.rag.recall

import app.skein.core.model.Chunk
import app.skein.core.model.ChunkId
import app.skein.core.model.DocId
import app.skein.core.model.DocumentKind
import app.skein.core.model.Edge
import app.skein.core.model.EdgeKind
import app.skein.core.model.IndexStore
import app.skein.core.model.NewChunk
import app.skein.core.model.NewDocument
import app.skein.core.model.VaultRepository
import app.skein.testing.InMemoryIndexStore
import app.skein.testing.InMemoryVaultRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * `GraphRecall` (skein-7tw, plan `E5.I11`) tests. Fixtures are hand-built
 * directly against `InMemoryIndexStore` / `InMemoryVaultRepository` (`:testing`)
 * rather than `SyntheticVault` — the plan's own text for this bead: "Entity
 * rows are inserted directly by the tests here", and the same applies to the
 * `edges` this recall stage walks (`SyntheticVault` only writes wikilink
 * *text*, not materialized `Edge` rows — see its file header).
 */
class GraphRecallTest {
    @Test
    fun `A to B to C chain scores by hop distance from the query's title seed`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()

            val a = createDoc(repo, "Alpha")
            val b = createDoc(repo, "Bravo")
            val c = createDoc(repo, "Charlie")
            link(index, a, b)
            link(index, b, c)
            val chunkA = addChunk(index, a, "alpha body")
            val chunkB = addChunk(index, b, "bravo body")
            val chunkC = addChunk(index, c, "charlie body")

            val results = GraphRecall(index, repo).recall("tell me about Alpha")

            val byChunk = results.associateBy { it.chunkId }
            assertThat(byChunk.keys).containsExactly(chunkA, chunkB, chunkC)
            assertThat(byChunk.getValue(chunkA).score).isWithin(1e-9).of(1.0)
            assertThat(byChunk.getValue(chunkB).score).isWithin(1e-9).of(0.5)
            assertThat(byChunk.getValue(chunkC).score).isWithin(1e-9).of(1.0 / 3.0)
        }

    @Test
    fun `results are ordered by descending score`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()

            val a = createDoc(repo, "Alpha")
            val b = createDoc(repo, "Bravo")
            val c = createDoc(repo, "Charlie")
            link(index, a, b)
            link(index, b, c)
            addChunk(index, a, "alpha body")
            addChunk(index, b, "bravo body")
            addChunk(index, c, "charlie body")

            val results = GraphRecall(index, repo).recall("Alpha")
            val scores = results.map { it.score }

            assertThat(scores).isEqualTo(scores.sortedDescending())
        }

    @Test
    fun `maxNodes caps how far the neighborhood expands`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()

            val a = createDoc(repo, "Alpha")
            val b = createDoc(repo, "Bravo")
            val c = createDoc(repo, "Charlie")
            val d = createDoc(repo, "Delta")
            link(index, a, b)
            link(index, b, c)
            link(index, c, d)
            addChunk(index, a, "alpha body")
            addChunk(index, b, "bravo body")
            addChunk(index, c, "charlie body")
            addChunk(index, d, "delta body")

            // maxNodes = 2: only the seed (Alpha) plus one hop (Bravo) fit.
            val recall = GraphRecall(index, repo, hops = 2, maxNodes = 2)
            val results = recall.recall("Alpha")

            val docsSeen = results.map { docIdOf(index, it.chunkId) }.toSet()
            assertThat(docsSeen).containsExactly(a, b)
        }

    @Test
    fun `unresolved wikilink sentinel nodes are excluded, not treated as documents`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()

            val a = createDoc(repo, "Alpha")
            addChunk(index, a, "alpha body")
            // A links to a title with no matching document yet — the
            // `EdgeUpserter` convention (`core/vault/.../extract/EdgeUpserter.kt`):
            // dst_id = "title:<lowercased target>", weight 0.5.
            index.replaceEdges(
                a,
                setOf(EdgeKind.WIKILINK),
                listOf(
                    Edge(srcId = a, dstId = "title:ghost note", kind = EdgeKind.WIKILINK, weight = 0.5, createdAt = 0L),
                ),
            )

            val results = GraphRecall(index, repo).recall("Alpha")

            assertThat(results).hasSize(1)
            assertThat(results.single().score).isWithin(1e-9).of(1.0)
        }

    @Test
    fun `entity seed reaches its linked document at one hop`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()

            val a = createDoc(repo, "Some Note")
            val chunkA = addChunk(index, a, "a note about the org")
            val entity = index.upsertEntity("Skein Labs", "ORG", firstSeen = 0L)
            index.replaceEdges(
                a,
                setOf(EdgeKind.ENTITY),
                listOf(
                    Edge(srcId = a, dstId = "entity:${entity.id}", kind = EdgeKind.ENTITY, createdAt = 0L),
                ),
            )

            val results = GraphRecall(index, repo).recall("who works at Skein Labs")

            assertThat(results).hasSize(1)
            assertThat(results.single().chunkId).isEqualTo(chunkA)
            assertThat(results.single().score).isWithin(1e-9).of(0.5)
        }

    @Test
    fun `ties are broken deterministically by chunkId`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()

            val seed = createDoc(repo, "Alpha")
            val x = createDoc(repo, "Xray")
            val y = createDoc(repo, "Yankee")
            addChunk(index, seed, "alpha body")
            // X's chunk is inserted before Y's, so it gets the lower chunkId.
            val chunkX = addChunk(index, x, "xray body")
            val chunkY = addChunk(index, y, "yankee body")
            link(index, seed, x)
            link(index, seed, y)

            val results = GraphRecall(index, repo).recall("Alpha")
            val tied = results.filter { it.chunkId == chunkX || it.chunkId == chunkY }

            assertThat(tied.map { it.chunkId }).containsExactly(chunkX, chunkY).inOrder()
        }

    @Test
    fun `same query and vault state produce the same ordering on repeat calls`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()

            val a = createDoc(repo, "Alpha")
            val b = createDoc(repo, "Bravo")
            val c = createDoc(repo, "Charlie")
            link(index, a, b)
            link(index, b, c)
            addChunk(index, a, "alpha body")
            addChunk(index, b, "bravo body")
            addChunk(index, c, "charlie body")

            val recall = GraphRecall(index, repo)
            val first = recall.recall("Alpha")
            val second = recall.recall("Alpha")

            assertThat(second.map { it.chunkId }).isEqualTo(first.map { it.chunkId })
        }

    @Test
    fun `empty query returns empty result without touching the index`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = CountingIndexStore(InMemoryIndexStore())

            val results = GraphRecall(index, repo).recall("")

            assertThat(results).isEmpty()
            assertThat(index.neighborhoodCalls).isEqualTo(0)
            assertThat(index.chunksForDocsCalls).isEqualTo(0)
        }

    @Test
    fun `query with no matching entity or title returns empty quickly, no neighborhood query issued`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = CountingIndexStore(InMemoryIndexStore())
            createDoc(repo, "Alpha")

            val results = GraphRecall(index, repo).recall("Nothing Matches Here")

            assertThat(results).isEmpty()
            assertThat(index.neighborhoodCalls).isEqualTo(0)
            assertThat(index.chunksForDocsCalls).isEqualTo(0)
        }

    @Test
    fun `k caps the number of returned chunks`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()

            val a = createDoc(repo, "Alpha")
            val b = createDoc(repo, "Bravo")
            link(index, a, b)
            addChunk(index, a, "alpha one")
            addChunk(index, b, "bravo one")

            val results = GraphRecall(index, repo).recall("Alpha", k = 1)

            assertThat(results).hasSize(1)
            assertThat(results.single().score).isWithin(1e-9).of(1.0)
        }

    // ------------------------------------------------------------------
    // Fixture helpers
    // ------------------------------------------------------------------

    private suspend fun createDoc(
        repo: VaultRepository,
        title: String,
    ): DocId =
        repo
            .createDocument(
                NewDocument(kind = DocumentKind.NOTE, title = title, bodyMd = title),
            ).id

    private suspend fun link(
        index: IndexStore,
        from: DocId,
        to: DocId,
    ) {
        val existing = index.edgesFrom(from)
        index.replaceEdges(
            from,
            setOf(EdgeKind.WIKILINK),
            existing.filter { it.kind == EdgeKind.WIKILINK } +
                Edge(srcId = from, dstId = to, kind = EdgeKind.WIKILINK, createdAt = 0L),
        )
    }

    private suspend fun addChunk(
        index: IndexStore,
        docId: DocId,
        text: String,
    ): ChunkId =
        index
            .replaceChunks(
                docId,
                listOf(NewChunk(ord = 0, text = text, tokenCount = text.split(" ").size)),
                embedderId = "test-embedder",
                embedderVersion = 1,
            ).single()

    private suspend fun docIdOf(
        index: IndexStore,
        chunkId: ChunkId,
    ): DocId = index.getChunks(listOf(chunkId)).getValue(chunkId).docId

    /** Counts calls to the two operations the empty-seed fast path must never reach. */
    private class CountingIndexStore(
        private val delegate: IndexStore,
    ) : IndexStore by delegate {
        var neighborhoodCalls: Int = 0
            private set
        var chunksForDocsCalls: Int = 0
            private set

        override suspend fun neighborhood(
            seeds: Set<String>,
            hops: Int,
            maxNodes: Int,
        ): List<Edge> {
            neighborhoodCalls++
            return delegate.neighborhood(seeds, hops, maxNodes)
        }

        override suspend fun chunksForDocs(
            docIds: Collection<DocId>,
            limitPerDoc: Int,
        ): List<Chunk> {
            chunksForDocsCalls++
            return delegate.chunksForDocs(docIds, limitPerDoc)
        }
    }
}
