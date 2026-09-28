package app.skein.core.rag.prompt

import app.skein.core.model.AnswerScope
import app.skein.core.model.DocumentKind
import app.skein.core.model.InferenceException
import app.skein.core.model.Message
import app.skein.core.model.Prompt
import app.skein.core.model.PromptMeasurement
import app.skein.core.model.RecallSource
import app.skein.core.model.Retrieved
import app.skein.core.model.Role
import app.skein.core.model.SamplingParams
import app.skein.core.model.TokenBudget
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ExactPromptAssemblerTest {
    private val assembler = PromptAssemblerImpl()
    private val params = SamplingParams(maxTokens = 20)
    private val budget = TokenBudget(100_000, 20, 50_000)
    private val query = "Café 日本語 🧶"
    private val hash = "ab".repeat(32)

    private fun exactSize(prompt: Prompt): Int =
        1 + prompt.messages.sumOf { it.content.codePointCount(0, it.content.length) + 7 } + 5

    private fun floor(): Int =
        exactSize(assembler.assemble(null, emptyList(), emptyList(), query, budget, { 0 }).prompt)

    private fun source(id: Long) =
        Retrieved(id, "doc-$id", "Title", "quoted evidence $id", 1.0, DocumentKind.NOTE, setOf(RecallSource.LEXICAL))

    private fun history(pairs: Int): List<Message> =
        (0 until pairs * 2).map {
            Message(
                "m$it",
                "chat",
                if (it % 2 ==
                    0
                ) {
                    Role.USER
                } else {
                    Role.ASSISTANT
                },
                "message $it",
                null,
                emptyList(),
                it.toLong(),
            )
        }

    @Test
    fun `formatted overhead can force history removal even when content estimate is zero`() =
        runTest {
            val history = history(3)
            val seen = mutableListOf<Prompt>()
            val fitter =
                ExactPromptAssembler(assembler) { prompt, _ ->
                    seen += prompt
                    PromptMeasurement(exactSize(prompt), floor() + 20 + 40, hash)
                }
            val result =
                fitter.assemble(
                    null,
                    history,
                    emptyList(),
                    query,
                    budget,
                    { 0 },
                    AnswerScope.KNOWLEDGE,
                    params,
                )
            assertThat(result.estimatedTokens).isEqualTo(0)
            assertThat(result.droppedHistoryTurns).isEqualTo(4)
            assertThat(
                result.prompt.messages.drop(1).dropLast(1).map {
                    it.content
                },
            ).containsExactly("message 4", "message 5").inOrder()
            assertThat(result.formattedTokens!! + params.maxTokens).isAtMost(result.contextLength!!)
            assertThat(result.prompt.expectedModelSha256).isEqualTo(hash)
            assertThat(seen.drop(1).all { it.expectedModelSha256 == hash }).isTrue()
        }

    @Test
    fun `answer reservation accepts equality and refuses one token overflow and Int overflow`() =
        runTest {
            val needed = floor() + params.maxTokens
            val fitting =
                ExactPromptAssembler(assembler) { prompt, _ -> PromptMeasurement(exactSize(prompt), needed, hash) }
            assertThat(
                fitting
                    .assemble(null, emptyList(), emptyList(), query, budget, {
                        0
                    }, AnswerScope.KNOWLEDGE, params)
                    .formattedTokens,
            ).isEqualTo(floor())
            val tooSmall =
                ExactPromptAssembler(assembler) { prompt, _ -> PromptMeasurement(exactSize(prompt), needed - 1, hash) }
            assertThat(
                runCatching {
                    tooSmall.assemble(null, emptyList(), emptyList(), query, budget, {
                        0
                    }, AnswerScope.KNOWLEDGE, params)
                }.exceptionOrNull(),
            ).isInstanceOf(InferenceException.ContextFull::class.java)
            assertThat(
                runCatching {
                    fitting.assemble(null, emptyList(), emptyList(), query, budget, {
                        0
                    }, AnswerScope.KNOWLEDGE, params.copy(maxTokens = Int.MAX_VALUE))
                }.exceptionOrNull(),
            ).isInstanceOf(InferenceException.ContextFull::class.java)
        }

    @Test
    fun `trims history before evidence and reports citations only for surviving sources`() =
        runTest {
            val sources = listOf(source(1), source(2))
            val oneSource = assembler.assemble(null, emptyList(), sources.take(1), query, budget, { 0 })
            val capacity = exactSize(oneSource.prompt) + params.maxTokens
            val fitter =
                ExactPromptAssembler(assembler) { prompt, _ -> PromptMeasurement(exactSize(prompt), capacity, hash) }
            val result =
                fitter.assemble(
                    null,
                    history(40),
                    sources,
                    query,
                    budget,
                    { 0 },
                    AnswerScope.KNOWLEDGE,
                    params,
                )
            assertThat(result.droppedHistoryTurns).isEqualTo(80)
            assertThat(result.droppedRetrievedItems).isEqualTo(1)
            assertThat(result.citations).containsExactly(1, sources.first())
            assertThat(
                result.prompt.messages
                    .last()
                    .content,
            ).doesNotContain("quoted evidence 2")
        }

    @Test
    fun `all evidence may be omitted while mandatory query stays intact`() =
        runTest {
            val fitter =
                ExactPromptAssembler(
                    assembler,
                ) { prompt, _ -> PromptMeasurement(exactSize(prompt), floor() + 20, hash) }
            val result =
                fitter.assemble(
                    null,
                    history(2),
                    listOf(source(1)),
                    query,
                    budget,
                    { 0 },
                    AnswerScope.KNOWLEDGE,
                    params,
                )
            assertThat(result.citations).isEmpty()
            assertThat(result.droppedRetrievedItems).isEqualTo(1)
            assertThat(
                result.prompt.messages
                    .last()
                    .content,
            ).isEqualTo("User: $query")
        }

    @Test
    fun `measurement failures never fall back to a content estimate`() =
        runTest {
            val fitter = ExactPromptAssembler(assembler) { _, _ -> throw InferenceException.ServiceDied() }
            assertThat(
                runCatching {
                    fitter.assemble(null, emptyList(), emptyList(), query, budget, { 0 }, AnswerScope.GENERAL, params)
                }.exceptionOrNull(),
            ).isInstanceOf(InferenceException.ServiceDied::class.java)
        }

    @Test
    fun `model swap between mandatory and full prompt measurements refuses`() =
        runTest {
            var calls = 0
            val fitter =
                ExactPromptAssembler(assembler) { prompt, _ ->
                    PromptMeasurement(
                        exactSize(prompt),
                        20_000,
                        if (calls++ ==
                            0
                        ) {
                            hash
                        } else {
                            "cd".repeat(32)
                        },
                    )
                }
            assertThat(
                runCatching {
                    fitter.assemble(null, history(1), emptyList(), query, budget, { 0 }, AnswerScope.GENERAL, params)
                }.exceptionOrNull(),
            ).isInstanceOf(InferenceException.ModelChanged::class.java)
        }

    @Test
    fun `transport oversized optional history drops full exchanges then measures survivors`() =
        runTest {
            val fitter =
                ExactPromptAssembler(assembler) { prompt, _ ->
                    if (prompt.messages.size > 4) throw InferenceException.TransactionTooLarge()
                    PromptMeasurement(exactSize(prompt), 20_000, hash)
                }
            val result =
                fitter.assemble(
                    null,
                    history(4),
                    emptyList(),
                    query,
                    budget,
                    { 0 },
                    AnswerScope.GENERAL,
                    params,
                )
            assertThat(result.droppedHistoryTurns).isEqualTo(6)
            assertThat(
                result.prompt.messages.map {
                    it.role
                },
            ).containsExactly(Role.SYSTEM, Role.USER, Role.ASSISTANT, Role.USER).inOrder()
        }
}
