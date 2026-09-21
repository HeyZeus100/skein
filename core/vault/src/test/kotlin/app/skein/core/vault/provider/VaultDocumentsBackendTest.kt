// `E2.I6` (bd `skein-75x`): JVM unit tests for `VaultDocumentsBackend` —
// the lock-gated, Android-free core every `VaultDocumentsProvider` entry
// point delegates to. Seeds the JVM-only `InMemoryVaultRepository`
// (`:testing`) behind the real `ExportServiceImpl` (`E2.I10`), exactly as
// `MarkdownExportTest` does, and drives the lock gate through a
// `MutableStateFlow<UnlockState>` (plus one end-to-end test against a real
// `UnlockManager` + `FakeVaultKeyProvider`, so the gate is proven to follow
// the manager's own `state`).
//
// bd acceptance criteria exercised here:
//   - roots / notes / attachments listed with `text/markdown` and the stored
//     `mime_type`;
//   - reading a note returns frontmatter + body identical to `exportMarkdown`;
//   - writing a note updates the document and enqueues ingest;
//   - locked vault ⇒ every query/open throws and nothing is listed;
//   - `note:<uuid>` / `att:<uuid>` ids and `isChildDocument` scoping.

package app.skein.core.vault.provider

import android.provider.DocumentsContract
import app.skein.core.vault.codec.Frontmatter
import app.skein.core.vault.export.ExportServiceImpl
import app.skein.core.vault.key.UnlockResult
import app.skein.core.vault.key.VaultKeyProvider
import app.skein.core.vault.session.FakeVaultKeyProvider
import app.skein.core.vault.session.LockReason
import app.skein.core.vault.session.TestClock
import app.skein.core.vault.session.UnlockManager
import app.skein.core.vault.session.UnlockState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test
import us.aherrera.skein.core.model.AuthorizationToken
import us.aherrera.skein.core.model.Document
import us.aherrera.skein.core.model.DocumentKind
import us.aherrera.skein.core.model.FrontmatterKeys
import us.aherrera.skein.core.model.IngestReason
import us.aherrera.skein.core.model.NewDocument
import us.aherrera.skein.core.model.NewMessage
import us.aherrera.skein.core.model.Role
import us.aherrera.skein.testing.InMemoryVaultRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream

public class VaultDocumentsBackendTest {
    private class Harness {
        val repo = InMemoryVaultRepository()
        val export = ExportServiceImpl(repo)
        val unlockState = MutableStateFlow<UnlockState>(UnlockState.Locked)
        val backend = VaultDocumentsBackend(repo, export, unlockState)

        fun unlock() {
            unlockState.value = UnlockState.Unlocked(since = 0L, token = AuthorizationToken(1L))
        }

        fun lock() {
            unlockState.value = UnlockState.Locked
        }

        suspend fun note(
            title: String = "Example Note",
            body: String = "body",
        ): Document =
            repo.createDocument(
                NewDocument(
                    kind = DocumentKind.NOTE,
                    title = title,
                    bodyMd = body,
                    frontmatter =
                        buildJsonObject {
                            put(FrontmatterKeys.TITLE, JsonPrimitive(title))
                            put(FrontmatterKeys.TAGS, JsonArray(listOf(JsonPrimitive("example"))))
                        },
                ),
            )

        suspend fun chat(): Document {
            val chat = repo.createDocument(NewDocument(kind = DocumentKind.CHAT, title = "Chat", bodyMd = null))
            repo.appendMessage(chat.id, NewMessage(role = Role.USER, contentMd = "hello"))
            return chat
        }

        suspend fun attachment(
            bytes: ByteArray = byteArrayOf(1, 2, 3),
            mimeType: String = "application/pdf",
            title: String = "External 1",
        ): Document = repo.createAttachment(title = title, mimeType = mimeType) { it.write(bytes) }

        suspend fun exported(doc: Document): ByteArray =
            ByteArrayOutputStream()
                .also {
                    export.exportMarkdown(doc.id, it)
                }.toByteArray()
    }

    private fun Map<String, Any?>.id(): Any? = this[DocumentsContract.Document.COLUMN_DOCUMENT_ID]

    private fun Map<String, Any?>.mime(): Any? = this[DocumentsContract.Document.COLUMN_MIME_TYPE]

    // ---- roots ---------------------------------------------------------------

