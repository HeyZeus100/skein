// JVM unit tests for `EdgeUpserter` (skein-ys2, E5.I8 acceptance criteria):
//   - create/update/delete round-trip of WIKILINK/TAG edges
//   - unresolved-wikilink handling (dst_id sentinel, retroactive resolution)
//   - ENTITY edges of the same source are untouched by a WIKILINK/TAG upsert
//
// Uses the `:testing` module's JVM fakes (`InMemoryVaultRepository`,
// `InMemoryIndexStore`) rather than the SQLCipher-backed implementations, per
// the bead's instructions.

package app.skein.core.vault.extract

import app.skein.core.model.DocumentKind
import app.skein.core.model.Edge
import app.skein.core.model.EdgeKind
import app.skein.core.model.NewDocument
import app.skein.testing.InMemoryIndexStore
import app.skein.testing.InMemoryVaultRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

public class EdgeUpserterTest {
    @Test
    public fun `wikilink to a nonexistent title is recorded as an unresolved edge`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            val upserter = EdgeUpserter(repo, index, clock = { 1_000L })
            val docA = repo.createDocument(NewDocument(DocumentKind.NOTE, "A", "[[Missing Note]]"))

            upserter.upsert(docA.id, WikilinkExtractor.extract(docA.bodyMd!!), emptySet())

