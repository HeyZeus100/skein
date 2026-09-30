// skein-xtov.24.8 (AL-09a): the Chat destination's NavDisplay entries
// (ADAPTIVE_LAYOUT_SPEC.md §8.2, §4.2; CHAT_UX_SPEC.md §3, §12, §17): the
// Chat root (the landing in a drawer window, the Conversations list with a
// rail), the NewChatKey landing, the conversation, the context inspector and
// an opened source. Every entry reads ids from its key and shows titles only
// on screen: never in a key, a log line or the saved state (SECURITY_REVIEW_D7.md M1, M13).
package app.skein.feature.chat.entries

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import app.skein.core.designsystem.components.SkeinAction
import app.skein.core.designsystem.components.SkeinContextChip
import app.skein.core.designsystem.components.SkeinNotice
import app.skein.core.designsystem.icons.SkeinIcons
import app.skein.core.designsystem.theme.SkeinSpacing
import app.skein.core.model.ChatDraftKey
import app.skein.core.model.DocId
import app.skein.core.model.DocumentKind
import app.skein.core.model.ImportService
import app.skein.core.model.TimelineFilter
import app.skein.core.model.VaultRepository
import app.skein.core.navigation.ChatContextKey
import app.skein.core.navigation.ChatHomeKey
import app.skein.core.navigation.ChatKey
import app.skein.core.navigation.ChatSourceKey
import app.skein.core.navigation.Destination
import app.skein.core.navigation.NavMode
import app.skein.core.navigation.NewChatKey
import app.skein.core.navigation.SkeinId
import app.skein.core.navigation.SkeinKey
import app.skein.core.navigation.TransientKey
import app.skein.core.navigation.TransientKind
import app.skein.core.navigation.contentKey
import app.skein.feature.chat.ChatKnowledge
import app.skein.feature.chat.ChatModelStatus
import app.skein.feature.chat.ChatScreen
import app.skein.feature.chat.ChatTurnController
import app.skein.feature.chat.ContextPanel
import app.skein.feature.chat.SendPipeline
import app.skein.feature.chat.drafts.SessionDraftStore
import app.skein.feature.chat.drafts.rememberDraftComposerState
import app.skein.feature.editor.autocomplete.Suggestion
import app.skein.feature.editor.entries.KnowledgeEntryDeps
import app.skein.feature.editor.entries.SourceEntry
import app.skein.feature.shell.container.ChatHistoryItem
import app.skein.feature.shell.host.EntryAction
import app.skein.feature.shell.host.EntryDocument
import app.skein.feature.shell.host.EntryTopBar
import app.skein.feature.shell.host.GoneEntry
import app.skein.feature.shell.host.LocalSheetMode
import app.skein.feature.shell.host.LocalSkeinWindowLayout
import app.skein.feature.shell.host.SheetMode
import app.skein.feature.shell.host.SheetPeekRow
import app.skein.feature.shell.host.SkeinShellState
import app.skein.feature.shell.host.followById
import app.skein.feature.shell.host.navMode
import app.skein.feature.shell.host.open
import app.skein.feature.shell.host.rememberEntryDocument
import app.skein.feature.shell.layout.SkeinNavContainer
import app.skein.feature.shell.layout.SkeinPosture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * What the Chat entries need from the open vault. [sendPipeline] is null only
 * where a session has no model services (test fixtures); [hasModel] is false
 * until a default model exists. [history] is the same list the drawer shows.
 * [knowledge]'s clock and zone date the rows.
 */
class ChatEntryDeps(
    val repository: VaultRepository,
    val knowledge: KnowledgeEntryDeps,
    val sendPipeline: SendPipeline?,
    val hasModel: Boolean,
    val handoff: ChatHandoff,
    val importService: ImportService? = null,
    val history: List<ChatHistoryItem> = emptyList(),
    val turns: ChatTurnController? = null,
    val drafts: SessionDraftStore? = null,
    val defaultSpaceId: String? = null,
    val modelStatus: ChatModelStatus = ChatModelStatus.Unavailable,
    /** Requests the shared confirmation dialog; never deletes directly. Null hides Delete. */
    val onDelete: ((DocId) -> Unit)? = null,
)

