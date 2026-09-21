// JVM unit tests for `DanglingResolver` (skein-ax9.1, E5.I8b acceptance
// criteria):
//   - link-before-target: a sentinel wikilink resolves once its target
//     document is created
//   - idempotence: re-resolving (directly or via `resolveAll`) is a no-op
//   - other-kind edges on the same source are untouched
//   - a sentinel written against a document's post-retitle title resolves
//
// Uses the `:testing` module's JVM fakes (`InMemoryVaultRepository`,
// `InMemoryIndexStore`), matching `EdgeUpserterTest`.

package app.skein.core.vault.extract

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import us.aherrera.skein.core.model.DocumentKind
import us.aherrera.skein.core.model.Edge
import us.aherrera.skein.core.model.EdgeKind
import us.aherrera.skein.core.model.NewDocument
import us.aherrera.skein.testing.InMemoryIndexStore
import us.aherrera.skein.testing.InMemoryVaultRepository

public class DanglingResolverTest {
    @Test
    public fun `resolveFor rewrites a sentinel edge to the newly created document's id`() =
        runTest {
            val repo = InMemoryVaultRepository(clock = { 1_000L })
            val index = InMemoryIndexStore()
            val upserter = EdgeUpserter(repo, index, clock = { 1_000L })
            val resolver = DanglingResolver(repo, index)

            val docA = repo.createDocument(NewDocument(DocumentKind.NOTE, "A", "[[B]]"))
            upserter.upsert(docA.id, WikilinkExtractor.extract(docA.bodyMd!!), emptySet())
            check(index.edgesFrom(docA.id).single().dstId == "title:b")

            val docB = repo.createDocument(NewDocument(DocumentKind.NOTE, "B", "body"))
            resolver.resolveFor(docB)

            assertThat(index.edgesFrom(docA.id)).containsExactly(
                Edge(srcId = docA.id, dstId = docB.id, kind = EdgeKind.WIKILINK, createdAt = 1_000L),
            )
            assertThat(index.edgesTo(docB.id, EdgeKind.WIKILINK).map { it.srcId }).containsExactly(docA.id)
            assertThat(index.edgesTo("title:b", EdgeKind.WIKILINK)).isEmpty()
        }

    @Test
    public fun `resolveFor is a no-op when nothing targets the document's title`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            val resolver = DanglingResolver(repo, index)
            val docB = repo.createDocument(NewDocument(DocumentKind.NOTE, "B", "body"))

            resolver.resolveFor(docB)

            assertThat(index.edgesFrom(docB.id)).isEmpty()
        }

    @Test
    public fun `resolveFor is idempotent once a sentinel has already been resolved`() =
        runTest {
            val repo = InMemoryVaultRepository(clock = { 1_000L })
            val index = InMemoryIndexStore()
            val upserter = EdgeUpserter(repo, index, clock = { 1_000L })
            val resolver = DanglingResolver(repo, index)
            val docA = repo.createDocument(NewDocument(DocumentKind.NOTE, "A", "[[B]]"))
            upserter.upsert(docA.id, WikilinkExtractor.extract(docA.bodyMd!!), emptySet())
            val docB = repo.createDocument(NewDocument(DocumentKind.NOTE, "B", "body"))
            resolver.resolveFor(docB)
            val afterFirst = index.edgesFrom(docA.id)

            resolver.resolveFor(docB)

            assertThat(index.edgesFrom(docA.id)).isEqualTo(afterFirst)
        }

    @Test
    public fun `resolveFor leaves TAG and ENTITY edges on the same source untouched`() =
        runTest {
            val repo = InMemoryVaultRepository(clock = { 1_000L })
            val index = InMemoryIndexStore()
            val upserter = EdgeUpserter(repo, index, clock = { 1_000L })
            val resolver = DanglingResolver(repo, index)
            val docA = repo.createDocument(NewDocument(DocumentKind.NOTE, "A", "[[B]] #foo"))
            upserter.upsert(
                docA.id,
                WikilinkExtractor.extract(docA.bodyMd!!),
                TagExtractor.extract(docA.bodyMd!!),
            )
            val entityEdge = Edge(srcId = docA.id, dstId = "entity:1", kind = EdgeKind.ENTITY, createdAt = 500L)
            index.replaceEdges(docA.id, setOf(EdgeKind.ENTITY), listOf(entityEdge))

            val docB = repo.createDocument(NewDocument(DocumentKind.NOTE, "B", "body"))
            resolver.resolveFor(docB)

            val edges = index.edgesFrom(docA.id)
            assertThat(edges).contains(entityEdge)
            assertThat(edges.first { it.kind == EdgeKind.TAG }.dstId).isEqualTo("tag:foo")
            assertThat(edges.first { it.kind == EdgeKind.WIKILINK }.dstId).isEqualTo(docB.id)
        }

    @Test
    public fun `resolveFor resolves a sentinel written against a document's post-retitle title`() =
        runTest {
            val repo = InMemoryVaultRepository(clock = { 1_000L })
            val index = InMemoryIndexStore()
            val upserter = EdgeUpserter(repo, index, clock = { 1_000L })
            val resolver = DanglingResolver(repo, index)
            val docB = repo.createDocument(NewDocument(DocumentKind.NOTE, "Old Title", "body"))
            val docA = repo.createDocument(NewDocument(DocumentKind.NOTE, "A", "[[New Title]]"))
            upserter.upsert(docA.id, WikilinkExtractor.extract(docA.bodyMd!!), emptySet())
            check(index.edgesFrom(docA.id).single().dstId == "title:new title")

            val retitledB = repo.updateBody(docB.id, "New Title", "body")
            resolver.resolveFor(retitledB)

            assertThat(index.edgesFrom(docA.id)).containsExactly(
                Edge(srcId = docA.id, dstId = docB.id, kind = EdgeKind.WIKILINK, createdAt = 1_000L),
            )
        }

    @Test
    public fun `resolveAll resolves every dangling sentinel across the vault and is idempotent`() =
        runTest {
            val repo = InMemoryVaultRepository(clock = { 1_000L })
            val index = InMemoryIndexStore()
            val upserter = EdgeUpserter(repo, index, clock = { 1_000L })
            val resolver = DanglingResolver(repo, index)

            val docA = repo.createDocument(NewDocument(DocumentKind.NOTE, "A", "[[One]]"))
            val docC = repo.createDocument(NewDocument(DocumentKind.NOTE, "C", "[[Two]]"))
            upserter.upsert(docA.id, WikilinkExtractor.extract(docA.bodyMd!!), emptySet())
            upserter.upsert(docC.id, WikilinkExtractor.extract(docC.bodyMd!!), emptySet())

            val docOne = repo.createDocument(NewDocument(DocumentKind.NOTE, "One", "body"))
            val docTwo = repo.createDocument(NewDocument(DocumentKind.NOTE, "Two", "body"))

            resolver.resolveAll()

            assertThat(index.edgesFrom(docA.id).single().dstId).isEqualTo(docOne.id)
            assertThat(index.edgesFrom(docC.id).single().dstId).isEqualTo(docTwo.id)

            resolver.resolveAll()

            assertThat(index.edgesFrom(docA.id).single().dstId).isEqualTo(docOne.id)
            assertThat(index.edgesFrom(docC.id).single().dstId).isEqualTo(docTwo.id)
        }
}
