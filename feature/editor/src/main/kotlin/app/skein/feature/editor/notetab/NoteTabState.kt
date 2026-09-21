// `NoteTabState` (plan `E6.I9`, bd `skein-u01`, spec §8.5): state holder for
// the note tab — the editor, the collapsible backlinks drawer, and the
// header (title, ✦ graph button). This is the one place in `:feature:editor`
// that touches `VaultRepository`/`IndexStore` directly; every other surface
// in this module (`EditorState`, `BacklinksState`) stays vault-pure by
// design (see their file headers) and hands resolution back out through a
// callback for exactly this class to answer.

package app.skein.feature.editor.notetab

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.input.TextFieldValue
import app.skein.core.vault.codec.Frontmatter
import app.skein.feature.editor.AutosaveStatus
import app.skein.feature.editor.EditorState
import app.skein.feature.editor.WikilinkTarget
import app.skein.feature.editor.backlinks.BacklinksState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import us.aherrera.skein.core.model.DocId
import us.aherrera.skein.core.model.DocumentKind
import us.aherrera.skein.core.model.FrontmatterKeys
import us.aherrera.skein.core.model.IndexStore
import us.aherrera.skein.core.model.NewDocument
import us.aherrera.skein.core.model.VaultRepository
import java.time.Duration

/**
 * @param docId the note this tab shows. One [NoteTabState] instance is
 *   scoped to one document for its lifetime — a host reusing the tab UI for
 *   a different doc (e.g. following a wikilink into the *same* tab rather
 *   than opening a new one) constructs a fresh instance keyed by [docId]
 *   (see [app.skein.feature.editor.notetab.NoteTab]'s `remember(docId, ...)`),
 *   the same pattern `rememberBacklinksState` uses for cross-tab reuse.
 * @param vaultRepository already-open; used for `getDocument` (initial
 *   load), `updateBody` (autosave + title edits), and `findByTitle`/
 *   `createDocument` (wikilink open-or-create, spec §8.5).
 * @param indexStore already-open; handed straight through to [backlinksState].
 * @param scope owner of every suspend operation this state kicks off — a
 *   Compose `rememberCoroutineScope()` in the app, `backgroundScope` in
 *   tests. Also [EditorState.autosaveScope] and [BacklinksState]'s scope,
 *   so everything this tab owns tears down together.
 * @param onPinRequested fired exactly once, the first time the editor's
 *   text actually changes (not on the initial load) — the host wires this
 *   to `TabsState.pin(tabId)` (plan `E6.I9` acceptance: "typing in the
 *   editor calls `TabController.pin(tabId)` once").
 * @param onOpenDocument fired with a resolved id + display title whenever
 *   this tab wants another document opened — from a backlink row tap or a
 *   wikilink click. The host decides what "open" means (a preview tab,
 *   pinned, etc.) — this state holder only resolves *which* document.
 */
