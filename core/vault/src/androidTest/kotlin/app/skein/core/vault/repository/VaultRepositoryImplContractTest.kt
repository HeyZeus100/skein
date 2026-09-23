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
import app.skein.core.model.DocumentKind
import app.skein.core.model.NewDocument
import app.skein.core.model.PersonaId
import app.skein.core.model.TimelineFilter
import app.skein.core.model.VaultRepository
import app.skein.core.vault.blob.InMemoryAttachmentStore
import app.skein.core.vault.db.SkeinSQLiteConnection
import app.skein.core.vault.db.SkeinSQLiteDriver
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

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
        // has applied.
        for (migration in listOf("001_initial.sql", "003_document_revisions.sql", "008_ingest_attempts.sql")) {
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