            val edges = index.edgesFrom(docA.id)
            assertThat(edges).containsExactly(
                Edge(
                    srcId = docA.id,
                    dstId = "title:missing note",
                    kind = EdgeKind.WIKILINK,
                    weight = EdgeUpserter.UNRESOLVED_WIKILINK_WEIGHT,
                    createdAt = 1_000L,
                ),
            )
        }

    @Test
    public fun `wikilink to an existing title resolves to that document's id at default weight`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            val upserter = EdgeUpserter(repo, index, clock = { 1_000L })
            val docB = repo.createDocument(NewDocument(DocumentKind.NOTE, "B", "body"))
            val docA = repo.createDocument(NewDocument(DocumentKind.NOTE, "A", "[[B]]"))

            upserter.upsert(docA.id, WikilinkExtractor.extract(docA.bodyMd!!), emptySet())

            val edges = index.edgesFrom(docA.id)
            assertThat(edges).containsExactly(
                Edge(srcId = docA.id, dstId = docB.id, kind = EdgeKind.WIKILINK, createdAt = 1_000L),
            )
            assertThat(edges.single().weight).isEqualTo(EdgeKind.WIKILINK.weight)
        }

    @Test
    public fun `re-indexing after removing a link removes its edge`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            val upserter = EdgeUpserter(repo, index, clock = { 1_000L })
            val docA = repo.createDocument(NewDocument(DocumentKind.NOTE, "A", "[[B]]"))
            upserter.upsert(docA.id, WikilinkExtractor.extract(docA.bodyMd!!), emptySet())
            check(index.edgesFrom(docA.id).isNotEmpty())

            upserter.upsert(docA.id, WikilinkExtractor.extract("no more links"), emptySet())

            assertThat(index.edgesFrom(docA.id)).isEmpty()
        }

    @Test
    public fun `re-indexing an unchanged wikilink preserves the edge's original createdAt`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            var now = 1_000L
            val upserter = EdgeUpserter(repo, index, clock = { now })
            val docA = repo.createDocument(NewDocument(DocumentKind.NOTE, "A", "[[Missing]]"))

            upserter.upsert(docA.id, WikilinkExtractor.extract(docA.bodyMd!!), emptySet())
            now = 2_000L
            upserter.upsert(docA.id, WikilinkExtractor.extract(docA.bodyMd!!), emptySet())

            assertThat(index.edgesFrom(docA.id).single().createdAt).isEqualTo(1_000L)
        }

    @Test
    public fun `a newly added wikilink alongside an unchanged one gets a fresh createdAt`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            var now = 1_000L
            val upserter = EdgeUpserter(repo, index, clock = { now })
            val docA = repo.createDocument(NewDocument(DocumentKind.NOTE, "A", "[[First]]"))

            upserter.upsert(docA.id, WikilinkExtractor.extract(docA.bodyMd!!), emptySet())
            now = 2_000L
            upserter.upsert(docA.id, WikilinkExtractor.extract("[[First]] and [[Second]]"), emptySet())

            val byDst = index.edgesFrom(docA.id).associateBy { it.dstId }
            assertThat(byDst.getValue("title:first").createdAt).isEqualTo(1_000L)
            assertThat(byDst.getValue("title:second").createdAt).isEqualTo(2_000L)
        }

    @Test
    public fun `duplicate wikilinks to the same title collapse to a single edge`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            val upserter = EdgeUpserter(repo, index, clock = { 1_000L })
            val docA = repo.createDocument(NewDocument(DocumentKind.NOTE, "A", "[[X]] and [[X]] again"))

            upserter.upsert(docA.id, WikilinkExtractor.extract(docA.bodyMd!!), emptySet())

            assertThat(index.edgesFrom(docA.id)).hasSize(1)
        }

    @Test
    public fun `tags become TAG edges with the tag colon prefix dst id`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            val upserter = EdgeUpserter(repo, index, clock = { 1_000L })
            val docA = repo.createDocument(NewDocument(DocumentKind.NOTE, "A", "#foo"))

            upserter.upsert(docA.id, emptyList(), TagExtractor.extract(docA.bodyMd!!))

            assertThat(index.edgesFrom(docA.id)).containsExactly(
                Edge(srcId = docA.id, dstId = "tag:foo", kind = EdgeKind.TAG, createdAt = 1_000L),
            )
        }

    @Test
    public fun `removing a tag on re-index removes only that TAG edge`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            val upserter = EdgeUpserter(repo, index, clock = { 1_000L })
            val docA = repo.createDocument(NewDocument(DocumentKind.NOTE, "A", "#foo #bar"))
            upserter.upsert(docA.id, emptyList(), TagExtractor.extract(docA.bodyMd!!))

            upserter.upsert(docA.id, emptyList(), setOf("foo"))

            assertThat(index.edgesFrom(docA.id).map { it.dstId }).containsExactly("tag:foo")
        }

    @Test
    public fun `upserting WIKILINK and TAG edges leaves an ENTITY edge on the same source untouched`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            val upserter = EdgeUpserter(repo, index, clock = { 1_000L })
            val docA = repo.createDocument(NewDocument(DocumentKind.NOTE, "A", "[[B]] #foo"))
            val entityEdge =
                Edge(srcId = docA.id, dstId = "entity:1", kind = EdgeKind.ENTITY, createdAt = 500L)
            index.replaceEdges(docA.id, setOf(EdgeKind.ENTITY), listOf(entityEdge))

            upserter.upsert(
                docA.id,
                WikilinkExtractor.extract(docA.bodyMd!!),
                TagExtractor.extract(docA.bodyMd!!),
            )

            assertThat(index.edgesFrom(docA.id)).contains(entityEdge)
        }

    @Test
    public fun `upserting a whole document extracts body wikilinks and merges frontmatter tags`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            val upserter = EdgeUpserter(repo, index, clock = { 1_000L })
            val docB = repo.createDocument(NewDocument(DocumentKind.NOTE, "B", "body"))
            val frontmatter = JsonObject(mapOf("tags" to JsonArray(listOf(JsonPrimitive("Alpha")))))
            val docA =
                repo.createDocument(
                    NewDocument(DocumentKind.NOTE, "A", "[[b]] #beta", frontmatter = frontmatter),
                )

            upserter.upsert(docA)

            assertThat(index.edgesFrom(docA.id).map { it.dstId })
                .containsExactly(docB.id, "tag:alpha", "tag:beta")
        }

    @Test
    public fun `source frontmatter produces a CITE edge to the attachment id`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            val upserter = EdgeUpserter(repo, index, clock = { 1_000L })
            val attachment = repo.createAttachment("scan.pdf", "application/pdf") { it.write(ByteArray(0)) }
            val frontmatter = JsonObject(mapOf("source" to JsonPrimitive(attachment.id)))
            val docA = repo.createDocument(NewDocument(DocumentKind.NOTE, "A", "body", frontmatter = frontmatter))

            upserter.upsert(docA)

            assertThat(index.edgesFrom(docA.id)).contains(
                Edge(srcId = docA.id, dstId = attachment.id, kind = EdgeKind.CITE, createdAt = 1_000L),
            )
        }

    @Test
    public fun `re-upserting an unchanged source frontmatter preserves the CITE edge's original createdAt`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            var now = 1_000L
            val upserter = EdgeUpserter(repo, index, clock = { now })
            val attachment = repo.createAttachment("scan.pdf", "application/pdf") { it.write(ByteArray(0)) }
            val frontmatter = JsonObject(mapOf("source" to JsonPrimitive(attachment.id)))
            val docA = repo.createDocument(NewDocument(DocumentKind.NOTE, "A", "body", frontmatter = frontmatter))
            upserter.upsert(docA)

            now = 2_000L
            upserter.upsert(docA)

            val citeEdge = index.edgesFrom(docA.id).single { it.kind == EdgeKind.CITE }
            assertThat(citeEdge.createdAt).isEqualTo(1_000L)
        }

    @Test
    public fun `removing source frontmatter and re-upserting removes the CITE edge`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            val upserter = EdgeUpserter(repo, index, clock = { 1_000L })
            val attachment = repo.createAttachment("scan.pdf", "application/pdf") { it.write(ByteArray(0)) }
            val withSource = JsonObject(mapOf("source" to JsonPrimitive(attachment.id)))
            val docA = repo.createDocument(NewDocument(DocumentKind.NOTE, "A", "body", frontmatter = withSource))
            upserter.upsert(docA)
            check(index.edgesFrom(docA.id).any { it.kind == EdgeKind.CITE })

            val withoutSource = repo.updateFrontmatter(docA.id, JsonObject(emptyMap()))
            upserter.upsert(withoutSource)

            assertThat(index.edgesFrom(docA.id).none { it.kind == EdgeKind.CITE }).isTrue()
        }

    @Test
    public fun `upserting a CITE edge from source frontmatter leaves ENTITY edges untouched`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            val upserter = EdgeUpserter(repo, index, clock = { 1_000L })
            val attachment = repo.createAttachment("scan.pdf", "application/pdf") { it.write(ByteArray(0)) }
            val frontmatter = JsonObject(mapOf("source" to JsonPrimitive(attachment.id)))
            val docA = repo.createDocument(NewDocument(DocumentKind.NOTE, "A", "body", frontmatter = frontmatter))
            val entityEdge = Edge(srcId = docA.id, dstId = "entity:1", kind = EdgeKind.ENTITY, createdAt = 500L)
            index.replaceEdges(docA.id, setOf(EdgeKind.ENTITY), listOf(entityEdge))

            upserter.upsert(docA)

            assertThat(index.edgesFrom(docA.id)).contains(entityEdge)
        }
}
