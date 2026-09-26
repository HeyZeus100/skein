package app.skein.feature.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * bd `skein-6rr` (`E7.I3`): [EditorState]'s collapse/expand flag and the
 * protected-`id` guard, driven directly (Compose-off) like
 * `EditorAutosaveTest`. Round-tripping the *saved* frontmatter JSON is
 * `NoteTabStateTest`'s job (it owns `Frontmatter.parse`/`updateFrontmatter`);
 * this file only covers what `EditorState` itself is responsible for.
 */
class EditorStateFrontmatterTest {
    private val withFrontmatter = "---\nid: 0192abc\ntags: [a, b]\n---\nbody text"

    @Test
    fun `frontmatter starts collapsed by default`() {
        val state = EditorState(initial = TextFieldValue(withFrontmatter))

        assertFalse(state.frontmatterExpanded.value)
    }

    @Test
    fun `toggling frontmatter flips the expanded flag`() {
        val state = EditorState(initial = TextFieldValue(withFrontmatter))

        state.toggleFrontmatter()
        assertTrue(state.frontmatterExpanded.value)

        state.toggleFrontmatter()
        assertFalse(state.frontmatterExpanded.value)
    }

    @Test
    fun `an initial expanded state can be requested`() {
        val state = EditorState(initial = TextFieldValue(withFrontmatter), initialFrontmatterExpanded = true)

        assertTrue(state.frontmatterExpanded.value)
    }

    @Test
    fun `editing the id line is rejected and the buffer reverts`() {
        val state = EditorState(initial = TextFieldValue(withFrontmatter))

        val attempted = TextFieldValue(withFrontmatter.replace("id: 0192abc", "id: HACKED"))
        state.onValueChange(attempted)

        assertEquals(withFrontmatter, state.value.text)
        assertTrue(state.idEditRejected.value)
    }

    @Test
    fun `editing a non-id frontmatter key is accepted`() {
        val state = EditorState(initial = TextFieldValue(withFrontmatter))

        val edited = TextFieldValue(withFrontmatter.replace("tags: [a, b]", "tags: [a, b, c]"))
        state.onValueChange(edited)

        assertEquals(edited.text, state.value.text)
        assertFalse(state.idEditRejected.value)
    }

    @Test
    fun `editing the body below the frontmatter block is unaffected by the guard`() {
        val state = EditorState(initial = TextFieldValue(withFrontmatter))

        val edited = TextFieldValue(withFrontmatter.replace("body text", "body text, edited"))
        state.onValueChange(edited)

        assertEquals(edited.text, state.value.text)
        assertFalse(state.idEditRejected.value)
    }

    @Test
    fun `a rejection is a one-shot signal that clears on the next accepted edit`() {
        val state = EditorState(initial = TextFieldValue(withFrontmatter))
        state.onValueChange(TextFieldValue(withFrontmatter.replace("id: 0192abc", "id: HACKED")))
        assertTrue(state.idEditRejected.value)

        state.onValueChange(TextFieldValue(state.value.text.replace("tags: [a, b]", "tags: [a]")))

        assertFalse(state.idEditRejected.value)
    }

    @Test
    fun `a document with no frontmatter block never trips the id guard`() {
        val state = EditorState(initial = TextFieldValue("plain body, no frontmatter at all"))

        state.onValueChange(TextFieldValue("plain body, no frontmatter at all — edited"))

        assertEquals("plain body, no frontmatter at all — edited", state.value.text)
        assertFalse(state.idEditRejected.value)
    }

    @Test
    fun `a rejected edit clamps the selection into the reverted text`() {
        val state = EditorState(initial = TextFieldValue(withFrontmatter))

        val tampered = withFrontmatter.replace("id: 0192abc", "id: HACKED-LONGER-VALUE")
        state.onValueChange(TextFieldValue(tampered, selection = TextRange(tampered.length)))

        assertTrue(state.value.selection.end <= state.value.text.length)
    }

    // ------------------------------------------------------------------
    // UX-P0-10 / K-P0-2 (KNOWLEDGE_UX_SPEC.md §6.4 hotfix): nothing the user
    // can do while the block is collapsed reaches into it.
    // ------------------------------------------------------------------

    private val bodyStart = withFrontmatter.indexOf("body text")

    @Test
    fun `the initial caret starts at the body, not in front of the collapsed block`() {
        val state = EditorState(initial = TextFieldValue(withFrontmatter))

        assertEquals(TextRange(bodyStart), state.value.selection)
    }

    @Test
    fun `a caret or selection can't enter the collapsed block`() {
        val state = EditorState(initial = TextFieldValue(withFrontmatter))

        // Up on the first line / Left at the first character both ask for raw 0.
        state.onValueChange(state.value.copy(selection = TextRange(0)))
        assertEquals(TextRange(bodyStart), state.value.selection)

        // Select all.
        state.onValueChange(state.value.copy(selection = TextRange(0, withFrontmatter.length)))
        assertEquals(TextRange(bodyStart, withFrontmatter.length), state.value.selection)
    }

    @Test
    fun `collapsing the block moves a caret that was inside it to the body start`() {
        val state = EditorState(initial = TextFieldValue(withFrontmatter), initialFrontmatterExpanded = true)
        val insideBlock = TextRange(withFrontmatter.indexOf("tags"))
        state.onValueChange(state.value.copy(selection = insideBlock))
        assertEquals("expanded: the block is editable", insideBlock, state.value.selection)

        state.toggleFrontmatter()

        assertEquals(TextRange(bodyStart), state.value.selection)
    }

    @Test
    fun `backspace at the body start can't delete the collapsed block's closing line`() {
        val state = EditorState(initial = TextFieldValue(withFrontmatter))

        val backspaced = withFrontmatter.removeRange(bodyStart - 1, bodyStart)
        state.onValueChange(TextFieldValue(backspaced, selection = TextRange(bodyStart - 1)))

        assertEquals(withFrontmatter, state.value.text)
        assertEquals(TextRange(bodyStart), state.value.selection)
    }

    @Test
    fun `deleting an empty first body line is still allowed`() {
        val blankFirstLine = withFrontmatter.replace("---\nbody text", "---\n\nbody text")
        val state = EditorState(initial = TextFieldValue(blankFirstLine))

        state.onValueChange(TextFieldValue(withFrontmatter, selection = TextRange(bodyStart)))

        assertEquals(withFrontmatter, state.value.text)
    }
}
