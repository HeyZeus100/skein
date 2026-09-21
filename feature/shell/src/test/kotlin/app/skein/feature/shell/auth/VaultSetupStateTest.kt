package app.skein.feature.shell.auth

import app.skein.core.vault.key.SetupResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * skein-ank2: [VaultSetupState] against a scripted `setup()` — one test per
 * [SetupResult] branch, plus the in-flight / double-tap guards. The real
 * `VaultKeyProvider.setup` takes Android types (`FragmentActivity`,
 * `PromptInfo`) the host JVM cannot construct, which is why the state
 * holder is expressed over a `suspend () -> SetupResult` factory — the
 * same seam `UnlockManager.unlockWith` / `VaultKeyProviderImpl.setupWith`
 * use for their own JVM tests.
 *
 * Instances are built with `backgroundScope` where a run must outlive the
 * test body, and with `this` (the `TestScope`) where the launched work
 * completes on its own — see `SettingsViewModelTest` for the reasoning.
 */
class VaultSetupStateTest {
    private class Recorder {
        var provisioned: Boolean? = null
        var alreadyInitialised = false
    }

    private fun CoroutineScope.state(
        recorder: Recorder,
        runSetup: suspend () -> SetupResult,
    ) = VaultSetupState(
        scope = this,
        runSetup = runSetup,
        onProvisioned = { recorder.provisioned = it },
        onAlreadyInitialised = { recorder.alreadyInitialised = true },
    )

    // ---- initial / in-flight -------------------------------------------------

    @Test
    fun `starts in Ready without calling setup`() =
        runTest {
            var calls = 0
            val state =
                state(Recorder()) {
                    calls++
                    SetupResult.UserCancelled
                }

            assertEquals(VaultSetupUiState.Ready, state.uiState)
            assertEquals(0, calls)
        }

    @Test
    fun `begin moves to InProgress while setup is running`() =
        runTest {
            val gate = CompletableDeferred<SetupResult>()
            val state = backgroundScope.state(Recorder()) { gate.await() }

            state.begin()
            testScheduler.runCurrent()

            assertEquals(VaultSetupUiState.InProgress, state.uiState)
        }

    @Test
    fun `a second begin while in flight does not run setup again`() =
        runTest {
            var calls = 0
            val gate = CompletableDeferred<SetupResult>()
            val state =
                backgroundScope.state(Recorder()) {
                    calls++
                    gate.await()
                }

            state.begin()
            state.begin()
            testScheduler.runCurrent()

            assertEquals(1, calls)
        }

    // ---- every SetupResult branch --------------------------------------------

    @Test
    fun `Success reports provisioned with strongBoxBacked=true`() =
        runTest {
            val recorder = Recorder()
            val state = state(recorder) { SetupResult.Success(masterKeyVersion = 1, strongBoxBacked = true) }

            state.begin()
            testScheduler.advanceUntilIdle()

            assertEquals(true, recorder.provisioned)
        }

    @Test
    fun `StrongBoxUnavailableFallback reports provisioned with strongBoxBacked=false`() =
        runTest {
            val recorder = Recorder()
            val state = state(recorder) { SetupResult.StrongBoxUnavailableFallback(masterKeyVersion = 1) }

            state.begin()
            testScheduler.advanceUntilIdle()

            assertEquals(false, recorder.provisioned)
        }

    @Test
    fun `AlreadyInitialised routes to unlock without reporting provisioned`() =
        runTest {
            val recorder = Recorder()
            val state = state(recorder) { SetupResult.AlreadyInitialised }

            state.begin()
            testScheduler.advanceUntilIdle()

            assertTrue(recorder.alreadyInitialised)
            assertNull(recorder.provisioned)
        }

    @Test
    fun `UserCancelled offers a retry`() =
        runTest {
            val state = state(Recorder()) { SetupResult.UserCancelled }

            state.begin()
            testScheduler.advanceUntilIdle()

            assertTrue(state.uiState is VaultSetupUiState.Retry)
        }

    @Test
    fun `NoBiometricEnrolled shows the enrolment guidance`() =
        runTest {
            val state = state(Recorder()) { SetupResult.NoBiometricEnrolled }

            state.begin()
            testScheduler.advanceUntilIdle()

            assertEquals(VaultSetupUiState.NoBiometricEnrolled, state.uiState)
        }

    @Test
    fun `a corrupt envelope routes to unlock, never to a retry of setup`() =
        runTest {
            val recorder = Recorder()
            val state = state(recorder) { SetupResult.Failed(EnvelopeUnreadable.REASON_CORRUPT) }

            state.begin()
            testScheduler.advanceUntilIdle()

            assertTrue(recorder.alreadyInitialised)
            assertFalse(state.uiState is VaultSetupUiState.Retry)
        }

    @Test
    fun `an unreadable envelope routes to unlock, never to a retry of setup`() =
        runTest {
            val recorder = Recorder()
            val state = state(recorder) { SetupResult.Failed(EnvelopeUnreadable.REASON_IO) }

            state.begin()
            testScheduler.advanceUntilIdle()

            assertTrue(recorder.alreadyInitialised)
        }

    @Test
    fun `any other Failed offers a retry`() =
        runTest {
            val state = state(Recorder()) { SetupResult.Failed("biometric hardware unavailable") }

            state.begin()
            testScheduler.advanceUntilIdle()

            assertTrue(state.uiState is VaultSetupUiState.Retry)
        }

    @Test
    fun `a Failed reason is never surfaced verbatim`() =
        runTest {
            val reason = "cipher init failed: SomeInternalException"
            val state = state(Recorder()) { SetupResult.Failed(reason) }

            state.begin()
            testScheduler.advanceUntilIdle()

            val message = (state.uiState as VaultSetupUiState.Retry).message
            assertFalse(message.contains(reason))
        }

    @Test
    fun `cancellation and failure show different messages`() =
        runTest {
            val cancelled = state(Recorder()) { SetupResult.UserCancelled }
            val failed = state(Recorder()) { SetupResult.Failed("failed to create Layer-0 Keystore entries") }

            cancelled.begin()
            failed.begin()
            testScheduler.advanceUntilIdle()

            assertNotEquals(
                (cancelled.uiState as VaultSetupUiState.Retry).message,
                (failed.uiState as VaultSetupUiState.Retry).message,
            )
        }

    @Test
    fun `setup throwing offers a retry instead of crashing`() =
        runTest {
            val state = state(Recorder()) { throw IllegalStateException("boom") }

            state.begin()
            testScheduler.advanceUntilIdle()

            assertTrue(state.uiState is VaultSetupUiState.Retry)
        }

    // ---- retry ---------------------------------------------------------------

    @Test
    fun `begin after a cancellation runs setup again`() =
        runTest {
            var calls = 0
            val results = ArrayDeque<SetupResult>(listOf(SetupResult.UserCancelled, SetupResult.NoBiometricEnrolled))
            val state =
                state(Recorder()) {
                    calls++
                    results.removeFirst()
                }
            state.begin()
            testScheduler.advanceUntilIdle()

            state.begin()
            testScheduler.advanceUntilIdle()

            assertEquals(2, calls)
            assertEquals(VaultSetupUiState.NoBiometricEnrolled, state.uiState)
        }
}
