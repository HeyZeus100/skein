// `E2.I6` (bd `skein-75x`): instrumented test for the real
// `VaultDocumentsProvider` — `MatrixCursor`s and `ParcelFileDescriptor`
// pipes are Android-only, so the cursor/pipe plumbing can only be proven
// on a device. The provider is instantiated directly (plan `E2.I6` step 1:
// "`ProviderTestRule`-free direct instantiation with a fake repo and a
// fake unlock state") over the JVM `InMemoryVaultRepository` seeded by
// `SyntheticVault` (`:testing`), the real `ExportServiceImpl`, and a
// `MutableStateFlow<UnlockState>` standing in for `UnlockManager.state`.
//
// `attachInfo` is called with a `ProviderInfo` shaped exactly like the
// merged manifest's `<provider>` (exported, MANAGE_DOCUMENTS read+write,
// `grantUriPermissions` effectively true via the `<grant-uri-permission>`
// subsets — see the class header of `VaultDocumentsProvider`) so the
// framework's own `DocumentsProvider.attachInfo` sanity checks run too.
//
// Follow-up (skein-k3b2): no emulator was available in this worktree,
// matching every other `*InstrumentedTest`/`*ContractTest` in the module.
// This class is compiled by `compileFossDebugAndroidTestKotlin`; running it
// for real is gated on that follow-up. The `DocumentsContract`-level
// grant/exported-components audit is `E10.I9` (skein-fubu).

package app.skein.core.vault.provider

import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.skein.core.model.AuthorizationToken
import app.skein.core.model.DocumentKind
import app.skein.core.model.IngestReason
import app.skein.core.model.TimelineFilter
import app.skein.core.vault.export.ExportServiceImpl
import app.skein.core.vault.session.UnlockState
import app.skein.testing.InMemoryVaultRepository
import app.skein.testing.fixtures.SyntheticVault
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException

@RunWith(AndroidJUnit4::class)
public class VaultDocumentsProviderTest {
    private val repo = InMemoryVaultRepository()
    private val export = ExportServiceImpl(repo)
    private val unlockState = MutableStateFlow<UnlockState>(UnlockState.Locked)
    private val provider =
        VaultDocumentsProvider { VaultDocumentsProvider.Services(repo, export, unlockState) }

