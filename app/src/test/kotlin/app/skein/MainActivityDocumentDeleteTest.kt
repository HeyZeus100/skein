package app.skein

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.skein.core.designsystem.components.SKEIN_DESTRUCTIVE_DIALOG_CANCEL_TEST_TAG
import app.skein.core.designsystem.components.SKEIN_DESTRUCTIVE_DIALOG_CONFIRM_TEST_TAG
import app.skein.core.designsystem.components.SKEIN_DESTRUCTIVE_DIALOG_TEST_TAG
import app.skein.core.model.DocumentKind
import app.skein.core.model.NewDocument
import app.skein.feature.chat.entries.ChatEntryTestTags
import app.skein.feature.editor.entries.KnowledgeEntryTestTags
import app.skein.feature.editor.notetab.NoteTabTestTags
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp", application = TestSkeinApplication::class)
class MainActivityDocumentDeleteTest {
    @get:Rule val composeRule = createEmptyComposeRule()
    private val app: TestSkeinApplication get() = ApplicationProvider.getApplicationContext()

    private fun awaitTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `current chat menu confirms and deletes only after explicit second action`() {
        app.enableSessionChat = true
        val chat = runBlocking { app.repository.createDocument(NewDocument(DocumentKind.CHAT, "Disposable chat", "")) }
        val note = runBlocking { app.repository.createDocument(NewDocument(DocumentKind.NOTE, "Keep note", "Body")) }
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag(ChatEntryTestTags.LANDING)
            composeRule
                .onNode(
                    hasText(chat.title) and hasAnyAncestor(hasTestTag(ChatEntryTestTags.LANDING)),
                ).performClick()
            awaitTag(ChatEntryTestTags.DELETE_MENU)
            composeRule.onNodeWithTag(ChatEntryTestTags.DELETE_MENU).performClick()
            composeRule.onNodeWithText("Delete…").performClick()
            awaitTag(SKEIN_DESTRUCTIVE_DIALOG_TEST_TAG)
            composeRule.onNodeWithText("Delete \"Disposable chat\"?").assertExists()
            composeRule.onNodeWithTag(SKEIN_DESTRUCTIVE_DIALOG_CANCEL_TEST_TAG).performClick()
            assertNotNull(runBlocking { app.repository.getDocument(chat.id) })
            composeRule.onNodeWithTag(ChatEntryTestTags.DELETE_MENU).performClick()
            composeRule.onNodeWithText("Delete…").performClick()
            awaitTag(SKEIN_DESTRUCTIVE_DIALOG_TEST_TAG)
            composeRule.onNodeWithTag(SKEIN_DESTRUCTIVE_DIALOG_CONFIRM_TEST_TAG).performClick()
            awaitTag(ChatEntryTestTags.LANDING)
            assertNull(runBlocking { app.repository.getDocument(chat.id) })
            assertEquals(note, runBlocking { app.repository.getDocument(note.id) })
        }
    }

    @Test
    fun `open note menu shares the confirmation and returns to Knowledge after delete`() {
        val note =
            runBlocking { app.repository.createDocument(NewDocument(DocumentKind.NOTE, "Disposable note", "Body")) }
        val keep = runBlocking { app.repository.createDocument(NewDocument(DocumentKind.CHAT, "Keep chat", "")) }
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag(ChatEntryTestTags.LANDING)
            composeRule.onNodeWithContentDescription("Open navigation").performSemanticsAction(SemanticsActions.OnClick)
            composeRule.onNodeWithText("Knowledge").performSemanticsAction(SemanticsActions.OnClick)
            awaitTag(KnowledgeEntryTestTags.LIST)
            composeRule.onNodeWithText(note.title).performClick()
            awaitTag(NoteTabTestTags.ACTIONS_BUTTON)
            composeRule.onNodeWithTag(NoteTabTestTags.ACTIONS_BUTTON).performClick()
            composeRule.onNodeWithTag(NoteTabTestTags.DELETE_ACTION).performClick()
            awaitTag(SKEIN_DESTRUCTIVE_DIALOG_TEST_TAG)
            composeRule.onNodeWithText("This permanently removes the note from Skein.").assertExists()
            composeRule.onNodeWithTag(SKEIN_DESTRUCTIVE_DIALOG_CANCEL_TEST_TAG).performClick()
            assertNotNull(runBlocking { app.repository.getDocument(note.id) })
            composeRule.onNodeWithTag(NoteTabTestTags.ACTIONS_BUTTON).performClick()
            composeRule.onNodeWithTag(NoteTabTestTags.DELETE_ACTION).performClick()
            awaitTag(SKEIN_DESTRUCTIVE_DIALOG_TEST_TAG)
            composeRule.onNodeWithTag(SKEIN_DESTRUCTIVE_DIALOG_CONFIRM_TEST_TAG).performClick()
            awaitTag(KnowledgeEntryTestTags.LIST)
            assertNull(runBlocking { app.repository.getDocument(note.id) })
            assertEquals(keep, runBlocking { app.repository.getDocument(keep.id) })
        }
    }
}
