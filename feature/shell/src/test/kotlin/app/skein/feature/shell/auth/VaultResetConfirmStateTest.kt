// skein-v3wb — [VaultResetConfirmState] against a scripted `performReset()`,
// following `VaultSetupStateTest`'s shape: one behaviour per test, AAA
// structure, the real `VaultReset.reset()` abstracted behind a
// `suspend () -> VaultResetResult` factory so the mapping is JVM-testable.

package app.skein.feature.shell.auth

import app.skein.core.vault.lifecycle.VaultResetResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultResetConfirmStateTest {
    private fun CoroutineScope.state(
        onReset: () -> Unit = {},
        performReset: suspend () -> VaultResetResult = { VaultResetResult.Success },
    ) = VaultResetConfirmState(
        scope = this,
        performReset = performReset,
        onReset = onReset,
    )

    // ---- typed confirmation -------------------------------------------------

    @Test
    fun `starts on the type-confirmation step with an empty field`() =
        runTest {
            val state = state()

            assertEquals(VaultResetStep.TypeConfirmation, state.step)
            assertEquals("", state.typedText)
        }

    @Test
    fun `wrong typed text leaves canContinue false`() =
        runTest {
            val state = state()

            state.onTypedTextChange("reset")

            assertFalse(state.canContinue)
        }

    @Test
    fun `an empty field leaves canContinue false`() =
        runTest {
            val state = state()

            assertFalse(state.canContinue)
        }

    @Test
    fun `the exact word RESET makes canContinue true`() =
        runTest {
            val state = state()

            state.onTypedTextChange("RESET")

            assertTrue(state.canContinue)
        }

    @Test
    fun `continueToFinalConfirm is a no-op while the typed text is wrong`() =
        runTest {
            val state = state()
            state.onTypedTextChange("reset")

            state.continueToFinalConfirm()

            assertEquals(VaultResetStep.TypeConfirmation, state.step)
        }

    @Test
    fun `continueToFinalConfirm advances once the typed text matches`() =
        runTest {
            val state = state()
            state.onTypedTextChange("RESET")

            state.continueToFinalConfirm()

            assertEquals(VaultResetStep.FinalConfirm, state.step)
        }

    // ---- both confirms required ----------------------------------------------

    @Test
    fun `confirmReset is a no-op on the type-confirmation step`() =
        runTest {
            var calls = 0
            val state =
                state(
                    performReset = {
                        calls++
                        VaultResetResult.Success
                    },
                )

            state.confirmReset()
            testScheduler.advanceUntilIdle()

            assertEquals(0, calls)
        }

    @Test
    fun `typing RESET alone never runs the reset`() =
        runTest {
            var calls = 0
            val state =
                state(
                    performReset = {
                        calls++
                        VaultResetResult.Success
                    },
                )

            state.onTypedTextChange("RESET")
            testScheduler.advanceUntilIdle()

            assertEquals(0, calls)
        }

    @Test
    fun `both the typed confirmation and the final confirm are required to run reset`() =
        runTest {
            var calls = 0
            val state =
                state(
                    performReset = {
                        calls++
                        VaultResetResult.Success
                    },
                )
            state.onTypedTextChange("RESET")
            state.continueToFinalConfirm()

            state.confirmReset()
            testScheduler.advanceUntilIdle()

            assertEquals(1, calls)
        }

    @Test
    fun `a second tap of confirmReset while resetting does not issue a second call`() =
        runTest {
            var calls = 0
            val state =
                state(
                    performReset = {
                        calls++
                        VaultResetResult.Success
                    },
                )
            state.onTypedTextChange("RESET")
            state.continueToFinalConfirm()

            state.confirmReset()
            state.confirmReset()
            testScheduler.advanceUntilIdle()

            assertEquals(1, calls)
        }

    // ---- outcomes -------------------------------------------------------------

    @Test
    fun `Success calls onReset`() =
        runTest {
            var reset = false
            val state =
                state(
                    onReset = { reset = true },
                    performReset = { VaultResetResult.Success },
                )
            state.onTypedTextChange("RESET")
            state.continueToFinalConfirm()

            state.confirmReset()
            testScheduler.advanceUntilIdle()

            assertTrue(reset)
        }

    @Test
    fun `RefusedUnlocked shows a message and never calls onReset`() =
        runTest {
            var reset = false
            val state =
                state(
                    onReset = { reset = true },
                    performReset = { VaultResetResult.RefusedUnlocked },
                )
            state.onTypedTextChange("RESET")
            state.continueToFinalConfirm()

            state.confirmReset()
            testScheduler.advanceUntilIdle()

            assertFalse(reset)
            assertTrue(state.errorMessage!!.contains("unlocked"))
        }

    @Test
    fun `Failed shows a message and never calls onReset`() =
        runTest {
            var reset = false
            val state =
                state(
                    onReset = { reset = true },
                    performReset = { VaultResetResult.Failed("vault reset io failure: IOException") },
                )
            state.onTypedTextChange("RESET")
            state.continueToFinalConfirm()

            state.confirmReset()
            testScheduler.advanceUntilIdle()

            assertFalse(reset)
            assertFalse(state.errorMessage!!.contains("IOException"))
        }

    @Test
    fun `resetting is true only while the reset is in flight`() =
        runTest {
            val state = state(performReset = { VaultResetResult.Success })
            state.onTypedTextChange("RESET")
            state.continueToFinalConfirm()

            state.confirmReset()

            assertTrue(state.resetting)
            testScheduler.advanceUntilIdle()
        }

    @Test
    fun `resetting resets to false after a refusal so the user can retry`() =
        runTest {
            val state = state(performReset = { VaultResetResult.RefusedUnlocked })
            state.onTypedTextChange("RESET")
            state.continueToFinalConfirm()

            state.confirmReset()
            testScheduler.advanceUntilIdle()

            assertFalse(state.resetting)
        }

    // ---- cancel -----------------------------------------------------------------

    @Test
    fun `cancel returns to the type-confirmation step and clears the typed text`() =
        runTest {
            val state = state()
            state.onTypedTextChange("RESET")
            state.continueToFinalConfirm()

            state.cancel()

            assertEquals(VaultResetStep.TypeConfirmation, state.step)
            assertEquals("", state.typedText)
        }

    @Test
    fun `cancel clears any error message`() =
        runTest {
            val state = state(performReset = { VaultResetResult.RefusedUnlocked })
            state.onTypedTextChange("RESET")
            state.continueToFinalConfirm()
            state.confirmReset()
            testScheduler.advanceUntilIdle()

            state.cancel()

            assertNull(state.errorMessage)
        }
}
