// skein-4c7 (E4.I7) — content-hash LRU cache behavior for `ContextBudget`.
//
// The 20-turn simulation mirrors how `PromptAssembler` (E5.I15) actually
// drives token counting: at every turn it re-counts the *entire* running
// history to compute `estimatedTokens`, not just the newest message. With
// two new messages per turn over 20 turns that's sum(2*i, i=1..20) = 420
// total `countTokens` calls against only 40 distinct texts, so a correct
// content-hash cache hits 380/420 ~= 90.5% of the time -- the AC's "counts
// only new turns" requirement, expressed as a >=90% hit ratio.

package app.skein.core.inference

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ContextBudgetCacheTest {
    private fun conversationHistory(turns: Int): List<String> =
        buildList {
            for (turn in 1..turns) {
                add("user turn $turn")
                add("assistant turn $turn")
            }
        }

    @Test
    fun `cache hit ratio is at least 90 percent over a 20-turn conversation`() =
        runTest {
            // Arrange
            val contextBudget = ContextBudget(TokenCounter { text -> text.length })
            val history = mutableListOf<String>()

            // Act: each turn appends a new user+assistant message, then
            // re-counts the whole running history so far.
            for (turn in 1..20) {
                history += "user turn $turn"
                history += "assistant turn $turn"
                history.forEach { contextBudget.countTokens(it) }
            }
            val total = contextBudget.cacheHits + contextBudget.cacheMisses
            val ratio = contextBudget.cacheHits.toDouble() / total

            // Assert
            assertThat(ratio).isAtLeast(0.90)
        }

    @Test
    fun `cache misses count only the new turns, not re-counted history`() =
        runTest {
            // Arrange
            val contextBudget = ContextBudget(TokenCounter { text -> text.length })
            val history = mutableListOf<String>()

            // Act
            for (turn in 1..20) {
                history += "user turn $turn"
                history += "assistant turn $turn"
                history.forEach { contextBudget.countTokens(it) }
            }

            // Assert: 20 turns x 2 messages/turn = 40 distinct texts ever counted.
            assertThat(contextBudget.cacheMisses).isEqualTo(40)
        }

    @Test
    fun `identical text counted twice is a single miss followed by a hit`() =
        runTest {
            // Arrange
            var calls = 0
            val contextBudget =
                ContextBudget(
                    TokenCounter { text ->
                        calls++
                        text.length
                    },
                )

            // Act
            contextBudget.countTokens("same text")
            contextBudget.countTokens("same text")

            // Assert
            assertThat(calls).isEqualTo(1)
        }

    @Test
    fun `cache evicts the least-recently-used entry beyond its configured size`() =
        runTest {
            // Arrange: capacity 2, three distinct texts, each with a distinct length.
            val seen = mutableListOf<String>()
            val counter =
                TokenCounter { text ->
                    seen.add(text)
                    text.length
                }
            val contextBudget = ContextBudget(counter, InferenceConfig(tokenCountCacheSize = 2))

            // Act: fill with "a","b"; touching "a" makes "b" the least-recently
            // used; inserting "c" evicts "b"; re-requesting "b" must miss again.
            contextBudget.countTokens("a")
            contextBudget.countTokens("b")
            contextBudget.countTokens("a")
            contextBudget.countTokens("c")
            contextBudget.countTokens("b")

            // Assert
            assertThat(seen).isEqualTo(listOf("a", "b", "c", "b"))
        }
}