    @Test
    public fun `roots lists exactly one root when unlocked`() =
        runTest {
            val h = Harness().apply { unlock() }
            val roots = h.backend.roots(iconResId = 1)
            assertThat(roots.map { it[DocumentsContract.Root.COLUMN_ROOT_ID] }).containsExactly(ProviderIds.ROOT_ID)
        }

    @Test
    public fun `roots throws vault locked when locked`() =
        runTest {
            val h = Harness()
            val error = runCatching { h.backend.roots(iconResId = 1) }.exceptionOrNull()
            assertThat(error).hasMessageThat().isEqualTo(VaultDocumentsBackend.VAULT_LOCKED)
        }

    @Test
    public fun `roots throws a FileNotFoundException when locked`() =
        runTest {
            val h = Harness()
            val error = runCatching { h.backend.roots(iconResId = 1) }.exceptionOrNull()
            assertThat(error).isInstanceOf(FileNotFoundException::class.java)
        }

    // ---- document ------------------------------------------------------------

    @Test
    public fun `document resolves the root, notes and attachments directories`() =
        runTest {
            val h = Harness().apply { unlock() }
            val rows =
                listOf(
                    h.backend.document(ProviderIds.ROOT_DOCUMENT_ID),
                    h.backend.document(ProviderIds.NOTES_DOCUMENT_ID),
                    h.backend.document(ProviderIds.ATTACHMENTS_DOCUMENT_ID),
                )
            assertThat(rows.map { it.mime() }.distinct()).containsExactly(DocumentsContract.Document.MIME_TYPE_DIR)
        }

    @Test
    public fun `document resolves a note as text markdown`() =
        runTest {
            val h = Harness().apply { unlock() }
            val note = h.note()
            assertThat(
                h.backend.document(ProviderIds.note(note.id)).mime(),
            ).isEqualTo(ProviderCursors.MARKDOWN_MIME_TYPE)
        }

    @Test
    public fun `document resolves an attachment with its stored mime type`() =
        runTest {
            val h = Harness().apply { unlock() }
            val att = h.attachment(mimeType = "image/png")
            assertThat(h.backend.document(ProviderIds.attachment(att.id)).mime()).isEqualTo("image/png")
        }

    @Test
    public fun `document throws for an unknown note id`() =
        runTest {
            val h = Harness().apply { unlock() }
            val error = runCatching { h.backend.document(ProviderIds.note("missing")) }.exceptionOrNull()
            assertThat(error).isInstanceOf(FileNotFoundException::class.java)
        }

    @Test
    public fun `document throws when an attachment is addressed with the note prefix`() =
        runTest {
            val h = Harness().apply { unlock() }
            val att = h.attachment()
            val error = runCatching { h.backend.document(ProviderIds.note(att.id)) }.exceptionOrNull()
            assertThat(error).isInstanceOf(FileNotFoundException::class.java)
        }

    @Test
    public fun `document throws when a note is addressed with the att prefix`() =
        runTest {
            val h = Harness().apply { unlock() }
            val note = h.note()
            val error = runCatching { h.backend.document(ProviderIds.attachment(note.id)) }.exceptionOrNull()
            assertThat(error).isInstanceOf(FileNotFoundException::class.java)
        }

    @Test
    public fun `document throws for a malformed id`() =
        runTest {
            val h = Harness().apply { unlock() }
            val error = runCatching { h.backend.document("bogus") }.exceptionOrNull()
            assertThat(error).isInstanceOf(FileNotFoundException::class.java)
        }

    @Test
    public fun `document throws when locked even for the root directory`() =
        runTest {
            val h = Harness()
            val error = runCatching { h.backend.document(ProviderIds.ROOT_DOCUMENT_ID) }.exceptionOrNull()
            assertThat(error).isInstanceOf(FileNotFoundException::class.java)
        }

    // ---- children ------------------------------------------------------------

    @Test
    public fun `children of the root are the notes and attachments directories`() =
        runTest {
            val h = Harness().apply { unlock() }
            val rows = h.backend.children(ProviderIds.ROOT_DOCUMENT_ID)
            assertThat(rows.map { it.id() })
                .containsExactly(ProviderIds.NOTES_DOCUMENT_ID, ProviderIds.ATTACHMENTS_DOCUMENT_ID)
                .inOrder()
        }

