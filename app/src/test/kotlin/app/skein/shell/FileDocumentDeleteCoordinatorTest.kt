package app.skein.shell

import app.skein.core.model.AttachmentSweepResult
import app.skein.core.model.DocId
import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.FileDeletionReceipt
import app.skein.core.model.FileDeletionTarget
import app.skein.core.model.FileDeletionTargetChangedException
import app.skein.core.model.FileLifecycle
import app.skein.core.model.NewDocument
import app.skein.core.model.VaultRepository
import app.skein.testing.InMemoryIndexStore
import app.skein.testing.InMemoryVaultRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import java.io.OutputStream

/** Coordinator contracts; actual SQLCipher/attachment behavior is covered by FileLifecycleInstrumentedTest. */
@OptIn(ExperimentalCoroutinesApi::class)
class FileDocumentDeleteCoordinatorTest {
    private val index = InMemoryIndexStore()
    private val vault = InMemoryVaultRepository(index = index)
    private val events = mutableListOf<String>()
    private val pruned = mutableListOf<DocId>()
    private val notices = DocumentDeleteNotices()

    private suspend fun fixture(): FileDeletionTarget {
        val file = vault.createAttachment("Disposable report.pdf", "application/pdf") { it.write(byteArrayOf(1, 2)) }
        val notes =
            (1..2).map { n ->
                vault.createDocument(
                    NewDocument(
                        DocumentKind.NOTE,
                        "Extracted $n",
                        "Disposable text $n",
                        frontmatter = buildJsonObject { put("source", file.id) },
                    ),
                )
            }
        return FileDeletionTarget(file, notes)
    }

    private fun TestScope.coordinator(
        port: Port,
        drain: CompletableDeferred<Unit>? = null,
    ) = DocumentDeleteCoordinator(port, index, this, { document ->
        events += "reserve:${document.id}"
        object : PendingDocumentDelete {
            override suspend fun awaitIdle() {
                events += "drain:${document.id}"
                drain?.await()
            }

            override fun commit() {
                events += "commit:${document.id}"
            }

            override suspend fun rollback() {
                events += "rollback:${document.id}"
            }
        }
    }, { pruned += it }, notices)

    @Test fun `cancel from an extracted note preserves the entire file and reserves nothing`() =
        runTest {
            val target = fixture()
            val coordinator = coordinator(Port(target))
            coordinator.request(target.extractedNotes.first().id)
            runCurrent()
            assertThat(coordinator.prompt.value?.id).isEqualTo(target.attachment.id)
            assertThat(
                coordinator.prompt.value?.consequence,
            ).isEqualTo("This permanently removes the file and its text from Skein.")
            coordinator.cancel()
            coordinator.confirm()
            runCurrent()
            for (id in target.documentIds) assertThat(vault.getDocument(id)).isNotNull()
            assertThat(events).isEmpty()
        }

    @Test fun `all editors reserve before drain and all deleted routes prune only after commit`() =
        runTest {
            val target = fixture()
            val drain = CompletableDeferred<Unit>()
            val port = Port(target)
            val coordinator = coordinator(port, drain)
            coordinator.request(target.attachment.id)
            runCurrent()
            coordinator.confirm()
            runCurrent()
            assertThat(events.take(3)).containsExactlyElementsIn(target.documentIds.map { "reserve:$it" }).inOrder()
            assertThat(port.calls).isEqualTo(0)
            assertThat(pruned).isEmpty()
            drain.complete(Unit)
            runCurrent()
            for (id in target.documentIds) assertThat(vault.getDocument(id)).isNull()
            assertThat(pruned).containsExactlyElementsIn(target.documentIds)
            assertThat(events.filter { it.startsWith("commit:") }).hasSize(3)
            assertThat(coordinator.message.value?.text).isEqualTo("File and extracted text deleted.")
        }

    @Test fun `changed membership keeps every row and restores every reservation`() =
        runTest {
            val target = fixture()
            val port = Port(target).apply { changed = true }
            val coordinator = coordinator(port)
            coordinator.request(target.attachment.id)
            runCurrent()
            coordinator.confirm()
            runCurrent()
            for (id in target.documentIds) assertThat(vault.getDocument(id)).isNotNull()
            assertThat(pruned).isEmpty()
            assertThat(events.filter { it.startsWith("rollback:") }).hasSize(3)
            assertThat(coordinator.message.value?.text).isEqualTo("This file changed. Open it and try again.")
        }

