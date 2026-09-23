// `E2.I6` (bd `skein-75x`): the `DocumentsProvider` that exposes the vault
// to the system file picker / Files app — the ONLY way vault content
// reaches another app (spec §2.9 principle 9/10, §9; plan `E2.I6`;
// `docs/design/POST_REVIEW_RESOLUTIONS.md` §4: DocumentsProvider-only,
// no `FileProvider`, nothing ever staged on disk).
//
// Tree and semantics (see `ProviderIds` / `ProviderCursors`):
//   Skein vault (root, `Root.FLAG_LOCAL_ONLY`)
//   ├── Notes/        every NOTE / CHAT / AIOUT as a virtual `<title>.md`
//   │                 (`text/markdown`, content = `ExportService.exportMarkdown`;
//   │                 `FLAG_SUPPORTS_WRITE` on NOTE only)
//   └── Attachments/  every ATTACHMENT blob, read-only, stored `mime_type`
//
// Lock gate: every entry point delegates to `VaultDocumentsBackend`, whose
// every method throws `FileNotFoundException("vault locked")` unless
// `UnlockManager.state` is `Unlocked` — a locked vault lists nothing, opens
// nothing, and `isChildDocument` is `false` for everything. The framework
// turns a `FileNotFoundException` from `queryRoots`/`queryDocument`/
// `queryChildDocuments` into a logged `null` cursor and from `openDocument`
// into the caller's own `FileNotFoundException`. An UNINSTALLED provider
// (no [install] yet, or `install(null)` on vault teardown) behaves exactly
// like a locked one — fail closed.
//
// Wiring: the framework instantiates this class from the manifest with no
// constructor arguments, so the live `VaultRepository` / `ExportService` /
// `UnlockManager.state` triple is handed over through [install] by
// whichever component brings the vault up (the `:app` wiring bead; until
// it lands the provider is installed-but-locked). Tests use the
// `internal` constructor to inject a per-instance [Services] override.
//
// Manifest (app/src/main/AndroidManifest.xml), per POST_REVIEW_RESOLUTIONS
// §4.3 verbatim:
//   android:exported="true"  android:permission="android.permission.MANAGE_DOCUMENTS"
//   android:grantUriPermissions="false"
//   <grant-uri-permission android:pathPrefix="/document/note:" />
//   <grant-uri-permission android:pathPrefix="/document/att:" />
// Two platform facts make that combination work and were verified against
// the API 34 framework bytecode (Robolectric's `android-all` jar) while
// authoring this class, since no device was available:
//   1. `DocumentsProvider.attachInfo` throws `SecurityException("Provider
//      must grantUriPermissions")` when `ProviderInfo.grantUriPermissions`
//      is false — a plain `grantUriPermissions="false"` would crash the
//      process at provider install.
//   2. The manifest parser (`ParsedProviderUtils.parseGrantUriPermission`,
//      and the legacy `PackageParser`) sets `grantUriPermissions = true`
//      whenever a `<grant-uri-permission>` child is declared, recording the
//      subsets in `ProviderInfo.uriPermissionPatterns`. The effective
//      posture is therefore "grants allowed, but ONLY for paths under
//      `/document/note:` and `/document/att:`" — exactly §4's intent
//      (`ManifestPolicyTest` asserts the effective `ProviderInfo`).
// Consequence: a tree grant (`content://…/tree/root/…`, what
// `ACTION_OPEN_DOCUMENT_TREE` hands out) matches neither prefix and is
// refused by the system, so the root deliberately does NOT advertise
// `Root.FLAG_SUPPORTS_IS_CHILD` — DocumentsUI hides such roots from tree
// pickers instead of offering a folder whose grant would then fail.
// `isChildDocument` is still implemented correctly (bd acceptance
// criterion) for any tree URI that reaches `enforceTree`. Folder-level
// access (e.g. Obsidian opening the vault as a folder) is therefore a
// v1.1 decision, not a v1 feature — see the bd close notes.
//
// Persistable grants: the provider never advertises
// `Root.FLAG_SUPPORTS_RECENTS`/`FLAG_SUPPORTS_SEARCH`, never returns
// `FLAG_SUPPORTS_DELETE`/`FLAG_SUPPORTS_MOVE`, and offers no create flag;
// `E10.I9` (skein-fubu) audits the grant surface end to end.
//
// Streaming: `openDocument` returns one end of a
// `ParcelFileDescriptor.createReliablePipe()` and feeds the other end from
// a plain daemon `Thread` (plan `E2.I6` step 2: "`AsyncTask`-free `Thread`
// for streaming writes, or `createReliablePipe`"). A failure mid-stream
// (including the vault locking) closes the pipe with an error so the
// client's read/write throws instead of seeing a truncated file. Error
// strings carry an exception class name or "vault locked" — never content.

package app.skein.core.vault.provider

import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import app.skein.core.model.ExportService
import app.skein.core.model.VaultRepository
import app.skein.core.vault.session.UnlockState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.concurrent.thread

