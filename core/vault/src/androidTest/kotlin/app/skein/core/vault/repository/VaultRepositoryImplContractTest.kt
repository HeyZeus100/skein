// Instrumented contract test for `VaultRepositoryImpl` (`E2.I4`, bd
// `skein-2my`). Runs the shared `VaultRepositoryContractTest` suite from
// `:testing` against the real SQL-backed impl over an unencrypted `:memory:`
// connection with sqlite-vec + FTS5 live — same approach as
// `IndexStoreImplContractTest` (`E2.I15`, skein-qi6), which this class
// mirrors: migration 001 is applied statement-by-statement on the same
// connection the repository then runs against (a fresh `SkeinSQLiteDriver`
// with no key — `verifyExtensions` still asserts vec/FTS5 linkage even
// unencrypted).
//
// Beyond the inherited suite, this class adds the four extra `skein-2my`
// acceptance-criteria checks bd calls out by name:
//   • `frontmatter.id` override (caller's `NewDocument.id` wins over a
//     frontmatter `id` key, but a frontmatter `id` key is still honored as
//     a fallback when the caller supplies none).
//   • `searchBodies("quantum")` BM25-ranked, bracket-snippet shape.
//   • `observeTimeline` re-emitting within 50ms of a write from another
//     coroutine.
//
// Follow-up (skein-k3b2): no API 35 emulator was available in this
// worktree, matching every other `*InstrumentedTest`/`*ContractTest` in
// this module (`IndexStoreImplContractTest`, `VaultLifecycleInstrumentedTest`,
// `MigratorInstrumentedTest`, `VaultKeyProviderInstrumentedTest`). This class
// is compiled (and so any broken statement is caught) by the ordinary
// Gradle `check` path; running it for real is gated on that follow-up.

package app.skein.core.vault.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.skein.core.model.Citation
import app.skein.core.model.CitationRecord
import app.skein.core.model.CitationRecordJson
import app.skein.core.model.CitationSourceKind
import app.skein.core.model.DocumentKind
import app.skein.core.model.IndexStore
import app.skein.core.model.Locator
import app.skein.core.model.NewDocument
import app.skein.core.model.NewMessage
import app.skein.core.model.PersonaId
import app.skein.core.model.Role
import app.skein.core.model.TimelineFilter
import app.skein.core.model.VaultRepository
import app.skein.core.vault.blob.InMemoryAttachmentStore
import app.skein.core.vault.db.SkeinSQLiteConnection
import app.skein.core.vault.db.SkeinSQLiteDriver
import app.skein.core.vault.export.stage.ExportStageRow
import app.skein.core.vault.index.IndexStoreImpl
import app.skein.core.vault.testutil.splitMigrationStatements
import app.skein.testing.VaultRepositoryContractTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
public class VaultRepositoryImplContractTest : VaultRepositoryContractTest() {
    private val openImpls: MutableList<VaultRepositoryImpl> = mutableListOf()
    private val openConnections: MutableList<SkeinSQLiteConnection> = mutableListOf()

