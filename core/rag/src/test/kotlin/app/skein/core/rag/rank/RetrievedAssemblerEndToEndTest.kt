// End-to-end `skein-wqli` (E5.I13 prep): the three *real* recall stages,
// fused by `PprRanker`, bridged by `RetrievedAssembler` — over a real
// ingest of `SyntheticVault`'s 500-document MEDIUM preset, mirroring
// `PprRankerEndToEndTest`'s fixture so a marker-term query is exercised
// through the whole recall → fusion → `List<Retrieved>` path
// `RetrievalServiceImpl` (skein-do6) will call.

package app.skein.core.rag.rank

import app.skein.core.model.CitationSourceKind
import app.skein.core.model.Document
import app.skein.core.model.EmbedderService
import app.skein.core.model.ScoredChunk
import app.skein.core.model.TimelineFilter
import app.skein.core.rag.recall.GraphRecall
import app.skein.core.rag.recall.LexicalRecall
import app.skein.core.rag.recall.VectorRecall
import app.skein.testing.InMemoryIndexStore
import app.skein.testing.InMemoryVaultRepository
import app.skein.testing.fixtures.SyntheticVault
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test

class RetrievedAssemblerEndToEndTest {
    @Test
    fun `a marker query returns hydrated, ordered Retrieved hits with real provenance`(): Unit =
        runBlocking {
            val vault = ingestedVault()
            val marker = vault.marker()

            val sources = vault.recall(marker.term)
            val ranked = PprRanker(vault.index, vault.repo).rank(sources, personaId = null)
            assertThat(ranked).isNotEmpty()

            val retrieved = RetrievedAssembler(vault.index, vault.repo).assemble(ranked, sources)

            // Order and count are exactly PprRanker's own output — nothing
            // re-sorted, nothing dropped (every ranked chunk's doc is live).
            assertThat(retrieved.map { it.chunkId }).isEqualTo(ranked.map { it.chunkId })
            assertThat(retrieved.map { it.score }).isEqualTo(ranked.map { it.score })

            val top = retrieved.first()
            assertThat(marker.shared.map { it.id }).contains(top.docId)
            assertThat(top.text.lowercase()).contains(marker.term)
            assertThat(top.docTitle).isNotEmpty()
            assertThat(top.recalledBy).isNotEmpty()
            assertThat(top.revisionHash).isNull()
            assertThat(top.locator).isNull()

            // Every recalledBy value traces back to a source that actually
            // listed the chunk, via the one locked bridge.
            for (hit in retrieved) {
                for (recallSource in hit.recalledBy) {
                    val list = sources[recallSource.citationSourceKind].orEmpty()
                    assertThat(list.map { it.chunkId }).contains(hit.chunkId)
                }
            }
        }

    @Test
    fun `RetrievedAssembler never widens or reorders PprRanker's k-capped output`(): Unit =
        runBlocking {
            val vault = ingestedVault()
            val sources = vault.recall(vault.marker().term)
            val ranked = PprRanker(vault.index, vault.repo).rank(sources, personaId = null)

            val retrieved = RetrievedAssembler(vault.index, vault.repo).assemble(ranked, sources)

            assertThat(retrieved.size).isAtMost(ranked.size)
            assertThat(retrieved.size).isAtMost(RankerConfig.DEFAULT_K)
        }

    // ------------------------------------------------------------------

    private class Marker(
        val term: String,
        val documents: List<Document>,
    ) {
        val shared: List<Document> get() = documents.filter { it.personaId == null }
    }

    private class IngestedVault(
        val repo: InMemoryVaultRepository,
        val index: InMemoryIndexStore,
        val embedder: EmbedderService,
        val documents: List<Document>,
    ) {
        fun marker(): Marker {
            val byTerm = LinkedHashMap<String, MutableList<Document>>()
            for (document in documents) {
                val body = document.bodyMd?.lowercase() ?: continue
                for (term in MARKER_TERMS) {
                    if (body.contains(term)) byTerm.getOrPut(term) { mutableListOf() } += document
                }
            }
            val chosen =
                byTerm.entries
                    .filter { entry -> entry.value.any { it.personaId == null } }
                    .minByOrNull { it.key }
            checkNotNull(chosen) { "SyntheticVault produced no persona-less marker note" }
            return Marker(chosen.key, chosen.value)
        }

        suspend fun recall(query: String): Map<CitationSourceKind, List<ScoredChunk>> =
            mapOf(
                CitationSourceKind.LEXICAL to LexicalRecall(index).recall(query),
                CitationSourceKind.VECTOR to VectorRecall(index, embedder).recall(query),
                CitationSourceKind.GRAPH to GraphRecall(index, repo).recall(query),
            )
    }

    private companion object {
        /** Mirrors `SyntheticVault.Lexicon.markerTerms`, which is private to the fixture. */
        val MARKER_TERMS =
            listOf(
                "zamboni-latitude",
                "quokka-ledger",
                "umbral-cartography",
                "ferrous-lullaby",
                "obsidian-perihelion",
                "tessellate-brambling",
                "cinnabar-almanac",
                "glacial-mimeograph",
                "paprika-obelisk",
                "wisteria-quadrant",
                "sorrel-tesseract",
                "vermillion-cadence",
            )

        /** Ingesting 500 documents costs a few seconds — do it once for the whole class. */
        private val shared: IngestedVault by lazy {
            runBlocking {
                val repo = InMemoryVaultRepository()
                SyntheticVault.seed(repo, size = SyntheticVault.Preset.MEDIUM)
                val documents =
                    repo
                        .observeTimeline(TimelineFilter(), limit = SyntheticVault.Preset.MEDIUM.total)
                        .first()
                val index = InMemoryIndexStore()
                val embedder = RankFixtures.loadedEmbedder()
                val chunkCount = RankFixtures.ingest(index, repo, embedder, documents)
                check(chunkCount > 0) { "ingest wrote no chunks" }
                IngestedVault(repo, index, embedder, documents)
            }
        }
    }

    private fun ingestedVault(): IngestedVault = shared
}
