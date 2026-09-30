package app.skein.core.vault.repository

import androidx.sqlite.SQLiteConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.skein.core.model.Citation
import app.skein.core.model.CitationRecord
import app.skein.core.model.CitationSourceKind
import app.skein.core.model.DocId
import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.Edge
import app.skein.core.model.EdgeKind
import app.skein.core.model.FileDeletionReceipt
import app.skein.core.model.FileDeletionTargetChangedException
import app.skein.core.model.FileLifecycle
import app.skein.core.model.FrontmatterKeys
import app.skein.core.model.Locator
import app.skein.core.model.NewChunk
import app.skein.core.model.NewDocument
import app.skein.core.model.NewMessage
import app.skein.core.model.Role
import app.skein.core.model.VaultRepository
import app.skein.core.vault.blob.AttachmentStore
import app.skein.core.vault.blob.FileAttachmentStore
import app.skein.core.vault.db.SkeinSQLiteDriver
import app.skein.core.vault.db.migrations.Migrator
import app.skein.core.vault.export.ExportServiceImpl
import app.skein.core.vault.index.IndexSql
import app.skein.core.vault.index.IndexStoreImpl
import app.skein.core.vault.lifecycle.CreateResult
import app.skein.core.vault.lifecycle.OpenResult
import app.skein.core.vault.lifecycle.VaultLifecycle
import app.skein.core.vault.lifecycle.VaultPaths
import app.skein.core.vault.transfer.ImportServiceImpl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** LC-09: synthetic encrypted SQLite vaults and actual SKAT files; no owner/device content. */
@RunWith(AndroidJUnit4::class)
public class FileLifecycleInstrumentedTest {
    private val lifecycles = mutableListOf<VaultLifecycle>()
    private val dirs = mutableListOf<File>()

    @After
    public fun tearDown() {
        runBlocking { lifecycles.forEach { it.close() } }
        dirs.forEach { it.deleteRecursively() }
    }

    @Test
    public fun file_delete_removes_all_text_indexes_and_bytes_but_retains_quotes_and_other_files(): Unit =
        runBlocking {
            val v = openVault()
            val attachment = attachment(v)
            val first = note(v, attachment.id, "First", "uniquefiletokennine")
            val second = note(v, attachment.id, "Second", "uniquefiletokenten")
            val unrelated = attachment(v)
            val kept = note(v, unrelated.id, "Kept", "kepttoken")
            val aiout =
                v.repo.createDocument(
                    NewDocument(DocumentKind.AIOUT, "Saved output", "kept", frontmatter = source(attachment.id)),
                )
            val chat = v.repo.createDocument(NewDocument(DocumentKind.CHAT, "Saved quote", ""))
            val citation =
                Citation(
                    1,
                    first.id,
                    first.contentHash!!,
                    Locator(0, first.bodyMd!!.length, 0),
                    first.bodyMd!!,
                    CitationSourceKind.LEXICAL,
                )
            v.repo.appendMessage(
                chat.id,
                NewMessage(
                    Role.ASSISTANT,
                    "saved [1]",
                    citations = CitationRecord(retrieved = listOf(citation), cited = listOf(1)),
                ),
            )
            val chunks =
                (listOf(first, second, kept)).flatMap { doc ->
                    v.index.replaceChunks(doc.id, listOf(NewChunk(0, doc.bodyMd!!, 1)), "synthetic", 1)
                }
            v.index.putEmbeddings(chunks.map { it to ByteArray(256) { 1 } })
            v.index.replaceEdges(
                first.id,
                setOf(EdgeKind.CITE),
                listOf(Edge(first.id, attachment.id, EdgeKind.CITE, createdAt = 1)),
            )
            val target = checkNotNull(v.repo.resolveFileDeletion(first.id))
            assertEquals(setOf(attachment.id, first.id, second.id), target.documentIds)
            assertFalse(v.index.bm25("uniquefiletokennine", 10).isEmpty())

            val receipt = v.repo.deleteFile(target)

            assertTrue(receipt.committed)
            assertFalse(receipt.attachmentCleanupPending)
            assertEquals(setOf(aiout.id), receipt.detachedDocumentIds)
            assertFalse(aiout.id in receipt.documentIds)
            for (id in target.documentIds) {
                assertNull(v.repo.getDocument(id))
                assertTrue(v.index.edgesFrom(id).isEmpty())
                assertTrue(v.index.edgesTo(id).isEmpty())
            }
            assertTrue(v.index.chunksForDocs(listOf(first.id, second.id), 10).isEmpty())
            assertTrue(v.index.bm25("uniquefiletokennine", 10).isEmpty())
            assertEquals(1L, count(v.writer, "SELECT count(*) FROM chunks_vec"))
            assertTrue(v.repo.dequeueIngest(100).none { it.docId in target.documentIds })
            assertFalse(File(v.dir, "attachments/${attachment.id}").exists())
            assertArrayEquals(BYTES, v.repo.openAttachment(unrelated.id).use { it.readBytes() })
            assertEquals(kept, v.repo.getDocument(kept.id))
            assertDetached(aiout, checkNotNull(v.repo.getDocument(aiout.id)))
            assertEquals(
                first.bodyMd,
                v.repo
                    .listMessages(chat.id)
                    .single()
                    .citations!!
                    .retrieved
                    .single()
                    .excerpt,
            )
            assertTrue(v.repo.deleteFile(target).committed)
            v.lifecycle.close()
            val reopened = openVault(v.dir)
            assertNull(reopened.repo.getDocument(first.id))
            assertNull(reopened.repo.getDocument(attachment.id))
            assertNotNull(reopened.repo.getDocument(kept.id))
        }

