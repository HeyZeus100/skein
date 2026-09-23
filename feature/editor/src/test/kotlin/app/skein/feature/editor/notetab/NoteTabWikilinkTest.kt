package app.skein.feature.editor.notetab

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import app.skein.feature.editor.SKEIN_EDITOR_TEST_TAG
import app.skein.feature.editor.autocomplete.WIKILINK_AUTOCOMPLETE_TEST_TAG
import app.skein.feature.editor.autocomplete.wikilinkSuggestionTestTag
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import us.aherrera.skein.core.model.Document
import us.aherrera.skein.core.model.DocumentKind
import us.aherrera.skein.core.model.NewDocument
import us.aherrera.skein.testing.InMemoryIndexStore
import us.aherrera.skein.testing.InMemoryVaultRepository

/**
 * bd `skein-pnqo`: proves [NoteTab] actually wires `wikilinkSuggest` /
 * `onCreateWikilink` into [app.skein.feature.editor.SkeinEditor] (device
 * report: typing `[[` showed no popup at all because these two arguments
 * were never passed) and that a tap on a rendered wikilink resolves through
 * [NoteTabState.onOpenDocument] against a real (fake) `VaultRepository` —
 * the piece `SkeinEditorInteractionTest` can't cover since it only exercises
 * the bare `EditorState.onLinkOpen` callback, not vault resolution.
 *
 * Pinned to SDK 34 (bd memory `robolectric-sdk37-needs-java21`), same shape
 * as this package's `ShareMenuTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoteTabWikilinkTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `typing an open bracket and a partial title shows the popup with a matching row and a Create row`() {
        val repo = InMemoryVaultRepository()
        val index = InMemoryIndexStore()
        runBlocking { repo.note("Smoke") }
        val doc = runBlocking { repo.note("Main note") }

        composeRule.setContent {
            MaterialTheme { NoteTab(docId = doc.id, vaultRepository = repo, indexStore = index) }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SKEIN_EDITOR_TEST_TAG).performTextInput("[[Sm")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(WIKILINK_AUTOCOMPLETE_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(wikilinkSuggestionTestTag(0)).assertIsDisplayed()
        composeRule.onNodeWithTag(wikilinkSuggestionTestTag(1)).assertIsDisplayed()
    }

    @Test
    fun `accepting the Create row persists a new note and inserts the link text`() {
        val repo = InMemoryVaultRepository()
        val index = InMemoryIndexStore()
        val doc = runBlocking { repo.note("Main note") }

        composeRule.setContent {
            MaterialTheme { NoteTab(docId = doc.id, vaultRepository = repo, indexStore = index) }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SKEIN_EDITOR_TEST_TAG).performTextInput("[[Fresh Idea")
        composeRule.waitForIdle()

        // No existing title matches "Fresh Idea" -- the lone row is "Create".
        composeRule.onNodeWithTag(wikilinkSuggestionTestTag(0)).performClick()
        composeRule.waitForIdle()

        val created = runBlocking { repo.findByTitle("Fresh Idea") }
        assertNotNull("accepting Create must persist the new note", created)

        val fieldText =
            composeRule
                .onNodeWithTag(SKEIN_EDITOR_TEST_TAG)
                .textLayoutResult()
                .layoutInput.text.text
        assertTrue("the popup must still insert [[Fresh Idea]] into the buffer", fieldText.contains("Fresh Idea"))
    }

    @Test
    fun `tapping a rendered wikilink resolves the existing note and invokes onOpenDocument`() {
        val repo = InMemoryVaultRepository()
        val index = InMemoryIndexStore()
        val target = runBlocking { repo.note("Target") }
        // The link is the *entire* second line -- Robolectric's text
        // measurement shadow has no real per-glyph advances (see
        // SkeinEditorInteractionTest's kdoc), so within a line every x maps
        // somewhere on that line; making the whole line the link sidesteps
        // needing an accurate x, only the line's y needs to be right.
        val doc = runBlocking { repo.note("Main note", body = "intro\n[[Target]]") }
        var openedId: String? = null
        var openedTitle: String? = null

        composeRule.setContent {
            MaterialTheme {
                NoteTab(
                    docId = doc.id,
                    vaultRepository = repo,
                    indexStore = index,
                    onOpenDocument = { id, title ->
                        openedId = id
                        openedTitle = title
                    },
                )
            }
        }
        composeRule.waitForIdle()

        val node = composeRule.onNodeWithTag(SKEIN_EDITOR_TEST_TAG)
        val layout = node.textLayoutResult()
        val lineIndex = 1
        val y = (layout.getLineTop(lineIndex) + layout.getLineBottom(lineIndex)) / 2f

        node.performTouchInput { click(Offset(1f, y)) }
        composeRule.waitForIdle()

        assertEquals(target.id, openedId)
        assertEquals("Target", openedTitle)
    }

    private suspend fun InMemoryVaultRepository.note(
        title: String,
        body: String = "",
    ): Document = createDocument(NewDocument(kind = DocumentKind.NOTE, title = title, bodyMd = body))

    /** `TextLayoutResult` behind [node] via the `GetTextLayoutResult` semantics action — the real rendered layout. */
    private fun SemanticsNodeInteraction.textLayoutResult(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.first()
    }
}
