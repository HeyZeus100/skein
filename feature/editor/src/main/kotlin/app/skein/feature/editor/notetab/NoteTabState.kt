// `NoteTabState` (plan `E6.I9`, bd `skein-u01`, spec §8.5): state holder for
// the note tab — the editor, the collapsible backlinks drawer, and the
// header (title, ✦ graph button). This is the one place in `:feature:editor`
// that touches `VaultRepository`/`IndexStore` directly; every other surface
// in this module (`EditorState`, `BacklinksState`) stays vault-pure by
// design (see their file headers) and hands resolution back out through a
// callback for exactly this class to answer.

package app.skein.feature.editor.notetab

import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import app.skein.core.model.DocId
import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.FrontmatterKeys
import app.skein.core.model.IndexStore
import app.skein.core.model.NewDocument
import app.skein.core.model.VaultRepository
import app.skein.core.vault.codec.Frontmatter
import app.skein.core.vault.export.ExportServiceImpl
import app.skein.core.vault.transfer.ImportedLinkTargets
import app.skein.feature.editor.EditorState
import app.skein.feature.editor.WikilinkTarget
import app.skein.feature.editor.autocomplete.Suggestion
import app.skein.feature.editor.autocomplete.TitleMatcher
import app.skein.feature.editor.backlinks.BacklinksState
import app.skein.feature.editor.share.SaveAsFormat
import app.skein.feature.editor.share.SaveAsIntents
import app.skein.feature.editor.share.ShareIntents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.OutputStream
import java.time.Duration

