// `PprRanker` (skein-dxj, plan `E5.I12`) tests, against the JVM
// `InMemoryIndexStore` / `InMemoryVaultRepository` fakes from `:testing`.
// Recall lists are handed in directly rather than produced by the recall
// stages — this suite is about the rank step's own contract (persona
// filter, fusion, graph bound, ordering, caps); `PprRankerEndToEndTest`
// drives the real stages over a synthetic vault.
//
// The 6-node fixture is bd `E5.I12`'s first acceptance criterion: two
// strong candidates (`s1`, `s2`) both link a hub document, an isolate
// carries exactly the same fused recall as the hub (same rank, different
// source), and two unrelated documents fill the graph out to six nodes.

package app.skein.core.rag.rank

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import us.aherrera.skein.core.model.ChunkId
import us.aherrera.skein.core.model.CitationSourceKind
import us.aherrera.skein.core.model.DocId
import us.aherrera.skein.core.model.Document
import us.aherrera.skein.core.model.DocumentKind
import us.aherrera.skein.core.model.Edge
import us.aherrera.skein.core.model.EdgeKind
import us.aherrera.skein.core.model.IndexStore
import us.aherrera.skein.core.model.NewChunk
import us.aherrera.skein.core.model.NewDocument
import us.aherrera.skein.core.model.PersonaId
import us.aherrera.skein.core.model.ScoredChunk
import us.aherrera.skein.testing.InMemoryIndexStore
import us.aherrera.skein.testing.InMemoryVaultRepository

class PprRankerTest {
    @Test
    fun `a document linked by two strong candidates outranks an isolated document with equal recall`() =
        runTest {
            val vault = sixNodeVault()

            val ranked =
                PprRanker(vault.index, vault.repo).rank(
                    sources =
                        mapOf(
                            CitationSourceKind.LEXICAL to
                                listOf(
                                    scored(vault, "s1"),
                                    scored(vault, "s2"),
                                    scored(vault, "hub"),
                                ),
                            CitationSourceKind.VECTOR to
                                listOf(
                                    scored(vault, "s1"),
                                    scored(vault, "s2"),
                                    scored(vault, "isolate"),
                                ),
                        ),
                )

            val byChunk = ranked.associate { it.chunkId to it.score }
            // Equal fused recall (rank 3 in one source each) — only the
            // graph separates them.
            assertThat(byChunk.getValue(vault.chunk("hub"))).isGreaterThan(byChunk.getValue(vault.chunk("isolate")))
            assertThat(ranked.map { it.chunkId }.take(2))
                .containsExactly(vault.chunk("s1"), vault.chunk("s2"))
                .inOrder()
        }

    @Test
    fun `scores descend and every score sits in 0, 1`() =
        runTest {
            val vault = sixNodeVault()

            val ranked = PprRanker(vault.index, vault.repo).rank(allSources(vault))

            assertThat(ranked.map { it.score }).isEqualTo(ranked.map { it.score }.sortedDescending())
            for (scored in ranked) {
                assertThat(scored.score).isAtLeast(0.0)
                assertThat(scored.score).isAtMost(1.0)
            }
        }

    @Test
    fun `the same inputs rank identically every time`() =
        runTest {
            val vault = sixNodeVault()
            val ranker = PprRanker(vault.index, vault.repo)

            val first = ranker.rank(allSources(vault))
            val second = ranker.rank(allSources(vault))

            assertThat(second).isEqualTo(first)
        }

    @Test
    fun `a chunk found by several sources is returned once`() =
        runTest {
            val vault = sixNodeVault()
            val hub = scored(vault, "hub")

            val ranked =
                PprRanker(vault.index, vault.repo).rank(
                    mapOf(
                        CitationSourceKind.LEXICAL to listOf(hub),
                        CitationSourceKind.VECTOR to listOf(hub),
                        CitationSourceKind.GRAPH to listOf(hub),
                    ),
                )

            assertThat(ranked.map { it.chunkId }).containsExactly(vault.chunk("hub"))
        }

    @Test
    fun `tied candidates break on ascending chunk id`() =
        runTest {
            val vault = sixNodeVault()
            // Two chunks of the same document, adjacent in no list but
            // alone at rank 1 of two different sources: identical fused
            // recall and identical PPR, so only the id can separate them.
            val extra = vault.addChunk("hub", "a second hub chunk")
            val ranked =
                PprRanker(vault.index, vault.repo).rank(
                    mapOf(
                        CitationSourceKind.LEXICAL to listOf(ScoredChunk(extra, 1.0)),
                        CitationSourceKind.VECTOR to listOf(scored(vault, "hub")),
                    ),
                )

            assertThat(ranked.map { it.chunkId }).isInStrictOrder()
            assertThat(ranked.map { it.score }.distinct()).hasSize(1)
        }