/**
 * The landing's first message, from its send to the new chat's screen, which
 * sends it (CHAT_UX_SPEC.md §11.4). Memory only, for this session: the caller
 * holds it under the vault gate, so a lock drops it (SECURITY_REVIEW_D7.md M6).
 */
class ChatHandoff {
    private val pending = ConcurrentHashMap<String, String>()

    internal fun put(
        chatId: String,
        text: String,
    ) {
        pending[chatId] = text
    }

    internal fun take(chatId: String): String? = pending.remove(chatId)
}

object ChatEntryTestTags {
    const val CONVERSATIONS = "chat_entry_conversations"
    const val LANDING = "chat_entry_landing"
    const val INSPECTOR = "chat_entry_inspector"
    const val INSPECTOR_PEEK = "chat_entry_inspector_peek"
    const val CONTEXT_ACTION = "chat_entry_context_action"
    const val NEW_CHAT_ACTION = "chat_entry_new_chat_action"
    const val UNAVAILABLE = "chat_entry_unavailable"
    const val DELETE_MENU = "chat_entry_delete_menu"
}

/** One Chat key's content (§8.2). The Chat root renders by container: the landing in a drawer, else Conversations. */
@Composable
fun ChatEntry(
    key: SkeinKey,
    shell: SkeinShellState,
    deps: ChatEntryDeps,
) {
    when (key) {
        ChatHomeKey ->
            if (LocalSkeinWindowLayout.current.nav == SkeinNavContainer.DRAWER) {
                NewChatLanding(key, shell, deps)
            } else {
                ConversationsPane(shell, deps.history, deps.knowledge.clock(), deps.knowledge.zone)
            }
        is NewChatKey -> NewChatLanding(key, shell, deps)
        is ChatKey -> ChatRoute(key, key.chatId.value, key.chatId, shell, deps)
        is ChatContextKey -> ChatInspector(key, shell, deps)
        is ChatSourceKey -> SourceEntry(key, key.docId.value, shell, deps.knowledge)
        is TransientKey -> {
            val raw = shell.nav.rawIdOf(key) ?: return
            when (key.kind) {
                TransientKind.CHAT -> ChatRoute(key, raw, null, shell, deps)
                TransientKind.SOURCE -> SourceEntry(key, raw, shell, deps.knowledge)
                else -> Unit
            }
        }
        else -> Unit
    }
}

/** The Conversations pane's empty detail (§4.1): the landing, so `[Chat]` and `[Chat, New chat]` look the same. */
@Composable
fun ChatDetailPlaceholder(
    shell: SkeinShellState,
    deps: ChatEntryDeps,
) = NewChatLanding(null, shell, deps)