    @Test
    public fun `children of notes are every non-attachment document as a note id`() =
        runTest {
            val h = Harness().apply { unlock() }
            val note = h.note()
            val chat = h.chat()
            h.attachment()
            val rows = h.backend.children(ProviderIds.NOTES_DOCUMENT_ID)
            assertThat(rows.map { it.id() }).containsExactly(ProviderIds.note(note.id), ProviderIds.note(chat.id))
        }

    @Test
    public fun `children of notes are all text markdown`() =
        runTest {
            val h = Harness().apply { unlock() }
            h.note()
            h.chat()
            val rows = h.backend.children(ProviderIds.NOTES_DOCUMENT_ID)
            assertThat(rows.map { it.mime() }.distinct()).containsExactly(ProviderCursors.MARKDOWN_MIME_TYPE)
        }

    @Test
    public fun `children of attachments are every attachment with its stored mime type`() =
        runTest {
            val h = Harness().apply { unlock() }
            h.note()
            val pdf = h.attachment(mimeType = "application/pdf")
            val png = h.attachment(mimeType = "image/png", title = "External 2")
            val rows = h.backend.children(ProviderIds.ATTACHMENTS_DOCUMENT_ID)
            assertThat(rows.associate { it.id() to it.mime() })
                .containsExactly(
                    ProviderIds.attachment(pdf.id),
                    "application/pdf",
                    ProviderIds.attachment(png.id),
                    "image/png",
                )
        }

    @Test
    public fun `children of a note throws because a note is not a directory`() =
        runTest {
            val h = Harness().apply { unlock() }
            val note = h.note()
            val error = runCatching { h.backend.children(ProviderIds.note(note.id)) }.exceptionOrNull()
            assertThat(error).isInstanceOf(FileNotFoundException::class.java)
        }

    @Test
    public fun `children throws when locked and lists nothing`() =
        runTest {
            val h = Harness().apply { unlock() }
            h.note()
            h.lock()
            val error = runCatching { h.backend.children(ProviderIds.NOTES_DOCUMENT_ID) }.exceptionOrNull()
            assertThat(error).isInstanceOf(FileNotFoundException::class.java)
        }

    @Test
    public fun `no listed id ever contains a path separator`() =
        runTest {
            val h = Harness().apply { unlock() }
            h.note(title = "with/slash")
            h.attachment(title = "dir\\name")
            val ids =
                (
                    h.backend.children(ProviderIds.NOTES_DOCUMENT_ID) +
                        h.backend.children(ProviderIds.ATTACHMENTS_DOCUMENT_ID)
                ).map { it.id() as String }
            assertThat(ids.filter { '/' in it || '\\' in it }).isEmpty()
        }

    // ---- isChildDocument -----------------------------------------------------

    @Test
    public fun `isChildDocument scopes a note under the notes directory when unlocked`() =
        runTest {
            val h = Harness().apply { unlock() }
            assertThat(h.backend.isChildDocument(ProviderIds.NOTES_DOCUMENT_ID, ProviderIds.note("x"))).isTrue()
        }

    @Test
    public fun `isChildDocument is false for everything when locked`() =
        runTest {
            val h = Harness()
            assertThat(h.backend.isChildDocument(ProviderIds.ROOT_DOCUMENT_ID, ProviderIds.note("x"))).isFalse()
        }

    // ---- resolveAccess -------------------------------------------------------

    @Test
    public fun `mode r on a note grants read`() =
        runTest {
            val h = Harness().apply { unlock() }
            val note = h.note()
            assertThat(
                h.backend.resolveAccess(ProviderIds.note(note.id), "r"),
            ).isEqualTo(VaultDocumentsBackend.Access.READ)
        }

    @Test
    public fun `mode w on a note grants write`() =
        runTest {
            val h = Harness().apply { unlock() }
            val note = h.note()
            assertThat(
                h.backend.resolveAccess(ProviderIds.note(note.id), "w"),
            ).isEqualTo(VaultDocumentsBackend.Access.WRITE)
        }

    @Test
    public fun `mode wt on a note grants write`() =
        runTest {
            val h = Harness().apply { unlock() }
            val note = h.note()
            assertThat(
                h.backend.resolveAccess(ProviderIds.note(note.id), "wt"),
            ).isEqualTo(VaultDocumentsBackend.Access.WRITE)
        }

