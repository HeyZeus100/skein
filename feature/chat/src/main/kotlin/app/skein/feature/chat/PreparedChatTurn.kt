package app.skein.feature.chat

import app.skein.core.model.AnswerScope
import app.skein.core.model.AssembledPrompt
import app.skein.core.model.DocId
import app.skein.core.model.Message
import app.skein.core.model.ModelId
import app.skein.core.model.NewMessage
import app.skein.core.model.Persona
import app.skein.core.model.Retrieved
import app.skein.core.model.Role
import app.skein.core.model.SamplingParams
import app.skein.core.model.StopReason
import app.skein.core.model.VaultRepository
import app.skein.core.rag.chat.CitationRecords
import app.skein.core.rag.chat.Segment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Immutable model selection; preparing it happens only when this turn reaches the engine queue. */
public class TurnModelSelection(
    public val modelId: ModelId?,
    internal val prepare: suspend () -> Unit,
)

/** Admission snapshots preferences before USER is committed; no later shell/Space switch changes them. */
public class PreparedChatTurn internal constructor(
    public val user: Message,
    internal val persona: Persona?,
    internal val answerScope: AnswerScope,
    internal val params: SamplingParams,
    internal val selectedModel: TurnModelSelection?,
) {
    public val chatId: DocId get() = user.chatDocId
}

/** Prompt/citation offer captured for one execution; private content never belongs in diagnostics. */
public class TurnPromptContext internal constructor(
    internal val turn: PreparedChatTurn,
    internal val retrieved: List<Retrieved>,
    internal val assembled: AssembledPrompt,
    internal val modelId: ModelId?,
    internal val generationSkipped: Boolean = false,
)

/** The published answer, frozen before Stop/lock can admit any later tokens. */
internal class TurnAnswerSnapshot(
    val context: TurnPromptContext,
    val text: String,
    val segments: List<Segment>,
    val reason: StopReason,
) {
    fun outcome(message: Message?): TurnOutcome =
        TurnOutcome(
            chatDocId = context.turn.chatId,
            userQuery = context.turn.user.contentMd,
            stopReason = reason,
            interrupted = reason == StopReason.CANCELLED,
            retrieved = context.retrieved,
            assembled = context.assembled,
            assistantMessage = message,
            answerScope = context.turn.answerScope,
            generationSkipped = context.generationSkipped,
            persona = context.turn.persona,
            modelId = context.modelId,
        )
}

internal sealed interface TurnPersistResult {
    class Committed(
        val message: Message?,
    ) : TurnPersistResult

    data object Refused : TurnPersistResult
}

/** Exactly one assistant commit shared by completion, Stop and lock; no engine wait under this gate. */
internal class TurnFinalizer(
    private val repository: VaultRepository,
) {
    private val gate = Mutex()

    @Volatile private var committed = false

    @Volatile private var message: Message? = null

    @Volatile private var closed = false

    fun clear() {
        closed = true
        message = null
    }

    suspend fun persist(
        snapshot: TurnAnswerSnapshot,
        mayWrite: () -> Boolean,
    ): TurnPersistResult =
        gate.withLock {
            if (committed) return@withLock TurnPersistResult.Committed(message)
            if (closed || !mayWrite()) return@withLock TurnPersistResult.Refused
            if (snapshot.text.isBlank()) {
                committed = true
                return@withLock TurnPersistResult.Committed(null)
            }
            try {
                repository.transaction {
                    if (closed || !mayWrite()) return@transaction
                    val saved =
                        repository.appendMessage(
                            snapshot.context.turn.chatId,
                            NewMessage(
                                role = Role.ASSISTANT,
                                contentMd =
                                    snapshot.text +
                                        if (snapshot.reason == StopReason.CANCELLED) INTERRUPTED_MARKER else "",
                                modelId = snapshot.context.modelId,
                                citations = CitationRecords.fromStream(snapshot.context.assembled, snapshot.segments),
                            ),
                        )
                    if (closed || !mayWrite()) throw PersistenceRevoked()
                    // The post-COMMIT hook records success before dispatch/cancellation can
                    // hide it from Stop/lock and cause a second assistant append.
                    repository.afterTransactionCommit {
                        if (!closed) message = saved
                        committed = true
                    }
                }
            } catch (_: PersistenceRevoked) {
                return@withLock TurnPersistResult.Refused
            }
            if (committed) TurnPersistResult.Committed(message) else TurnPersistResult.Refused
        }

    private class PersistenceRevoked : CancellationException("turn persistence revoked")
}
