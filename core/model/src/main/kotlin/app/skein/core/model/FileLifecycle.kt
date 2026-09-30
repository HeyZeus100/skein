package app.skein.core.model

import java.io.OutputStream

/** A File is its attachment and every NOTE whose source names that attachment. */
public data class FileDeletionTarget(
    val attachment: Document,
    val extractedNotes: List<Document>,
    val sourceDependents: List<Document> = emptyList(),
) {
    /** Deleted ids only. Never prune [sourceDependents] from navigation. */
    public val documentIds: Set<DocId>
        get() = extractedNotes.mapTo(linkedSetOf(attachment.id)) { it.id }

    /** Reserve all these editors before entering the repository transaction. */
    public val affectedDocumentIds: Set<DocId>
        get() = documentIds + sourceDependents.map { it.id }
}

/**
 * Receipt for a file delete, including when it joins an enclosing repository transaction.
 * [committed] changes synchronously at the outer COMMIT, before change notifications.
 * A cancelled return or failed file unlink cannot turn a committed delete into a rollback.
 * [attachmentCleanupPending] stays true until the post-commit unlink succeeds; the next
 * unlock sweep retries abandoned ciphertext. These properties contain no document text.
 */
public interface FileDeletionReceipt {
    public val documentIds: Set<DocId>

    /** Surviving rows whose source association was removed; refresh, do not prune. */
    public val detachedDocumentIds: Set<DocId>
    public val committed: Boolean
    public val attachmentCleanupPending: Boolean
}

public data class AttachmentSweepResult(
    val removedBlobs: Int = 0,
    val removedTemporaryFiles: Int = 0,
    val retainedActiveFiles: Int = 0,
    val failedFiles: Int = 0,
)

/**
 * File-specific lifecycle port. Independent note/chat deletion remains on [VaultRepository].
 * Callers reserve and drain all target editors before deletion, then prune [FileDeletionReceipt.documentIds]
 * only after COMMIT. Do not await an editor while holding a repository transaction.
 */
public interface FileLifecycle {
    /**
     * ZIP restore: write bytes outside SQLite, then insert their attachment and link the
     * supplied newly imported NOTE/AIOUT ids in one transaction. Every source must still
     * equal [expectedSource], or the entire metadata change rolls back. Earlier archive
     * entries retain the importer's existing partial-import semantics on failure.
     */
    public suspend fun createAttachmentWithExtractedNotes(
        title: String,
        mimeType: String,
        extractedNoteIds: Set<DocId>,
        expectedSource: DocId,
        write: suspend (OutputStream) -> Unit,
    ): Document

    /** Resolve an attachment or its extracted NOTE id; unrelated objects return null. */
    public suspend fun resolveFileDeletion(id: DocId): FileDeletionTarget?

    /** Counts each surviving quoting chat once across the attachment and all extracted notes. */
    public suspend fun countChatsCitingFile(target: FileDeletionTarget): Int

    /**
     * Revalidates the exact extracted NOTE membership under the writer transaction, then
     * removes all those notes and the attachment atomically. A changed target throws
     * [FileDeletionTargetChangedException] without deleting anything. Other documents,
     * including saved chat quotes and AIOUTs, survive. Only their source association is
     * detached, after exact dependent membership/kind revalidation in the same transaction.
     * Repeating a completed delete is safe.
     * Blob removal runs only after the outermost COMMIT.
     */
    public suspend fun deleteFile(target: FileDeletionTarget): FileDeletionReceipt

    /**
     * Run before publishing an unlocked session. Removes only unreferenced attachment
     * ciphertext and expired recognized temporary files, never metadata for missing bytes.
     * In-flight imports remain protected even if an older session is still unwinding.
     * Failures remain retryable and are counted, not represented as successful removal.
     */
    public suspend fun sweepOrphanAttachments(): AttachmentSweepResult
}

public class FileDeletionTargetChangedException : IllegalStateException("File changed before deletion")
