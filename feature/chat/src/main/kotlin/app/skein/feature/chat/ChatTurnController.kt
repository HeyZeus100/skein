package app.skein.feature.chat

import app.skein.core.model.ChatDraftKey
import app.skein.core.model.DocId
import app.skein.core.model.DocumentKind
import app.skein.core.model.InferenceException
import app.skein.core.model.NewDocument
import app.skein.core.model.StopReason
import app.skein.core.model.VaultRepository
import app.skein.core.rag.chat.Segment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.min

/** One chat's session state; this holder and every previously returned flow are cleared at lock. */
public class ChatTurnSnapshot(
    public val turn: ChatTurnState? = null,
    public val citations: Map<Int, ChatCitation> = emptyMap(),
    public val outcome: TurnOutcome? = null,
    public val userMessageId: String? = null,
)

/**
 * Session-owned FIFO over a single inference engine. Admission commits USER
 * before queueing; navigation only observes state and never owns a turn job.
 * No user text or ids are written to logs, saved state or exception messages.
 */
public class ChatTurnController(
    private val repository: VaultRepository,
    private val pipeline: SendPipeline,
    private val epoch: Long,
    private val unlockedEpoch: () -> Long?,
    private val lockingEpoch: () -> Long?,
    private val scope: CoroutineScope,
    private val awaitEngineIdle: suspend () -> Unit = {},
    private val commitDraft: (suspend (ChatDraftKey, Long, suspend () -> PreparedChatTurn) -> PreparedChatTurn)? = null,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val ownedScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    private enum class Phase { OPEN, LOCKING, CLOSED }

    private val monitor = Any()
    private val admission = Mutex()
    private val pendingAdmissions = mutableSetOf<Job>()
    private var phase = Phase.OPEN
    private val flows = mutableMapOf<DocId, MutableStateFlow<ChatTurnSnapshot>>()
    private val closeListeners = mutableSetOf<() -> Unit>()
    private val records = mutableMapOf<DocId, Record>()
    private val queue = ArrayDeque<Record>()
    private var active: Record? = null
    private var lockDeadlineNanos = 0L
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private class Record(
        turn: PreparedChatTurn,
        val finalizer: TurnFinalizer,
    ) {
        val chatId = turn.chatId
        val userId = turn.user.id
        var prepared: PreparedChatTurn? = turn
        val turn: PreparedChatTurn get() = requireNotNull(prepared)

        var context: TurnPromptContext? = null
        val text = StringBuilder()
        val segments = mutableListOf<Segment>()
        val citations = mutableMapOf<Int, ChatCitation>()
        var job: Job? = null
        var cancelJob: Job? = null
        var frozen = false
        var finished = false
        var deleting = false
        var snapshot: TurnAnswerSnapshot? = null
        var reason = StopReason.EOS
        var startedAt = 0L
    }

    private val worker =
        ownedScope.launch {
            for (signal in wake) {
                while (isActive) {
                    val record =
                        synchronized(monitor) {
                            if (!accepts()) return@synchronized null
                            queue.removeFirstOrNull()?.also { active = it }
                        } ?: break
                    try {
                        run(record)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        synchronized(monitor) {
                            if (accepts() && !record.deleting) {
                                record.finished = true
                                flow(record.chatId).value =
                                    ChatTurnSnapshot(
                                        ChatTurnState.Failed(InferenceException.Internal()),
                                        userMessageId = record.userId,
                                    )
                            }
                        }
                    }
                    synchronized(monitor) { if (active === record) active = null }
                    // Cancelling a Flow unregisters its collector before a Binder
                    // engine necessarily becomes idle. Never dispatch the next turn
                    // until the real service has acknowledged readiness/death.
                    if (synchronized(monitor) { accepts() }) {
                        try {
                            awaitEngineIdle()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            synchronized(monitor) {
                                if (accepts()) {
                                    queue.forEach { queued ->
                                        queued.finished = true
                                        flow(queued.chatId).value =
                                            ChatTurnSnapshot(
                                                ChatTurnState.Failed(
                                                    failure as? InferenceException ?: InferenceException.Internal(),
                                                ),
                                                userMessageId = queued.userId,
                                            )
                                    }
                                    queue.clear()
                                }
                            }
                        }
                    }
                }
            }
        }

    internal fun observeClose(listener: () -> Unit): () -> Unit =
        synchronized(monitor) {
            if (phase == Phase.CLOSED) listener() else closeListeners.add(listener)
            val remove: () -> Unit = {
                synchronized(monitor) {
                    closeListeners.remove(listener)
                    Unit
                }
            }
            remove
        }

    internal fun publishView(action: () -> Unit) = synchronized(monitor) { if (accepts()) action() }

    public fun state(chatId: DocId): StateFlow<ChatTurnSnapshot> =
        synchronized(monitor) {
            if (phase == Phase.CLOSED) return@synchronized MutableStateFlow(ChatTurnSnapshot()).asStateFlow()
            flows.getOrPut(chatId) { MutableStateFlow(ChatTurnSnapshot()) }.asStateFlow()
        }

    /** Capture text/version from the controlled composer; acknowledged only after the atomic send commits. */
    public fun enqueue(
        chatId: DocId,
        text: String,
        draftVersion: Long? = null,
    ): Deferred<DocId> =
        ownedScope.async {
            admitToQueue(chatId, if (draftVersion != null) ChatDraftKey.Existing(chatId) else null, draftVersion) {
                pipeline.admit(chatId, text)
            }
        }

    /** First send creates the chat and its Knowledge choice before admitting USER, in the draft transaction. */
    public fun enqueueNew(
        key: ChatDraftKey.New,
        version: Long,
        text: String,
        title: String,
        knowledgeEnabled: Boolean = true,
    ): Deferred<DocId> =
        ownedScope.async {
            admitToQueue(null, key, version) {
                val chat =
                    repository.createDocument(
                        NewDocument(
                            DocumentKind.CHAT,
                            title,
                            "",
                            personaId = key.spaceId,
                            frontmatter = JsonObject(mapOf(ChatKnowledge.KEY to JsonPrimitive(knowledgeEnabled))),
                        ),
                    )
                pipeline.admit(chat.id, text)
            }
        }

    /** Retry a durable unanswered USER; no private retry text is retained after lock and no USER is duplicated. */
    public fun retry(
        chatId: DocId,
        userMessageId: String,
    ): Deferred<DocId> =
        ownedScope.async {
            admitToQueue(chatId, null, null) { pipeline.resume(chatId, userMessageId) }
        }

    private suspend fun admitToQueue(
        chatId: DocId?,
        key: ChatDraftKey?,
        version: Long?,
        append: suspend () -> PreparedChatTurn,
    ): DocId =
        admissionTask {
            admission.withLock {
                synchronized(monitor) {
                    checkOpen()
                    if (chatId != null && records[chatId]?.finished == false) throw InferenceException.Busy()
                }
                val write: suspend () -> PreparedChatTurn = {
                    synchronized(monitor) { checkOpen() }
                    val prepared = append()
                    synchronized(monitor) { checkOpen() }
                    // If cancellation lands after COMMIT but before dispatcher return,
                    // admission still records the durable USER exactly once.
                    repository.afterTransactionCommit {
                        synchronized(monitor) {
                            if (accepts()) {
                                val record = Record(prepared, pipeline.finalizer())
                                records[prepared.chatId] = record
                                queue.addLast(record)
                                flow(prepared.chatId).value =
                                    ChatTurnSnapshot(ChatTurnState.Queued, userMessageId = prepared.user.id)
                                wake.trySend(Unit)
                            }
                        }
                    }
                    prepared
                }
                val prepared =
                    if (key != null && version != null) {
                        val commit = requireNotNull(commitDraft) { "Draft send is unavailable" }
                        commit(key, version, write)
                    } else {
                        var value: PreparedChatTurn? = null
                        repository.transaction { value = write() }
                        requireNotNull(value)
                    }
                prepared.chatId
            }
        }

    private suspend fun admissionTask(block: suspend () -> DocId): DocId {
        val job = currentCoroutineContext()[Job]!!
        synchronized(monitor) {
            checkOpen()
            pendingAdmissions += job
        }
        try {
            return block()
        } finally {
            synchronized(monitor) { pendingAdmissions -= job }
        }
    }

    private suspend fun run(record: Record) {
        val job =
            ownedScope.launch(start = CoroutineStart.LAZY) {
                val ticker =
                    launch {
                        while (isActive) {
                            delay(100L)
                            synchronized(monitor) {
                                if (!canPublish(record) || record.text.isNotEmpty()) return@launch
                                flow(record.chatId).value =
                                    ChatTurnSnapshot(
                                        ChatTurnState.Thinking((nanoTime() - record.startedAt) / 1_000_000L),
                                        userMessageId = record.userId,
                                    )
                            }
                        }
                    }
                try {
                    pipeline
                        .runPrepared(
                            record.turn,
                            onContext = { context ->
                                synchronized(monitor) {
                                    if (!record.frozen) {
                                        record.context =
                                            context
                                    }
                                }
                            },
                            onDone = { reason -> synchronized(monitor) { if (!record.frozen) record.reason = reason } },
                        ).collect { segment ->
                            synchronized(monitor) {
                                if (!canPublish(record)) return@collect
                                record.segments += segment
                                when (segment) {
                                    is Segment.Text -> record.text.append(segment.text)
                                    is Segment.Citation -> {
                                        record.text.append("[${segment.marker}]")
                                        record.citations[segment.marker] =
                                            ChatCitation(
                                                segment.marker,
                                                segment.retrieved.docId,
                                                segment.retrieved.docTitle,
                                                segment.retrieved.text,
                                            )
                                    }
                                }
                                flow(record.chatId).value =
                                    ChatTurnSnapshot(
                                        ChatTurnState.Streaming(record.text.toString()),
                                        record.citations.toMap(),
                                        userMessageId = record.userId,
                                    )
                            }
                        }
                    end(record)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    synchronized(monitor) {
                        if (canPublish(record)) {
                            record.finished = true
                            flow(record.chatId).value =
                                ChatTurnSnapshot(
                                    ChatTurnState.Failed(
                                        e as? InferenceException ?: InferenceException.Internal(),
                                    ),
                                    userMessageId = record.userId,
                                )
                        }
                    }
                } finally {
                    ticker.cancel()
                }
            }
        synchronized(monitor) {
            record.job = job
            record.startedAt = nanoTime()
            if (record.frozen || !accepts()) {
                job.cancel()
            } else {
                flow(record.chatId).value =
                    ChatTurnSnapshot(ChatTurnState.Thinking(0L), userMessageId = record.userId)
            }
        }
        job.start()
        job.join()
        synchronized(monitor) { record.cancelJob }?.join()
        if (synchronized(monitor) { record.frozen && phase == Phase.OPEN }) end(record)
    }

    /** Stops accepting tokens immediately. Queue removal is immediate; the USER remains durable. */
    public fun stop(chatId: DocId) {
        val record =
            synchronized(monitor) {
                records[chatId]?.takeUnless { it.finished }?.also { freeze(it) }
            } ?: return
        scheduleCancel(record)
        // Stop's durable snapshot is independent of a slow/hung engine cancel.
        // The worker still waits for cancellation/readiness before dispatching another turn.
        ownedScope.launch {
            try {
                end(record)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                synchronized(monitor) {
                    if (accepts() && records[record.chatId] === record && !record.deleting) {
                        record.finished = true
                        flow(record.chatId).value =
                            ChatTurnSnapshot(
                                ChatTurnState.Failed(InferenceException.Internal()),
                                userMessageId = record.userId,
                            )
                    }
                }
            }
        }
        wake.trySend(Unit)
    }

    /** A pending delete owns the terminal action and must never acquire a lock-time partial answer. */
    public fun markDeleting(chatId: DocId) {
        val record =
            synchronized(monitor) {
                records[chatId]?.also {
                    it.deleting = true
                    freeze(it)
                }
            } ?: return
        scheduleCancel(record)
    }

    /** HIGH-tier hook: synchronous freeze only, engine cancellation is scheduled without waiting. */
    public fun onLockingHigh(
        lockEpoch: Long,
        budgetMillis: Long,
    ) {
        if (lockEpoch != epoch) return
        val toCancel =
            synchronized(monitor) {
                if (phase != Phase.OPEN) return
                phase = Phase.LOCKING
                lockDeadlineNanos = nanoTime() + budgetMillis.coerceAtLeast(0L) * 1_000_000L
                records.values
                    .filter { !it.finished }
                    .onEach(::freeze)
                    .toList()
            }
        synchronized(monitor) { pendingAdmissions.toList() }.forEach(Job::cancel)
        toCancel.forEach(::scheduleCancel)
    }

    /** LOW-tier hook: wait at most 150 ms, then persist the frozen visible snapshot without the engine. */
    public suspend fun onLockingLow(
        lockEpoch: Long,
        budgetMillis: Long,
    ) {
        if (lockEpoch != epoch) return
        val running =
            synchronized(monitor) {
                if (phase != Phase.LOCKING) return
                active
            }
        val remaining = min(budgetMillis, remainingMillis())
        if (remaining <= 0L) return
        withTimeoutOrNull(min(150L, remaining)) {
            running?.job?.join()
            running?.cancelJob?.join()
        }
        val left = min(budgetMillis, remainingMillis())
        if (left <= 0L) return
        withTimeoutOrNull(left) {
            val frozen = synchronized(monitor) { records.values.filter { it.frozen && !it.finished }.toList() }
            for (record in frozen) end(record, lockFlush = true)
        }
    }

    /** Pure in-memory teardown, also used by an early session close before normal onLocked dispatch. */
    public fun close() {
        synchronized(monitor) {
            if (phase == Phase.CLOSED) return
            phase = Phase.CLOSED
            (records.values.toList() + listOfNotNull(active) + queue.toList()).distinct().forEach {
                it.frozen = true
                it.job?.cancel()
                it.text.clear()
                it.segments.clear()
                it.citations.clear()
                it.context =
                    null
                it.snapshot = null
                it.prepared = null
                it.finalizer.clear()
            }
            flows.values.forEach { it.value = ChatTurnSnapshot() }
            flows.clear()
            records.clear()
            queue.clear()
            active = null
            pipeline.clearSessionState()
            closeListeners.toList().forEach { it() }
            closeListeners.clear()
            pendingAdmissions.clear()
        }
        wake.close()
        worker.cancel()
        ownedScope.cancel()
    }

    private fun freeze(record: Record) {
        if (record.frozen) return
        record.frozen = true
        record.reason = StopReason.CANCELLED
        record.snapshot = snapshot(record)
        queue.remove(record)
        if (record.job == null) {
            record.finished = true
            if (phase == Phase.OPEN) {
                flow(record.chatId).value =
                    ChatTurnSnapshot(
                        ChatTurnState.Interrupted(""),
                        userMessageId = record.userId,
                    )
            }
        }
    }

    private fun scheduleCancel(record: Record) {
        val cancellation =
            synchronized(monitor) {
                if (record.job == null || record.cancelJob != null) return
                ownedScope
                    .launch(start = CoroutineStart.LAZY) {
                        try {
                            pipeline.cancel(record.chatId)
                        } catch (
                            cancelled: CancellationException,
                        ) {
                            throw cancelled
                        } catch (_: Exception) {
                            // Readiness/death is checked before the next dispatch; LOW never relies on this RPC succeeding.
                        }
                    }.also { record.cancelJob = it }
            }
        cancellation.start()
        record.job?.cancel()
    }

    private suspend fun end(
        record: Record,
        lockFlush: Boolean = false,
    ) {
        val snapshot =
            synchronized(monitor) {
                // Only LOW owns a lock-time write. A completion in the tiny gap
                // between epoch revocation and HIGH freeze stays pending for LOW.
                if (phase == Phase.CLOSED ||
                    (phase == Phase.OPEN && unlockedEpoch() != epoch) ||
                    (phase == Phase.LOCKING && !lockFlush)
                ) {
                    return
                }
                record.snapshot ?: snapshot(record)?.also { record.snapshot = it }
            }
        if (snapshot == null) {
            synchronized(monitor) {
                record.finished = true
                if (phase == Phase.OPEN) {
                    flow(record.chatId).value =
                        ChatTurnSnapshot(
                            ChatTurnState.Interrupted(""),
                            userMessageId = record.userId,
                        )
                }
            }
            return
        }
        val persisted = record.finalizer.persist(snapshot) { synchronized(monitor) { mayPersist(record, lockFlush) } }
        if (persisted is TurnPersistResult.Refused) return
        val message = (persisted as TurnPersistResult.Committed).message
        synchronized(monitor) {
            record.finished = true
            if (phase == Phase.OPEN &&
                unlockedEpoch() == epoch &&
                !record.deleting &&
                records[record.chatId] === record
            ) {
                pipeline.publishOutcome(snapshot, message)
                flow(record.chatId).value =
                    ChatTurnSnapshot(
                        if (snapshot.reason ==
                            StopReason.CANCELLED
                        ) {
                            ChatTurnState.Interrupted(snapshot.text)
                        } else {
                            ChatTurnState.Done
                        },
                        outcome = snapshot.outcome(message),
                        userMessageId = record.userId,
                    )
            }
        }
    }

    private fun snapshot(record: Record): TurnAnswerSnapshot? =
        record.context?.let {
            TurnAnswerSnapshot(it, record.text.toString(), record.segments.toList(), record.reason)
        }

    private fun flow(chatId: DocId): MutableStateFlow<ChatTurnSnapshot> =
        flows.getOrPut(chatId) {
            MutableStateFlow(ChatTurnSnapshot())
        }

    private fun accepts(): Boolean = phase == Phase.OPEN && unlockedEpoch() == epoch

    private fun canPublish(record: Record): Boolean =
        accepts() && !record.frozen && !record.finished && !record.deleting && records[record.chatId] === record

    private fun mayPersist(
        record: Record,
        lockFlush: Boolean,
    ): Boolean =
        !record.deleting &&
            when (phase) {
                Phase.OPEN -> !lockFlush && unlockedEpoch() == epoch
                Phase.LOCKING -> lockFlush && lockingEpoch() == epoch
                Phase.CLOSED -> false
            }

    private fun checkOpen() {
        if (!accepts()) throw InferenceException.SessionLocked()
    }

    private fun remainingMillis(): Long = ((lockDeadlineNanos - nanoTime()) / 1_000_000L).coerceAtLeast(0L)
}
