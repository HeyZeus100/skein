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
    fun freshDatabaseMigratesToVersion8WithAllSchemaObjects() {
        val dbFile = tempDbFile()

        val result = Migrator(SkeinSQLiteDriver(randomKey(1))).migrate(dbFile.absolutePath)

        assertThat(result.fromVersion).isEqualTo(0)
        assertThat(result.toVersion).isEqualTo(8)

        SkeinSQLiteDriver(randomKey(1)).open(dbFile.absolutePath).use { conn ->
            val inspector = SchemaInspector(conn)
            assertThat(inspector.userVersion()).isEqualTo(8)
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
                // 003_document_revisions.sql (skein-uo5n): content-addressed
                // revision history, per POST_REVIEW_RESOLUTIONS.md §1.3.
                "document_revisions",
                // 005_export_stages.sql (skein-0m1z): the staged-plaintext
                // ledger, per POST_REVIEW_RESOLUTIONS.md §4.3. 005 is a
                // RESERVED number lower than the already-landed 007/008;
                // `Migrator` sorts numerically, so a fresh database applies
                // it between 003 and 007 and still ends at user_version 8.
                "export_stages",
            )
            // 007_drop_attachment_master_key.sql (skein-7d0l): the vestigial
            // Layer-1 wrapped-master table (superseded by the app-private
            // key-envelope file, skein-txrh) and the never-populated Layer-2
            // per-attachment key table (FileAttachmentStore derives per-write
            // keys via HKDF and persists nothing) are both gone after a
            // fresh migrate.
            assertThat(inspector.tables()).containsNoneOf("attachment_master_key", "attachment_keys")
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
                "idx_document_revisions_doc",
                "idx_chunks_revision",
                // 005 (skein-0m1z): the sweeper's "what has expired" index.
                "idx_export_stages_expires",
            )
            // Dropping attachment_keys drops its index with it (no explicit
            // DROP INDEX needed — verified empirically against sqlite3
            // 3.51.0 for 007's header comment).
            assertThat(inspector.indexes()).doesNotContain("idx_attachment_keys_version")

            // 003's ALTER TABLE added `chunks.revision_hash` (the durable
            // reverse pointer retrieval needs to emit a
            // `(revision_hash, locator)` tuple). It carries no FK clause —
            // see that migration's "Deviations from §1.3" header note.
            assertThat(columnsOf(conn, "chunks")).contains("revision_hash")
            // 008 (skein-zx15): the UTF-8 byte-offset columns 003 deferred,
            // plus the persisted ingest_queue retry counter.
            assertThat(columnsOf(conn, "chunks")).containsAtLeast("byte_start", "byte_end")
            assertThat(columnsOf(conn, "ingest_queue")).contains("attempts")
        }
    }

    // --- 008: ingest_queue.attempts defaults to 0 and survives INSERT OR REPLACE resetting it ---

    @Test
    fun ingestQueueAttemptsDefaultsToZeroAndResetsOnReQueue() {
        val dbFile = tempDbFile()
        Migrator(SkeinSQLiteDriver(randomKey(11))).migrate(dbFile.absolutePath)

        SkeinSQLiteDriver(randomKey(11)).open(dbFile.absolutePath).use { conn ->
            insertNote(conn, id = "doc-attempts", title = "Title", bodyMd = "body", createdAt = 100, updatedAt = 100)
            assertThat(readAttempts(conn, "doc-attempts")).isEqualTo(0L)

            exec(conn, "UPDATE ingest_queue SET attempts = 2 WHERE doc_id = 'doc-attempts';")
            assertThat(readAttempts(conn, "doc-attempts")).isEqualTo(2L)

            // A body edit re-queues via documents_au_ingest's INSERT OR
            // REPLACE, which resets attempts to its column default — a
            // fresh edit gets a fresh retry budget (migration header).
            exec(conn, "UPDATE documents SET body_md = 'changed', updated_at = 200 WHERE id = 'doc-attempts';")
            assertThat(readAttempts(conn, "doc-attempts")).isEqualTo(0L)
        }
    }

    // --- 008: chunks.byte_start/byte_end round-trip ---

    @Test
    fun chunkByteOffsetColumnsRoundTrip() {
        val dbFile = tempDbFile()
        Migrator(SkeinSQLiteDriver(randomKey(12))).migrate(dbFile.absolutePath)

        SkeinSQLiteDriver(randomKey(12)).open(dbFile.absolutePath).use { conn ->
            insertNote(conn, id = "doc-offsets", title = "Title", bodyMd = null, createdAt = 100, updatedAt = 100)
            conn
                .prepare(
                    "INSERT INTO chunks(id, doc_id, ord, text, byte_start, byte_end) VALUES (?, ?, ?, ?, ?, ?);",
                ).use { stmt ->
                    stmt.bindLong(1, 1)
                    stmt.bindText(2, "doc-offsets")
                    stmt.bindLong(3, 0)
                    stmt.bindText(4, "café")
                    stmt.bindLong(5, 0)
                    stmt.bindLong(6, 5) // "café" is 5 UTF-8 bytes (é is 2 bytes), 4 UTF-16 chars.
                    stmt.step()
                }

            conn.prepare("SELECT byte_start, byte_end FROM chunks WHERE id = 1;").use { stmt ->
                assertThat(stmt.step()).isTrue()
                assertThat(stmt.getLong(0)).isEqualTo(0L)
                assertThat(stmt.getLong(1)).isEqualTo(5L)
            }
        }
    }

    private fun readAttempts(
        conn: SQLiteConnection,
        docId: String,
    ): Long {
        conn.prepare("SELECT attempts FROM ingest_queue WHERE doc_id = ?;").use { stmt ->
            stmt.bindText(1, docId)
            check(stmt.step()) { "no ingest_queue row for doc_id=$docId" }
            return stmt.getLong(0)
        }
    }

    // --- 003: document_revisions round-trip and cascade ---

    @Test
    fun documentRevisionsUpsertsByContentAddressAndCascadesWithItsDocument() {
        val dbFile = tempDbFile()
        Migrator(SkeinSQLiteDriver(randomKey(10))).migrate(dbFile.absolutePath)

        SkeinSQLiteDriver(randomKey(10)).open(dbFile.absolutePath).use { conn ->
            insertNote(conn, id = "doc-rev", title = "Title", bodyMd = "body", createdAt = 100, updatedAt = 100)
            insertRevision(conn, docId = "doc-rev", hash = "a".repeat(64), ord = 0)
            insertRevision(conn, docId = "doc-rev", hash = "b".repeat(64), ord = 1)
            assertThat(revisionCount(conn, "doc-rev")).isEqualTo(2)

            // The primary key is (document_id, revision_hash), so re-capturing
            // content the document already held moves that row rather than
            // appending a duplicate (§1.4 idempotency).
            insertRevision(conn, docId = "doc-rev", hash = "a".repeat(64), ord = 2)
            assertThat(revisionCount(conn, "doc-rev")).isEqualTo(2)

            exec(conn, "DELETE FROM documents WHERE id = 'doc-rev';")
            assertThat(revisionCount(conn, "doc-rev")).isEqualTo(0)
        }
    }

    // --- 005: export_stages defaults and document cascade ---

    @Test
    fun exportStageDefaultsToUnsweptAndCascadesWithItsDocument() {
        val dbFile = tempDbFile()
        Migrator(SkeinSQLiteDriver(randomKey(13))).migrate(dbFile.absolutePath)

        SkeinSQLiteDriver(randomKey(13)).open(dbFile.absolutePath).use { conn ->
            // FK enforcement is off by default in SQLite; the cascade below
            // only fires with it on, and `VaultLifecycle` turns it on for
            // every production connection.
            exec(conn, "PRAGMA foreign_keys = ON;")
            insertNote(conn, id = "doc-stage", title = "Title", bodyMd = "body", createdAt = 100, updatedAt = 100)
            exec(
                conn,
                "INSERT INTO export_stages(stage_id, path, origin, document_id, created_at, expires_at) " +
                    "VALUES ('stage-1', '/cache/staging_export/stage-1-x.pdf', 'pdf_export', 'doc-stage', 100, 700);",
            )

            // `swept` defaults to 0 -- a freshly recorded stage is always
            // pending, never accidentally born already-swept.
            assertThat(sweptFlag(conn, "stage-1")).isEqualTo(0L)

            // Deleting the document takes its stage row with it. The FILE is
            // still removed, because StagedPlaintextSweep.sweepAll sweeps the
            // staging DIRECTORY and not only the rows -- see 005's header.
            exec(conn, "DELETE FROM documents WHERE id = 'doc-stage';")
            assertThat(stageCount(conn, "stage-1")).isEqualTo(0L)
        }
    }

    private fun sweptFlag(
        conn: SQLiteConnection,
        stageId: String,
    ): Long {
        conn.prepare("SELECT swept FROM export_stages WHERE stage_id = ?;").use { stmt ->
            stmt.bindText(1, stageId)
            check(stmt.step()) { "no export_stages row for stage_id=$stageId" }
            return stmt.getLong(0)
        }
    }

    private fun stageCount(
        conn: SQLiteConnection,
        stageId: String,
    ): Long {
        conn.prepare("SELECT COUNT(*) FROM export_stages WHERE stage_id = ?;").use { stmt ->
            stmt.bindText(1, stageId)
            check(stmt.step()) { "COUNT(*) returned no row" }
            return stmt.getLong(0)
        }
    }

    // --- Pre-007 rows survive the drop (seed on v1, then migrate to 007) ---

    @Test
    fun rowsSeededBeforeMigration007SurviveTheDropAndDocumentsIsUnaffected() {
        val dbFile = tempDbFile()

        // Seed at v1 only, by applying 001_initial.sql's own statements
        // directly (same production resource the real Migrator loads) and
        // stopping before 007 -- mirrors a real device that installed
        // before 007 shipped. No separate "v1-only" manifest fixture is
        // needed: this reuses the one production migration file instead of
        // duplicating it under a test resource directory. `MigrationStatementSplitter`
        // itself is `internal` to the main source set and not visible from
        // `androidTest` (a separate compilation), so this splits on the
        // same `--;` sentinel locally -- mirroring the identical workaround
        // already used by `VaultRepositoryImplContractTest.splitOnSentinel`
        // / `IndexStoreImplContractTest` / `PersonaServiceImplContractTest`
        // in this module.
        SkeinSQLiteDriver(randomKey(9)).open(dbFile.absolutePath).use { conn ->
            val sql =
                requireNotNull(Migrator::class.java.classLoader?.getResourceAsStream("migrations/001_initial.sql")) {
                    "migrations/001_initial.sql not on the classpath"
                }.use { it.readBytes().toString(Charsets.UTF_8) }
            exec(conn, "BEGIN IMMEDIATE;")
            for (statement in splitOnSentinel(sql)) exec(conn, statement)
            exec(conn, "PRAGMA user_version = 1;")
            exec(conn, "COMMIT;")

            insertNote(conn, id = "doc-preserved", title = "Title", bodyMd = "body", createdAt = 100, updatedAt = 100)
            exec(
                conn,
                "INSERT INTO attachment_master_key(key_version, created_at) VALUES (1, 100);",
            )
            exec(
                conn,
                "INSERT INTO attachment_keys(attachment_uuid, wrapped_content_key, wrap_iv, wrap_tag, " +
                    "master_key_version, created_at) VALUES ('doc-preserved', x'01', x'02', x'03', 1, 100);",
            )
        }

        // Now point a full-manifest Migrator (001 + 007) at the same file.
        val upgraded = Migrator(SkeinSQLiteDriver(randomKey(9))).migrate(dbFile.absolutePath)
        assertThat(upgraded.fromVersion).isEqualTo(1)
        assertThat(upgraded.toVersion).isEqualTo(8)

        SkeinSQLiteDriver(randomKey(9)).open(dbFile.absolutePath).use { conn ->
            val inspector = SchemaInspector(conn)
            assertThat(inspector.userVersion()).isEqualTo(8)
            assertThat(inspector.tables()).containsNoneOf("attachment_master_key", "attachment_keys")

            // The pre-existing, unrelated document row survived the
            // migration untouched.
            val queued = readIngestQueue(conn, docId = "doc-preserved")
            assertThat(queued).isNotNull()
            assertThat(queued!!.reason).isEqualTo("created")
        }
    }

    // --- Idempotency ---

    @Test
    fun runningMigrateTwiceIsANoop() {
        val dbFile = tempDbFile()
        val first = Migrator(SkeinSQLiteDriver(randomKey(2))).migrate(dbFile.absolutePath)
        assertThat(first.toVersion).isEqualTo(8)

        val second = Migrator(SkeinSQLiteDriver(randomKey(2))).migrate(dbFile.absolutePath)

        assertThat(second.fromVersion).isEqualTo(8)
        assertThat(second.toVersion).isEqualTo(8)

        SkeinSQLiteDriver(randomKey(2)).open(dbFile.absolutePath).use { conn ->
            // The second, no-op migrate() must not have re-inserted (or
            // duplicated) any ledger row -- schema_migrations.version is the
            // primary key, so a re-insert would have thrown rather than
            // silently duplicating, but assert the exact set too.
            assertThat(ledgerVersions(conn)).containsExactly(1L, 3L, 5L, 7L, 8L)
        }
    }

    // --- schema_migrations ledger (skein-p8rn) ---

    @Test
    fun freshDatabaseLedgerListsEveryAppliedVersion() {
        val dbFile = tempDbFile()
        Migrator(SkeinSQLiteDriver(randomKey(14))).migrate(dbFile.absolutePath)

        SkeinSQLiteDriver(randomKey(14)).open(dbFile.absolutePath).use { conn ->
            assertThat(ledgerVersions(conn)).containsExactly(1L, 3L, 5L, 7L, 8L)
        }
    }

    /**
     * The exact hazard skein-p8rn fixes: a database that reached
     * `user_version` 8 via 001 -> 003 -> 007 -> 008 -- built here by
     * applying those four migrations' own statements directly, mirroring a
     * real device that installed before `005_export_stages.sql` (skein-0m1z)
     * landed -- has no `export_stages` table and no `schema_migrations`
     * ledger. Under the OLD `version > user_version` rule this database
     * would never apply 005 (5 is not greater than 8): `export_stages`
     * would silently never exist, and `StagedPlaintextSweeper` would have
     * nothing to sweep without ever knowing it was missing a table.
     *
     * Against the ledger-aware `Migrator`, the first `migrate()` call must
     * seed 001/003/007/008 as already applied (their effects are all
     * observable on this database) while correctly leaving 005 out of the
     * seed (its witness table, `export_stages`, is absent) -- and then
     * apply 005 for real, in this same call, without lowering
     * `user_version` back down to 5.
     */
    @Test
    fun ledgerSeedsPre005DatabaseAndStillAppliesTheGapFillerWithoutLoweringUserVersion() {
        val dbFile = tempDbFile()

        SkeinSQLiteDriver(randomKey(15)).open(dbFile.absolutePath).use { conn ->
            applyRawMigrationSkippingLedger(conn, "001_initial.sql", version = 1)
            applyRawMigrationSkippingLedger(conn, "003_document_revisions.sql", version = 3)
            applyRawMigrationSkippingLedger(conn, "007_drop_attachment_master_key.sql", version = 7)
            applyRawMigrationSkippingLedger(conn, "008_ingest_attempts.sql", version = 8)

            val inspector = SchemaInspector(conn)
            assertThat(inspector.userVersion()).isEqualTo(8)
            assertThat(inspector.tables()).doesNotContain("export_stages")
            assertThat(ledgerTableExistsRaw(conn)).isFalse()
        }

        val result = Migrator(SkeinSQLiteDriver(randomKey(15))).migrate(dbFile.absolutePath)

        assertThat(result.fromVersion).isEqualTo(8)
        // The gap-filler applied, but user_version must stay at the max
        // ever applied (8), never fall back to 005's own version number.
        assertThat(result.toVersion).isEqualTo(8)

        SkeinSQLiteDriver(randomKey(15)).open(dbFile.absolutePath).use { conn ->
            val inspector = SchemaInspector(conn)
            assertThat(inspector.userVersion()).isEqualTo(8)
            assertThat(inspector.tables()).contains("export_stages")
            assertThat(inspector.indexes()).contains("idx_export_stages_expires")
            assertThat(ledgerVersions(conn)).containsExactly(1L, 3L, 5L, 7L, 8L)

            // The table is not just present but usable: a stage row can be
            // inserted and defaults exactly as 005's header specifies.
            insertNote(conn, id = "doc-hazard", title = "Title", bodyMd = "body", createdAt = 100, updatedAt = 100)
            exec(
                conn,
                "INSERT INTO export_stages(stage_id, path, origin, document_id, created_at, expires_at) " +
                    "VALUES ('stage-hazard', '/cache/staging_export/x.pdf', 'pdf_export', 'doc-hazard', 100, 700);",
            )
            assertThat(sweptFlag(conn, "stage-hazard")).isEqualTo(0L)
        }
    }

    /** Raw `SELECT version FROM schema_migrations` — mirrors `Migrator`'s own read, for test assertions. */
    private fun ledgerVersions(conn: SQLiteConnection): List<Long> {
        val versions = mutableListOf<Long>()
        conn.prepare("SELECT version FROM schema_migrations;").use { stmt ->
            while (stmt.step()) versions += stmt.getLong(0)
        }
        return versions
    }

    private fun ledgerTableExistsRaw(conn: SQLiteConnection): Boolean =
        conn.prepare("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'schema_migrations';").use { stmt ->
            stmt.step()
        }

    /**
     * Applies one production migration file's own statements directly
     * (bypassing `Migrator` and its ledger entirely) and sets
     * `PRAGMA user_version` to [version] by hand -- builds a database that
     * mimics one migrated entirely by pre-skein-p8rn code, the same
     * technique [rowsSeededBeforeMigration007SurviveTheDropAndDocumentsIsUnaffected]
     * uses for a single migration, generalized to a specific subset run in
     * order.
     */
    private fun applyRawMigrationSkippingLedger(
        conn: SQLiteConnection,
        fileName: String,
        version: Int,
    ) {
        val sql =
            requireNotNull(Migrator::class.java.classLoader?.getResourceAsStream("migrations/$fileName")) {
                "migrations/$fileName not on the classpath"
            }.use { it.readBytes().toString(Charsets.UTF_8) }
        exec(conn, "BEGIN IMMEDIATE;")
        for (statement in splitOnSentinel(sql)) exec(conn, statement)
        exec(conn, "PRAGMA user_version = $version;")
        exec(conn, "COMMIT;")
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

    private fun insertRevision(
        conn: SQLiteConnection,
        docId: String,
        hash: String,
        ord: Long,
    ) {
        conn
            .prepare(
                "INSERT INTO document_revisions(document_id, revision_hash, revision_ord, body_md_snapshot, " +
                    "frontmatter_snapshot, captured_at, reason) VALUES (?, ?, ?, 'body', '{}', 100, 'ingest') " +
                    "ON CONFLICT(document_id, revision_hash) DO UPDATE SET revision_ord = excluded.revision_ord;",
            ).use { stmt ->
                stmt.bindText(1, docId)
                stmt.bindText(2, hash)
                stmt.bindLong(3, ord)
                stmt.step()
            }
    }

    private fun revisionCount(
        conn: SQLiteConnection,
        docId: String,
    ): Long {
        conn.prepare("SELECT COUNT(*) FROM document_revisions WHERE document_id = ?;").use { stmt ->
            stmt.bindText(1, docId)
            check(stmt.step()) { "COUNT(*) returned no row" }
            return stmt.getLong(0)
        }
    }

    private fun columnsOf(
        conn: SQLiteConnection,
        table: String,
    ): List<String> {
        val names = mutableListOf<String>()
        conn.prepare("PRAGMA table_info($table);").use { stmt ->
            while (stmt.step()) names += stmt.getText(1)
        }
        return names
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

    /**
     * Split migration SQL on the `--;` sentinel (`001_initial.sql`'s file
     * header) -- mirrors production `MigrationStatementSplitter`, which is
     * `internal` and not visible from this separate `androidTest`
     * compilation. Same helper already duplicated in
     * `VaultRepositoryImplContractTest`, `IndexStoreImplContractTest`, and
     * `PersonaServiceImplContractTest` in this module.
     */
    private fun splitOnSentinel(sql: String): List<String> {
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

    private companion object {
        const val VEC_DIMENSION = 256
    }
}