    @Test
    public fun `mode r on an attachment grants read`() =
        runTest {
            val h = Harness().apply { unlock() }
            val att = h.attachment()
            assertThat(
                h.backend.resolveAccess(ProviderIds.attachment(att.id), "r"),
            ).isEqualTo(VaultDocumentsBackend.Access.READ)
        }

    @Test
    public fun `read-write and append modes are refused`() =
        runTest {
            val h = Harness().apply { unlock() }
            val note = h.note()
            val errors =
                listOf("rw", "rwt", "wa", "").map {
                    runCatching { h.backend.resolveAccess(ProviderIds.note(note.id), it) }.exceptionOrNull()
                }
            assertThat(errors.all { it is UnsupportedOperationException }).isTrue()
        }

    @Test
    public fun `write mode on a chat is refused`() =
        runTest {
            val h = Harness().apply { unlock() }
            val chat = h.chat()
            val error = runCatching { h.backend.resolveAccess(ProviderIds.note(chat.id), "w") }.exceptionOrNull()
            assertThat(error).isInstanceOf(UnsupportedOperationException::class.java)
        }

    @Test
    public fun `write mode on an attachment is refused`() =
        runTest {
            val h = Harness().apply { unlock() }
            val att = h.attachment()
            val error = runCatching { h.backend.resolveAccess(ProviderIds.attachment(att.id), "w") }.exceptionOrNull()
            assertThat(error).isInstanceOf(UnsupportedOperationException::class.java)
        }

    @Test
    public fun `opening a directory is refused`() =
        runTest {
            val h = Harness().apply { unlock() }
            val error = runCatching { h.backend.resolveAccess(ProviderIds.NOTES_DOCUMENT_ID, "r") }.exceptionOrNull()
            assertThat(error).isInstanceOf(FileNotFoundException::class.java)
        }

    @Test
    public fun `resolveAccess throws when locked`() =
        runTest {
            val h = Harness().apply { unlock() }
            val note = h.note()
            h.lock()
            val error = runCatching { h.backend.resolveAccess(ProviderIds.note(note.id), "r") }.exceptionOrNull()
            assertThat(error).isInstanceOf(FileNotFoundException::class.java)
        }

    // ---- read ----------------------------------------------------------------

    @Test
    public fun `read of a note streams exactly what exportMarkdown produces`() =
        runTest {
            val h = Harness().apply { unlock() }
            val note = h.note(body = "# Heading\n\nbody text")
            val out = ByteArrayOutputStream()
            h.backend.read(ProviderIds.note(note.id), out)
            assertThat(out.toByteArray()).isEqualTo(h.exported(note))
        }

    @Test
    public fun `read of a note byte count matches the listed size`() =
        runTest {
            val h = Harness().apply { unlock() }
            val note = h.note(body = "sized body")
            val out = ByteArrayOutputStream()
            h.backend.read(ProviderIds.note(note.id), out)
            val listed = h.backend.document(ProviderIds.note(note.id))[DocumentsContract.Document.COLUMN_SIZE]
            assertThat(out.size().toLong()).isEqualTo(listed)
        }

    @Test
    public fun `read of a chat streams its materialized transcript`() =
        runTest {
            val h = Harness().apply { unlock() }
            val chat = h.chat()
            val out = ByteArrayOutputStream()
            h.backend.read(ProviderIds.note(chat.id), out)
            assertThat(out.toString(Charsets.UTF_8.name())).contains("**user:** hello")
        }

    @Test
    public fun `read of an attachment streams the original bytes`() =
        runTest {
            val h = Harness().apply { unlock() }
            val bytes = ByteArray(20_000) { (it % 251).toByte() }
            val att = h.attachment(bytes = bytes)
            val out = ByteArrayOutputStream()
            h.backend.read(ProviderIds.attachment(att.id), out)
            assertThat(out.toByteArray()).isEqualTo(bytes)
        }

    @Test
    public fun `read writes nothing when locked`() =
        runTest {
            val h = Harness().apply { unlock() }
            val note = h.note()
            h.lock()
            val out = ByteArrayOutputStream()
            runCatching { h.backend.read(ProviderIds.note(note.id), out) }
            assertThat(out.size()).isEqualTo(0)
        }

