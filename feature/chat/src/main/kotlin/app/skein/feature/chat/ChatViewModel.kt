// skein-6as (E6.I8). State holder for `ChatScreen` — deliberately a plain
// `@Stable` class, not an `androidx.lifecycle.ViewModel`, matching
// `NavState`/`TabsState`/`SettingsViewModel` (`:feature:shell`/
// `:feature:settings`) — none of those use the AAC ViewModel either; see
// `SettingsViewModel`'s own doc comment for the precedent this follows.
package app.skein.feature.chat

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skein.core.model.DocId
import app.skein.core.model.ImportService
import app.skein.core.model.InferenceException
import app.skein.core.model.Message
import app.skein.core.model.PersonaId
import app.skein.core.model.Retrieved
import app.skein.core.model.Role
import app.skein.core.model.VaultRepository
import app.skein.core.rag.chat.Segment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream

/** One retrieved item's chip-relevant projection — enough to open a preview and show its excerpt. */
public data class ChatCitation(
    val marker: Int,
    val docId: DocId,
    val label: String,
    val excerpt: String,
)

/** One message as the UI renders it — [displayText] has any [INTERRUPTED_MARKER] stripped. */
public data class ChatMessageUi(
    val id: String,
    val role: Role,
    val displayText: String,
    val interrupted: Boolean,
    val citations: Map<Int, ChatCitation>,
    val createdAt: Long,
)

/** Error banner state (spec §8.4: `ServiceDied` -> "model process restarted, retry"). */
public enum class ChatBanner { NONE, SERVICE_DIED, ENGINE_ERROR }

/**
 * State holder for [ChatScreen]. Owns nothing [SendPipeline] doesn't already
 * own — this class only projects [VaultRepository.observeMessages] and
 * [SendPipeline]'s streaming/outcome state into Compose state (via
 * [turnState] — see `ChatTurnState.kt`), and forwards UI gestures
 * (send/cancel/retry/citation tap/attach) to [sendPipeline] /
 * [tabController] / [importService].
 *
 * @param tabController the seam this module defines for the shared tab
 *   system (`:feature:shell`'s `TabsState` has no interface a fake can
 *   implement yet — see `app.skein.testing.RecordingTabController`'s own
 *   header). The wiring bead (`skein-whg8`) adapts the real `TabsState` to
 *   this interface.
 * @param importService nullable so a host that hasn't wired attachment
 *   import yet still renders a working chat screen (📎 disabled).
 * @param currentPersonaId read once per [send] call; `null` means "use
 *   `PersonaService.default()`" and is resolved by whatever
 *   `personaProvider` the app wired into [sendPipeline] — this class never
 *   talks to a `PersonaService` directly.
 */
