// OBJECT_LIFECYCLE_SPEC.md LC-08 (skein-cash): the emulator-lane lifecycle
// suite. The class KDoc maps every §11.1 EMU and §11.3 id to the test that
// covers it; the tests in this file are the ones that did not exist yet.

package app.skein.core.vault.repository

import android.util.Log
import androidx.sqlite.SQLiteConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.skein.core.model.DocumentKind
import app.skein.core.model.EdgeKind
import app.skein.core.model.NewChunk
import app.skein.core.model.NewDocument
import app.skein.core.vault.blob.FileAttachmentStore
import app.skein.core.vault.db.SkeinSQLiteDriver
import app.skein.core.vault.db.migrations.Migrator
import app.skein.core.vault.extract.DanglingResolver
import app.skein.core.vault.extract.EdgeUpserter
import app.skein.core.vault.index.IndexStoreImpl
import app.skein.core.vault.lifecycle.CreateResult
import app.skein.core.vault.lifecycle.IntegrityResult
import app.skein.core.vault.lifecycle.OpenResult
import app.skein.core.vault.lifecycle.VaultLifecycle
import app.skein.core.vault.lifecycle.VaultPaths
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

/**
 * The §11.1 EMU and §11.3 tests of `docs/ux/OBJECT_LIFECYCLE_SPEC.md` (LC-08), run
 * against a real vault: an encrypted file under the app's cache dir, opened by
 * [VaultLifecycle] with every production migration (010 included) and wired the
 * way `DeviceVaultOpener` wires a session (repository on the pool writer, the
 * index store on a reader-pool connection, [FileAttachmentStore] blobs).
 *
 * Every spec id and the test that covers it:
 *
 * §11.1. "contract" means the same-named test in `VaultRepositoryContractTest`
 * (`:testing`), which `VaultRepositoryImplContractTest` runs on the real driver.
 * - `deleteDocument_removes_the_row_and_getDocument_returns_null`: contract
 * - `deleteDocument_is_idempotent_for_a_missing_id`: contract
 * - `deleteDocument_removes_messages_revisions_and_the_ingest_queue_entry`: contract
 * - `deleteDocument_removes_the_documents_chunks`: contract
 * - `deleteDocument_removes_out_edges_of_every_kind`: contract
 * - `deleteDocument_rewrites_wikilink_in_edges_to_the_title_sentinel_at_weight_half`: contract
 * - `deleteDocument_sentinel_matches_EdgeUpserter_for_a_non_ascii_title`: contract
 * - `deleteDocument_removes_cite_in_edges`: contract
 * - `deleteDocument_requeues_the_surviving_document_with_the_same_title`: contract
 * - `a_note_created_with_a_deleted_notes_title_regains_its_backlinks`: here (its
 *   JVM twin is `DanglingResolverTest`, on the linked fakes)
 * - `observeDocument_emits_null_after_delete`: contract
 * - `observeMessages_emits_empty_after_the_chat_is_deleted`: contract
 * - `observeTimeline_drops_the_deleted_document`: contract
 * - `searchTitles_and_searchBodies_never_return_a_deleted_document`: contract
 * - `writes_to_a_deleted_document_throw_NoSuchElementException_and_write_nothing`: contract
 * - `a_late_autosave_never_resurrects_a_deleted_note`: contract
 * - `deleteDocument_keeps_other_chats_citation_excerpts`: contract
 * - `countChatsCiting_counts_distinct_chats_by_decoded_citation_records`: contract
 * - `a_citation_into_a_deleted_document_does_not_match`: contract
 * - `deleting_a_document_cascades_its_revisions_ahead_of_any_sweep`: contract
 * - `transaction_nests_writes_without_deadlock`: contract
 * - `a_file_delete_removes_the_attachment_and_its_text_notes_together`: not built
 *   (LC-09, file delete, does not exist yet)
 * - EMU only `a_failed_transaction_rolls_back_every_delete_in_it`: contract
 * - EMU only `attachment_blob_is_deleted_only_after_commit`: contract (the blob
 *   outlives the open transaction) plus, here,
 *   [attachment_blob_outlives_a_delete_whose_commit_fails] (the injected failing COMMIT)
 * - EMU only `staged_export_file_of_a_deleted_document_is_purged_after_commit`:
 *   `VaultRepositoryImplContractTest`
 * - EMU only `a_committed_delete_survives_pool_close_and_reopen`: here
 *
 * §11.3.
 * - `cascade_leaves_no_chunks_fts_matches_or_vec_rows`: here
 * - `pragma_secure_delete_is_on_for_every_pool_connection`: here
 * - `migration_010_enables_fts5_secure_delete`: `MigratorInstrumentedTest.migration010EnablesFts5SecureDelete`
 * - `after_migration_010_a_deleted_token_leaves_no_bytes_in_chunks_fts_data`:
 *   `MigratorInstrumentedTest.afterMigration010ADeletedTokenLeavesNoBytesInChunksFtsData`
 *   and `afterMigration010ReChunkingLeavesNoResidue`
 * - `migration_010_optimize_purges_residue_written_before_it`:
 *   `MigratorInstrumentedTest.migration010OptimizePurgesResidueWrittenBeforeIt`
 * - `migration_010_is_idempotent_on_rerun`: `MigratorInstrumentedTest.migration010IsIdempotentOnRerun`
 * - `migration_010_keeps_the_integrity_catalogue_green`:
 *   `VaultLifecycleInstrumentedTest.integrityCheckOnAFreshlyCreatedVaultReportsOk`
 *   (a fresh create applies 010), plus the `integrityCheck()` in
 *   [a_committed_delete_survives_pool_close_and_reopen], taken after a secure
 *   delete has moved `chunks_fts` to FTS5 format 5
 * - `fts_delete_cost_with_secure_delete_stays_within_budget`:
 *   [fts_delete_cost_with_secure_delete_is_recorded]. The E2 owner has not set
 *   the budget, so it logs the cost (logcat tag [TAG]) and asserts only correctness.
 *
 * Deterministic: no sleeps and no timing assertions. Every chunk and edge source
 * is created through the repository first, since index writes skip an id with no
 * `documents` row (LC-06).
 */
