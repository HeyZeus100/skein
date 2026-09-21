package app.skein.feature.editor.autocomplete

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Trigger detection for the `[[` popup: given the text *before* the caret
 * (all an [AutocompleteHost] ever exposes), decide whether an autocomplete
 * session is open and, if so, what query/alias/replacement range it implies.
 * bd `skein-zzu`, plan `E7.I5`.
 */
class WikilinkTriggerTest {
    @Test
    fun `no open bracket means no trigger`() {
        val trigger = findWikilinkTrigger("plain text")

        assertNull(trigger)
    }

    @Test
    fun `an open double bracket with a partial query triggers with that query`() {
        val trigger = findWikilinkTrigger("intro [[qu")

        assertEquals(WikilinkTrigger(start = 6, query = "qu", alias = null), trigger)
    }

    @Test
    fun `an empty query right after the brackets still triggers`() {
        val trigger = findWikilinkTrigger("[[")

        assertEquals(WikilinkTrigger(start = 0, query = "", alias = null), trigger)
    }

    @Test
    fun `a closed link before the cursor does not trigger`() {
        val trigger = findWikilinkTrigger("see [[Quantum notes]] and")

        assertNull(trigger)
    }

    @Test
    fun `only the nearest unclosed bracket pair matters`() {
        val trigger = findWikilinkTrigger("[[Closed]] then [[Open")

        assertEquals(WikilinkTrigger(start = 16, query = "Open", alias = null), trigger)
    }

    @Test
    fun `a pipe splits the query from a preserved alias`() {
        val trigger = findWikilinkTrigger("[[Quantum notes|alias text")

        assertEquals(WikilinkTrigger(start = 0, query = "Quantum notes", alias = "alias text"), trigger)
    }

    @Test
    fun `a space typed immediately after the empty brackets closes the trigger`() {
        // Plan E7.I5: "Esc/space closes" -- a bare `[[ ` reads as literal
        // markup (e.g. a footnote-ish aside), not the start of a link.
        val trigger = findWikilinkTrigger("[[ answer")

        assertNull(trigger)
    }

    @Test
    fun `a space in the middle of an in-progress query keeps the trigger open`() {
        // Titles are multi-word ("Quantum notes"), so continuing to type a
        // second search word must not dismiss the popup.
        val trigger = findWikilinkTrigger("[[qu answer")

        assertEquals(WikilinkTrigger(start = 0, query = "qu answer", alias = null), trigger)
    }

    @Test
    fun `a newline after the brackets closes the trigger`() {
        val trigger = findWikilinkTrigger("[[qu\nsecond line")

        assertNull(trigger)
    }

    @Test
    fun `typing inside a fenced code block never triggers`() {
        val textBeforeCursor = "```kotlin\nval x = 1\n[[qu"

        val trigger = findWikilinkTrigger(textBeforeCursor)

        assertNull(trigger)
    }

    @Test
    fun `text before an unopened fence still triggers normally`() {
        val textBeforeCursor = "[[qu"

        val trigger = findWikilinkTrigger(textBeforeCursor)

        assertEquals(WikilinkTrigger(start = 0, query = "qu", alias = null), trigger)
    }

    @Test
    fun `a closed fence before the cursor does not suppress a later trigger`() {
        val textBeforeCursor = "```\ncode\n```\n[[qu"

        val trigger = findWikilinkTrigger(textBeforeCursor)

        assertEquals(WikilinkTrigger(start = 13, query = "qu", alias = null), trigger)
    }
}
