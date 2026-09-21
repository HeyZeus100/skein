package app.skein.feature.editor.autocomplete

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives [WikilinkAutocompleteState] against a bare-bones fake
 * [AutocompleteHost] — deliberately NOT `EditorState` — to prove the state
 * holder is host-agnostic (bd `skein-zzu`: "shared with chat"). Covers
 * suggestion fetch/ranking-limit wiring, keyboard navigation, and the
 * insert/replace math for both the "select a title" and "Create '<text>'"
 * paths.
 */
class WikilinkAutocompleteStateTest {
    /** Minimal host: a single mutable text buffer with the caret always at its end. */
    private class FakeHost(
        var text: String = "",
    ) : AutocompleteHost {
        val replacements = mutableListOf<Triple<Int, Int, String>>()

        override val textBeforeCursor: String get() = text

        override fun replaceRange(
            start: Int,
            end: Int,
            with: String,
        ) {
            replacements += Triple(start, end, with)
            text = text.substring(0, start) + with + text.substring(end)
        }
    }

    @Test
    fun `typing an open bracket with no query yet shows nothing until a query arrives`() =
        runTest {
            val host = FakeHost("[[")
            val state =
                WikilinkAutocompleteState(
                    host,
                    suggest = { listOf(Suggestion("Quantum notes")) },
                    scope = backgroundScope,
                )

            state.onTextChanged()
            runCurrent()

            assertTrue(state.isVisible)
            assertEquals(listOf(Suggestion("Quantum notes")), state.suggestions)
        }

    @Test
    fun `suggestions from the callback are shown with a trailing Create row`() =
        runTest {
            val host = FakeHost("[[qu")
            val state =
                WikilinkAutocompleteState(
                    host,
                    suggest = { query ->
                        TitleMatcher.rank(listOf("Quantum notes", "Quiz"), query).map { Suggestion(it) }
                    },
                    scope = backgroundScope,
                )

            state.onTextChanged()
            runCurrent()

            assertEquals(
                listOf(Suggestion("Quantum notes"), Suggestion("Quiz"), Suggestion("qu", isCreate = true)),
                state.suggestions,
            )
        }

    @Test
    fun `no open bracket means the popup is not visible`() =
        runTest {
            val host = FakeHost("plain text")
            val state =
                WikilinkAutocompleteState(
                    host,
                    suggest = { listOf(Suggestion("Quantum notes")) },
                    scope = backgroundScope,
                )

            state.onTextChanged()
            runCurrent()

            assertFalse(state.isVisible)
            assertTrue(state.suggestions.isEmpty())
        }

    @Test
    fun `moveDown advances the selection and wraps past the last row`() =
        runTest {
            // Blank query: no trailing "Create" row, so the fake suggest's
            // two rows are the whole list -- keeps the wrap-around math simple.
            val host = FakeHost("[[")
            val state =
                WikilinkAutocompleteState(
                    host,
                    suggest = { listOf(Suggestion("A"), Suggestion("B")) },
                    scope = backgroundScope,
                )
            state.onTextChanged()
            runCurrent()

            assertEquals(0, state.selectedIndex)
            state.moveDown()
            assertEquals(1, state.selectedIndex)
            state.moveDown()
            assertEquals(0, state.selectedIndex)
        }

    @Test
    fun `moveUp wraps to the last row from the first`() =
        runTest {
            val host = FakeHost("[[")
            val state =
                WikilinkAutocompleteState(
                    host,
                    suggest = { listOf(Suggestion("A"), Suggestion("B")) },
                    scope = backgroundScope,
                )
            state.onTextChanged()
            runCurrent()

            state.moveUp()

            assertEquals(1, state.selectedIndex)
        }

    @Test
    fun `confirmSelected replaces the open bracket and query with the full link and closes`() =
        runTest {
            val host = FakeHost("intro [[qu")
            val state =
                WikilinkAutocompleteState(
                    host,
                    suggest = { listOf(Suggestion("Quantum notes")) },
                    scope = backgroundScope,
                )
            state.onTextChanged()
            runCurrent()

            state.confirmSelected()

            assertEquals("intro [[Quantum notes]]", host.text)
            assertEquals(listOf(Triple(6, 10, "[[Quantum notes]]")), host.replacements)
            assertFalse(state.isVisible)
        }

    @Test
    fun `confirming preserves a typed alias in the inserted link`() =
        runTest {
            val host = FakeHost("[[Quantum notes|My alias")
            val state =
                WikilinkAutocompleteState(
                    host,
                    suggest = { listOf(Suggestion("Quantum notes")) },
                    scope = backgroundScope,
                )
            state.onTextChanged()
            runCurrent()

            state.confirmSelected()

            assertEquals("[[Quantum notes|My alias]]", host.text)
        }

    @Test
    fun `selecting the Create row invokes onCreate with the typed text and inserts it as a link`() =
        runTest {
            val host = FakeHost("[[Nonexistent")
            var created: String? = null
            val state =
                WikilinkAutocompleteState(
                    host,
                    suggest = { emptyList() },
                    scope = backgroundScope,
                    onCreate = { created = it },
                )
            state.onTextChanged()
            runCurrent()

            state.confirmSelected()
            runCurrent()

            assertEquals("[[Nonexistent]]", host.text)
            assertEquals("Nonexistent", created)
        }

    @Test
    fun `dismiss hides the popup and clears suggestions`() =
        runTest {
            val host = FakeHost("[[qu")
            val state =
                WikilinkAutocompleteState(
                    host,
                    suggest = { listOf(Suggestion("Quantum notes")) },
                    scope = backgroundScope,
                )
            state.onTextChanged()
            runCurrent()

            state.dismiss()

            assertFalse(state.isVisible)
            assertTrue(state.suggestions.isEmpty())
        }

    @Test
    fun `confirmSelected on a closed popup is a no-op`() =
        runTest {
            val host = FakeHost("plain text")
            val state =
                WikilinkAutocompleteState(
                    host,
                    suggest = { listOf(Suggestion("Quantum notes")) },
                    scope = backgroundScope,
                )

            state.confirmSelected()

            assertEquals("plain text", host.text)
            assertTrue(host.replacements.isEmpty())
        }

    @Test
    fun `suggestions are capped at eight rows including the Create row`() =
        runTest {
            val host = FakeHost("[[qu")
            val many = (1..20).map { Suggestion("Quantum $it") }
            val state = WikilinkAutocompleteState(host, suggest = { many }, scope = backgroundScope)

            state.onTextChanged()
            runCurrent()

            assertEquals(8, state.suggestions.size)
            assertTrue(state.suggestions.last().isCreate)
        }

    @Test
    fun `changing only the alias does not re-invoke suggest`() =
        runTest {
            val host = FakeHost("[[qu")
            var calls = 0
            val state =
                WikilinkAutocompleteState(
                    host,
                    suggest = {
                        calls++
                        listOf(Suggestion("Quantum notes"))
                    },
                    scope = backgroundScope,
                )
            state.onTextChanged()
            runCurrent()
            assertEquals(1, calls)

            host.text = "[[qu|al"
            state.onTextChanged()
            runCurrent()

            assertEquals(1, calls)
        }

    @Test
    fun `a host that is not EditorState works identically -- the state holder is host-agnostic`() =
        runTest {
            val host = FakeHost("[[qu")
            val state =
                WikilinkAutocompleteState(
                    host,
                    suggest = { listOf(Suggestion("Quantum notes")) },
                    scope = backgroundScope,
                )

            state.onTextChanged()
            runCurrent()
            state.confirmSelected()

            assertEquals("[[Quantum notes]]", host.text)
        }
}