    @Test
    public fun `read of an attachment stops as soon as the vault locks mid-stream`() =
        runTest {
            val h = Harness().apply { unlock() }
            val bytes = ByteArray(64 * 1024) { 7 }
            val att = h.attachment(bytes = bytes)
            val sink = ByteArrayOutputStream()
            val lockingSink =
                object : OutputStream() {
                    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

                    override fun write(
                        b: ByteArray,
                        off: Int,
                        len: Int,
                    ) {
                        sink.write(b, off, len)
                        h.lock()
                    }
                }
            runCatching { h.backend.read(ProviderIds.attachment(att.id), lockingSink) }
            assertThat(sink.size()).isAtMost(VaultDocumentsBackend.COPY_BUFFER_BYTES)
        }

    @Test
    public fun `read of an attachment throws vault locked when the vault locks mid-stream`() =
        runTest {
            val h = Harness().apply { unlock() }
            val att = h.attachment(bytes = ByteArray(64 * 1024) { 7 })
            val lockingSink =
                object : OutputStream() {
                    override fun write(b: Int) = h.lock()

                    override fun write(
                        b: ByteArray,
                        off: Int,
                        len: Int,
                    ) = h.lock()
                }
            val error = runCatching { h.backend.read(ProviderIds.attachment(att.id), lockingSink) }.exceptionOrNull()
            assertThat(error).hasMessageThat().isEqualTo(VaultDocumentsBackend.VAULT_LOCKED)
        }

    // ---- writeNote -----------------------------------------------------------

    @Test
    public fun `writeNote replaces the body`() =
        runTest {
            val h = Harness().apply { unlock() }
            val note = h.note(body = "old")
            h.backend.writeNote(ProviderIds.note(note.id), "new body".toByteArray())
            assertThat(h.repo.getDocument(note.id)!!.bodyMd).isEqualTo("new body")
        }

    @Test
    public fun `writeNote takes the title from the written frontmatter`() =
        runTest {
            val h = Harness().apply { unlock() }
            val note = h.note(title = "Old Title")
            val text =
                Frontmatter.render(
                    buildJsonObject { put(FrontmatterKeys.TITLE, JsonPrimitive("New Title")) },
                    "body",
                )
            h.backend.writeNote(ProviderIds.note(note.id), text.toByteArray())
            assertThat(h.repo.getDocument(note.id)!!.title).isEqualTo("New Title")
        }

    @Test
    public fun `writeNote replaces the frontmatter but the id key always survives`() =
        runTest {
            val h = Harness().apply { unlock() }
            val note = h.note()
            val text =
                Frontmatter.render(
                    buildJsonObject {
                        put(FrontmatterKeys.TAGS, JsonArray(listOf(JsonPrimitive("new"))))
                    },
                    "body",
                )
            h.backend.writeNote(ProviderIds.note(note.id), text.toByteArray())
            val frontmatter = h.repo.getDocument(note.id)!!.frontmatter
            assertThat(
                frontmatter,
            ).containsExactly(
                FrontmatterKeys.TAGS,
                JsonArray(listOf(JsonPrimitive("new"))),
                FrontmatterKeys.ID,
                JsonPrimitive(note.id),
            )
        }

    @Test
    public fun `writeNote without a frontmatter block keeps the existing frontmatter and title`() =
        runTest {
            val h = Harness().apply { unlock() }
            val note = h.note(title = "Kept")
            h.backend.writeNote(ProviderIds.note(note.id), "just a body".toByteArray())
            val updated = h.repo.getDocument(note.id)!!
            assertThat(updated.title to updated.frontmatter).isEqualTo("Kept" to note.frontmatter)
        }

    @Test
    public fun `writeNote enqueues ingest for the document`() =
        runTest {
            val h = Harness().apply { unlock() }
            val note = h.note()
            h.backend.writeNote(ProviderIds.note(note.id), "changed".toByteArray())
            val queued = h.repo.dequeueIngest(100).single { it.docId == note.id }
            assertThat(queued.reason).isEqualTo(IngestReason.UPDATED)
        }

    @Test
    public fun `writeNote round-trips what read produced`() =
        runTest {
            val h = Harness().apply { unlock() }
            val note = h.note(body = "round trip")
            val out = ByteArrayOutputStream()
            h.backend.read(ProviderIds.note(note.id), out)
            h.backend.writeNote(ProviderIds.note(note.id), out.toByteArray())
            val after = h.repo.getDocument(note.id)!!
            assertThat(after.bodyMd to after.frontmatter).isEqualTo(note.bodyMd to note.frontmatter)
        }