    @Test
    public fun failed_outer_commit_preserves_every_row_index_and_blob(): Unit =
        runBlocking {
            val v = openVault()
            val attachment = attachment(v)
            val note = note(v, attachment.id)
            val kept = note(v, null, "Kept")
            val chunks = v.index.replaceChunks(note.id, listOf(NewChunk(0, "rollbacktoken", 1)), "synthetic", 1)
            val target = checkNotNull(v.repo.resolveFileDeletion(attachment.id))
            var receipt: FileDeletionReceipt? = null
            var reachedCommit = false
            val failure =
                runCatching {
                    v.repo.transaction {
                        receipt = v.repo.deleteFile(target)
                        assertFalse(receipt!!.committed)
                        assertArrayEquals(BYTES, v.repo.openAttachment(attachment.id).use { it.readBytes() })
                        exec(v.writer, "PRAGMA defer_foreign_keys = ON")
                        v.writer
                            .prepare(
                                "UPDATE documents SET persona_id = 'missing-synthetic-persona' WHERE id = ?",
                            ).use {
                                it.bindText(1, kept.id)
                                it.step()
                            }
                        reachedCommit = true
                    }
                }
            assertTrue(reachedCommit)
            assertTrue(failure.isFailure)
            assertFalse(receipt!!.committed)
            assertNotNull(v.repo.getDocument(attachment.id))
            assertNotNull(v.repo.getDocument(note.id))
            assertEquals(chunks, v.index.bm25("rollbacktoken", 10).map { it.chunkId })
            assertArrayEquals(BYTES, v.repo.openAttachment(attachment.id).use { it.readBytes() })
            assertEquals(0, v.repo.sweepOrphanAttachments().removedBlobs)
        }

    @Test
    public fun changed_membership_is_rejected_before_any_partial_delete(): Unit =
        runBlocking {
            val v = openVault()
            val attachment = attachment(v)
            val first = note(v, attachment.id)
            val target = checkNotNull(v.repo.resolveFileDeletion(first.id))
            val later = note(v, attachment.id, "Later")
            assertTrue(
                runCatching { v.repo.deleteFile(target) }.exceptionOrNull() is FileDeletionTargetChangedException,
            )
            assertNotNull(v.repo.getDocument(first.id))
            assertNotNull(v.repo.getDocument(later.id))
            assertArrayEquals(BYTES, v.repo.openAttachment(attachment.id).use { it.readBytes() })
        }

