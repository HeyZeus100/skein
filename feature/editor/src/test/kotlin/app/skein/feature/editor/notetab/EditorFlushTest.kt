package app.skein.feature.editor.notetab

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.Lifecycle
import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.NewDocument
import app.skein.core.vault.session.LockObserverPriority
import app.skein.feature.editor.SKEIN_EDITOR_TEST_TAG
import app.skein.testing.InMemoryIndexStore
import app.skein.testing.InMemoryVaultRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * UX-P0-11 / K-P0-9: an edit still inside the 500 ms autosave debounce is
 * persisted when the note tab leaves composition (tab close, note → chat
 * switch, lock, fold), when the Activity stops, and before the vault locks.
 * Before the fix all three dropped it: the debounce lived in the tab's
 * `rememberCoroutineScope`, cancelled with the composition, and nothing ever
 * called the flush handle.
 *
 * Pinned to SDK 34 (bd memory `robolectric-sdk37-needs-java21`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorFlushTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val repo = InMemoryVaultRepository()
    private val doc: Document =
        runBlocking { repo.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "Note", bodyMd = "")) }

    @Test
    fun pendingEditIsSavedWhenTheTabLeavesComposition() {
        var shown by mutableStateOf(true)
        composeRule.setContent {
            MaterialTheme { if (shown) NoteTab(doc.id, repo, InMemoryIndexStore()) }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false // keep the debounce from saving on its own

        composeRule.onNodeWithTag(SKEIN_EDITOR_TEST_TAG).performTextInput("typed")
        shown = false
        composeRule.mainClock.advanceTimeByFrame()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("typed", savedBody())
    }

    @Test
    fun pendingEditIsSavedOnStop() {
        composeRule.setContent {
            MaterialTheme { NoteTab(docId = doc.id, vaultRepository = repo, indexStore = InMemoryIndexStore()) }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false

        composeRule.onNodeWithTag(SKEIN_EDITOR_TEST_TAG).performTextInput("typed")
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("typed", savedBody())
    }

    @Test
    fun pendingEditIsSavedBeforeTheVaultLocks() =
        runTest {
            val state = NoteTabState(doc.id, repo, InMemoryIndexStore(), backgroundScope)
            runCurrent()
            val editor = state.editorState
            editor.onValueChange(TextFieldValue(editor.source + "typed", editor.value.selection))

            val observer = FlushBeforeLock(state)
            observer.onLocking(epoch = 1, budgetMillis = 500)

            // LOW runs while the vault is still open: `UnlockManager` finishes it before the TEARDOWN close.
            assertEquals(LockObserverPriority.LOW, observer.priority)
            assertEquals("typed", savedBody())
        }

    private fun savedBody(): String? = runBlocking { repo.getDocument(doc.id) }?.bodyMd
}
