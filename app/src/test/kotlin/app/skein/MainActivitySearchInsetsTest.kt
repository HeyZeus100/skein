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
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.skein.core.model.DocumentKind
import app.skein.core.model.NewDocument
import app.skein.feature.chat.entries.ChatEntryTestTags
import app.skein.feature.shell.host.SkeinSearchTestTags
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The actual Activity search overlay, including the root inset boundary and real result rows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp", application = TestSkeinApplication::class)
class MainActivitySearchInsetsTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val app: TestSkeinApplication get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `search header clears status bar and last result clears keyboard without adding navigation twice`() {
        val matches =
            runBlocking {
                repeat(40) { number ->
                    app.repository.createDocument(NewDocument(DocumentKind.NOTE, "Inset result $number", "Body"))
                }
                app.repository.searchTitles("Inset result")
            }
        assertTrue("fixture must overflow the viewport", matches.size >= 20)
        val lastResult = hasText(matches.last().title) and hasAnyAncestor(hasTestTag(SkeinSearchTestTags.RESULTS))

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitTag(ChatEntryTestTags.LANDING)
            composeRule.onNodeWithContentDescription("Open navigation").performSemanticsAction(SemanticsActions.OnClick)
            composeRule.onNodeWithText("Search or run a command").performSemanticsAction(SemanticsActions.OnClick)
            awaitTag(SkeinSearchTestTags.FIELD)
            composeRule.onNodeWithTag(SkeinSearchTestTags.FIELD).performTextInput("Inset result")
            composeRule.waitUntil("search results to arrive", WAIT_MILLIS) {
                composeRule
                    .onAllNodes(
                        hasText(matches.first().title) and hasAnyAncestor(hasTestTag(SkeinSearchTestTags.RESULTS)),
                    ).fetchSemanticsNodes()
                    .isNotEmpty()
            }

            fun assertLastResultClears(obstruction: Int) {
                // Request the index explicitly: an existing last row can still look visible to the
                // semantics viewport while an IME overlaps it, so a visibility-only scroll is insufficient.
                composeRule.onNodeWithTag(SkeinSearchTestTags.RESULTS).performScrollToIndex(matches.lastIndex)
                composeRule.waitForIdle()
                val overlay = composeRule.onNodeWithTag(SkeinSearchTestTags.OVERLAY).fetchSemanticsNode().boundsInRoot
                val row = composeRule.onNode(lastResult).fetchSemanticsNode().boundsInRoot
                assertEquals(
                    "last row must end at the obstruction, with neither missing nor doubled bottom padding",
                    overlay.bottom - obstruction,
                    row.bottom,
                    2f,
                )
            }

            dispatchInsets(scenario, ime = 0)
            composeRule.waitForIdle()
            val close = composeRule.onNodeWithContentDescription("Close search").fetchSemanticsNode().boundsInRoot
            val field = composeRule.onNodeWithTag(SkeinSearchTestTags.FIELD).fetchSemanticsNode().boundsInRoot
            assertTrue("Close must clear the status bar: $close", close.top >= STATUS_PX)
            assertTrue("search field must clear the status bar: $field", field.top >= STATUS_PX)
            assertLastResultClears(NAVIGATION_PX)

            val headerBeforeIme = field
            dispatchInsets(scenario, ime = IME_PX)
            composeRule.waitForIdle()
            assertEquals(
                "opening the keyboard must keep the search field in place",
                headerBeforeIme,
                composeRule.onNodeWithTag(SkeinSearchTestTags.FIELD).fetchSemanticsNode().boundsInRoot,
            )
            assertLastResultClears(IME_PX)

            dispatchInsets(scenario, ime = 0)
            assertLastResultClears(NAVIGATION_PX)
        }
    }

    private fun dispatchInsets(
        scenario: ActivityScenario<MainActivity>,
        ime: Int,
    ) {
        scenario.onActivity { activity ->
            ViewCompat.dispatchApplyWindowInsets(
                activity.window.decorView,
                WindowInsetsCompat
                    .Builder()
                    .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, STATUS_PX, 0, 0))
                    .setVisible(WindowInsetsCompat.Type.statusBars(), true)
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, NAVIGATION_PX))
                    .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime))
                    .setVisible(WindowInsetsCompat.Type.ime(), ime > 0)
                    .build(),
            )
        }
    }

    private fun awaitTag(tag: String) {
        composeRule.waitUntil("test tag $tag", WAIT_MILLIS) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        const val WAIT_MILLIS = 30_000L
        const val STATUS_PX = 90
        const val NAVIGATION_PX = 60
        const val IME_PX = 300
    }
}
