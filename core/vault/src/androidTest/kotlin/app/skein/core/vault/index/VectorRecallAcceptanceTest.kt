// Instrumented acceptance test for `VectorRecall` (skein-4uu, gap on plan
// `E5.I7`) against the real SQL-backed `IndexStoreImpl` — real vec0 cosine
// distance, not `:testing`'s dot-product approximation
// (`VectorRecallTest` in `:core:rag` covers the fake). This is the
// androidTest referenced by `VectorRecallTest`'s file header.
//
// The real `EmbedderService` (nomic-embed, skein-079) is M0-gated and not
// available here, so this test drives `IndexStoreImpl.knn` with two
// hand-built, exactly orthogonal int8 vectors (disjoint halves of the
// 256-d space) via a minimal `FixedVectorEmbedder` stub instead of a real
// embedding pipeline — enough to prove the real store's distance→
// similarity conversion (`1.0 - distance`) composes correctly with
// `VectorRecall`'s `(s + 1) / 2` fusion mapping end to end. Real semantic
// ranking (e.g. the plan's literal `recall("feline pets")` cats example)
// is `skein-079`'s job once the real backend lands.
//
// Follow-up (skein-k3b2): the API 35 emulator is not yet provisioned in
// CI, so — like `IndexStoreImplAcceptanceTest` and
// `LexicalRecallAcceptanceTest` — this class is compiled-but-not-executed
// on the Gradle `check` path (`:core:vault:compileFossDebugAndroidTestKotlin`).
// Once skein-k3b2 lands, `connectedFossDebugAndroidTest` will run it.

package app.skein.core.vault.index

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.skein.core.rag.recall.VectorRecall
import app.skein.core.vault.db.SkeinSQLiteConnection
import app.skein.core.vault.db.SkeinSQLiteDriver
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import us.aherrera.skein.core.model.EmbedderService
import us.aherrera.skein.core.model.EntitySpan
import us.aherrera.skein.core.model.Int8Quantizer
import us.aherrera.skein.core.model.Model
import us.aherrera.skein.core.model.NewChunk
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
public class VectorRecallAcceptanceTest {
    private val opened: MutableList<IndexStoreImpl> = mutableListOf()

    @After
    public fun tearDown() {
        for (impl in opened) {
            try {
                impl.close()
            } catch (_: Throwable) {
                // Best effort — the assertion has already run or thrown.
            }
        }
        opened.clear()
    }

    @Test
    public fun `recall ranks the chunk matching the query vector first, scores mapped to 0,1`(): Unit =
        runTest {
            val idx = freshIndex()
            val chunkA =
                idx
                    .replaceChunks(
                        docId = "01924a4b-4d29-7000-8000-00000000D111",
                        chunks =
                            listOf(
                                NewChunk(ord = 0, text = "chunk aligned with the query vector", tokenCount = 5),
                            ),
                        embedderId = "fake",
                        embedderVersion = 1,
                    ).single()
            val chunkB =
                idx
                    .replaceChunks(
                        docId = "01924a4b-4d29-7000-8000-00000000D112",
                        chunks =
                            listOf(
                                NewChunk(ord = 0, text = "chunk orthogonal to the query vector", tokenCount = 5),
                            ),
                        embedderId = "fake",
                        embedderVersion = 1,
                    ).single()

            // Disjoint halves of the 256-d space: dot product is exactly
            // zero regardless of quantization noise, so cosine similarity
            // is exactly 0 (orthogonal), mapping to a fused score of 0.5.
            val vectorA = halfActiveVector(firstHalf = true)
            val vectorB = halfActiveVector(firstHalf = false)
            idx.putEmbeddings(listOf(chunkA to vectorA, chunkB to vectorB))

            val results = VectorRecall(idx, FixedVectorEmbedder(vectorA)).recall("query matching chunk A")

            assertThat(results).hasSize(2)
            val top = results.first()
            assertThat(top.chunkId).isEqualTo(chunkA)
            // Cosine(A, A) == 1 exactly (same vector) -> mapped score == 1.0.
            assertThat(top.score).isWithin(1e-6).of(1.0)
            // Cosine(A, B) == 0 (orthogonal) -> mapped score == 0.5.
            val bScore = results.first { it.chunkId == chunkB }.score
            assertThat(bScore).isWithin(0.05).of(0.5)
            for (r in results) {
                assertThat(r.score).isAtLeast(0.0)
                assertThat(r.score).isAtMost(1.0)
            }
        }

