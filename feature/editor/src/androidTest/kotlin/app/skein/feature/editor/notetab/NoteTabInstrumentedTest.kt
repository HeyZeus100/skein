package app.skein.feature.editor.notetab

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.NewDocument
import app.skein.feature.editor.SKEIN_EDITOR_TEST_TAG
import app.skein.feature.editor.backlinks.BacklinksTestTags
import app.skein.testing.InMemoryIndexStore
import app.skein.testing.InMemoryVaultRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration

/**
 * On-device Compose UI test for [NoteTab] (plan `E6.I9`, bd `skein-u01`).
 * Runs on an emulator/device — no Robolectric — matching the shape of
 * `:feature:editor`'s `SkeinEditorInstrumentedTest` /
 * `BacklinksDrawerInstrumentedTest`. bd `skein-k3b2` tracks the CI emulator
 * lane that will run this; this worktree only compiles it (bd `skein-u01`
 * acceptance: "compile only, gated on skein-k3b2").
 *
 * Covers the composable-level wiring `NoteTabStateTest` can't (it drives
 * [NoteTabState] Compose-off): the editor and backlinks drawer both render
 * inside one tab, and the ✦ graph button's callback actually fires on tap.
 */
@RunWith(AndroidJUnit4::class)
class NoteTabInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun the_tab_renders_the_editor_and_the_collapsed_backlinks_drawer() {
        val repo = InMemoryVaultRepository()
        val index = InMemoryIndexStore()
        val doc = runBlocking { repo.note("My Note", body = "hello from the vault") }

        composeRule.setContent {
            MaterialTheme {
                NoteTab(docId = doc.id, vaultRepository = repo, indexStore = index)
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(NoteTabTestTags.ROOT).assertIsDisplayed()
        composeRule.onNodeWithTag(SKEIN_EDITOR_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(BacklinksTestTags.HEADER).assertIsDisplayed()
    }

    @Test
    fun typing_in_the_editor_requests_a_pin_exactly_once() {
        val repo = InMemoryVaultRepository()
        val index = InMemoryIndexStore()
        val doc = runBlocking { repo.note("My Note", body = "") }
        var pinCount = 0

        composeRule.setContent {
            MaterialTheme {
                NoteTab(
                    docId = doc.id,
                    vaultRepository = repo,
                    indexStore = index,
                    onPin = { pinCount++ },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SKEIN_EDITOR_TEST_TAG).performTextInput("hi")
        composeRule.waitForIdle()

        assertEquals(1, pinCount)
    }

    @Test
    fun the_graph_button_invokes_onOpenGraph_with_the_current_docId() {
        val repo = InMemoryVaultRepository()
        val index = InMemoryIndexStore()
        val doc = runBlocking { repo.note("My Note") }
        var opened: String? = null

        composeRule.setContent {
            MaterialTheme {
                NoteTab(
                    docId = doc.id,
                    vaultRepository = repo,
                    indexStore = index,
                    onOpenGraph = { opened = it },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(NoteTabTestTags.GRAPH_BUTTON).performClick()

        assertEquals(doc.id, opened)
    }

    @Test
    fun the_flush_handle_is_registered_once_the_note_has_loaded_and_removed_on_disposal() {
        val repo = InMemoryVaultRepository()
        val index = InMemoryIndexStore()
        val doc = runBlocking { repo.note("My Note") }
        var registered: (suspend (Duration) -> Boolean)? = null
        var unregisteredCount = 0
        val showTab = mutableStateOf(true)

        composeRule.setContent {
            MaterialTheme {
                if (showTab.value) {
                    NoteTab(
                        docId = doc.id,
                        vaultRepository = repo,
                        indexStore = index,
                        registerFlush = { registered = it },
                        unregisterFlush = { unregisteredCount++ },
                    )
                }
            }
        }
        composeRule.waitForIdle()

        assertTrue("flush handle should be registered once the tab is composed", registered != null)

        showTab.value = false
        composeRule.waitForIdle()

        assertEquals(1, unregisteredCount)
    }

    private suspend fun InMemoryVaultRepository.note(
        title: String,
        body: String = "body of $title",
    ): Document = createDocument(NewDocument(kind = DocumentKind.NOTE, title = title, bodyMd = body))
}
