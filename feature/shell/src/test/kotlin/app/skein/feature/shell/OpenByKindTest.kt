package app.skein.feature.shell

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.window.core.layout.WindowSizeClass
import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.NewDocument
import app.skein.feature.shell.layout.FoldPosture
import app.skein.testing.InMemoryVaultRepository
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UX-P0-05 (Stage H4, skein-xtov.22): every open builder in [SkeinApp] used
 * to hard-code a NOTE tab, so a past chat opened as an editable transcript
 * with no composer. Each path now opens the document as its own kind.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenByKindTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val vault = InMemoryVaultRepository()
    private val chat: Document = create(DocumentKind.CHAT, "Trip plan")
    private val note: Document = create(DocumentKind.NOTE, "Packing list")

    private fun create(
        kind: DocumentKind,
        title: String,
    ): Document = runBlocking { vault.createDocument(NewDocument(kind = kind, title = title, bodyMd = "body")) }

    private fun showShell() {
        composeRule.setContent {
            SkeinApp(
                vaultRepository = vault,
                windowSizeClass = WindowSizeClass(400f, 800f),
                posture = FoldPosture.Unknown,
                timelinePane = { _, onEntryOpen, _ ->
                    Column {
                        Text("OPEN_CHAT", Modifier.clickable { onEntryOpen(chat.id, chat.title) })
                        Text("OPEN_NOTE", Modifier.clickable { onEntryOpen(note.id, note.title) })
                    }
                },
                overlay = { openPreview, _ ->
                    Text("GRAPH_NODE_CHAT", Modifier.clickable { openPreview(chat.id, chat.title) })
                },
                chatTabContent = { tab, _ -> Text("CHAT_SCREEN_${tab.docId}") },
                noteTabContent = { tab, _, onOpenDocument, _ ->
                    Text(
                        "NOTE_EDITOR_${tab.docId}",
                        Modifier.clickable { onOpenDocument(chat.id, chat.title) },
                    )
                },
            )
        }
    }

    private fun awaitText(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `a chat opened from the timeline opens as a chat`() {
        showShell()

        composeRule.onNodeWithText("OPEN_CHAT").performClick()

        awaitText("CHAT_SCREEN_${chat.id}")
        composeRule.onNodeWithText("NOTE_EDITOR_${chat.id}").assertDoesNotExist()
    }

    @Test
    fun `a note opened from the timeline still opens as a note`() {
        showShell()

        composeRule.onNodeWithText("OPEN_NOTE").performClick()

        awaitText("NOTE_EDITOR_${note.id}")
    }

    @Test
    fun `a chat opened from a graph node opens as a chat`() {
        showShell()

        composeRule.onNodeWithText("GRAPH_NODE_CHAT").performClick()

        awaitText("CHAT_SCREEN_${chat.id}")
    }

    @Test
    fun `a chat opened from a note link opens as a chat`() {
        showShell()
        composeRule.onNodeWithText("OPEN_NOTE").performClick()
        awaitText("NOTE_EDITOR_${note.id}")

        composeRule.onNodeWithText("NOTE_EDITOR_${note.id}").performClick()

        awaitText("CHAT_SCREEN_${chat.id}")
    }

    @Test
    fun `a chat opened from search opens as a chat`() {
        showShell()

        composeRule.onNode(hasSetTextAction()).performTextInput("Trip")
        awaitText("Trip plan")
        composeRule.onNodeWithText("Trip plan", substring = true).performClick()

        awaitText("CHAT_SCREEN_${chat.id}")
    }
}
