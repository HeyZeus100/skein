// End-to-end `E5.I12` (skein-dxj): the three *real* recall stages over a
// *real* ingest of `SyntheticVault`'s 500-document MEDIUM preset, fused by
// `PprRanker`.
//
// The assertion is the one the fixture was built for: `SyntheticVault`
// sprinkles rare "marker terms" (`zamboni-latitude`, `quokka-ledger`, …)
// that "never appear outside a marker sentence" (its `Lexicon`), so a
// query for one of them must put a note that actually carries it at the
// top — through chunking, lexical recall, fake vector recall and the PPR
// blend, not just through `bm25` alone. A term lands in a handful of the
// 500 documents rather than in exactly one (the generator rolls a marker
// into roughly one note in six and sometimes paraphrases an earlier
// marker note), so the assertion is "the top chunk is one of those notes,
// and its text contains the term" rather than a single fixed id.
//
// Everything here is seeded: the vault (`SyntheticVault.DEFAULT_SEED`), the
// fake embedder (a character hash), and the ranker itself. The test either
// always passes or always fails; it cannot flake.

package app.skein.core.rag.rank

import app.skein.core.rag.recall.GraphRecall
import app.skein.core.rag.recall.LexicalRecall
import app.skein.core.rag.recall.VectorRecall
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import us.aherrera.skein.core.model.CitationSourceKind
import us.aherrera.skein.core.model.Document
import us.aherrera.skein.core.model.EmbedderService
import us.aherrera.skein.core.model.ScoredChunk
import us.aherrera.skein.core.model.TimelineFilter
import us.aherrera.skein.testing.InMemoryIndexStore
import us.aherrera.skein.testing.InMemoryVaultRepository
import us.aherrera.skein.testing.fixtures.SyntheticVault
import kotlin.system.measureNanoTime

class PprRankerEndToEndTest {
    @Test
    fun `a rare marker term ranks its own note first through the whole pipeline`() =
        runBlocking {
            val vault = ingestedVault()
            val marker = vault.marker()
            // Out of 500 documents only a handful mention the term at all,
            // and the retrieval runs persona-less, so only the shared ones
            // are even eligible.
            assertThat(marker.documents.size).isLessThan(20)

            val ranked = vault.retrieve(marker.term, persona = null)

            assertThat(ranked).isNotEmpty()
            val topChunk =
                vault.index
                    .getChunks(listOf(ranked.first().chunkId))
                    .values
                    .single()
            assertThat(marker.shared.map { it.id }).contains(topChunk.docId)
            assertThat(topChunk.text.lowercase()).contains(marker.term)
        }

    @Test
    fun `the ranked list is capped, de-duplicated and ordered`() =
        runBlocking {
            val vault = ingestedVault()

            val ranked = vault.retrieve(vault.marker().term, persona = null)

            assertThat(ranked.size).isAtMost(RankerConfig.DEFAULT_K)
            assertThat(ranked.map { it.chunkId }).containsNoDuplicates()
            assertThat(ranked.map { it.score }).isEqualTo(ranked.map { it.score }.sortedDescending())
        }

    @Test
    fun `the persona filter never surfaces another persona's note`() =
        runBlocking {
            val vault = ingestedVault()
            // `SyntheticVault` gives roughly a third of its notes a
            // persona; retrieving under one of them must return only that
            // persona's notes and the shared (persona-less) ones.
            val persona = "work"
            val ranked = vault.retrieve("vault index chunk retrieval", persona = persona)

            assertThat(ranked).isNotEmpty()
            for (scored in ranked) {
                val chunk =
                    vault.index
                        .getChunks(listOf(scored.chunkId))
                        .values
                        .single()
                val personaId = vault.repo.getDocument(chunk.docId)?.personaId
                assertThat(personaId).isAnyOf(null, persona)
            }
        }

    @Test
    fun `ranking the medium corpus stays well inside the retrieval budget`() =
        runBlocking {
            val vault = ingestedVault()

            // Warm the JIT, then time the rank step alone (recall is the
            // recall stages' own budget, measured in their suites).
            val sources = vault.recall(vault.marker().term)
            val ranker = PprRanker(vault.index, vault.repo)
            repeat(20) { ranker.rank(sources, personaId = null) }
            var best = Long.MAX_VALUE
            repeat(10) {
                val nanos = measureNanoTime { ranker.rank(sources, personaId = null) }
                if (nanos < best) best = nanos
            }

            val millis = best / 1_000_000.0
            println(
                "PprRanker: ${sources.values.sumOf { it.size }} recalled chunks over " +
                    "${SyntheticVault.Preset.MEDIUM.total} documents ranked in %.2f ms".format(millis),
            )
            assertThat(millis).isLessThan(20.0)
        }

    // ------------------------------------------------------------------

    /** A rare marker term and the notes that carry it — see [IngestedVault.marker]. */
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
        /**
         * The lexicographically first marker term that at least one
         * *persona-less* note carries, with every note carrying it.
         *
         * `SyntheticVault` rolls a marker term into roughly one note in
         * six and occasionally paraphrases an earlier marker note, so a
         * term lands in a handful of the 500 documents rather than exactly
         * one — [Marker.documents] is that handful, and [Marker.shared] is
         * the subset a `personaId = null` retrieval is allowed to see.
         */
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

        suspend fun retrieve(
            query: String,
            persona: String?,
        ): List<ScoredChunk> = PprRanker(index, repo).rank(recall(query), persona)
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