@RunWith(AndroidJUnit4::class)
public class ObjectLifecycleEmulatorLaneTest {
    private val lifecycles = mutableListOf<VaultLifecycle>()
    private val dirs = mutableListOf<File>()

    @After
    public fun tearDown() {
        runBlocking { lifecycles.forEach { it.close() } }
        dirs.forEach { it.deleteRecursively() }
    }

    // ------------------------------------------------------------------
    // §11.1
    // ------------------------------------------------------------------

    @Test
    public fun a_committed_delete_survives_pool_close_and_reopen(): Unit =
        runBlocking {
            val dir = newVaultDir()
            val first = openVault(dir)
            val gone = first.repo.createDocument(NewDocument(DocumentKind.NOTE, "Gone", GONE_TEXT))
            val kept = first.repo.createDocument(NewDocument(DocumentKind.NOTE, "Kept", KEPT_TEXT))
            first.index.replaceChunks(gone.id, listOf(NewChunk(0, GONE_TEXT, 4)), "fake", 1)
            val keptChunks = first.index.replaceChunks(kept.id, listOf(NewChunk(0, KEPT_TEXT, 4)), "fake", 1)
            assertTrue("sanity: the token is indexed", ftsResidueBlocks(first.writer, GONE_TOKEN) > 0L)
            first.repo.deleteDocument(gone.id)
            // The lock path: TRUNCATE checkpoint, then every pool connection closes.
            first.lifecycle.close()

            val second = openVault(dir)
            assertEquals("the reopened vault is the same file", "Kept", second.repo.getDocument(kept.id)?.title)
            assertNull(second.repo.getDocument(gone.id))
            assertNull(second.repo.findByTitle("Gone"))
            assertTrue(second.repo.dequeueIngest(10).none { it.docId == gone.id })
            assertTrue(second.index.chunksForDocs(listOf(gone.id), limitPerDoc = 10).isEmpty())
            assertTrue(second.index.bm25(GONE_TOKEN, k = 10).isEmpty())
            assertEquals(keptChunks, second.index.bm25("kept", k = 10).map { it.chunkId })
            assertEquals(0L, ftsResidueBlocks(second.writer, GONE_TOKEN))
            assertEquals(IntegrityResult.Ok, second.lifecycle.integrityCheck())
        }

