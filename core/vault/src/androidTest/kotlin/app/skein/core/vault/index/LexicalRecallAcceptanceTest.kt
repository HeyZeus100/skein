// Instrumented acceptance test for `LexicalRecall` (skein-3rj3, gap on
// plan `E5.I6`) against the real SQL-backed `IndexStoreImpl` — real FTS5
// grammar and real `bm25()`, not `:testing`'s term-count approximation
// (`LexicalRecallTest` in `:core:rag` covers the fake). This is the
// androidTest referenced by `LexicalRecallTest`'s file header and by
// skein-b8v's own acceptance criteria ("Instrumented test: ... `recall
// ("sqlite cipher")` returns the chunk mentioning both terms first").
//
// Follow-up (skein-k3b2): the API 35 emulator is not yet provisioned in
// CI, so — like `IndexStoreImplAcceptanceTest` and
// `IndexStoreImplContractTest` — this class is compiled-but-not-executed
// on the Gradle `check` path (`:core:vault:compileFossDebugAndroidTestKotlin`).
// Once skein-k3b2 lands, `connectedFossDebugAndroidTest` will run it.

package app.skein.core.vault.index

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.skein.core.model.NewChunk
import app.skein.core.rag.recall.LexicalRecall
import app.skein.core.vault.db.SkeinSQLiteConnection
import app.skein.core.vault.db.SkeinSQLiteDriver
import app.skein.core.vault.testutil.splitMigrationStatements
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
public class LexicalRecallAcceptanceTest {
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
    public fun recallOfSqliteCipherRanksTheBothTermsChunkFirstWithScoresNormalizedTo01(): Unit =
        runTest {
            val (idx, conn) = freshIndexWithConnection()
            // skein-ci54: chunks.doc_id REFERENCES documents(id) under
            // PRAGMA foreign_keys = ON.
            seedDocument(conn, "01924a4b-4d29-7000-8000-00000000C111")
            seedDocument(conn, "01924a4b-4d29-7000-8000-00000000C112")
            seedDocument(conn, "01924a4b-4d29-7000-8000-00000000C113")
            idx.replaceChunks(
                docId = "01924a4b-4d29-7000-8000-00000000C111",
                chunks =
                    listOf(
                        NewChunk(
                            ord = 0,
                            text = "SQLite Cipher wraps SQLite with transparent AES encryption",
                            tokenCount = 8,
                        ),
                    ),
                embedderId = "fake",
                embedderVersion = 1,
            )
            idx.replaceChunks(
                docId = "01924a4b-4d29-7000-8000-00000000C112",
                chunks =
                    listOf(
                        NewChunk(ord = 0, text = "SQLite is a small, fast, embedded database engine", tokenCount = 8),
                    ),
                embedderId = "fake",
                embedderVersion = 1,
            )
            idx.replaceChunks(
                docId = "01924a4b-4d29-7000-8000-00000000C113",
                chunks =
                    listOf(
                        NewChunk(ord = 0, text = "Cipher suites negotiate TLS session encryption", tokenCount = 6),
                    ),
                embedderId = "fake",
                embedderVersion = 1,
            )

            val results = LexicalRecall(idx).recall("sqlite cipher")

            assertThat(results).isNotEmpty()
            // The chunk mentioning both terms scores highest and is
            // normalized to exactly 1.0.
            val top = results.first()
            assertThat(top.score).isWithin(1e-9).of(1.0)
            for (r in results) {
                assertThat(r.score).isAtLeast(0.0)
                assertThat(r.score).isAtMost(1.0)
            }
        }

    @Test
    public fun lexicalRecallDoesNotThrowOnTwentyAdversarialQueryStrings(): Unit =
        runTest {
            val (idx, conn) = freshIndexWithConnection()
            // skein-ci54: chunks.doc_id REFERENCES documents(id) under
            // PRAGMA foreign_keys = ON.
            seedDocument(conn, "01924a4b-4d29-7000-8000-00000000C211")
            idx.replaceChunks(
                docId = "01924a4b-4d29-7000-8000-00000000C211",
                chunks =
                    listOf(
                        NewChunk(ord = 0, text = "the quick brown fox", tokenCount = 4),
                        NewChunk(ord = 1, text = "lorem ipsum dolor sit amet", tokenCount = 5),
                    ),
                embedderId = "fake",
                embedderVersion = 1,
            )
            val recall = LexicalRecall(idx)
            // Same 20-string adversarial set as
            // `IndexStoreImplAcceptanceTest.\`bm25 does not throw on twenty adversarial query strings\``.
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

    /**
     * Precondition helper (skein-ci54): inserts a minimal `documents` row
     * for [docId] so a subsequent `replaceChunks(docId, ...)` call
     * satisfies `chunks.doc_id REFERENCES documents(id) ON DELETE CASCADE`
     * (`001_initial.sql`) under `PRAGMA foreign_keys = ON` (skein-gg11.10).
     * Production always creates the document through `VaultRepository`
     * before RAG ingest ever calls `IndexStore.replaceChunks`; this
     * fixture never did, which is exactly the fixture debt skein-ci54
     * closes. Columns beyond `id`/`kind`/`title`/timestamps are
     * irrelevant to every test in this file.
     */
    private fun seedDocument(
        conn: SkeinSQLiteConnection,
        docId: String,
    ) {
        conn
            .prepare(
                "INSERT INTO documents(id, kind, title, created_at, updated_at) VALUES (?, 'note', 'seed', 0, 0)",
            ).use { stmt ->
                stmt.bindText(1, docId)
                stmt.step()
            }
    }

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
            for (statement in splitMigrationStatements(sql)) {
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
    }
}
