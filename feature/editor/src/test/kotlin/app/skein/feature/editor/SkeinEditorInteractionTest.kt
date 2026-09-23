package app.skein.feature.editor

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
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
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkeinEditorInteractionTest {
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

    /** `TextLayoutResult` behind [node] via the `GetTextLayoutResult` semantics action — the real rendered layout, not a re-derived one. */
    private fun SemanticsNodeInteraction.textLayoutResult(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.first()
    }
}
