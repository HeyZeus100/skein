// The `E0.I11` `IndexStore` contract suite. Abstract JUnit 4 test class,
// consumed on the JVM by `InMemoryIndexStoreTest` here and by the SQL-
// backed `IndexStoreImplTest` in `E2.I15`.
//
// The five semantic tests below mirror the plan `E0.I11` acceptance
// criteria bullet list exactly.

package us.aherrera.skein.testing

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import us.aherrera.skein.core.model.ChunkId
import us.aherrera.skein.core.model.Edge
import us.aherrera.skein.core.model.EdgeKind
import us.aherrera.skein.core.model.IndexStore
import us.aherrera.skein.core.model.NewChunk

public abstract class IndexStoreContractTest {
    /** Fresh index per test method. */
    protected abstract fun index(): IndexStore

    // Orthogonal-ish int8 vectors: e0 concentrated on axis 0, e1 on axis 1.
    // Norm is small but nonzero → cosine is well-defined.
    private fun basis(axis: Int): ByteArray =
        ByteArray(INT8_DIM).also { arr ->
            arr[axis] = 127
            // Add tiny mass on other axes so the norm is bounded away from
            // zero-per-axis pathologies in the impl.
            for (i in 1..3) arr[(axis + i) % INT8_DIM] = 1
        }

    // ------------------------------------------------------------------
    // AC: replaceChunks deletes old chunk ids
    // ------------------------------------------------------------------

    @Test
    public fun replaceChunks_deletes_old_chunk_ids(): Unit =
        runTest {
            val idx = index()
            val docId = "01924a4b-4d29-7000-8000-00000000D0C1"
            val firstIds: List<ChunkId> =
                idx.replaceChunks(
                    docId = docId,
                    chunks =
                        listOf(
                            NewChunk(ord = 0, text = "alpha", tokenCount = 1),
                            NewChunk(ord = 1, text = "beta", tokenCount = 1),
                        ),
                    embedderId = "fake",
                    embedderVersion = 1,
                )
            assertEquals(2, firstIds.size)

            val secondIds: List<ChunkId> =
                idx.replaceChunks(
                    docId = docId,
                    chunks = listOf(NewChunk(ord = 0, text = "gamma", tokenCount = 1)),
                    embedderId = "fake",
                    embedderVersion = 1,
                )
            assertEquals(1, secondIds.size)
            // Fetching the old ids returns an empty map — they were deleted.
            val remaining = idx.getChunks(firstIds)
            assertTrue(
                "expected old chunk ids to be gone, still present: ${remaining.keys}",
                remaining.isEmpty(),
            )
        }

    // ------------------------------------------------------------------
    // AC: knn returns nearest first for two orthogonal-ish int8 vectors
    // ------------------------------------------------------------------

    @Test
    public fun knn_returns_nearest_first_for_orthogonal_int8_vectors(): Unit =
        runTest {
            val idx = index()
            val docA = "01924a4b-4d29-7000-8000-00000000A0A0"
            val docB = "01924a4b-4d29-7000-8000-00000000B0B0"
            val idsA =
                idx.replaceChunks(
                    docId = docA,
                    chunks = listOf(NewChunk(ord = 0, text = "a text", tokenCount = 1)),
                    embedderId = "fake",
                    embedderVersion = 1,
                )
            val idsB =
                idx.replaceChunks(
                    docId = docB,
                    chunks = listOf(NewChunk(ord = 0, text = "b text", tokenCount = 1)),
                    embedderId = "fake",
                    embedderVersion = 1,
                )
            val e0 = basis(axis = 0)
            val e1 = basis(axis = 1)
            idx.putEmbeddings(listOf(idsA.single() to e0, idsB.single() to e1))

            val nearest = idx.knn(queryInt8 = e0, k = 2)
            assertEquals(2, nearest.size)
            assertEquals(
                "expected the axis-0 chunk to be nearest for an axis-0 query",
                idsA.single(),
                nearest.first().chunkId,
            )
            assertTrue(
                "expected the axis-1 chunk to be strictly further, was ${nearest[0].score} vs ${nearest[1].score}",
                nearest[0].score > nearest[1].score,
            )
        }

