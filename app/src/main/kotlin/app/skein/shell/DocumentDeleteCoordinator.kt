package app.skein.shell

import app.skein.core.model.DocId
import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.EdgeKind
import app.skein.core.model.FileDeletionReceipt
import app.skein.core.model.FileDeletionTarget
import app.skein.core.model.FileDeletionTargetChangedException
import app.skein.core.model.FileLifecycle
import app.skein.core.model.IndexStore
import app.skein.core.model.VaultRepository
import app.skein.feature.editor.entries.canDeleteIndependentNote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/** A writer reservation, never a copy of the document or a delayed delete. */
internal interface PendingDocumentDelete {
    suspend fun awaitIdle() = Unit

    fun validateReadyForCommit() = Unit

    /** Called synchronously after COMMIT, before repository change signals, possibly on IO. */
    fun commit()

    suspend fun rollback()

    /** Surviving writers stay reserved until their current metadata has been reloaded. */
    suspend fun afterCommit(): Boolean = true
}

internal data class DocumentDeletePrompt(
    val id: DocId,
    val title: String,
    val kind: DocumentKind,
    val consequence: String,
    val file: FileDeletionTarget? = null,
) {
    val heading: String
        get() {
            val label = title.replace(Regex("\\s+"), " ").trim()
            val noun =
                when (kind) {
                    DocumentKind.CHAT -> "chat"
                    DocumentKind.ATTACHMENT -> "file"
                    else -> "note"
                }
            if (label.isEmpty()) return "Delete this $noun?"
            val shortened = if (label.length > 60) label.take(59) + "…" else label
            return "Delete \"$shortened\"?"
        }
}

internal data class DocumentDeleteMessage(
    val text: String,
    val sequence: Long,
)

/** Content-free notice survives closing the vault; never retains titles or document ids. */
internal class DocumentDeleteNotices {
    private val interrupted = AtomicBoolean(false)

    fun interrupted() {
        interrupted.set(true)
    }

    fun consumeInterrupted(): Boolean = interrupted.getAndSet(false)
}

