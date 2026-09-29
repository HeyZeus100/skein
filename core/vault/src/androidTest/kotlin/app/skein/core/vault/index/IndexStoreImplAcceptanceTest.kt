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
import app.skein.core.model.IndexStore
import app.skein.core.model.LexicalQueryLimits
import app.skein.core.model.NewChunk
import app.skein.core.rag.chunk.Chunker
import app.skein.core.rag.ingest.IngestSteps
import app.skein.core.rag.tokenizers.ApproximateTokenizer
import app.skein.core.vault.db.SkeinSQLiteConnection
import app.skein.core.vault.db.SkeinSQLiteDriver
import app.skein.core.vault.testutil.splitMigrationStatements
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
            val (idx, conn) = freshIndexWithConnection()
            // Seed the corpus with something searchable so FTS5 has real
            // work to do — an empty index would trivially pass.
            // skein-ci54: the document row must exist first —
            // chunks.doc_id REFERENCES documents(id) under
            // PRAGMA foreign_keys = ON.
            seedDocument(conn, "01924a4b-4d29-7000-8000-00000000B111")
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
    public fun ingestRowProbeVerifiesHealthyPostingBelowTheGlobalTop50(): Unit =
        runTest {
            val (idx, conn) = freshIndexWithConnection()
            seedDocument(conn, "crowded")
            idx.replaceChunks(
                "crowded",
                List(60) { NewChunk(it, "crowding crowding crowding", 3) },
                "fake",
                1,
            )
            seedDocument(conn, "target")
            val warnings = mutableListOf<String>()
            val body = "crowding " + "a ".repeat(128)
            val ids =
                IngestSteps(idx, warn = { warnings += it }).indexLexical(
                    "target",
                    Chunker(ApproximateTokenizer).chunk(body),
                    body = body,
                )
            val target = ids.single()

            // Establish the exact false-warning precondition, against real
            // BM25: the posting exists but sixty stronger rows outrank it.
            assertThat(idx.bm25("crowding", 61).map { it.chunkId }).hasSize(61)
            assertThat(idx.bm25("crowding", 50).map { it.chunkId }).doesNotContain(target)
            assertThat(idx.hasLexicalMatch(target, "crowding")).isTrue()
            assertThat(warnings).isEmpty()
            conn.prepare("INSERT INTO chunks_fts(chunks_fts, rank) VALUES('integrity-check', 1)").use { it.step() }
        }

    @Test
    public fun ingestRowProbeWarnsWhenTheActualInsertTriggerIsMissing(): Unit =
        runTest {
            val (idx, conn) = freshIndexWithConnection()
            seedDocument(conn, "missing-trigger")
            conn.prepare("DROP TRIGGER chunks_ai").use { it.step() }
            val warnings = mutableListOf<String>()
            val body = "Confidentiality must never appear in diagnostics."
            val id =
                IngestSteps(idx, warn = { warnings += it })
                    .indexLexical("missing-trigger", Chunker(ApproximateTokenizer).chunk(body), body = body)
                    .single()

            // Both base-table and external-content reads still see the row.
            // Only MATCH tests the missing posting created by this control.
            assertThat(idx.getChunks(listOf(id)).keys).containsExactly(id)
            conn.prepare("SELECT rowid FROM chunks_fts WHERE rowid = ?").use { stmt ->
                stmt.bindLong(1, id)
                assertThat(stmt.step()).isTrue()
            }
            assertThat(idx.hasLexicalMatch(id, "Confidentiality")).isFalse()
            assertThat(warnings).hasSize(1)
            assertThat(warnings.single()).contains("missing for 1 of 1 probed")
            assertThat(warnings.single()).contains("chunks_fts")
            assertThat(warnings.single()).doesNotContain("Confidentiality")
            assertThat(warnings.single()).doesNotContain("missing-trigger")
        }

    @Test
    public fun ingestRowProbeDetectsAMissingLaterPostingWhenTheFirstRowIsHealthy(): Unit =
        runTest {
            val (idx, conn) = freshIndexWithConnection()
            seedDocument(conn, "partial")
            val body = "Searchable first paragraph.\n\nConfidentiality second paragraph."
            val chunks = Chunker(ApproximateTokenizer, targetTokens = 10, overlapTokens = 0).chunk(body)
            assertThat(chunks).hasSize(2)
            val warnings = mutableListOf<String>()
            val damaged =
                object : IndexStore by idx {
                    override suspend fun hasLexicalMatch(
                        chunkId: Long,
                        query: String,
                    ): Boolean {
                        val chunk = idx.getChunks(listOf(chunkId)).getValue(chunkId)
                        if (chunk.ord == 1) {
                            conn
                                .prepare("INSERT INTO chunks_fts(chunks_fts, rowid, text) VALUES('delete', ?, ?)")
                                .use { stmt ->
                                    stmt.bindLong(1, chunkId)
                                    stmt.bindText(2, chunk.text)
                                    stmt.step()
                                }
                        }
                        return idx.hasLexicalMatch(chunkId, query)
                    }
                }

            val ids = IngestSteps(damaged, warn = { warnings += it }).indexLexical("partial", chunks, body = body)

            assertThat(idx.hasLexicalMatch(ids[0], "Searchable")).isTrue()
            assertThat(idx.hasLexicalMatch(ids[1], "Confidentiality")).isFalse()
            assertThat(idx.getChunks(ids)).hasSize(2)
            assertThat(warnings).hasSize(1)
            assertThat(warnings.single()).contains("missing for 1 of 2 probed")
            assertThat(warnings.single()).doesNotContain("Confidentiality")
        }

    @Test
    public fun ingestRowProbeDoesNotTreatAsciiInsideAUnicodeTokenAsAMissingPosting(): Unit =
        runTest {
            val (idx, conn) = freshIndexWithConnection()
            seedDocument(conn, "unicode")
            val warnings = mutableListOf<String>()
            val body = "中文English café cafe\u0301 \uE000secret A"

            val id =
                IngestSteps(idx, warn = { warnings += it })
                    .indexLexical("unicode", Chunker(ApproximateTokenizer).chunk(body), body = body)
                    .single()

            assertThat(idx.hasLexicalMatch(id, "English")).isFalse()
            assertThat(idx.hasLexicalMatch(id, "A")).isTrue()
            assertThat(warnings).isEmpty()
        }

    @Test
    public fun unicodeQueryTermsMatchTheActualDefaultSqliteTokenizer(): Unit =
        runTest {
            val (idx, conn) = freshIndexWithConnection()
            seedDocument(conn, "unicode-only")
            val body = "中文English café cafe\u0301 résumé naïve Русский العربية १२३ \uE000secret 𐐀𐐁"
            val id = idx.replaceChunks("unicode-only", listOf(NewChunk(0, body, 12)), "fake", 1).single()

            assertThat(idx.lexicalTerms(body))
                .containsExactly(
                    "中文english",
                    "cafe",
                    "cafe",
                    "resume",
                    "naive",
                    "русский",
                    "العربية",
                    "१२३",
                    "\uE000secret",
                    "𐐨𐐩",
                ).inOrder()
            for (query in listOf(
                "中文English",
                "CAFÉ",
                "cafe\u0301",
                "résumé",
                "naive",
                "РУССКИЙ",
                "العربية",
                "१२३",
                "\uE000secret",
                "𐐀𐐁",
            )) {
                assertThat(idx.bm25(query, 8).map { it.chunkId }).containsExactly(id)
                assertThat(idx.hasLexicalMatch(id, query)).isTrue()
            }
            // unicode61 keeps mixed-script/private-use runs together. An
            // ASCII suffix is not a separate posting and must not match.
            assertThat(idx.bm25("English", 8)).isEmpty()
            assertThat(idx.bm25("secret", 8)).isEmpty()
            assertThat(idx.getChunks(listOf(id)).getValue(id).text).isEqualTo(body)
            // Default remove_diacritics=1 deliberately differs from a general
            // Unicode normalization library for multi-diacritic codepoints.
            assertThat(idx.lexicalTerms("ộ o\u0323\u0302")).containsExactly("ộ", "o").inOrder()
        }

    @Test
    public fun unicodeTextBindingPreservesSupplementaryAndLegacyModifiedUtf8(): Unit =
        runTest {
            val (_, conn) = freshIndexWithConnection()
            val text = "研究𐐀資料\u0000終"
            conn.prepare("SELECT ?, hex(?)").use { stmt ->
                stmt.bindText(1, text)
                stmt.bindText(2, text)
                assertThat(stmt.step()).isTrue()
                assertThat(stmt.getText(0)).isEqualTo(text)
                assertThat(stmt.getText(1)).isEqualTo("E7A094E7A9B6F0909080E8B387E6969900E7B582")
            }
            // Reproduce the actual old-storage encoding in a durable revision
            // and its JSON metadata. Reading must preserve text and stored bytes.
            seedDocument(conn, "legacy-unicode")
            conn
                .prepare(
                    "INSERT INTO document_revisions VALUES ('legacy-unicode', 'retained-hash', 1, " +
                        "CAST(X'EDA081EDB080C08061' AS TEXT), " +
                        "CAST(X'7B226C6162656C223A22EDA081EDB080227D' AS TEXT), 0, 'test')",
                ).use { it.step() }
            conn
                .prepare(
                    "SELECT body_md_snapshot, frontmatter_snapshot, revision_hash, hex(body_md_snapshot) " +
                        "FROM document_revisions WHERE document_id = 'legacy-unicode'",
                ).use { stmt ->
                    assertThat(stmt.step()).isTrue()
                    assertThat(stmt.getText(0)).isEqualTo("𐐀\u0000a")
                    assertThat(stmt.getText(1)).isEqualTo("{\"label\":\"𐐀\"}")
                    assertThat(stmt.getText(2)).isEqualTo("retained-hash")
                    assertThat(stmt.getText(3)).isEqualTo("EDA081EDB080C08061")
                }
        }

    @Test
    public fun unicodeAdversarialQueriesRemainLiteralAndCannotBroadenToUnrelatedRows(): Unit =
        runTest {
            val (idx, conn) = freshIndexWithConnection()
            seedDocument(conn, "literal-unicode")
            val ids =
                idx.replaceChunks(
                    "literal-unicode",
                    listOf(NewChunk(0, "研究資料", 1), NewChunk(1, "秘密内容", 1)),
                    "fake",
                    1,
                )
            for (query in listOf(
                "研究資料 OR text:秘密* boundary",
                "研究資料 NEAR(秘密) boundary",
                "研究資料\" OR \"秘密 boundary",
                "研究資料\u0000NOT 秘密 boundary",
            )) {
                // FTS keywords and punctuation become literal terms; none of
                // these asks for the full unrelated token 秘密内容.
                assertThat(idx.bm25(query, 8).map { it.chunkId }).containsExactly(ids[0])
            }
            assertThat(idx.bm25("研究資", 8).map { it.chunkId }).containsExactly(ids[0])
        }

    @Test
    public fun unicodeOnlyIngestProbeChecksARealPostingWithoutAsciiFallback(): Unit =
        runTest {
            val (idx, conn) = freshIndexWithConnection()
            seedDocument(conn, "healthy-unicode")
            val warnings = mutableListOf<String>()
            val body = "研究資料 保管場所"
            val id =
                IngestSteps(idx, warn = { warnings += it })
                    .indexLexical("healthy-unicode", Chunker(ApproximateTokenizer).chunk(body), body = body)
                    .single()
            assertThat(idx.hasLexicalMatch(id, "研究資料")).isTrue()
            assertThat(warnings).isEmpty()
        }

    @Test
    public fun unicodeOnlyIngestProbeWarnsWhenTheInsertTriggerIsMissing(): Unit =
        runTest {
            val (idx, conn) = freshIndexWithConnection()
            seedDocument(conn, "missing-unicode")
            conn.prepare("DROP TRIGGER chunks_ai").use { it.step() }
            val warnings = mutableListOf<String>()
            val body = "研究資料 保管場所"
            val id =
                IngestSteps(idx, warn = { warnings += it })
                    .indexLexical("missing-unicode", Chunker(ApproximateTokenizer).chunk(body), body = body)
                    .single()
            assertThat(idx.getChunks(listOf(id)).getValue(id).text).isEqualTo(body)
            assertThat(idx.hasLexicalMatch(id, "研究資料")).isFalse()
            assertThat(warnings).hasSize(1)
            assertThat(warnings.single()).contains("missing for 1 of 1 probed")
            assertThat(warnings.single()).doesNotContain("研究資料")
            assertThat(warnings.single()).doesNotContain("missing-unicode")
        }

    @Test
    public fun unicodeOnlyIngestProbeFindsAMissingMiddleRowBetweenHealthyRows(): Unit =
        runTest {
            val (idx, conn) = freshIndexWithConnection()
            seedDocument(conn, "partial-unicode")
            val body = "研究資料\n\n保管場所\n\n運搬記録"
            val chunks = Chunker(ApproximateTokenizer, targetTokens = 1, overlapTokens = 0).chunk(body)
            assertThat(chunks).hasSize(3)
            val warnings = mutableListOf<String>()
            val damaged =
                object : IndexStore by idx {
                    override suspend fun hasLexicalMatch(
                        chunkId: Long,
                        query: String,
                    ): Boolean {
                        val chunk = idx.getChunks(listOf(chunkId)).getValue(chunkId)
                        if (chunk.ord == 1) {
                            conn
                                .prepare("INSERT INTO chunks_fts(chunks_fts, rowid, text) VALUES('delete', ?, ?)")
                                .use { stmt ->
                                    stmt.bindLong(1, chunkId)
                                    stmt.bindText(2, chunk.text)
                                    stmt.step()
                                }
                        }
                        return idx.hasLexicalMatch(chunkId, query)
                    }
                }
            val ids =
                IngestSteps(
                    damaged,
                    warn = { warnings += it },
                ).indexLexical("partial-unicode", chunks, body = body)
            assertThat(idx.hasLexicalMatch(ids[0], "研究資料")).isTrue()
            assertThat(idx.hasLexicalMatch(ids[1], "保管場所")).isFalse()
            assertThat(idx.hasLexicalMatch(ids[2], "運搬記録")).isTrue()
            assertThat(idx.getChunks(ids)).hasSize(3)
            assertThat(warnings).hasSize(1)
            assertThat(warnings.single()).contains("missing for 1 of 3 probed")
            assertThat(warnings.single()).doesNotContain("保管場所")
        }

    @Test
    public fun actualTokenizerBoundsSkipWholeTokensAndNeverInventPostingFragments(): Unit =
        runTest {
            val (idx, _) = freshIndexWithConnection()
            val max = LexicalQueryLimits.MAX_TERM_UTF8_BYTES
            assertThat(idx.lexicalTerms("界".repeat(max) + " 研究資料")).containsExactly("研究資料")
            assertThat(idx.lexicalTerms("x".repeat(max))).containsExactly("x".repeat(max))
            assertThat(idx.lexicalTerms("x ".repeat(200))).hasSize(LexicalQueryLimits.MAX_TERMS)
            assertThat(idx.lexicalTerms("x".repeat(LexicalQueryLimits.MAX_TEXT_UTF8_BYTES + 1))).isEmpty()
            assertThat(idx.lexicalTerms("🚀 \u0301 !!!")).isEmpty()
        }

    @Test
    public fun knnReturnsExactlyKRowsOverA10000VectorCorpus(): Unit =
        runTest {
            val (idx, conn) = freshIndexWithConnection()
            val docId = "01924a4b-4d29-7000-8000-000000010000"
            // skein-ci54: chunks.doc_id REFERENCES documents(id) under
            // PRAGMA foreign_keys = ON.
            seedDocument(conn, docId)
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
            val (idx, conn) = freshIndexWithConnection()
            val src = "01924a4b-4d29-7000-8000-00000000E001"
            val dstNote = "01924a4b-4d29-7000-8000-00000000E002"
            val dstEntity = "entity:1"
            // OBJECT_LIFECYCLE_SPEC.md LC-06: an edge write from a document
            // with no `documents` row is skipped, so the source must exist —
            // as it always does for EntityIndexer, which writes from `document.id`.
            seedDocument(conn, src)

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
            // skein-ci54: chunks.doc_id REFERENCES documents(id) under
            // PRAGMA foreign_keys = ON.
            seedDocument(conn, docId)
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
            val (idx, conn) = freshIndexWithConnection()
            val docId = "01924a4b-4d29-7000-8000-00000000R012"
            // skein-ci54: chunks.doc_id REFERENCES documents(id) under
            // PRAGMA foreign_keys = ON.
            seedDocument(conn, docId)
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
        // Apply the production manifest, including FTS secure-delete (010),
        // so posting checks exercise the current index configuration.
        val migrationFiles =
            requireNotNull(javaClass.classLoader?.getResourceAsStream("migrations/INDEX.txt"))
                .bufferedReader()
                .use { reader -> reader.readLines() }
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .sorted()
        for (fileName in migrationFiles) {
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
}
