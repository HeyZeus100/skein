package app.skein

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.skein.core.model.DocumentKind
import app.skein.core.model.NewDocument
import app.skein.core.model.TimelineFilter
import app.skein.feature.chat.CHAT_SCREEN_TEST_TAG
import app.skein.feature.chat.entries.ChatEntryTestTags
import app.skein.feature.editor.entries.KnowledgeEntryTestTags
import app.skein.feature.editor.notetab.NoteTabTestTags
import app.skein.feature.graph.GraphTestTags
import app.skein.feature.models.MODELS_EMPTY_TEST_TAG
import app.skein.feature.shell.host.SkeinSearchTestTags
import app.skein.feature.shell.testing.ShellTestTags
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Stage H behaviors retained by the default NavDisplay shell: open by kind, visible creation, search and Back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp", application = TestSkeinApplication::class)
class ShellHotfixComposeTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val app: TestSkeinApplication
        get() = ApplicationProvider.getApplicationContext()

    private fun awaitTag(tag: String) =
        await("test tag \"$tag\"") { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun await(
        description: String,
        condition: () -> Boolean,
    ) {
        try {
            composeRule.waitUntil(
                conditionDescription = description,
                timeoutMillis = WAIT_MILLIS,
                condition = condition,
            )
        } catch (e: ComposeTimeoutException) {
            val tree = runCatching { composeRule.onRoot().printToString() }.getOrElse { "<failed: $it>" }
            throw AssertionError("Timed out waiting for $description\n${tree.take(4_000)}", e)
        }
    }

    private fun navigateTo(label: String) {
        composeRule.onNodeWithContentDescription("Open navigation").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithText(label).performSemanticsAction(SemanticsActions.OnClick)
    }

    @Test
    fun `a past chat reopened from Recent opens the chat screen`() {
        runBlocking { app.repository.createDocument(NewDocument(DocumentKind.CHAT, "Past chat", "")) }
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag(ChatEntryTestTags.LANDING)
            composeRule
                .onNode(
                    hasText("Past chat") and hasAnyAncestor(hasTestTag(ChatEntryTestTags.LANDING)),
                ).performClick()
            awaitTag(CHAT_SCREEN_TEST_TAG)
            composeRule.onNodeWithTag(NoteTabTestTags.ROOT).assertDoesNotExist()
        }
    }

    @Test
    fun `the drawer New chat action opens an unsaved landing`() {
        runBlocking { app.repository.createDocument(NewDocument(DocumentKind.NOTE, "Seeded note", "content")) }
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag(ChatEntryTestTags.LANDING)
            navigateTo("Knowledge")
            awaitTag(KnowledgeEntryTestTags.LIST)
            navigateTo(" New chat")
            awaitTag(ChatEntryTestTags.LANDING)
            val chats =
                runBlocking { app.repository.observeTimeline(TimelineFilter(kinds = setOf(DocumentKind.CHAT))).first() }
            assertTrue("New chat must not create a blank document", chats.isEmpty())
        }
    }

    @Test
    fun `the Knowledge New note action opens an editor`() {
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag(ChatEntryTestTags.LANDING)
            navigateTo("Knowledge")
            awaitTag(KnowledgeEntryTestTags.NEW_NOTE_ACTION)
            composeRule.onNodeWithTag(KnowledgeEntryTestTags.NEW_NOTE_ACTION).performClick()
            awaitTag(NoteTabTestTags.ROOT)
        }
    }

    private fun ActivityScenario<MainActivity>.pressBackAndAssertStillOpen() {
        onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
        onActivity { assertFalse("Back left the app", it.isFinishing) }
    }

    @Test
    fun `system Back from Models returns to Chat`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitTag(ChatEntryTestTags.LANDING)
            navigateTo("Models")
            awaitTag(MODELS_EMPTY_TEST_TAG)
            scenario.pressBackAndAssertStillOpen()
            awaitTag(ChatEntryTestTags.LANDING)
            composeRule.onNodeWithTag(MODELS_EMPTY_TEST_TAG).assertDoesNotExist()
        }
    }

    @Test
    fun `system Back from Graph returns to Chat`() {
        runBlocking { app.repository.createDocument(NewDocument(DocumentKind.NOTE, "Graph Me", "content")) }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitTag(ChatEntryTestTags.LANDING)
            navigateTo("Graph")
            awaitTag(GraphTestTags.CANVAS)
            scenario.pressBackAndAssertStillOpen()
            awaitTag(ChatEntryTestTags.LANDING)
            composeRule.onNodeWithTag(GraphTestTags.CANVAS).assertDoesNotExist()
        }
    }

    @Test
    fun `search remains reachable after removing the command bar and a result opens by kind`() {
        runBlocking { app.repository.createDocument(NewDocument(DocumentKind.NOTE, "Searchable note", "content")) }
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag(ShellTestTags.SKEIN_SHELL_ROOT)
            navigateTo("Search or run a command")
            awaitTag(SkeinSearchTestTags.FIELD)
            composeRule.onNodeWithTag(SkeinSearchTestTags.FIELD).performTextInput("Searchable")
            val result = hasText("Searchable note") and hasAnyAncestor(hasTestTag(SkeinSearchTestTags.OVERLAY))
            await("search result") { composeRule.onAllNodes(result).fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNode(result).performClick()
            awaitTag(NoteTabTestTags.ROOT)
            composeRule.onNodeWithTag(SkeinSearchTestTags.OVERLAY).assertDoesNotExist()
        }
    }

    private companion object {
        const val WAIT_MILLIS = 30_000L
    }
}
