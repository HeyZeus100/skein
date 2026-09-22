package app.skein.ingest

import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import app.skein.core.rag.chunk.Chunker
import app.skein.core.rag.ingest.IngestOutcome
import app.skein.core.rag.ingest.IngestPace
import app.skein.core.rag.ingest.IngestPipeline
import app.skein.core.rag.ingest.IngestSteps
import app.skein.core.rag.tokenizers.ApproximateTokenizer
import app.skein.core.vault.extract.DanglingResolver
import app.skein.core.vault.extract.EdgeUpserter
import app.skein.core.vault.key.VaultKeyProvider
import app.skein.core.vault.provider.VaultDocumentsProvider
import app.skein.core.vault.session.LockReason
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import us.aherrera.skein.core.model.Document
import us.aherrera.skein.core.model.DocumentKind
import us.aherrera.skein.core.model.EdgeKind
import us.aherrera.skein.core.model.NewDocument
import us.aherrera.skein.testing.FakeExportService
import us.aherrera.skein.testing.FakeImportService
import us.aherrera.skein.testing.InMemoryIndexStore
import us.aherrera.skein.testing.InMemoryPersonaService
import us.aherrera.skein.testing.InMemoryVaultRepository
import java.util.Collections

/**
 * E5.I10 (skein-7v3): `IngestScheduler` over a real [UnlockManager] (so the
 * HIGH-before-LOW observer ordering is the production one), the real
 * [VaultBootstrap] (whose LOW observer closes the session), an in-memory
 * vault and a recording [IngestWorkPort]. Every step appends to one event
 * list so the lock test asserts ORDER: cancel, then close, then key zero.
 *
 * Robolectric only because `UnlockManager.unlock` takes a `FragmentActivity`
 * (the scripted provider ignores it). Pinned to SDK 34 like the other
 * `:app` Robolectric tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IngestSchedulerTest {
    private class RecordingPort(
        private val events: MutableList<String>,
    ) : IngestWorkPort {
        override fun enqueue(sessionEpoch: Long) {
            events += "enqueue($sessionEpoch)"
        }

        override fun cancel() {
            events += "cancelWork"
        }
    }

    private class ScriptedPacer : IngestPacer {
        @Volatile
        var next: IngestPace = IngestPace.FULL
        val brackets = Collections.synchronizedList(mutableListOf<String>())

        override fun begin() {
            brackets += "begin"
        }

        override fun end() {
            brackets += "end"
        }

        override fun pace(): IngestPace = next
    }

    private inner class Harness {
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val keyProvider = ScriptedVaultKeyProvider(events)
        val manager = UnlockManager(keyProvider = keyProvider, scope = null, installShutdownHook = false)
        val repository = InMemoryVaultRepository()
        val index = InMemoryIndexStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pacer = ScriptedPacer()

        /** Wraps the real pipeline's link step; tests inject a mid-run lock here. */
        var beforeLink: suspend (Document) -> Unit = {}

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
                    ) { events += "close" }
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
                port = RecordingPort(events),
                pacer = pacer,
                // The production composition (`IngestPipelines.forSession`)
                // with the injectable [beforeLink] hook in front of its link step.
                pipelines = { session, pace ->
                    val upserter = EdgeUpserter(session.repository, session.indexStore)
                    val resolver = DanglingResolver(session.repository, session.indexStore)
                    IngestPipeline(
                        repository = session.repository,
                        chunker = Chunker(ApproximateTokenizer),
                        steps = IngestSteps(session.indexStore),
                        links = { document ->
                            beforeLink(document)
                            upserter.upsert(document)
                            resolver.resolveFor(document)
                        },
                        pace = pace,
                        warn = { events += "warn" },
                    )
                },
                scope = scope,
                debounceMillis = 50L,
            ).also { it.start() }

        private val activity: FragmentActivity = Robolectric.buildActivity(FragmentActivity::class.java).get()
        private val prompt =
            BiometricPrompt.PromptInfo
                .Builder()
                .setTitle("Unlock")
                .setNegativeButtonText("Cancel")
                .build()

        suspend fun unlock() = manager.unlock(activity, prompt, VaultKeyProvider.Factor.BIOMETRIC)

        suspend fun lock() = manager.lockAndAwait(LockReason.USER_REQUESTED)

        suspend fun bringUp(): VaultSession = (bootstrap.bringUp() as BringUpResult.Ready).session

        suspend fun note(
            title: String,
            body: String,
        ): Document = repository.createDocument(NewDocument(kind = DocumentKind.NOTE, title = title, bodyMd = body))

        fun epoch(): Long = requireNotNull(manager.authorizationToken.value).epoch

        /** Waits until [count] enqueue events have been recorded (the subscription runs on [scope]). */
        suspend fun awaitEnqueues(count: Int) =
            withTimeout(5_000L) {
                while (events.count { it.startsWith("enqueue(") } < count) delay(10L)
            }
    }

    private var harness: Harness? = null

    private fun harness(): Harness = Harness().also { harness = it }

    @After
    fun tearDown() {
        harness?.scope?.cancel()
    }

    // ---- enqueue triggers ---------------------------------------------------------

    @Test
    fun `construction sweeps stale work from a previous process`() {
        val h = harness()

        assertEquals(listOf("cancelWork"), h.events.toList())
    }

    @Test
    fun `an open session enqueues one pass stamped with the session epoch`() =
        runBlocking {
            val h = harness()
            h.unlock()
            h.bringUp()

            h.awaitEnqueues(1)

            assertEquals(listOf("cancelWork", "enqueue(${h.epoch()})"), h.events.toList())
        }

    @Test
    fun `a document change while unlocked enqueues another pass after the debounce`() =
        runBlocking {
            val h = harness()
            h.unlock()
            h.bringUp()
            h.awaitEnqueues(1)

            h.note("Fresh", "A new note.")

            h.awaitEnqueues(2)
            assertEquals("enqueue(${h.epoch()})", h.events.last())
        }

    @Test
    fun `a document change while locked enqueues nothing`() =
        runBlocking {
            // Invariant I2 (LOCK_POLICY_INDEXING.md §6.1): queue rows accumulate, no work runs.
            val h = harness()
            h.unlock()
            h.bringUp()
            h.awaitEnqueues(1)
            h.lock()
            h.events.clear()

            h.note("Written while locked", "Body.")
            delay(300L)

            assertEquals(emptyList<String>(), h.events.filter { it.startsWith("enqueue(") })
            assertEquals(1, h.repository.peekIngestQueue().size)
        }

    @Test
    fun `indexNow while locked enqueues nothing`() {
        val h = harness()
        h.events.clear()

        h.scheduler.indexNow()

        assertEquals(emptyList<String>(), h.events.toList())
    }

    // ---- lock ordering ---------------------------------------------------------------

    @Test
    fun `lock cancels the work before the vault closes and before the key is zeroed`() =
        runBlocking {
            // Arrange
            val h = harness()
            h.unlock()
            h.bringUp()
            h.awaitEnqueues(1)
            h.events.clear()
            // Act
            h.lock()
            // Assert — HIGH observer (cancel) → LOW observer (close) → keyProvider.lock().
            assertEquals(listOf("cancelWork", "close", "keyLock"), h.events.toList())
        }

    @Test
    fun `lock resets progress to idle`() =
        runBlocking {
            val h = harness()
            h.unlock()
            h.bringUp()
            h.note("N", "Body.")
            h.scheduler.runPending(h.epoch())
            assertEquals(1, h.scheduler.progress.value.processed)

            h.lock()

            assertEquals(IngestProgress(), h.scheduler.progress.value)
        }

    // ---- runPending authorisation -------------------------------------------------

    @Test
    fun `runPending while locked reports Locked and leaves the queue untouched`() =
        runBlocking {
            val h = harness()
            h.note("Queued", "Body.")

            val outcome = h.scheduler.runPending(sessionEpoch = 1L)

            assertEquals(IngestOutcome.Locked(0, 0), outcome)
            assertEquals(1, h.repository.peekIngestQueue().size)
            assertTrue(h.pacer.brackets.isEmpty())
        }

    @Test
    fun `runPending with a stale epoch reports Locked and leaves the queue untouched`() =
        runBlocking {
            val h = harness()
            h.unlock()
            h.bringUp()
            h.note("Queued", "Body.")

            val outcome = h.scheduler.runPending(sessionEpoch = h.epoch() + 1)

            assertEquals(IngestOutcome.Locked(0, 0), outcome)
            assertEquals(1, h.repository.peekIngestQueue().size)
        }

    @Test
    fun `runPending with the session epoch drains the queue into the index`() =
        runBlocking {
            val h = harness()
            h.unlock()
            h.bringUp()
            val note = h.note("Indexed", "A body that mentions zamboni once. See [[Other]].")

            val outcome = h.scheduler.runPending(h.epoch())

            assertTrue("expected Drained, got $outcome", outcome is IngestOutcome.Drained)
            assertEquals(emptyList<Any>(), h.repository.peekIngestQueue())
            val hit = h.index.bm25("zamboni", k = 5).single()
            assertEquals(
                note.id,
                h.index
                    .getChunks(listOf(hit.chunkId))
                    .getValue(hit.chunkId)
                    .docId,
            )
            assertEquals(listOf(EdgeKind.WIKILINK), h.index.edgesFrom(note.id).map { it.kind })
            assertEquals(listOf("begin", "end"), h.pacer.brackets.toList())
        }

    @Test
    fun `runPending reports the pacer's Paused as Paused`() =
        runBlocking {
            val h = harness()
            h.unlock()
            h.bringUp()
            h.note("Queued", "Body.")
            h.pacer.next = IngestPace.PAUSED

            val outcome = h.scheduler.runPending(h.epoch())

            assertEquals(IngestOutcome.Paused(0, 0), outcome)
            assertEquals(1, h.repository.peekIngestQueue().size)
        }

    @Test
    fun `a lock landing mid-batch stops the pass at the next document boundary`() =
        runBlocking {
            // Arrange — three queued notes; the first document's link step locks the vault.
            val h = harness()
            h.unlock()
            h.bringUp()
            repeat(3) { h.note("N$it", "Body $it.") }
            var locked = false
            h.beforeLink = {
                if (!locked) {
                    locked = true
                    h.lock()
                }
            }
            // Act
            val outcome = h.scheduler.runPending(h.epoch())
            // Assert — one document finished, the other two stay queued for the next session.
            assertEquals(IngestOutcome.Locked(1, 1), outcome)
            assertEquals(2, h.repository.peekIngestQueue().size)
        }

    // ---- bounded retries (persisted ingest_queue.attempts, migration 008, skein-zx15) ----

    @Test
    fun `a document whose link step keeps failing stays queued for two passes and is dropped on the third`() =
        runBlocking {
            // Arrange
            val h = harness()
            h.unlock()
            h.bringUp()
            val poisoned = h.note("Poisoned", "Body.")
            h.beforeLink = { document -> if (document.id == poisoned.id) throw IllegalStateException("boom") }
            // Act
            h.scheduler.runPending(h.epoch())
            h.scheduler.runPending(h.epoch())
            val queuedAfterTwo = h.repository.peekIngestQueue().map { it.docId }
            h.scheduler.runPending(h.epoch())
            // Assert
            assertEquals(listOf(poisoned.id), queuedAfterTwo)
            assertEquals(emptyList<Any>(), h.repository.peekIngestQueue())
            assertEquals(3, h.events.count { it == "warn" })
        }

    @Test
    fun `the attempt counter is persisted, not session-scoped — it survives a lock and unlock`() =
        runBlocking {
            // Arrange — two failing passes, then lock/unlock. Migration 008
            // moved the counter from an `IngestScheduler`-local `IngestAttempts`
            // (which used to reset in `onLocked`) into `ingest_queue.attempts`,
            // so it is no longer cleared by a lock/unlock cycle.
            val h = harness()
            h.unlock()
            h.bringUp()
            val poisoned = h.note("Poisoned", "Body.")
            h.beforeLink = { document -> if (document.id == poisoned.id) throw IllegalStateException("boom") }
            h.scheduler.runPending(h.epoch())
            h.scheduler.runPending(h.epoch())
            h.lock()
            h.unlock()
            h.bringUp()
            // Act — this is the third consecutive failure overall (the first
            // two happened before the lock), so it drops the entry.
            h.scheduler.runPending(h.epoch())
            // Assert
            assertEquals(emptyList<Any>(), h.repository.peekIngestQueue())
            assertEquals(3, h.events.count { it == "warn" })
        }
}
