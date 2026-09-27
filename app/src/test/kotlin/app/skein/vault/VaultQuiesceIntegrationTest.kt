package app.skein.vault

import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import app.skein.core.model.DocumentKind
import app.skein.core.model.NewDocument
import app.skein.core.model.VaultQuiescedException
import app.skein.core.vault.key.VaultKeyProvider
import app.skein.core.vault.provider.VaultDocumentsProvider
import app.skein.core.vault.session.LockReason
import app.skein.core.vault.session.UnlockManager
import app.skein.core.vault.session.UnlockState
import app.skein.export.stage.FakeExportStageRepository
import app.skein.testing.FakeExportService
import app.skein.testing.FakeImportService
import app.skein.testing.InMemoryIndexStore
import app.skein.testing.InMemoryPersonaService
import app.skein.testing.InMemoryVaultRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VaultQuiesceIntegrationTest {
    @Test
    fun `lock drains writes before closing and zeroing then new session accepts writes`() =
        runTest {
            val h = Harness(this)
            h.open()
            val old = h.repository
            val finish = CompletableDeferred<Unit>()
            val writing =
                async {
                    old.transaction {
                        old.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "pending", bodyMd = "body"))
                        finish.await()
                    }
                    h.events += "commit"
                }
            runCurrent()
            val lock = async { h.manager.lockAndAwait(LockReason.USER_REQUESTED) }
            runCurrent()
            assertFalse(lock.isCompleted)
            assertNotNull(h.key.currentKey())
            assertTrue(runCatching { old.transaction { } }.exceptionOrNull() is VaultQuiescedException)
            finish.complete(Unit)
            writing.await()
            lock.await()
            assertEquals(listOf("commit", "close", "keyLock"), h.events)
            assertNull(h.key.currentKey())
            assertNull(h.bootstrap.session.value)
            h.open()
            h.repository.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "after reopen", bodyMd = null))
            assertTrue(runCatching { old.transaction { } }.exceptionOrNull() is VaultQuiescedException)
        }

    @Test
    fun `repository timeout still force closes and zeroes the key`() =
        runTest {
            // Let the repository's own 500ms cap expire before the manager's
            // deadline, proving it does not leak a foreign timeout exception.
            val h = Harness(this, budgetMillis = 1_000)
            h.open()
            val writing = async { h.repository.transaction { awaitCancellation() } }
            runCurrent()
            h.manager.lockAndAwait(LockReason.USER_REQUESTED)
            writing.join()
            assertEquals(500L, testScheduler.currentTime)
            assertTrue(writing.isCancelled)
            assertEquals(listOf("close", "keyLock"), h.events)
            assertEquals(UnlockState.Locked, h.manager.state.value)
            assertNull(h.key.currentKey())
        }

    @Test
    fun `teardown deadline cancels drain without extending the key lifetime`() =
        runTest {
            val h = Harness(this, budgetMillis = 25)
            h.open()
            val writing = async { h.repository.transaction { awaitCancellation() } }
            runCurrent()
            h.manager.lockAndAwait(LockReason.USER_REQUESTED)
            writing.join()
            assertEquals(25L, testScheduler.currentTime)
            assertEquals(listOf("close", "keyLock"), h.events)
            assertNull(h.key.currentKey())
            assertEquals(UnlockState.Locked, h.manager.state.value)
            assertTrue(runCatching { h.repository.transaction { } }.exceptionOrNull() is VaultQuiescedException)
        }

    private class Harness(
        scope: CoroutineScope,
        budgetMillis: Long = 500,
    ) {
        val events = mutableListOf<String>()
        val key = ScriptedVaultKeyProvider(events)
        val manager = UnlockManager(keyProvider = key, observerBudgetMillis = budgetMillis, installShutdownHook = false)
        lateinit var repository: InMemoryVaultRepository
        val bootstrap =
            VaultBootstrap(
                unlockManager = manager,
                openVault = {
                    repository = InMemoryVaultRepository()
                    VaultSession(
                        repository = repository,
                        indexStore = InMemoryIndexStore(),
                        personaService = InMemoryPersonaService(),
                        exportService = FakeExportService(),
                        importService = FakeImportService(),
                        exportStages = FakeExportStageRepository(),
                    ) {
                        assertNotNull(key.currentKey())
                        events += "close"
                    }
                },
                provider =
                    object : DocumentsProviderPort {
                        override fun install(services: VaultDocumentsProvider.Services?) = Unit

                        override fun notifyRootsChanged() = Unit
                    },
                scope = scope,
            )

        suspend fun open() {
            val activity = Robolectric.buildActivity(FragmentActivity::class.java).get()
            val prompt =
                BiometricPrompt.PromptInfo
                    .Builder()
                    .setTitle("Unlock")
                    .setNegativeButtonText("Cancel")
                    .build()
            manager.unlock(activity, prompt, VaultKeyProvider.Factor.BIOMETRIC)
            assertTrue(bootstrap.bringUp() is BringUpResult.Ready)
        }
    }
}
