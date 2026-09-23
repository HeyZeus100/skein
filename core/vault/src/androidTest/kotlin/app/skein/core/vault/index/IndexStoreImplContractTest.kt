// Instrumented contract test for `IndexStoreImpl` (E2.I15). Runs the
// shared `IndexStoreContractTest` suite from `:testing` against the real
// SQL-backed impl over a `:memory:` SQLCipher connection with `sqlite-vec`
// and FTS5 live — the same libskein_sqlite.so that ships with the app.
//
// Follow-up (skein-k3b2): the API 35 emulator is not yet provisioned in
// CI, so this class is compiled-but-not-executed on the Gradle `check`
// path. Once skein-k3b2 lands, `connectedFossDebugAndroidTest` will run
// this suite headless on every PR.

package app.skein.core.vault.index

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.skein.core.model.DocId
import app.skein.core.model.IndexStore
import app.skein.core.vault.db.SkeinSQLiteConnection
import app.skein.core.vault.db.SkeinSQLiteDriver
import app.skein.core.vault.testutil.splitMigrationStatements
import app.skein.testing.IndexStoreContractTest
import org.junit.After
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
public class IndexStoreImplContractTest : IndexStoreContractTest() {
    private val openImpls: MutableList<IndexStoreImpl> = mutableListOf()

    // skein-ci54: kept alongside `openImpls` (same connection, same
    // lifetime) so `seedDocument` can insert on the connection the most
    // recent `index()` call opened.
    private val openConnections: MutableList<SkeinSQLiteConnection> = mutableListOf()

    @After
    public fun tearDown() {
        // Close every impl handed out during the test. `close()` shuts
        // the SQLiteConnection, which in turn drops the SQLCipher-keyed
        // in-memory DB.
        for (impl in openImpls) {
            try {
                impl.close()
            } catch (_: Throwable) {
                // Best effort — the assertion has already run or thrown.
            }
        }
        openImpls.clear()
        openConnections.clear()
    }

    /**
     * skein-ci54: `IndexStore` has no `documents` table access, so this
     * inserts a minimal row directly on the same connection `index()` just
     * opened — the id/kind/title/timestamps beyond `id` are irrelevant to
     * every case in the shared suite; only the row's existence matters for
     * `chunks.doc_id REFERENCES documents(id) ON DELETE CASCADE` under
     * `PRAGMA foreign_keys = ON`.
     */
    override fun seedDocument(docId: DocId) {
        openConnections
            .last()
            .prepare(
                "INSERT INTO documents(id, kind, title, created_at, updated_at) VALUES (?, 'note', 'seed', 0, 0)",
            ).use { stmt ->
                stmt.bindText(1, docId)
                stmt.step()
            }
    }

    override fun index(): IndexStore {
        // Fresh unencrypted in-memory DB per test (no key argument to
        // SkeinSQLiteDriver; verifyExtensions still asserts vec/FTS5
        // linkage). Then run every production migration statement-by-
        // statement, using the same trigger-aware splitter as
        // `SchemaLoadInstrumentedTest`. All four are needed, not just 001:
        // `IndexStoreImpl.replaceChunks` (skein-zx15) writes
        // `chunks.revision_hash` (003) and `chunks.byte_start`/`byte_end`
        // (008) on every insert, so a chunks table with only 001's columns
        // would fail every `replaceChunks` call in this suite with "no such
        // column".
        val driver = SkeinSQLiteDriver()
        val conn = driver.openWithKey(":memory:", passphrase = null) as SkeinSQLiteConnection
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
        openImpls += impl
        openConnections += conn
        return impl
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
