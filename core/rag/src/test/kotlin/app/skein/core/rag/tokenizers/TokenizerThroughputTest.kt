package app.skein.core.rag.tokenizers

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import us.aherrera.skein.core.model.TimelineFilter
import us.aherrera.skein.testing.InMemoryVaultRepository
import us.aherrera.skein.testing.fixtures.SyntheticVault
import kotlin.system.measureNanoTime

/**
 * bd skein-bpt (E5.I2): the tokenizers run on-device over a whole vault during
 * ingest (bd skein-92u chunks against `countTokens`), so a realistic corpus —
 * `SyntheticVault`'s 1 000-document preset — must tokenize far inside the
 * ingest budget on the JVM.
 *
 * This is a sanity gate, not a benchmark: the threshold is deliberately loose
 * (a whole vault in under 10 s, i.e. > 100 docs/s) so it will not flake on a
 * loaded CI box, while still catching an accidental quadratic.
 */
class TokenizerThroughputTest {
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
    fun aThousandNoteVaultTokenizesWellInsideTheIngestBudget() {
        assertThat(corpus.size).isAtLeast(800)
        val characters = corpus.sumOf { it.length }

        for (name in listOf(TokenizerFixtures.NOMIC, TokenizerFixtures.GLINER)) {
            val tokenizer = TokenizerFixtures.tokenizer(name)
            // Warm up the JIT on a slice of the corpus first.
            for (body in corpus.take(50)) tokenizer.countTokens(body)

            var tokens = 0L
            val nanos = measureNanoTime { for (body in corpus) tokens += tokenizer.countTokens(body) }

            val millis = nanos / 1_000_000.0
            val docsPerSecond = corpus.size * 1000.0 / millis
            println(
                "$name: ${corpus.size} docs / $characters chars -> $tokens tokens " +
                    "in %.0f ms (%.0f docs/s, %.1f MB/s)".format(
                        millis,
                        docsPerSecond,
                        characters / 1024.0 / 1024.0 / (millis / 1000.0),
                    ),
            )
            assertThat(tokens).isGreaterThan(0L)
            assertThat(millis).isLessThan(10_000.0)
        }
    }

    @Test
    fun countTokensNeverDisagreesWithEncodeOverTheCorpus() {
        val tokenizer = TokenizerFixtures.tokenizer(TokenizerFixtures.NOMIC)
        for (body in corpus.take(100)) {
            assertThat(tokenizer.countTokens(body)).isEqualTo(tokenizer.encode(body).size)
        }
    }
}