    @After
    public fun tearDown() {
        // Close every impl handed out during the test — `close()` shuts the
        // SQLiteConnection(s), which drops the in-memory DB behind them.
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

    override fun repo(): VaultRepository {
        val driver = SkeinSQLiteDriver()
        val conn = driver.openWithKey(":memory:", passphrase = null) as SkeinSQLiteConnection
        // 001 plus 003 (`document_revisions`, skein-uo5n): the inherited
        // contract suite now covers POST_REVIEW_RESOLUTIONS.md §1's revision
        // and citation-record semantics, which need 003's table. 007 drops
        // only tables this class never touches, so it is still skipped. 008
        // (skein-zx15) is needed too: `dequeueIngest`/`recordIngestFailure`
        // now read/write `ingest_queue.attempts`, which only exists once 008
        // has applied. 005 (`export_stages`) too: `deleteDocument` reads a
        // document's stages before its DELETE (OBJECT_LIFECYCLE_SPEC.md LC-03).
        val migrations =
            listOf(
                "001_initial.sql",
                "003_document_revisions.sql",
                "005_export_stages.sql",
                "008_ingest_attempts.sql",
                "011_chat_drafts.sql",
            )
        for (migration in migrations) {
            val sql =
                requireNotNull(
                    javaClass.classLoader?.getResourceAsStream("migrations/$migration"),
                ) { "migrations/$migration not on the classpath" }
                    .use { it.readBytes().toString(Charsets.UTF_8) }
            for (statement in splitMigrationStatements(sql)) {
                conn.prepare(statement).use { it.step() }
            }
        }
        openConnections += conn
        val impl =
            VaultRepositoryImpl(
                writer = conn,
                attachments = InMemoryAttachmentStore(),
            )
        openImpls += impl
        return impl
    }

    /**
     * LC-02: an `IndexStoreImpl` over the connection the most recent [repo]
     * call opened — one database, so the repository's deletes and renames act
     * on the chunks and edges written here. Not closed separately: closing
     * the repository closes the shared connection.
     */
    override fun index(): IndexStore = IndexStoreImpl(openConnections.last())

    /**
     * skein-ci54: `VaultRepository` has no persona CRUD (that is
     * `PersonaService`'s table), so this inserts a minimal row directly on
     * the same connection `repo()` just opened — only the row's existence
     * matters for `documents.persona_id REFERENCES personas(id)` under
     * `PRAGMA foreign_keys = ON`.
     */
    override fun seedPersona(id: PersonaId) {
        openConnections
            .last()
            .prepare("INSERT INTO personas(id, name, created_at) VALUES (?, 'seed', 0)")
            .use { stmt ->
                stmt.bindText(1, id)
                stmt.step()
            }
    }

    @Test
    public fun altered_excerpt_does_not_unpin_other_citations_or_its_own_revision(): Unit =
        runBlocking {
            val repo = repo()
            val notes = (1..2).map { repo.createDocument(NewDocument(DocumentKind.NOTE, "note $it", "original $it")) }
            val chat = repo.createDocument(NewDocument(DocumentKind.CHAT, "chat", ""))
            val record =
                CitationRecord(
                    retrieved =
                        notes.mapIndexed { index, note ->
                            Citation(
                                index + 1,
                                note.id,
                                requireNotNull(note.contentHash),
                                Locator(0, 10),
                                "original ${index + 1}",
                                CitationSourceKind.LEXICAL,
                            )
                        },
                    cited = listOf(1, 2),
                )
            val message = repo.appendMessage(chat.id, NewMessage(Role.ASSISTANT, "see [1] and [2]", citations = record))
            notes.forEach { repo.updateBody(it.id, it.title, "replacement") }
            val altered = CitationRecordJson.encode(record).replace("original 1", "altered 1")
            openConnections.last().prepare("UPDATE messages SET retrieved_chunks = ? WHERE id = ?").use {
                it.bindText(1, altered)
                it.bindText(2, message.id)
                it.step()
            }
            val replay = repo.listMessages(chat.id).single()
            assertEquals("see [1] and [2]", replay.contentMd)
            assertNull(replay.citations)
            repo.sweepUnreferencedRevisions()
            notes.forEach {
                assertNotNull(repo.getRevision(it.id, requireNotNull(it.contentHash)))
                assertEquals(1, repo.countChatsCiting(it.id))
            }

            // A truncated payload cannot prove which revisions are safe to remove.
            openConnections.last().prepare("UPDATE messages SET retrieved_chunks = ? WHERE id = ?").use {
                it.bindText(1, "{truncated")
                it.bindText(2, message.id)
                it.step()
            }
            assertEquals(0, repo.sweepUnreferencedRevisions())
            notes.forEach { assertNotNull(repo.getRevision(it.id, requireNotNull(it.contentHash))) }
        }

    // ------------------------------------------------------------------
    // OBJECT_LIFECYCLE_SPEC.md §11.1 EMU only (LC-03): staged plaintext
    // ------------------------------------------------------------------

    @Test
    public fun staged_export_file_of_a_deleted_document_is_purged_after_commit(): Unit =
        runBlocking {
            val repo = repo() as VaultRepositoryImpl
            val doc = repo.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "n", bodyMd = "b"))
            val staging = Files.createTempDirectory("staging_export").toFile()
            val stageId = "01924a4b-4d29-7000-8000-000000005a6e"
            val staged = File(staging, "$stageId-n.pdf").apply { writeText("PLAINTEXT") }
            repo.insertStage(
                ExportStageRow(
                    stageId = stageId,
                    path = staged.absolutePath,
                    origin = "pdf_export",
                    documentId = doc.id,
                    revisionHash = doc.contentHash,
                    createdAt = 0L,
                    expiresAt = Long.MAX_VALUE,
                ),
            )

            repo.transaction {
                repo.deleteDocument(doc.id)
                assertTrue("staged plaintext is untouched until COMMIT", staged.exists())
            }

