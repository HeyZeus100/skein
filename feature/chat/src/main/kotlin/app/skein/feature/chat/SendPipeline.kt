// skein-6as (E6.I8). The chat send flow, extracted to one non-Compose class
// per `docs/design/NORTH_STAR_REVIEW.md` §3.3 (SEAM NOW): "retrieve ->
// budget -> assemble -> stream -> parse -> persist", constructor-injected,
// so `skein-6sd`'s `InlineAiRunner` and `skein-2cd`'s sheet can call it with
// a different prompt/persona instead of reimplementing the chain. The
// Composable (`ChatViewModel`) only collects [send]'s `Flow<Segment>` and
// renders.
//
// Every collaborator is typed as a `core/model` interface or a plain Kotlin
// function type — spec seam §3.4 / the bead's hard rule: this file (and this
// module) must never import `:core:inference` or `:core:ipc`, and no
// signature here may name `LlamaCppEngine` or `IInferenceService`.
// `ContextBudget` lives in `:core:inference`, out of reach for that reason,
// so its two methods are taken as injected function types ([countTokens],
// [budgetFor]) that the app wires in `skein-whg8` — see the class doc below
// for exactly what a real `ContextBudget` plugs in as.
//
// ## The context-full branch (bead note / POCKETPAL_RECON.md PP-66/PP-67)
//
// The bead's dispatch note describes this as driven by a typed
// `InferenceException` subclass for `CONTEXT_FULL`. That subclass does not
// exist in the locked `core/model` contract this bead builds on
// (`Inference.kt`'s `InferenceException` hierarchy has no `ContextFull`
// case), and `inference-service/.../InferenceService.kt` (skein-3aw,
// already landed) instead surfaces context exhaustion as a normal stream
// completion — `Token.Done(reason = StopReason.LENGTH)` — never an
// exception, never a message string. `StopReason.LENGTH` is itself the
// typed, locked signal PP-67's test wants ("driven by a typed value, never
// a message string"), so this class keys the context-full UX branch off
// it. Recorded as a deviation in `bd note skein-6as` per the working rules.
package app.skein.feature.chat

import app.skein.core.model.AssembledPrompt
import app.skein.core.model.DocId
import app.skein.core.model.InferenceEngine
import app.skein.core.model.Message
import app.skein.core.model.NewMessage
import app.skein.core.model.Persona
import app.skein.core.model.PromptAssembler
import app.skein.core.model.RetrievalService
import app.skein.core.model.Retrieved
import app.skein.core.model.Role
import app.skein.core.model.SamplingParams
import app.skein.core.model.StopReason
import app.skein.core.model.Token
import app.skein.core.model.TokenBudget
import app.skein.core.model.VaultRepository
import app.skein.core.rag.chat.CitationParser
import app.skein.core.rag.chat.CitationRecords
import app.skein.core.rag.chat.Segment
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * How many [Retrieved] items a turn asks for — design spec §7.3's send flow
 * ("`RetrievalService.retrieveContext(query, 8, persona)`"), pinned as a
 * literal by the spec, not a tuning knob this class exposes.
 */
private const val RETRIEVAL_K = 8

/**
 * Suffix appended to a cancelled turn's persisted `contentMd` so a reload
 * can tell an interrupted turn from a normal one without a dedicated
 * `messages` column (`NewMessage` has none — see [NewMessage.contentMd]).
 * Kept out of band from the live streaming bubble (only applied at persist
 * time): the UI drives its own "interrupted" badge from [TurnOutcome], not
 * by scanning rendered text for this string. HTML-comment-shaped so it
 * renders as nothing extra if a caller ever forgets to strip it.
 */
internal const val INTERRUPTED_MARKER = "\n\n<!-- skein:interrupted stopReason=CANCELLED -->"

/** Strips [INTERRUPTED_MARKER] back off, for the reload path. */
internal fun stripInterruptedMarker(contentMd: String): String = contentMd.removeSuffix(INTERRUPTED_MARKER)

/**
 * One finished (or aborted, or failed) turn's metadata — everything the UI
 * needs that isn't itself a [Segment]: the [Retrieved] offer for the
 * context panel (spec §8.4, "the last turn"), whether the turn was
 * interrupted, and the persisted assistant [Message] (null when the turn
 * produced no content at all — POCKETPAL_RECON.md PP-64's "an empty turn
 * leaves no ghost row", realized here as "never call `appendMessage`",
 * since the locked `VaultRepository` contract has no delete-message method
 * to un-persist one after the fact).
 */
