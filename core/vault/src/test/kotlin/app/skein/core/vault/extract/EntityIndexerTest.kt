// JVM unit tests for `EntityIndexer` (skein-aq6, E5.I9 acceptance criteria):
//   - canonicalization (whitespace/NFKC/casing) collapses mentions to one entity row
//   - display casing is the first (reading-order) occurrence's
//   - the 0.6 edge-creation threshold is stricter than GLiNER's own 0.5 decode threshold
//   - frequency weighting: 0.6 default, 0.8 at >= 3 mentions (still <= WIKILINK)
//   - other-kind edges on the same source are untouched
//   - re-indexing with fewer entities removes stale ENTITY edges
//   - idempotence and `createdAt` preservation across re-index
//
// Uses the `:testing` module's JVM fakes (`InMemoryVaultRepository`,
// `InMemoryIndexStore`) rather than the SQLCipher-backed implementations, per
// the bead's instructions.

package app.skein.core.vault.extract

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import us.aherrera.skein.core.model.DocumentKind
import us.aherrera.skein.core.model.Edge
import us.aherrera.skein.core.model.EdgeKind
import us.aherrera.skein.core.model.EntitySpan
import us.aherrera.skein.core.model.NewDocument
import us.aherrera.skein.testing.InMemoryIndexStore
import us.aherrera.skein.testing.InMemoryVaultRepository

public class EntityIndexerTest {
    private companion object {
        const val PERSON = "PERSON"
    }

    /** Builds an [EntitySpan] for the first occurrence of [mention] in [body], at [score]. */
    private fun spanFor(
        body: String,
        mention: String,
        label: String = PERSON,
        score: Float = 0.9f,
    ): EntitySpan {
        val start = body.indexOf(mention)
        check(start >= 0) { "'$mention' not found in body" }
        return EntitySpan(
            start = start,
            end = start + mention.length,
            text = mention,
            label = label,
            score = score,
        )
    }

    /** Builds one [EntitySpan] per occurrence of [mention] in [body]. */
    private fun allSpansFor(
        body: String,
        mention: String,
        label: String = PERSON,
        score: Float = 0.9f,
    ): List<EntitySpan> {
        val spans = mutableListOf<EntitySpan>()
        var from = 0
        while (true) {
            val start = body.indexOf(mention, from)
            if (start < 0) break
            spans +=
                EntitySpan(
                    start = start,
                    end = start + mention.length,
                    text = mention,
                    label = label,
                    score = score,
                )
            from = start + mention.length
        }
        return spans
    }

    @Test
    public fun `whitespace and casing variants of a name collapse to one entity`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            val indexer = EntityIndexer(index, clock = { 1_000L })
            val body = "Ada Lovelace was an analyst. ada  lovelace wrote the notes."
            val doc = repo.createDocument(NewDocument(DocumentKind.NOTE, "A", body))

            indexer.index(
                doc,
                listOf(
                    spanFor(body, "Ada Lovelace"),
                    spanFor(body, "ada  lovelace"),
                ),
            )