    @Test
    public fun attachment_blob_outlives_a_delete_whose_commit_fails(): Unit =
        runBlocking {
            val v = openVault(newVaultDir())
            val bytes = byteArrayOf(7, 8, 9)
            val att = v.repo.createAttachment("a.bin", "application/octet-stream") { it.write(bytes) }
            val note = v.repo.createDocument(NewDocument(DocumentKind.NOTE, "n", "b"))

            var blockReturned = false
            val failed =
                runCatching {
                    v.repo.transaction {
                        v.repo.deleteDocument(att.id)
                        // A deferred foreign-key violation lets every statement
                        // succeed and fails the COMMIT itself.
                        exec(v.writer, "PRAGMA defer_foreign_keys = ON;")
                        v.writer.prepare("UPDATE documents SET persona_id = 'no-such-persona' WHERE id = ?;").use {
                            it.bindText(1, note.id)
                            it.step()
                        }
                        blockReturned = true
                    }
                }

            assertTrue("the block ran to its end, so what failed is the COMMIT", blockReturned)
            assertTrue(failed.isFailure)
            assertNotNull(v.repo.getDocument(att.id))
            val blob = v.repo.openAttachment(att.id).use { it.readBytes() }
            assertArrayEquals("the blob is whole after the failed COMMIT", bytes, blob)
            assertNull(v.repo.getDocument(note.id)?.personaId)

            // The writer was rolled back, not left mid-transaction: a delete that commits works and takes the blob.
            v.repo.deleteDocument(att.id)
            assertNull(v.repo.getDocument(att.id))
            assertTrue(runCatching { v.repo.openAttachment(att.id).close() }.isFailure)
        }

    @Test
    public fun a_note_created_with_a_deleted_notes_title_regains_its_backlinks(): Unit =
        runBlocking {
            val v = openVault(newVaultDir())
            val plan = v.repo.createDocument(NewDocument(DocumentKind.NOTE, "Plan", "v1"))
            val linker = v.repo.createDocument(NewDocument(DocumentKind.NOTE, "C", "see [[Plan]]"))
            EdgeUpserter(v.repo, v.index).upsert(linker)
            assertEquals(listOf(plan.id), v.index.edgesFrom(linker.id).map { it.dstId })

            v.repo.deleteDocument(plan.id)
            assertEquals(listOf("title:plan"), v.index.edgesFrom(linker.id).map { it.dstId })
            val reborn = v.repo.createDocument(NewDocument(DocumentKind.NOTE, "Plan", "v2"))
            DanglingResolver(v.repo, v.index).resolveFor(reborn)

            val backlinks = v.index.edgesTo(reborn.id, EdgeKind.WIKILINK)
            assertEquals(listOf(linker.id), backlinks.map { it.srcId })
            assertEquals(EdgeKind.WIKILINK.weight, backlinks.single().weight, 0.0)
            assertTrue(v.index.edgesTo(plan.id).isEmpty())
        }

    // ------------------------------------------------------------------
    // §11.3
    // ------------------------------------------------------------------

