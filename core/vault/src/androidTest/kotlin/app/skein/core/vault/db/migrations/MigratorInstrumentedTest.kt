// On-device tests for [Migrator] / [SchemaInspector] against the real
// libskein_sqlite.so + libskein_sqlite_jni.so (`E2.I2` / skein-5my).
// Skipped on host JVM (no `.so`) — see `MigrationStatementSplitterTest`
// and `MigratorTest` for the JVM-only coverage (splitter edge cases,
// discovery-manifest error paths).
//
// Follow-up: skein-k3b2 tracks provisioning an API 35 emulator inside CI
// so `connectedFossDebugAndroidTest` can run headless; no device/emulator
// was available to actually execute this suite in the skein-5my worktree
// (see that issue's bd notes). This class is compiled in the ordinary
// Gradle `check` (which does not run instrumented tests) so a broken
// statement surfaces as a compile-time error even without the device
// queue.

package app.skein.core.vault.db.migrations

import androidx.sqlite.SQLiteConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.skein.core.vault.db.SkeinSQLiteDriver
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MigratorInstrumentedTest {
    private val tempFiles = mutableListOf<File>()

    @After
    fun cleanup() {
        tempFiles.forEach { it.delete() }
    }

    private fun tempDbFile(): File {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val f = File.createTempFile("skein-migrator-test-", ".db", ctx.cacheDir)
        tempFiles += f
        // createTempFile leaves an empty file; SQLite is happy to open+init.
        f.delete()
        return f
    }

    /** Deterministic per-test key: same [seed] always yields the same 32 bytes. */
    private fun randomKey(seed: Byte): ByteArray = ByteArray(32) { (seed + it).toByte() }

    // --- Fresh migrate: version + full schema object set (§4.9) ---

    @Test
    fun freshDatabaseMigratesToVersion1WithAllSchemaObjects() {
        val dbFile = tempDbFile()

        val result = Migrator(SkeinSQLiteDriver(randomKey(1))).migrate(dbFile.absolutePath)

        assertThat(result.fromVersion).isEqualTo(0)
        assertThat(result.toVersion).isEqualTo(1)

        SkeinSQLiteDriver(randomKey(1)).open(dbFile.absolutePath).use { conn ->
            val inspector = SchemaInspector(conn)
            assertThat(inspector.userVersion()).isEqualTo(1)
            assertThat(inspector.tables()).containsAtLeast(
                "documents",
                "chunks",
                "chunks_fts",
                "chunks_vec",
                "edges",
                "entities",
                "messages",
                "personas",
                "models",
                "ingest_queue",
                "attachment_master_key",
                "attachment_keys",
            )
            assertThat(inspector.virtualTables()).containsExactly("chunks_fts", "chunks_vec")
            assertThat(inspector.triggers()).containsExactly(
                "chunks_ai",
                "chunks_ad",
                "chunks_au",
                "documents_ai_ingest",
                "documents_au_ingest",
            )
            assertThat(inspector.indexes()).containsAtLeast(
                "idx_documents_updated",
                "idx_documents_persona",
                "idx_chunks_doc",
                "idx_edges_dst",
                "idx_messages_chat",
                "idx_documents_title_nocase",
                "idx_documents_kind_updated",
                "idx_attachment_keys_version",
            )
        }
    }

    // --- Idempotency ---

    @Test
    fun runningMigrateTwiceIsANoop() {
        val dbFile = tempDbFile()
        val first = Migrator(SkeinSQLiteDriver(randomKey(2))).migrate(dbFile.absolutePath)
        assertThat(first.toVersion).isEqualTo(1)

        val second = Migrator(SkeinSQLiteDriver(randomKey(2))).migrate(dbFile.absolutePath)

        assertThat(second.fromVersion).isEqualTo(1)
        assertThat(second.toVersion).isEqualTo(1)
    }

    // --- Broken migration -> ROLLBACK ---

    @Test
    fun brokenMigrationRollsBackAndLeavesUserVersionUnchanged() {
        val dbFile = tempDbFile()
        val migrator =
            Migrator(
                driver = SkeinSQLiteDriver(randomKey(3)),
                migrationsPath = "migrations-bad",
            )

        try {
            migrator.migrate(dbFile.absolutePath)
            error("expected the broken migration (999_bad.sql) to throw")
        } catch (_: Throwable) {
            // Expected: `CREATE TABLE syntax_error (x INT,);` is a genuine
            // SQLite syntax error (trailing comma before the closing
            // paren) — see 999_bad.sql's header comment for why the
            // obvious-looking `INVALID_SQL_HERE` alternative does NOT
            // actually fail.
        }

        SkeinSQLiteDriver(randomKey(3)).open(dbFile.absolutePath).use { conn ->
            val inspector = SchemaInspector(conn)
            assertThat(inspector.userVersion()).isEqualTo(0)
            assertThat(inspector.tables()).doesNotContain("valid_but_isolated")
            assertThat(inspector.tables()).doesNotContain("syntax_error")
        }
    }

    // --- ingest_queue trigger discrimination (documents_ai_ingest / documents_au_ingest) ---

    @Test
    fun insertingNoteDocumentEnqueuesCreatedReason() {
        val dbFile = tempDbFile()
        Migrator(SkeinSQLiteDriver(randomKey(4))).migrate(dbFile.absolutePath)

        SkeinSQLiteDriver(randomKey(4)).open(dbFile.absolutePath).use { conn ->
            insertNote(conn, id = "doc-1", title = "Title", bodyMd = "body", createdAt = 100, updatedAt = 100)

            val queued = readIngestQueue(conn, docId = "doc-1")
            assertThat(queued).isNotNull()
            assertThat(queued!!.reason).isEqualTo("created")
            assertThat(queued.queuedAt).isEqualTo(100L)
        }
    }

    @Test
    fun updatingBodyMdEnqueuesUpdatedReason() {
        val dbFile = tempDbFile()
        Migrator(SkeinSQLiteDriver(randomKey(5))).migrate(dbFile.absolutePath)

        SkeinSQLiteDriver(randomKey(5)).open(dbFile.absolutePath).use { conn ->
            insertNote(conn, id = "doc-2", title = "Title", bodyMd = "original", createdAt = 100, updatedAt = 100)

            exec(conn, "UPDATE documents SET body_md = 'changed', updated_at = 200 WHERE id = 'doc-2';")

            val queued = readIngestQueue(conn, docId = "doc-2")
            assertThat(queued).isNotNull()
            assertThat(queued!!.reason).isEqualTo("updated")
            assertThat(queued.queuedAt).isEqualTo(200L)
        }
    }

    @Test
    fun updatingOnlyPersonaIdDoesNotEnqueue() {
        val dbFile = tempDbFile()
        Migrator(SkeinSQLiteDriver(randomKey(6))).migrate(dbFile.absolutePath)

        SkeinSQLiteDriver(randomKey(6)).open(dbFile.absolutePath).use { conn ->
            exec(
                conn,
                "INSERT INTO personas(id, name, created_at) VALUES ('persona-1', 'Persona One', 50);",
            )
            insertNote(conn, id = "doc-3", title = "Title", bodyMd = "unchanged", createdAt = 100, updatedAt = 100)

            exec(conn, "UPDATE documents SET persona_id = 'persona-1' WHERE id = 'doc-3';")

            // documents_au_ingest is `AFTER UPDATE OF body_md, title` — an
            // UPDATE whose SET list touches neither column must not fire
            // it at all, so the row the INSERT trigger queued stays
            // exactly as the insert left it (reason='created', not
            // overwritten to 'updated').
            val queued = readIngestQueue(conn, docId = "doc-3")
            assertThat(queued).isNotNull()
            assertThat(queued!!.reason).isEqualTo("created")
        }
    }

    // --- chunks_fts / chunks_vec sync triggers (chunks_ai / chunks_ad) ---

    @Test
    fun insertingChunkMakesTextFindableViaFtsMatch() {
        val dbFile = tempDbFile()
        Migrator(SkeinSQLiteDriver(randomKey(7))).migrate(dbFile.absolutePath)

        SkeinSQLiteDriver(randomKey(7)).open(dbFile.absolutePath).use { conn ->
            insertNote(conn, id = "doc-4", title = "Title", bodyMd = null, createdAt = 100, updatedAt = 100)
            insertChunk(conn, id = 1, docId = "doc-4", ord = 0, text = "hello unique_token_xyz world")

            assertThat(ftsMatchRowIds(conn, "unique_token_xyz")).containsExactly(1L)
        }
    }

    @Test
    fun deletingChunkRemovesItFromFtsAndVec() {
        val dbFile = tempDbFile()
        Migrator(SkeinSQLiteDriver(randomKey(8))).migrate(dbFile.absolutePath)

        SkeinSQLiteDriver(randomKey(8)).open(dbFile.absolutePath).use { conn ->
            insertNote(conn, id = "doc-5", title = "Title", bodyMd = null, createdAt = 100, updatedAt = 100)
            insertChunk(conn, id = 1, docId = "doc-5", ord = 0, text = "hello unique_token_xyz world")
            insertVecRow(conn, rowId = 1, embedding = ByteArray(VEC_DIMENSION) { (it - 128).toByte() })

            // Preconditions: present in both before the delete.
            assertThat(ftsMatchRowIds(conn, "unique_token_xyz")).containsExactly(1L)
            assertThat(vecRowExists(conn, rowId = 1)).isTrue()

            exec(conn, "DELETE FROM chunks WHERE id = 1;")

            assertThat(ftsMatchRowIds(conn, "unique_token_xyz")).isEmpty()
            assertThat(vecRowExists(conn, rowId = 1)).isFalse()
        }
    }

    // --- fixtures / helpers ---

    private data class IngestQueueRow(
        val reason: String?,
        val queuedAt: Long?,
    )

    private fun insertNote(
        conn: SQLiteConnection,
        id: String,
        title: String,
        bodyMd: String?,
        createdAt: Long,
        updatedAt: Long,
    ) {
        conn
            .prepare(
                "INSERT INTO documents(id, kind, title, body_md, created_at, updated_at) VALUES (?, 'note', ?, ?, ?, ?);",
            ).use { stmt ->
                stmt.bindText(1, id)
                stmt.bindText(2, title)
                if (bodyMd == null) stmt.bindNull(3) else stmt.bindText(3, bodyMd)
                stmt.bindLong(4, createdAt)
                stmt.bindLong(5, updatedAt)
                stmt.step()
            }
    }

    private fun insertChunk(
        conn: SQLiteConnection,
        id: Long,
        docId: String,
        ord: Long,
        text: String,
    ) {
        conn.prepare("INSERT INTO chunks(id, doc_id, ord, text) VALUES (?, ?, ?, ?);").use { stmt ->
            stmt.bindLong(1, id)
            stmt.bindText(2, docId)
            stmt.bindLong(3, ord)
            stmt.bindText(4, text)
            stmt.step()
        }
    }

    private fun insertVecRow(
        conn: SQLiteConnection,
        rowId: Long,
        embedding: ByteArray,
    ) {
        conn.prepare("INSERT INTO chunks_vec(rowid, embedding) VALUES (?, ?);").use { stmt ->
            stmt.bindLong(1, rowId)
            stmt.bindBlob(2, embedding)
            stmt.step()
        }
    }

    private fun readIngestQueue(
        conn: SQLiteConnection,
        docId: String,
    ): IngestQueueRow? {
        conn.prepare("SELECT reason, queued_at FROM ingest_queue WHERE doc_id = ?;").use { stmt ->
            stmt.bindText(1, docId)
            if (!stmt.step()) return null
            val reason = if (stmt.isNull(0)) null else stmt.getText(0)
            val queuedAt = if (stmt.isNull(1)) null else stmt.getLong(1)
            return IngestQueueRow(reason, queuedAt)
        }
    }

    private fun ftsMatchRowIds(
        conn: SQLiteConnection,
        query: String,
    ): List<Long> {
        val rowIds = mutableListOf<Long>()
        conn.prepare("SELECT rowid FROM chunks_fts WHERE chunks_fts MATCH ?;").use { stmt ->
            stmt.bindText(1, query)
            while (stmt.step()) rowIds += stmt.getLong(0)
        }
        return rowIds
    }

    private fun vecRowExists(
        conn: SQLiteConnection,
        rowId: Long,
    ): Boolean {
        conn.prepare("SELECT 1 FROM chunks_vec WHERE rowid = ?;").use { stmt ->
            stmt.bindLong(1, rowId)
            return stmt.step()
        }
    }

    private fun exec(
        conn: SQLiteConnection,
        sql: String,
    ) {
        conn.prepare(sql).use { it.step() }
    }

    private companion object {
        const val VEC_DIMENSION = 256
    }
}
