package app.skein.vault

import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import app.skein.core.vault.key.VaultKeyProvider
import app.skein.core.vault.provider.VaultDocumentsProvider
import app.skein.core.vault.session.LockReason
import app.skein.core.vault.session.UnlockManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import us.aherrera.skein.testing.FakeExportService
import us.aherrera.skein.testing.FakeImportService
import us.aherrera.skein.testing.InMemoryIndexStore
import us.aherrera.skein.testing.InMemoryPersonaService
import us.aherrera.skein.testing.InMemoryVaultRepository
import java.util.Collections

/**
 * skein-2ige: `VaultBootstrap` over a real [UnlockManager] (so the lock
 * sequence — observers, then key zeroization, then `onLocked` — is the
 * production one) with a [ScriptedVaultKeyProvider], an in-memory
 * `openVault`, and a recording [DocumentsProviderPort]. Every step appends
 * to one event list so the tests assert ORDER, not just calls.
 *
 * Robolectric only because `UnlockManager.unlock` takes a `FragmentActivity`
 * and a `BiometricPrompt.PromptInfo` (the scripted provider ignores both).
 * Pinned to SDK 34 (bd memory `robolectric-sdk37-needs-java21`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultBootstrapTest {
    private class RecordingProviderPort(
        private val events: MutableList<String>,
    ) : DocumentsProviderPort {
        @Volatile
        var installed: VaultDocumentsProvider.Services? = null

        override fun install(services: VaultDocumentsProvider.Services?) {
            installed = services
            events += if (services == null) "install(null)" else "install(services)"
        }

        override fun notifyRootsChanged() {
            events += "notifyRoots"
        }
    }

    private class Harness(
        budgetMillis: Long = UnlockManager.DEFAULT_OBSERVER_BUDGET_MILLIS,
        private val onRelease: suspend () -> Unit = {},
    ) {
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val keyProvider = ScriptedVaultKeyProvider(events)
        val manager =
            UnlockManager(
                keyProvider = keyProvider,
                observerBudgetMillis = budgetMillis,
                scope = null,
                installShutdownHook = false,
            )
        val provider = RecordingProviderPort(events)
        val repository = InMemoryVaultRepository()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** Runs inside `openVault`, after the "open" event — tests inject a mid-open lock or a failure here. */
        var duringOpen: suspend () -> Unit = {}

        @Volatile
        var opens: Int = 0

        val bootstrap =
            VaultBootstrap(
                unlockManager = manager,
                openVault = {
                    opens++
                    events += "open"
                    duringOpen()
                    VaultSession(
                        repository = repository,
                        indexStore = InMemoryIndexStore(),
                        personaService = InMemoryPersonaService(),
                        exportService = FakeExportService(),
                        importService = FakeImportService(),
                    ) {
                        events += "close"
                        onRelease()
                    }
                },
                provider = provider,
                scope = scope,
            )

        private val activity: FragmentActivity = Robolectric.buildActivity(FragmentActivity::class.java).get()
        private val prompt =
            BiometricPrompt.PromptInfo
                .Builder()
                .setTitle("Unlock")
                .setNegativeButtonText("Cancel")
                .build()

        suspend fun unlock() = manager.unlock(activity, prompt, VaultKeyProvider.Factor.BIOMETRIC)

        suspend fun lock() = manager.lockAndAwait(LockReason.USER_REQUESTED)
    }

    private var harness: Harness? = null

    private fun harness(
        budgetMillis: Long = UnlockManager.DEFAULT_OBSERVER_BUDGET_MILLIS,
        onRelease: suspend () -> Unit = {},
    ): Harness = Harness(budgetMillis, onRelease).also { harness = it }

    @After
    fun tearDown() {
        harness?.scope?.cancel()
    }

    // ---- unlock → bringUp -------------------------------------------------------

    @Test
    fun `bringUp after unlock opens the vault, installs the provider, then notifies roots — in that order`() =
        runBlocking {
            // Arrange
            val h = harness()
            h.unlock()
            // Act
            h.bootstrap.bringUp()
            // Assert
            assertEquals(listOf("open", "install(services)", "notifyRoots"), h.events.toList())
        }

    @Test
    fun `bringUp hands the live repository to the provider`() =
        runBlocking {
            val h = harness()
            h.unlock()

            val result = h.bootstrap.bringUp() as BringUpResult.Ready

            assertSame(result.session.repository, h.provider.installed?.repository)
        }

    @Test
    fun `bringUp hands the unlock manager's state flow to the provider`() =
        runBlocking {
            val h = harness()
            h.unlock()

            h.bootstrap.bringUp()

            assertSame(h.manager.state, h.provider.installed?.unlockState)
        }

    @Test
    fun `bringUp exposes the session it returned`() =
        runBlocking {
            val h = harness()
            h.unlock()

            val result = h.bootstrap.bringUp() as BringUpResult.Ready

            assertSame(result.session, h.bootstrap.session.value)
        }

    @Test
    fun `a second bringUp reuses the open session`() =
        runBlocking {
            val h = harness()
            h.unlock()
            h.bootstrap.bringUp()

            h.bootstrap.bringUp()

            assertEquals(1, h.opens)
        }

    @Test
    fun `bringUp while locked returns NotUnlocked`() =
        runBlocking {
            val h = harness()

            val result = h.bootstrap.bringUp()

            assertEquals(BringUpResult.NotUnlocked, result)
        }

    @Test
    fun `bringUp while locked never opens the vault`() =
        runBlocking {
            val h = harness()

            h.bootstrap.bringUp()

            assertEquals(emptyList<String>(), h.events.toList())
        }

    @Test
    fun `bringUp reports the opener's failure reason`() =
        runBlocking {
            val h = harness()
            h.duringOpen = { throw VaultOpenException("wrong vault key") }
            h.unlock()

            val result = h.bootstrap.bringUp()

            assertEquals(BringUpResult.Failed("wrong vault key"), result)
        }

    @Test
    fun `a failed open installs nothing`() =
        runBlocking {
            val h = harness()
            h.duringOpen = { throw VaultOpenException("wrong vault key") }
            h.unlock()

            h.bootstrap.bringUp()

            assertEquals(listOf("open"), h.events.toList())
        }

    @Test
    fun `a lock that lands while the vault is opening closes it without ever installing`() =
        runBlocking {
            val h = harness()
            h.duringOpen = { h.lock() }
            h.unlock()

            h.bootstrap.bringUp()

            assertEquals(listOf("open", "keyLock", "close"), h.events.toList())
        }

    @Test
    fun `a lock that lands while the vault is opening reports NotUnlocked`() =
        runBlocking {
            val h = harness()
            h.duringOpen = { h.lock() }
            h.unlock()

            val result = h.bootstrap.bringUp()

            assertEquals(BringUpResult.NotUnlocked, result)
        }

    // ---- lock -------------------------------------------------------------------

    @Test
    fun `lock uninstalls the provider, notifies roots, closes the session, and only then zeroes the key`() =
        runBlocking {
            // Arrange
            val h = harness()
            h.unlock()
            h.bootstrap.bringUp()
            h.events.clear()
            // Act
            h.lock()
            // Assert
            assertEquals(listOf("install(null)", "notifyRoots", "close", "keyLock"), h.events.toList())
        }

    @Test
    fun `lock drops the session reference`() =
        runBlocking {
            val h = harness()
            h.unlock()
            h.bootstrap.bringUp()

            h.lock()

            assertNull(h.bootstrap.session.value)
        }

    @Test
    fun `lock before any bringUp leaves the provider untouched`() =
        runBlocking {
            val h = harness()
            h.unlock()

            h.lock()

            assertEquals(listOf("keyLock"), h.events.toList())
        }

    @Test
    fun `a session whose close overruns the observer budget is still dropped once locked`() =
        runBlocking {
            // Arrange — the release never returns, so onLocking is cut off by
            // the budget (FORCE_TIMEOUT) and onLocked's backstop must act.
            val h = harness(budgetMillis = 50L, onRelease = { awaitCancellation() })
            h.unlock()
            h.bootstrap.bringUp()
            // Act
            h.lock()
            // Assert
            assertNull(h.bootstrap.session.value)
        }
}
