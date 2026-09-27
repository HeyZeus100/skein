// skein-xtov.24.4 (AL-05, throwaway): the entry provider. Today's real
// ChatScreen, NoteTab and GraphScreen are hosted unchanged; the inspector,
// Connections and node detail are stand-ins (their real content is Wave 4-7
// work). Every entry carries an EntryProbe: a T3 ViewModel with an instance
// serial and a T2 saveable counter, which is what the gate tests measure.
package app.skein.prototype.nav3

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.SupportingPaneSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavEntry
import app.skein.feature.chat.ChatScreen
import app.skein.feature.chat.ChatViewModel
import app.skein.feature.chat.TabController
import app.skein.feature.editor.autocomplete.Suggestion
import app.skein.feature.editor.notetab.NoteTab
import app.skein.feature.graph.GraphScreen
import app.skein.feature.shell.layout.SecondarySurface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Test instrumentation: how many entry ViewModels were created and cleared, per content key. */
object ProbeLedger {
    private val serial = AtomicInteger()
    val created = ConcurrentHashMap<String, AtomicInteger>()
    val cleared = ConcurrentHashMap<String, AtomicInteger>()

    internal fun onCreated(contentKey: String): Int {
        created.getOrPut(contentKey) { AtomicInteger() }.incrementAndGet()
        return serial.incrementAndGet()
    }

    internal fun onCleared(contentKey: String) {
        cleared.getOrPut(contentKey) { AtomicInteger() }.incrementAndGet()
    }

    fun createdCount(contentKey: String): Int = created[contentKey]?.get() ?: 0

    fun clearedCount(contentKey: String): Int = cleared[contentKey]?.get() ?: 0

    fun reset() {
        created.clear()
        cleared.clear()
    }
}

/** The T3 holder every entry gets. [confirmDelete] stands in for a dialog target (§7.3 row 17). */
class ProbeViewModel(
    private val contentKey: String,
) : ViewModel() {
    val serial: Int = ProbeLedger.onCreated(contentKey)
    var confirmDelete: SkeinId? by mutableStateOf(null)
    var hadFocus: Boolean = false
    val standInDraft = TextFieldState()

    override fun onCleared() {
        confirmDelete = null
        standInDraft.clearText()
        ProbeLedger.onCleared(contentKey)
    }
}

/**
 * Today's ChatViewModel held in T3 with `viewModelScope` (§5.6 #4's minimum; CHAT_UX_SPEC.md
 * C1 moves the turn to T5). It shows what the entry store buys a turn across a recreation.
 */
class TurnHost(
    chatId: SkeinId,
    deps: ProtoDeps,
) : ViewModel() {
    val chat = ChatViewModel(chatId.value, deps.vault, deps.sendPipeline, { _, _, _ -> "" }, viewModelScope)
}

const val T3_SEND_TAG = "t3-send"

fun probeTag(contentKey: String) = "probe/$contentKey"

fun bumpTag(contentKey: String) = "bump/$contentKey"

@Composable
private fun EntryProbe(contentKey: String): ProbeViewModel {
    val vm = viewModel { ProbeViewModel(contentKey) }
    var t2 by rememberSaveable { mutableIntStateOf(0) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("vm=${vm.serial} t2=$t2", Modifier.testTag(probeTag(contentKey)))
        TextButton(onClick = { t2++ }, modifier = Modifier.testTag(bumpTag(contentKey))) { Text("+") }
    }
    return vm
}

private val noSuggestions: suspend (String) -> List<Suggestion> = { emptyList() }

const val OPEN_CONTEXT_TAG = "open-context"
const val OPEN_CONNECTIONS_TAG = "open-connections"
const val SELECT_NODE_TAG = "select-node"
const val DELETE_TAG = "delete"
const val DELETE_DIALOG_TAG = "delete-dialog"
const val DETAIL_PLACEHOLDER_TAG = "detail-placeholder"

fun chatPaneTag(id: SkeinId) = "chat-pane/${id.value}"

fun chatRowTag(id: SkeinId) = "chat-row/${id.value}"

fun notePaneTag(id: SkeinId) = "note-pane/${id.value}"

fun noteRowTag(id: SkeinId) = "note-row/${id.value}"