    @Test
    public fun failed_post_commit_unlink_is_pending_and_recovered_after_reopen(): Unit =
        runBlocking {
            val v =
                openVault(decorate = { store ->
                    object : AttachmentStore by store {
                        override suspend fun delete(id: DocId): Unit = throw IOException("synthetic unlink failure")
                    }
                })
            val attachment = attachment(v)
            val note = note(v, attachment.id)
            val aiout =
                v.repo.createDocument(
                    NewDocument(
                        DocumentKind.AIOUT,
                        "Saved output",
                        "synthetic output",
                        frontmatter = source(attachment.id),
                    ),
                )
            val chat =
                v.repo.createDocument(
                    NewDocument(DocumentKind.CHAT, "Saved chat", "synthetic body", frontmatter = source(attachment.id)),
                )
            v.repo.appendMessage(chat.id, NewMessage(Role.USER, "synthetic message"))
            // appendMessage materializes the chat transcript before file deletion starts.
            val chatBeforeDelete = checkNotNull(v.repo.getDocument(chat.id))
            val messagesBeforeDelete = v.repo.listMessages(chat.id)
            assertEquals("**user:** synthetic message", chatBeforeDelete.bodyMd)
            val receipt = v.repo.deleteFile(checkNotNull(v.repo.resolveFileDeletion(attachment.id)))
            assertTrue(receipt.committed)
            assertTrue(receipt.attachmentCleanupPending)
            assertEquals(setOf(aiout.id, chat.id), receipt.detachedDocumentIds)
            assertEquals(setOf(attachment.id, note.id), receipt.documentIds)
            assertDetached(aiout, checkNotNull(v.repo.getDocument(aiout.id)))
            assertDetached(chatBeforeDelete, checkNotNull(v.repo.getDocument(chat.id)))
            assertEquals(messagesBeforeDelete, v.repo.listMessages(chat.id))
            assertEquals(
                "synthetic message",
                v.repo
                    .listMessages(chat.id)
                    .single()
                    .contentMd,
            )
            assertNull(v.repo.getDocument(note.id))
            assertNull(v.repo.getDocument(attachment.id))
            assertTrue(File(v.dir, "attachments/${attachment.id}").isFile)
            v.lifecycle.close()

            val reopened = openVault(v.dir)
            assertEquals(1, reopened.repo.sweepOrphanAttachments().removedBlobs)
            assertFalse(File(v.dir, "attachments/${attachment.id}").exists())
            assertEquals(0, reopened.repo.sweepOrphanAttachments().removedBlobs)
            assertDetached(aiout, checkNotNull(reopened.repo.getDocument(aiout.id)))
            assertDetached(chatBeforeDelete, checkNotNull(reopened.repo.getDocument(chat.id)))
            assertEquals(messagesBeforeDelete, reopened.repo.listMessages(chat.id))
        }

    @Test
    public fun a_new_surviving_source_dependent_requires_fresh_editor_reservations(): Unit =
        runBlocking {
            val v = openVault()
            val attachment = attachment(v)
            val extracted = note(v, attachment.id)
            val target = checkNotNull(v.repo.resolveFileDeletion(attachment.id))
            val later =
                v.repo.createDocument(
                    NewDocument(DocumentKind.AIOUT, "Later", "synthetic output", frontmatter = source(attachment.id)),
                )
            val failure = runCatching { v.repo.deleteFile(target) }.exceptionOrNull()
            assertTrue(failure is FileDeletionTargetChangedException)
            assertEquals(later, v.repo.getDocument(later.id))
            assertNotNull(v.repo.getDocument(extracted.id))
            val fresh = checkNotNull(v.repo.resolveFileDeletion(attachment.id))
            assertEquals(setOf(attachment.id, extracted.id, later.id), fresh.affectedDocumentIds)
            assertEquals(setOf(attachment.id, extracted.id), fresh.documentIds)
        }

    @Test
    public fun quoting_chat_count_deduplicates_multiple_text_sources_and_turns(): Unit =
        runBlocking {
            val v = openVault()
            val attachment = attachment(v)
            val first = note(v, attachment.id, "First")
            val second = note(v, attachment.id, "Second")
            val chat = v.repo.createDocument(NewDocument(DocumentKind.CHAT, "Quoting chat", ""))
            val citations =
                listOf(first, second).mapIndexed { index, document ->
                    Citation(
                        index + 1,
                        document.id,
                        document.contentHash!!,
                        Locator(0, document.bodyMd!!.length),
                        document.bodyMd!!,
                        CitationSourceKind.LEXICAL,
                    )
                }
            repeat(2) {
                v.repo.appendMessage(
                    chat.id,
                    NewMessage(
                        Role.ASSISTANT,
                        "synthetic [1] [2]",
                        citations = CitationRecord(retrieved = citations, cited = listOf(1, 2)),
                    ),
                )
            }
            val target = checkNotNull(v.repo.resolveFileDeletion(attachment.id))
            assertEquals(1, v.repo.countChatsCitingFile(target))
            assertEquals(1, v.repo.countChatsCiting(first.id))
            assertEquals(1, v.repo.countChatsCiting(second.id))
            v.repo.deleteFile(target)
            assertEquals(1, v.repo.countChatsCitingFile(target))
            assertEquals(2, v.repo.listMessages(chat.id).size)
        }

