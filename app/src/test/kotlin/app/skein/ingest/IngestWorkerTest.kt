package app.skein.ingest

import android.content.Context
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import app.skein.core.rag.ingest.IngestOutcome
import app.skein.core.rag.ingest.IngestPace
import app.skein.core.vault.key.VaultKeyProvider
import app.skein.core.vault.provider.VaultDocumentsProvider
import app.skein.core.vault.session.UnlockManager
import app.skein.export.stage.FakeExportStageRepository
import app.skein.vault.BringUpResult
import app.skein.vault.DocumentsProviderPort
import app.skein.vault.ScriptedVaultKeyProvider
import app.skein.vault.VaultBootstrap
import app.skein.vault.VaultSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import us.aherrera.skein.core.model.DocumentKind
import us.aherrera.skein.core.model.EdgeKind
import us.aherrera.skein.core.model.NewDocument
import us.aherrera.skein.testing.FakeExportService
import us.aherrera.skein.testing.FakeImportService
import us.aherrera.skein.testing.InMemoryIndexStore
import us.aherrera.skein.testing.InMemoryPersonaService
import us.aherrera.skein.testing.InMemoryVaultRepository

/**
 * E5.I10 (skein-7v3): `WorkManagerIngestWorkPort` against a real WorkManager
 * (`WorkManagerTestInitHelper`, synchronous executor, `IngestWorker.Factory`
 * over a scripted runner) — unique name, constraints per the amended plan
 * (no device-idle, no charging, no network), append-or-replace, cancel —
 * plus `IngestWorker`'s outcome → `Result` mapping via
 * `TestListenableWorkerBuilder`, and one end-to-end pass (bd AC 1) where
 * the factory resolves to a real `IngestScheduler` over the in-memory vault.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IngestWorkerTest {
    private class ScriptedRunner(
        @Volatile var outcome: IngestOutcome = IngestOutcome.Drained(0, 0),
    ) : IngestRunner {
        val epochs = mutableListOf<Long>()

        override suspend fun runPending(sessionEpoch: Long): IngestOutcome {
            epochs += sessionEpoch
            return outcome
        }
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val runner = ScriptedRunner()

    /** When set, the worker factory hands workers this runner instead of [runner] (end-to-end test). */
    @Volatile
    private var liveRunner: IngestRunner? = null
    private lateinit var workManager: WorkManager
    private lateinit var port: WorkManagerIngestWorkPort

    @Before
    fun initWorkManager() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration
                .Builder()
                .setWorkerFactory(IngestWorker.Factory { liveRunner ?: runner })
                .setExecutor(SynchronousExecutor())
                .setTaskExecutor(SynchronousExecutor())
                .build(),
        )
        workManager = WorkManager.getInstance(context)
        port = WorkManagerIngestWorkPort(workManager)
    }

    private fun uniqueInfos(): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(WorkManagerIngestWorkPort.UNIQUE_WORK_NAME).get()

    // ---- request shape ----------------------------------------------------------------

    @Test
    fun `the request carries no constraints — no device idle, no charging, no network`() {
        val request = WorkManagerIngestWorkPort.request(sessionEpoch = 1L)

        val constraints = request.workSpec.constraints
        assertFalse(constraints.requiresDeviceIdle())
        assertFalse(constraints.requiresCharging())
        assertFalse(constraints.requiresBatteryNotLow())
        assertFalse(constraints.requiresStorageNotLow())
        assertEquals(NetworkType.NOT_REQUIRED, constraints.requiredNetworkType)
    }

    @Test
    fun `the request is not periodic and not expedited`() {
        val request = WorkManagerIngestWorkPort.request(sessionEpoch = 1L)

        assertFalse(request.workSpec.isPeriodic)
        assertFalse(request.workSpec.expedited)
    }

    @Test
    fun `the request retries with exponential backoff`() {
        val request = WorkManagerIngestWorkPort.request(sessionEpoch = 1L)

        assertEquals(androidx.work.BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
        assertEquals(androidx.work.WorkRequest.DEFAULT_BACKOFF_DELAY_MILLIS, request.workSpec.backoffDelayDuration)
    }

    @Test
    fun `the request carries only the session epoch as input`() {
        val request = WorkManagerIngestWorkPort.request(sessionEpoch = 42L)

        assertEquals(workDataOf(IngestWorker.KEY_SESSION_EPOCH to 42L), request.workSpec.input)
    }

    // ---- enqueue / cancel against a real WorkManager -----------------------------------

    @Test
    fun `enqueue runs one unique pass tagged skein-ingest and hands the worker the epoch`() {
        port.enqueue(sessionEpoch = 7L)

        val infos = uniqueInfos()
        assertEquals(1, infos.size)
        assertTrue(WorkManagerIngestWorkPort.UNIQUE_WORK_NAME in infos.single().tags)
        assertEquals(WorkInfo.State.SUCCEEDED, infos.single().state)
        assertEquals(listOf(7L), runner.epochs)
    }

    @Test
    fun `every enqueued request lives under the unique name`() {
        port.enqueue(sessionEpoch = 1L)
        port.enqueue(sessionEpoch = 2L)

        val byTag = workManager.getWorkInfosByTag(WorkManagerIngestWorkPort.UNIQUE_WORK_NAME).get().map { it.id }
        assertEquals(byTag.toSet(), uniqueInfos().map { it.id }.toSet())
    }

    @Test
    fun `a pass that must retry stays enqueued and a second enqueue appends behind it`() {
        runner.outcome = IngestOutcome.Paused(0, 0)
        port.enqueue(sessionEpoch = 1L)
        assertEquals(listOf(WorkInfo.State.ENQUEUED), uniqueInfos().map { it.state })

        port.enqueue(sessionEpoch = 1L)

        assertEquals(2, uniqueInfos().size)
    }

    @Test
    fun `cancel cancels every pending pass`() {
        runner.outcome = IngestOutcome.Paused(0, 0)
        port.enqueue(sessionEpoch = 1L)
        port.enqueue(sessionEpoch = 1L)

        port.cancel()

        assertEquals(setOf(WorkInfo.State.CANCELLED), uniqueInfos().map { it.state }.toSet())
    }

    // ---- outcome → Result --------------------------------------------------------------

    private fun worker(
        outcome: IngestOutcome,
        epoch: Long? = 3L,
    ): IngestWorker {
        val scripted = ScriptedRunner(outcome)
        val builder =
            TestListenableWorkerBuilder<IngestWorker>(context)
                .setWorkerFactory(IngestWorker.Factory { scripted })
        if (epoch != null) builder.setInputData(workDataOf(IngestWorker.KEY_SESSION_EPOCH to epoch))
        return builder.build()
    }

    @Test
    fun `Drained maps to success`() =
        runBlocking {
            assertEquals(ListenableWorker.Result.success(), worker(IngestOutcome.Drained(3, 3)).doWork())
        }

    @Test
    fun `Paused maps to retry`() =
        runBlocking {
            assertEquals(ListenableWorker.Result.retry(), worker(IngestOutcome.Paused(1, 1)).doWork())
        }

    @Test
    fun `Locked maps to failure, never retry`() =
        runBlocking {
            assertEquals(ListenableWorker.Result.failure(), worker(IngestOutcome.Locked(0, 0)).doWork())
        }

    @Test
    fun `a request without an epoch fails without running the pass`() =
        runBlocking {
            val scripted = ScriptedRunner()
            val worker =
                TestListenableWorkerBuilder<IngestWorker>(context)
                    .setWorkerFactory(IngestWorker.Factory { scripted })
                    .build()

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.failure(), result)
            assertEquals(emptyList<Long>(), scripted.epochs)
        }

    @Test
    fun `the factory defers other worker classes to the default factory`() {
        // Capture a real WorkerParameters through the builder's factory hook.
        var params: WorkerParameters? = null
        TestListenableWorkerBuilder<IngestWorker>(context)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker {
                        params = workerParameters
                        return IngestWorker(appContext, workerParameters, runner)
                    }
                },
            ).build()

        val built = IngestWorker.Factory { runner }.createWorker(context, "app.skein.ingest.SomeOtherWorker", params!!)

        assertEquals(null, built)
    }

    // ---- end to end: real WorkManager, real scheduler, in-memory vault (bd skein-7v3 AC 1) --------

    @Test
    fun `three queued notes drained through a real WorkManager run leave the queue empty and the index populated`() {
        // Arrange — the production wiring (`IngestScheduler` + `IngestPipelines.forSession`)
        // over the JVM fakes; the worker factory resolves to this scheduler.
        val keyProvider = ScriptedVaultKeyProvider()
        val manager = UnlockManager(keyProvider = keyProvider, scope = null, installShutdownHook = false)
        val repository = InMemoryVaultRepository()
        val index = InMemoryIndexStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bootstrap =
            VaultBootstrap(
                unlockManager = manager,
                openVault = {
                    VaultSession(
                        repository = repository,
                        indexStore = index,
                        personaService = InMemoryPersonaService(),
                        exportService = FakeExportService(),
                        importService = FakeImportService(),
                        exportStages = FakeExportStageRepository(),
                    ) {}
                },
                provider =
                    object : DocumentsProviderPort {
                        override fun install(services: VaultDocumentsProvider.Services?) = Unit

                        override fun notifyRootsChanged() = Unit
                    },
                scope = scope,
            )
        val scheduler =
            IngestScheduler(
                unlockManager = manager,
                session = bootstrap.session,
                port = port,
                pacer =
                    object : IngestPacer {
                        override fun pace(): IngestPace = IngestPace.FULL
                    },
                pipelines = { session, pace -> IngestPipelines.forSession(session, pace) },
                scope = scope,
            )
        liveRunner = scheduler
        try {
            val activity = Robolectric.buildActivity(FragmentActivity::class.java).get()
            val prompt =
                BiometricPrompt.PromptInfo
                    .Builder()
                    .setTitle("Unlock")
                    .setNegativeButtonText("Cancel")
                    .build()
            val ids =
                runBlocking {
                    manager.unlock(activity, prompt, VaultKeyProvider.Factor.BIOMETRIC)
                    check(bootstrap.bringUp() is BringUpResult.Ready)
                    listOf(
                        repository.createDocument(note("Alpha", "Alpha mentions zamboni once. See [[Beta]].")),
                        repository.createDocument(note("Beta", "Beta is short. #tagged")),
                        repository.createDocument(note("Gamma", "Gamma links [[Alpha]] and [[Nowhere]].")),
                    ).map { it.id }
                }

            // Act — the synchronous executor runs the worker inside the enqueue.
            scheduler.indexNow()

            // Assert
            assertEquals(listOf(WorkInfo.State.SUCCEEDED), uniqueInfos().map { it.state })
            runBlocking {
                assertEquals(emptyList<Any>(), repository.peekIngestQueue())
                val hit = index.bm25("zamboni", k = 5).single()
                assertEquals(ids[0], index.getChunks(listOf(hit.chunkId)).getValue(hit.chunkId).docId)
                assertEquals(listOf(ids[0]), index.edgesTo(ids[1], EdgeKind.WIKILINK).map { it.srcId })
                assertEquals(listOf(ids[2]), index.edgesTo(ids[0], EdgeKind.WIKILINK).map { it.srcId })
                assertTrue(index.edgesFrom(ids[1]).any { it.kind == EdgeKind.TAG })
            }
        } finally {
            liveRunner = null
            scope.cancel()
        }
    }

    private fun note(
        title: String,
        body: String,
    ) = NewDocument(kind = DocumentKind.NOTE, title = title, bodyMd = body)
}