public class VaultDocumentsProvider internal constructor(
    private val servicesOverride: (() -> Services?)?,
) : DocumentsProvider() {
    /** The no-arg constructor the framework uses; dependencies arrive via [install]. */
    public constructor() : this(null)

    /** The live vault handles the provider serves. `unlockState` is `UnlockManager.state`. */
    public class Services(
        public val repository: VaultRepository,
        public val exportService: ExportService,
        public val unlockState: StateFlow<UnlockState>,
    )

    @Volatile
    private var authority: String = AUTHORITY

    @Volatile
    private var rootIconResId: Int = DEFAULT_ROOT_ICON_RES_ID

    override fun onCreate(): Boolean = true

    override fun attachInfo(
        context: Context,
        info: ProviderInfo,
    ) {
        // Captured before the framework's own sanity checks run, so both
        // the real authority (manifest-driven) and the app's launcher icon
        // are what the root row reports.
        info.authority?.let { authority = it }
        info.applicationInfo
            ?.icon
            ?.takeIf { it != 0 }
            ?.let { rootIconResId = it }
        super.attachInfo(context, info)
    }

    // ---- queries ------------------------------------------------------------------

    override fun queryRoots(projection: Array<String>?): Cursor {
        val rows = runBlocking { backend().roots(rootIconResId) }
        return cursor(ProviderCursors.DEFAULT_ROOT_PROJECTION, projection, rows)
    }

    override fun queryDocument(
        documentId: String,
        projection: Array<String>?,
    ): Cursor {
        val row = runBlocking { backend().document(documentId) }
        return cursor(ProviderCursors.DEFAULT_DOCUMENT_PROJECTION, projection, listOf(row))
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        val rows = runBlocking { backend().children(parentDocumentId) }
        val cursor = cursor(ProviderCursors.DEFAULT_DOCUMENT_PROJECTION, projection, rows)
        context?.let {
            cursor.setNotificationUri(
                it.contentResolver,
                DocumentsContract.buildChildDocumentsUri(authority, parentDocumentId),
            )
        }
        return cursor
    }

    override fun isChildDocument(
        parentDocumentId: String,
        documentId: String,
    ): Boolean = backendOrNull()?.isChildDocument(parentDocumentId, documentId) ?: false

    // ---- open -----------------------------------------------------------------------

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val backend = backend()
        return when (runBlocking { backend.resolveAccess(documentId, mode) }) {
            VaultDocumentsBackend.Access.READ -> openForRead(backend, documentId)
            VaultDocumentsBackend.Access.WRITE -> openForWrite(backend, documentId)
        }
    }

    private fun openForRead(
        backend: VaultDocumentsBackend,
        documentId: String,
    ): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]
        thread(name = "skein-documents-read", isDaemon = true) {
            val out = ParcelFileDescriptor.AutoCloseOutputStream(writeEnd)
            try {
                runBlocking { backend.read(documentId, out) }
                out.flush()
                out.close()
            } catch (t: Throwable) {
                runCatching { writeEnd.closeWithError(safeMessage(t)) }
            }
        }
        return readEnd
    }

    private fun openForWrite(
        backend: VaultDocumentsBackend,
        documentId: String,
    ): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]
        thread(name = "skein-documents-write", isDaemon = true) {
            val bytes =
                try {
                    ParcelFileDescriptor.AutoCloseInputStream(readEnd).use {
                        VaultDocumentsBackend.readBounded(it, VaultDocumentsBackend.MAX_NOTE_BYTES)
                    }
                } catch (_: IOException) {
                    // The client aborted (closeWithError / crashed) or
                    // exceeded the size bound: nothing is persisted.
                    return@thread
                }
            // The lock state is re-checked inside writeNote at commit time:
            // a note whose write outlived the session is discarded.
            runCatching { runBlocking { backend.writeNote(documentId, bytes) } }
                .onSuccess { notifyDocumentChanged(documentId) }
        }
        return writeEnd
    }

    // ---- internals -----------------------------------------------------------------

    private fun currentServices(): Services? = servicesOverride?.invoke() ?: installed

    private fun backendOrNull(): VaultDocumentsBackend? =
        currentServices()?.let { VaultDocumentsBackend(it.repository, it.exportService, it.unlockState) }

    private fun backend(): VaultDocumentsBackend =
        backendOrNull() ?: throw FileNotFoundException(VaultDocumentsBackend.VAULT_LOCKED)

    private fun cursor(
        default: Array<String>,
        requested: Array<String>?,
        rows: List<ProviderRow>,
    ): MatrixCursor {
        val projection = ProviderCursors.resolveProjection(requested, default)
        val cursor = MatrixCursor(projection, rows.size)
        for (row in rows) cursor.addRow(ProviderCursors.project(row, projection))
        return cursor
    }

    private fun notifyDocumentChanged(documentId: String) {
        val resolver = context?.contentResolver ?: return
        resolver.notifyChange(DocumentsContract.buildDocumentUri(authority, documentId), null)
        resolver.notifyChange(DocumentsContract.buildChildDocumentsUri(authority, ProviderIds.NOTES_DOCUMENT_ID), null)
    }

    /** Pipe error text: the plan's "vault locked", or an exception class name — never document content. */
    private fun safeMessage(t: Throwable): String =
        if (t is FileNotFoundException && t.message == VaultDocumentsBackend.VAULT_LOCKED) {
            VaultDocumentsBackend.VAULT_LOCKED
        } else {
            t.javaClass.simpleName
        }

    public companion object {
        /** Plan `E2.I6` "Produces: provider authority"; must match `android:authorities` in the app manifest. */
        public const val AUTHORITY: String = "app.skein.documents"

        /** Root icon when `ProviderInfo.applicationInfo.icon` is unavailable (the manifest's own app icon). */
        internal const val DEFAULT_ROOT_ICON_RES_ID: Int = android.R.drawable.sym_def_app_icon

        @Volatile
        private var installed: Services? = null

        /**
         * Hands the live vault to the provider. Call with the open vault's
         * services once the vault is up and with `null` when it is torn
         * down; until then (and in between) every provider call fails
         * closed as "vault locked". Pair with [notifyRootsChanged] so an
         * open picker refreshes.
         */
        public fun install(services: Services?) {
            installed = services
        }

        /** Tells DocumentsUI to re-query roots — call on lock/unlock transitions and after [install]. */
        public fun notifyRootsChanged(context: Context) {
            context.contentResolver.notifyChange(DocumentsContract.buildRootsUri(AUTHORITY), null)
        }
    }
}