    @Test
    public fun unlock_sweep_retains_shared_references_and_missing_blob_metadata(): Unit =
        runBlocking {
            val v = openVault()
            val sharedId = "018f2b6e-6c3a-7c3e-8f2a-6b1e2d3c4a5b"
            v.store.write(sharedId) { it.write(BYTES) }
            val first = note(v, sharedId)
            val second = note(v, sharedId, "Second")
            val missing = attachment(v)
            v.store.delete(missing.id)
            assertEquals(0, v.repo.sweepOrphanAttachments().removedBlobs)
            assertNotNull(v.repo.getDocument(missing.id))
            v.repo.deleteDocument(first.id)
            assertEquals(0, v.repo.sweepOrphanAttachments().removedBlobs)
            v.repo.deleteDocument(second.id)
            assertEquals(1, v.repo.sweepOrphanAttachments().removedBlobs)
            assertNotNull(v.repo.getDocument(missing.id))
        }

    @Test
    public fun import_reservation_survives_blob_landing_before_metadata_and_sweep_does_not_deadlock(): Unit =
        runBlocking {
            val landed = CompletableDeferred<Unit>()
            val permitInsert = CompletableDeferred<Unit>()
            val v =
                openVault(decorate = { store ->
                    object : AttachmentStore by store {
                        override suspend fun write(
                            id: DocId,
                            write: suspend (OutputStream) -> Unit,
                        ): Long {
                            val size = store.write(id, write)
                            landed.complete(Unit)
                            permitInsert.await()
                            return size
                        }
                    }
                })
            var created: Document? = null
            val importer = launch(Dispatchers.IO) { created = attachment(v) }
            withTimeout(10_000) { landed.await() }
            assertEquals(1, withTimeout(10_000) { v.repo.sweepOrphanAttachments() }.retainedActiveFiles)
            permitInsert.complete(Unit)
            withTimeout(10_000) { importer.join() }
            assertNotNull(created)
            assertEquals(0, v.repo.sweepOrphanAttachments().removedBlobs)
            assertArrayEquals(BYTES, v.repo.openAttachment(created!!.id).use { it.readBytes() })
        }

    @Test
    public fun late_ingest_cannot_restore_deleted_text_chunks_vectors_or_edges(): Unit =
        runBlocking {
            val v = openVault()
            val attachment = attachment(v)
            val note = note(v, attachment.id)
            val chunks = v.index.replaceChunks(note.id, listOf(NewChunk(0, "beforetoken", 1)), "synthetic", 1)
            v.repo.deleteFile(checkNotNull(v.repo.resolveFileDeletion(attachment.id)))
            // Negative control: the exact pre-LC09 embedding SQL admits an orphan
            // in this real encrypted vault. It is deliberately confined to this fixture.
            v.writer.prepare(IndexSql.UPSERT_EMBEDDING).use {
                it.bindLong(1, chunks.single())
                it.bindBlob(2, ByteArray(256) { 1 })
                it.step()
            }
            assertEquals(1L, count(v.writer, "SELECT count(*) FROM chunks_vec"))
            v.repo.sweepIndexOrphans()
            assertEquals(0L, count(v.writer, "SELECT count(*) FROM chunks_vec"))
            assertTrue(v.index.replaceChunks(note.id, listOf(NewChunk(0, "latetoken", 1)), "synthetic", 1).isEmpty())
            v.index.putEmbeddings(chunks.map { it to ByteArray(256) { 1 } })
            v.index.replaceEdges(
                note.id,
                setOf(EdgeKind.CITE),
                listOf(Edge(note.id, attachment.id, EdgeKind.CITE, createdAt = 1)),
            )
            assertTrue(v.index.bm25("latetoken", 10).isEmpty())
            assertTrue(v.index.edgesFrom(note.id).isEmpty())
            assertEquals(0L, count(v.writer, "SELECT count(*) FROM chunks_vec"))
        }

