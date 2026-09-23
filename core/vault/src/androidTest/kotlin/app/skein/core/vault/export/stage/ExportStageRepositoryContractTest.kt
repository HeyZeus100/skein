// skein-0m1z — POST_REVIEW_RESOLUTIONS.md §4.4's `ExportStageRepositoryTest`
// ("inserts, updates, sweeps by expiry, cascades when a `document_revisions`
// row is deleted (§1 tie-in)"), against the REAL SQL implementation.
//
// Instrumented rather than JVM because `export_stages` only exists once
// migration 005 has actually run against a real SQLite connection — the
// host-JVM `FakeSkeinSQLiteNative` executes no SQL at all (see
// `MigratorTest`'s header). Same `:memory:` + statement-by-statement
// migration setup as `VaultRepositoryImplContractTest`, which this mirrors.
//
// DEVIATION from §4.4's wording, recorded deliberately: the cascade asserted
// below is the DOCUMENT cascade, not a `document_revisions` cascade. §4.3's
// proposed `revision_hash TEXT REFERENCES document_revisions(revision_hash)`
// is not expressible in SQLite — `document_revisions`'s primary key is the
// composite `(document_id, revision_hash)`, so `revision_hash` alone is
// neither a PK nor UNIQUE and the FK raises "foreign key mismatch" at the
// first INSERT. See 005_export_stages.sql's "Deviation from §4.3" header;
// `documents(id) ON DELETE CASCADE` (which 005 does carry) is what actually
// removes a stage row when its document — and with it, that document's
// revisions — goes away.
//
// Follow-up (skein-k3b2): no emulator was available in this worktree, same
// as every other `*ContractTest`/`*InstrumentedTest` in this module. This
// class is compiled by the ordinary Gradle `check` path, so a broken
// statement still surfaces there; running it is gated on that follow-up.