    @Test
    fun `the persona filter drops another persona's documents before ranking`() =
        runTest {
            val vault = sixNodeVault()
            vault.addDocument("shared", personaId = null)
            vault.addDocument("work-only", personaId = "persona-work")
            vault.addDocument("personal-only", personaId = "persona-personal")

            val ranked =
                PprRanker(vault.index, vault.repo).rank(
                    sources =
                        mapOf(
                            CitationSourceKind.LEXICAL to
                                listOf(
                                    ScoredChunk(vault.chunk("work-only"), 1.0),
                                    ScoredChunk(vault.chunk("personal-only"), 0.9),
                                    ScoredChunk(vault.chunk("shared"), 0.8),
                                ),
                        ),
                    personaId = "persona-work",
                )

            assertThat(ranked.map { it.chunkId })
                .containsExactly(vault.chunk("work-only"), vault.chunk("shared"))
            // The dropped chunk did not consume a rank: the survivors are
            // ranked 1 and 2, not 1 and 3.
            assertThat(ranked.map { it.chunkId }.first()).isEqualTo(vault.chunk("work-only"))
        }

    @Test
    fun `a null persona admits only persona-less documents`() =
        runTest {
            val vault = sixNodeVault()
            vault.addDocument("shared", personaId = null)
            vault.addDocument("work-only", personaId = "persona-work")

            val ranked =
                PprRanker(vault.index, vault.repo).rank(
                    sources =
                        mapOf(
                            CitationSourceKind.LEXICAL to
                                listOf(
                                    ScoredChunk(vault.chunk("work-only"), 1.0),
                                    ScoredChunk(vault.chunk("shared"), 0.9),
                                ),
                        ),
                    personaId = null,
                )

            assertThat(ranked.map { it.chunkId }).containsExactly(vault.chunk("shared"))
        }

    @Test
    fun `a chunk whose document row is gone is dropped`() =
        runTest {
            val vault = sixNodeVault()
            val orphan =
                vault.index
                    .replaceChunks(
                        docId = "doc-that-never-existed",
                        chunks = listOf(NewChunk(ord = 0, text = "orphan", tokenCount = 1)),
                        embedderId = "fake",
                        embedderVersion = 1,
                    ).single()

            val ranked =
                PprRanker(vault.index, vault.repo).rank(
                    mapOf(
                        CitationSourceKind.LEXICAL to
                            listOf(ScoredChunk(orphan, 1.0), scored(vault, "hub")),
                    ),
                )

            assertThat(ranked.map { it.chunkId }).containsExactly(vault.chunk("hub"))
        }

    @Test
    fun `a missing vector stage still ranks the remaining sources`() =
        runTest {
            // `VectorRecall` returns nothing until embeddings exist
            // (skein-079) — the rank step must not depend on it.
            val vault = sixNodeVault()

            val ranked =
                PprRanker(vault.index, vault.repo).rank(
                    mapOf(
                        CitationSourceKind.LEXICAL to listOf(scored(vault, "s1"), scored(vault, "hub")),
                        CitationSourceKind.VECTOR to emptyList(),
                        CitationSourceKind.GRAPH to listOf(scored(vault, "hub")),
                    ),
                )

            assertThat(ranked.map { it.chunkId })
                .containsExactly(vault.chunk("hub"), vault.chunk("s1"))
                .inOrder()
        }

    @Test
    fun `k caps the ranked list and a non-positive k returns nothing`() =
        runTest {
            val vault = sixNodeVault()
            val ranker = PprRanker(vault.index, vault.repo)

            assertThat(ranker.rank(allSources(vault), k = 2)).hasSize(2)
            assertThat(ranker.rank(allSources(vault), k = 0)).isEmpty()
        }

    @Test
    fun `empty sources rank to nothing without touching the graph`() =
        runTest {
            val vault = sixNodeVault()
            val recording = RecordingIndexStore(vault.index)

            val ranked = PprRanker(recording, vault.repo).rank(emptyMap())

            assertThat(ranked).isEmpty()
            assertThat(recording.neighborhoodCalls).isEmpty()
        }

    @Test
    fun `the ranking graph is bounded to the candidate documents and one hop`() =
        runTest {
            val vault = sixNodeVault()
            val recording = RecordingIndexStore(vault.index)

            PprRanker(recording, vault.repo).rank(
                mapOf(CitationSourceKind.LEXICAL to listOf(scored(vault, "s1"))),
            )

            val call = recording.neighborhoodCalls.single()
            assertThat(call.seeds).containsExactly(vault.doc("s1"))
            assertThat(call.hops).isEqualTo(RankerConfig.DEFAULT_NEIGHBOR_HOPS)
            assertThat(call.maxNodes).isEqualTo(1 + RankerConfig.DEFAULT_MAX_NEIGHBOR_NODES)
        }

