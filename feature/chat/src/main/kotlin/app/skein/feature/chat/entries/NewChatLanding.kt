// skein-xtov.24.8 (AL-09a): the landing, "What are you working on?"
// (ADAPTIVE_LAYOUT_SPEC.md §4.1; CHAT_UX_SPEC.md §11; IA §3.7). It is an
// unsaved new chat: no chat document exists until the first send, which
// creates it and hands the message to the new chat's screen (§11.4).
package app.skein.feature.chat.entries

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.skein.core.designsystem.components.SkeinListRow
import app.skein.core.designsystem.components.SkeinSectionHeader
import app.skein.core.designsystem.icons.SkeinIcons
import app.skein.core.designsystem.theme.SkeinSize
import app.skein.core.designsystem.theme.SkeinSpacing
import app.skein.core.model.ChatDraftKey
import app.skein.core.model.DocumentKind
import app.skein.core.model.NewDocument
import app.skein.core.model.TimelineFilter
import app.skein.core.navigation.ChatHomeKey
import app.skein.core.navigation.Destination
import app.skein.core.navigation.NewChatKey
import app.skein.core.navigation.ObjectKind
import app.skein.core.navigation.SkeinKey
import app.skein.feature.chat.ChatBottomBar
import app.skein.feature.chat.DraftLoadNotice
import app.skein.feature.chat.drafts.rememberDraftComposerState
import app.skein.feature.chat.importAttachment
import app.skein.feature.editor.autocomplete.Suggestion
import app.skein.feature.editor.entries.newNote
import app.skein.feature.shell.host.EntryTopBar
import app.skein.feature.shell.host.LocalSkeinWindowLayout
import app.skein.feature.shell.host.SkeinShellState
import app.skein.feature.shell.host.open
import app.skein.feature.shell.layout.SkeinNavContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * [key] is the entry showing it (the Chat root in a drawer, or a pushed
 * [app.skein.core.navigation.NewChatKey]); null when it is the Conversations
 * pane's detail placeholder, which has no navigation icon.
 *
 * ponytail: the composer's draft is composition state until AL-10's DraftStore,
 * and "Import file" waits for the Knowledge import flow (KNOWLEDGE_UX_SPEC.md §9.2).
 */
