package app.skein.feature.editor.notetab

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.NewDocument
import app.skein.feature.editor.SKEIN_EDITOR_TEST_TAG
import app.skein.testing.InMemoryIndexStore
import app.skein.testing.InMemoryVaultRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * UX-P0-10 / K-P0-2 (`docs/ux/KNOWLEDGE_UX_SPEC.md` §6.4 tests 1–2): typing
 * through the real [NoteTab] into a note whose frontmatter block is hidden
 * (collapsed) must land in the body and leave the frontmatter untouched.
 * Before the fix the caret sat at raw offset 0, in front of the hidden
 * `---`, so the first keystroke broke the block and saved the `id:` lines
 * into the body.
 *
 * Pinned to SDK 34 (bd memory `robolectric-sdk37-needs-java21`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoteTypingTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val repo = InMemoryVaultRepository()

    @Test
    fun firstKeystrokeLandsInBody() {
        // `/new note Test`: an empty body behind the `id` frontmatter every vault note carries.
        val doc = note(body = "")
        val flush = setNoteTab(doc)

        val editor = composeRule.onNodeWithTag(SKEIN_EDITOR_TEST_TAG)
        editor.performTouchInput { click() }
        editor.performTextInput("X")
        composeRule.waitForIdle()
        runBlocking { flush(Duration.ofSeconds(2)) }

        val saved = runBlocking { repo.getDocument(doc.id) }!!
        assertEquals("X", saved.bodyMd)
        assertEquals(doc.frontmatter, saved.frontmatter)
    }

    @Test
    fun upArrowOnTheFirstLineThenTypingLandsInBody() {
        val doc = note(body = "hello")
        val flush = setNoteTab(doc)

        val editor = composeRule.onNodeWithTag(SKEIN_EDITOR_TEST_TAG)
        editor.performTouchInput { click() }
        editor.performKeyInput { pressKey(Key.DirectionUp) }
        editor.performTextInput("X")
        composeRule.waitForIdle()
        runBlocking { flush(Duration.ofSeconds(2)) }

        val saved = runBlocking { repo.getDocument(doc.id) }!!
        assertEquals("Xhello", saved.bodyMd)
        assertEquals(doc.frontmatter, saved.frontmatter)
    }

    private fun note(body: String): Document =
        runBlocking { repo.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "Test", bodyMd = body)) }

    /** Composes [NoteTab] for [doc] and returns the flush handle it registers. */
    private fun setNoteTab(doc: Document): suspend (Duration) -> Boolean {
        var flush: (suspend (Duration) -> Boolean)? = null
        composeRule.setContent {
            MaterialTheme {
                NoteTab(
                    docId = doc.id,
                    vaultRepository = repo,
                    indexStore = InMemoryIndexStore(),
                    registerFlush = { flush = it },
                )
            }
        }
        composeRule.waitForIdle()
        return flush!!
    }
}
