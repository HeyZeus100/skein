package app.skein.shell

import android.content.Context
import androidx.biometric.BiometricPrompt
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.skein.core.model.ChatDraft
import app.skein.core.model.ChatDraftKey
import app.skein.core.model.Citation
import app.skein.core.model.CitationRecord
import app.skein.core.model.CitationSourceKind
import app.skein.core.model.DocId
import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.FileDeletionReceipt
import app.skein.core.model.FileDeletionTarget
import app.skein.core.model.FileDeletionTargetChangedException
import app.skein.core.model.FileLifecycle
import app.skein.core.model.Locator
import app.skein.core.model.NewDocument
import app.skein.core.model.NewMessage
import app.skein.core.model.Role
import app.skein.core.model.VaultRepository
import app.skein.core.vault.codec.Frontmatter
import app.skein.core.vault.key.RewrapResult
import app.skein.core.vault.key.SetupResult
import app.skein.core.vault.key.UnlockResult
import app.skein.core.vault.key.VaultKeyProvider
import app.skein.core.vault.lifecycle.VaultPaths
import app.skein.feature.editor.notetab.NoteTabState
import app.skein.vault.DeviceVaultOpener
import app.skein.vault.VaultSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Actual SQLCipher/attachment + app services/coordinator + editor reservations. Each case owns a
 * synthetic cache vault and key; no Activity, global provider, owner content or model is used.
 * The controlled editor dispatcher holds ordinary autosave/title work pending until reservation.
 * Repository decorators only observe real operations or inject the named fault, never emulate SQL.
 * Closing/reopening here qualifies the encrypted service boundary, not physical lock-screen UX.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class FileSourceDetachmentInstrumentedTest {
    @Test
    fun dirty_survivor_is_durable_and_reload_preserves_selection_other_metadata_and_unrelated_drafts() =
        runBlocking {
            withFixture { f ->
                val state = f.editor(f.output)
                val unrelated = f.editor(f.unrelated)
                unrelated.editorState.onValueChange(TextFieldValue("Unrelated pending draft", TextRange(4, 11)))
                val unrelatedBefore = unrelated.editorState.value
                f.preparePrompt()
                f.dirty(state)
                // This field changes after the editor loaded: preflush must merge the current row.
                f.actual.repository.updateFrontmatter(
                    f.output.id,
                    JsonObject(f.output.frontmatter + ("external" to JsonPrimitive("current"))),
                )

                assertEquals("File and extracted text deleted.", f.confirm())

                f.assertPreflushWasDurable()
                f.assertDetached(f.actual.repository)
                assertEquals(DIRTY_TITLE, state.title)
                assertEquals(DIRTY_BODY, Frontmatter.parse(state.editorState.source).second)
                assertEquals(
                    TextRange(state.editorState.source.length - 5, state.editorState.source.length - 1),
                    state.editorState.value.selection,
                )
                assertEquals(
                    JsonPrimitive("current"),
                    f.actual.repository
                        .getDocument(f.output.id)!!
                        .frontmatter["external"],
                )
                assertEquals(unrelatedBefore, unrelated.editorState.value)
                assertEquals(f.unrelated, f.actual.repository.getDocument(f.unrelated.id))
                f.assertUnrelated(f.actual.repository)

                // A pasted pre-delete header cannot restore the removed source after successful reload.
                state.editorState.onValueChange(TextFieldValue(Frontmatter.render(f.output.frontmatter, DIRTY_BODY)))
                assertTrue(state.flush())
                assertNull(
                    f.actual.repository
                        .getDocument(f.output.id)!!
                        .frontmatter["source"],
                )
                f.reopen { reopened ->
                    assertEquals(DIRTY_BODY, reopened.repository.getDocument(f.output.id)!!.bodyMd)
                    assertEquals(DIRTY_TITLE, reopened.repository.getDocument(f.output.id)!!.title)
                    assertNull(reopened.repository.getDocument(f.output.id)!!.frontmatter["source"])
                    f.assertUnrelated(reopened.repository)
                }
            }
        }

    @Test
    fun actual_close_and_key_lock_after_commit_preserve_dirty_survivor_on_reopen_and_block_old_writer() =
        runBlocking {
            withFixture { f ->
                val state = f.editor(f.output)
                f.preparePrompt()
                f.dirty(state)
                f.repository.afterCommit = {
                    f.editorScope.cancel()
                    f.actual.close()
                    f.key.lock()
                }

                assertEquals(REOPEN_MESSAGE, f.confirm())

                assertEquals(1, f.repository.postCommitBoundaries)
                assertTrue(f.repository.receipt!!.committed)
                assertNull(f.key.currentKey())
                f.assertPreflushWasDurable()
                assertNotNull(state.loadError)
                f.assertOldWriterBlocked(state)
                assertTrue(
                    runCatching { f.actual.repository.renameDocument(f.output.id, "Old session write") }.isFailure,
                )
                f.reopen { reopened ->
                    f.assertDetached(reopened.repository)
                    f.assertUnrelated(reopened.repository)
                    f.assertOldWriterBlocked(state)
                    f.assertDetached(reopened.repository)
                }
            }
        }

    @Test
    fun reload_read_failure_keeps_old_writer_reserved_and_precommit_content_survives_reopen() =
        runBlocking {
            withFixture { f ->
                val state = f.editor(f.output)
                f.preparePrompt()
                f.dirty(state)
                f.repository.failReload = true

                assertEquals(REOPEN_MESSAGE, f.confirm())

                assertEquals(1, f.repository.reloadFailures)
                assertNotNull(state.loadError)
                f.assertPreflushWasDurable()
                f.assertDetached(f.actual.repository)
                f.assertOldWriterBlocked(state)
                f.reopen { reopened ->
                    f.assertDetached(reopened.repository)
                    f.assertUnrelated(reopened.repository)
                }
            }
        }

    @Test
    fun source_edit_flushed_before_delete_is_revalidated_and_refuses_the_original_target() =
        runBlocking {
            withFixture { f ->
                val state = f.editor(f.output)
                f.preparePrompt()
                f.dirty(state, f.otherAttachment.id)

                assertEquals("This file changed. Open it and try again.", f.confirm())

                assertEquals(1, f.repository.deleteAttempts)
                assertTrue(f.repository.targetChanged)
                assertNull(f.repository.receipt)
                assertTrue(f.pruned.isEmpty())
                val saved = f.actual.repository.getDocument(f.output.id)!!
                assertEquals(DIRTY_BODY, saved.bodyMd)
                assertEquals(DIRTY_TITLE, saved.title)
                assertEquals(JsonPrimitive("dirty"), saved.frontmatter["custom"])
                assertEquals(JsonPrimitive(f.otherAttachment.id), saved.frontmatter["source"])
                f.assertOriginalFilePresent(f.actual.repository)
                f.reopen { reopened ->
                    assertEquals(saved, reopened.repository.getDocument(f.output.id))
                    f.assertOriginalFilePresent(reopened.repository)
                    f.assertUnrelated(reopened.repository)
                }
            }
        }

    @Test
    fun extracted_note_added_after_preflush_is_not_silently_added_to_the_delete_target() =
        runBlocking {
            withFixture { f ->
                val state = f.editor(f.output)
                f.preparePrompt()
                f.dirty(state)
                var added: Document? = null
                f.repository.afterPreflush = {
                    added =
                        f.actual.repository.createDocument(
                            NewDocument(DocumentKind.NOTE, "New extraction", "New text", frontmatter = f.source()),
                        )
                }

                assertEquals("This file changed. Open it and try again.", f.confirm())

                assertTrue(f.repository.targetChanged)
                f.assertPreflushWasDurable()
                assertEquals(setOf(f.attachment.id, f.extracted.id), f.repository.attemptedIds)
                assertTrue(f.pruned.isEmpty())
                assertEquals(added, f.actual.repository.getDocument(checkNotNull(added).id))
                f.assertOriginalFilePresent(f.actual.repository)
                assertEquals(
                    DIRTY_BODY,
                    f.actual.repository
                        .getDocument(f.output.id)!!
                        .bodyMd,
                )
                f.reopen { reopened ->
                    assertEquals(added, reopened.repository.getDocument(checkNotNull(added).id))
                    f.assertOriginalFilePresent(reopened.repository)
                    f.assertUnrelated(reopened.repository)
                }
            }
        }

    @Test
    fun preflush_write_fault_rolls_back_its_sql_transaction_and_refuses_deletion_without_losing_edits() =
        runBlocking {
            withFixture { f ->
                val state = f.editor(f.output)
                f.preparePrompt()
                f.dirty(state)
                f.repository.failNextBody = true

                assertEquals("Couldn't delete \"Disposable source\". Try again.", f.confirm())

                // Snapshot is read after SQL rollback, before coordinator rollback resumes normal saves.
                assertEquals(f.output, f.repository.afterFailedTransaction)
                assertEquals(1, f.repository.bodyFailures)
                assertEquals(0, f.repository.deleteAttempts)
                assertTrue(f.pruned.isEmpty())
                assertEquals(DIRTY_BODY, Frontmatter.parse(state.editorState.source).second)
                assertEquals(DIRTY_TITLE, state.title)
                assertEquals(
                    DIRTY_BODY,
                    f.actual.repository
                        .getDocument(f.output.id)!!
                        .bodyMd,
                )
                assertEquals(
                    DIRTY_TITLE,
                    f.actual.repository
                        .getDocument(f.output.id)!!
                        .title,
                )
                assertEquals(
                    JsonPrimitive("dirty"),
                    f.actual.repository
                        .getDocument(f.output.id)!!
                        .frontmatter["custom"],
                )
                f.assertOriginalFilePresent(f.actual.repository)
                f.reopen { reopened ->
                    assertEquals(DIRTY_BODY, reopened.repository.getDocument(f.output.id)!!.bodyMd)
                    f.assertOriginalFilePresent(reopened.repository)
                    f.assertUnrelated(reopened.repository)
                }
            }
        }

    @Test
    fun unqualified_chat_and_attachment_source_writers_still_refuse_a_delete_prompt() =
        runBlocking {
            for (kind in listOf(DocumentKind.CHAT, DocumentKind.ATTACHMENT)) {
                withFixture { f ->
                    val dependent =
                        if (kind == DocumentKind.ATTACHMENT) {
                            f.actual.repository.updateFrontmatter(f.otherAttachment.id, f.source())
                        } else {
                            f.actual.repository.createDocument(
                                NewDocument(kind, "Unsupported writer", "", frontmatter = f.source()),
                            )
                        }
                    f.services.coordinator.request(f.attachment.id)
                    f.until { f.services.coordinator.message.value != null && f.coordinatorIdle() }
                    assertNull(f.services.coordinator.prompt.value)
                    assertEquals(
                        "This file is used by another saved item and can't be deleted yet.",
                        f.services.coordinator.message.value!!
                            .text,
                    )
                    assertEquals(0, f.repository.deleteAttempts)
                    assertEquals(dependent, f.actual.repository.getDocument(dependent.id))
                    f.assertOriginalFilePresent(f.actual.repository)
                }
            }
        }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "file-detach-").toFile()
        val master = ByteArray(32).also(SecureRandom()::nextBytes)
        val key = TestKey(master)
        var actual: VaultSession? = null
        var fixture: Fixture? = null
        try {
            actual = open(directory, key)
            val created = Fixture(directory, master, key, actual)
            fixture = created
            created.seed()
            withTimeout(30_000) { block(created) }
        } finally {
            fixture?.editorScope?.cancel()
            fixture?.coordinatorScope?.cancel()
            fixture?.scheduler?.runCurrent()
            try {
                actual?.close()
            } finally {
                key.lock()
                master.fill(0)
                check(directory.deleteRecursively()) { "Synthetic file-detachment vault cleanup failed" }
            }
        }
    }

    private class Fixture(
        val directory: File,
        val master: ByteArray,
        val key: TestKey,
        val actual: VaultSession,
    ) {
        val scheduler = TestCoroutineScheduler()
        val editorScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))

        // Confirm enters reservation immediately, before queued title/autosave work is dispatched.
        val coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = ObservedRepository(actual.repository)
        val pruned = CopyOnWriteArrayList<DocId>()
        private val decoratedSession =
            VaultSession(
                repository,
                actual.indexStore,
                actual.personaService,
                actual.exportService,
                actual.importService,
                actual.exportStages,
                release = { actual.close() },
            )
        val services =
            createDocumentDeletion(decoratedSession, coordinatorScope, { pruned += it }, DocumentDeleteNotices())
        lateinit var attachment: Document
        lateinit var otherAttachment: Document
        lateinit var extracted: Document
        lateinit var output: Document
        lateinit var unrelated: Document
        lateinit var chat: Document
        private val draft = ChatDraft("Unrelated encrypted chat draft", 4, 13)

        suspend fun seed() {
            attachment = actual.repository.createAttachment("Disposable source", "text/plain") { it.write(BYTES) }
            otherAttachment = actual.repository.createAttachment("Kept source", "text/plain") { it.write(BYTES) }
            extracted =
                actual.repository.createDocument(
                    NewDocument(DocumentKind.NOTE, "Extracted", "Saved quote", frontmatter = source()),
                )
            output =
                actual.repository.createDocument(
                    NewDocument(
                        DocumentKind.AIOUT,
                        "Original title",
                        "Original body",
                        personaId = actual.personaService.default().id,
                        frontmatter =
                            buildJsonObject {
                                put("source", attachment.id)
                                put("custom", "original")
                                put("keep", "retained")
                            },
                    ),
                )
            repository.survivorId = output.id
            unrelated =
                actual.repository.createDocument(NewDocument(DocumentKind.NOTE, "Unrelated", "Unrelated original"))
            chat = actual.repository.createDocument(NewDocument(DocumentKind.CHAT, "Quoted chat", ""))
            actual.repository.appendMessage(
                chat.id,
                NewMessage(
                    Role.ASSISTANT,
                    "Keep the quote [1]",
                    citations =
                        CitationRecord(
                            retrieved =
                                listOf(
                                    Citation(
                                        1,
                                        extracted.id,
                                        extracted.contentHash!!,
                                        Locator(0, 11, 0),
                                        "Saved quote",
                                        CitationSourceKind.LEXICAL,
                                    ),
                                ),
                            cited = listOf(1),
                        ),
                ),
            )
            actual.repository.writeDraft(ChatDraftKey.Existing(chat.id), draft)
        }

        fun source(): JsonObject = buildJsonObject { put("source", attachment.id) }

        suspend fun editor(document: Document): NoteTabState =
            NoteTabState(document.id, repository, actual.indexStore, editorScope, noteDeletions = services.notes).also {
                until { !it.loading }
                assertNull(it.loadError)
            }

        fun dirty(
            state: NoteTabState,
            sourceId: DocId = attachment.id,
        ) {
            val frontmatter =
                JsonObject(
                    output.frontmatter + mapOf("custom" to JsonPrimitive("dirty"), "source" to JsonPrimitive(sourceId)),
                )
            val text = Frontmatter.render(frontmatter, DIRTY_BODY)
            state.editorState.onValueChange(TextFieldValue(text, TextRange(text.length - 5, text.length - 1)))
            state.onTitleChange(DIRTY_TITLE)
        }

        suspend fun preparePrompt() {
            services.coordinator.request(attachment.id)
            until {
                (services.coordinator.prompt.value != null || services.coordinator.message.value != null) &&
                    coordinatorIdle()
            }
            val target = checkNotNull(services.coordinator.prompt.value).file!!
            assertEquals(setOf(attachment.id, extracted.id), target.documentIds)
            assertEquals(listOf(output.id), target.sourceDependents.map { it.id })
        }

        suspend fun confirm(): String {
            services.coordinator.confirm()
            until { services.coordinator.message.value != null && coordinatorIdle() }
            return services.coordinator.message.value!!
                .text
        }

        fun coordinatorIdle(): Boolean = coordinatorScope.coroutineContext[Job]!!.children.none { it.isActive }

        suspend fun until(condition: () -> Boolean) {
            withTimeout(10_000) {
                while (!condition()) {
                    scheduler.runCurrent()
                    delay(5)
                }
                scheduler.runCurrent()
            }
        }

        fun assertPreflushWasDurable() {
            val observed = checkNotNull(repository.beforeDelete)
            assertEquals(DIRTY_BODY, observed.bodyMd)
            assertEquals(DIRTY_TITLE, observed.title)
            assertEquals(JsonPrimitive("dirty"), observed.frontmatter["custom"])
            assertEquals(JsonPrimitive(attachment.id), observed.frontmatter["source"])
            assertTrue(repository.sourcePresentBeforeDelete)
        }

        suspend fun assertDetached(vault: VaultRepository) {
            val saved = checkNotNull(vault.getDocument(output.id))
            assertEquals(DIRTY_BODY, saved.bodyMd)
            assertEquals(DIRTY_TITLE, saved.title)
            assertEquals(JsonPrimitive("dirty"), saved.frontmatter["custom"])
            assertEquals(JsonPrimitive("retained"), saved.frontmatter["keep"])
            assertEquals(output.personaId, saved.personaId)
            assertEquals(output.createdAt, saved.createdAt)
            assertEquals(DocumentKind.AIOUT, saved.kind)
            assertNull(saved.frontmatter["source"])
            assertNull(vault.getDocument(attachment.id))
            assertNull(vault.getDocument(extracted.id))
            assertFalse(File(directory, "attachments/${attachment.id}").exists())
            assertEquals(setOf(attachment.id, extracted.id), pruned.toSet())
            assertEquals(2, pruned.size)
        }

        suspend fun assertOriginalFilePresent(vault: VaultRepository) {
            assertEquals(attachment, vault.getDocument(attachment.id))
            assertEquals(extracted, vault.getDocument(extracted.id))
            assertArrayEquals(BYTES, vault.openAttachment(attachment.id).use { it.readBytes() })
        }

        suspend fun assertUnrelated(vault: VaultRepository) {
            assertEquals(unrelated, vault.getDocument(unrelated.id))
            assertEquals(otherAttachment, vault.getDocument(otherAttachment.id))
            assertArrayEquals(BYTES, vault.openAttachment(otherAttachment.id).use { it.readBytes() })
            assertEquals(draft, vault.readDraft(ChatDraftKey.Existing(chat.id)))
            val message = vault.listMessages(chat.id).single()
            assertEquals("Keep the quote [1]", message.contentMd)
            assertEquals(
                "Saved quote",
                message.citations!!
                    .retrieved
                    .single()
                    .excerpt,
            )
            assertEquals(
                extracted.id,
                message.citations!!
                    .retrieved
                    .single()
                    .documentId,
            )
            assertEquals(
                extracted.contentHash,
                message.citations!!
                    .retrieved
                    .single()
                    .revisionHash,
            )
            assertEquals(listOf(1), message.citations!!.cited)
        }

        suspend fun assertOldWriterBlocked(state: NoteTabState) {
            val value = state.editorState.value
            val title = state.title
            state.editorState.onValueChange(
                TextFieldValue(Frontmatter.render(output.frontmatter, "Forbidden late edit")),
            )
            state.onTitleChange("Forbidden late title")
            state.flush()
            scheduler.runCurrent()
            assertEquals(value, state.editorState.value)
            assertEquals(title, state.title)
        }

        suspend fun reopen(block: suspend (VaultSession) -> Unit) {
            editorScope.cancel()
            scheduler.runCurrent()
            actual.close()
            key.lock()
            assertNull(key.currentKey())
            val header = VaultPaths(directory).databaseFile.inputStream().use { it.readNBytes(16) }
            assertFalse(header.contentEquals("SQLite format 3\u0000".toByteArray()))
            val nextKey = TestKey(master)
            val reopened = open(directory, nextKey)
            try {
                block(reopened)
            } finally {
                reopened.close()
                nextKey.lock()
            }
        }
    }

    private class ObservedRepository(
        private val actual: VaultRepository,
    ) : VaultRepository by actual,
        FileLifecycle by (actual as FileLifecycle) {
        var survivorId: DocId = ""
        var afterPreflush: (suspend () -> Unit)? = null
        var afterCommit: (suspend () -> Unit)? = null
        var failNextBody = false
        var failReload = false
        var bodyFailures = 0
        var reloadFailures = 0
        var postCommitBoundaries = 0
        var deleteAttempts = 0
        var targetChanged = false
        var attemptedIds: Set<DocId> = emptySet()
        var beforeDelete: Document? = null
        var sourcePresentBeforeDelete = false
        var receipt: FileDeletionReceipt? = null
        var afterFailedTransaction: Document? = null

        override suspend fun getDocument(id: DocId): Document? {
            if (failReload && receipt?.committed == true && id == survivorId) {
                reloadFailures++
                throw IOException("Injected postcommit reload read failure")
            }
            return actual.getDocument(id)
        }

        override suspend fun replaceBody(
            id: DocId,
            bodyMd: String,
        ): Document {
            if (failNextBody && id == survivorId) {
                failNextBody = false
                bodyFailures++
                throw IOException("Injected precommit body write failure")
            }
            return actual.replaceBody(id, bodyMd)
        }

        override suspend fun deleteFile(target: FileDeletionTarget): FileDeletionReceipt {
            deleteAttempts++
            attemptedIds = target.documentIds
            beforeDelete = actual.getDocument(survivorId)
            sourcePresentBeforeDelete = actual.getDocument(target.attachment.id)?.kind == DocumentKind.ATTACHMENT
            try {
                return (actual as FileLifecycle).deleteFile(target).also { receipt = it }
            } catch (changed: FileDeletionTargetChangedException) {
                targetChanged = true
                throw changed
            }
        }

        override suspend fun <T> transaction(block: suspend () -> T): T {
            val result =
                try {
                    actual.transaction(block)
                } catch (error: Exception) {
                    if (bodyFailures > 0 && afterFailedTransaction == null) {
                        afterFailedTransaction = actual.getDocument(survivorId)
                    }
                    throw error
                }
            if (receipt?.committed == true) {
                postCommitBoundaries++
                val hook = afterCommit
                afterCommit = null
                hook?.invoke()
            } else if (deleteAttempts == 0) {
                val hook = afterPreflush
                afterPreflush = null
                hook?.invoke()
            }
            return result
        }
    }

    /** Synthetic key copies permit a fresh opener after an actual close/zero of the old key. */
    private class TestKey(
        master: ByteArray,
    ) : VaultKeyProvider {
        private val bytes = master.copyOf()
        private var closed = false

        override fun isInitialised(): Boolean = true

        override fun currentKey(): ByteArray? = bytes.takeUnless { closed }

        override fun lock() {
            closed = true
            bytes.fill(0)
        }

        override suspend fun setup(
            activity: FragmentActivity,
            prompt: BiometricPrompt.PromptInfo,
        ): SetupResult = error("No key setup")

        override suspend fun setup(
            activity: FragmentActivity,
            prompt: BiometricPrompt.PromptInfo,
            existingMaster: ByteArray,
        ): SetupResult = error("No key recovery")

        override suspend fun unlock(
            activity: FragmentActivity,
            prompt: BiometricPrompt.PromptInfo,
            factor: VaultKeyProvider.Factor,
        ): UnlockResult = error("No device authentication")

        override suspend fun rewrapAfterInvalidation(
            activity: FragmentActivity,
            prompt: BiometricPrompt.PromptInfo,
            survivingFactor: VaultKeyProvider.Factor,
        ): RewrapResult = error("No envelope replacement")
    }

    private companion object {
        const val DIRTY_BODY = "Dirty surviving body with selection"
        const val DIRTY_TITLE = "Dirty surviving title"
        const val REOPEN_MESSAGE = "File and extracted text deleted. Reopen saved items before editing them."
        val BYTES = byteArrayOf(41, 42, 43, 44)

        suspend fun open(
            directory: File,
            key: TestKey,
        ): VaultSession = DeviceVaultOpener(key, VaultPaths(directory), File(directory, "attachments")).open()
    }
}