@Composable
internal fun NewChatLanding(
    key: SkeinKey?,
    shell: SkeinShellState,
    deps: ChatEntryDeps,
) {
    val layout = LocalSkeinWindowLayout.current
    val recentCount =
        when {
            layout.nav == SkeinNavContainer.DRAWER -> RECENT_COMPACT
            layout.maxPanes > 1 -> RECENT_EXPANDED
            else -> RECENT_DEFAULT
        }
    val repository = deps.repository
    val recent by remember(repository, recentCount) {
        repository.observeTimeline(TimelineFilter(kinds = RECENT_KINDS), limit = recentCount)
    }.collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    val spaceId = shell.nav.space?.value ?: deps.defaultSpaceId
    // The root has one stable, encrypted draft per Space, including process restoration.
    val draftKey = spaceId?.let { ChatDraftKey.New(it, (key as? NewChatKey)?.draftId?.value ?: ROOT_DRAFT_ID) }
    val composer =
        if (deps.drafts != null &&
            draftKey != null
        ) {
            rememberDraftComposerState(deps.drafts, draftKey)
        } else {
            null
        }
    var sending by remember(draftKey) { mutableStateOf(false) }
    var sendFailed by remember(draftKey) { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().testTag(ChatEntryTestTags.LANDING)) {
        // As the placeholder beside Conversations it follows the Chat root's rule: no navigation icon.
        shell.EntryTopBar(key ?: ChatHomeKey, "New chat")
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = SkeinSpacing.space16),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = SkeinSize.readingMax).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(SkeinSpacing.space8),
            ) {
                Text(
                    "What are you working on?",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(top = SkeinSpacing.space24).semantics { heading() },
                )
                Text(
                    "Ask Skein, search your knowledge, or continue something recent.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!deps.hasModel) NoModelNotice(shell)
                SkeinListRow(
                    title = "New note",
                    leadingIcon = SkeinIcons.NewNote,
                    onClick = { shell.newNote() },
                )
                SkeinListRow(
                    title = "Search knowledge",
                    leadingIcon = SkeinIcons.Search,
                    onClick = { shell.navigate { switchTo(it, Destination.KNOWLEDGE) } },
                )
                if (recent.isNotEmpty()) {
                    SkeinSectionHeader("Recent")
                    val now = deps.knowledge.clock()
                    val zone = deps.knowledge.zone
                    val locale = LocalLocale.current.platformLocale
                    recent.forEach { document ->
                        val isChat = document.kind == DocumentKind.CHAT
                        SkeinListRow(
                            title = document.title.ifBlank { if (isChat) UNTITLED_CHAT else "Untitled" },
                            leadingIcon = if (isChat) SkeinIcons.Chat else SkeinIcons.Note,
                            trailingMeta = chatTimeLabel(document.updatedAt, now, zone, locale),
                            onClick = { shell.open(document) },
                        )
                    }
                }
            }
        }
        val pipeline = deps.sendPipeline
        if (pipeline != null && deps.hasModel) {
            DraftLoadNotice(composer)
            if (sendFailed) Text("Couldn't send this message. Try again.", Modifier.padding(SkeinSpacing.space16))
            ChatBottomBar(
                isGenerating = false,
                composerState = composer,
                enabled = !sending && (deps.turns == null || composer != null),
                onSend = { text ->
                    val origin = shell.nav
                    val selectedSpace = origin.space?.value
                    val turns = deps.turns
                    if (turns != null) {
                        val snapshot = composer?.snapshot
                        if (snapshot != null &&
                            draftKey != null &&
                            !sending &&
                            draftKey.spaceId == (selectedSpace ?: deps.defaultSpaceId)
                        ) {
                            sending = true
                            sendFailed = false
                            val committed =
                                turns.enqueueNew(
                                    draftKey,
                                    snapshot.version,
                                    snapshot.draft.text,
                                    provisionalTitle(snapshot.draft.text, deps.knowledge.clock()),
                                )
                            scope.launch {
                                try {
                                    val chatId = committed.await()
                                    if (shell.nav ===
                                        origin
                                    ) {
                                        shell.navigate { openDocument(it, chatId, ObjectKind.CHAT) }
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    sendFailed = true
                                } finally {
                                    sending = false
                                }
                            }
                        }
                    } else {
                        scope.launch {
                            // §11.4: create on first send; the new chat's screen sends the message.
                            val chat =
                                repository.createDocument(
                                    NewDocument(
                                        DocumentKind.CHAT,
                                        provisionalTitle(text, deps.knowledge.clock()),
                                        bodyMd = "",
                                        personaId = selectedSpace,
                                    ),
                                )
                            deps.handoff.put(chat.id, text)
                            shell.navigate { openDocument(it, chat.id, ObjectKind.CHAT) }
                        }
                    }
                },
                onCancel = {},
                wikilinkSuggest = { query -> repository.searchTitles(query).map { Suggestion(it.title) } },
                onAttach = { name, mime, input ->
                    val personaId = shell.nav.space?.value
                    deps.importService?.let { importAttachment(it, name, mime, input, personaId) }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * CHAT_UX_SPEC.md §13.1's provisional title, deterministic and without a model
 * call: the first sentence (two if the first is under three words), Markdown and
 * `[[ ]]` stripped, cut at 48 characters on a word boundary, never "Chat".
 * ponytail: a bare URL is not reduced to its host yet (LC-22 owns titles).
 */
internal fun provisionalTitle(
    message: String,
    nowMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    val plain =
        message
            .trim()
            .replace(LEADING_COMMAND, "")
            .replace(CODE_FENCE, " ")
            .replace(WIKILINK, "$1")
            .replace(LIST_MARKER, "")
            .replace(MARKDOWN_MARKS, "")
    val sentences = plain.split(SENTENCE_END).map(String::trim).filter(String::isNotEmpty)
    var title = sentences.firstOrNull().orEmpty()
    if (title.split(' ').size < MIN_WORDS && sentences.size > 1) title += " " + sentences[1]
    title = title.replace(WHITESPACE, " ").trim()
    if (title.length > TITLE_MAX) {
        val cut = title.lastIndexOf(' ', TITLE_MAX)
        title = title.take(if (cut >= TITLE_MAX * 6 / 10) cut else TITLE_MAX)
    }
    title = title.trimEnd('.', ',', ';', ':', '!', ' ').replaceFirstChar { it.uppercase(locale) }
    if (title.isNotEmpty()) return title
    val at = Instant.ofEpochMilli(nowMillis).atZone(zone)
    return "Chat from " + at.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale))
}

private val RECENT_KINDS = setOf(DocumentKind.CHAT, DocumentKind.NOTE, DocumentKind.AIOUT)
private const val RECENT_COMPACT = 3
private const val RECENT_DEFAULT = 5
private const val RECENT_EXPANDED = 8

private val LEADING_COMMAND = Regex("""^/\S+\s*""")
private val CODE_FENCE = Regex("(?s)```.*?(```|$)")
private val WIKILINK = Regex("""\[\[([^\]|]+)(\|[^\]]*)?]]""")
private val LIST_MARKER = Regex("""(?m)^\s*([-*+]|\d+\.)\s+""")
private val MARKDOWN_MARKS = Regex("""[*_`#>]""")
private val SENTENCE_END = Regex("""(?<=[.?!])\s+|\n+""")
private val WHITESPACE = Regex("""\s+""")
private const val MIN_WORDS = 3
private const val TITLE_MAX = 48

private const val ROOT_DRAFT_ID = "00000000-0000-0000-0000-000000000001"
