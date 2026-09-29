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
// Production chat uses ChatTurnController: USER admission and immutable
// preferences precede the session queue, execution is scoped to that USER,
// and the shared TurnFinalizer acknowledges one assistant commit. The legacy
// send facade remains for direct callers. Exact formatted budget failures
// are typed ContextFull before streaming; LENGTH means the answer limit.
package app.skein.feature.chat

import app.skein.core.model.AnswerPolicy
import app.skein.core.model.AnswerScope
import app.skein.core.model.AssembledPrompt
import app.skein.core.model.DocId
import app.skein.core.model.InferenceEngine
import app.skein.core.model.InferenceException
import app.skein.core.model.Message
import app.skein.core.model.ModelId
import app.skein.core.model.NewMessage
import app.skein.core.model.Persona
import app.skein.core.model.PersonaId
import app.skein.core.model.Prompt
import app.skein.core.model.PromptAssembler
import app.skein.core.model.PromptMeasurement
import app.skein.core.model.RetrievalService
import app.skein.core.model.Retrieved
import app.skein.core.model.Role
import app.skein.core.model.SamplingParams
import app.skein.core.model.StopReason
import app.skein.core.model.Token
import app.skein.core.model.TokenBudget
import app.skein.core.model.VaultRepository
import app.skein.core.rag.chat.CitationParser
import app.skein.core.rag.chat.Segment
import app.skein.core.rag.prompt.ExactPromptAssembler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
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
internal const val NO_KNOWLEDGE_EVIDENCE =
    "I couldn't find enough evidence in Knowledge to answer that. " +
        "Add a relevant note or turn Knowledge off to ask from general knowledge."

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
    val answerScope: AnswerScope = AnswerScope.KNOWLEDGE,
    val generationSkipped: Boolean = false,
    val persona: Persona? = null,
    val modelId: ModelId? = null,
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
 *   having stopped. [cancel] delegates to [InferenceEngine.cancel]. The
 *   session controller schedules it without blocking HIGH and caps LOW's
 *   engine wait at 150 ms, independently of the persistence transaction.
 * - **App-side coalescing at ~30 ms (PP-63).** [coalesceInterval] batches
 *   [Segment]s emitted to the collector rather than emitting one per token.
 * - **Interrupted-turn contract (PP-64).** A turn whose stream ends with
 *   `StopReason.CANCELLED` and has non-blank text is persisted with
 *   [INTERRUPTED_MARKER] appended (see [TurnOutcome.interrupted]); a turn
 *   with no content at all is never persisted (no ghost row). A turn that
 *   fails with an [app.skein.core.model.InferenceException] mid-stream
 *   persists nothing either (design spec's "rollback empty turn" branch) —
 *   the exception propagates out of [send] for the caller's error banner.
 * - **Context-full as a typed signal (PP-66/PP-67).** Exact fitting throws
 *   `InferenceException.ContextFull`; `StopReason.LENGTH` remains the normal
 *   answer limit. Neither is inferred from exception text.
 *
 * @param personaProvider resolves the default Space for legacy unassigned chats.
 * @param personaById resolves an explicitly owned chat's Space. Missing owners fail before writing a turn.
 * @param prepareModel prepares the model chosen for that Space snapshot before retrieval and token counting.
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
    /**
     * Runs before the prompt is assembled, so that [countTokens] finds a
     * loaded model. The real engine's token counter needs the bound
     * `:inference` service, but the model was only loaded lazily by
     * `engine.stream`, which runs AFTER assembly — so on the Fold every
     * first send died in [countTokens] with `ModelNotLoaded` before anything
     * bound (skein-gg11.22). Production uses [prepareModel]; this fallback is retained for test and legacy callers.
     */
    private val warmUp: suspend () -> Unit = {},
    private val personaById: (suspend (PersonaId) -> Persona?)? = null,
    /** Captures and prepares the model for this immutable Space snapshot. */
    private val prepareModel: (suspend (Persona?) -> ModelId?)? = null,
    /** Production exact measurement. Null is retained for pure test/legacy assemblers only. */
    private val measurePrompt: (suspend (Prompt, SamplingParams) -> PromptMeasurement)? = null,
    /** Snapshot the selected model without loading it while another chat is active. */
    private val selectModel: (suspend (Persona?) -> TurnModelSelection)? = null,
) {
    private val turnGate = Mutex()

    @Volatile private var activeChatId: DocId? = null

    @Volatile private var preparationCancelled = false
    private val _lastOutcome = MutableStateFlow<TurnOutcome?>(null)

    /** The most recently finished turn's metadata — the context panel's "last turn" (spec §8.4). */
    public val lastOutcome: StateFlow<TurnOutcome?> = _lastOutcome.asStateFlow()

    /**
     * Forwards to [InferenceEngine.cancel]. Deliberately does not touch the
     * [Flow] [send] returns — cancelling only stops generation; the
     * in-flight [send] call keeps running so it can flush, parse and
     * persist the partial turn (see the class doc's B-3 note).
     */
    public suspend fun cancel(chatDocId: DocId? = null) {
        if (chatDocId == null || activeChatId == chatDocId) {
            preparationCancelled = true
            engine.cancel()
        }
    }

    /** Resolves the immutable Space/model snapshot before the durable USER write. Joins the caller's transaction. */
    public suspend fun admit(
        chatDocId: DocId,
        text: String,
    ): PreparedChatTurn {
        var admitted: PreparedChatTurn? = null
        vaultRepository.transaction {
            val config = capture(chatDocId)
            val user = vaultRepository.appendMessage(chatDocId, NewMessage(role = Role.USER, contentMd = text))
            admitted = PreparedChatTurn(user, config.persona, config.scope, config.params, config.model)
        }
        return requireNotNull(admitted)
    }

    /** Re-executes a durable user row, without appending a duplicate USER after failure/unlock. */
    public suspend fun resume(
        chatDocId: DocId,
        userMessageId: String,
    ): PreparedChatTurn {
        val user =
            vaultRepository.listMessages(chatDocId).lastOrNull()?.takeIf {
                it.id == userMessageId &&
                    it.role == Role.USER
            }
                ?: throw IllegalStateException("The unanswered message is unavailable")
        val config = capture(chatDocId)
        return PreparedChatTurn(user, config.persona, config.scope, config.params, config.model)
    }

    private class AdmissionConfig(
        val persona: Persona?,
        val scope: AnswerScope,
        val params: SamplingParams,
        val model: TurnModelSelection?,
    )

    private suspend fun capture(chatDocId: DocId): AdmissionConfig {
        val chat = requireNotNull(vaultRepository.getDocument(chatDocId)) { "Chat no longer exists" }
        val ownerId = chat.personaId
        val persona =
            if (ownerId == null) {
                personaProvider()
            } else {
                requireNotNull(
                    personaById?.invoke(ownerId) ?: personaProvider()?.takeIf { it.id == ownerId },
                ) { "The chat's Space is unavailable" }
            }
        return AdmissionConfig(
            persona,
            if (ChatKnowledge.enabled(chat)) AnswerScope.KNOWLEDGE else AnswerScope.GENERAL,
            samplingParams(),
            selectModel?.invoke(persona),
        )
    }

    /** Legacy one-turn facade; the session controller uses [runPrepared] after queue admission. */
    public fun send(
        chatDocId: DocId,
        text: String,
    ): Flow<Segment> = execute({ admit(chatDocId, text) }, {}, {}, persist = true)

    /** Runs one already committed USER. Collection is owned by the session, never a composable. */
    public fun runPrepared(
        turn: PreparedChatTurn,
        onContext: (TurnPromptContext) -> Unit,
        onDone: (StopReason) -> Unit,
    ): Flow<Segment> = execute({ turn }, onContext, onDone, persist = false)

    internal fun finalizer(): TurnFinalizer = TurnFinalizer(vaultRepository)

    internal fun publishOutcome(
        snapshot: TurnAnswerSnapshot,
        message: Message?,
    ) {
        _lastOutcome.value = snapshot.outcome(message)
    }

    public fun clearSessionState() {
        _lastOutcome.value = null
    }

    private fun execute(
        admission: suspend () -> PreparedChatTurn,
        onContext: (TurnPromptContext) -> Unit,
        onDone: (StopReason) -> Unit,
        persist: Boolean,
    ): Flow<Segment> =
        channelFlow {
            if (!turnGate.tryLock()) throw InferenceException.Busy()
            preparationCancelled = false
            try {
                val turn = admission()
                activeChatId = turn.chatId
                val chatDocId = turn.chatId
                val text = turn.user.contentMd
                val persona = turn.persona
                val answerScope = turn.answerScope
                // Later queued messages must never enter this turn's history.
                val transcript = vaultRepository.listMessages(chatDocId)
                val userIndex = transcript.indexOfFirst { it.id == turn.user.id }
                check(userIndex >= 0) { "The unanswered message is unavailable" }
                val priorHistory = transcript.take(userIndex)
                val modelId =
                    when {
                        turn.selectedModel != null -> {
                            turn.selectedModel.prepare()
                            turn.selectedModel.modelId
                        }
                        prepareModel != null -> prepareModel.invoke(persona)
                        else -> {
                            warmUp()
                            null
                        }
                    }
                if (preparationCancelled) throw CancellationException("prompt preparation cancelled")
                val retrieved =
                    if (answerScope == AnswerScope.KNOWLEDGE) {
                        retrievalService.retrieveContext(text, RETRIEVAL_K, persona?.id)
                    } else {
                        emptyList()
                    }
                val params = turn.params
                val budget = budgetFor(params.maxTokens, AnswerPolicy.systemPrompt(persona, answerScope))
                val assembled =
                    if (measurePrompt != null) {
                        ExactPromptAssembler(promptAssembler) { prompt, sampling ->
                            if (preparationCancelled) throw CancellationException("prompt preparation cancelled")
                            measurePrompt.invoke(prompt, sampling).also {
                                if (preparationCancelled) throw CancellationException("prompt preparation cancelled")
                            }
                        }.assemble(persona, priorHistory, retrieved, text, budget, countTokens, answerScope, params)
                    } else {
                        promptAssembler.assemble(
                            persona,
                            priorHistory,
                            retrieved,
                            text,
                            budget,
                            countTokens,
                            answerScope,
                        )
                    }
                if (preparationCancelled) throw CancellationException("prompt preparation cancelled")
                val noEvidence =
                    answerScope == AnswerScope.KNOWLEDGE &&
                        !retrievalService.acceptsEvidence(text, assembled.citations.values.toList())
                // A supporting tail chunk can be removed by either budget pass.
                // An application-owned abstention uses no sources or model prompt.
                val finalAssembly =
                    if (noEvidence) {
                        promptAssembler
                            .assemble(
                                persona,
                                priorHistory,
                                emptyList(),
                                text,
                                budget,
                                countTokens,
                                answerScope,
                            ).copy(
                                droppedRetrievedItems = retrieved.size,
                                formattedTokens = null,
                            )
                    } else {
                        assembled
                    }
                if (preparationCancelled) throw CancellationException("prompt preparation cancelled")
                val context = TurnPromptContext(turn, retrieved, finalAssembly, modelId, noEvidence)
                onContext(context)
                if (noEvidence) {
                    val segments = listOf(Segment.Text(NO_KNOWLEDGE_EVIDENCE))
                    send(segments.single())
                    val snapshot = TurnAnswerSnapshot(context, NO_KNOWLEDGE_EVIDENCE, segments, StopReason.EOS)
                    if (persist) {
                        publishOutcome(
                            snapshot,
                            (finalizer().persist(snapshot) { true } as TurnPersistResult.Committed).message,
                        )
                    }
                    onDone(StopReason.EOS)
                    return@channelFlow
                }
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
                if (persist) {
                    val snapshot = TurnAnswerSnapshot(context, rawText.toString(), allSegments.toList(), reason)
                    publishOutcome(
                        snapshot,
                        (finalizer().persist(snapshot) { true } as TurnPersistResult.Committed).message,
                    )
                }
                onDone(reason)
            } finally {
                activeChatId = null
                turnGate.unlock()
            }
        }.buffer(Channel.UNLIMITED)

    private companion object {
        val COALESCE_INTERVAL_DEFAULT: Duration = 30.milliseconds
    }
}
