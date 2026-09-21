package app.skein.feature.graph

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import us.aherrera.skein.core.model.DocId
import us.aherrera.skein.core.model.DocumentKind
import us.aherrera.skein.core.model.Edge
import us.aherrera.skein.core.model.EdgeKind
import us.aherrera.skein.core.model.IndexStore
import us.aherrera.skein.core.model.NewDocument
import us.aherrera.skein.core.model.VaultRepository
import us.aherrera.skein.testing.InMemoryIndexStore
import us.aherrera.skein.testing.InMemoryVaultRepository

/**
 * `GraphState` (bd `skein-z2u`, plan `E6.I11`) tests. Fixtures are hand-built
 * directly against `InMemoryIndexStore`/`InMemoryVaultRepository` (`:testing`)
 * — same shape as `core/rag`'s `GraphRecallTest` ("Entity rows are inserted
 * directly by the tests here").
 *
 * Every test calls `runCurrent()` right after constructing a [GraphState]:
 * its `init` block launches the first load on `backgroundScope` rather than
 * running it synchronously, so the coroutine needs one scheduler pump
 * before `nodes`/`edges`/`positions`/`loading` reflect it — same pattern
 * `TimelineStateTest` documents for `TimelineState`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GraphStateTest {
    @Test
    fun `a center document with no edges still produces a one-node graph`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            val center = createDoc(repo, "Solo")

            val state = GraphState(docId = center, indexStore = index, vaultRepository = repo, scope = backgroundScope)
            runCurrent()

            assertEquals(listOf(center), state.nodes.value.map { it.id })
            assertTrue(state.edges.value.isEmpty())
            assertEquals(
                0,
                state.nodes.value
                    .single()
                    .hopDistance,
            )
            assertTrue(
                state.nodes.value
                    .single()
                    .isCenter,
            )
        }

    @Test
    fun `node and edge counts match the neighborhood IndexStore returns`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            val a = createDoc(repo, "Alpha")
            val b = createDoc(repo, "Bravo")
            val c = createDoc(repo, "Charlie")
            link(index, a, b)
            link(index, b, c)

            val state = GraphState(docId = a, indexStore = index, vaultRepository = repo, scope = backgroundScope)
            runCurrent()

            val expectedEdges = index.neighborhood(setOf(a), hops = 2, maxNodes = 80)
            assertEquals(expectedEdges.size, state.edges.value.size)
            val expectedNodeIds = expectedEdges.flatMap { listOf(it.srcId, it.dstId) }.toSet() + a
            assertEquals(
                expectedNodeIds,
                state.nodes.value
                    .map { it.id }
                    .toSet(),
            )
        }

    @Test
    fun `hop distance is measured by BFS from the center`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            val a = createDoc(repo, "Alpha")
            val b = createDoc(repo, "Bravo")
            val c = createDoc(repo, "Charlie")
            link(index, a, b)
            link(index, b, c)

            val state = GraphState(docId = a, indexStore = index, vaultRepository = repo, scope = backgroundScope)
            runCurrent()

            val byId = state.nodes.value.associateBy { it.id }
            assertEquals(0, byId.getValue(a).hopDistance)
            assertEquals(1, byId.getValue(b).hopDistance)
            assertEquals(2, byId.getValue(c).hopDistance)
        }

    @Test
    fun `document nodes are hydrated with their title and kind`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            val a = createDoc(repo, "Alpha", kind = DocumentKind.NOTE)
            val b = createDoc(repo, "Bravo", kind = DocumentKind.CHAT)
            link(index, a, b)

            val state = GraphState(docId = a, indexStore = index, vaultRepository = repo, scope = backgroundScope)
            runCurrent()

            val byId = state.nodes.value.associateBy { it.id }
            assertEquals("Alpha", byId.getValue(a).label)
            assertEquals(DocumentKind.NOTE, byId.getValue(a).documentKind)
            assertEquals("Bravo", byId.getValue(b).label)
            assertEquals(DocumentKind.CHAT, byId.getValue(b).documentKind)
            assertEquals(GraphNodeKind.DOCUMENT, byId.getValue(a).kind)
        }

    @Test
    fun `tag entity and unresolved-title sentinel nodes are kept, labeled, and typed`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            val a = createDoc(repo, "Alpha")
            val entity = index.upsertEntity("Skein Labs", "ORG", firstSeen = 0L)
            index.replaceEdges(
                a,
                setOf(EdgeKind.ENTITY, EdgeKind.TAG, EdgeKind.WIKILINK),
                listOf(
                    Edge(srcId = a, dstId = "entity:${entity.id}", kind = EdgeKind.ENTITY, createdAt = 0L),
                    Edge(srcId = a, dstId = "tag:project", kind = EdgeKind.TAG, createdAt = 0L),
                    Edge(srcId = a, dstId = "title:ghost note", kind = EdgeKind.WIKILINK, weight = 0.5, createdAt = 0L),
                ),
            )

            val state = GraphState(docId = a, indexStore = index, vaultRepository = repo, scope = backgroundScope)
            runCurrent()

            val byId = state.nodes.value.associateBy { it.id }
            assertEquals(4, state.nodes.value.size)

            val entityNode = byId.getValue("entity:${entity.id}")
            assertEquals(GraphNodeKind.ENTITY, entityNode.kind)
            assertEquals("Entity #${entity.id}", entityNode.label)
            assertNull(entityNode.documentKind)

            val tagNode = byId.getValue("tag:project")
            assertEquals(GraphNodeKind.TAG, tagNode.kind)
            assertEquals("#project", tagNode.label)

            val titleNode = byId.getValue("title:ghost note")
            assertEquals(GraphNodeKind.UNRESOLVED_TITLE, titleNode.kind)
            assertEquals("ghost note", titleNode.label)
        }

    @Test
    fun `maxNodes caps how far the graph expands`() =
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

            val state =
                GraphState(docId = a, indexStore = index, vaultRepository = repo, scope = backgroundScope, maxNodes = 2)
            runCurrent()

            assertEquals(
                setOf(a, b),
                state.nodes.value
                    .map { it.id }
                    .toSet(),
            )
        }

    @Test
    fun `positions are deterministic for a fixed seed across two separate states`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            val a = createDoc(repo, "Alpha")
            val b = createDoc(repo, "Bravo")
            link(index, a, b)

            val first =
                GraphState(docId = a, indexStore = index, vaultRepository = repo, scope = backgroundScope, seed = 99L)
            runCurrent()
            val second =
                GraphState(docId = a, indexStore = index, vaultRepository = repo, scope = backgroundScope, seed = 99L)
            runCurrent()

            assertEquals(first.positions.value, second.positions.value)
        }

    @Test
    fun `loading is true only until the first load completes`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            val a = createDoc(repo, "Alpha")

            val state = GraphState(docId = a, indexStore = index, vaultRepository = repo, scope = backgroundScope)
            assertTrue(state.loading.value)
            runCurrent()

            assertFalse(state.loading.value)
        }

    @Test
    fun `an EdgesReplaced index change triggers a live refresh`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            val a = createDoc(repo, "Alpha")
            val b = createDoc(repo, "Bravo")

            val state = GraphState(docId = a, indexStore = index, vaultRepository = repo, scope = backgroundScope)
            runCurrent()
            assertEquals(listOf(a), state.nodes.value.map { it.id })

            // A wikilink from Alpha to a brand-new Bravo is indexed after the
            // graph already loaded — `observeChanges()` should pick it up
            // without anybody re-creating the `GraphState`.
            link(index, a, b)
            runCurrent()

            assertEquals(
                setOf(a, b),
                state.nodes.value
                    .map { it.id }
                    .toSet(),
            )
        }

    // ------------------------------------------------------------------
    // Fixture helpers
    // ------------------------------------------------------------------

    private suspend fun createDoc(
        repo: VaultRepository,
        title: String,
        kind: DocumentKind = DocumentKind.NOTE,
    ): DocId = repo.createDocument(NewDocument(kind = kind, title = title, bodyMd = title)).id

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
}