    @Test
    public fun lock_cancels_uncommitted_file_delete_without_removing_bytes(): Unit =
        runBlocking {
            val v = openVault()
            val attachment = attachment(v)
            val note = note(v, attachment.id)
            val target = checkNotNull(v.repo.resolveFileDeletion(attachment.id))
            val rowsRemoved = CompletableDeferred<Unit>()
            var receipt: FileDeletionReceipt? = null
            val deletion =
                launch(Dispatchers.IO) {
                    v.repo.transaction {
                        receipt = v.repo.deleteFile(target)
                        rowsRemoved.complete(Unit)
                        CompletableDeferred<Unit>().await()
                    }
                }
            withTimeout(10_000) { rowsRemoved.await() }
            assertTrue(runCatching { v.repo.quiesce(0) }.isFailure)
            withTimeout(10_000) { deletion.join() }
            assertFalse(receipt!!.committed)
            v.lifecycle.close()
            val reopened = openVault(v.dir)
            assertNotNull(reopened.repo.getDocument(note.id))
            assertNotNull(reopened.repo.getDocument(attachment.id))
            assertArrayEquals(BYTES, reopened.repo.openAttachment(attachment.id).use { it.readBytes() })
        }

    @Test
    public fun pdf_extraction_finishing_after_file_delete_cannot_create_its_note(): Unit =
        runBlocking {
            val v = openVault()
            val raced =
                object : VaultRepository by v.repo {
                    override suspend fun createAttachment(
                        title: String,
                        mimeType: String,
                        write: suspend (OutputStream) -> Unit,
                    ): Document =
                        v.repo.createAttachment(title, mimeType, write).also {
                            v.repo.deleteFile(checkNotNull(v.repo.resolveFileDeletion(it.id)))
                        }
                }
            val result = runCatching { ImportServiceImpl(raced).importPdf("synthetic.pdf", BYTES.inputStream(), null) }
            assertEquals("Imported file was deleted", result.exceptionOrNull()?.message)
            assertEquals(0L, count(v.writer, "SELECT count(*) FROM documents"))
            assertTrue(File(v.dir, "attachments").listFiles()!!.isEmpty())
        }

    @Test
    public fun zip_attachment_and_existing_text_link_before_a_delete_can_observe_them(): Unit =
        runBlocking {
            val original = openVault()
            val attachment = attachment(original)
            note(original, attachment.id, "First")
            note(original, attachment.id, "Second")
            val archive =
                ByteArrayOutputStream()
                    .also {
                        ExportServiceImpl(
                            original.repo,
                        ).exportVaultZip(it)
                    }.toByteArray()
            val restored = openVault()
            var observedNotes = -1
            val raced =
                object : VaultRepository by restored.repo, FileLifecycle by restored.repo {
                    override suspend fun createAttachmentWithExtractedNotes(
                        title: String,
                        mimeType: String,
                        extractedNoteIds: Set<DocId>,
                        expectedSource: DocId,
                        write: suspend (OutputStream) -> Unit,
                    ): Document =
                        restored.repo
                            .createAttachmentWithExtractedNotes(
                                title,
                                mimeType,
                                extractedNoteIds,
                                expectedSource,
                                write,
                            ).also {
                                val target = checkNotNull(restored.repo.resolveFileDeletion(it.id))
                                observedNotes = target.extractedNotes.size
                                restored.repo.deleteFile(target)
                            }
                }
            ImportServiceImpl(raced).importVaultZip(archive.inputStream(), null)
            assertEquals(2, observedNotes)
            assertEquals(0L, count(restored.writer, "SELECT count(*) FROM documents"))
            assertTrue(File(restored.dir, "attachments").listFiles()!!.isEmpty())
            assertArrayEquals(BYTES, original.repo.openAttachment(attachment.id).use { it.readBytes() })
        }

    @Test
    public fun changed_import_source_rolls_back_attachment_linkage_and_leaves_unrelated_note_intact(): Unit =
        runBlocking {
            val v = openVault()
            val first = note(v, "archive-id", "First")
            val unrelated = note(v, "different-source", "Unrelated")
            val result =
                runCatching {
                    v.repo.createAttachmentWithExtractedNotes(
                        "synthetic.bin",
                        "application/octet-stream",
                        setOf(first.id, unrelated.id),
                        "archive-id",
                    ) {
                        it.write(BYTES)
                    }
                }
            assertTrue(result.isFailure)
            assertEquals(first, v.repo.getDocument(first.id))
            assertEquals(unrelated, v.repo.getDocument(unrelated.id))
            assertEquals(2L, count(v.writer, "SELECT count(*) FROM documents"))
            assertEquals(1, v.repo.sweepOrphanAttachments().removedBlobs)
        }