@Stable
public class ChatViewModel(
    private val chatDocId: DocId,
    private val vaultRepository: VaultRepository,
    private val sendPipeline: SendPipeline,
    private val tabController: TabController,
    private val scope: CoroutineScope,
    private val importService: ImportService? = null,
    private val currentPersonaId: () -> PersonaId? = { null },
) {
    var messages: List<ChatMessageUi> by mutableStateOf(emptyList())
        private set

    /**
     * The most recent (or in-flight) turn's state — owner scope addition.
     * `null` until the first [send] this session; the sent user bubble's
     * own [SentMessageState] and the streaming assistant bubble both derive
     * from this single source of truth.
     */
    var turnState: ChatTurnState? by mutableStateOf(null)
        private set

    val isGenerating: Boolean
        get() =
            turnState.let {
                it is ChatTurnState.Queued ||
                    it is ChatTurnState.Thinking ||
                    it is ChatTurnState.Streaming
            }

    var streamingCitations: Map<Int, ChatCitation> by mutableStateOf(emptyMap())
        private set

    val banner: ChatBanner
        get() {
            val failed = turnState as? ChatTurnState.Failed ?: return ChatBanner.NONE
            return if (failed.exception is InferenceException.ServiceDied) {
                ChatBanner.SERVICE_DIED
            } else {
                ChatBanner.ENGINE_ERROR
            }
        }

    var contextPanelOpen: Boolean by mutableStateOf(false)
        private set

    /** The last turn's [Retrieved] offer, with scores and `recalledBy` — spec §8.4's context panel. */
    var contextItems: List<Retrieved> by mutableStateOf(emptyList())
        private set

    private var expandedCitations: Set<Pair<String, Int>> by mutableStateOf(emptySet())
    private var tappedOnce: Set<Pair<String, Int>> = emptySet()
    private var lastFailedText: String? = null

    private val jobs: MutableList<Job> = mutableListOf()

    init {
        jobs +=
            scope.launch {
                vaultRepository.observeMessages(chatDocId).collect { list ->
                    // `List.map`'s `transform` isn't a suspend-typed lambda
                    // parameter, so a suspend `toUi` (it does a
                    // `VaultRepository.getDocument` lookup per citation)
                    // can't be passed as a callable reference to it — a
                    // plain loop instead.
                    val projected = ArrayList<ChatMessageUi>(list.size)
                    for (message in list) projected += toUi(message)
                    messages = projected
                }
            }
        jobs +=
            scope.launch {
                sendPipeline.lastOutcome.collect { outcome ->
                    if (outcome != null) contextItems = outcome.retrieved
                }
            }
    }

    /** True once [messageId]/[marker] has been tapped more than once — the "second tap expands the excerpt" rule. */
    public fun isExcerptExpanded(
        messageId: String,
        marker: Int,
    ): Boolean = (messageId to marker) in expandedCitations

    private suspend fun toUi(message: Message): ChatMessageUi {
        val displayText = stripInterruptedMarker(message.contentMd)
        val interrupted = displayText != message.contentMd
        val citations =
            message.citations?.retrieved?.associateBy({ it.marker }) { citation ->
                val title = vaultRepository.getDocument(citation.documentId)?.title
                ChatCitation(
                    marker = citation.marker,
                    docId = citation.documentId,
                    label = title ?: citation.excerpt.take(EXCERPT_LABEL_LENGTH),
                    excerpt = citation.excerpt,
                )
            } ?: emptyMap()
        return ChatMessageUi(
            id = message.id,
            role = message.role,
            displayText = displayText,
            interrupted = interrupted,
            citations = citations,
            createdAt = message.createdAt,
        )
    }

    /** Bottom bar ⏎ send (also used by [retry]). No-op while a generation is already running. */
    public fun send(text: String) {
        if (text.isBlank() || isGenerating) return
        lastFailedText = null
        streamingCitations = emptyMap()
        turnState = ChatTurnState.Queued
        val startedAtNanos = System.nanoTime()

        val tickerJob =
            scope.launch {
                while (isActive) {
                    delay(THINKING_TICK_MS)
                    if (turnState !is ChatTurnState.Thinking) break
                    turnState = ChatTurnState.Thinking(elapsedMs(startedAtNanos))
                }
            }

        scope.launch {
            turnState = ChatTurnState.Thinking(elapsedMs(startedAtNanos))
            val builder = StringBuilder()
            try {
                sendPipeline.send(chatDocId, text).collect { segment ->
                    when (segment) {
                        is Segment.Text -> {
                            builder.append(segment.text)
                            turnState = ChatTurnState.Streaming(builder.toString())
                        }
                        is Segment.Citation -> {
                            streamingCitations =
                                streamingCitations +
                                (
                                    segment.marker to
                                        ChatCitation(
                                            marker = segment.marker,
                                            docId = segment.retrieved.docId,
                                            label = segment.retrieved.docTitle,
                                            excerpt = segment.retrieved.text,
                                        )
                                )
                            if (turnState !is ChatTurnState.Streaming) {
                                turnState =
                                    ChatTurnState.Streaming(builder.toString())
                            }
                        }
                    }
                }
                turnState =
                    if (sendPipeline.lastOutcome.value?.interrupted == true) {
                        ChatTurnState.Interrupted(builder.toString())
                    } else {
                        ChatTurnState.Done
                    }
            } catch (e: InferenceException) {
                turnState = ChatTurnState.Failed(e)
                lastFailedText = text
            } finally {
                tickerJob.cancel()
                streamingCitations = emptyMap()
            }
        }
    }

    /** Cancel button while generating (spec §8.4). Forwards to [SendPipeline.cancel]; does not touch [messages]. */
    public fun cancel() {
        scope.launch { sendPipeline.cancel() }
    }

    /** Error banner's retry action — re-sends the exact prompt that failed. */
    public fun retry() {
        val text = lastFailedText ?: return
        send(text)
    }

    public fun toggleContextPanel() {
        contextPanelOpen = !contextPanelOpen
    }

    /**
     * A citation chip tap (spec §8.4). First tap opens the source as a
     * preview tab; a second tap on the same chip additionally expands its
     * inline excerpt (both AC-required behaviours).
     */
    public fun onCitationTap(
        messageId: String,
        citation: ChatCitation,
    ) {
        tabController.openPreview(citation.docId, citation.label, ChatTabKind.NOTE)
        val key = messageId to citation.marker
        expandedCitations =
            if (key in tappedOnce) {
                expandedCitations + key
            } else {
                tappedOnce = tappedOnce + key
                expandedCitations
            }
    }

    /**
     * 📎 attach: caller already resolved [displayName]/[mimeType]/[input] from
     * a SAF `OpenDocument` result. Routes to the right [ImportService] entry
     * point and returns the `[[attachment title]]` wikilink text to insert
     * into the composer — `null` when no [importService] was wired.
     */
    public suspend fun attach(
        displayName: String,
        mimeType: String,
        input: InputStream,
    ): String? {
        val service = importService ?: return null
        when {
            mimeType == "application/pdf" -> service.importPdf(displayName, input, currentPersonaId())
            mimeType.startsWith("image/") -> service.importImage(displayName, mimeType, input, currentPersonaId())
            else -> service.importText(displayName, mimeType, input, currentPersonaId())
        }
        return "[[$displayName]]"
    }

    private fun elapsedMs(startedAtNanos: Long): Long = (System.nanoTime() - startedAtNanos) / NANOS_PER_MILLI

    private companion object {
        const val EXCERPT_LABEL_LENGTH = 40
        const val THINKING_TICK_MS = 200L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