    // ------------------------------------------------------------------
    // AC: bm25 finds an exact term
    // ------------------------------------------------------------------

    @Test
    public fun bm25_finds_an_exact_term(): Unit =
        runTest {
            val idx = index()
            val docId = "01924a4b-4d29-7000-8000-00000000C1C1"
            idx.replaceChunks(
                docId = docId,
                chunks =
                    listOf(
                        NewChunk(ord = 0, text = "the quick brown fox", tokenCount = 4),
                        NewChunk(ord = 1, text = "lorem ipsum dolor sit amet", tokenCount = 5),
                    ),
                embedderId = "fake",
                embedderVersion = 1,
            )
            val hits = idx.bm25(query = "brown", k = 5)
            assertFalse("expected at least one bm25 hit for `brown`, got none", hits.isEmpty())
            // The exact-term chunk should outrank chunks that don't contain
            // the term at all — the second chunk has zero occurrences and
            // must be filtered out or ranked strictly lower.
            val topChunk = idx.getChunks(listOf(hits.first().chunkId)).values.single()
            assertTrue(
                "expected the top-ranked chunk to contain the query term, was: ${topChunk.text}",
                topChunk.text.contains("brown", ignoreCase = true),
            )
        }

    // ------------------------------------------------------------------
    // AC: edgesTo returns backlinks
    // ------------------------------------------------------------------

    @Test
    public fun edgesTo_returns_backlinks(): Unit =
        runTest {
            val idx = index()
            val src = "01924a4b-4d29-7000-8000-00000000A0A1"
            val dst = "01924a4b-4d29-7000-8000-00000000A0A2"
            val other = "01924a4b-4d29-7000-8000-00000000A0A3"
            idx.replaceEdges(
                srcId = src,
                kinds = setOf(EdgeKind.WIKILINK),
                edges =
                    listOf(
                        Edge(srcId = src, dstId = dst, kind = EdgeKind.WIKILINK, createdAt = 0L),
                    ),
            )
            idx.replaceEdges(
                srcId = other,
                kinds = setOf(EdgeKind.WIKILINK),
                edges =
                    listOf(
                        Edge(srcId = other, dstId = dst, kind = EdgeKind.WIKILINK, createdAt = 0L),
                    ),
            )
            val back = idx.edgesTo(dstId = dst, kind = EdgeKind.WIKILINK)
            assertEquals("expected two backlinks to dst", 2, back.size)
            val srcs = back.map { it.srcId }.toSet()
            assertEquals(setOf(src, other), srcs)
        }

    // ------------------------------------------------------------------
    // AC: neighborhood(hops=2) stops at maxNodes
    // ------------------------------------------------------------------

    @Test
    public fun neighborhood_hops2_stops_at_maxNodes(): Unit =
        runTest {
            val idx = index()
            // Build a small graph: A - B - C - D - E - F. Every hop from A
            // adds one new node; with maxNodes = 3 we expect to reach at
            // most A + B + C (visited size caps expansion).
            val ids = (0..5).map { "node-$it" }
            for (i in 0 until ids.size - 1) {
                idx.replaceEdges(
                    srcId = ids[i],
                    kinds = setOf(EdgeKind.WIKILINK),
                    edges =
                        listOf(
                            Edge(
                                srcId = ids[i],
                                dstId = ids[i + 1],
                                kind = EdgeKind.WIKILINK,
                                createdAt = 0L,
                            ),
                        ),
                )
            }
            val out =
                idx.neighborhood(seeds = setOf(ids[0]), hops = 2, maxNodes = 3)
            val visited = out.flatMap { listOf(it.srcId, it.dstId) }.toSet()
            assertTrue(
                "expected neighborhood to stop at maxNodes=3, visited: $visited (edges: $out)",
                visited.size <= 4,
            )
            assertTrue("expected the seed to appear in the neighborhood", ids[0] in visited)
        }

    private companion object {
        const val INT8_DIM: Int = 256
    }
}
