// skein-a0mm — "Import a folder…" (INFORMATION_ARCHITECTURE.md §8b, UX
// Wave 6): brings a tree of Markdown/text notes the user copied to the phone
// into one Space. Orchestration only: every file goes through
// `ImportService.importText` into the target persona, so titles, frontmatter
// ids and conflicts behave exactly like a single-file import, and the
// import session resolves unambiguous filename/path/alias links to UUIDs
// after the files arrive. Unresolved links stay guarded even on cancellation;
// ingest cannot mistake a duplicate basename for an unrelated same-title note.
//
// The shape follows MainActivity's model import (`ImportProgress` flow, one
// import at a time, a `Job` to cancel) with the ownership skein-gg11.19 asks
// for there: the job lives in `VaultServices` (process scope), not in a
// composition, so leaving the screen or a fold does not cancel it; its state
// is a `StateFlow` the UI observes. No UI starts it yet (Wave 6 does).
//
// The tree is untrusted input:
//   - One `DocumentsContract` children query per directory (not DocumentFile,
//     which is not a dependency here and costs a query per property).
//   - SAF has no notion of a symlink, so the walk cannot refuse to follow
//     one; it is bounded instead: directories past [MAX_DEPTH] are not
//     entered, a directory id is listed at most once, and listing stops
//     after [MAX_TREE_ENTRIES] rows. Hitting either reports `INCOMPLETE`.
//   - Hidden files and directories (a leading `.`) are ignored; only
//     `.md`, `.markdown` and `.txt` files are imported.
//   - A file over [MAX_FILE_BYTES] (by its reported size, or by what it
//     actually yields) is skipped and counted; so is one that cannot be read.
//     Invalid UTF-8 is `importText`'s to tolerate (it substitutes U+FFFD).
//   - Nothing is copied anywhere but the vault, and nothing is logged: file
//     names are titles.
//   - The tree grant comes from the picker's result; if it is revoked
//     mid-walk, the unreadable parts are skipped (`INCOMPLETE`), never fatal.
//
// Lock: a HIGH-priority `LockObserver` (registered by `VaultServices`), like
// `IngestScheduler`, so the job is cancelled before `VaultBootstrap` closes
// the session it writes to. Files imported before the lock stay imported.

package app.skein.transfer

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import app.skein.core.model.PersonaId
import app.skein.core.vault.session.LockObserver
import app.skein.core.vault.session.LockObserverPriority
import app.skein.core.vault.transfer.MarkdownImportLinker
import app.skein.vault.VaultSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** What a [FolderImportJob] is doing, for the progress row and the final snackbar. */
sealed interface FolderImportState {
    data object Idle : FolderImportState

    /** [done] of [total] files attempted; [total] is 0 while the folder is still being listed. */
    data class Running(
        val done: Int,
        val total: Int,
    ) : FolderImportState

    data class Finished(
        val imported: Int,
        /** Files that looked importable but were too large, unreadable, or refused by the import. */
        val skipped: Int,
        val outcome: FolderImportOutcome,
    ) : FolderImportState
}

enum class FolderImportOutcome {
    /** Every importable file in the folder was attempted. */
    COMPLETED,

    /** The walk hit a safety cap or a folder it could not read, so some files were never seen. */
    INCOMPLETE,

    /** [FolderImportJob.cancel]. */
    CANCELLED,

    /** The vault locked; the rest needs a new import after unlock. */
    LOCKED,
}

/**
 * See the file header. One import at a time, process-scoped.
 *
 * @param resolver the app's `ContentResolver`, which holds the tree grant.
 * @param session `VaultBootstrap.session` — the import writes to the session open at [start].
 * @param scope a process-lifetime scope (`VaultServices`'), so the job outlives any screen.
 */
