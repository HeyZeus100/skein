// E5.I10 (skein-7v3): the ingest pass end-to-end on a device — a real
// `UnlockManager`, the real `DeviceVaultOpener` (SQLCipher via
// libskein_sqlite.so; `VaultRepositoryImpl`, `IndexStoreImpl` with the FTS5
// triggers), the real `IngestScheduler`/`IngestPipelines` and a real
// WorkManager (`WorkManagerTestInitHelper`, synchronous executor) running the
// real `IngestWorker` through `IngestWorker.Factory`. Only the key provider
// is faked (random 32-byte key held for the test's lifetime).
//
// Compiled by `compileFossDebugAndroidTestKotlin`; the on-device run is
// gated on the emulator lane tracked by bd `skein-k3b2`, like every other
// `*InstrumentedTest` in the repo.

package app.skein.ingest

import android.content.Context
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.skein.MainActivity
import app.skein.core.model.AuthorizationToken
import app.skein.core.model.DocumentKind
import app.skein.core.model.EdgeKind
import app.skein.core.model.NewDocument
import app.skein.core.rag.ingest.IngestPace
import app.skein.core.vault.key.RewrapResult
import app.skein.core.vault.key.SetupResult
import app.skein.core.vault.key.UnlockResult
import app.skein.core.vault.key.VaultKeyProvider
import app.skein.core.vault.lifecycle.VaultPaths
import app.skein.core.vault.provider.VaultDocumentsProvider
import app.skein.core.vault.session.LockReason
import app.skein.core.vault.session.UnlockManager
import app.skein.vault.BringUpResult
import app.skein.vault.DeviceVaultOpener
import app.skein.vault.DocumentsProviderPort
import app.skein.vault.VaultBootstrap
import app.skein.vault.VaultServices
import app.skein.vault.VaultSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.SecureRandom

@RunWith(AndroidJUnit4::class)
class IngestWorkerInstrumentedTest {
    /** Test-only provider: a random in-memory key, unlock always succeeds. */
    private class RandomKeyVaultKeyProvider : VaultKeyProvider {
        @Volatile
        private var master: ByteArray? = null
        private var epoch = 0L

        override suspend fun setup(
            activity: FragmentActivity,
            prompt: BiometricPrompt.PromptInfo,
        ): SetupResult = SetupResult.Success(masterKeyVersion = 1, strongBoxBacked = false)

        override suspend fun setup(
            activity: FragmentActivity,
            prompt: BiometricPrompt.PromptInfo,
            existingMaster: ByteArray,
        ): SetupResult = error("IngestWorkerInstrumentedTest never restores a master key")

        override fun isInitialised(): Boolean = true

        override suspend fun unlock(
            activity: FragmentActivity,
            prompt: BiometricPrompt.PromptInfo,
            factor: VaultKeyProvider.Factor,
        ): UnlockResult {
            master = ByteArray(KEY_LENGTH).also(SecureRandom()::nextBytes)
            return UnlockResult.Success(AuthorizationToken(++epoch))
        }

        override fun currentKey(): ByteArray? = master

        override fun lock() {
            master?.fill(0)
            master = null
        }

