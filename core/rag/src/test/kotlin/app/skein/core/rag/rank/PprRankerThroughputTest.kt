// bd `E5.I12`'s third acceptance criterion, verbatim: "Runtime for 90
// candidates + 200 neighbors < 20 ms on the JVM (smoke)".
//
// The fixture is exactly that shape — 90 candidate documents (3 recall
// sources × the stages' own `DEFAULT_K = 30`, one chunk each) wired into
// 200 neighbour documents — so the measurement covers what the rank step
// actually does on a phone: 90 `getChunks` hydrations, 290 persona checks,
// one bounded `neighborhood` expansion, and 20 power-iteration sweeps over
// a ~290-node graph.
//
// A sanity gate, not a benchmark: it reports the best of several runs
// after a warm-up, so a loaded CI box costs headroom rather than a flake.

package app.skein.core.rag.rank

import app.skein.core.model.ChunkId
import app.skein.core.model.CitationSourceKind
import app.skein.core.model.DocId
import app.skein.core.model.DocumentKind
import app.skein.core.model.Edge
import app.skein.core.model.EdgeKind
import app.skein.core.model.NewChunk
import app.skein.core.model.NewDocument
import app.skein.core.model.ScoredChunk
import app.skein.testing.InMemoryIndexStore
import app.skein.testing.InMemoryVaultRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.random.Random
import kotlin.system.measureNanoTime

class PprRankerThroughputTest {
    @Test
    fun ninetyCandidatesAndTwoHundredNeighborsRankInUnderTwentyMillis() =
        runBlocking {
            val repo = InMemoryVaultRepository()
            val index = InMemoryIndexStore()
            val random = Random(20_12L)

            val candidates = ArrayList<ChunkId>(CANDIDATES)
            val candidateDocs = ArrayList<DocId>(CANDIDATES)
            for (i in 0 until CANDIDATES) {
                val (docId, chunkId) = addDocument(repo, index, "Candidate $i")
                candidateDocs += docId
                candidates += chunkId
            }
            val neighborDocs = ArrayList<DocId>(NEIGHBORS)
            for (i in 0 until NEIGHBORS) {
                neighborDocs += addDocument(repo, index, "Neighbor $i").first
            }
            // Each neighbour hangs off two random candidates, so every
            // neighbour is exactly one hop out and the expansion really
            // does pull all 200 in.
            for (neighbor in neighborDocs) {
                index.replaceEdges(
                    srcId = neighbor,
                    kinds = setOf(EdgeKind.WIKILINK),
                    edges =
                        List(2) {
                            Edge(
                                srcId = neighbor,
                                dstId = candidateDocs[random.nextInt(candidateDocs.size)],
                                kind = EdgeKind.WIKILINK,
                                createdAt = 0L,
                            )
                        },
                )
            }

            val sources =
                mapOf(
                    CitationSourceKind.LEXICAL to scored(candidates.subList(0, 30)),
                    CitationSourceKind.VECTOR to scored(candidates.subList(30, 60)),
                    CitationSourceKind.GRAPH to scored(candidates.subList(60, 90)),
                )
            val ranker = PprRanker(index, repo)

            repeat(20) { ranker.rank(sources) }
            var best = Long.MAX_VALUE
            repeat(20) {
                val nanos = measureNanoTime { ranker.rank(sources) }
                if (nanos < best) best = nanos
            }

            val millis = best / 1_000_000.0
            println("PprRanker: $CANDIDATES candidates + $NEIGHBORS neighbors ranked in %.2f ms".format(millis))
            assertThat(ranker.rank(sources)).hasSize(RankerConfig.DEFAULT_K)
            assertThat(millis).isLessThan(20.0)
        }

    private fun scored(ids: List<ChunkId>): List<ScoredChunk> =
        ids.mapIndexed { position, id -> ScoredChunk(id, 1.0 - position / 100.0) }

    private suspend fun addDocument(
        repo: InMemoryVaultRepository,
        index: InMemoryIndexStore,
        title: String,
    ): Pair<DocId, ChunkId> {
        val document =
            repo.createDocument(
                NewDocument(kind = DocumentKind.NOTE, title = title, bodyMd = "body of $title"),
            )
        val chunkId =
            index
                .replaceChunks(
                    docId = document.id,
                    chunks = listOf(NewChunk(ord = 0, text = "body of $title", tokenCount = 3)),
                    embedderId = "fake",
                    embedderVersion = 1,
                ).single()
        return document.id to chunkId
    }

    private companion object {
        const val CANDIDATES = 90
        const val NEIGHBORS = 200
    }
}