    @Test
    public fun zip_with_attachment_entry_before_notes_links_later_notes_atomically(): Unit =
        runBlocking {
            val original = openVault()
            val attachment = attachment(original)
            note(original, attachment.id, "First")
            note(original, attachment.id, "Second")
            val archive =
                ByteArrayOutputStream()
                    .also {
                        ExportServiceImpl(
                            original.repo,
                        ).exportVaultZip(it)
                    }.toByteArray()
            val entries =
                buildList {
                    ZipInputStream(archive.inputStream()).use { zip ->
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            add(entry.name to zip.readBytes())
                        }
                    }
                }
            val reordered = ByteArrayOutputStream()
            ZipOutputStream(reordered).use { zip ->
                for ((name, bytes) in entries.sortedBy {
                    if (it.first ==
                        ".skein/manifest.json"
                    ) {
                        0
                    } else if (it.first.startsWith("attachments/")) {
                        1
                    } else {
                        2
                    }
                }) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            val restored = openVault()
            val result = ImportServiceImpl(restored.repo).importVaultZip(reordered.toByteArray().inputStream(), null)
            assertEquals(3, result.imported)
            val first = checkNotNull(restored.repo.findByTitle("First"))
            val target = checkNotNull(restored.repo.resolveFileDeletion(first.id))
            assertEquals(2, target.extractedNotes.size)
            assertArrayEquals(BYTES, restored.repo.openAttachment(target.attachment.id).use { it.readBytes() })
            restored.repo.deleteFile(target)
            assertEquals(0L, count(restored.writer, "SELECT count(*) FROM documents"))
        }

    private data class Vault(
        val dir: File,
        val lifecycle: VaultLifecycle,
        val repo: VaultRepositoryImpl,
        val index: IndexStoreImpl,
        val writer: SQLiteConnection,
        val store: FileAttachmentStore,
    )

    private suspend fun openVault(
        dir: File = newDir(),
        decorate: (FileAttachmentStore) -> AttachmentStore = { it },
    ): Vault {
        val paths = VaultPaths(dir)
        val lifecycle = VaultLifecycle({ SkeinSQLiteDriver(it) }, { Migrator(it) }, paths, readerCount = 3)
        lifecycles += lifecycle
        val result = if (paths.databaseFile.exists()) lifecycle.open(key()) else lifecycle.create(key())
        check(result is CreateResult.Success || result is OpenResult.Success)
        val pool = lifecycle.connectionPool()
        val store = FileAttachmentStore(File(dir, "attachments"), ::key)
        return Vault(
            dir,
            lifecycle,
            VaultRepositoryImpl(pool.writer(), decorate(store), pool.readers().take(2)),
            IndexStoreImpl(pool.readers()[2]),
            pool.writer(),
            store,
        )
    }

    private fun newDir(): File =
        Files
            .createTempDirectory(
                InstrumentationRegistry
                    .getInstrumentation()
                    .targetContext.cacheDir
                    .toPath(),
                "lc09-",
            ).toFile()
            .also {
                dirs +=
                    it
            }

    private suspend fun attachment(v: Vault): Document =
        v.repo.createAttachment("synthetic.bin", "application/octet-stream") {
            it.write(BYTES)
        }

    private suspend fun note(
        v: Vault,
        sourceId: String?,
        title: String = "Extracted",
        body: String = "synthetic extracted text",
    ): Document =
        v.repo.createDocument(
            NewDocument(
                DocumentKind.NOTE,
                title,
                body,
                frontmatter =
                    if (sourceId ==
                        null
                    ) {
                        JsonObject(emptyMap())
                    } else {
                        source(sourceId)
                    },
            ),
        )

    private fun source(id: String) = JsonObject(mapOf(FrontmatterKeys.SOURCE to JsonPrimitive(id)))

    private fun assertDetached(
        before: Document,
        after: Document,
    ) {
        assertEquals(before.id, after.id)
        assertEquals(before.kind, after.kind)
        assertEquals(before.title, after.title)
        assertEquals(before.bodyMd, after.bodyMd)
        assertEquals(before.personaId, after.personaId)
        assertEquals(before.createdAt, after.createdAt)
        assertEquals(JsonObject(before.frontmatter - FrontmatterKeys.SOURCE), after.frontmatter)
    }

    private fun count(
        connection: SQLiteConnection,
        sql: String,
    ): Long =
        connection.prepare(sql).use {
            check(it.step())
            it.getLong(0)
        }

    private fun exec(
        connection: SQLiteConnection,
        sql: String,
    ) {
        connection.prepare(sql).use { it.step() }
    }

    private fun key(): ByteArray = ByteArray(32) { (it + 13).toByte() }

    private companion object {
        val BYTES = byteArrayOf(1, 4, 7, 9)
    }
}