package app.skein.core.vault.export.stage

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.skein.core.model.DocumentKind
import app.skein.core.model.NewDocument
import app.skein.core.vault.blob.InMemoryAttachmentStore
import app.skein.core.vault.db.SkeinSQLiteConnection
import app.skein.core.vault.db.SkeinSQLiteDriver
import app.skein.core.vault.repository.VaultRepositoryImpl
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExportStageRepositoryContractTest {
    private val open: MutableList<VaultRepositoryImpl> = mutableListOf()

    @After
    fun tearDown() {
        for (impl in open) runCatching { impl.close() }
        open.clear()
    }

    private fun repository(): VaultRepositoryImpl {
        val driver = SkeinSQLiteDriver()
        val conn = driver.openWithKey(":memory:", passphrase = null) as SkeinSQLiteConnection
        // 005 needs 001 (`documents`, its FK parent). 003/007/008 touch
        // nothing this class reads, so they are skipped — same selective
        // application `VaultRepositoryImplContractTest` uses.
        for (migration in listOf("001_initial.sql", "005_export_stages.sql")) {
            val sql =
                requireNotNull(javaClass.classLoader?.getResourceAsStream("migrations/$migration")) {
                    "migrations/$migration not on the classpath"
                }.use { it.readBytes().toString(Charsets.UTF_8) }
            for (statement in splitOnSentinel(sql)) conn.prepare(statement).use { it.step() }
        }
        // The cascade below only fires with FK enforcement on, which
        // `VaultLifecycle` sets for every production connection.
        conn.prepare("PRAGMA foreign_keys = ON;").use { it.step() }
        return VaultRepositoryImpl(writer = conn, attachments = InMemoryAttachmentStore()).also { open += it }
    }

    private fun row(
        stageId: String,
        documentId: String? = null,
        expiresAt: Long = 600_000L,
        swept: Boolean = false,
    ) = ExportStageRow(
        stageId = stageId,
        path = "/data/user/0/app.skein/cache/staging_export/$stageId-note.pdf",
        origin = "pdf_export",
        documentId = documentId,
        revisionHash = null,
        createdAt = 0L,
        expiresAt = expiresAt,
        swept = swept,
    )

    // --- insert / get ------------------------------------------------------

    @Test
    fun insertedStageReadsBackWithEveryColumnIntact(): Unit =
        runBlocking {
            val repo = repository()
            val inserted = row("stage-1")

            repo.insertStage(inserted)

            assertThat(repo.getStage("stage-1")).isEqualTo(inserted)
        }

    @Test
    fun anUnrecordedStageIdReadsBackAsNull(): Unit =
        runBlocking {
            assertThat(repository().getStage("never-recorded")).isNull()
        }

    @Test
    fun aFreshlyInsertedStageIsNotSwept(): Unit =
        runBlocking {
            val repo = repository()

            repo.insertStage(row("stage-1"))

            assertThat(repo.getStage("stage-1")!!.swept).isFalse()
        }

    @Test
    fun reinsertingTheSameStageIdReplacesTheRowRatherThanThrowing(): Unit =
        runBlocking {
            val repo = repository()
            repo.insertStage(row("stage-1", expiresAt = 100L))

            repo.insertStage(row("stage-1", expiresAt = 999L))

            assertThat(repo.getStage("stage-1")!!.expiresAt).isEqualTo(999L)
        }

    // --- listUnswept -------------------------------------------------------

    @Test
    fun listUnsweptOmitsRowsAlreadyMarkedSwept(): Unit =
        runBlocking {
            val repo = repository()
            repo.insertStage(row("stage-1"))
            repo.insertStage(row("stage-2", swept = true))

            assertThat(repo.listUnsweptStages().map { it.stageId }).containsExactly("stage-1")
        }

    @Test
    fun listUnsweptReturnsTheMostOverduePlaintextFirst(): Unit =
        runBlocking {
            val repo = repository()
            repo.insertStage(row("later", expiresAt = 900L))
            repo.insertStage(row("sooner", expiresAt = 100L))

            assertThat(repo.listUnsweptStages().map { it.stageId }).containsExactly("sooner", "later").inOrder()
        }

    // --- markSwept ---------------------------------------------------------

    @Test
    fun markingAStageSweptReportsTrueTheFirstTime(): Unit =
        runBlocking {
            val repo = repository()
            repo.insertStage(row("stage-1"))

            assertThat(repo.markStageSwept("stage-1")).isTrue()
        }

    @Test
    fun markingAnAlreadySweptStageReportsFalse(): Unit =
        runBlocking {
            val repo = repository()
            repo.insertStage(row("stage-1"))
            repo.markStageSwept("stage-1")

            assertThat(repo.markStageSwept("stage-1")).isFalse()
        }

    @Test
    fun markingAnUnknownStageReportsFalse(): Unit =
        runBlocking {
            assertThat(repository().markStageSwept("never-recorded")).isFalse()
        }

    @Test
    fun markAllStagesSweptReportsHowManyRowsItChanged(): Unit =
        runBlocking {
            val repo = repository()
            repo.insertStage(row("stage-1"))
            repo.insertStage(row("stage-2"))
            repo.insertStage(row("stage-3", swept = true))

            assertThat(repo.markAllStagesSwept()).isEqualTo(2)
        }

    @Test
    fun markAllStagesSweptLeavesNothingUnswept(): Unit =
        runBlocking {
            val repo = repository()
            repo.insertStage(row("stage-1"))
            repo.insertStage(row("stage-2"))

            repo.markAllStagesSwept()

            assertThat(repo.listUnsweptStages()).isEmpty()
        }

    // --- cascade (§1 tie-in; see the header for the deviation) ------------

    @Test
    fun deletingTheDocumentCascadesItsStageRowAway(): Unit =
        runBlocking {
            val repo = repository()
            val doc = repo.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "Note", bodyMd = "body"))
            repo.insertStage(row("stage-1", documentId = doc.id))

            repo.deleteDocument(doc.id)

            assertThat(repo.getStage("stage-1")).isNull()
        }

    @Test
    fun aStageWithNoDocumentSurvivesUnrelatedDocumentDeletes(): Unit =
        runBlocking {
            val repo = repository()
            val doc = repo.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "Note", bodyMd = "body"))
            repo.insertStage(row("stage-1", documentId = null))

            repo.deleteDocument(doc.id)

            assertThat(repo.getStage("stage-1")).isNotNull()
        }

    /**
     * Splits migration SQL on the `--;` sentinel. `MigrationStatementSplitter`
     * is `internal` to the main source set and invisible from this separate
     * `androidTest` compilation — the same helper is already duplicated in
     * `VaultRepositoryImplContractTest`, `IndexStoreImplContractTest` and
     * `MigratorInstrumentedTest` for that reason.
     */
    private fun splitOnSentinel(sql: String): List<String> =
        sql
            .split("--;")
            .map { chunk ->
                chunk
                    .lineSequence()
                    .map { it.trimEnd() }
                    .filter { line -> line.isNotBlank() && !line.trimStart().startsWith("--") }
                    .joinToString(separator = "\n")
                    .trim()
                    .removeSuffix(";")
                    .trim()
            }.filter { it.isNotEmpty() }
}