    @Test
    fun `a document beyond the one-hop neighbourhood cannot change the ranking`() =
        runTest {
            val vault = sixNodeVault()
            val sources =
                mapOf(
                    CitationSourceKind.LEXICAL to
                        listOf(scored(vault, "s1"), scored(vault, "s2"), scored(vault, "hub")),
                )
            val before = PprRanker(vault.index, vault.repo).rank(sources)

            // `far` hangs off `isolate`, which is itself not a candidate —
            // so `far` sits two hops out and must not enter the graph.
            vault.addDocument("far", personaId = null)
            vault.link("isolate", "far")
            val after = PprRanker(vault.index, vault.repo).rank(sources)

            assertThat(after).isEqualTo(before)
            assertThat(after.map { it.chunkId }).doesNotContain(vault.chunk("far"))
        }

    @Test
    fun `maxCandidates truncates an oversized recall union by fused recall`() =
        runTest {
            val vault = sixNodeVault()
            val config = RankerConfig(maxCandidates = 2)

            val ranked =
                PprRanker(vault.index, vault.repo, config).rank(
                    sources = allSources(vault),
                    k = 50,
                )

            assertThat(ranked).hasSize(2)
        }

    // ------------------------------------------------------------------
    // Fixture
    // ------------------------------------------------------------------

    /**
     * `s1 — hub — s2`, an `isolate` with no edges, and an unrelated
     * `f1 — f2` pair: six documents, one chunk each.
     */
    private suspend fun sixNodeVault(): Vault {
        val vault = Vault()
        for (name in listOf("s1", "s2", "hub", "isolate", "f1", "f2")) {
            vault.addDocument(name, personaId = null)
        }
        vault.link("s1", "hub")
        vault.link("s2", "hub")
        vault.link("f1", "f2")
        return vault
    }

    private fun allSources(vault: Vault): Map<CitationSourceKind, List<ScoredChunk>> =
        mapOf(
            CitationSourceKind.LEXICAL to
                listOf(scored(vault, "s1"), scored(vault, "hub"), scored(vault, "f1")),
            CitationSourceKind.VECTOR to
                listOf(scored(vault, "s2"), scored(vault, "isolate")),
            CitationSourceKind.GRAPH to
                listOf(scored(vault, "hub"), scored(vault, "f2")),
        )

    private fun scored(
        vault: Vault,
        name: String,
    ): ScoredChunk = ScoredChunk(vault.chunk(name), 1.0)

    /** The hand-built fixture: named documents, one chunk each, plus wikilink edges. */
    private class Vault {
        val repo = InMemoryVaultRepository()
        val index = InMemoryIndexStore()
        private val docIds = HashMap<String, DocId>()
        private val chunkIds = HashMap<String, ChunkId>()

        suspend fun addDocument(
            name: String,
            personaId: PersonaId?,
        ): DocId {
            val document: Document =
                repo.createDocument(
                    NewDocument(
                        kind = DocumentKind.NOTE,
                        title = name,
                        bodyMd = "body of $name",
                        personaId = personaId,
                    ),
                )
            docIds[name] = document.id
            chunkIds[name] =
                index
                    .replaceChunks(
                        docId = document.id,
                        chunks = listOf(NewChunk(ord = 0, text = "body of $name", tokenCount = 3)),
                        embedderId = "fake",
                        embedderVersion = 1,
                    ).single()
            return document.id
        }

        /** A second chunk on an existing document, returned by id. */
        suspend fun addChunk(
            name: String,
            text: String,
        ): ChunkId {
            val docId = doc(name)
            val ids =
                index.replaceChunks(
                    docId = docId,
                    chunks =
                        listOf(
                            NewChunk(ord = 0, text = "body of $name", tokenCount = 3),
                            NewChunk(ord = 1, text = text, tokenCount = 4),
                        ),
                    embedderId = "fake",
                    embedderVersion = 1,
                )
            chunkIds[name] = ids[0]
            return ids[1]
        }

        suspend fun link(
            from: String,
            to: String,
        ) {
            index.replaceEdges(
                srcId = doc(from),
                kinds = setOf(EdgeKind.WIKILINK),
                edges =
                    listOf(
                        Edge(
                            srcId = doc(from),
                            dstId = doc(to),
                            kind = EdgeKind.WIKILINK,
                            createdAt = 0L,
                        ),
                    ),
            )
        }

        fun doc(name: String): DocId = docIds.getValue(name)

        fun chunk(name: String): ChunkId = chunkIds.getValue(name)
    }

    /** Records `neighborhood` calls so the bounded-graph contract can be asserted directly. */
    private class RecordingIndexStore(
        private val delegate: IndexStore,
    ) : IndexStore by delegate {
        data class Call(
            val seeds: Set<String>,
            val hops: Int,
            val maxNodes: Int,
        )

        val neighborhoodCalls: MutableList<Call> = mutableListOf()

        override suspend fun neighborhood(
            seeds: Set<String>,
            hops: Int,
            maxNodes: Int,
        ): List<Edge> {
            neighborhoodCalls += Call(seeds, hops, maxNodes)
            return delegate.neighborhood(seeds, hops, maxNodes)
        }
    }
}