    @Test fun `failed file transaction rolls back all rows and holder reservations`() =
        runTest {
            val target = fixture()
            val coordinator = coordinator(Port(target).apply { failAfterFirstDelete = true })
            coordinator.request(target.attachment.id)
            runCurrent()
            coordinator.confirm()
            runCurrent()
            for (id in target.documentIds) assertThat(vault.getDocument(id)).isNotNull()
            assertThat(pruned).isEmpty()
            assertThat(events.filter { it.startsWith("rollback:") }).hasSize(3)
        }

    @Test fun `cancellation returning from commit still prunes every deleted route without rollback`() =
        runTest {
            val target = fixture()
            val coordinator = coordinator(Port(target).apply { cancelAfterCommit = true })
            coordinator.request(target.attachment.id)
            runCurrent()
            coordinator.confirm()
            runCurrent()
            assertThat(pruned).containsExactlyElementsIn(target.documentIds)
            assertThat(events.filter { it.startsWith("rollback:") }).isEmpty()
            assertThat(notices.consumeInterrupted()).isFalse()
        }

    @Test fun `pending ciphertext cleanup is disclosed after metadata commit`() =
        runTest {
            val target = fixture()
            val coordinator = coordinator(Port(target).apply { cleanupPending = true })
            coordinator.request(target.attachment.id)
            runCurrent()
            coordinator.confirm()
            runCurrent()
            assertThat(coordinator.message.value?.text).isEqualTo(
                "File and extracted text deleted. File cleanup will retry when you unlock.",
            )
            assertThat(pruned).containsExactlyElementsIn(target.documentIds)
        }

    @Test fun `surviving dependent blocks UI until a safe editor-change reservation exists`() =
        runTest {
            val target = fixture()
            val other =
                vault.createDocument(
                    NewDocument(
                        DocumentKind.AIOUT,
                        "Saved output",
                        "Keep this",
                        frontmatter = buildJsonObject { put("source", target.attachment.id) },
                    ),
                )
            val port = Port(target.copy(sourceDependents = listOf(other)))
            val coordinator = coordinator(port)
            coordinator.request(target.attachment.id)
            runCurrent()
            coordinator.confirm()
            runCurrent()
            assertThat(coordinator.prompt.value).isNull()
            assertThat(port.calls).isEqualTo(0)
            assertThat(events).isEmpty()
            assertThat(vault.getDocument(other.id)).isEqualTo(other)
        }

    @Test fun `file confirmation uses distinct quoting-chat count supplied by file lifecycle`() =
        runTest {
            val target = fixture()
            val coordinator = coordinator(Port(target).apply { quotingChats = 1 })
            coordinator.request(target.attachment.id)
            runCurrent()
            assertThat(coordinator.prompt.value?.consequence).contains("It was quoted in 1 chat. Those quotes stay.")
        }

    private inner class Port(
        private val target: FileDeletionTarget,
    ) : VaultRepository by vault,
        FileLifecycle {
        var calls = 0
        var changed = false
        var failAfterFirstDelete = false
        var cancelAfterCommit = false
        var cleanupPending = false
        var quotingChats = 0

        override suspend fun resolveFileDeletion(id: DocId) = target.takeIf { id in it.documentIds }

        override suspend fun countChatsCitingFile(target: FileDeletionTarget) = quotingChats

        override suspend fun deleteFile(target: FileDeletionTarget): FileDeletionReceipt {
            calls++
            if (changed) throw FileDeletionTargetChangedException()
            val receipt =
                object : FileDeletionReceipt {
                    override val documentIds = target.documentIds
                    override val detachedDocumentIds = emptySet<DocId>()
                    override var committed = false
                    override val attachmentCleanupPending get() = cleanupPending
                }
            for (id in target.documentIds) {
                vault.deleteDocument(id)
                if (failAfterFirstDelete) error("synthetic transaction failure")
            }
            vault.afterTransactionCommit { receipt.committed = true }
            return receipt
        }

        override suspend fun <T> transaction(block: suspend () -> T): T {
            val result = vault.transaction(block)
            if (cancelAfterCommit) throw CancellationException("synthetic dispatcher return")
            return result
        }

        override suspend fun sweepOrphanAttachments() = AttachmentSweepResult()

        override suspend fun createAttachmentWithExtractedNotes(
            title: String,
            mimeType: String,
            extractedNoteIds: Set<DocId>,
            expectedSource: DocId,
            write: suspend (OutputStream) -> Unit,
        ): Document = error("not used by deletion UI")
    }
}