        override suspend fun rewrapAfterInvalidation(
            activity: FragmentActivity,
            prompt: BiometricPrompt.PromptInfo,
            survivingFactor: VaultKeyProvider.Factor,
        ): RewrapResult = RewrapResult.Failed("not supported in tests")
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val vaultDir = File(context.cacheDir, "ingest-worker-test-${System.nanoTime()}")
    private val keyProvider = RandomKeyVaultKeyProvider()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val manager = UnlockManager(keyProvider = keyProvider, scope = null, installShutdownHook = false)
    private val opener =
        DeviceVaultOpener(
            keyProvider = keyProvider,
            paths = VaultPaths(vaultDir = vaultDir),
            attachmentsDir = File(vaultDir, VaultServices.ATTACHMENTS_DIR),
        )
    private val bootstrap =
        VaultBootstrap(
            unlockManager = manager,
            openVault = opener::open,
            provider =
                object : DocumentsProviderPort {
                    override fun install(services: VaultDocumentsProvider.Services?) = Unit

                    override fun notifyRootsChanged() = Unit
                },
            scope = scope,
        )
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: IngestScheduler
    private val prompt =
        BiometricPrompt.PromptInfo
            .Builder()
            .setTitle("Unlock")
            .setNegativeButtonText("Cancel")
            .build()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration
                .Builder()
                .setWorkerFactory(IngestWorker.Factory { scheduler })
                .setExecutor(SynchronousExecutor())
                .setTaskExecutor(SynchronousExecutor())
                .build(),
        )
        workManager = WorkManager.getInstance(context)
        scheduler =
            IngestScheduler(
                unlockManager = manager,
                session = bootstrap.session,
                port = WorkManagerIngestWorkPort(workManager),
                pacer =
                    object : IngestPacer {
                        override fun pace(): IngestPace = IngestPace.FULL
                    },
                pipelines = { session, pace -> IngestPipelines.forSession(session, pace) },
                scope = scope,
                debounceMillis = 100L,
            )
        // Not `start()`ed: the tests drive `indexNow()` explicitly so the
        // synchronous executor runs the pass inside the call.
    }

    @After
    fun tearDown() {
        runBlocking { manager.lockAndAwait(LockReason.SESSION_ENDED) }
        scope.cancel()
        vaultDir.deleteRecursively()
    }

    private fun unlockAndBringUp(): VaultSession {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                runBlocking { manager.unlock(activity, prompt, VaultKeyProvider.Factor.BIOMETRIC) }
            }
        }
        val result = runBlocking { bootstrap.bringUp() }
        check(result is BringUpResult.Ready) { "bring-up did not complete: $result" }
        return result.session
    }

    private fun uniqueInfos(): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(WorkManagerIngestWorkPort.UNIQUE_WORK_NAME).get()

    private fun awaitTerminalState(timeoutMillis: Long = 10_000L): List<WorkInfo> {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val infos = uniqueInfos()
            if (infos.all { it.state.isFinished }) {
                return infos
            }
            Thread.sleep(50)
        }
        return uniqueInfos()
    }

    @Test
    fun three_queued_notes_are_chunked_searchable_and_linked_after_one_pass() {
        val session = unlockAndBringUp()
        val ids =
            runBlocking {
                listOf(
                    session.repository.createDocument(
                        note("Alpha", "Alpha talks about glacial-mimeograph. See [[Beta]]."),
                    ),
                    session.repository.createDocument(note("Beta", "Beta is short. #tagged")),
                    session.repository.createDocument(note("Gamma", "Gamma links [[Alpha]] and [[Nowhere]].")),
                ).map { it.id }
            }

        scheduler.indexNow()

        assertEquals(listOf(WorkInfo.State.SUCCEEDED), awaitTerminalState().map { it.state })
        runBlocking {
            assertEquals(emptyList<Any>(), session.repository.dequeueIngest(10))
            val hit = session.indexStore.bm25("glacial-mimeograph", k = 5).single()
            assertEquals(
                ids[0],
                session.indexStore
                    .getChunks(listOf(hit.chunkId))
                    .getValue(hit.chunkId)
                    .docId,
            )
            assertEquals(listOf(ids[0]), session.indexStore.edgesTo(ids[1], EdgeKind.WIKILINK).map { it.srcId })
            assertEquals(listOf(ids[2]), session.indexStore.edgesTo(ids[0], EdgeKind.WIKILINK).map { it.srcId })
            assertTrue(session.indexStore.edgesFrom(ids[1]).any { it.kind == EdgeKind.TAG })
        }
    }

    @Test
    fun lock_cancels_the_unique_work_and_nothing_runs_while_locked() {
        val session = unlockAndBringUp()
        runBlocking { session.repository.createDocument(note("Alpha", "Body.")) }

        runBlocking { manager.lockAndAwait(LockReason.USER_REQUESTED) }
        scheduler.indexNow()

        assertTrue(uniqueInfos().none { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING })
    }

    private fun note(
        title: String,
        body: String,
    ) = NewDocument(kind = DocumentKind.NOTE, title = title, bodyMd = body)

    private companion object {
        const val KEY_LENGTH = 32
    }
}
