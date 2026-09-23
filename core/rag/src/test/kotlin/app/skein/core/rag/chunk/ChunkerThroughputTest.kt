package app.skein.core.rag.chunk

import app.skein.core.model.TimelineFilter
import app.skein.core.rag.tokenizers.TokenizerFixtures
import app.skein.testing.InMemoryVaultRepository
import app.skein.testing.fixtures.SyntheticVault
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.system.measureNanoTime

/**
 * bd skein-92u (E5.I5): the chunker runs on-device over a whole vault during
 * ingest, so a realistic corpus — `SyntheticVault`'s 1 000-document preset,
 * same fixture `TokenizerThroughputTest` (skein-bpt) uses — must chunk far
 * inside the ingest budget on the JVM.
 *
 * A sanity gate, not a benchmark: the threshold is deliberately loose so it
 * will not flake on a loaded CI box, while still catching an accidental
 * quadratic in the packing loop.
 */
class ChunkerThroughputTest {
    private val corpus: List<String> by lazy {
        val repo = InMemoryVaultRepository()
        SyntheticVault.seed(repo, size = SyntheticVault.Preset.LARGE)
        runBlocking {
            repo
                .observeTimeline(TimelineFilter(), limit = SyntheticVault.Preset.LARGE.total)
                .first()
                .mapNotNull { it.bodyMd }
                .filter { it.isNotBlank() }
        }
    }

    @Test
    fun aThousandNoteVaultChunksWellInsideTheIngestBudget() {
        assertThat(corpus.size).isAtLeast(800)
        val characters = corpus.sumOf { it.length }
        val chunker = Chunker(TokenizerFixtures.tokenizer(TokenizerFixtures.NOMIC))

        // Warm up the JIT on a slice of the corpus first.
        for (body in corpus.take(50)) chunker.chunk(body)

        var chunkCount = 0
        val nanos = measureNanoTime { for (body in corpus) chunkCount += chunker.chunk(body).size }

        val millis = nanos / 1_000_000.0
        val docsPerSecond = corpus.size * 1000.0 / millis
        println(
            "Chunker: ${corpus.size} docs / $characters chars -> $chunkCount chunks " +
                "in %.0f ms (%.0f docs/s)".format(millis, docsPerSecond),
        )
        assertThat(chunkCount).isGreaterThan(0)
        assertThat(millis).isLessThan(10_000.0)
    }
}