const val INSPECTOR_TAG = "inspector"
const val CONNECTIONS_TAG = "connections"
const val NODE_TAG = "node-detail"
const val GRAPH_PANE_TAG = "graph-pane"
const val SOURCE_TAG = "source"

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
fun protoEntryProvider(
    deps: ProtoDeps,
    nav: ProtoNavigationState,
    sheets: SheetPresentation,
): (ProtoKey) -> NavEntry<ProtoKey> {
    // Opens a sheet surface; on one pane it opens expanded, because the user asked for it (§2.5).
    fun openSheet(
        key: ProtoKey,
        singlePane: Boolean,
    ) {
        nav.push(key)
        if (singlePane) sheets.expand(key.contentKey)
    }

    // No `entryProvider {}` DSL: its fallback throws "Unknown screen $key" (an id in an
    // exception message, M13); a `when` over the sealed keys needs no fallback at all.
    return { key ->
        when (key) {
            ChatHomeKey ->
                NavEntry(
                    key,
                    key.contentKey,
                    ListDetailSceneStrategy.listPane(Destination.CHAT) {
                        Text("New chat", Modifier.testTag(DETAIL_PLACEHOLDER_TAG))
                    },
                ) {
                    Column(Modifier.fillMaxSize()) {
                        EntryProbe(key.contentKey)
                        deps.chats.forEach { id ->
                            val selected =
                                nav.stacks
                                    .getValue(
                                        Destination.CHAT,
                                    ).any { it is ChatKey && it.chatId == id }
                            Text(
                                title(deps, id),
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { nav.select(ChatKey(id)) }
                                    .semantics { this.selected = selected }
                                    .testTag(chatRowTag(id))
                                    .padding(12.dp),
                            )
                        }
                    }
                }
            is ChatKey ->
                NavEntry(key, key.contentKey, ListDetailSceneStrategy.detailPane(Destination.CHAT)) {
                    val layout = LocalSkeinLayout.current
                    val single = layout.maxPanes == 1
                    val tabs = remember(nav, key) { TabController { docId, _, _ -> openSource(nav, key, docId) } }
                    Column(Modifier.fillMaxSize().testTag(chatPaneTag(key.chatId))) {
                        Row {
                            EntryProbe(key.contentKey)
                            TextButton(
                                onClick = { openSheet(ChatContextKey(key.chatId), single) },
                                modifier = Modifier.testTag(OPEN_CONTEXT_TAG),
                            ) { Text("Context") }
                        }
                        StandInComposer(key.contentKey)
                        val turns = viewModel { TurnHost(key.chatId, deps) }
                        TextButton(
                            onClick = { turns.chat.send("q") },
                            modifier = Modifier.testTag(T3_SEND_TAG),
                        ) { Text("Send (T3)") }
                        ChatScreen(
                            docId = key.chatId.value,
                            vaultRepository = deps.vault,
                            sendPipeline = deps.sendPipeline,
                            tabController = tabs,
                            wikilinkSuggest = noSuggestions,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            is ChatContextKey ->
                NavEntry(
                    key,
                    key.contentKey,
                    ListDetailSceneStrategy.extraPane(Destination.CHAT) +
                        SheetMetadata.sheet(SecondarySurface.CONTEXT_INSPECTOR),
                ) { SecondaryStandIn(key.contentKey, INSPECTOR_TAG, "Context", "Context · 2 notes · 3 sources ▴") }
            is ChatSourceKey ->
                NavEntry(key, key.contentKey, ListDetailSceneStrategy.extraPane(Destination.CHAT)) {
                    SecondaryStandIn(key.contentKey, SOURCE_TAG, "Source", "Source")
                }
            is KnowledgeHomeKey ->
                NavEntry(
                    key,
                    key.contentKey,
                    ListDetailSceneStrategy.listPane(Destination.KNOWLEDGE) {
                        Text("Pick a note", Modifier.testTag(DETAIL_PLACEHOLDER_TAG))
                    },
                ) {
                    Column(Modifier.fillMaxSize()) {
                        EntryProbe(key.contentKey)
                        deps.notes.forEach { id ->
                            val selected =
                                nav.stacks.getValue(Destination.KNOWLEDGE).any {
                                    it is NoteKey &&
                                        it.docId == id
                                }
                            Text(
                                title(deps, id),
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { nav.select(NoteKey(id)) }
                                    .semantics { this.selected = selected }
                                    .testTag(noteRowTag(id))
                                    .padding(12.dp),
                            )
                        }
                    }
                }
            is NoteKey ->
                NavEntry(key, key.contentKey, ListDetailSceneStrategy.detailPane(Destination.KNOWLEDGE)) {
                    val single = LocalSkeinLayout.current.maxPanes == 1
                    Column(Modifier.fillMaxSize().testTag(notePaneTag(key.docId))) {
                        Row {
                            val probe = EntryProbe(key.contentKey)
                            TextButton(
                                onClick = { openSheet(ConnectionsKey(key.docId), single) },
                                modifier = Modifier.testTag(OPEN_CONNECTIONS_TAG),
                            ) { Text("Connections") }
                            TextButton(
                                onClick = { probe.confirmDelete = key.docId },
                                modifier = Modifier.testTag(DELETE_TAG),
                            ) {
                                Text("Delete")
                            }
                        }
                        NoteTab(
                            docId = key.docId.value,
                            vaultRepository = deps.vault,
                            indexStore = deps.indexStore,
                            modifier = Modifier.weight(1f),
                            onOpenDocument = { id, _ -> SkeinId.parse(id)?.let { nav.push(NoteKey(it)) } },
                        )
                        DeleteDialog(key)
                    }
                }
            is ConnectionsKey ->
                NavEntry(
                    key,
                    key.contentKey,
                    ListDetailSceneStrategy.extraPane(Destination.KNOWLEDGE) +
                        SheetMetadata.sheet(SecondarySurface.CONNECTIONS),
                ) { SecondaryStandIn(key.contentKey, CONNECTIONS_TAG, "Connections", "Connections · 1 link ▴") }
            is GraphKey ->
                NavEntry(key, key.contentKey, SupportingPaneSceneStrategy.mainPane(Destination.GRAPH)) {
                    val focus = key.focusDocId ?: deps.notes.first()
                    Box(Modifier.fillMaxSize().testTag(GRAPH_PANE_TAG)) {
                        GraphScreen(
                            docId = focus.value,
                            vaultRepository = deps.vault,
                            indexStore = deps.indexStore,
                            onOpenPreview = { id ->
                                SkeinId.parse(id)?.let { nav.select(GraphNodeKey(key.focusDocId, it)) }
                            },
                            onClose = { nav.back() },
                        )
                        val single = LocalSkeinLayout.current.maxPanes == 1
                        Row(Modifier.align(Alignment.TopStart)) {
                            EntryProbe(key.contentKey)
                            TextButton(
                                onClick = {
                                    val node = GraphNodeKey(key.focusDocId, deps.notes.last())
                                    nav.select(node)
                                    if (single) sheets.expand(node.contentKey)
                                },
                                modifier = Modifier.testTag(SELECT_NODE_TAG),
                            ) { Text("Select") }
                        }
                    }
                }
            is GraphNodeKey ->
                NavEntry(
                    key,
                    key.contentKey,
                    SupportingPaneSceneStrategy.supportingPane(Destination.GRAPH) +
                        SheetMetadata.sheet(SecondarySurface.NODE_DETAIL),
                ) { SecondaryStandIn(key.contentKey, NODE_TAG, "Node", "Node · 3 links ▴") }
        }
    }
}

private fun openSource(
    nav: ProtoNavigationState,
    chat: ChatKey,
    docId: String,
): String {
    // M2(a): a foreign (non-UUID) id never becomes a saved key.
    SkeinId.parse(docId)?.let { nav.push(ChatSourceKey(chat.chatId, it)) }
    return "source"
}

@Composable
private fun title(
    deps: ProtoDeps,
    id: SkeinId,
): String {
    val title by produceState("", id) {
        value =
            deps.vault
                .getDocument(id.value)
                ?.title
                .orEmpty()
    }
    return title
}

@Composable
private fun DeleteDialog(key: NoteKey) {
    val probe = viewModel { ProbeViewModel(key.contentKey) }
    val target = probe.confirmDelete ?: return
    AlertDialog(
        onDismissRequest = { probe.confirmDelete = null },
        confirmButton = { TextButton(onClick = { probe.confirmDelete = null }) { Text("Delete") } },
        text = {
            Text(
                "Delete this note? ${if (target == key.docId) "(this one)" else ""}",
                Modifier.testTag(DELETE_DIALOG_TAG),
            )
        },
    )
}

const val STANDIN_COMPOSER_TAG = "standin-composer"

/**
 * What AL-10 must add to ChatBottomBar, shown on a stand-in field: moving an
 * entry into another scene (Nav3 moves it with movableContentOf) detaches the
 * focused node, so focus drops. A T3 flag captured when the entry composes into
 * its new scene ([LocalSceneIdentity]), and one `requestFocus()` after it, brings it back
 * (§7.3 row 35). The draft itself is a T3 `TextFieldState`, never saveable (M3b).
 */
@Composable
private fun StandInComposer(contentKey: String) {
    val holder = viewModel { ProbeViewModel(contentKey) }
    val focus = remember { FocusRequester() }
    val scene = LocalSceneIdentity.current
    // Captured while composing into the new scene, i.e. before the move detaches the node.
    val hadFocus = remember(scene) { holder.hadFocus }
    LaunchedEffect(scene) { if (hadFocus) focus.requestFocus() }
    BasicTextField(
        state = holder.standInDraft,
        modifier =
            Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .onFocusChanged { holder.hadFocus = it.isFocused }
                .testTag(STANDIN_COMPOSER_TAG),
    )
}

@Composable
private fun SecondaryStandIn(
    contentKey: String,
    tag: String,
    title: String,
    peek: String,
) {
    Column(Modifier.fillMaxWidth().testTag(tag).padding(8.dp)) {
        Text(if (LocalSheetMode.current == SheetMode.PEEK) peek else title)
        EntryProbe(contentKey)
    }
}
