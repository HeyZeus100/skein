// Acceptance-criterion checks specific to `IndexStoreImpl` (E2.I15).
//
// The generic semantics (replaceChunks/knn/bm25/edgesTo/neighborhood) are
// covered by `IndexStoreImplContractTest`. This file layers the E2.I15
// bd acceptance bullet points that are impl-specific:
//   • `bm25("it's a \"quoted\" (weird) query")` and 19 other adversarial
//     strings do NOT throw against real FTS5.
//   • `knn` returns exactly `k` rows even when the corpus is larger than
//     `k` (timing is logged, not asserted, per the plan).
//   • `replaceEdges(src, kinds={WIKILINK}, …)` leaves an ENTITY edge on
//     the same source untouched.
//   • `observeChanges()` publishes nothing for a transaction that rolled
//     back (bd `skein-rkxi`). This is the one clause of
//     `IndexStore.observeChanges`'s guarantee that `IndexStoreContractTest`
//     cannot check portably — `InMemoryIndexStore` has no transaction to
//     roll back — so it is pinned here, against the real BEGIN/ROLLBACK.
//
// Follow-up (skein-k3b2): pending CI emulator; this class compiles as
// part of `:app:check` and will run once skein-k3b2 lands.

package app.skein.core.vault.index

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.skein.core.model.Edge
import app.skein.core.model.EdgeKind
import app.skein.core.model.IndexChange
import app.skein.core.model.NewChunk
import app.skein.core.vault.db.SkeinSQLiteConnection
import app.skein.core.vault.db.SkeinSQLiteDriver
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
public class IndexStoreImplAcceptanceTest {
    private val opened: MutableList<IndexStoreImpl> = mutableListOf()

    @After
    public fun tearDown() {
        for (impl in opened) {
            try {
                impl.close()
            } catch (_: Throwable) {
                // ignore
            }
        }
        opened.clear()
    }