            val edges = index.edgesFrom(doc.id).filter { it.kind == EdgeKind.ENTITY }
            assertThat(edges).hasSize(1)
        }

    @Test
    public fun `NFKC-equivalent variants of a name collapse to one entity`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            val indexer = EntityIndexer(index, clock = { 1_000L })
            // U+FF21 FULLWIDTH LATIN CAPITAL LETTER A NFKC-normalizes to 'A'.
            val fullwidth = "Ａda Lovelace"
            val body = "Ada Lovelace founded it. $fullwidth visited later."
            val doc = repo.createDocument(NewDocument(DocumentKind.NOTE, "A", body))

            indexer.index(
                doc,
                listOf(
                    spanFor(body, "Ada Lovelace"),
                    spanFor(body, fullwidth),
                ),
            )

            val edges = index.edgesFrom(doc.id).filter { it.kind == EdgeKind.ENTITY }
            assertThat(edges).hasSize(1)
        }

    @Test
    public fun `display casing is taken from the first occurrence in reading order`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            val indexer = EntityIndexer(index, clock = { 1_000L })
            // The lowercase mention appears first in the body, even though it
            // is passed second in `spans`.
            val body = "ada  lovelace worked with Ada Lovelace on the engine."
            val doc = repo.createDocument(NewDocument(DocumentKind.NOTE, "A", body))

            indexer.index(
                doc,
                listOf(
                    spanFor(body, "Ada Lovelace"),
                    spanFor(body, "ada  lovelace"),
                ),
            )

            val entities = index.findEntitiesByName(listOf("ada lovelace"))
            assertThat(entities).hasSize(1)
            assertThat(entities.single().canonicalName).isEqualTo("ada lovelace")
        }

    @Test
    public fun `a span scoring below 0-6 creates no edge`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            val indexer = EntityIndexer(index, clock = { 1_000L })
            val body = "Foo appears here."
            val doc = repo.createDocument(NewDocument(DocumentKind.NOTE, "A", body))

            indexer.index(doc, listOf(spanFor(body, "Foo", score = 0.59f)))

            assertThat(index.edgesFrom(doc.id)).isEmpty()
            assertThat(index.findEntitiesByName(listOf("foo"))).isEmpty()
        }

    @Test
    public fun `a span scoring exactly 0-6 creates an edge`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            val indexer = EntityIndexer(index, clock = { 1_000L })
            val body = "Bar appears here."
            val doc = repo.createDocument(NewDocument(DocumentKind.NOTE, "A", body))

            indexer.index(doc, listOf(spanFor(body, "Bar", score = 0.6f)))

            assertThat(index.edgesFrom(doc.id).filter { it.kind == EdgeKind.ENTITY }).hasSize(1)
        }

    @Test
    public fun `an entity mentioned fewer than 3 times gets the default 0-6 weight`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            val indexer = EntityIndexer(index, clock = { 1_000L })
            val body = "Grace Hopper spoke. Grace Hopper listened."
            val doc = repo.createDocument(NewDocument(DocumentKind.NOTE, "A", body))
            val mentions = allSpansFor(body, "Grace Hopper")
            check(mentions.size == 2)

            indexer.index(doc, mentions)

            val edge = index.edgesFrom(doc.id).single { it.kind == EdgeKind.ENTITY }
            assertThat(edge.weight).isEqualTo(EdgeKind.ENTITY.weight)
            assertThat(edge.weight).isEqualTo(0.6)
        }

    @Test
    public fun `an entity mentioned 3 or more times gets weight 0-8`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            val indexer = EntityIndexer(index, clock = { 1_000L })
            val body = "Grace Hopper spoke. Grace Hopper listened. Grace Hopper wrote code."
            val doc = repo.createDocument(NewDocument(DocumentKind.NOTE, "A", body))
            val mentions = allSpansFor(body, "Grace Hopper")
            check(mentions.size == 3)

            indexer.index(doc, mentions)

            val edge = index.edgesFrom(doc.id).single { it.kind == EdgeKind.ENTITY }
            assertThat(edge.weight).isEqualTo(0.8)
        }

    @Test
    public fun `weight 0-8 stays less than or equal to the WIKILINK default weight`() {
        assertThat(EntityIndexer.FREQUENT_ENTITY_WEIGHT).isAtMost(EdgeKind.WIKILINK.weight)
    }

    @Test
    public fun `indexing entities leaves a WIKILINK edge on the same source untouched`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            val indexer = EntityIndexer(index, clock = { 1_000L })
            val body = "Ada Lovelace wrote the first algorithm."
            val doc = repo.createDocument(NewDocument(DocumentKind.NOTE, "A", body))
            val wikilinkEdge =
                Edge(srcId = doc.id, dstId = "title:missing", kind = EdgeKind.WIKILINK, weight = 0.5, createdAt = 500L)
            index.replaceEdges(doc.id, setOf(EdgeKind.WIKILINK), listOf(wikilinkEdge))

            indexer.index(doc, listOf(spanFor(body, "Ada Lovelace")))

            assertThat(index.edgesFrom(doc.id)).contains(wikilinkEdge)
        }

    @Test
    public fun `indexing entities leaves a TAG edge on the same source untouched`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            val indexer = EntityIndexer(index, clock = { 1_000L })
            val body = "Ada Lovelace wrote the first algorithm."
            val doc = repo.createDocument(NewDocument(DocumentKind.NOTE, "A", body))
            val tagEdge = Edge(srcId = doc.id, dstId = "tag:history", kind = EdgeKind.TAG, createdAt = 500L)
            index.replaceEdges(doc.id, setOf(EdgeKind.TAG), listOf(tagEdge))

            indexer.index(doc, listOf(spanFor(body, "Ada Lovelace")))

            assertThat(index.edgesFrom(doc.id)).contains(tagEdge)
        }

    @Test
    public fun `re-indexing with fewer entities removes the stale ENTITY edge`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            val indexer = EntityIndexer(index, clock = { 1_000L })
            val body = "Ada Lovelace worked with Charles Babbage."
            val doc = repo.createDocument(NewDocument(DocumentKind.NOTE, "A", body))
            indexer.index(doc, listOf(spanFor(body, "Ada Lovelace"), spanFor(body, "Charles Babbage")))
            check(index.edgesFrom(doc.id).filter { it.kind == EdgeKind.ENTITY }.size == 2)

            indexer.index(doc, listOf(spanFor(body, "Ada Lovelace")))

            val entityEdges = index.edgesFrom(doc.id).filter { it.kind == EdgeKind.ENTITY }
            assertThat(entityEdges).hasSize(1)
            val remainingEntity = index.findEntitiesByName(listOf("ada lovelace")).single()
            assertThat(entityEdges.single().dstId).isEqualTo("entity:${remainingEntity.id}")
        }

    @Test
    public fun `re-indexing with fewer entities leaves an unrelated WIKILINK edge intact`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            val indexer = EntityIndexer(index, clock = { 1_000L })
            val body = "Ada Lovelace worked with Charles Babbage."
            val doc = repo.createDocument(NewDocument(DocumentKind.NOTE, "A", body))
            val wikilinkEdge = Edge(srcId = doc.id, dstId = "title:engine", kind = EdgeKind.WIKILINK, createdAt = 500L)
            index.replaceEdges(doc.id, setOf(EdgeKind.WIKILINK), listOf(wikilinkEdge))
            indexer.index(doc, listOf(spanFor(body, "Ada Lovelace"), spanFor(body, "Charles Babbage")))

            indexer.index(doc, listOf(spanFor(body, "Ada Lovelace")))

            assertThat(index.edgesFrom(doc.id)).contains(wikilinkEdge)
        }

    @Test
    public fun `re-indexing with identical spans is idempotent`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            val indexer = EntityIndexer(index, clock = { 1_000L })
            val body = "Ada Lovelace worked with Charles Babbage."
            val doc = repo.createDocument(NewDocument(DocumentKind.NOTE, "A", body))
            val spans = listOf(spanFor(body, "Ada Lovelace"), spanFor(body, "Charles Babbage"))

            indexer.index(doc, spans)
            val firstPass = index.edgesFrom(doc.id).filter { it.kind == EdgeKind.ENTITY }.toSet()
            indexer.index(doc, spans)
            val secondPass = index.edgesFrom(doc.id).filter { it.kind == EdgeKind.ENTITY }.toSet()

            assertThat(secondPass).isEqualTo(firstPass)
        }

    @Test
    public fun `re-indexing an unchanged entity preserves its edge's original createdAt`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            var now = 1_000L
            val indexer = EntityIndexer(index, clock = { now })
            val body = "Ada Lovelace worked with Charles Babbage."
            val doc = repo.createDocument(NewDocument(DocumentKind.NOTE, "A", body))
            val spans = listOf(spanFor(body, "Ada Lovelace"), spanFor(body, "Charles Babbage"))
            indexer.index(doc, spans)

            now = 2_000L
            indexer.index(doc, spans)

            val entityEdges = index.edgesFrom(doc.id).filter { it.kind == EdgeKind.ENTITY }
            assertThat(entityEdges).isNotEmpty()
            for (edge in entityEdges) {
                assertThat(edge.createdAt).isEqualTo(1_000L)
            }
        }

    @Test
    public fun `a weight change on re-index gets a fresh createdAt`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            var now = 1_000L
            val indexer = EntityIndexer(index, clock = { now })
            // The document body is fixed with 3 occurrences; the first pass
            // only feeds spans for the first 2, so re-indexing with the
            // third span is what flips the weight without changing the doc.
            val body = "Grace Hopper spoke. Grace Hopper listened. Grace Hopper coded."
            val doc = repo.createDocument(NewDocument(DocumentKind.NOTE, "A", body))
            val allMentions = allSpansFor(body, "Grace Hopper")
            check(allMentions.size == 3)
            indexer.index(doc, allMentions.take(2))
            check(index.edgesFrom(doc.id).single { it.kind == EdgeKind.ENTITY }.weight == 0.6)

            now = 2_000L
            indexer.index(doc, allMentions)

            val edge = index.edgesFrom(doc.id).single { it.kind == EdgeKind.ENTITY }
            assertThat(edge.weight).isEqualTo(0.8)
            assertThat(edge.createdAt).isEqualTo(2_000L)
        }

    @Test
    public fun `different labels for the same text produce distinct entities`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            val indexer = EntityIndexer(index, clock = { 1_000L })
            val body = "Washington is both a person and a place."
            val doc = repo.createDocument(NewDocument(DocumentKind.NOTE, "A", body))

            indexer.index(
                doc,
                listOf(
                    spanFor(body, "Washington", label = "PERSON"),
                    spanFor(body, "Washington", label = "LOCATION"),
                ),
            )

            val entityEdges = index.edgesFrom(doc.id).filter { it.kind == EdgeKind.ENTITY }
            assertThat(entityEdges).hasSize(2)
            assertThat(entityEdges.map { it.dstId }.toSet()).hasSize(2)
        }

    @Test
    public fun `entity edge ordering is deterministic across re-indexes`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            val indexer = EntityIndexer(index, clock = { 1_000L })
            val body = "Ada Lovelace worked with Charles Babbage."
            val doc = repo.createDocument(NewDocument(DocumentKind.NOTE, "A", body))
            val forward = listOf(spanFor(body, "Ada Lovelace"), spanFor(body, "Charles Babbage"))
            val reversed = forward.reversed()

            indexer.index(doc, forward)
            val order1 = index.edgesFrom(doc.id).filter { it.kind == EdgeKind.ENTITY }.map { it.dstId }
            indexer.index(doc, reversed)
            val order2 = index.edgesFrom(doc.id).filter { it.kind == EdgeKind.ENTITY }.map { it.dstId }

            assertThat(order2).isEqualTo(order1)
        }
}
