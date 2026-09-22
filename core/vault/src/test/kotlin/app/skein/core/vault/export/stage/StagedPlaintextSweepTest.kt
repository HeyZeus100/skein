// skein-0m1z — JVM coverage for the sweep step itself (real files under a
// JUnit `TemporaryFolder`, a fake repository for the rows). The SQL side is
// exercised on-device by `ExportStageRepositoryContractTest`; everything
// about WHEN a file is deleted lives here so it runs on every `check`.
//
// POST_REVIEW_RESOLUTIONS.md §4.4 asks for a `StagedPlaintextSweeperTest`
// that "runs with a FakeClock; deletes when expired; retries when
// premature" — the delete/premature decision is this class; the WorkManager
// `Result.retry()` mapping it feeds is `:app`'s `StagedPlaintextSweeperTest`.

package app.skein.core.vault.export.stage

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StagedPlaintextSweepTest {
    @get:Rule
    val staging: TemporaryFolder = TemporaryFolder()

    private var now: Long = 1_000L

    private val repository = FakeExportStageRepository()

    private fun sweep(): StagedPlaintextSweep =
        StagedPlaintextSweep(
            repository = repository,
            stagingDir = staging.root,
            clock = { now },
        )

    private fun stagedFile(name: String): File = staging.newFile(name).apply { writeText("PLAINTEXT") }

    private fun row(
        stageId: String,
        file: File,
        expiresAt: Long,
        swept: Boolean = false,
    ) = ExportStageRow(
        stageId = stageId,
        path = file.absolutePath,
        origin = "pdf_export",
        documentId = "doc-1",
        revisionHash = null,
        createdAt = 0L,
        expiresAt = expiresAt,
        swept = swept,
    )

    // --- sweepIfExpired ----------------------------------------------------

    @Test
    fun `reports SWEPT once the expiry has passed`() =
        runTest {
            val file = stagedFile("stage-1-note.pdf")
            repository.seed(row("stage-1", file, expiresAt = 900L))

            assertThat(sweep().sweepIfExpired("stage-1")).isEqualTo(SweepOutcome.SWEPT)
        }

    @Test
    fun `marks the row swept once its expiry has passed`() =
        runTest {
            val file = stagedFile("stage-1-note.pdf")
            repository.seed(row("stage-1", file, expiresAt = 900L))

            sweep().sweepIfExpired("stage-1")

            assertThat(repository.rows.getValue("stage-1").swept).isTrue()
        }

    @Test
    fun `the plaintext is gone from disk once its expiry has passed`() =
        runTest {
            val file = stagedFile("stage-1-note.pdf")
            repository.seed(row("stage-1", file, expiresAt = 900L))

            sweep().sweepIfExpired("stage-1")

            assertThat(file.exists()).isFalse()
        }

    @Test
    fun `reports PREMATURE when the expiry has not been reached`() =
        runTest {
            val file = stagedFile("stage-1-note.pdf")
            repository.seed(row("stage-1", file, expiresAt = 5_000L))

            assertThat(sweep().sweepIfExpired("stage-1")).isEqualTo(SweepOutcome.PREMATURE)
        }

    @Test
    fun `leaves the plaintext alone when the expiry has not been reached`() =
        runTest {
            val file = stagedFile("stage-1-note.pdf")
            repository.seed(row("stage-1", file, expiresAt = 5_000L))

            sweep().sweepIfExpired("stage-1")

            assertThat(file.exists()).isTrue()
        }

    @Test
    fun `reports UNKNOWN for a stage id that was never recorded`() =
        runTest {
            assertThat(sweep().sweepIfExpired("never-recorded")).isEqualTo(SweepOutcome.UNKNOWN)
        }

    @Test
    fun `reports UNKNOWN for a row that is already swept`() =
        runTest {
            val file = stagedFile("stage-1-note.pdf")
            repository.seed(row("stage-1", file, expiresAt = 900L, swept = true))

            assertThat(sweep().sweepIfExpired("stage-1")).isEqualTo(SweepOutcome.UNKNOWN)
        }

    @Test
    fun `sweeping a row whose file already vanished still succeeds`() =
        runTest {
            val file = stagedFile("stage-1-note.pdf")
            repository.seed(row("stage-1", file, expiresAt = 900L))
            file.delete()

            assertThat(sweep().sweepIfExpired("stage-1")).isEqualTo(SweepOutcome.SWEPT)
        }

    // --- sweepAll (the on-lock, zero-tolerance sweep) ----------------------

    @Test
    fun `sweepAll deletes plaintext that has not expired yet`() =
        runTest {
            val file = stagedFile("stage-1-note.pdf")
            repository.seed(row("stage-1", file, expiresAt = Long.MAX_VALUE))

            sweep().sweepAll()

            assertThat(file.exists()).isFalse()
        }

    @Test
    fun `sweepAll marks every unswept row swept`() =
        runTest {
            val first = stagedFile("stage-1-note.pdf")
            val second = stagedFile("stage-2-note.pdf")
            repository.seed(row("stage-1", first, expiresAt = Long.MAX_VALUE))
            repository.seed(row("stage-2", second, expiresAt = Long.MAX_VALUE))

            sweep().sweepAll()

            assertThat(repository.rows.values.map { it.swept }).containsExactly(true, true)
        }

    @Test
    fun `sweepAll deletes an orphaned file that has no row at all`() =
        runTest {
            // The cascade case from 005's header: `ON DELETE CASCADE` can
            // take a row away while its plaintext is still on disk, and a
            // crash can leave a file that was never recorded. A row-driven
            // sweep would miss both, so the sweep is directory-driven.
            val orphan = stagedFile("stage-orphan-note.pdf")

            sweep().sweepAll()

            assertThat(orphan.exists()).isFalse()
        }

    @Test
    fun `sweepAll reports how many files it deleted`() =
        runTest {
            stagedFile("stage-1-note.pdf")
            stagedFile("stage-2-note.pdf")

            assertThat(sweep().sweepAll()).isEqualTo(2)
        }

    @Test
    fun `sweepAll on an empty staging directory is a no-op`() =
        runTest {
            assertThat(sweep().sweepAll()).isEqualTo(0)
        }

    // --- purgeDirectory (the boot path — no vault, no rows) ---------------

    @Test
    fun `purgeDirectory deletes staged plaintext without touching the repository`() {
        val file = stagedFile("stage-1-note.pdf")

        sweep().purgeDirectory()

        assertThat(file.exists()).isFalse()
    }

    @Test
    fun `purgeDirectory never reads the repository`() {
        stagedFile("stage-1-note.pdf")

        sweep().purgeDirectory()

        assertThat(repository.reads).isEqualTo(0)
    }

    @Test
    fun `purgeDirectory tolerates a staging directory that does not exist`() {
        val absent = File(staging.root, "never-created")

        val sweep = StagedPlaintextSweep(repository, absent, clock = { now })

        assertThat(sweep.purgeDirectory()).isEqualTo(0)
    }
}

/** Minimal in-memory [ExportStageRepository]; the SQL impl is covered on-device. */
internal class FakeExportStageRepository : ExportStageRepository {
    val rows: MutableMap<String, ExportStageRow> = linkedMapOf()
    var reads: Int = 0

    /** Non-suspending seeding helper, so a test's arrange step needs no coroutine. */
    fun seed(row: ExportStageRow) {
        rows[row.stageId] = row
    }

    override suspend fun insertStage(row: ExportStageRow) {
        rows[row.stageId] = row
    }

    override suspend fun getStage(stageId: String): ExportStageRow? {
        reads++
        return rows[stageId]
    }

    override suspend fun listUnsweptStages(): List<ExportStageRow> {
        reads++
        return rows.values.filter { !it.swept }.sortedBy { it.expiresAt }
    }

    override suspend fun markStageSwept(stageId: String): Boolean {
        val existing = rows[stageId] ?: return false
        if (existing.swept) return false
        rows[stageId] = existing.copy(swept = true)
        return true
    }

    override suspend fun markAllStagesSwept(): Int {
        var changed = 0
        for ((id, row) in rows.entries.toList()) {
            if (!row.swept) {
                rows[id] = row.copy(swept = true)
                changed++
            }
        }
        return changed
    }
}
