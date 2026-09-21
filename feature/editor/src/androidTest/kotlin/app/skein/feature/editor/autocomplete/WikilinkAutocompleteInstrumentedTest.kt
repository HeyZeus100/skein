package app.skein.feature.editor.autocomplete

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.skein.feature.editor.EditorState
import app.skein.feature.editor.SKEIN_EDITOR_TEST_TAG
import app.skein.feature.editor.SkeinEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device Compose UI test for the `[[` wikilink autocomplete popup
 * (bd `skein-zzu`, plan `E7.I5`). Matches the shape of
 * `SkeinEditorInstrumentedTest`/`BacklinksDrawerInstrumentedTest`: runs on
 * an emulator/device, no Robolectric. bd `skein-k3b2` tracks the CI
 * emulator lane that will run this; this worktree only compiles it
 * (acceptance: "compile only, gated on skein-k3b2").
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class WikilinkAutocompleteInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val titles = listOf("Quantum notes", "Quiz answers", "Backlog quirks")

    @Test
    fun typing_double_bracket_and_a_prefix_shows_matching_titles_first() {
        val state = EditorState(initial = TextFieldValue(""))
        composeRule.setContent {
            MaterialTheme {
                SkeinEditor(
                    state = state,
                    wikilinkSuggest = { query ->
                        TitleMatcher.rank(titles, query).map { Suggestion(it) }
                    },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SKEIN_EDITOR_TEST_TAG).performTextInput("[[qu")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(wikilinkSuggestionTestTag(0)).assertIsDisplayed()
    }

    @Test
    fun selecting_a_row_inserts_the_full_wikilink_and_closes_the_popup() {
        val state = EditorState(initial = TextFieldValue(""))
        composeRule.setContent {
            MaterialTheme {
                SkeinEditor(
                    state = state,
                    wikilinkSuggest = { query ->
                        TitleMatcher.rank(titles, query).map { Suggestion(it) }
                    },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SKEIN_EDITOR_TEST_TAG).performTextInput("[[qu")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(wikilinkSuggestionTestTag(0)).performClick()
        composeRule.waitForIdle()

        assertEquals("[[Quantum notes]]", state.source)
        composeRule.onAllNodesWithTag(WIKILINK_AUTOCOMPLETE_TEST_TAG).assertCountEquals(0)
    }

    @Test
    fun selecting_the_create_row_invokes_the_create_callback_and_inserts_the_typed_text() {
        val state = EditorState(initial = TextFieldValue(""))
        var created: String? = null
        composeRule.setContent {
            MaterialTheme {
                SkeinEditor(
                    state = state,
                    wikilinkSuggest = { emptyList() },
                    onCreateWikilink = { created = it },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SKEIN_EDITOR_TEST_TAG).performTextInput("[[Nonexistent")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(wikilinkSuggestionTestTag(0)).performClick()
        composeRule.waitForIdle()

        assertEquals("[[Nonexistent]]", state.source)
        assertEquals("Nonexistent", created)
    }

    @Test
    fun hardware_down_arrow_then_enter_selects_the_second_row() {
        val state = EditorState(initial = TextFieldValue(""))
        composeRule.setContent {
            MaterialTheme {
                SkeinEditor(
                    state = state,
                    wikilinkSuggest = { query ->
                        TitleMatcher.rank(titles, query).map { Suggestion(it) }
                    },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SKEIN_EDITOR_TEST_TAG).performTextInput("[[qu")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SKEIN_EDITOR_TEST_TAG).performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.Enter)
        }
        composeRule.waitForIdle()

        assertEquals("[[Quiz answers]]", state.source)
    }

    @Test
    fun escape_closes_the_popup_without_changing_the_text() {
        val state = EditorState(initial = TextFieldValue(""))
        composeRule.setContent {
            MaterialTheme {
                SkeinEditor(
                    state = state,
                    wikilinkSuggest = { query ->
                        TitleMatcher.rank(titles, query).map { Suggestion(it) }
                    },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SKEIN_EDITOR_TEST_TAG).performTextInput("[[qu")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SKEIN_EDITOR_TEST_TAG).performKeyInput {
            pressKey(Key.Escape)
        }
        composeRule.waitForIdle()

        assertEquals("[[qu", state.source)
        composeRule.onAllNodesWithTag(WIKILINK_AUTOCOMPLETE_TEST_TAG).assertCountEquals(0)
    }

    @Test
    fun omitting_wikilinkSuggest_never_shows_a_popup() {
        val state = EditorState(initial = TextFieldValue(""))
        composeRule.setContent { MaterialTheme { SkeinEditor(state = state) } }
        composeRule.waitForIdle()

        composeRule.runOnUiThread {
            state.onValueChange(TextFieldValue(text = "[[qu", selection = TextRange(4)))
        }
        composeRule.waitForIdle()

        composeRule.onAllNodesWithTag(WIKILINK_AUTOCOMPLETE_TEST_TAG).assertCountEquals(0)
        assertTrue("existing no-popup callers keep working unchanged", state.source == "[[qu")
    }
}
