package app.skein.feature.editor.frontmatter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * bd `skein-6rr` (`E7.I3`) acceptance: "attempting to edit the `id` line
 * leaves text unchanged". Pure-function tests for the guard
 * [app.skein.feature.editor.EditorState.onValueChange] delegates to.
 */
class ProtectedIdGuardTest {
    private val original = "---\nid: 0192abc\ntags: [a, b]\n---\nbody text"

    @Test
    fun `an edit to the id line is reverted and reported as rejected`() {
        val tampered = original.replace("id: 0192abc", "id: HACKED")

        val result = ProtectedIdGuard.guard(previous = original, next = tampered)

        assertEquals(original, result.text)
        assertTrue(result.rejected)
    }

    @Test
    fun `an edit to a non-id key is accepted unchanged`() {
        val edited = original.replace("tags: [a, b]", "tags: [a, b, c]")

        val result = ProtectedIdGuard.guard(previous = original, next = edited)

        assertEquals(edited, result.text)
        assertFalse(result.rejected)
    }

    @Test
    fun `identical text is never rejected`() {
        val result = ProtectedIdGuard.guard(previous = original, next = original)

        assertEquals(original, result.text)
        assertFalse(result.rejected)
    }

    @Test
    fun `a document with no frontmatter block is never guarded`() {
        val result = ProtectedIdGuard.guard(previous = "plain body", next = "different body")

        assertEquals("different body", result.text)
        assertFalse(result.rejected)
    }

    @Test
    fun `a wholesale buffer replacement that drops the frontmatter block entirely is not reverted`() {
        // Deliberately narrow scope (see the class kdoc): this guard only
        // protects an in-place edit to the id line, not every possible
        // mutation of a buffer that once had frontmatter. NoteTabState's
        // save path is the actual backstop against a lost id ever reaching
        // the vault.
        val result = ProtectedIdGuard.guard(previous = original, next = "entirely new text")

        assertEquals("entirely new text", result.text)
        assertFalse(result.rejected)
    }

    @Test
    fun `only the id line itself is reverted, other edits in the same change survive`() {
        val tampered =
            original
                .replace("id: 0192abc", "id: HACKED")
                .replace("body text", "body text edited")

        val result = ProtectedIdGuard.guard(previous = original, next = tampered)

        assertTrue(result.rejected)
        assertEquals("---\nid: 0192abc\ntags: [a, b]\n---\nbody text edited", result.text)
    }

    @Test
    fun `a custom id key is respected`() {
        val previous = "---\nuid: 1\n---\nbody"
        val next = "---\nuid: 2\n---\nbody"

        val result = ProtectedIdGuard.guard(previous = previous, next = next, idKey = "uid")

        assertEquals(previous, result.text)
        assertTrue(result.rejected)
    }
}