    // ------------------------------------------------------------------

    /**
     * A 256-d unit vector with `1/sqrt(128)` in the first (or second) half
     * of its components and zero elsewhere, then int8-quantized — two such
     * vectors on opposite halves have zero dot product (exactly orthogonal)
     * regardless of quantization rounding, since a product with an exact
     * `0` component stays `0`.
     */
    private fun halfActiveVector(firstHalf: Boolean): ByteArray {
        val raw = FloatArray(VECTOR_DIM)
        val value = 1f / sqrt(HALF_DIM.toFloat())
        val range = if (firstHalf) 0 until HALF_DIM else HALF_DIM until VECTOR_DIM
        for (i in range) raw[i] = value
        return Int8Quantizer.quantize(raw)
    }

    private fun freshIndex(): IndexStoreImpl {
        val driver = SkeinSQLiteDriver()
        val conn = driver.openWithKey(":memory:", passphrase = null) as SkeinSQLiteConnection
        // skein-zx15: chunks.revision_hash (003) and chunks.byte_start/
        // byte_end (008) are written by every replaceChunks call, so the
        // schema here must include those migrations too, not just 001.
        for (fileName in SCHEMA_MIGRATION_FILES) {
            val sql =
                requireNotNull(
                    javaClass.classLoader?.getResourceAsStream("migrations/$fileName"),
                ) { "migrations/$fileName not on the classpath" }
                    .use { it.readBytes().toString(Charsets.UTF_8) }
            for (statement in splitOnSentinel(sql)) {
                conn.prepare(statement).use { it.step() }
            }
        }
        val impl = IndexStoreImpl(conn)
        opened += impl
        return impl
    }

    /**
     * Minimal `EmbedderService` stub that always returns a fixed,
     * caller-supplied int8 vector from `embedQuery`/`embedDocuments`,
     * standing in for the real (M0-gated, skein-079) nomic-embed pipeline
     * so this test can drive `IndexStoreImpl.knn` with vectors it fully
     * controls. Every other member is unused by `VectorRecall` and throws
     * or returns a trivial value if ever called.
     */
    private class FixedVectorEmbedder(
        private val vector: ByteArray,
    ) : EmbedderService {
        override suspend fun load(
            embed: Model,
            ner: Model?,
            rerank: Model?,
        ): Result<Unit> = Result.success(Unit)

        override suspend fun embedDocuments(texts: List<String>): List<ByteArray> = texts.map { vector }

        override suspend fun embedQuery(text: String): ByteArray = vector

        override suspend fun extractEntities(
            text: String,
            labels: List<String>,
        ): List<EntitySpan> = emptyList()

        override suspend fun rerank(
            query: String,
            candidates: List<String>,
        ): FloatArray = FloatArray(candidates.size)

        override suspend fun countTokens(text: String): Int = text.split(Regex("\\s+")).size

        override suspend fun unload() {}

        override val embedderId: String = "fixed-vector-embedder"
        override val embedderVersion: Int = 1
    }

    private companion object {
        // skein-zx15: schema for a fresh :memory: chunks/ingest_queue
        // table that has chunks.revision_hash (003) and
        // chunks.byte_start/byte_end (008) — every replaceChunks call
        // in this suite writes those columns.
        val SCHEMA_MIGRATION_FILES: List<String> =
            listOf(
                "001_initial.sql",
                "003_document_revisions.sql",
                "007_drop_attachment_master_key.sql",
                "008_ingest_attempts.sql",
            )

        const val VECTOR_DIM: Int = 256
        const val HALF_DIM: Int = VECTOR_DIM / 2

        fun splitOnSentinel(sql: String): List<String> {
            val raw = sql.split("--;")
            val cleaned =
                raw.map { chunk ->
                    chunk
                        .lineSequence()
                        .map { it.trimEnd() }
                        .filter { line -> line.isNotBlank() && !line.trimStart().startsWith("--") }
                        .joinToString(separator = "\n")
                        .trim()
                        .removeSuffix(";")
                        .trim()
                }
            return cleaned.filter { it.isNotEmpty() }
        }
    }
}
