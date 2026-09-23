// Owner scope addition, 2026-09-23 (see `ChatTurnState.kt`'s header): proves
// the turn-state seam's one required behaviour — `Thinking` precedes
// `Streaming`, and the sent user bubble's derived `SentMessageState` flips
// from `QUEUED` to `PICKED_UP` on the first answer token, never before.
package app.skein.feature.chat

import app.skein.core.model.Capability
import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.Locator
import app.skein.core.model.Model
import app.skein.core.model.ModelFormat
import app.skein.core.model.PersonaId
import app.skein.core.model.RecallSource
import app.skein.core.model.RetrievalService
import app.skein.core.model.Retrieved
import app.skein.core.model.TokenBudget
import app.skein.testing.fakeVault
import app.skein.testing.scriptedEngine
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

class ChatTurnStateTest {
    private fun textModel(): Model =
        Model(
            id = "fake-model",
            name = "Fake",
            path = "/dev/null/fake.gguf",
            sha256 = "a".repeat(64),
            format = ModelFormat.GGUF,
            capabilities = setOf(Capability.TEXT),
            sizeBytes = 1_000L,
        )

    private fun budget(): TokenBudget =
        TokenBudget(contextLength = 16_384, reserveForAnswer = 1024, maxRetrievedTokens = 3072)

    @Test
    fun `thinking precedes streaming and the user bubble flips to picked-up on the first token`() =
        runTest {
            lateinit var doc: Document
            val vault = fakeVault { doc = chat("Chat") }
            // A deliberate delay ahead of the first token, so `Thinking` has a
            // window to observe before any `Segment` arrives — separate from
            // the engine's own inter-token delay (below), which creates a
            // second window to observe `Streaming` before `Done`.
            //
            // The script's `[1]` is a *valid* citation (a non-empty
            // `retrieved` list, below) deliberately: `CitationParser`
            // (`:core:rag`) never emits a `Segment.Text` for plain
            // non-citation prose until `flush()` (`Token.Done`) — a script
            // with no citation at all would coalesce into one `Segment` only
            // at the very end, giving this test no intermediate `Streaming`
            // point to observe before `Done`. A validated `[1]` forces an
            // earlier flush the moment it resolves.
            val retrieved =
                Retrieved(
                    chunkId = 1L,
                    docId = "doc-1",
                    docTitle = "Source",
                    text = "excerpt",
                    score = 0.5,
                    sourceKind = DocumentKind.NOTE,
                    recalledBy = setOf(RecallSource.VECTOR),
                    revisionHash = "hash",
                    locator = Locator(byteStart = 0, byteEnd = 1),
                )
            val delayedRetrieval =
                object : RetrievalService {
                    override suspend fun retrieveContext(
                        query: String,
                        k: Int,
                        personaId: PersonaId?,
                    ): List<Retrieved> {
                        delay(RETRIEVAL_DELAY_MS)
                        return listOf(retrieved)
                    }
                }
            val engine =
                scriptedEngine("q" to listOf("answer ", "[1]", " more"), tokenDelay = TOKEN_DELAY_MS.milliseconds)
            engine.load(textModel()).getOrThrow()
            val pipeline =
                SendPipeline(
                    vaultRepository = vault,
                    retrievalService = delayedRetrieval,
                    promptAssembler = SimplePromptAssembler(),
                    engine = engine,
                    personaProvider = { null },
                    budgetFor = { _, _ -> budget() },
                    countTokens = { it.length / 4 },
                )
            // `backgroundScope`, not `this`: `ChatViewModel.init` launches two
            // observers (`observeMessages`, `lastOutcome`) that run for the
            // ViewModel's whole lifetime and never complete on their own —
            // exactly what `TestScope.backgroundScope` exists for. Passing
            // `this` (the main test-body scope) instead makes `runTest` wait
            // for them at the end of the test body and fail with
            // `UncompletedCoroutinesError` after its real-time deadline.
            val viewModel =
                ChatViewModel(
                    chatDocId = doc.id,
                    vaultRepository = vault,
                    sendPipeline = pipeline,
                    tabController = TabController { _, _, _ -> "tab-1" },
                    scope = backgroundScope,
                )

            viewModel.send("q")

            val beforeAnyToken = viewModel.turnState
            assertThat(beforeAnyToken is ChatTurnState.Queued || beforeAnyToken is ChatTurnState.Thinking).isTrue()
            assertThat(beforeAnyToken?.toSentMessageState()).isEqualTo(SentMessageState.QUEUED)

            // Small-step virtual-time advancement (draining ready work after
            // each step) until each state transition happens, rather than a
            // hand-computed exact-millisecond boundary.
            var steps = 0
            while (viewModel.turnState !is ChatTurnState.Streaming && steps < MAX_STEPS) {
                advanceTimeBy(STEP_MS)
                runCurrent()
                steps++
            }
            check(viewModel.turnState is ChatTurnState.Streaming) { "never reached Streaming: ${viewModel.turnState}" }

            val afterFirstToken = viewModel.turnState
            assertThat((afterFirstToken as ChatTurnState.Streaming).text).isEqualTo("answer ")
            assertThat(afterFirstToken.toSentMessageState()).isEqualTo(SentMessageState.PICKED_UP)

            steps = 0
            while (viewModel.turnState !is ChatTurnState.Done && steps < MAX_STEPS) {
                advanceTimeBy(STEP_MS)
                runCurrent()
                steps++
            }
            check(viewModel.turnState is ChatTurnState.Done) { "never reached Done: ${viewModel.turnState}" }
            assertThat(viewModel.turnState?.toSentMessageState()).isEqualTo(SentMessageState.SETTLED)
        }

    private companion object {
        const val RETRIEVAL_DELAY_MS = 50L
        const val TOKEN_DELAY_MS = 50L
        const val STEP_MS = 5L
        const val MAX_STEPS = 200
    }
}
