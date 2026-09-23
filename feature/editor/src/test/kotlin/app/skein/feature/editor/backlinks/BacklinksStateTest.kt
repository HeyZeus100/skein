package app.skein.feature.editor.backlinks

import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.Edge
import app.skein.core.model.EdgeKind
import app.skein.core.model.IndexStore
import app.skein.core.model.NewChunk
import app.skein.core.model.NewDocument
import app.skein.core.model.VaultRepository
import app.skein.testing.InMemoryIndexStore
import app.skein.testing.InMemoryVaultRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * Drives [BacklinksState] directly — Compose-off — under `runTest`, same
 * shape as `:feature:timeline`'s `TimelineStateTest`. Edges are hand-built
 * with [IndexStore.replaceEdges]/[IndexStore.replaceChunks] rather than run
 * through the real `EdgeUpserter`/wikilink extractor pipeline (`core/vault`,
 * bd `skein-ys2`) — this module has no dependency on `:core:vault` (see
 * `BacklinksState`'s file header) — but the *documents* come from
 * `SyntheticVault`-style bodies containing literal `[[Title]]` wikilink
 * text, so the excerpt-search assertions exercise the same string shape a
 * real indexed vault would produce.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BacklinksStateTest {
    @Test
    fun `a document with no incoming wikilinks yields an empty list`() =
        runTest {
            val repo = newRepo()
            val index = InMemoryIndexStore()
            val target = repo.note("Lonely Note")
            val state = BacklinksState(target.id, repo, index, backgroundScope)

            subscribe(state)

            assertTrue(state.backlinks.value.isEmpty())
        }

    @Test
    fun `two notes linking to the same target produce two backlink groups with snippets`() =
        runTest {
            val repo = newRepo()
            val index = InMemoryIndexStore()
            val target = repo.note("Target Note")
            val sourceOne = repo.note("Source One", body = "Intro.\n\nSee also [[Target Note]].")
            val sourceTwo = repo.note("Source Two", body = "Different intro.\n\nRelated: [[Target Note]] again.")
            index.link(sourceOne, target.id)
            index.link(sourceTwo, target.id)
            val state = BacklinksState(target.id, repo, index, backgroundScope)

            subscribe(state)

            val groups = state.backlinks.value
            assertEquals(2, groups.size)
            assertEquals(setOf(sourceOne.id, sourceTwo.id), groups.map { it.document.id }.toSet())
            groups.forEach { group ->
                assertTrue(
                    "excerpt must contain the link text, was: ${group.excerpt}",
                    group.excerpt.contains("[[Target Note]]", ignoreCase = true),
                )
            }
        }

    @Test
    fun `a wikilink recorded under the unresolved title sentinel surfaces once the target document exists`() =
        runTest {
            val repo = newRepo()
            val index = InMemoryIndexStore()
            // Indexed before "Future Note" exists — EdgeUpserter (core/vault,
            // bd skein-ys2) would have written the unresolved sentinel
            // (`title:<lowercased target>`) as dst_id in this situation.
            val source = repo.note("Early Linker", body = "Coming soon: [[Future Note]].")
            index.link(source, BacklinksState.titleSentinel("Future Note"))

            val target = repo.note("Future Note")
            val state = BacklinksState(target.id, repo, index, backgroundScope)

            subscribe(state)

            val groups = state.backlinks.value
            assertEquals(listOf(source.id), groups.map { it.document.id })
            assertTrue(groups.single().excerpt.contains("[[Future Note]]", ignoreCase = true))
        }

    @Test
    fun `switching documents re-queries backlinks for the new target`() =
        runTest {
            val repo = newRepo()
            val index = InMemoryIndexStore()
            val targetA = repo.note("Target A")
            val targetB = repo.note("Target B")
            val sourceA = repo.note("Source A", body = "See also [[Target A]].")
            val sourceB = repo.note("Source B", body = "See also [[Target B]].")
            index.link(sourceA, targetA.id)
            index.link(sourceB, targetB.id)
            val state = BacklinksState(targetA.id, repo, index, backgroundScope)
            subscribe(state)
            assertEquals(listOf(sourceA.id), state.backlinks.value.map { it.document.id })

            state.switchDocument(targetB.id)
            runCurrent()

            assertEquals(listOf(sourceB.id), state.backlinks.value.map { it.document.id })
            assertEquals(targetB.id, state.docId)
        }

    @Test
    fun `adding a link elsewhere updates the drawer without switching documents`() =
        runTest {
            val repo = newRepo()
            val index = InMemoryIndexStore()
            val target = repo.note("Watched Note")
            val state = BacklinksState(target.id, repo, index, backgroundScope)
            subscribe(state)
            assertTrue(state.backlinks.value.isEmpty())

            val source = repo.note("New Linker", body = "See also [[Watched Note]].")
            index.link(source, target.id)
            runCurrent()

            assertEquals(listOf(source.id), state.backlinks.value.map { it.document.id })
        }

    @Test
    fun `an edge re-index with no accompanying document write still refreshes the drawer`() =
        runTest {
            // The skein-9jj gap, now closed by IndexStore.observeChanges
            // (bd skein-rkxi): a background IngestWorker commits the edge
            // long after the document write that triggered it, so no
            // observeTimeline tick follows the edge write. Both documents
            // therefore already exist *before* the drawer subscribes —
            // nothing here touches `documents` afterwards.
            val repo = newRepo()
            val index = InMemoryIndexStore()
            val target = repo.note("Watched Note")
            val source = repo.note("Deferred Linker", body = "See also [[Watched Note]].")
            val state = BacklinksState(target.id, repo, index, backgroundScope)
            subscribe(state)
            assertTrue(state.backlinks.value.isEmpty())

            index.link(source, target.id)
            runCurrent()

            assertEquals(listOf(source.id), state.backlinks.value.map { it.document.id })
        }

    @Test
    fun `an edge re-index of a kind the drawer does not care about leaves the rows alone`() =
        runTest {
            val repo = newRepo()
            val index = InMemoryIndexStore()
            val target = repo.note("Watched Note")
            val source = repo.note("Linker", body = "See also [[Watched Note]].")
            index.link(source, target.id)
            val state = BacklinksState(target.id, repo, index, backgroundScope)
            subscribe(state)
            val before = state.backlinks.value

            // An ENTITY-kind rewrite is not a wikilink re-index, so it is
            // filtered out — the drawer must not churn on every ingest.
            index.replaceEdges(
                srcId = source.id,
                kinds = setOf(EdgeKind.ENTITY),
                edges = listOf(Edge(srcId = source.id, dstId = "entity:1", kind = EdgeKind.ENTITY, createdAt = 0L)),
            )
            runCurrent()

            assertEquals(before.map { it.document.id }, state.backlinks.value.map { it.document.id })
            assertEquals(listOf(source.id), state.backlinks.value.map { it.document.id })
        }

    @Test
    fun `tapping a row routes the source document id through onOpen`() =
        runTest {
            val repo = newRepo()
            val index = InMemoryIndexStore()
            val target = repo.note("Target Note")
            val source = repo.note("Source One", body = "See also [[Target Note]].")
            index.link(source, target.id)
            var opened: String? = null
            val state = BacklinksState(target.id, repo, index, backgroundScope, onOpen = { opened = it })
            subscribe(state)

            val onlyRow = state.backlinks.value.single()
            state.open(onlyRow.document)

            assertEquals(source.id, opened)
        }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun newRepo(): InMemoryVaultRepository {
        val clock = AtomicLong(BASE_MILLIS)
        return InMemoryVaultRepository(clock = { clock.getAndAdd(1_000L) })
    }

    /** Subscribes [BacklinksState.backlinks], then settles the scheduler — mirrors `TimelineStateTest.subscribe`. */
    private fun TestScope.subscribe(state: BacklinksState) {
        backgroundScope.launch { state.backlinks.collect {} }
        runCurrent()
    }

    private suspend fun VaultRepository.note(
        title: String,
        body: String = "body of $title",
    ): Document =
        createDocument(
            NewDocument(kind = DocumentKind.NOTE, title = title, bodyMd = body),
        )

    /**
     * Mimics the observable effect of `EdgeUpserter.upsert` for one
     * document: a single chunk holding the full body (so
     * `chunksForDocs`/excerpt search has something to scan) plus a
     * `WIKILINK` edge from [source] to [dstId].
     */
    private suspend fun IndexStore.link(
        source: Document,
        dstId: String,
    ) {
        val body = source.bodyMd.orEmpty()
        replaceChunks(
            docId = source.id,
            chunks = listOf(NewChunk(ord = 0, text = body, tokenCount = body.length)),
            embedderId = "test-embedder",
            embedderVersion = 1,
        )
        replaceEdges(
            srcId = source.id,
            kinds = setOf(EdgeKind.WIKILINK),
            edges = listOf(Edge(srcId = source.id, dstId = dstId, kind = EdgeKind.WIKILINK, createdAt = 0L)),
        )
    }

    private companion object {
        const val BASE_MILLIS = 1_700_000_000_000L
    }
}
