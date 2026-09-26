package app.skein

import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.skein.core.model.DocumentKind
import app.skein.core.model.NewDocument
import app.skein.feature.editor.notetab.NoteTabTestTags
import app.skein.feature.shell.testing.ShellTestTags
import app.skein.feature.timeline.TimelineTestTags
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage H (skein-xtov.22) regressions that need the real `MainActivity`
 * wiring: a past chat reopens as a chat (H4, UX-P0-05) and the timeline's
 * New chat / New note buttons create and open (H5, UX-P0-16). Same harness
 * as [MainActivityComposeTest]: [TestSkeinApplication]'s in-memory vault,
 * which registers no model, so an opened chat shows the "No model yet"
 * guidance — the chat tab's content, never the note editor's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestSkeinApplication::class)
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

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `a past chat reopened from the timeline opens as a chat, not in the note editor`() {
        val pastChat = NewDocument(kind = DocumentKind.CHAT, title = "Chat", bodyMd = "")
        runBlocking { app.repository.createDocument(pastChat) }

        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag(ShellTestTags.SKEIN_SHELL_ROOT)
            await("the chat's timeline entry") {
                composeRule.onAllNodesWithContentDescription("Chat").fetchSemanticsNodes().isNotEmpty()
            }

            composeRule.onNodeWithContentDescription("Chat").performClick()

            awaitTag(MainActivityTestTags.CHAT_NO_MODEL_GUIDANCE)
            composeRule.onNodeWithTag(NoteTabTestTags.ROOT).assertDoesNotExist()
        }
    }

    @Test
    @Config(qualifiers = "w1000dp-h900dp")
    fun `the timeline New chat button creates a chat and opens it`() {
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag(TimelineTestTags.NEW_CHAT)

            composeRule.onNodeWithTag(TimelineTestTags.NEW_CHAT).performClick()

            awaitTag(MainActivityTestTags.CHAT_NO_MODEL_GUIDANCE)
        }
    }

    @Test
    @Config(qualifiers = "w1000dp-h900dp")
    fun `the timeline New note button creates a note and opens it`() {
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag(TimelineTestTags.NEW_NOTE)

            composeRule.onNodeWithTag(TimelineTestTags.NEW_NOTE).performClick()

            awaitTag(NoteTabTestTags.ROOT)
        }
    }

    private companion object {
        /** A failure budget, not an expected duration — see [MainActivityComposeTest]'s own. */
        const val WAIT_MILLIS = 30_000L
    }
}