    @Test
    public fun cascade_leaves_no_chunks_fts_matches_or_vec_rows(): Unit =
        runBlocking {
            val v = openVault(newVaultDir())
            val gone = v.repo.createDocument(NewDocument(DocumentKind.NOTE, "Gone", GONE_TEXT))
            val kept = v.repo.createDocument(NewDocument(DocumentKind.NOTE, "Kept", KEPT_TEXT))
            val goneChunks =
                v.index.replaceChunks(
                    gone.id,
                    listOf(NewChunk(0, GONE_TEXT, 4), NewChunk(1, "second $SECOND_TOKEN chunk", 3)),
                    "fake",
                    1,
                )
            val keptChunks = v.index.replaceChunks(kept.id, listOf(NewChunk(0, KEPT_TEXT, 4)), "fake", 1)
            v.index.putEmbeddings((goneChunks + keptChunks).map { it to ByteArray(VEC_DIM) { 1 } })
            // Non-vacuous: everything the delete must remove is there first.
            assertEquals(2L, count(v.writer, "SELECT count(*) FROM chunks WHERE doc_id = ?;", gone.id))
            assertEquals(goneChunks.take(1), ftsMatch(v.writer, GONE_TOKEN))
            assertEquals(3L, count(v.writer, "SELECT count(*) FROM chunks_vec;"))
            assertTrue(ftsResidueBlocks(v.writer, GONE_TOKEN) > 0L)
            assertTrue(ftsResidueBlocks(v.writer, SECOND_TOKEN) > 0L)

            v.repo.deleteDocument(gone.id)

            assertEquals(0L, count(v.writer, "SELECT count(*) FROM chunks WHERE doc_id = ?;", gone.id))
            assertTrue(ftsMatch(v.writer, GONE_TOKEN).isEmpty())
            assertTrue(ftsMatch(v.writer, SECOND_TOKEN).isEmpty())
            for (id in goneChunks) {
                val vectors = count(v.writer, "SELECT count(*) FROM chunks_vec WHERE rowid = $id;")
                assertEquals("vector of chunk $id", 0L, vectors)
            }
            assertEquals(0L, ftsResidueBlocks(v.writer, GONE_TOKEN))
            assertEquals(0L, ftsResidueBlocks(v.writer, SECOND_TOKEN))
            // The survivor is untouched, and FTS5's own check agrees with `chunks` (it throws on a mismatch).
            assertEquals(keptChunks, ftsMatch(v.writer, "kept"))
            assertEquals(1L, count(v.writer, "SELECT count(*) FROM chunks_vec;"))
            exec(v.writer, "INSERT INTO chunks_fts(chunks_fts, rank) VALUES ('integrity-check', 1);")
        }

    @Test
    public fun pragma_secure_delete_is_on_for_every_pool_connection(): Unit =
        runBlocking {
            val v = openVault(newVaultDir())
            val pool = v.lifecycle.connectionPool()
            val connections = listOf(pool.writer()) + pool.readers()
            assertEquals(1 + READER_COUNT, connections.size)
            for ((i, conn) in connections.withIndex()) {
                // `SQLITE_SECURE_DELETE` (native/sqlite/CMakeLists.txt): freed pages are zeroed.
                assertEquals("PRAGMA secure_delete, pool connection $i", 1L, count(conn, "PRAGMA secure_delete;"))
                // Migration 010's FTS5 option lives in the file, so every connection sees it.
                assertEquals(
                    "FTS5 secure-delete, pool connection $i",
                    1L,
                    count(conn, "SELECT v FROM chunks_fts_config WHERE k = 'secure-delete';"),
                )
            }
        }

    /**
     * Records what FTS5 `secure-delete` costs a delete: the same corpus and the same
     * deletes, once with the option on (as migration 010 leaves every vault) and once
     * with it switched off. The spec's `…_stays_within_budget` needs the budget the E2
     * owner has not set yet, so this logs the numbers and asserts only correctness.
     */
    @Test
    public fun fts_delete_cost_with_secure_delete_is_recorded(): Unit =
        runBlocking {
            val secureMs = timedDeletes(secureDelete = true)
            val plainMs = timedDeletes(secureDelete = false)
            Log.i(
                TAG,
                "fts delete cost: $COST_DELETES deleteDocument calls ($COST_CHUNKS chunks x $COST_WORDS words each, " +
                    "$COST_DOCS-document vault): secure-delete on ${secureMs}ms, off ${plainMs}ms",
            )
        }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private class Vault(
        val lifecycle: VaultLifecycle,
        val repo: VaultRepositoryImpl,
        val index: IndexStoreImpl,
        val writer: SQLiteConnection,
    )

    /** Creates the vault in [dir], or opens the one already there, and wires it like `DeviceVaultOpener`. */
    private suspend fun openVault(dir: File): Vault {
        val paths = VaultPaths(vaultDir = dir)
        val lifecycle =
            VaultLifecycle(
                driverFactory = { key -> SkeinSQLiteDriver(key) },
                migrator = { driver -> Migrator(driver) },
                paths = paths,
                readerCount = READER_COUNT,
            )
        lifecycles += lifecycle
        val result = if (paths.databaseFile.exists()) lifecycle.open(key()) else lifecycle.create(key())
        check(result is CreateResult.Success || result is OpenResult.Success) { "vault did not open: $result" }
        val pool = lifecycle.connectionPool()
        val readers = pool.readers()
        return Vault(
            lifecycle = lifecycle,
            repo =
                VaultRepositoryImpl(
                    writer = pool.writer(),
                    attachments = FileAttachmentStore(File(dir, "attachments"), masterKey = ::key),
                    readers = readers.subList(0, 2),
                ),
            index = IndexStoreImpl(readers[2]),
            writer = pool.writer(),
        )
    }