public class NoteTabState(
    public val docId: DocId,
    private val vaultRepository: VaultRepository,
    indexStore: IndexStore,
    private val scope: CoroutineScope,
    public val onPinRequested: () -> Unit = {},
    public val onOpenDocument: (DocId, String) -> Unit = { _, _ -> },
    private val autosaveDebounce: Duration = Duration.ofMillis(500),
) {
    /** Current title — seeded from the loaded document, then editable via [onTitleChange]. */
    public var title: String by mutableStateOf("")
        private set

    /** `true` until the initial [VaultRepository.getDocument] read completes. */
    public var loading: Boolean by mutableStateOf(true)
        private set

    /** Non-null if [docId] could not be loaded (e.g. deleted out from under an open tab). */
    public var loadError: String? by mutableStateOf(null)
        private set

    /**
     * The editor's state. Starts as an empty placeholder (never shown —
     * [NoteTab] gates the editor on [loading]) and is replaced once by the
     * real, document-seeded instance when [load] completes; never mutated
     * in place after that, so a stale reference from before load can't leak
     * into an autosave for a different document's content.
     */
    public var editorState: EditorState by mutableStateOf(EditorState(autosaveScope = scope))
        private set

    /** Backlinks drawer state (bd `skein-9jj`) over the same repository/index pair. */
    public val backlinksState: BacklinksState =
        BacklinksState(
            initialDocId = docId,
            vaultRepository = vaultRepository,
            indexStore = indexStore,
            scope = scope,
            onOpen = { openedId -> resolveTitleThenOpen(openedId) },
        )

    init {
        scope.launch { load() }
    }

    /** Inline title edit (header, spec §8.5) — persisted immediately against the current body text. */
    public fun onTitleChange(newTitle: String) {
        title = newTitle
        scope.launch {
            // `editorState.source` is the *whole* buffer (frontmatter block
            // + body, see [load]'s kdoc) — split it before writing, or a
            // title edit would silently smuggle the frontmatter header into
            // `bodyMd`.
            val (_, body) = Frontmatter.parse(editorState.source)
            vaultRepository.updateBody(docId, newTitle, body)
        }
    }

    /**
     * Flush hook for the host's flush registry
     * (`docs/design/LOCK_POLICY_INDEXING.md` §4.3,
     * `app.skein.feature.shell.tabs.FlushRegistry`): delegates straight to
     * [EditorState.flush]. Bounded by [deadline]; returns `false` if it
     * didn't complete in time.
     */
    public suspend fun flush(deadline: Duration = Duration.ofSeconds(2)): Boolean = editorState.flush(deadline)

    /**
     * bd `skein-6rr` (`E7.I3`): the editor's buffer is the document's
     * frontmatter block *and* body concatenated — `Frontmatter.render`
     * (`:core:vault`) — so [EditorState] can hide/show the block and guard
     * its `id:` line without `NoteTabState` (or `EditorState` itself)
     * needing a second, parallel text field. `render` emits no `---`
     * header at all when [us.aherrera.skein.core.model.Document.frontmatter]
     * is empty, so a document without frontmatter seeds the editor with
     * exactly its body — byte-identical to pre-`E7.I3` behavior.
     */
    private suspend fun load() {
        loading = true
        loadError = null
        val document = vaultRepository.getDocument(docId)
        if (document == null) {
            loadError = "Note not found"
            loading = false
            return
        }
        title = document.title
        editorState =
            EditorState(
                initial = TextFieldValue(Frontmatter.render(document.frontmatter, document.bodyMd.orEmpty())),
                onLinkOpen = ::onWikilinkClicked,
                onSave = { value -> saveEditorValue(value) },
                autosaveDebounce = autosaveDebounce,
                autosaveScope = scope,
            )
        loading = false
        watchFirstEdit()
    }

    /**
     * Splits the editor's combined buffer back into frontmatter + body
     * (`Frontmatter.parse`) and writes each half through its own
     * `VaultRepository` call. `id` is force-pinned back to [docId] right
     * before the write — belt-and-suspenders alongside `EditorState`'s
     * `ProtectedIdGuard`, so a changed (or, per that guard's deliberately
     * narrow scope, even a structurally-removed) id can never reach the
     * vault. A document with no frontmatter block parses to an empty
     * [JsonObject] and is left alone — no `updateFrontmatter` call, exactly
     * the pre-`E7.I3` single-`updateBody` write.
     */
    private suspend fun saveEditorValue(value: TextFieldValue) {
        val (frontmatter, body) = Frontmatter.parse(value.text)
        if (frontmatter.isNotEmpty()) {
            val pinned: JsonObject =
                buildJsonObject {
                    frontmatter.forEach { (key, element) -> if (key != FrontmatterKeys.ID) put(key, element) }
                    put(FrontmatterKeys.ID, JsonPrimitive(docId))
                }
            vaultRepository.updateFrontmatter(docId, pinned)
        }
        vaultRepository.updateBody(docId, title, body)
    }

    /**
     * A real user edit flips [EditorState.autosaveStatus] away from `IDLE`
     * (see that class's `onValueChange` — a no-op cursor move never does
     * this). The very first such transition after [load] is exactly "first
     * edit" per the plan's pin semantics.
     */
    private fun watchFirstEdit() {
        scope.launch {
            snapshotFlow { editorState.autosaveStatus.value }
                .filter { it != AutosaveStatus.IDLE }
                .first()
            onPinRequested()
        }
    }

    /** Backlink row tap hands back only a [DocId] — fetch its title for the tab label before opening. */
    private fun resolveTitleThenOpen(openedId: DocId) {
        scope.launch {
            val doc = vaultRepository.getDocument(openedId)
            onOpenDocument(openedId, doc?.title ?: openedId)
        }
    }

    /**
     * Spec §8.5 wikilink behavior: open the existing document by title, or
     * create a fresh empty `NOTE` if none exists yet. `:feature:editor`
     * never does this fetch itself outside this class (see
     * `EditorState.onLinkOpen`'s kdoc) — a click is an explicit user
     * action, not a side effect of typing.
     */
    private fun onWikilinkClicked(target: WikilinkTarget) {
        scope.launch {
            val existing = vaultRepository.findByTitle(target.title)
            val doc =
                existing
                    ?: vaultRepository.createDocument(
                        NewDocument(kind = DocumentKind.NOTE, title = target.title, bodyMd = ""),
                    )
            onOpenDocument(doc.id, doc.title)
        }
    }
}