/** One coordinator shared by both workspaces for exactly one unlocked vault. */
internal class DocumentDeleteCoordinator(
    private val repository: VaultRepository,
    private val index: IndexStore,
    private val scope: CoroutineScope,
    private val reserve: (Document) -> PendingDocumentDelete?,
    private val prune: (DocId) -> Unit,
    private val notices: DocumentDeleteNotices,
    private val canReloadSource: (Document) -> Boolean = { false },
    private val reserveSourceReload: (Document, DocId) -> PendingDocumentDelete? = { _, _ -> null },
) {
    private val _prompt = MutableStateFlow<DocumentDeletePrompt?>(null)
    val prompt = _prompt.asStateFlow()
    private val _message = MutableStateFlow<DocumentDeleteMessage?>(null)
    val message = _message.asStateFlow()
    private var busy = false
    private var sequence = 0L

    init {
        if (notices.consumeInterrupted()) announce("Deletion was interrupted. Check the item and try again.")
    }

    fun request(id: DocId) {
        if (busy || _prompt.value != null) return
        busy = true
        scope.launch {
            try {
                val document = repository.getDocument(id)
                if (document == null) {
                    prune(id)
                    announce("This item was already deleted.")
                } else if (document.kind == DocumentKind.CHAT || document.canDeleteIndependentNote()) {
                    _prompt.value = buildPrompt(document)
                } else {
                    val file = (repository as? FileLifecycle)?.resolveFileDeletion(id)
                    if (file != null && file.sourceDependents.any { !canReloadSource(it) }) {
                        // Only explicitly qualified surviving writers may resume after metadata changes.
                        announce("This file is used by another saved item and can't be deleted yet.")
                    } else if (file != null) {
                        _prompt.value = buildPrompt(file.attachment, file)
                    } else {
                        announce("This file can't be deleted here.")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                announce("Couldn't prepare deletion. Try again.")
            } finally {
                busy = false
            }
        }
    }

    fun cancel() {
        _prompt.value = null
    }

    fun confirm() {
        val target = _prompt.value ?: return
        if (busy) return
        _prompt.value = null
        busy = true
        scope.launch {
            val file = target.file
            if (file != null) {
                confirmFile(target, file)
                return@launch
            }
            val committed = AtomicBoolean(false)
            var pending: PendingDocumentDelete? = null
            try {
                val document = repository.getDocument(target.id)
                if (document == null) {
                    prune(target.id)
                    announce("This item was already deleted.")
                    return@launch
                }
                // Recheck eligibility after confirmation: a file/extracted note must never be partially deleted.
                if (document.kind != target.kind ||
                    (document.kind != DocumentKind.CHAT && !document.canDeleteIndependentNote())
                ) {
                    announce("This item changed. Open it and try again.")
                    return@launch
                }
                val reservation = reserve(document)
                if (reservation == null) {
                    announce("Stop the answer to delete this chat.")
                    return@launch
                }
                pending = reservation
                reservation.awaitIdle()
                repository.transaction {
                    // The writer lock also excludes a concurrent metadata change to a file-backed note.
                    val current = repository.getDocument(target.id)
                    check(
                        current == null ||
                            (
                                current.kind == target.kind &&
                                    (current.kind == DocumentKind.CHAT || current.canDeleteIndependentNote())
                            ),
                    )
                    repository.deleteDocument(target.id)
                    repository.afterTransactionCommit {
                        committed.set(true)
                        reservation.commit()
                    }
                }
            } catch (e: CancellationException) {
                if (!committed.get()) notices.interrupted()
                throw e
            } catch (_: Exception) {
                if (!committed.get()) announce("Couldn't delete \"${target.title.take(60)}\". Try again.")
            } finally {
                // A COMMIT followed by dispatcher-return cancellation is still a successful delete.
                withContext(NonCancellable) {
                    if (committed.get()) {
                        prune(target.id)
                        announce(if (target.kind == DocumentKind.CHAT) "Chat deleted." else "Note deleted.")
                    } else {
                        try {
                            withTimeoutOrNull(2_000) { pending?.rollback() }
                        } catch (_: Exception) {
                            // The original failure is already visible. Lock teardown may have closed
                            // the editor's repository; never retry against a subsequent vault session.
                        }
                    }
                }
                busy = false
            }
        }
    }

    private suspend fun confirmFile(
        target: DocumentDeletePrompt,
        file: FileDeletionTarget,
    ) {
        val reservations = mutableListOf<PendingDocumentDelete>()
        val committed = AtomicBoolean(false)
        var receipt: FileDeletionReceipt? = null
        try {
            check(file.sourceDependents.all(canReloadSource)) { "Surviving file writer cannot reload" }
            val lifecycle = repository as? FileLifecycle ?: error("File deletion unavailable")
            // Reserve every editor before awaiting any of them. No SQLite writer lock is held here.
            for (document in listOf(file.attachment) + file.extractedNotes) {
                val pending = reserve(document) ?: error("File writer unavailable")
                reservations += pending
            }
            for (document in file.sourceDependents) {
                val pending = reserveSourceReload(document, file.attachment.id) ?: error("Source writer unavailable")
                reservations += pending
            }
            reservations.forEach { it.awaitIdle() }
            repository.transaction {
                reservations.forEach { it.validateReadyForCommit() }
                receipt = lifecycle.deleteFile(file)
                reservations.forEach { it.validateReadyForCommit() }
                repository.afterTransactionCommit {
                    committed.set(true)
                    reservations.forEach { it.commit() }
                }
            }
        } catch (e: CancellationException) {
            if (!committed.get()) notices.interrupted()
            throw e
        } catch (_: FileDeletionTargetChangedException) {
            announce("This file changed. Open it and try again.")
        } catch (_: Exception) {
            if (!committed.get()) announce("Couldn't delete \"${target.title.take(60)}\". Try again.")
        } finally {
            withContext(NonCancellable) {
                if (committed.get()) {
                    file.documentIds.forEach(prune)
                    val reloaded =
                        withTimeoutOrNull(2_000) {
                            reservations
                                .map { pending ->
                                    try {
                                        pending.afterCommit()
                                    } catch (error: Exception) {
                                        if (error is CancellationException) throw error
                                        false
                                    }
                                }.all { it }
                        } == true
                    announce(
                        if (!reloaded) {
                            "File and extracted text deleted. Reopen saved items before editing them."
                        } else if (receipt?.attachmentCleanupPending != false) {
                            "File and extracted text deleted. File cleanup will retry when you unlock."
                        } else {
                            "File and extracted text deleted."
                        },
                    )
                } else {
                    for (pending in reservations.asReversed()) {
                        try {
                            withTimeoutOrNull(2_000) { pending.rollback() }
                        } catch (_: Exception) {
                            // A lock may have closed this session; never retry against a new one.
                        }
                    }
                }
            }
            busy = false
        }
    }

    fun dismissMessage(message: DocumentDeleteMessage) {
        _message.compareAndSet(message, null)
    }

    private fun announce(text: String) {
        _message.value = DocumentDeleteMessage(text, ++sequence)
    }

    private suspend fun buildPrompt(
        document: Document,
        file: FileDeletionTarget? = null,
    ): DocumentDeletePrompt {
        val noun =
            when {
                file != null -> "file and its text"
                document.kind == DocumentKind.CHAT -> "conversation"
                else -> "note"
            }
        val lines = mutableListOf("This permanently removes the $noun from Skein.")
        if (file?.sourceDependents?.isNotEmpty() == true) {
            lines += "Saved AI outputs stay. Their source link to this file is removed."
        }
        if (document.kind == DocumentKind.CHAT &&
            index
                .edgesFrom(
                    document.id,
                ).any { it.kind == EdgeKind.WIKILINK && repository.getDocument(it.dstId) != null }
        ) {
            lines += "Notes and files it used stay in Knowledge."
        }
        val removedIds = file?.documentIds ?: setOf(document.id)
        val linkingIds = mutableSetOf<DocId>()
        for (id in removedIds) linkingIds += index.edgesTo(id, EdgeKind.WIKILINK).map { it.srcId }
        val linkingNotes =
            linkingIds.filterNot { it in removedIds }.count { id ->
                repository.getDocument(id)?.kind in setOf(DocumentKind.NOTE, DocumentKind.AIOUT)
            }
        if (linkingNotes > 0) {
            lines +=
                if (linkingNotes == 1) {
                    "1 note links to it. That link will show as missing."
                } else {
                    "$linkingNotes notes link to it. Those links will show as missing."
                }
        }
        val quotingChats =
            if (file == null) {
                repository.countChatsCiting(document.id)
            } else {
                (repository as FileLifecycle).countChatsCitingFile(file)
            }
        if (quotingChats > 0) {
            lines +=
                if (quotingChats == 1) {
                    "It was quoted in 1 chat. Those quotes stay."
                } else {
                    "It was quoted in $quotingChats chats. Those quotes stay."
                }
        }
        return DocumentDeletePrompt(document.id, document.title, document.kind, lines.joinToString("\n\n"), file)
    }
}
