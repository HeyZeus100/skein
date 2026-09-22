// skein-0m1z — POST_REVIEW_RESOLUTIONS.md §4.4's `StagedPlaintextSweeperTest`:
// "deletes when expired; retries when premature", plus the request shape §4.3
// pins down (10-minute initial delay, unique `sweep-<stageId>`,
// ExistingWorkPolicy.REPLACE).
//
// The expiry DECISION itself is `:core:vault`'s `StagedPlaintextSweepTest`
// (plain JVM, real files, a fake clock); what this class covers is the
// WorkManager surface around it — the `SweepOutcome` -> `Result` mapping and
// the enqueued request.

package app.skein.export.stage

import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import app.skein.core.vault.export.stage.SweepOutcome
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StagedPlaintextSweeperTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before
    fun initWorkManager() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration
                .Builder()
                .setExecutor(SynchronousExecutor())
                .build(),
        )
    }

    private fun runWorker(
        stageId: String?,
        outcome: SweepOutcome,
    ): ListenableWorker.Result {
        val builder =
            TestListenableWorkerBuilder<StagedPlaintextSweeper>(context)
                .setWorkerFactory(StagedPlaintextSweeper.Factory { StageSweep { outcome } })
        if (stageId != null) {
            builder.setInputData(workDataOf(StagedPlaintextSweeper.KEY_STAGE_ID to stageId))
        }
        return runBlocking { builder.build().doWork() }
    }

    // --- SweepOutcome -> Result mapping -----------------------------------

    @Test
    fun `a swept stage succeeds`() {
        assertThat(runWorker("stage-1", SweepOutcome.SWEPT)).isEqualTo(ListenableWorker.Result.success())
    }

    @Test
    fun `an unknown stage succeeds rather than retrying forever`() {
        // Already swept, cascaded away with its document, or the vault is
        // locked (in which case the lock sweep already deleted the file).
        assertThat(runWorker("stage-1", SweepOutcome.UNKNOWN)).isEqualTo(ListenableWorker.Result.success())
    }

    @Test
    fun `a premature run retries instead of deleting early`() {
        assertThat(runWorker("stage-1", SweepOutcome.PREMATURE)).isEqualTo(ListenableWorker.Result.retry())
    }

    @Test
    fun `a request with no stage id fails rather than sweeping something arbitrary`() {
        assertThat(runWorker(null, SweepOutcome.SWEPT)).isEqualTo(ListenableWorker.Result.failure())
    }

    @Test
    fun `the worker passes the requested stage id through to the sweep`() {
        var seen: String? = null
        val worker =
            TestListenableWorkerBuilder<StagedPlaintextSweeper>(context)
                .setInputData(workDataOf(StagedPlaintextSweeper.KEY_STAGE_ID to "stage-42"))
                .setWorkerFactory(
                    StagedPlaintextSweeper.Factory {
                        StageSweep { id ->
                            seen = id
                            SweepOutcome.SWEPT
                        }
                    },
                ).build()

        runBlocking { worker.doWork() }

        assertThat(seen).isEqualTo("stage-42")
    }

    // --- Request shape (§4.3) ---------------------------------------------

    @Test
    fun `the request carries the stage id as its only input`() {
        val request = StagedPlaintextSweeper.request("stage-1", TimeUnit.MINUTES.toMillis(10))

        assertThat(request.workSpec.input.getString(StagedPlaintextSweeper.KEY_STAGE_ID)).isEqualTo("stage-1")
    }

    @Test
    fun `the request carries the ten-minute initial delay`() {
        val request = StagedPlaintextSweeper.request("stage-1", TimeUnit.MINUTES.toMillis(10))

        assertThat(request.workSpec.initialDelay).isEqualTo(TimeUnit.MINUTES.toMillis(10))
    }

    @Test
    fun `an already-elapsed lifetime clamps to no delay rather than a negative one`() {
        val request = StagedPlaintextSweeper.request("stage-1", -5_000L)

        assertThat(request.workSpec.initialDelay).isEqualTo(0L)
    }

    @Test
    fun `the request is tagged for bulk inspection`() {
        val request = StagedPlaintextSweeper.request("stage-1", 0L)

        assertThat(request.tags).contains(StagedPlaintextSweeper.TAG)
    }

    @Test
    fun `the unique work name is keyed per stage`() {
        assertThat(StagedPlaintextSweeper.uniqueWorkName("stage-1")).isEqualTo("sweep-stage-1")
    }

    // --- enqueue ----------------------------------------------------------

    @Test
    fun `enqueue registers the sweep under its per-stage unique name`() {
        val workManager = WorkManager.getInstance(context)

        StagedPlaintextSweeper.enqueue(workManager, "stage-1", TimeUnit.MINUTES.toMillis(10))

        val infos = workManager.getWorkInfosForUniqueWork(StagedPlaintextSweeper.uniqueWorkName("stage-1")).get()
        assertThat(infos.map { it.state }).containsExactly(WorkInfo.State.ENQUEUED)
    }

    @Test
    fun `re-staging the same id replaces the pending sweep rather than queueing a second`() {
        // ExistingWorkPolicy.REPLACE (§4.3): a re-staged export restarts the
        // clock; two deletions must never queue up behind each other.
        val workManager = WorkManager.getInstance(context)

        StagedPlaintextSweeper.enqueue(workManager, "stage-1", TimeUnit.MINUTES.toMillis(10))
        StagedPlaintextSweeper.enqueue(workManager, "stage-1", TimeUnit.MINUTES.toMillis(10))

        val infos = workManager.getWorkInfosForUniqueWork(StagedPlaintextSweeper.uniqueWorkName("stage-1")).get()
        assertThat(infos.filter { it.state == WorkInfo.State.ENQUEUED }).hasSize(1)
    }

    @Test
    fun `two different stages each get their own sweep`() {
        val workManager = WorkManager.getInstance(context)

        StagedPlaintextSweeper.enqueue(workManager, "stage-1", 0L)
        StagedPlaintextSweeper.enqueue(workManager, "stage-2", 0L)

        assertThat(workManager.getWorkInfosByTag(StagedPlaintextSweeper.TAG).get()).hasSize(2)
    }
}