    @Before
    public fun setUp() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val info =
            ProviderInfo().apply {
                authority = VaultDocumentsProvider.AUTHORITY
                exported = true
                grantUriPermissions = true
                readPermission = android.Manifest.permission.MANAGE_DOCUMENTS
                writePermission = android.Manifest.permission.MANAGE_DOCUMENTS
                packageName = context.packageName
                applicationInfo = context.applicationInfo
            }
        provider.attachInfo(context, info)
        SyntheticVault.seed(repo, size = SyntheticVault.Preset.SMALL)
        unlockState.value = UnlockState.Unlocked(since = 0L, token = AuthorizationToken(1L))
    }

    private fun Cursor.column(name: String): List<String?> =
        use {
            val out = ArrayList<String?>(count)
            val index = getColumnIndexOrThrow(name)
            while (moveToNext()) out += if (isNull(index)) null else getString(index)
            out
        }

    private fun readAll(pfd: ParcelFileDescriptor): ByteArray =
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use {
            it.readBytes()
        }

    // Named `sortOrder` disambiguates from the framework's
    // (String, Array<String>?, Bundle?) overload when passing null.
    private fun children(parentDocumentId: String): Cursor =
        provider.queryChildDocuments(parentDocumentId, projection = null, sortOrder = null)

    private fun markdownDocuments() =
        runBlocking {
            repo
                .observeTimeline(
                    filter = TimelineFilter(kinds = DocumentKind.entries.toSet() - DocumentKind.ATTACHMENT),
                    limit = Int.MAX_VALUE,
                ).first()
        }

    private fun attachmentDocuments() =
        runBlocking {
            repo
                .observeTimeline(filter = TimelineFilter(kinds = setOf(DocumentKind.ATTACHMENT)), limit = Int.MAX_VALUE)
                .first()
        }

    // ---- unlocked ----------------------------------------------------------

    @Test
    public fun queryRoots_listsOneVaultRoot() {
        val rootIds = provider.queryRoots(null).column(DocumentsContract.Root.COLUMN_ROOT_ID)
        assertEquals(listOf(ProviderIds.ROOT_ID), rootIds)
    }

    @Test
    public fun queryChildDocuments_ofRoot_listsNotesAndAttachments() {
        val ids = children(ProviderIds.ROOT_DOCUMENT_ID).column(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        assertEquals(listOf(ProviderIds.NOTES_DOCUMENT_ID, ProviderIds.ATTACHMENTS_DOCUMENT_ID), ids)
    }

    @Test
    public fun queryChildDocuments_ofNotes_listsEveryMarkdownDocumentAsTextMarkdown() {
        val mimes = children(ProviderIds.NOTES_DOCUMENT_ID).column(DocumentsContract.Document.COLUMN_MIME_TYPE)
        assertEquals(markdownDocuments().size, mimes.size)
        assertEquals(setOf(ProviderCursors.MARKDOWN_MIME_TYPE), mimes.toSet())
    }

    @Test
    public fun queryChildDocuments_ofAttachments_listsEveryAttachmentWithItsStoredMimeType() {
        val expected =
            attachmentDocuments().associate {
                ProviderIds.attachment(it.id) to
                    runBlocking { repo.attachmentMimeType(it.id) }
            }
        val ids = children(ProviderIds.ATTACHMENTS_DOCUMENT_ID).column(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val mimes = children(ProviderIds.ATTACHMENTS_DOCUMENT_ID).column(DocumentsContract.Document.COLUMN_MIME_TYPE)
        assertEquals(expected, ids.zip(mimes).toMap())
    }

    @Test
    public fun queryDocument_honoursARequestedProjection() {
        val note = markdownDocuments().first()
        val cursor =
            provider.queryDocument(
                ProviderIds.note(note.id),
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            )
        assertEquals(
            listOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            cursor.columnNames.toList(),
        )
        assertEquals(listOf(ProviderIds.note(note.id)), cursor.column(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
    }

    @Test
    public fun openDocument_read_streamsExactlyWhatExportMarkdownProduces() {
        val note = markdownDocuments().first()
        val expected = ByteArrayOutputStream().also { runBlocking { export.exportMarkdown(note.id, it) } }.toByteArray()
        val actual = readAll(provider.openDocument(ProviderIds.note(note.id), "r", null))
        assertTrue(expected.contentEquals(actual))
    }

    @Test
    public fun openDocument_read_streamsTheOriginalAttachmentBytes() {
        val attachment = attachmentDocuments().first()
        val expected = runBlocking { repo.openAttachment(attachment.id).use { it.readBytes() } }
        val actual = readAll(provider.openDocument(ProviderIds.attachment(attachment.id), "r", null))
        assertTrue(expected.contentEquals(actual))
    }

    @Test
    public fun openDocument_write_updatesTheNoteAndEnqueuesIngest() {
        val note = markdownDocuments().first { it.kind == DocumentKind.NOTE }
        val pfd = provider.openDocument(ProviderIds.note(note.id), "w", null)
        ParcelFileDescriptor
            .AutoCloseOutputStream(
                pfd,
            ).use { it.write("---\ntitle: Rewritten\n---\nnew body".toByteArray()) }

        val updated = awaitUntil { runBlocking { repo.getDocument(note.id) }!!.bodyMd == "new body" }
        assertTrue("write did not land within the deadline", updated)
        assertEquals("Rewritten", runBlocking { repo.getDocument(note.id) }!!.title)
        val queued = runBlocking { repo.dequeueIngest(Int.MAX_VALUE) }.single { it.docId == note.id }
        assertEquals(IngestReason.UPDATED, queued.reason)
    }

    @Test
    public fun openDocument_refusesReadWriteMode() {
        val note = markdownDocuments().first { it.kind == DocumentKind.NOTE }
        val error = runCatching { provider.openDocument(ProviderIds.note(note.id), "rw", null) }.exceptionOrNull()
        assertTrue("expected UnsupportedOperationException, got $error", error is UnsupportedOperationException)
    }

    @Test
    public fun isChildDocument_scopesNotesUnderTheNotesDirectoryOnly() {
        val note = markdownDocuments().first()
        assertTrue(provider.isChildDocument(ProviderIds.NOTES_DOCUMENT_ID, ProviderIds.note(note.id)))
        assertFalse(provider.isChildDocument(ProviderIds.ATTACHMENTS_DOCUMENT_ID, ProviderIds.note(note.id)))
    }

    // ---- locked ------------------------------------------------------------

    @Test
    public fun locked_everyQueryAndOpenThrowsAndNothingIsListed() {
        val note = markdownDocuments().first()
        unlockState.value = UnlockState.Locked

        val outcomes =
            listOf(
                runCatching { provider.queryRoots(null) },
                runCatching { provider.queryDocument(ProviderIds.ROOT_DOCUMENT_ID, null) },
                runCatching { children(ProviderIds.NOTES_DOCUMENT_ID) },
                runCatching { provider.openDocument(ProviderIds.note(note.id), "r", null) },
                runCatching { provider.openDocument(ProviderIds.note(note.id), "w", null) },
            )

        assertTrue(
            "expected FileNotFoundException from every entry point, got ${outcomes.map { it.exceptionOrNull() }}",
            outcomes.all { it.exceptionOrNull() is FileNotFoundException },
        )
        assertFalse(provider.isChildDocument(ProviderIds.ROOT_DOCUMENT_ID, ProviderIds.note(note.id)))
    }

    private fun awaitUntil(
        deadlineMillis: Long = 5_000L,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + deadlineMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }
}
