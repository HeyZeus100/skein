package app.skein.feature.editor.autocomplete

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure ranking logic behind the `[[` popup (bd `skein-zzu`, plan `E7.I5`):
 * "fuzzy: prefix first, then substring, <= 8 rows". Kept independent of any
 * [AutocompleteHost]/vault type so it is trivially JVM-testable.
 */
class TitleMatcherTest {
    @Test
    fun `titles whose prefix matches the query rank before substring-only matches`() {
        val candidates = listOf("Backlog quirks", "Quantum notes", "Roster", "Quiz answers")

        val ranked = TitleMatcher.rank(candidates, "qu")

        assertEquals(
            listOf("Quantum notes", "Quiz answers", "Backlog quirks"),
            ranked,
        )
    }

    @Test
    fun `matching is case-insensitive`() {
        val candidates = listOf("quantum notes")

        val ranked = TitleMatcher.rank(candidates, "QU")

        assertEquals(listOf("quantum notes"), ranked)
    }

    @Test
    fun `non-matching titles are excluded`() {
        val candidates = listOf("Quantum notes", "Unrelated")

        val ranked = TitleMatcher.rank(candidates, "qu")

        assertEquals(listOf("Quantum notes"), ranked)
    }

    @Test
    fun `results are limited to the requested count`() {
        val candidates = (1..20).map { "Quantum $it" }

        val ranked = TitleMatcher.rank(candidates, "qu", limit = 8)

        assertEquals(8, ranked.size)
    }

    @Test
    fun `default limit is 8`() {
        val candidates = (1..20).map { "Quantum $it" }

        val ranked = TitleMatcher.rank(candidates, "qu")

        assertEquals(8, ranked.size)
    }

    @Test
    fun `blank query returns candidates unranked up to the limit`() {
        val candidates = listOf("Alpha", "Beta", "Gamma")

        val ranked = TitleMatcher.rank(candidates, "")

        assertEquals(listOf("Alpha", "Beta", "Gamma"), ranked)
    }

    @Test
    fun `duplicate candidates are de-duplicated`() {
        val candidates = listOf("Quantum notes", "Quantum notes")

        val ranked = TitleMatcher.rank(candidates, "qu")

        assertEquals(listOf("Quantum notes"), ranked)
    }
}
