// skein-xtov.24.8 (AL-09a): the Knowledge entries in the real NavDisplay
// shell — open by kind from the list (LC-20), the note as a session pending
// writer (ADAPTIVE_LAYOUT_SPEC.md §7.7), the gone state and the prune after a
// delete (OBJECT_LIFECYCLE_SPEC.md §3.5, §6.7), and the new-note draft (§6.2).
package app.skein.feature.editor.entries

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.FrontmatterKeys
import app.skein.core.model.Role
import app.skein.core.navigation.ConnectionsKey
import app.skein.core.navigation.Destination
import app.skein.core.navigation.FileKey
import app.skein.core.navigation.KnowledgeHomeKey
import app.skein.core.navigation.NewNoteKey
import app.skein.core.navigation.NoteKey
import app.skein.core.navigation.SkeinId
import app.skein.feature.editor.SKEIN_EDITOR_TEST_TAG
import app.skein.feature.editor.notetab.NoteTabTestTags
import app.skein.feature.shell.host.EntryChromeTestTags
import app.skein.feature.shell.host.SkeinShellState
import app.skein.feature.timeline.TimelineTestTags
import app.skein.testing.fakeVault
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KnowledgeEntriesTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var launchPlan: Document
    private lateinit var spec: Document
    private lateinit var chat: Document
    private val vault =
        fakeVault {
            launchPlan = note("Fold launch plan", "Targets M2.")
            spec = attachment("spec.pdf", "application/pdf", byteArrayOf(1, 2, 3))
            note(
                "spec",
                "Extracted text of the spec.",
                frontmatter =
                    JsonObject(
                        mapOf(
                            FrontmatterKeys.SOURCE to JsonPrimitive(spec.id),
                        ),
                    ),
            )
            chat = chat("Chat about the Fold", Role.USER to "Hello")
        }
    private val size = mutableStateOf(COMPACT)
    private lateinit var shell: SkeinShellState

    private fun setHost() {
        composeRule.setContent { SkeinTheme { KnowledgeHost(vault, size.value, { shell = it }) } }
        composeRule.runOnIdle { shell.navigate { switchTo(it, Destination.KNOWLEDGE) } }
        composeRule.waitForIdle()
    }

    private fun id(document: Document) = SkeinId.of(document.id)

    @Test
    fun `a list row opens its item by kind, and chats are not listed`() {
        setHost()
        composeRule.onNodeWithTag(TimelineTestTags.entryRow(chat.id)).assertDoesNotExist()

        composeRule.onNodeWithTag(TimelineTestTags.entryRow(launchPlan.id)).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(KnowledgeHomeKey, NoteKey(id(launchPlan))), shell.nav.stack(Destination.KNOWLEDGE))
        composeRule.onNodeWithTag(NoteTabTestTags.ROOT).assertIsDisplayed()

        composeRule.runOnIdle { shell.navigate { back(it, app.skein.core.navigation.NavMode.PHONE) } }
        composeRule.onNodeWithTag(TimelineTestTags.entryRow(spec.id)).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(KnowledgeHomeKey, FileKey(id(spec))), shell.nav.stack(Destination.KNOWLEDGE))
        composeRule.onNodeWithTag(FileRouteTestTags.ROOT).assertIsDisplayed()
        composeRule.onNodeWithText("Extracted text of the spec.").assertIsDisplayed()
        composeRule.onNodeWithTag(NoteTabTestTags.ROOT).assertDoesNotExist()
    }

    @Test
    fun `an open note is a session pending writer, and the lock drains its edits`() {
        setHost()
        assertEquals(0, shell.stores.pendingWriterCount)
        composeRule.runOnIdle { shell.navigate { goTo(it, NoteKey(id(launchPlan))) } }
        composeRule.waitForIdle()
        assertEquals(1, shell.stores.pendingWriterCount)

        val editor = composeRule.onNodeWithTag(SKEIN_EDITOR_TEST_TAG)
        editor.performTouchInput { click() }
        editor.performTextInput("Draft ")
        runBlocking { shell.stores.onLocking(1L, 500L) }
        assertTrue(runBlocking { vault.getDocument(launchPlan.id) }!!.bodyMd!!.contains("Draft "))

        composeRule.runOnIdle { shell.navigate { back(it, app.skein.core.navigation.NavMode.PHONE) } }
        composeRule.waitForIdle()
        assertEquals("a closed note unregisters", 0, shell.stores.pendingWriterCount)
    }

    @Test
    fun `a deleted note shows its gone state, and Go to Knowledge prunes it and its Connections`() {
        size.value = EXPANDED
        setHost()
        composeRule.runOnIdle {
            shell.navigate { goTo(it, NoteKey(id(launchPlan))) }
            shell.navigate { follow(it, ConnectionsKey(id(launchPlan))) }
        }
        composeRule.onNodeWithTag(KnowledgeEntryTestTags.CONNECTIONS).assertIsDisplayed()

        runBlocking { vault.deleteDocument(launchPlan.id) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(EntryChromeTestTags.GONE).assertIsDisplayed()
        composeRule.onNodeWithText("This note was deleted.").assertIsDisplayed()

        composeRule.onNodeWithText("Go to Knowledge").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(KnowledgeHomeKey), shell.nav.stack(Destination.KNOWLEDGE))
        composeRule.onNodeWithTag(KnowledgeEntryTestTags.CONNECTIONS).assertDoesNotExist()
        composeRule.onNodeWithTag(KnowledgeEntryTestTags.EMPTY_DETAIL).assertIsDisplayed()
        composeRule.onNodeWithTag(TimelineTestTags.entryRow(launchPlan.id)).assertDoesNotExist()
    }

    @Test
    fun `a new note is a draft until its first non-blank save, which creates the row under the draft id`() {
        setHost()
        val draft = SkeinId.random()
        composeRule.runOnIdle { shell.navigate { goTo(it, NewNoteKey(draft)) } }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(NoteTabTestTags.ROOT).assertIsDisplayed()
        assertNull("no row for an untouched draft", runBlocking { vault.getDocument(draft.value) })

        composeRule.onNodeWithTag(NoteTabTestTags.TITLE_FIELD).performTextInput("Weekly review")
        composeRule.waitUntil(WAIT_MILLIS) { runBlocking { vault.getDocument(draft.value) } != null }
        val created = runBlocking { vault.getDocument(draft.value) }!!
        assertEquals(DocumentKind.NOTE, created.kind)
        assertEquals("Weekly review", created.title)
        assertEquals(
            "the editor was not re-created",
            listOf(KnowledgeHomeKey, NewNoteKey(draft)),
            shell.nav.stack(Destination.KNOWLEDGE),
        )
    }

    private companion object {
        const val WAIT_MILLIS = 10_000L
    }
}