    @Test
    public fun `writeNote normalizes CRLF line endings so the frontmatter block still parses`() =
        runTest {
            val h = Harness().apply { unlock() }
            val note = h.note(title = "Old")
            val crlf = "---\r\ntitle: Windows\r\n---\r\nline one\r\nline two"
            h.backend.writeNote(ProviderIds.note(note.id), crlf.toByteArray())
            val updated = h.repo.getDocument(note.id)!!
            assertThat(updated.title to updated.bodyMd).isEqualTo("Windows" to "line one\nline two")
        }

    @Test
    public fun `writeNote strips a UTF-8 byte order mark`() =
        runTest {
            val h = Harness().apply { unlock() }
            val note = h.note()
            val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
            h.backend.writeNote(ProviderIds.note(note.id), bom + "body".toByteArray())
            assertThat(h.repo.getDocument(note.id)!!.bodyMd).isEqualTo("body")
        }

    @Test
    public fun `writeNote refuses a chat`() =
        runTest {
            val h = Harness().apply { unlock() }
            val chat = h.chat()
            val error =
                runCatching {
                    h.backend.writeNote(
                        ProviderIds.note(chat.id),
                        "x".toByteArray(),
                    )
                }.exceptionOrNull()
            assertThat(error).isInstanceOf(UnsupportedOperationException::class.java)
        }

    @Test
    public fun `writeNote refuses an attachment id`() =
        runTest {
            val h = Harness().apply { unlock() }
            val att = h.attachment()
            val error =
                runCatching {
                    h.backend.writeNote(
                        ProviderIds.attachment(att.id),
                        "x".toByteArray(),
                    )
                }.exceptionOrNull()
            assertThat(error).isInstanceOf(FileNotFoundException::class.java)
        }

    @Test
    public fun `writeNote leaves the document untouched when locked`() =
        runTest {
            val h = Harness().apply { unlock() }
            val note = h.note(body = "untouched")
            h.lock()
            runCatching { h.backend.writeNote(ProviderIds.note(note.id), "changed".toByteArray()) }
            assertThat(h.repo.getDocument(note.id)!!.bodyMd).isEqualTo("untouched")
        }

    @Test
    public fun `writeNote throws vault locked when locked`() =
        runTest {
            val h = Harness().apply { unlock() }
            val note = h.note()
            h.lock()
            val error =
                runCatching {
                    h.backend.writeNote(
                        ProviderIds.note(note.id),
                        "x".toByteArray(),
                    )
                }.exceptionOrNull()
            assertThat(error).hasMessageThat().isEqualTo(VaultDocumentsBackend.VAULT_LOCKED)
        }

    // ---- readBounded ---------------------------------------------------------

    @Test
    public fun `readBounded returns everything under the limit`() {
        val bytes = ByteArray(10_000) { it.toByte() }
        assertThat(VaultDocumentsBackend.readBounded(ByteArrayInputStream(bytes), limit = 10_000)).isEqualTo(bytes)
    }

    @Test
    public fun `readBounded refuses input over the limit`() {
        val error =
            runCatching {
                VaultDocumentsBackend.readBounded(ByteArrayInputStream(ByteArray(11)), limit = 10)
            }.exceptionOrNull()
        assertThat(error).isInstanceOf(IOException::class.java)
    }

    // ---- end-to-end against a real UnlockManager -----------------------------

    @Test
    public fun `gate follows the UnlockManager state across unlock and lock`() =
        runTest {
            val manager =
                UnlockManager(
                    keyProvider = FakeVaultKeyProvider(),
                    clock = TestClock(),
                    scope = null,
                    installShutdownHook = false,
                )
            val repo = InMemoryVaultRepository()
            val backend = VaultDocumentsBackend(repo, ExportServiceImpl(repo), manager.state)

            val whileLocked = runCatching { backend.roots(iconResId = 1) }.isFailure
            manager.unlockWith(VaultKeyProvider.Factor.BIOMETRIC) { UnlockResult.Success(AuthorizationToken(1L)) }
            val whileUnlocked = runCatching { backend.roots(iconResId = 1) }.isSuccess
            manager.lockAndAwait(LockReason.IDLE_TIMEOUT)
            val afterLock = runCatching { backend.roots(iconResId = 1) }.isFailure

            assertThat(listOf(whileLocked, whileUnlocked, afterLock)).containsExactly(true, true, true)
        }
}
