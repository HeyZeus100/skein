package app.skein.feature.editor.notetab

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.NewDocument
import app.skein.core.vault.transfer.ImportedLinkTargets
import app.skein.feature.editor.SKEIN_EDITOR_TEST_TAG
import app.skein.feature.editor.autocomplete.WIKILINK_AUTOCOMPLETE_TEST_TAG
import app.skein.feature.editor.autocomplete.wikilinkSuggestionTestTag
import app.skein.testing.InMemoryIndexStore
import app.skein.testing.InMemoryVaultRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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

    @Test
    fun `ambiguous imported link notice can be dismissed without opening a different note`() {
        assertImportedNotice(ambiguous = true)
    }

    @Test
    fun `missing imported link notice can be dismissed without creating a note`() {
        assertImportedNotice(ambiguous = false)
    }

    private fun assertImportedNotice(ambiguous: Boolean) {
        val repo = InMemoryVaultRepository()
        val index = InMemoryIndexStore()
        val target = "Imported target"
        val existing = if (ambiguous) runBlocking { repo.note(target) } else null
        val doc =
            runBlocking {
                repo.createDocument(
                    NewDocument(
                        kind = DocumentKind.NOTE,
                        title = "Imported source",
                        bodyMd = "Imported notes\n[[$target]]",
                        frontmatter =
                            buildJsonObject {
                                put(ImportedLinkTargets.UNRESOLVED, JsonArray(listOf(JsonPrimitive(target))))
                                if (ambiguous) {
                                    put(
                                        ImportedLinkTargets.AMBIGUOUS,
                                        JsonArray(listOf(JsonPrimitive(target))),
                                    )
                                }
                            },
                    ),
                )
            }
        var opens = 0
        composeRule.setContent {
            SkeinTheme {
                NoteTab(doc.id, repo, index, onOpenDocument = { _, _ -> opens++ })
            }
        }
        composeRule.waitForIdle()

        fun openLink() {
            val node = composeRule.onNodeWithTag(SKEIN_EDITOR_TEST_TAG)
            val layout = node.textLayoutResult()
            val offset =
                layout.layoutInput.text.text
                    .lastIndexOf(target)
            val line = layout.getLineForOffset(offset)
            val y = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f
            node.performTouchInput { click(Offset(1f, y)) }
            composeRule.waitForIdle()
        }
        openLink()
        composeRule.onNodeWithTag(NoteTabTestTags.LINK_NOTICE).assertIsDisplayed()
        composeRule.onNodeWithText("Link unavailable").assertIsDisplayed()
        val explanation = if (ambiguous) "more than one note" else "not resolved in the imported folder"
        composeRule.onNodeWithText(explanation, substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Dismiss").assertHeightIsAtLeast(48.dp).performClick()
        composeRule.onNodeWithTag(NoteTabTestTags.LINK_NOTICE).assertDoesNotExist()
        composeRule.onNodeWithTag(SKEIN_EDITOR_TEST_TAG).assertIsDisplayed()
        assertEquals(0, opens)
        if (existing == null) {
            assertNull(runBlocking { repo.findByTitle(target) })
        } else {
            assertEquals(existing, runBlocking { repo.findByTitle(target) })
        }
        openLink()
        composeRule.onNodeWithTag(NoteTabTestTags.LINK_NOTICE).assertIsDisplayed()
        assertEquals(0, opens)
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
