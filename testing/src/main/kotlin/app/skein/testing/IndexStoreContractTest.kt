// The `E0.I11` `IndexStore` contract suite. Abstract JUnit 4 test class,
// consumed on the JVM by `InMemoryIndexStoreTest` here and by the SQL-
// backed `IndexStoreImplTest` in `E2.I15`.
//
// The five semantic tests below mirror the plan `E0.I11` acceptance
// criteria bullet list exactly. The `observeChanges` block after them
// covers the invalidation-stream contract added by bd `skein-rkxi` — see
// `IndexStore.observeChanges`'s kdoc for the guarantee being pinned here.
// The one clause this suite cannot cover portably is "nothing is emitted
// for rolled-back work": only a store with a real transaction can be made
// to roll back, so that lives in `IndexStoreImplAcceptanceTest`
// (`:core:vault` androidTest).

package app.skein.testing

import app.skein.core.model.ChunkId
import app.skein.core.model.DocId
import app.skein.core.model.Edge
import app.skein.core.model.EdgeKind
import app.skein.core.model.IndexChange
import app.skein.core.model.IndexStore
import app.skein.core.model.NewChunk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

public abstract class IndexStoreContractTest {
    /** Fresh index per test method. */
    protected abstract fun index(): IndexStore

    /**
     * Precondition helper (skein-ci54): creates a `documents` row for
     * [docId] against the same backing store [index] just opened, so a
     * subsequent `replaceChunks(docId, ...)` call satisfies
     * `chunks.doc_id REFERENCES documents(id) ON DELETE CASCADE`
     * (`001_initial.sql`) under `PRAGMA foreign_keys = ON` (skein-gg11.10 —
     * the pragma now runs on every connection, matching production, which
     * is exactly why the fixtures below can no longer skip this row the
     * way they used to). `IndexStore` itself has no `documents` table
     * access — a real ingest pass always creates the document (via
     * `VaultRepository`) before calling `IndexStore.replaceChunks` — so
     * each concrete subclass seeds the row through whatever backs its own
     * [index]: `IndexStoreImplContractTest` inserts directly on the SQL
     * connection it opened; `InMemoryIndexStoreTest` is a documented no-op
     * because `InMemoryIndexStore` has no `documents` table and never
     * enforced this key (see that class's override for why).
     *
     * Not needed before a `replaceEdges`/`edgesTo`/`neighborhood` call:
     * `edges` carries no foreign key on `src_id`/`dst_id` at all
     * (`001_initial.sql` — deliberately unenforced, per `003_document_
     * revisions.sql`'s header note on `edges.src_id`/`dst_id`), and a node
     * id there may be a bare document UUID, an `entity:<id>`, or a
     * `tag:<name>` (see `Edge`'s KDoc), so those calls need no parent row.
     */
    protected abstract fun seedDocument(docId: DocId): Unit

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
            seedDocument(docId)
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
            seedDocument(docA)
            seedDocument(docB)
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
    // AC (skein-g32i, migration 008): replaceChunks/getChunks round-trip
    // revision_hash/byte_start/byte_end
    // ------------------------------------------------------------------

    @Test
    public fun replaceChunks_stamps_revisionHash_and_byte_offsets_that_getChunks_reads_back(): Unit =
        runTest {
            val idx = index()
            val docId = "01924a4b-4d29-7000-8000-00000000F0F1"
            seedDocument(docId)

            val ids =
                idx.replaceChunks(
                    docId = docId,
                    chunks = listOf(NewChunk(ord = 0, text = "alpha", tokenCount = 1, byteStart = 3, byteEnd = 9)),
                    embedderId = "fake",
                    embedderVersion = 1,
                    revisionHash = "a".repeat(64),
                )

            val chunk = idx.getChunks(ids).getValue(ids.single())
            assertEquals("a".repeat(64), chunk.revisionHash)
            assertEquals(3, chunk.byteStart)
            assertEquals(9, chunk.byteEnd)
        }

