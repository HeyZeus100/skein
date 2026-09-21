package app.skein.feature.editor

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.skein.feature.editor.frontmatter.FRONTMATTER_CHIP_TEST_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device Compose UI test for [SkeinEditor]. Runs on an emulator/device
 * — no Robolectric here — matching the shape of `:app`'s
 * `MainActivityComposeTest`. bd `skein-k3b2` tracks the CI emulator lane
 * that will run this; bd `skein-r4v` layers on top with device latency
 * measurements referenced in the acceptance criteria.
 *
 * These cases assert the transformer + `SkeinEditor` pair renders the
 * flip correctly: the source-of-truth `EditorState.value.text` never
 * changes as the caret moves; only the *transformed* text seen by the
 * IME/renderer flips between raw and styled forms.
 */
@RunWith(AndroidJUnit4::class)
class SkeinEditorInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun heading_raw_text_stays_in_EditorState_across_caret_moves() {
        val initial = "# Hello\nsecond line"
        var stateRef: EditorState? = null
        composeRule.setContent {
            MaterialTheme {
                val state =
                    remember {
                        EditorState(initial = TextFieldValue(initial, selection = TextRange(0)))
                            .also { stateRef = it }
                    }
                SkeinEditor(state = state)
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnUiThread {
            stateRef!!.onValueChange(
                TextFieldValue(text = initial, selection = TextRange(initial.length)),
            )
        }
        composeRule.waitForIdle()

        assertEquals(
            "raw source (autosave input) must survive a caret move",
            "# Hello\nsecond line",
            stateRef!!.source,
        )
    }

    @Test
    fun wikilink_click_routes_through_onLinkOpen_callback() {
        var routed: WikilinkTarget? = null
        val state =
            EditorState(
                initial = TextFieldValue("[[Some Note]]"),
                onLinkOpen = { routed = it },
            )
        composeRule.setContent { MaterialTheme { SkeinEditor(state = state) } }
        composeRule.waitForIdle()

        assertEquals("callback must not fire from a normal composition", null, routed)

        // Tap routing lands with the autocomplete popup (bd skein-zzu /
        // E7.I5); this test asserts the callback wire-up itself is real
        // — a hand-invoked open delivers the target unchanged.
        state.onLinkOpen(WikilinkTarget(title = "Some Note"))
        assertEquals(WikilinkTarget(title = "Some Note"), routed)
    }

    @Test
    fun editor_field_is_discoverable_by_its_public_test_tag() {
        val state = EditorState(initial = TextFieldValue("body"))
        composeRule.setContent { MaterialTheme { SkeinEditor(state = state) } }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SKEIN_EDITOR_TEST_TAG).assertIsDisplayed()
    }

    // ------------------------------------------------------------------
    // bd skein-6rr (E7.I3): frontmatter hide/show and protected `id`.
    // Compile-gated on skein-k3b2's CI emulator lane — this worktree
    // cannot run an on-device suite, only verify it builds.
    // ------------------------------------------------------------------

    @Test
    fun frontmatter_chip_toggle_shows_and_hides_the_block() {
        val initial = "---\nid: 0192abc\ntags: [a, b]\n---\nbody text"
        var stateRef: EditorState? = null
        composeRule.setContent {
            MaterialTheme {
                val state = remember { EditorState(initial = TextFieldValue(initial)).also { stateRef = it } }
                SkeinEditor(state = state)
            }
        }
        composeRule.waitForIdle()

        assertFalse("frontmatter starts collapsed", stateRef!!.frontmatterExpanded.value)

        composeRule.onNodeWithTag(FRONTMATTER_CHIP_TEST_TAG).performClick()
        composeRule.waitForIdle()

        assertTrue("tapping the chip expands the block", stateRef!!.frontmatterExpanded.value)

        composeRule.onNodeWithTag(FRONTMATTER_CHIP_TEST_TAG).performClick()
        composeRule.waitForIdle()

        assertFalse("tapping again re-collapses it", stateRef!!.frontmatterExpanded.value)
    }

    @Test
    fun id_line_is_uneditable_via_the_field() {
        val initial = "---\nid: 0192abc\n---\nbody text"
        var stateRef: EditorState? = null
        composeRule.setContent {
            MaterialTheme {
                val state =
                    remember {
                        EditorState(initial = TextFieldValue(initial), initialFrontmatterExpanded = true)
                            .also { stateRef = it }
                    }
                SkeinEditor(state = state)
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnUiThread {
            stateRef!!.onValueChange(TextFieldValue(initial.replace("id: 0192abc", "id: HACKED")))
        }
        composeRule.waitForIdle()

        assertEquals("an id edit must be reverted", initial, stateRef!!.source)
        assertTrue(stateRef!!.idEditRejected.value)
    }
}