public data class TurnOutcome(
    val chatDocId: DocId,
    val userQuery: String,
    val stopReason: StopReason,
    val interrupted: Boolean,
    val retrieved: List<Retrieved>,
    val assembled: AssembledPrompt,
    val assistantMessage: Message?,
)

/**
 * The chat send flow (design spec §7.3 / §8.4, `docs/design/NORTH_STAR_REVIEW.md`
 * §3.3): persist USER -> retrieve -> budget -> assemble -> stream -> parse
 * citations -> persist ASSISTANT, as one non-Compose, constructor-injected
 * class every caller (`ChatViewModel`, and later `InlineAiRunner`/the
 * command-palette sheet) shares instead of reimplementing.
 *
 * ## B-3 (POCKETPAL_RECON.md §8.1, landed inside this bead)
 *
 * - **Bounded cancel latency (PP-61).** [send]'s token loop only ever does
 *   cheap, synchronous work per [Token.Text] (append to a buffer, run
 *   [CitationParser.push]); it never awaits the slow, rate-limited part
 *   (handing [Segment]s to the collector) from that loop. Coalesced
 *   flushing runs on its own coroutine (a 30 ms ticker) so a slow UI
 *   collector applying backpressure never delays the next iteration of the
 *   loop that is — on every iteration — available to observe the engine
 *   having stopped. [cancel] itself is a single non-suspending call to
 *   [InferenceEngine.cancel] (see `FakeInferenceEngine.cancel`'s `trySend`),
 *   independent of the flow's own coroutine.
 * - **App-side coalescing at ~30 ms (PP-63).** [coalesceInterval] batches
 *   [Segment]s emitted to the collector rather than emitting one per token.
 * - **Interrupted-turn contract (PP-64).** A turn whose stream ends with
 *   `StopReason.CANCELLED` and has non-blank text is persisted with
 *   [INTERRUPTED_MARKER] appended (see [TurnOutcome.interrupted]); a turn
 *   with no content at all is never persisted (no ghost row). A turn that
 *   fails with an [app.skein.core.model.InferenceException] mid-stream
 *   persists nothing either (design spec's "rollback empty turn" branch) —
 *   the exception propagates out of [send] for the caller's error banner.
 * - **Context-full as a typed signal (PP-66/PP-67).** See the file header:
 *   `StopReason.LENGTH`, never a message string.
 *
 * @param personaProvider the persona to retrieve/assemble/persist under.
 *   Injected as a function (not a `PersonaService` reference) because the
 *   "current persona" is a `ui_prefs` concern (`E6.I10`) this module has no
 *   contract for.
 * @param budgetFor mirrors `core.inference.ContextBudget.computeBudget`'s
 *   signature exactly (`suspend fun computeBudget(reserveForAnswer: Int,
 *   systemPrompt: String): TokenBudget`) so the app can wire a method
 *   reference directly: `budgetFor = contextBudget::computeBudget`.
 * @param countTokens mirrors `PromptAssembler.assemble`'s own
 *   `countTokens: (String) -> Int` parameter — synchronous by that locked
 *   contract. The real `ContextBudget.countTokens` is `suspend`; the app
 *   must bridge that gap (e.g. a pre-warmed cache, or a bounded blocking
 *   call) when it wires this — see the hand-back note to `skein-whg8`.
 * @param samplingParams supplies the params for both the budget's
 *   `reserveForAnswer` (`SamplingParams.maxTokens`) and the actual
 *   `engine.stream` call, evaluated once per [send] call so the caller can
 *   change persona-level sampling overrides between turns (`E6.I10`).
 */