    @Test
    public fun replaceChunks_with_no_revisionHash_or_offsets_reads_back_null_for_all_three(): Unit =
        runTest {
            val idx = index()
            val docId = "01924a4b-4d29-7000-8000-00000000F0F2"
            seedDocument(docId)

            val ids =
                idx.replaceChunks(
                    docId = docId,
                    chunks = listOf(NewChunk(ord = 0, text = "alpha", tokenCount = 1)),
                    embedderId = "fake",
                    embedderVersion = 1,
                )

            val chunk = idx.getChunks(ids).getValue(ids.single())
            assertEquals(null, chunk.revisionHash)
            assertEquals(null, chunk.byteStart)
            assertEquals(null, chunk.byteEnd)
        }

    @Test
    public fun chunksForDocs_also_reads_back_revisionHash_and_byte_offsets(): Unit =
        runTest {
            val idx = index()
            val docId = "01924a4b-4d29-7000-8000-00000000F0F3"
            seedDocument(docId)
            idx.replaceChunks(
                docId = docId,
                chunks = listOf(NewChunk(ord = 0, text = "alpha", tokenCount = 1, byteStart = 0, byteEnd = 5)),
                embedderId = "fake",
                embedderVersion = 1,
                revisionHash = "b".repeat(64),
            )

            val chunk = idx.chunksForDocs(setOf(docId), limitPerDoc = 10).single()
            assertEquals("b".repeat(64), chunk.revisionHash)
            assertEquals(0, chunk.byteStart)
            assertEquals(5, chunk.byteEnd)
        }

    // ------------------------------------------------------------------
    // AC: bm25 finds an exact term
    // ------------------------------------------------------------------

