package app.skein.core.rag.recall

import app.skein.core.model.ChunkId
import app.skein.core.model.DocId
import app.skein.core.model.IndexStore
import app.skein.core.model.NewChunk
import app.skein.core.model.ScoredChunk
import app.skein.testing.InMemoryIndexStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * `LexicalRecall` (skein-3rj3, gap on plan `E5.I6`) tests, against the JVM
 * `InMemoryIndexStore` fake from `:testing`.
 *
 * The fake's `bm25` is a documented **approximation** — term-occurrence
 * counting, not real BM25/IDF weighting (see the fake's own file header:
 * "BM25 approximated by term-frequency count — it is a fake, documented as
 * such"). That is sufficient to prove `LexicalRecall`'s own contract
 * (normalization, ordering, k-cap, adversarial safety); the acceptance
 * criterion's literal `recall("sqlite cipher")`-ranks-both-terms-first
 * example against *real* FTS5 ranking is additionally covered by the
 * compile-only androidTest companion in `:core:vault`
 * (`LexicalRecallAcceptanceTest`, gated on skein-k3b2 like
 * `IndexStoreImplAcceptanceTest`).
 */
class LexicalRecallTest {
    @Test
    fun `chunk mentioning both query terms ranks first`() =
        runTest {
            val index = InMemoryIndexStore()
            val chunkBoth = addChunk(index, docId(1), "sqlite cipher is an sqlcipher extension")
            val chunkSqlite = addChunk(index, docId(2), "sqlite is a database engine")
            val chunkCipher = addChunk(index, docId(3), "cipher suites for tls")

            val results = LexicalRecall(index).recall("sqlite cipher")

            assertThat(results.map { it.chunkId })
                .containsExactly(chunkBoth, chunkSqlite, chunkCipher)
                .inOrder()
            assertThat(results.first().score).isWithin(1e-9).of(1.0)
        }

    @Test
    fun `scores normalize into 0,1 with the top exactly 1_0`() =
        runTest {
            val index = InMemoryIndexStore()
            val top = addChunk(index, docId(1), "alpha alpha alpha")
            addChunk(index, docId(2), "alpha")

            val results = LexicalRecall(index).recall("alpha")

            assertThat(results).hasSize(2)
            assertThat(results.first().chunkId).isEqualTo(top)
            assertThat(results.first().score).isWithin(1e-9).of(1.0)
            for (r in results) {
                assertThat(r.score).isAtLeast(0.0)
                assertThat(r.score).isAtMost(1.0)
            }
        }

    @Test
    fun `empty query returns empty result`() =
        runTest {
            val index = InMemoryIndexStore()
            addChunk(index, docId(1), "anything")

            val results = LexicalRecall(index).recall("")

            assertThat(results).isEmpty()
        }

    @Test
    fun `single matching chunk normalizes to 1_0`() =
        runTest {
            val index = InMemoryIndexStore()
            val only = addChunk(index, docId(1), "a unique marker term zyxq")

            val results = LexicalRecall(index).recall("zyxq")

            assertThat(results).hasSize(1)
            assertThat(results.single().chunkId).isEqualTo(only)
            assertThat(results.single().score).isWithin(1e-9).of(1.0)
        }

    @Test
    fun `tied non-zero scores both normalize to 1_0`() =
        runTest {
            // Both chunks contain the query term exactly once -> the fake's
            // term-count bm25 gives them equal, non-zero raw scores.
            // score / maxScore for equal non-zero inputs already yields
            // 1.0 for both without any special-casing.
            val index = InMemoryIndexStore()
            addChunk(index, docId(1), "beta term")
            addChunk(index, docId(2), "beta term")

            val results = LexicalRecall(index).recall("beta")

            assertThat(results).hasSize(2)
            assertThat(results.map { it.score }).containsExactly(1.0, 1.0)
        }

    @Test
    fun `zero scores from the store normalize to 1_0 without producing NaN`() =
        runTest {
            // A hand-built fake forces the maxScore == 0.0 edge case, which
            // InMemoryIndexStore's own bm25 never returns (it filters out
            // non-positive scores) — this exercises LexicalRecall's guard
            // against 0.0 / 0.0.
            val fake =
                FixedBm25IndexStore(
                    delegate = InMemoryIndexStore(),
                    fixed = listOf(ScoredChunk(chunkId = 1L, score = 0.0), ScoredChunk(chunkId = 2L, score = 0.0)),
                )

            val results = LexicalRecall(fake).recall("anything")

            assertThat(results.map { it.score }).containsExactly(1.0, 1.0)
            for (r in results) assertThat(r.score.isNaN()).isFalse()
        }

    @Test
    fun `ties are broken deterministically by ascending chunkId regardless of store order`() =
        runTest {
            // The fake hands back two tied scores in descending-chunkId
            // order — the opposite of the tie-break LexicalRecall must
            // impose — so this actually exercises the sort rather than
            // passing by construction.
            val fake =
                FixedBm25IndexStore(
                    delegate = InMemoryIndexStore(),
                    fixed = listOf(ScoredChunk(chunkId = 20L, score = 5.0), ScoredChunk(chunkId = 10L, score = 5.0)),
                )

            val results = LexicalRecall(fake).recall("anything")

            assertThat(results.map { it.chunkId }).containsExactly(10L, 20L).inOrder()
        }

    @Test
    fun `same query and index state produce the same ordering on repeat calls`() =
        runTest {
            val index = InMemoryIndexStore()
            addChunk(index, docId(1), "delta one")
            addChunk(index, docId(2), "delta two")
            addChunk(index, docId(3), "delta delta three")

            val recall = LexicalRecall(index)
            val first = recall.recall("delta")
            val second = recall.recall("delta")

            assertThat(second.map { it.chunkId }).isEqualTo(first.map { it.chunkId })
        }

    @Test
    fun `k caps the number of returned chunks`() =
        runTest {
            val index = InMemoryIndexStore()
            addChunk(index, docId(1), "gamma one")
            addChunk(index, docId(2), "gamma two")
            addChunk(index, docId(3), "gamma three")

            val results = LexicalRecall(index).recall("gamma", k = 2)

            assertThat(results).hasSize(2)
        }

    @Test
    fun `non-positive k returns empty without calling the store`() =
        runTest {
            val index = CountingBm25IndexStore(InMemoryIndexStore())
            addChunk(index, docId(1), "epsilon")

            val results = LexicalRecall(index).recall("epsilon", k = 0)

            assertThat(results).isEmpty()
            assertThat(index.bm25Calls).isEqualTo(0)
        }

    @Test
    fun `adversarial query strings never throw`() =
        runTest {
            val index = InMemoryIndexStore()
            addChunk(index, docId(1), "the quick brown fox")
            addChunk(index, docId(2), "lorem ipsum dolor sit amet")
            val recall = LexicalRecall(index)

            // Same 20-string adversarial set as
            // `IndexStoreImplAcceptanceTest.\`bm25 does not throw on twenty adversarial query strings\``
            // (`core/vault` androidTest). This fake doesn't exercise real
            // FTS5 grammar, but pins that `LexicalRecall`'s own
            // normalization/sort logic never throws on any of these inputs
            // either; the real-grammar assertion is the store's own test.
            val adversarial =
                listOf(
                    "it's a \"quoted\" (weird) query",
                    "",
                    "   ",
                    "\"",
                    "\"\"\"",
                    "()",
                    "( )",
                    "-",
                    "--",
                    "AND OR NOT NEAR",
                    "^^^",
                    "***",
                    "\u0000",
                    "🚀 rocket 💩",
                    "prefix: \"unterminated",
                    "column:body AND text:foo",
                    "col\u0000umn:body",
                    "\\\\\\",
                    "a b c d e f g h i j k l m n o p q r s t",
                    "!@#$%^&*()_+-=[]{}|;':\",./<>?`~",
                )
            for (query in adversarial) {
                recall.recall(query = query, k = 5)
            }
        }

    // ------------------------------------------------------------------
    // Fixture helpers
    // ------------------------------------------------------------------

    private fun docId(n: Int): DocId = "doc-$n"

    private suspend fun addChunk(
        index: IndexStore,
        docId: DocId,
        text: String,
    ): ChunkId =
        index
            .replaceChunks(
                docId,
                listOf(NewChunk(ord = 0, text = text, tokenCount = text.split(" ").size)),
                embedderId = "test-embedder",
                embedderVersion = 1,
            ).single()

    /** Delegates everything except `bm25`, which returns [fixed] (capped at `k`) verbatim. */
    private class FixedBm25IndexStore(
        private val delegate: IndexStore,
        private val fixed: List<ScoredChunk>,
    ) : IndexStore by delegate {
        override suspend fun bm25(
            query: String,
            k: Int,
        ): List<ScoredChunk> = fixed.take(k)
    }

    /** Counts calls to `bm25` — used to prove the `k <= 0` fast path never reaches the store. */
    private class CountingBm25IndexStore(
        private val delegate: IndexStore,
    ) : IndexStore by delegate {
        var bm25Calls: Int = 0
            private set

        override suspend fun bm25(
            query: String,
            k: Int,
        ): List<ScoredChunk> {
            bm25Calls++
            return delegate.bm25(query, k)
        }
    }
}