@Composable
private fun ChatRoute(
    key: SkeinKey,
    rawId: String,
    chatId: SkeinId?,
    shell: SkeinShellState,
    deps: ChatEntryDeps,
) {
    val document = rememberEntryDocument(deps.repository, rawId)
    if (document is EntryDocument.Gone) {
        shell.GoneEntry(rawId, "This chat was deleted.", Destination.CHAT, "Go to Chats")
        return
    }
    val pipeline = deps.sendPipeline
    if (document !is EntryDocument.Present || pipeline == null) {
        if (pipeline == null) {
            Text(
                "Chat is unavailable in this build.",
                Modifier.padding(SkeinSpacing.space16).testTag(ChatEntryTestTags.UNAVAILABLE),
            )
        }
        return
    }
    val scope = rememberCoroutineScope()
    val repository = deps.repository
    // A citation or an inspector passage (§8.3 rule 2): the source opens beside the chat, or over it on one pane.
    val sources: (DocId) -> Unit =
        remember(rawId) {
            { docId ->
                if (chatId != null) {
                    shell.navigate { openSource(it, chatId, docId) }
                } else {
                    scope.launch { shell.followById(repository, docId) }
                }
            }
        }
    val first = remember(rawId) { if (deps.turns == null) deps.handoff.take(rawId) else null }
    val composer = deps.drafts?.let { rememberDraftComposerState(it, ChatDraftKey.Existing(rawId)) }
    val onePane = LocalSkeinWindowLayout.current.maxPanes == 1
    val phone = LocalSkeinWindowLayout.current.navMode() == NavMode.PHONE
    val deleteRequest = deps.onDelete?.takeIf { document.document.kind == DocumentKind.CHAT }
    val deleteDisabledReason = if (deleteRequest != null) rememberChatDeleteDisabledReason(deps.turns, rawId) else null
    ChatScreen(
        docId = rawId,
        vaultRepository = repository,
        sendPipeline = pipeline,
        onOpenSource = sources,
        wikilinkSuggest = { query -> repository.searchTitles(query).map { Suggestion(it.title) } },
        importService = deps.importService,
        initialMessage = first,
        turnController = deps.turns,
        composerState = composer,
        modelStatus = deps.modelStatus,
        tabletopHinge = (LocalSkeinWindowLayout.current.posture as? SkeinPosture.Tabletop)?.hinge,
        contextChip = {
            if (chatId != null) {
                val label = if (ChatKnowledge.enabled(document.document)) "Knowledge on" else "Knowledge off"
                SkeinContextChip(
                    label = label,
                    contentDescription = "Context: $label. Inspect context.",
                    modifier =
                        Modifier
                            .padding(
                                horizontal = SkeinSpacing.space8,
                            ).testTag(ChatEntryTestTags.CONTEXT_ACTION),
                    onClick = {
                        val inspector = ChatContextKey(chatId)
                        shell.navigate { follow(it, inspector) }
                        if (onePane) shell.sheets.expand(inspector.contentKey)
                    },
                )
            }
        },
        topBar = {
            Column {
                shell.EntryTopBar(key, document.document.title.ifBlank { UNTITLED_CHAT }) {
                    if (phone) {
                        EntryAction(
                            SkeinIcons.NewChat,
                            "New chat",
                            Modifier.testTag(ChatEntryTestTags.NEW_CHAT_ACTION),
                        ) {
                            shell.navigate { goTo(it, NewChatKey(SkeinId.random())) }
                        }
                    }
                    if (deleteRequest != null) {
                        ChatDeleteMenu(deleteDisabledReason) {
                            requestChatDelete(rawId, deps.turns, deleteRequest)
                        }
                    }
                }
                if (!deps.hasModel) NoModelNotice(shell)
            }
        },
    )
}

/** CHAT_UX_SPEC.md §11.3: the no-model card; the one next action is Models (AL-09b's destination). */
@Composable
internal fun NoModelNotice(shell: SkeinShellState) {
    SkeinNotice(
        title = "Add a model to start",
        body = "Skein runs AI models on this device. Nothing leaves it.",
        action = SkeinAction("Choose a model") { shell.navigate { switchTo(it, Destination.MODELS) } },
        modifier = Modifier.padding(SkeinSpacing.space16),
    )
}

/**
 * The context inspector (CHAT_UX_SPEC.md §17): what the chat's last answer used.
 * On one pane after a fold it is the one-line peek (§4.2 "collapsed into the chip row").
 * ponytail: only the Knowledge section, from the pipeline's last turn when it
 * was this chat's; the per-turn record and the other sections are C1 and Wave 7.
 */
