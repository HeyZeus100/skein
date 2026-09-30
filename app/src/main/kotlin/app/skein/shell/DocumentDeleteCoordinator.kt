package app.skein.shell

import app.skein.core.model.DocId
import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.EdgeKind
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

    /** Called synchronously after COMMIT, before repository change signals, possibly on IO. */
    fun commit()

    suspend fun rollback()
}

internal data class DocumentDeletePrompt(
    val id: DocId,
    val title: String,
    val kind: DocumentKind,
    val consequence: String,
) {
    val heading: String
        get() {
            val label = title.replace(Regex("\\s+"), " ").trim()
            if (label.isEmpty()) return "Delete this ${if (kind == DocumentKind.CHAT) "chat" else "note"}?"
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
                    announce("File deletion isn't available yet.")
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

    fun dismissMessage(message: DocumentDeleteMessage) {
        _message.compareAndSet(message, null)
    }

    private fun announce(text: String) {
        _message.value = DocumentDeleteMessage(text, ++sequence)
    }

    private suspend fun buildPrompt(document: Document): DocumentDeletePrompt {
        val noun = if (document.kind == DocumentKind.CHAT) "conversation" else "note"
        val lines = mutableListOf("This permanently removes the $noun from Skein.")
        if (document.kind == DocumentKind.CHAT &&
            index
                .edgesFrom(
                    document.id,
                ).any { it.kind == EdgeKind.WIKILINK && repository.getDocument(it.dstId) != null }
        ) {
            lines += "Notes and files it used stay in Knowledge."
        }
        val linkingNotes =
            index.edgesTo(document.id, EdgeKind.WIKILINK).map { it.srcId }.distinct().count { id ->
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
        val quotingChats = repository.countChatsCiting(document.id)
        if (quotingChats > 0) {
            lines +=
                if (quotingChats == 1) {
                    "It was quoted in 1 chat. Those quotes stay."
                } else {
                    "It was quoted in $quotingChats chats. Those quotes stay."
                }
        }
        return DocumentDeletePrompt(document.id, document.title, document.kind, lines.joinToString("\n\n"))
    }
}
