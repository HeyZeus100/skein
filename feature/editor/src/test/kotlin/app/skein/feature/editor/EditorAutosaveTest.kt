package app.skein.feature.editor

import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

/**
 * Drives [EditorState]'s autosave machinery directly — Compose-off, no
 * `SkeinEditor` composable involved — via `TestScope`/`runTest` so the
 * 500ms debounce window is virtual time, not a real sleep. Covers `E7.I4`
 * (bd `skein-twb`): debounce coalescing, error surfacing, and the bounded
 * [EditorState.flush] the lock coordinator calls
 * (`LOCK_POLICY_INDEXING.md` §4.3).
 *
 * Every [EditorState] here is built with `autosaveScope = backgroundScope`
 * so its permanently-running debounce collector is torn down by the test
 * framework at the end of the test instead of failing it for leaving a job
 * active.
 */
class EditorAutosaveTest {
    @Test
    fun `rapid typing within the debounce window produces a single save`() =
        runTest {
            val saved = mutableListOf<String>()
            val state =
                EditorState(
                    onSave = { saved.add(it.text) },
                    autosaveDebounce = Duration.ofMillis(500),
                    autosaveScope = backgroundScope,
                )

            state.onValueChange(TextFieldValue("a"))
            advanceTimeBy(150)
            runCurrent()
            state.onValueChange(TextFieldValue("ab"))
            advanceTimeBy(150)
            runCurrent()
            state.onValueChange(TextFieldValue("abc"))

            // Settle past the debounce window from the *last* keystroke.
            advanceTimeBy(600)
            runCurrent()

            assertEquals(listOf("abc"), saved)
            assertEquals(AutosaveStatus.SAVED, state.autosaveStatus.value)
        }

    @Test
    fun `onSave error moves status to ERROR and exposes the message`() =
        runTest {
            val state =
                EditorState(
                    onSave = { throw IllegalStateException("disk full") },
                    autosaveDebounce = Duration.ofMillis(50),
                    autosaveScope = backgroundScope,
                )

            state.onValueChange(TextFieldValue("x"))
            advanceTimeBy(100)
            runCurrent()

            assertEquals(AutosaveStatus.ERROR, state.autosaveStatus.value)
            assertEquals("disk full", state.autosaveError.value)
        }

    @Test
    fun `flush returns true once the pending save completes before the deadline`() =
        runTest {
            val saved = mutableListOf<String>()
            val state =
                EditorState(
                    onSave = { saved.add(it.text) },
                    // Long debounce: only flush() should trigger the save.
                    autosaveDebounce = Duration.ofSeconds(10),
                    autosaveScope = backgroundScope,
                )
            state.onValueChange(TextFieldValue("draft"))

            val flushed = state.flush(Duration.ofSeconds(1))

            assertTrue(flushed)
            assertEquals(listOf("draft"), saved)
            assertEquals(AutosaveStatus.SAVED, state.autosaveStatus.value)
            assertNull(state.autosaveError.value)
        }

    @Test
    fun `flush returns false once the deadline expires before the save completes`() =
        runTest {
            val neverCompletes = CompletableDeferred<Unit>()
            val state =
                EditorState(
                    onSave = { neverCompletes.await() },
                    autosaveDebounce = Duration.ofSeconds(10),
                    autosaveScope = backgroundScope,
                )
            state.onValueChange(TextFieldValue("draft"))

            val flushed = state.flush(Duration.ofMillis(200))

            assertFalse(flushed)
            assertEquals(AutosaveStatus.UNSAVED, state.autosaveStatus.value)
        }

    @Test
    fun `flush cancels a save stalled by lock cleanly instead of hanging or throwing`() =
        runTest {
            var cleanedUp = false
            val state =
                EditorState(
                    onSave = {
                        try {
                            awaitCancellation()
                        } finally {
                            cleanedUp = true
                        }
                    },
                    autosaveDebounce = Duration.ofSeconds(10),
                    autosaveScope = backgroundScope,
                )
            state.onValueChange(TextFieldValue("mid-edit when the lock fires"))

            val flushed = state.flush(Duration.ofMillis(200))

            assertFalse(flushed)
            assertTrue("onSave's suspension should be cancelled, not orphaned", cleanedUp)
            assertEquals(AutosaveStatus.UNSAVED, state.autosaveStatus.value)
        }

    @Test
    fun `unchanged text never triggers a save`() =
        runTest {
            val saved = mutableListOf<String>()
            val state =
                EditorState(
                    initial = TextFieldValue("same"),
                    onSave = { saved.add(it.text) },
                    autosaveDebounce = Duration.ofMillis(50),
                    autosaveScope = backgroundScope,
                )

            // Cursor-only change: text is identical to the initial value.
            state.onValueChange(state.value.copy(selection = state.value.selection))
            advanceTimeBy(200)
            runCurrent()

            assertTrue(saved.isEmpty())
            assertEquals(AutosaveStatus.IDLE, state.autosaveStatus.value)
        }
}