@Composable
private fun ChatInspector(
    key: ChatContextKey,
    shell: SkeinShellState,
    deps: ChatEntryDeps,
) {
    val document = rememberEntryDocument(deps.repository, key.chatId.value)
    if (document is EntryDocument.Gone) {
        shell.GoneEntry(key.chatId.value, "This chat was deleted.", Destination.CHAT, "Go to Chats")
        return
    }
    if (document !is EntryDocument.Present) return
    val lastOutcome =
        deps.sendPipeline
            ?.lastOutcome
            ?.collectAsState()
            ?.value
    val turn =
        deps.turns
            ?.state(key.chatId.value)
            ?.collectAsState()
            ?.value
    val outcome = turn?.outcome ?: lastOutcome?.takeIf { deps.turns == null && it.chatDocId == key.chatId.value }
    // Only passages that survived the prompt budget were supplied to the model.
    val items =
        outcome
            ?.assembled
            ?.citations
            ?.values
            ?.toList()
            .orEmpty()
    val sourceCount = items.distinctBy { it.docId }.size
    val knowledgeEnabled = ChatKnowledge.enabled(document.document)
    val inspectorScroll = rememberScrollState()
    if (LocalSheetMode.current == SheetMode.PEEK) {
        val knowledgeLabel = if (knowledgeEnabled) "Knowledge on" else "Knowledge off"
        val summary = if (outcome == null) knowledgeLabel else "$knowledgeLabel · ${sourcesLabel(sourceCount)}"
        SheetPeekRow(
            summary,
            Modifier.testTag(ChatEntryTestTags.INSPECTOR_PEEK),
        )
        return
    }
    val scope = rememberCoroutineScope()
    var saving by remember(key.chatId) { mutableStateOf(false) }
    var saveFailed by remember(key.chatId) { mutableStateOf(false) }
    val sources: (DocId) -> Unit =
        remember(key.chatId) { { docId -> shell.navigate { openSource(it, key.chatId, docId) } } }
    Column(Modifier.fillMaxSize().testTag(ChatEntryTestTags.INSPECTOR)) {
        shell.EntryTopBar(key, "Context")
        ContextPanel(
            items = items,
            onOpenSource = sources,
            knowledgeEnabled = knowledgeEnabled,
            hasTurn = outcome != null,
            scrollState = inspectorScroll,
            knowledgeChangePending = saving,
            knowledgeChangeFailed = saveFailed,
            onKnowledgeChange = { enabled ->
                if (!saving) {
                    saving = true
                    saveFailed = false
                    scope.launch {
                        try {
                            ChatKnowledge.setEnabled(deps.repository, key.chatId.value, enabled)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            saveFailed = true
                        } finally {
                            saving = false
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun sourcesLabel(count: Int) = if (count == 1) "1 source" else "$count sources"

/**
 * The chat history (CHAT_UX_SPEC.md §12) for the drawer and the Conversations
 * pane: every chat, newest activity first, the open one selected. A row opens
 * its chat (go to). ponytail: no preview line or answering ring until the
 * chat-summary projection (ask B7). Rename stays hidden until its dialog;
 * Delete is supplied by the shared confirmation requester (LC-22).
 */
@Composable
fun rememberChatHistory(
    repository: VaultRepository,
    shell: SkeinShellState,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = LocalLocale.current.platformLocale,
    now: () -> Long = System::currentTimeMillis,
    onDelete: ((DocId) -> Unit)? = null,
    turns: ChatTurnController? = null,
): List<ChatHistoryItem> {
    val chats by remember(repository) {
        repository.observeTimeline(TimelineFilter(kinds = setOf(DocumentKind.CHAT)), limit = HISTORY_LIMIT)
    }.collectAsState(emptyList())
    val open = shell.nav.stack(Destination.CHAT).firstNotNullOfOrNull { (it as? ChatKey)?.chatId?.value }
    val nowMillis = now()
    return chats.map { chat ->
        key(chat.id) {
            val disabledReason = if (onDelete != null) rememberChatDeleteDisabledReason(turns, chat.id) else null
            ChatHistoryItem(
                id = chat.id,
                title = chat.title.ifBlank { UNTITLED_CHAT },
                lastMessageAtMillis = chat.updatedAt,
                timeLabel = chatTimeLabel(chat.updatedAt, nowMillis, zone, locale),
                isSelected = chat.id == open,
                onOpen = { shell.open(chat) },
                onDelete = onDelete?.let { request -> { requestChatDelete(chat.id, turns, request) } },
                deleteDisabledReason = disabledReason,
            )
        }
    }
}

/** §12.3: Today and Yesterday → "9:41"; the previous 7 days → "Tue"; older → "3 Sep". */
internal fun chatTimeLabel(
    millis: Long,
    nowMillis: Long,
    zone: ZoneId,
    locale: Locale,
): String {
    val at = Instant.ofEpochMilli(millis).atZone(zone)
    val age = ChronoUnit.DAYS.between(at.toLocalDate(), Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate())
    val format =
        when {
            age <= 1 -> DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
            age <= 7 -> DateTimeFormatter.ofPattern("EEE")
            else -> DateTimeFormatter.ofPattern("d MMM")
        }
    return at.format(format.withLocale(locale))
}

internal const val UNTITLED_CHAT = "Untitled chat"

/** The drawer and pane show the most recent chats; older ones wait for chat search (§12.4). */
private const val HISTORY_LIMIT = 200