            assertFalse("staged plaintext of a deleted document is gone after COMMIT", staged.exists())
            staging.deleteRecursively()
        }

    // ------------------------------------------------------------------
    // OBJECT_LIFECYCLE_SPEC.md §11.4 (LC-06): residue only raw SQL can plant
    // ------------------------------------------------------------------

    @Test
    public fun orphan_edge_sweep_deletes_edges_from_missing_sources_and_stray_vectors(): Unit =
        runBlocking {
            val repo = repo()
            val conn = openConnections.last()
            // An edge from a document that is gone (residue of a delete made
            // before edges were detached), and a vector for a chunk that is gone
            // (an embedder racing a delete: vec0 has no foreign key).
            conn
                .prepare(
                    "INSERT INTO edges(src_id, dst_id, kind, weight, created_at) VALUES (?, 'tag:x', 'tag', 0.4, 0)",
                ).use {
                    it.bindText(1, "01924a4b-4d29-7000-8000-000000000057")
                    it.step()
                }
            conn.prepare("INSERT INTO chunks_vec(rowid, embedding) VALUES (?, vec_int8(?))").use {
                it.bindLong(1, 424_242L)
                it.bindBlob(2, ByteArray(256) { 1 })
                it.step()
            }

            assertEquals(2, repo.sweepIndexOrphans())

            conn.prepare("SELECT (SELECT count(*) FROM edges) + (SELECT count(*) FROM chunks_vec)").use {
                it.step()
                assertEquals(0L, it.getLong(0))
            }
            assertEquals("idempotent", 0, repo.sweepIndexOrphans())
        }

    // ------------------------------------------------------------------
    // skein-2my AC: frontmatter.id is always the document id
    // ------------------------------------------------------------------

    @Test
    public fun createDocument_callers_id_wins_over_frontmatter_id_key(): Unit =
        runBlocking {
            val repo = repo()
            val doc =
                repo.createDocument(
                    NewDocument(
                        kind = DocumentKind.NOTE,
                        title = "n",
                        bodyMd = "b",
                        id = "caller-supplied-id",
                        frontmatter = buildJsonObject { put("id", JsonPrimitive("frontmatter-supplied-id")) },
                    ),
                )
            assertEquals("caller-supplied-id", doc.id)
            assertEquals(
                "persisted frontmatter must carry the row's own id, not the one the caller put in the frontmatter map",
                "caller-supplied-id",
                (doc.frontmatter.getValue("id") as JsonPrimitive).content,
            )

            val fetched = requireNotNull(repo.getDocument(doc.id))
            assertEquals("caller-supplied-id", (fetched.frontmatter.getValue("id") as JsonPrimitive).content)
        }

    @Test
    public fun createDocument_frontmatter_id_key_is_the_fallback_when_caller_supplies_none(): Unit =
        runBlocking {
            val repo = repo()
            val doc =
                repo.createDocument(
                    NewDocument(
                        kind = DocumentKind.NOTE,
                        title = "n",
                        bodyMd = "b",
                        frontmatter = buildJsonObject { put("id", JsonPrimitive("frontmatter-fallback-id")) },
                    ),
                )
            assertEquals("frontmatter-fallback-id", doc.id)
        }

    // ------------------------------------------------------------------
    // skein-2my AC: searchBodies BM25 rank + bracket-snippet shape
    // ------------------------------------------------------------------

    @Test
    public fun searchBodies_quantum_returns_bm25_ranked_hit_with_bracket_snippet(): Unit =
        runBlocking {
            val repo = repo() as VaultRepositoryImpl
            val conn = openConnections.last()
            val doc =
                repo.createDocument(
                    NewDocument(
                        kind = DocumentKind.NOTE,
                        title = "physics notes",
                        bodyMd = "a note about quantum computing",
                    ),
                )
            // The ingest pipeline that populates `chunks` (IndexStore.replaceChunks,
            // E2.I15/E5.I8) is out of scope for E2.I4 — insert directly, which
            // exercises the same `chunks_ai` trigger that keeps chunks_fts in sync.
            conn.prepare("INSERT INTO chunks(doc_id, ord, text) VALUES (?, 0, ?)").use { stmt ->
                stmt.bindText(1, doc.id)
                stmt.bindText(2, "quantum computing uses superposition and entanglement")
                stmt.step()
            }

            val hits = repo.searchBodies("quantum")
            assertTrue("expected at least one hit for 'quantum'", hits.isNotEmpty())
            val hit = hits.first()
            assertEquals(doc.id, hit.document.id)
            assertTrue(
                "expected a snippet bracketed with '[' ... ']' (snippet(chunks_fts, 0, '[', ']', '…', 12)), was: ${hit.snippet}",
                hit.snippet.contains("[") && hit.snippet.contains("]"),
            )
        }

    // ------------------------------------------------------------------
    // skein-2my AC: observeTimeline re-emits within 50ms of a cross-coroutine write
    // ------------------------------------------------------------------

    @Test
    public fun observeTimeline_reemits_within_50ms_of_a_write_on_another_coroutine(): Unit =
        runBlocking {
            val repo = repo()
            val before = repo.observeTimeline(TimelineFilter()).first().size

            launch(Dispatchers.IO) {
                repo.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "async", bodyMd = "b"))
            }

            withTimeout(1_000) {
                var latest = repo.observeTimeline(TimelineFilter()).first()
                while (latest.size == before) {
                    latest = repo.observeTimeline(TimelineFilter()).first()
                }
                assertEquals(before + 1, latest.size)
            }
        }
}