/**
 * @param docId the note this tab shows. One [NoteTabState] instance is
 *   scoped to one document for its lifetime — a host reusing the tab UI for
 *   a different doc (e.g. following a wikilink into the *same* tab rather
 *   than opening a new one) constructs a fresh instance keyed by [docId]
 *   (see [app.skein.feature.editor.notetab.NoteTab]'s `remember(docId, ...)`),
 *   the same pattern `rememberBacklinksState` uses for cross-tab reuse.
 * @param vaultRepository already-open; used for `getDocument` (initial
 *   load), `replaceBody` (autosave), `renameDocument` (title edits), and `findByTitle`/
 *   `createDocument` (wikilink open-or-create, spec §8.5).
 * @param indexStore already-open; handed straight through to [backlinksState].
 * @param scope owner of every suspend operation this state kicks off — a
 *   Compose `rememberCoroutineScope()` in the app, `backgroundScope` in
 *   tests. Also [EditorState.autosaveScope] and [BacklinksState]'s scope,
 *   so everything this tab owns tears down together.
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
    public val onOpenDocument: (DocId, String) -> Unit = { _, _ -> },
    private val autosaveDebounce: Duration = Duration.ofMillis(500),
    noteDeletions: NoteDeletionRegistry? = null,
) {
    private val writeMutex = Mutex()

    @Volatile
    private var deletionBlocked = false

    private var lastSavedTitle = ""
    private val admission = Any()
    private var pauseGeneration = 0L
    private val detachedSources = mutableSetOf<DocId>()

    /** Stops title writes and editor saves synchronously, including later dispose/lock flushes. */
    internal fun pauseForDeletion() {
        synchronized(admission) {
            pauseGeneration++
            deletionBlocked = true
            editorState.pauseForDeletion()
        }
    }

    internal suspend fun awaitDeletionIdle() {
        loadJob.join()
        editorState.awaitDeletionIdle()
        writeMutex.withLock { }
    }

    internal fun resumeAfterDeletionFailure() {
        if (!scope.coroutineContext.isActive) return
        deletionBlocked = false
        editorState.resumeAfterDeletionFailure()
    }

    internal suspend fun flushAfterDeletionFailure() {
        try {
            saveTitle()
        } finally {
            flush()
        }
    }

    /** Persist surviving drafts while their old source is still live, before the file transaction. */
    internal suspend fun prepareSourceDetachment(sourceId: DocId) {
        awaitDeletionIdle()
        writeMutex.withLock {
            scope.coroutineContext.ensureActive()
            check(deletionBlocked)
            val saved =
                vaultRepository.transaction {
                    val current = checkNotNull(vaultRepository.getDocument(docId))
                    check(current.kind == DocumentKind.AIOUT)
                    check(current.frontmatter[FrontmatterKeys.SOURCE] == JsonPrimitive(sourceId))
                    check(vaultRepository.getDocument(sourceId)?.kind == DocumentKind.ATTACHMENT)
                    scope.coroutineContext.ensureActive()
                    val (frontmatter, body) =
                        Frontmatter.parse(
                            mergeCurrentDocument(current, preserveSourceEdit = true),
                        )
                    if (frontmatter != current.frontmatter) vaultRepository.updateFrontmatter(docId, frontmatter)
                    if (body != current.bodyMd) vaultRepository.replaceBody(docId, body)
                    if (title != lastSavedTitle) vaultRepository.renameDocument(docId, title)
                    scope.coroutineContext.ensureActive()
                    checkNotNull(vaultRepository.getDocument(docId))
                }
            // The normal editor remains paused. Only this acknowledged precommit save
            // may clear its dirty state; a later reload/lock cannot discard unsaved bytes.
            synchronized(admission) {
                val persisted = Frontmatter.render(saved.frontmatter, saved.bodyMd.orEmpty())
                editorState.rebaseAfterMetadataChange(persisted) { _, _ -> persisted }
                if (title == lastSavedTitle) title = saved.title
                lastSavedTitle = saved.title
            }
        }
    }

    internal fun hasPendingEdits(): Boolean = title != lastSavedTitle || editorState.hasPendingEdits()

    internal fun sourceDetachmentReady(): Boolean =
        scope.coroutineContext.isActive &&
            loadJob.isCompleted &&
            !writeMutex.isLocked &&
            editorState.isReservedAndSaved() &&
            title == lastSavedTitle

    private fun mergeCurrentDocument(
        current: Document,
        preserveSourceEdit: Boolean,
    ): String =
        editorState.mergePendingSource { localText, savedText ->
            val (local, body) = Frontmatter.parse(localText)
            val (saved, savedBody) = Frontmatter.parse(savedText)
            val merged = current.frontmatter.toMutableMap()
            for (key in local.keys + saved.keys) {
                if (key == FrontmatterKeys.ID || (key == FrontmatterKeys.SOURCE && !preserveSourceEdit)) continue
                if (local[key] != saved[key]) {
                    local[key]?.let { merged[key] = it } ?: merged.remove(key)
                }
            }
            val retainedBody = if (body == savedBody) current.bodyMd.orEmpty() else body
            Frontmatter.render(JsonObject(merged), retainedBody)
        }

    internal fun sourceWasDetached(sourceId: DocId) {
        synchronized(admission) { detachedSources += sourceId }
    }

    /** New panes also re-read after any in-flight initial load; no stale snapshot can unblock them. */
    internal fun scheduleSourceReload() {
        scope.launch {
            if (withTimeoutOrNull(2_000) { reloadAfterSourceDetachment() } != true) {
                loadError = "Couldn't refresh this item. Reopen it before editing."
                loading = false
            }
        }
    }

    /** Keep drafts, but use current persisted metadata and never revive a detached source association. */
    internal suspend fun reloadAfterSourceDetachment(): Boolean {
        val generation = synchronized(admission) { pauseGeneration }
        try {
            awaitDeletionIdle()
            writeMutex.withLock {
                scope.coroutineContext.ensureActive()
                val current = checkNotNull(vaultRepository.getDocument(docId))
                check(current.kind == DocumentKind.AIOUT)
                check(!hasDetachedSource(current.frontmatter))
                scope.coroutineContext.ensureActive()
                synchronized(admission) {
                    if (generation != pauseGeneration) return false
                    val merged = mergeCurrentDocument(current, preserveSourceEdit = false)
                    editorState.rebaseAfterMetadataChange(
                        Frontmatter.render(current.frontmatter, current.bodyMd.orEmpty()),
                    ) { _, _ -> merged }
                    if (title == lastSavedTitle) title = current.title
                    lastSavedTitle = current.title
                    loadError = null
                    deletionBlocked = false
                    editorState.resumeAfterDeletionFailure()
                }
            }
            return true
        } catch (error: Exception) {
            if (error is CancellationException && scope.coroutineContext.isActive) throw error
            loadError = "Couldn't refresh this item. Reopen it before editing."
            return false
        }
    }

    private fun hasDetachedSource(frontmatter: JsonObject): Boolean =
        synchronized(admission) {
            (frontmatter[FrontmatterKeys.SOURCE] as? JsonPrimitive)?.content in detachedSources
        }

    private fun withoutDetachedSource(text: String): String {
        if (synchronized(admission) { detachedSources.isEmpty() }) return text
        val (frontmatter, body) = Frontmatter.parse(text)
        return if (hasDetachedSource(frontmatter)) {
            Frontmatter.render(JsonObject(frontmatter - FrontmatterKeys.SOURCE), body)
        } else {
            text
        }
    }

    /** Current title — seeded from the loaded document, then editable via [onTitleChange]. */
    public var title: String by mutableStateOf("")
        private set

    /** `true` until the initial [VaultRepository.getDocument] read completes. */
    public var loading: Boolean by mutableStateOf(true)
        private set

    /** Non-null if [docId] could not be loaded (e.g. deleted out from under an open tab). */
    public var loadError: String? by mutableStateOf(null)
        private set

    /** A link that could not be resolved safely; leaves the current note open. */
    public var linkNotice: String? by mutableStateOf(null)
        private set

    public fun dismissLinkNotice() {
        linkNotice = null
    }

    /**
     * The editor's state. Starts as an empty placeholder (never shown —
     * [NoteTab] gates the editor on [loading]) and is replaced once by the
     * real, document-seeded instance when [load] completes; never mutated
     * in place after that, so a stale reference from before load can't leak
     * into an autosave for a different document's content.
     */
    public var editorState: EditorState by mutableStateOf(EditorState(autosaveScope = scope))
        private set

    /**
     * bd `skein-fay` (E6.I16): backs [shareAsTextIntent] and [writeSaveAs].
     * Depends only on [VaultRepository] (see that class's own header) so it
     * works unmodified against the [InMemoryVaultRepository][app.skein.testing.InMemoryVaultRepository]
     * fake this state's own tests already seed.
     */
    private val exportService = ExportServiceImpl(vaultRepository)

    /** Backlinks drawer state (bd `skein-9jj`) over the same repository/index pair. */
    public val backlinksState: BacklinksState =
        BacklinksState(
            initialDocId = docId,
            vaultRepository = vaultRepository,
            indexStore = indexStore,
            scope = scope,
            onOpen = { openedId -> resolveTitleThenOpen(openedId) },
        )

    private val loadJob: Job = scope.launch(start = CoroutineStart.LAZY) { load() }

    // Enroll before initial load or any caller can receive an editable state. A pane
    // entering between final validation and COMMIT therefore has no admitted draft.
    private val deletionRegistration = noteDeletions?.register(this)
    private val deletionScopeHandle =
        scope.coroutineContext[Job]?.invokeOnCompletion {
            deletionRegistration?.dispose()
        }

    init {
        loadJob.start()
    }

    internal fun unregisterDeletionWriter() {
        deletionRegistration?.dispose()
        deletionScopeHandle?.dispose()
    }

    /**
     * Inline title edit (header, spec §8.5) — persisted immediately, as a
     * rename: the title only, never the body, so it works the same for a
     * note, a chat or an attachment (OBJECT_LIFECYCLE_SPEC.md §6.3).
     *
     * The repository refuses a title its rules reject (blank or over-long
     * mid-typing: IllegalArgumentException) and a document deleted under an
     * open tab (NoSuchElementException). Neither may escape this launch: an
     * uncaught throw here crashes the app. The last accepted title stays.
     */
    public fun onTitleChange(newTitle: String) {
        if (deletionBlocked) return
        title = newTitle
        val generation = synchronized(admission) { pauseGeneration }
        scope.launch {
            if (synchronized(admission) { generation == pauseGeneration }) saveTitle()
        }
    }

    private suspend fun saveTitle() {
        writeMutex.withLock {
            if (deletionBlocked || title == lastSavedTitle) return@withLock
            val pending = title
            try {
                vaultRepository.renameDocument(docId, pending)
                lastSavedTitle = pending
            } catch (_: IllegalArgumentException) {
                // Not a valid name (yet); keep the stored one.
            } catch (_: NoSuchElementException) {
                // Deleted under this tab; nothing to rename.
            }
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
     * bd `skein-fay` v1 scope item 1 ("share as text"): builds the
     * `ACTION_SEND` intent (via [ShareIntents], pure Kotlin, unit-tested on
     * its own) from what is currently on screen — the live editor buffer,
     * not the last-flushed vault row — split back into body text with the
     * same [Frontmatter.parse] every save path uses.
     */
    public fun shareAsTextIntent(): Intent {
        val (_, body) = Frontmatter.parse(editorState.source)
        return ShareIntents.shareAsText(title = title, bodyMd = body)
    }

    /**
     * bd `skein-fay` v1 scope item 2 ("save as..."): builds the
     * `ACTION_CREATE_DOCUMENT` intent (via [SaveAsIntents]) for [format],
     * suggesting the current [title] as the filename.
     */
    public fun saveAsDocumentIntent(format: SaveAsFormat): Intent =
        SaveAsIntents.createDocument(format, suggestedTitle = title)

    /**
     * Streams this note into [outputStream] once `ACTION_CREATE_DOCUMENT`
     * (launched from [saveAsDocumentIntent]) has handed the caller a
     * destination `Uri` and it opened a stream on it. [flush]es any pending
     * autosave first so a just-typed edit is never silently missing from
     * the exported bytes; per `docs/design/POST_REVIEW_RESOLUTIONS.md`
     * §4.2 there is no local staging file here — [ExportServiceImpl] writes
     * straight into the caller-supplied stream.
     */
    public suspend fun writeSaveAs(
        format: SaveAsFormat,
        outputStream: OutputStream,
    ) {
        flush()
        when (format) {
            SaveAsFormat.MARKDOWN -> exportService.exportMarkdown(docId, outputStream)
            SaveAsFormat.DOCX -> exportService.exportDocx(docId, outputStream, template = null)
        }
    }

    /**
     * bd `skein-6rr` (`E7.I3`): the editor's buffer is the document's
     * frontmatter block *and* body concatenated — `Frontmatter.render`
     * (`:core:vault`) — so [EditorState] can hide/show the block and guard
     * its `id:` line without `NoteTabState` (or `EditorState` itself)
     * needing a second, parallel text field. `render` emits no `---`
     * header at all when [app.skein.core.model.Document.frontmatter]
     * is empty, so a document without frontmatter seeds the editor with
     * exactly its body — byte-identical to pre-`E7.I3` behavior.
     */
    private suspend fun load() =
        writeMutex.withLock {
            loading = true
            loadError = null
            val document = vaultRepository.getDocument(docId)
            if (document == null) {
                loadError = "Note not found"
                loading = false
                return@withLock
            }
            title = document.title
            lastSavedTitle = document.title
            editorState =
                EditorState(
                    initial = TextFieldValue(Frontmatter.render(document.frontmatter, document.bodyMd.orEmpty())),
                    onLinkOpen = ::onWikilinkClicked,
                    onSave = { value -> saveEditorValue(value) },
                    autosaveDebounce = autosaveDebounce,
                    autosaveScope = scope,
                    normalizeSource = ::withoutDetachedSource,
                )
            if (deletionBlocked) editorState.pauseForDeletion()
            loading = false
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
     * the pre-`E7.I3` single body write.
     */
    private suspend fun saveEditorValue(value: TextFieldValue) {
        writeMutex.withLock {
            if (deletionBlocked) return@withLock
            val (frontmatter, body) = Frontmatter.parse(withoutDetachedSource(value.text))
            if (frontmatter.isNotEmpty()) {
                val pinned: JsonObject =
                    buildJsonObject {
                        frontmatter.forEach { (key, element) -> if (key != FrontmatterKeys.ID) put(key, element) }
                        put(FrontmatterKeys.ID, JsonPrimitive(docId))
                    }
                vaultRepository.updateFrontmatter(docId, pinned)
            }
            // Body only: a save that re-sent [title] would revert a rename made
            // elsewhere (OBJECT_LIFECYCLE_SPEC.md N7). A chat or attachment body is
            // not writable; that throw lands in `EditorState`'s save error state.
            vaultRepository.replaceBody(docId, body)
        }
    }

    /**
     * bd `skein-pnqo` (`E7.I5` wiring): backs [SkeinEditor][app.skein.feature.editor.SkeinEditor]'s
     * `wikilinkSuggest` — this is the one place in `:feature:editor` allowed
     * to touch [VaultRepository] (see this class's own header), so the `[[`
     * popup's title lookup lives here rather than in [NoteTab]. Capped at
     * [TitleMatcher.DEFAULT_LIMIT] and excludes this note's own [title] — a
     * self-link resolves fine through [onWikilinkClicked] but offering it as
     * a suggestion for "the note you're already in" is confusing noise, not
     * a real navigation target.
     */
    public suspend fun searchWikilinkSuggestions(query: String): List<Suggestion> =
        vaultRepository
            .searchTitles(query, limit = TitleMatcher.DEFAULT_LIMIT)
            .map { it.title }
            .filter { it != title }
            .map { Suggestion(title = it) }

    /**
     * bd `skein-pnqo`: backs [SkeinEditor][app.skein.feature.editor.SkeinEditor]'s
     * `onCreateWikilink` — the popup's "Create" row already inserted
     * `[[title]]` into the buffer ([app.skein.feature.editor.autocomplete.WikilinkAutocompleteState.confirm])
     * before invoking this callback; all that's left is persisting an empty
     * `NOTE` with that title so a later tap on that same link resolves it
     * through [onWikilinkClicked]'s `findByTitle` instead of creating a
     * second document with the same title.
     */
    public suspend fun createWikilink(title: String) {
        vaultRepository.createDocument(NewDocument(kind = DocumentKind.NOTE, title = title, bodyMd = ""))
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
            linkNotice = null
            // The buffer may still show the imported spelling while the final pass has
            // already rewritten the persisted body. Its guards must come from that same buffer.
            val frontmatter = Frontmatter.parse(editorState.value.text).first
            val persisted = vaultRepository.getDocument(docId)?.frontmatter ?: JsonObject(emptyMap())
            if (ImportedLinkTargets.isUnresolved(frontmatter, target.title) ||
                ImportedLinkTargets.isUnresolved(persisted, target.title)
            ) {
                linkNotice =
                    if (ImportedLinkTargets.isAmbiguous(frontmatter, target.title) ||
                        ImportedLinkTargets.isAmbiguous(persisted, target.title)
                    ) {
                        "This imported link matches more than one note and could not be resolved."
                    } else {
                        "This link's target was not resolved in the imported folder."
                    }
                return@launch
            }
            val isId = ImportedLinkTargets.isDocumentId(target.title)
            val existing =
                if (isId) {
                    vaultRepository.getDocument(target.title) ?: vaultRepository.getDocument(target.title.lowercase())
                } else {
                    vaultRepository.findByTitle(target.title)
                }
            if (isId && existing == null) {
                linkNotice = "The linked note is no longer available."
                return@launch
            }
            val doc =
                existing
                    ?: vaultRepository.createDocument(
                        NewDocument(kind = DocumentKind.NOTE, title = target.title, bodyMd = ""),
                    )
            onOpenDocument(doc.id, doc.title)
        }
    }
}
