package app.skein.vault

import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import app.skein.core.vault.key.VaultKeyProvider
import app.skein.core.vault.provider.VaultDocumentsProvider
import app.skein.core.vault.session.LockObserver
import app.skein.core.vault.session.LockObserverPriority
import app.skein.core.vault.session.LockReason
import app.skein.core.vault.session.UnlockManager
import app.skein.export.stage.FakeExportStageRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
        seed: suspend (VaultSession) -> Unit = {},
    ) {
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())

        /**
         * The ordinal of every session whose release lambda was invoked, in
         * invocation order (skein-1bx4). `events` only records that *a*
         * close happened; these tests need to know *which* session's.
         */
        val closedSessions: MutableList<Int> = Collections.synchronizedList(mutableListOf())
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
                    val ordinal = ++opens
                    events += "open"
                    duringOpen()
                    VaultSession(
                        repository = repository,
                        indexStore = InMemoryIndexStore(),
                        personaService = InMemoryPersonaService(),
                        exportService = FakeExportService(),
                        importService = FakeImportService(),
                        exportStages = FakeExportStageRepository(),
                    ) {
                        events += "close"
                        closedSessions += ordinal
                        onRelease()
                    }
                },
                provider = provider,
                scope = scope,
                seed = seed,
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

        /**
         * Registers a HIGH-priority observer that blocks until [gate]
         * completes — the "slow observer" of skein-1bx4's repro: it eats the
         * whole shared notify budget, so the lock is tagged `FORCE_TIMEOUT`
         * before the vault close is even reached.
         */
        fun addSlowObserver(gate: CompletableDeferred<Unit>) {
            manager.addLockObserver(
                object : LockObserver {
                    override val priority = LockObserverPriority.HIGH

                    override suspend fun onLocking(
                        epoch: Long,
                        budgetMillis: Long,
                    ) {
                        events += "slowObserver"
                        gate.await()
                    }

                    override fun onLocked(epoch: Long) = Unit

                    override fun onUnlocked(epoch: Long) = Unit
                },
            )
        }
    }

    private var harness: Harness? = null

    private fun harness(
        budgetMillis: Long = UnlockManager.DEFAULT_OBSERVER_BUDGET_MILLIS,
        onRelease: suspend () -> Unit = {},
        seed: suspend (VaultSession) -> Unit = {},
    ): Harness = Harness(budgetMillis, onRelease, seed).also { harness = it }

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

    // ---- seed (skein-ank2) --------------------------------------------------------

    @Test
    fun `bringUp runs the seed after open and before the provider sees the session`() =
        runBlocking {
            // Arrange
            val h =
                harness(
                    seed = { session ->
                        session.personaService.default()
                        harness!!.events += "seed"
                    },
                )
            h.unlock()
            // Act
            h.bootstrap.bringUp()
            // Assert
            assertEquals(listOf("open", "seed", "install(services)", "notifyRoots"), h.events.toList())
        }

    @Test
    fun `the production seed leaves the vault with a first persona`() =
        runBlocking {
            val h = harness(seed = VaultServices::seedFirstPersona)
            h.unlock()

            val result = h.bootstrap.bringUp() as BringUpResult.Ready

            val personaService = result.session.personaService
            assertEquals(1, personaService.observeAll().first().size)
        }

    @Test
    fun `the seed is idempotent across a lock and a second open`() =
        runBlocking {
            val h = harness(seed = VaultServices::seedFirstPersona)
            h.unlock()
            h.bootstrap.bringUp()
            h.lock()
            h.unlock()

            val result = h.bootstrap.bringUp() as BringUpResult.Ready

            val personaService = result.session.personaService
            assertEquals(1, personaService.observeAll().first().size)
        }

    @Test
    fun `a seed that throws closes the vault and reports Failed without installing`() =
        runBlocking {
            val h = harness(seed = { throw IllegalStateException("seed boom") })
            h.unlock()

            val result = h.bootstrap.bringUp()

            assertTrue("expected Failed, got $result", result is BringUpResult.Failed)
            assertEquals(listOf("open", "close"), h.events.toList())
            assertNull(h.bootstrap.session.value)
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

    // ---- lock → re-unlock (skein-1bx4) -------------------------------------------

    /**
     * The device P0, at this layer: on the Pixel 9 Pro Fold every screen-off
     * lock left the next unlock unable to reopen the vault until the process
     * was killed. The slow observer here is what made the lock sequence
     * non-deterministic in the first place — it burns the whole shared notify
     * budget, which used to cancel the enclosing `withTimeoutOrNull` and take
     * the vault close (then a LOW observer) down with it, deferring the close
     * onto `onLocked`'s backstop where it ran, asynchronously, against
     * whatever session came next.
     */
    @Test
    fun `a slow observer's lock leaves the session the next unlock opens untouched`() =
        runBlocking {
            // Arrange
            val gate = CompletableDeferred<Unit>()
            val h = harness(budgetMillis = 50L)
            h.addSlowObserver(gate)
            h.unlock()
            val first = (h.bootstrap.bringUp() as BringUpResult.Ready).session
            h.lock()
            gate.complete(Unit) // release the (already cancelled) observer
            // Act — re-unlock at once, exactly as the user does on the device.
            h.unlock()
            val second = (h.bootstrap.bringUp() as BringUpResult.Ready).session
            // Assert — a second, distinct session is open and published, and
            // only the first one was ever closed.
            assertNotSame(first, second)
            assertSame(second, h.bootstrap.session.value)
            assertEquals(listOf(1), h.closedSessions.toList())
        }

    @Test
    fun `the vault closes before the key is zeroed even when an observer overran the budget`() =
        runBlocking {
            // Arrange — the non-negotiable the TEARDOWN tier protects.
            val gate = CompletableDeferred<Unit>()
            val h = harness(budgetMillis = 50L)
            h.addSlowObserver(gate)
            h.unlock()
            h.bootstrap.bringUp()
            h.events.clear()
            // Act
            h.lock()
            gate.complete(Unit) // release the (already cancelled) observer
            // Assert — "close" precedes "keyLock" (ScriptedVaultKeyProvider
            // records the zeroization) on the lock path itself.
            val order = h.events.toList()
            assertTrue("expected a close in $order", order.contains("close"))
            assertTrue("expected close before keyLock in $order", order.indexOf("close") < order.indexOf("keyLock"))
        }

    /**
     * Fix contract item 3: a close that is still unwinding when the next
     * unlock lands must not reach into the session that unlock opened. In
     * production the structural half of this lives in `DeviceVaultOpener`
     * (one `VaultLifecycle`, and so one `ConnectionPool`, per `open()` call);
     * here the assertion is the observable one — the stale release lambda is
     * invoked for session 1 and for session 1 only.
     */
    @Test
    fun `a stale session close landing after a reopen never closes the new session`() =
        runBlocking {
            // Arrange — session 1's release blocks, so its close is still in
            // flight while session 2 is opened.
            val release = CompletableDeferred<Unit>()
            val h = harness(budgetMillis = 50L, onRelease = { release.await() })
            h.unlock()
            val first = (h.bootstrap.bringUp() as BringUpResult.Ready).session
            h.lock()
            h.unlock()
            val second = (h.bootstrap.bringUp() as BringUpResult.Ready).session
            // Act — the stale close finally completes, after the reopen.
            release.complete(Unit)
            // Assert
            assertNotSame(first, second)
            assertSame(second, h.bootstrap.session.value)
            assertEquals(listOf(1), h.closedSessions.toList())
        }

    @Test
    fun `the session is unpublished before its connections are closed`() =
        runBlocking {
            // Arrange — anything reading `bootstrap.session.value` during the
            // close (ExportStageCoordinator's repository lambda, and whatever
            // it has already launched on the process scope) must see null
            // rather than a session being torn down under it.
            var sessionDuringClose: VaultSession? = null
            val h = harness(onRelease = { sessionDuringClose = harness!!.bootstrap.session.value })
            h.unlock()
            h.bootstrap.bringUp()
            // Act
            h.lock()
            // Assert
            assertNull(sessionDuringClose)
        }
}
