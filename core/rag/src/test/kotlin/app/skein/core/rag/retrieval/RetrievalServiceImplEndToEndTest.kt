// End-to-end `RetrievalServiceImpl` (skein-do6, E5.I13) over a real ingest
// of `SyntheticVault`'s 500-document MEDIUM preset — the same fixture and
// marker-term technique `PprRankerEndToEndTest`/`RetrievedAssemblerEndToEndTest`
// use (E5.I12 prep, skein-dxj/skein-wqli), but driving the whole
// `retrieveContext` call (recall fan-out + rank + assemble) instead of
// wiring the stages by hand, since that wiring is exactly what this class
// now owns.
//
// Bead `skein-do6`'s AC asks for "timing on the emulator logged per stage";
// the emulator/instrumented half of that is `E10.I4`'s own follow-up. This
// suite covers the JVM-reachable half: relevance through the whole pipeline
// (a marker term's own note ranks first) and a latency smoke measurement.
// No numeric retrieval-latency budget is written down anywhere in the spec
// or in this bead (only the *unrelated*, still-provisional "≤200 ms" note
// on an optional cross-encoder rerank, design spec §7.2 step 2) — the
// bound asserted here is a smoke ceiling against a pathological regression,
// not a measured budget from `MEASUREMENTS.md`.

package app.skein.core.rag.retrieval

import app.skein.core.model.Document
import app.skein.core.model.EmbedderService
import app.skein.core.model.TimelineFilter
import app.skein.core.rag.rank.RankFixtures
import app.skein.testing.InMemoryIndexStore
import app.skein.testing.InMemoryVaultRepository
import app.skein.testing.fixtures.SyntheticVault
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.system.measureNanoTime

class RetrievalServiceImplEndToEndTest {
    @Test
    fun `a rare marker term ranks its own note first through retrieveContext`() =
        runBlocking {
            val vault = ingestedVault()
            val marker = vault.marker()
            assertThat(marker.documents.size).isLessThan(20)

            val service =
                RetrievalServiceImpl(index = vault.index, repository = vault.repo, embedder = vault.embedder)
            val results = service.retrieveContext(marker.term, personaId = null)

            assertThat(results).isNotEmpty()
            val top = results.first()
            assertThat(marker.shared.map { it.id }).contains(top.docId)
            assertThat(top.text.lowercase()).contains(marker.term)
            assertThat(top.recalledBy).isNotEmpty()
        }

    @Test
    fun `retrieveContext over the MEDIUM corpus stays within a smoke latency ceiling`() =
        runBlocking {
            val vault = ingestedVault()
            val marker = vault.marker()
            val service =
                RetrievalServiceImpl(index = vault.index, repository = vault.repo, embedder = vault.embedder)

            // Warm the JIT, then time the best of several runs — the same
            // technique `PprRankerEndToEndTest` uses for the rank step
            // alone; this times recall + rank + assemble together.
            repeat(10) { service.retrieveContext(marker.term) }
            var best = Long.MAX_VALUE
            repeat(10) {
                val nanos = measureNanoTime { service.retrieveContext(marker.term) }
                if (nanos < best) best = nanos
            }

            val millis = best / 1_000_000.0
            println(
                "RetrievalServiceImpl: retrieveContext over ${SyntheticVault.Preset.MEDIUM.total} " +
                    "documents in %.2f ms (best of 10; no formal budget is defined for this bead — see file header)"
                        .format(millis),
            )
            // Smoke ceiling only (see file header) — generous relative to
            // PprRankerEndToEndTest's own 20 ms rank-only budget, to leave
            // headroom for the two recall stages this call adds.
            assertThat(millis).isLessThan(SMOKE_CEILING_MILLIS)
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
    }

    private companion object {
        /** Generous smoke ceiling — see file header on why no formal budget exists to assert against. */
        const val SMOKE_CEILING_MILLIS = 250.0

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