public class SendPipeline(
    private val vaultRepository: VaultRepository,
    private val retrievalService: RetrievalService,
    private val promptAssembler: PromptAssembler,
    private val engine: InferenceEngine,
    private val personaProvider: suspend () -> Persona?,
    private val budgetFor: suspend (reserveForAnswer: Int, systemPrompt: String) -> TokenBudget,
    private val countTokens: (String) -> Int,
    private val samplingParams: () -> SamplingParams = { SamplingParams() },
    private val coalesceInterval: Duration = COALESCE_INTERVAL_DEFAULT,
) {
    private val _lastOutcome = MutableStateFlow<TurnOutcome?>(null)

    /** The most recently finished turn's metadata — the context panel's "last turn" (spec §8.4). */
    public val lastOutcome: StateFlow<TurnOutcome?> = _lastOutcome.asStateFlow()

    /**
     * Forwards to [InferenceEngine.cancel]. Deliberately does not touch the
     * [Flow] [send] returns — cancelling only stops generation; the
     * in-flight [send] call keeps running so it can flush, parse and
     * persist the partial turn (see the class doc's B-3 note).
     */
    public suspend fun cancel() {
        engine.cancel()
    }

    /**
     * Runs one full turn for [chatDocId]/[text] and streams [Segment]s as
     * they can be emitted with certainty (see [CitationParser]), coalesced
     * at [coalesceInterval]. Completes normally once the turn is fully
     * persisted (or determined to need no persistence); an
     * [app.skein.core.model.InferenceException] thrown while streaming
     * propagates out uncaught (design spec: "errors close the flow with an
     * `InferenceException`") after persisting nothing beyond the USER turn.
     */
    public fun send(
        chatDocId: DocId,
        text: String,
    ): Flow<Segment> =
        channelFlow {
            val priorHistory = vaultRepository.listMessages(chatDocId)
            vaultRepository.appendMessage(chatDocId, NewMessage(role = Role.USER, contentMd = text))

            val persona = personaProvider()
            val retrieved = retrievalService.retrieveContext(text, RETRIEVAL_K, persona?.id)
            val params = samplingParams()
            val budget = budgetFor(params.maxTokens, persona?.systemPrompt ?: "")
            val assembled = promptAssembler.assemble(persona, priorHistory, retrieved, text, budget, countTokens)

            val parser = CitationParser(assembled.citations)
            val rawText = StringBuilder()
            val allSegments = mutableListOf<Segment>()
            val pending = mutableListOf<Segment>()
            val pendingLock = Mutex()
            var doneReason: StopReason? = null

            suspend fun drainPending() {
                val batch =
                    pendingLock.withLock {
                        if (pending.isEmpty()) return@withLock null
                        pending.toList().also { pending.clear() }
                    } ?: return
                for (segment in batch) send(segment)
            }

            val ticker =
                launch {
                    while (isActive) {
                        delay(coalesceInterval)
                        drainPending()
                    }
                }
            try {
                engine.stream(assembled.prompt, params).collect { token ->
                    when (token) {
                        is Token.Text -> {
                            rawText.append(token.text)
                            val segments = parser.push(token.text)
                            if (segments.isNotEmpty()) {
                                allSegments += segments
                                pendingLock.withLock { pending += segments }
                            }
                        }
                        is Token.Done -> doneReason = token.reason
                    }
                }
            } finally {
                ticker.cancel()
            }

            val trailing = parser.flush()
            if (trailing.isNotEmpty()) {
                allSegments += trailing
                pendingLock.withLock { pending += trailing }
            }
            drainPending()

            val reason = doneReason ?: StopReason.EOS
            val interrupted = reason == StopReason.CANCELLED
            val finalText = rawText.toString()

            val assistantMessage =
                if (finalText.isBlank()) {
                    // PP-64: an empty turn leaves no ghost row — never persisted.
                    null
                } else {
                    val toPersist = if (interrupted) finalText + INTERRUPTED_MARKER else finalText
                    vaultRepository.appendMessage(
                        chatDocId,
                        NewMessage(
                            role = Role.ASSISTANT,
                            contentMd = toPersist,
                            // NORTH_STAR_REVIEW.md §3.6: `citations`, never `retrievedChunks`.
                            citations = CitationRecords.fromStream(assembled, allSegments),
                        ),
                    )
                }

            _lastOutcome.value =
                TurnOutcome(
                    chatDocId = chatDocId,
                    userQuery = text,
                    stopReason = reason,
                    interrupted = interrupted,
                    retrieved = retrieved,
                    assembled = assembled,
                    assistantMessage = assistantMessage,
                )
        }.buffer(Channel.UNLIMITED)

    private companion object {
        val COALESCE_INTERVAL_DEFAULT: Duration = 30.milliseconds
    }
}