    private suspend fun timedDeletes(secureDelete: Boolean): Long {
        val v = openVault(newVaultDir())
        if (!secureDelete) exec(v.writer, "INSERT INTO chunks_fts(chunks_fts, rank) VALUES ('secure-delete', 0);")
        val docs =
            List(COST_DOCS) { i ->
                v.repo.createDocument(NewDocument(DocumentKind.NOTE, "note $i", "b")).also { doc ->
                    val chunks = List(COST_CHUNKS) { ord -> NewChunk(ord, corpusText(i, ord), COST_WORDS) }
                    v.index.replaceChunks(doc.id, chunks, "fake", 1)
                }
            }
        val started = System.nanoTime()
        for (doc in docs.take(COST_DELETES)) v.repo.deleteDocument(doc.id)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        val chunksLeft = count(v.writer, "SELECT count(*) FROM chunks;")
        assertEquals(((COST_DOCS - COST_DELETES) * COST_CHUNKS).toLong(), chunksLeft)
        exec(v.writer, "INSERT INTO chunks_fts(chunks_fts, rank) VALUES ('integrity-check', 1);")
        return elapsedMs
    }

    private fun newVaultDir(): File {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        return Files.createTempDirectory(cache.toPath(), "lc08-").toFile().also { dirs += it }
    }

    /** A fresh copy each call: [VaultLifecycle] and [FileAttachmentStore] zero what they are given. */
    private fun key(): ByteArray = ByteArray(32) { (it + 8).toByte() }

    /** `chunks_fts_data` blocks whose raw bytes hold [token]: spec Appendix A's probe, as in `MigratorInstrumentedTest`. */
    private fun ftsResidueBlocks(
        conn: SQLiteConnection,
        token: String,
    ): Long =
        conn.prepare("SELECT count(*) FROM chunks_fts_data WHERE instr(block, ?) > 0;").use { stmt ->
            stmt.bindBlob(1, token.toByteArray(Charsets.UTF_8))
            check(stmt.step())
            stmt.getLong(0)
        }

    private fun ftsMatch(
        conn: SQLiteConnection,
        query: String,
    ): List<Long> =
        conn.prepare("SELECT rowid FROM chunks_fts WHERE chunks_fts MATCH ? ORDER BY rowid;").use { stmt ->
            stmt.bindText(1, query)
            buildList { while (stmt.step()) add(stmt.getLong(0)) }
        }

    /** The first column of the first row of [sql], as a Long. */
    private fun count(
        conn: SQLiteConnection,
        sql: String,
        vararg args: String,
    ): Long =
        conn.prepare(sql).use { stmt ->
            args.forEachIndexed { i, arg -> stmt.bindText(i + 1, arg) }
            check(stmt.step()) { "no row from: $sql" }
            stmt.getLong(0)
        }

    private fun exec(
        conn: SQLiteConnection,
        sql: String,
    ) {
        conn.prepare(sql).use { it.step() }
    }

    private fun corpusText(
        doc: Int,
        ord: Int,
    ): String {
        val random = Random(doc * 1_000 + ord)
        return List(COST_WORDS) { "w${random.nextInt(2_000)}" }.joinToString(" ")
    }

    private companion object {
        const val TAG = "SkeinLc08"

        /** Repository readers 0..1 and the index store on 2, as in `DeviceVaultOpener`. */
        const val READER_COUNT = 3
        const val VEC_DIM = 256

        // Residue tokens share no prefix with the term sorted before them, so
        // FTS5's prefix compression stores them whole and the probe can see them.
        const val GONE_TOKEN = "zanzibarx"
        const val SECOND_TOKEN = "yellowtailx"
        const val GONE_TEXT = "the walrus sleeps $GONE_TOKEN"
        const val KEPT_TEXT = "kept words stay here"

        const val COST_DOCS = 100
        const val COST_CHUNKS = 4
        const val COST_WORDS = 64
        const val COST_DELETES = 25
    }
}