class FolderImportJob(
    private val resolver: ContentResolver,
    private val session: StateFlow<VaultSession?>,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LockObserver {
    private val stateFlow = MutableStateFlow<FolderImportState>(FolderImportState.Idle)

    /** The running import's progress, or the last one's result. */
    val state: StateFlow<FolderImportState> = stateFlow.asStateFlow()

    private var job: Job? = null

    @Volatile private var locking = false

    /**
     * Imports every note under [treeUri] (an `ACTION_OPEN_DOCUMENT_TREE`
     * result) into [personaId]'s Space, or into no Space when `null`.
     * Returns `false`, starting nothing, while another import runs or the
     * vault is locked.
     */
    @Synchronized
    fun start(
        treeUri: Uri,
        personaId: PersonaId?,
    ): Boolean {
        if (job?.isActive == true) return false
        val open = session.value ?: return false
        locking = false
        stateFlow.value = FolderImportState.Running(done = 0, total = 0)
        job = scope.launch(dispatcher) { run(open, treeUri, personaId) }
        return true
    }

    /** Stops the running import; what it imported so far stays. */
    fun cancel() {
        job?.cancel()
    }

    private suspend fun run(
        open: VaultSession,
        treeUri: Uri,
        personaId: PersonaId?,
    ) {
        var imported = 0
        var skipped = 0
        val links = MarkdownImportLinker(open.repository)
        try {
            val listing = list(treeUri)
            for ((index, file) in listing.files.withIndex()) {
                currentCoroutineContext().ensureActive()
                if (importOne(open, file, personaId, links)) imported += 1 else skipped += 1
                stateFlow.value = FolderImportState.Running(done = index + 1, total = listing.files.size)
            }
            links.finish()
            val outcome = if (listing.complete) FolderImportOutcome.COMPLETED else FolderImportOutcome.INCOMPLETE
            stateFlow.value = FolderImportState.Finished(imported, skipped, outcome)
        } catch (e: CancellationException) {
            val outcome = if (locking) FolderImportOutcome.LOCKED else FolderImportOutcome.CANCELLED
            stateFlow.value = FolderImportState.Finished(imported, skipped, outcome)
            throw e
        }
    }

    private suspend fun importOne(
        open: VaultSession,
        file: TreeFile,
        personaId: PersonaId?,
        links: MarkdownImportLinker,
    ): Boolean {
        val bytes = read(file) ?: return false
        return try {
            links.importText(
                open.importService,
                file.relativePath,
                file.name,
                file.mimeType,
                bytes.inputStream(),
                personaId,
            )
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // One refused file (an id race, a storage error) must not end the import.
            false
        }
    }

    /** The file's bytes, or `null` when it is over [MAX_FILE_BYTES] or unreadable. */
    private fun read(file: TreeFile): ByteArray? {
        if (file.size != null && file.size > MAX_FILE_BYTES) return null
        return try {
            resolver.openInputStream(file.uri)?.use { readAtMost(it, MAX_FILE_BYTES) }
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            // SecurityException (grant revoked mid-import) or a provider failure.
            null
        }
    }

    /** Breadth-first listing of the importable files under [treeUri]; see the file header for the bounds. */
    private suspend fun list(treeUri: Uri): Listing {
        val files = ArrayList<TreeFile>()
        var complete = true
        var rows = 0
        val listed = HashSet<String>()
        val pending = ArrayDeque<Triple<String, Int, String>>()
        val rootId =
            try {
                DocumentsContract.getTreeDocumentId(treeUri)
            } catch (_: IllegalArgumentException) {
                return Listing(files, complete = false)
            }
        pending += Triple(rootId, 0, "")
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (directoryId, depth, directoryPath) = pending.removeFirst()
            if (!listed.add(directoryId)) continue
            try {
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, directoryId)
                // The Bundle form: `DocumentsProvider` implements only that one (API 26+).
                val cursor = resolver.query(children, PROJECTION, null, null)
                if (cursor == null) {
                    complete = false
                    continue
                }
                cursor.use {
                    while (it.moveToNext()) {
                        if (++rows > MAX_TREE_ENTRIES) return Listing(files, complete = false)
                        val id = it.getString(COLUMN_ID) ?: continue
                        val name = it.getString(COLUMN_NAME) ?: continue
                        if (name.startsWith('.')) continue
                        if ('/' in name || '\\' in name || '\u0000' in name) {
                            complete = false
                            continue
                        }
                        val relativePath = if (directoryPath.isEmpty()) name else "$directoryPath/$name"
                        if (it.getString(COLUMN_MIME) == Document.MIME_TYPE_DIR) {
                            if (depth < MAX_DEPTH) pending += Triple(id, depth + 1, relativePath) else complete = false
                            continue
                        }
                        val mimeType = MIME_TYPES[name.substringAfterLast('.', "").lowercase()] ?: continue
                        val size = if (it.isNull(COLUMN_SIZE)) null else it.getLong(COLUMN_SIZE)
                        files +=
                            TreeFile(
                                DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                                name,
                                relativePath,
                                mimeType,
                                size,
                            )
                    }
                }
            } catch (_: RuntimeException) {
                // The grant is gone (SecurityException) or the provider failed
                // on this folder: list the rest, and say the import is partial.
                complete = false
            }
        }
        return Listing(files, complete)
    }

    // ---- LockObserver --------------------------------------------------

    override val priority: LockObserverPriority = LockObserverPriority.HIGH

    override suspend fun onLocking(
        epoch: Long,
        budgetMillis: Long,
    ) {
        val running = job?.takeIf { it.isActive } ?: return
        locking = true
        running.cancel()
        withTimeoutOrNull(budgetMillis) { running.join() }
    }

    override fun onLocked(epoch: Long): Unit = Unit

    override fun onUnlocked(epoch: Long): Unit = Unit

    private class TreeFile(
        val uri: Uri,
        val name: String,
        val relativePath: String,
        val mimeType: String,
        val size: Long?,
    )

    private class Listing(
        val files: List<TreeFile>,
        val complete: Boolean,
    )

    companion object {
        /** 10 MiB: a note is held in memory while it is imported (the same bound as `importVaultZip`'s documents). */
        const val MAX_FILE_BYTES: Int = 10 * 1024 * 1024

        /** Directory nesting entered below the picked folder; deeper folders are not listed. */
        const val MAX_DEPTH: Int = 32

        /** Rows (files and folders, any type) listed before the walk stops. */
        const val MAX_TREE_ENTRIES: Int = 50_000

        private val MIME_TYPES: Map<String, String> =
            mapOf("md" to "text/markdown", "markdown" to "text/markdown", "txt" to "text/plain")

        private val PROJECTION: Array<String> =
            arrayOf(
                Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE,
                Document.COLUMN_SIZE,
            )
        private const val COLUMN_ID = 0
        private const val COLUMN_NAME = 1
        private const val COLUMN_MIME = 2
        private const val COLUMN_SIZE = 3
        private const val BUFFER_BYTES = 8 * 1024

        /** [input]'s bytes, or `null` once it yields more than [max]. */
        private fun readAtMost(
            input: InputStream,
            max: Int,
        ): ByteArray? {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) return out.toByteArray()
                out.write(buffer, 0, n)
                if (out.size() > max) return null
            }
        }
    }
}