    @Test
    public fun bm25DoesNotThrowOnTwentyAdversarialQueryStrings(): Unit =
        runTest {
            val idx = freshIndex()
            // Seed the corpus with something searchable so FTS5 has real
            // work to do — an empty index would trivially pass.
            idx.replaceChunks(
                docId = "01924a4b-4d29-7000-8000-00000000B111",
                chunks =
                    listOf(
                        NewChunk(ord = 0, text = "the quick brown fox", tokenCount = 4),
                        NewChunk(ord = 1, text = "lorem ipsum dolor sit amet", tokenCount = 5),
                    ),
                embedderId = "fake",
                embedderVersion = 1,
            )
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
                // Fails the test if any string throws — no assertion on
                // the returned hits list beyond "the call returned".
                idx.bm25(query = query, k = 5)
            }
        }

    @Test
    public fun knnReturnsExactlyKRowsOverA10000VectorCorpus(): Unit =
        runTest {
            val idx = freshIndex()
            val docId = "01924a4b-4d29-7000-8000-000000010000"
            val n = 10_000
            val chunks = List(n) { i -> NewChunk(ord = i, text = "chunk-$i", tokenCount = 1) }
            val ids = idx.replaceChunks(docId, chunks, "fake", 1)
            val rng = Random(seed = 0xC0FFEEL)
            val embeddings =
                ids.map { id ->
                    val vec = ByteArray(IndexSql.VEC_INT8_DIM)
                    rng.nextBytes(vec)
                    id to vec
                }
            idx.putEmbeddings(embeddings)

            val queryVec = ByteArray(IndexSql.VEC_INT8_DIM).also { rng.nextBytes(it) }
            val k = 32
            val start = System.nanoTime()
            val hits = idx.knn(queryInt8 = queryVec, k = k)
            val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
            // Log-only per the acceptance criterion — do not assert the
            // 50 ms budget in unit-tests, only on the emulator smoke.
            android.util.Log.i(
                "IndexStoreImpl",
                "knn 10_000 corpus, k=$k → ${hits.size} rows in ${"%.2f".format(elapsedMs)} ms",
            )
            assertThat(hits.size).isEqualTo(k)
        }

    @Test
    public fun replaceEdgesWithWIKILINKDoesNotTouchENTITYEdgesOfTheSameSource(): Unit =
        runTest {
            val idx = freshIndex()
            val src = "01924a4b-4d29-7000-8000-00000000E001"
            val dstNote = "01924a4b-4d29-7000-8000-00000000E002"
            val dstEntity = "entity:1"

            // Seed one WIKILINK and one ENTITY edge from the same source.
            idx.replaceEdges(
                srcId = src,
                kinds = setOf(EdgeKind.WIKILINK, EdgeKind.ENTITY),
                edges =
                    listOf(
                        Edge(srcId = src, dstId = dstNote, kind = EdgeKind.WIKILINK, createdAt = 1L),
                        Edge(srcId = src, dstId = dstEntity, kind = EdgeKind.ENTITY, createdAt = 2L),
                    ),
            )
            // Now rewrite ONLY the WIKILINK edges — the ENTITY edge must
            // survive because its kind was not in the `kinds` filter.
            idx.replaceEdges(
                srcId = src,
                kinds = setOf(EdgeKind.WIKILINK),
                edges = emptyList(),
            )
            val remaining = idx.edgesFrom(src)
            assertThat(remaining.map { it.kind }).containsExactly(EdgeKind.ENTITY)
            assertThat(remaining.single().dstId).isEqualTo(dstEntity)
        }

    @Test
    public fun aReplaceChunksWhoseInsertAbortsRollsBackAndPublishesNoIndexChange(): Unit =
        runTest {
            val (idx, conn) = freshIndexWithConnection()
            val docId = "01924a4b-4d29-7000-8000-00000000R011"
            val survivingIds =
                idx.replaceChunks(
                    docId = docId,
                    chunks = listOf(NewChunk(ord = 0, text = "original text", tokenCount = 2)),
                    embedderId = "fake",
                    embedderVersion = 1,
                )

            val seen = mutableListOf<IndexChange>()
            val collector =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    idx.observeChanges().collect { seen += it }
                }
            runCurrent()

            // Fail the write *mid-transaction*, after its DELETE has
            // already run — an ABORT trigger on INSERT is the most
            // DDL-agnostic way to do that, and exercises exactly the path
            // `IndexStoreImpl.transaction` rolls back on.
            conn
                .prepare(
                    "CREATE TRIGGER chunks_reject_insert BEFORE INSERT ON chunks " +
                        "BEGIN SELECT RAISE(ABORT, 'injected failure'); END",
                ).use { it.step() }

            val thrown =
                runCatching {
                    idx.replaceChunks(
                        docId = docId,
                        chunks = listOf(NewChunk(ord = 0, text = "replacement text", tokenCount = 2)),
                        embedderId = "fake",
                        embedderVersion = 1,
                    )
                }.exceptionOrNull()
            runCurrent()

            assertThat(thrown).isNotNull()
            // The rollback restored the pre-call chunks …
            assertThat(idx.getChunks(survivingIds).keys).containsExactlyElementsIn(survivingIds)
            // … and nothing was published for work that never committed.
            assertThat(seen).isEmpty()
            collector.cancel()
        }

    @Test
    public fun aCommittedReplaceChunksPublishesExactlyOneChunksReplaced(): Unit =
        runTest {
            val idx = freshIndex()
            val docId = "01924a4b-4d29-7000-8000-00000000R012"
            val seen = mutableListOf<IndexChange>()
            val collector =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    idx.observeChanges().collect { seen += it }
                }
            runCurrent()

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
            runCurrent()

            assertThat(seen).containsExactly(IndexChange.ChunksReplaced(docId))
            collector.cancel()
        }

    // ------------------------------------------------------------------

    private fun freshIndex(): IndexStoreImpl = freshIndexWithConnection().first

    private fun freshIndexWithConnection(): Pair<IndexStoreImpl, SkeinSQLiteConnection> {
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
        return impl to conn
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
