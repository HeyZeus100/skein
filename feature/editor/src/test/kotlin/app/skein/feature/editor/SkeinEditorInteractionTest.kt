package app.skein.feature.editor

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric, real-Compose-runtime coverage for bd `skein-pnqo`'s two
 * device-verified causes in [SkeinEditor]:
 *
 *  1. "no visible live preview" — [bold_markers_hide_on_a_non_cursor_line]
 *     drives the *actual* rendered [TextLayoutResult] (via the
 *     `GetTextLayoutResult` semantics action, not the bare [transform]
 *     function `LivePreviewTransformerTest` already covers) to prove the
 *     transformed text really does lose its `**` markers on a non-caret
 *     line end-to-end through `SkeinEditor` → `SecureBasicTextField` →
 *     layout. It passes, which means the hardware report was a genuine
 *     rendering bug in cause (2) below (wikilink taps did nothing, so the
 *     preview looked identical to source) rather than a transformer defect
 *     — see bd `skein-pnqo`'s notes for this bead's finding.
 *  2. wikilink tap routing was entirely unwired (zero call sites into
 *     [EditorState.onLinkOpen] before this bead). These tests exercise the
 *     real `pointerInput` added to [SkeinEditor] and prove both halves of
 *     "without stealing the text field's own taps": a link tap opens it,
 *     a plain-text tap still moves the caret.
 *
 * Pinned to SDK 34 (bd memory `robolectric-sdk37-needs-java21`), matching
 * `:feature:editor`'s other Robolectric Compose UI tests (`ShareMenuTest`).
 *
 * ## Hardware arrow-key caret movement (bd `skein-hacu`)
 *
 * The tests below inject real `KEYCODE_DPAD_*`/`KEYCODE_MOVE_*` events (via
 * [performKeyInput]/[pressKey] — the same API `WikilinkAutocompleteInstrumentedTest`
 * uses on-device) into the *real* rendered [SkeinEditor], covering both
 * render branches (`wikilinkSuggest == null` and the autocomplete-enabled
 * branch with its popup not visible) against a document with hidden markers
 * on inactive lines (`# Heading`, a `**bold**` run, a `[[wikilink]]`).
 * Assertions are all in ORIGINAL text coordinates ([EditorState.cursor]) —
 * never pixel/x positions — because Robolectric's text-measurement shadow
 * gives degenerate per-glyph X advances (see this class's kdoc above) but
 * real line heights, which is all vertical caret movement needs.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkeinEditorInteractionTest {
    // "# Heading" (hidden "# " marker off-caret) \n
    // "plain **bold** text" (hidden "**" markers off-caret) \n
    // "tail [[Some Link]] end" (hidden "[[""]]" markers off-caret)
    private val hiddenMarkerDoc = "# Heading\nplain **bold** text\ntail [[Some Link]] end"
    private val line0Start = 0
    private val line0End = hiddenMarkerDoc.indexOf('\n')
    private val line1Start = line0End + 1
    private val line1End = hiddenMarkerDoc.indexOf('\n', line1Start)
    private val line2Start = line1End + 1
    private val line2End = hiddenMarkerDoc.length

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun bold_markers_hide_on_a_non_cursor_line_in_the_real_rendered_layout() {
        val source = "**bold**\nsecond line"
        val state =
            EditorState(initial = TextFieldValue(source, selection = TextRange(source.indexOf("second"))))
        composeRule.setContent { MaterialTheme { SkeinEditor(state = state) } }
        composeRule.waitForIdle()

        val rendered =
            composeRule
                .onNodeWithTag(SKEIN_EDITOR_TEST_TAG)
                .textLayoutResult()
                .layoutInput.text.text

        assertFalse(
            "line 1 (not the caret's line) must render without ** markers — real layout was: $rendered",
            rendered.contains("**"),
        )
        assertTrue("the bold text itself must still be present", rendered.contains("bold"))
        assertTrue("line 2 is the caret's line and stays raw source", rendered.contains("second line"))
    }

    @Test
    fun tapping_a_rendered_wikilink_invokes_onLinkOpen_and_does_not_move_the_cursor() {
        // The wikilink is the *entire* second line -- Robolectric's text
        // measurement shadow doesn't provide real per-glyph advances (see
        // this test class's kdoc), so within a line every x maps somewhere
        // on that line; making the whole line the link sidesteps needing
        // an accurate x, and only the line's y (which Robolectric does get
        // right, from real line-height metrics) needs to be correct.
        val source = "intro\n[[Some Note]]"
        var routed: WikilinkTarget? = null
        val state =
            EditorState(
                initial = TextFieldValue(source, selection = TextRange(0)),
                onLinkOpen = { routed = it },
            )
        composeRule.setContent { MaterialTheme { SkeinEditor(state = state) } }
        composeRule.waitForIdle()

        val node = composeRule.onNodeWithTag(SKEIN_EDITOR_TEST_TAG)
        val layout = node.textLayoutResult()
        val lineIndex = 1
        val y = (layout.getLineTop(lineIndex) + layout.getLineBottom(lineIndex)) / 2f

        node.performTouchInput { click(Offset(1f, y)) }
        composeRule.waitForIdle()

        assertEquals(
            "tapping the rendered link must route through EditorState.onLinkOpen",
            WikilinkTarget(title = "Some Note"),
            routed,
        )
        assertEquals(
            "a link tap must not also move the caret into the raw source",
            0,
            state.cursor,
        )
    }

    @Test
    fun tapping_plain_text_moves_the_cursor_and_does_not_invoke_onLinkOpen() {
        // Second line is plain text with no wikilink anywhere in the
        // document -- whatever offset the tap resolves to (see the
        // previous test's kdoc note on Robolectric's text measurement),
        // it cannot land inside a TAG_WIKILINK annotation because none
        // exists, so this isolates "does the field's own cursor-placement
        // gesture still run" from any x-precision concern.
        val source = "intro\nplain text line, no link here"
        var routed: WikilinkTarget? = null
        val state =
            EditorState(
                initial = TextFieldValue(source, selection = TextRange(0)),
                onLinkOpen = { routed = it },
            )
        composeRule.setContent { MaterialTheme { SkeinEditor(state = state) } }
        composeRule.waitForIdle()

        val node = composeRule.onNodeWithTag(SKEIN_EDITOR_TEST_TAG)
        val layout = node.textLayoutResult()
        val lineIndex = 1
        val y = (layout.getLineTop(lineIndex) + layout.getLineBottom(lineIndex)) / 2f

        node.performTouchInput { click(Offset(1f, y)) }
        composeRule.waitForIdle()

        assertNull("a plain-text tap must never invoke onLinkOpen", routed)
        assertTrue(
            "a plain-text tap must still place the caret (moved off its initial position 0)",
            state.cursor != 0,
        )
    }

    @Test
    fun right_arrow_moves_the_caret_one_character_within_the_active_line() {
        val start = hiddenMarkerDoc.indexOf("plain")
        val state = EditorState(initial = TextFieldValue(hiddenMarkerDoc, selection = TextRange(start)))
        composeRule.setContent { MaterialTheme { SkeinEditor(state = state) } }
        composeRule.waitForIdle()

        val node = composeRule.onNodeWithTag(SKEIN_EDITOR_TEST_TAG)
        node.requestFocus()
        composeRule.waitForIdle()
        node.performKeyInput {
            pressKey(Key.DirectionRight)
            pressKey(Key.DirectionRight)
            pressKey(Key.DirectionRight)
        }
        composeRule.waitForIdle()

        assertEquals("three Right presses on the active (raw) line advance the caret by 3", start + 3, state.cursor)
    }

    @Test
    fun left_arrow_moves_the_caret_one_character_within_the_active_line() {
        val start = hiddenMarkerDoc.indexOf("bold") + 2
        val state = EditorState(initial = TextFieldValue(hiddenMarkerDoc, selection = TextRange(start)))
        composeRule.setContent { MaterialTheme { SkeinEditor(state = state) } }
        composeRule.waitForIdle()

        val node = composeRule.onNodeWithTag(SKEIN_EDITOR_TEST_TAG)
        node.requestFocus()
        composeRule.waitForIdle()
        node.performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.waitForIdle()

        assertEquals("one Left press on the active (raw) line moves back by 1", start - 1, state.cursor)
    }

    @Test
    fun right_arrow_from_end_of_a_line_with_hidden_markers_lands_at_the_start_of_the_next_line() {
        // Caret starts at the end of line 1 ("...bold** text"); line 2
        // ("tail [[Some Link]] end") is NOT the caret's line, so at the
        // moment the key is pressed it renders with its `[[`/`]]` hidden.
        // The resulting original-coordinate offset must land exactly at
        // line 2's raw start, not inside the hidden bracket run.
        val state = EditorState(initial = TextFieldValue(hiddenMarkerDoc, selection = TextRange(line1End)))
        composeRule.setContent { MaterialTheme { SkeinEditor(state = state) } }
        composeRule.waitForIdle()

        val node = composeRule.onNodeWithTag(SKEIN_EDITOR_TEST_TAG)
        node.requestFocus()
        composeRule.waitForIdle()
        node.performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.waitForIdle()

        assertEquals(
            "Right across the newline must land at the very start of line 2's raw text",
            line2Start,
            state.cursor,
        )
    }

    @Test
    fun left_arrow_from_the_start_of_a_wikilink_line_lands_at_the_end_of_the_previous_hidden_marker_line() {
        // Symmetric case: caret starts at line 2's raw start; line 1
        // ("plain **bold** text") is inactive at key-press time, so its
        // `**` markers are hidden. Left must still land exactly at line 1's
        // raw end (immediately before the newline), not inside the hidden
        // trailing `**`.
        val state = EditorState(initial = TextFieldValue(hiddenMarkerDoc, selection = TextRange(line2Start)))
        composeRule.setContent { MaterialTheme { SkeinEditor(state = state) } }
        composeRule.waitForIdle()

        val node = composeRule.onNodeWithTag(SKEIN_EDITOR_TEST_TAG)
        node.requestFocus()
        composeRule.waitForIdle()
        node.performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.waitForIdle()

        assertEquals(
            "Left across the newline must land at the very end of line 1's raw text",
            line1End,
            state.cursor,
        )
    }

    @Test
    fun down_arrow_from_a_heading_line_lands_somewhere_within_the_next_line() {
        val start = hiddenMarkerDoc.indexOf("Heading")
        val state = EditorState(initial = TextFieldValue(hiddenMarkerDoc, selection = TextRange(start)))
        composeRule.setContent { MaterialTheme { SkeinEditor(state = state) } }
        composeRule.waitForIdle()

        val node = composeRule.onNodeWithTag(SKEIN_EDITOR_TEST_TAG)
        node.requestFocus()
        composeRule.waitForIdle()
        node.performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.waitForIdle()

        assertTrue(
            "Down from line 0 must move the caret onto line 1 (raw offsets $line1Start..$line1End), was ${state.cursor}",
            state.cursor in line1Start..line1End,
        )
    }

    @Test
    fun up_arrow_from_the_wikilink_line_lands_somewhere_within_the_previous_line() {
        val start = hiddenMarkerDoc.indexOf("tail") + 2
        val state = EditorState(initial = TextFieldValue(hiddenMarkerDoc, selection = TextRange(start)))
        composeRule.setContent { MaterialTheme { SkeinEditor(state = state) } }
        composeRule.waitForIdle()

        val node = composeRule.onNodeWithTag(SKEIN_EDITOR_TEST_TAG)
        node.requestFocus()
        composeRule.waitForIdle()
        node.performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.waitForIdle()

        assertTrue(
            "Up from line 2 must move the caret onto line 1 (raw offsets $line1Start..$line1End), was ${state.cursor}",
            state.cursor in line1Start..line1End,
        )
    }

    @Test
    fun home_and_end_move_the_caret_to_the_active_lines_raw_boundaries() {
        val mid = hiddenMarkerDoc.indexOf("bold")
        val state = EditorState(initial = TextFieldValue(hiddenMarkerDoc, selection = TextRange(mid)))
        composeRule.setContent { MaterialTheme { SkeinEditor(state = state) } }
        composeRule.waitForIdle()

        val node = composeRule.onNodeWithTag(SKEIN_EDITOR_TEST_TAG)
        node.requestFocus()
        composeRule.waitForIdle()

        node.performKeyInput { pressKey(Key.MoveHome) }
        composeRule.waitForIdle()
        assertEquals("Home must move the caret to line 1's raw start", line1Start, state.cursor)

        node.performKeyInput { pressKey(Key.MoveEnd) }
        composeRule.waitForIdle()
        assertEquals("End must move the caret to line 1's raw end", line1End, state.cursor)
    }

    @Test
    fun arrow_keys_still_move_the_caret_when_the_wikilink_autocomplete_popup_is_not_visible() {
        // bd skein-hacu suspect (b): NoteTab always passes wikilinkSuggest
        // (bd skein-pnqo), so the device path always composes
        // wikilinkAutocompleteKeyEvents. That modifier must return false for
        // arrow keys whenever the popup isn't showing (no `[[` typed here),
        // letting the field's own hardware-key caret movement run exactly
        // as in the wikilinkSuggest == null branch above.
        val start = hiddenMarkerDoc.indexOf("plain")
        val state = EditorState(initial = TextFieldValue(hiddenMarkerDoc, selection = TextRange(start)))
        composeRule.setContent {
            MaterialTheme { SkeinEditor(state = state, wikilinkSuggest = { emptyList() }) }
        }
        composeRule.waitForIdle()

        val node = composeRule.onNodeWithTag(SKEIN_EDITOR_TEST_TAG)
        node.requestFocus()
        composeRule.waitForIdle()
        node.performKeyInput {
            pressKey(Key.DirectionRight)
            pressKey(Key.DirectionRight)
        }
        composeRule.waitForIdle()

        assertEquals(
            "arrow keys must reach the field even when wikilinkSuggest is non-null but its popup is closed",
            start + 2,
            state.cursor,
        )
    }

    @Test
    fun down_then_up_returns_to_a_position_on_the_original_line_through_hidden_marker_lines() {
        // Round-trip check across the whole three-line fixture: start on
        // line 0 (heading, hidden "# " when inactive), Down onto line 1
        // (hidden "**" when inactive), Down again onto line 2 (hidden
        // "[[""]]" when inactive), then Up, Up should return to line 0.
        val start = hiddenMarkerDoc.indexOf("Heading")
        val state = EditorState(initial = TextFieldValue(hiddenMarkerDoc, selection = TextRange(start)))
        composeRule.setContent { MaterialTheme { SkeinEditor(state = state) } }
        composeRule.waitForIdle()

        val node = composeRule.onNodeWithTag(SKEIN_EDITOR_TEST_TAG)
        node.requestFocus()
        composeRule.waitForIdle()

        node.performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.waitForIdle()
        assertTrue("first Down must land on line 1", state.cursor in line1Start..line1End)

        node.performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.waitForIdle()
        assertTrue("second Down must land on line 2", state.cursor in line2Start..line2End)

        node.performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.waitForIdle()
        assertTrue("Up from line 2 must land back on line 1", state.cursor in line1Start..line1End)

        node.performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.waitForIdle()
        assertTrue("Up from line 1 must land back on line 0", state.cursor in line0Start..line0End)
    }

    /** `TextLayoutResult` behind [node] via the `GetTextLayoutResult` semantics action — the real rendered layout, not a re-derived one. */
    private fun SemanticsNodeInteraction.textLayoutResult(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.first()
    }
}
