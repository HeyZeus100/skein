// skein-4c7 (E4.I7) — pinned arithmetic for `ContextBudget.computeBudget`.
//
// Coordinator decision, 2026-09-21 (skein-4c7): the AC originally pinned
// "history budget 11 976"; that was a typo. Working the documented formula
// (`contextLength - reserveForAnswer - systemTokens - safetyMargin(128)`,
// then `maxRetrievedTokens = min(3072, 40 %)` of the remainder) with this
// AC's own inputs gives 16384 - 1024 - 200 - 128 = 15032, retrieved =
// min(3072, 40 % x 15032) = 3072, history = 15032 - 3072 = 11 960 — the
// value pinned below (see plan.md E4.I7 and bd note history for the
// corrected AC text).

package app.skein.core.inference

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ContextBudgetTest {
    private val systemPrompt = "s".repeat(200)
    private val counter = TokenCounter { text -> if (text == systemPrompt) 200 else text.length }

    @Test
    fun `maxRetrievedTokens is capped at 3072 for the pinned inputs`() =
        runTest {
            // Arrange
            val contextBudget = ContextBudget(counter, InferenceConfig(contextLengthCap = 16_384))

            // Act
            val budget = contextBudget.computeBudget(reserveForAnswer = 1_024, systemPrompt = systemPrompt)

            // Assert
            assertThat(budget.maxRetrievedTokens).isEqualTo(3_072)
        }

    @Test
    fun `history budget derived from contextLength minus reserve minus system minus retrieved is 11960`() =
        runTest {
            // Arrange
            val contextBudget = ContextBudget(counter, InferenceConfig(contextLengthCap = 16_384))

            // Act
            val budget = contextBudget.computeBudget(reserveForAnswer = 1_024, systemPrompt = systemPrompt)
            val promptBudget = budget.contextLength - budget.reserveForAnswer
            val historyBudget = promptBudget - 200 - budget.maxRetrievedTokens

            // Assert
            assertThat(historyBudget).isEqualTo(11_960)
        }

    @Test
    fun `reserveForAnswer is passed through unchanged`() =
        runTest {
            // Arrange
            val contextBudget = ContextBudget(counter, InferenceConfig(contextLengthCap = 16_384))

            // Act
            val budget = contextBudget.computeBudget(reserveForAnswer = 1_024, systemPrompt = systemPrompt)

            // Assert
            assertThat(budget.reserveForAnswer).isEqualTo(1_024)
        }

    @Test
    fun `contextLength folds the safety margin in`() =
        runTest {
            // Arrange
            val contextBudget =
                ContextBudget(counter, InferenceConfig(contextLengthCap = 16_384, safetyMargin = 128))

            // Act
            val budget = contextBudget.computeBudget(reserveForAnswer = 1_024, systemPrompt = systemPrompt)

            // Assert
            assertThat(budget.contextLength).isEqualTo(16_384 - 128)
        }
}
