package app.skein.feature.shell.host

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.NewDocument
import app.skein.testing.FakeClock
import app.skein.testing.InMemoryVaultRepository
import app.skein.testing.SkeinLogCaptureRule
import app.skein.testing.ui.skeinComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Search behavior retained when AL-09c retires the old CommandBarState. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkeinSearchTest {
    @get:Rule
    val composeRule = skeinComposeRule()

    @get:Rule
    val logCapture = SkeinLogCaptureRule()

    @Test
    fun `title hits precede body-only hits and a document appears once`() =
        runBlocking {
            val clock = FakeClock()
            val repository = InMemoryVaultRepository(clock = clock::now)
            val body = repository.createDocument(NewDocument(DocumentKind.NOTE, "Body-only note", "quantum"))
            clock.advanceBy(1)
            val title = repository.createDocument(NewDocument(DocumentKind.NOTE, "Quantum notes", "unrelated"))
            clock.advanceBy(1)
            val both = repository.createDocument(NewDocument(DocumentKind.NOTE, "Quantum in both", "quantum"))

            assertEquals(listOf(both.id, title.id, body.id), vaultSearch(repository)("quantum").map { it.id })
        }

    @Test
    fun `blank search does not query and submitting opens the first result without logging contents`() {
        val repository = InMemoryVaultRepository()
        val document =
            runBlocking {
                repository.createDocument(NewDocument(DocumentKind.NOTE, "Secret project", "Confidential body"))
            }
        val queries = mutableListOf<String>()
        val opened = mutableListOf<Document>()
        composeRule.setContent {
            SkeinTheme {
                SkeinSearchOverlay(
                    search = { query ->
                        queries += query
                        vaultSearch(repository)(query)
                    },
                    onOpen = { opened += it },
                    onDismiss = {},
                )
            }
        }
        composeRule.waitForIdle()
        assertTrue(queries.isEmpty())
        composeRule.onNodeWithTag(SkeinSearchTestTags.FIELD).performTextInput("  Secret  ")
        composeRule.waitUntil(5_000) { queries.isNotEmpty() }
        composeRule.onNodeWithText("Secret project").assertIsDisplayed()
        composeRule.onNodeWithTag(SkeinSearchTestTags.FIELD).performImeAction()
        composeRule.runOnIdle {
            assertEquals(listOf("Secret"), queries)
            assertEquals(listOf(document.id), opened.map { it.id })
            assertTrue(logCapture.captured().none { "Secret" in it.message || "Confidential" in it.message })
        }
    }

    @Test
    fun `no matches is visible and submitting does not open anything`() {
        val opened = mutableListOf<Document>()
        composeRule.setContent {
            SkeinTheme { SkeinSearchOverlay(search = { emptyList() }, onOpen = { opened += it }, onDismiss = {}) }
        }
        composeRule.onNodeWithTag(SkeinSearchTestTags.FIELD).performTextInput("nothing matches")
        composeRule.waitUntil(5_000) {
            composeRule
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasTestTag(SkeinSearchTestTags.NO_MATCHES),
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithTag(SkeinSearchTestTags.NO_MATCHES).assertIsDisplayed()
        composeRule.onNodeWithTag(SkeinSearchTestTags.FIELD).performImeAction()
        composeRule.runOnIdle { assertTrue(opened.isEmpty()) }
    }
}