    @Test
    public fun bm25_finds_an_exact_term(): Unit =
        runTest {
            val idx = index()
            val docId = "01924a4b-4d29-7000-8000-00000000C1C1"
            seedDocument(docId)
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

    // ------------------------------------------------------------------
    // AC (skein-rkxi): observeChanges emits after the mutating call
    // ------------------------------------------------------------------

    @Test
    public fun observeChanges_emits_ChunksReplaced_by_the_time_replaceChunks_returns(): Unit =
        runTest {
            val idx = index()
            val docId = "01924a4b-4d29-7000-8000-00000000E0E1"
            seedDocument(docId)
            val seen = collectChanges(idx)

            idx.replaceChunks(
                docId = docId,
                chunks = listOf(NewChunk(ord = 0, text = "alpha", tokenCount = 1)),
                embedderId = "fake",
                embedderVersion = 1,
            )

            // No `runCurrent()` between the call and the assertion: the
            // contract is that the event is published *before* the
            // mutating call returns, not merely eventually.
            assertEquals(listOf(IndexChange.ChunksReplaced(docId)), seen)
        }

    @Test
    public fun observeChanges_emits_ChunksReplaced_even_when_the_new_chunk_list_is_empty(): Unit =
        runTest {
            val idx = index()
            val docId = "01924a4b-4d29-7000-8000-00000000E0E2"
            seedDocument(docId)
            idx.replaceChunks(
                docId = docId,
                chunks = listOf(NewChunk(ord = 0, text = "alpha", tokenCount = 1)),
                embedderId = "fake",
                embedderVersion = 1,
            )
            val seen = collectChanges(idx)

            // Clearing a document's chunks is still a change to the index.
            idx.replaceChunks(docId = docId, chunks = emptyList(), embedderId = "fake", embedderVersion = 1)

            assertEquals(listOf(IndexChange.ChunksReplaced(docId)), seen)
        }

    @Test
    public fun observeChanges_emits_EdgesReplaced_carrying_the_nominated_kinds(): Unit =
        runTest {
            val idx = index()
            val src = "01924a4b-4d29-7000-8000-00000000E0E3"
            val dst = "01924a4b-4d29-7000-8000-00000000E0E4"
            val seen = collectChanges(idx)

            idx.replaceEdges(
                srcId = src,
                kinds = setOf(EdgeKind.WIKILINK, EdgeKind.TAG),
                edges = listOf(Edge(srcId = src, dstId = dst, kind = EdgeKind.WIKILINK, createdAt = 0L)),
            )

            assertEquals(
                listOf(IndexChange.EdgesReplaced(src, setOf(EdgeKind.WIKILINK, EdgeKind.TAG))),
                seen,
            )
        }

    @Test
    public fun observeChanges_is_silent_for_a_no_op_replaceEdges(): Unit =
        runTest {
            val idx = index()
            val seen = collectChanges(idx)

            idx.replaceEdges(srcId = "any-src", kinds = emptySet(), edges = emptyList())

            assertEquals(emptyList<IndexChange>(), seen)
        }

    @Test
    public fun observeChanges_emits_EmbeddingsUpdated_with_the_written_chunk_ids(): Unit =
        runTest {
            val idx = index()
            val docId = "01924a4b-4d29-7000-8000-00000000E0E5"
            seedDocument(docId)
            val ids =
                idx.replaceChunks(
                    docId = docId,
                    chunks = listOf(NewChunk(ord = 0, text = "alpha", tokenCount = 1)),
                    embedderId = "fake",
                    embedderVersion = 1,
                )
            val seen = collectChanges(idx)

            idx.putEmbeddings(listOf(ids.single() to basis(axis = 0)))

            assertEquals(listOf(IndexChange.EmbeddingsUpdated(ids)), seen)
        }

    @Test
    public fun observeChanges_delivers_one_event_per_call_in_publication_order(): Unit =
        runTest {
            val idx = index()
            val docA = "01924a4b-4d29-7000-8000-00000000E0E6"
            val docB = "01924a4b-4d29-7000-8000-00000000E0E7"
            seedDocument(docA)
            seedDocument(docB)
            val seen = collectChanges(idx)

            // Two chunks in ONE call must still produce exactly one event —
            // the stream is a re-query tick, not a per-row delta log.
            idx.replaceChunks(
                docId = docA,
                chunks =
                    listOf(
                        NewChunk(ord = 0, text = "alpha", tokenCount = 1),
                        NewChunk(ord = 1, text = "beta", tokenCount = 1),
                    ),
                embedderId = "fake",
                embedderVersion = 1,
            )
            idx.replaceEdges(
                srcId = docA,
                kinds = setOf(EdgeKind.WIKILINK),
                edges = listOf(Edge(srcId = docA, dstId = docB, kind = EdgeKind.WIKILINK, createdAt = 0L)),
            )
            idx.replaceChunks(
                docId = docB,
                chunks = listOf(NewChunk(ord = 0, text = "gamma", tokenCount = 1)),
                embedderId = "fake",
                embedderVersion = 1,
            )

            assertEquals(
                listOf(
                    IndexChange.ChunksReplaced(docA),
                    IndexChange.EdgesReplaced(docA, setOf(EdgeKind.WIKILINK)),
                    IndexChange.ChunksReplaced(docB),
                ),
                seen,
            )
        }

    @Test
    public fun observeChanges_does_not_replay_changes_made_before_subscribing(): Unit =
        runTest {
            val idx = index()
            val docId = "01924a4b-4d29-7000-8000-00000000E0E8"
            seedDocument(docId)
            idx.replaceChunks(
                docId = docId,
                chunks = listOf(NewChunk(ord = 0, text = "alpha", tokenCount = 1)),
                embedderId = "fake",
                embedderVersion = 1,
            )

            val seen = collectChanges(idx)

            assertEquals(emptyList<IndexChange>(), seen)
        }

    /**
     * Subscribes to [IndexStore.observeChanges] on an unconfined test
     * dispatcher and returns the live-appended list of what it saw.
     *
     * Unconfined matters: it makes the collector run eagerly at every
     * emission point, so a test can assert "the event was already
     * published when the mutating call returned" without an intervening
     * `runCurrent()`. The [runCurrent] below is only to let the
     * subscription itself register before the test mutates anything —
     * `observeChanges` has no replay, so a late subscriber sees nothing.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.collectChanges(idx: IndexStore): List<IndexChange> {
        val seen = mutableListOf<IndexChange>()
        val job: Job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                idx.observeChanges().collect { seen += it }
            }
        runCurrent()
        check(job.isActive) { "observeChanges collector died before the test body ran" }
        return seen
    }

    private companion object {
        const val INT8_DIM: Int = 256
    }
}
