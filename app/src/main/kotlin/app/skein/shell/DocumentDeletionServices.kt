package app.skein.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import app.skein.core.model.DocumentKind
import app.skein.core.model.FileDeletionTargetChangedException
import app.skein.feature.editor.notetab.NoteDeletionRegistry
import app.skein.feature.shell.host.SkeinShellState
import app.skein.vault.VaultSession

internal class DocumentDeletionServices(
    val coordinator: DocumentDeleteCoordinator,
    val notes: NoteDeletionRegistry,
)

@Composable
internal fun rememberDocumentDeletion(
    session: VaultSession,
    shells: List<SkeinShellState>,
    notices: DocumentDeleteNotices,
): DocumentDeletionServices {
    val scope = rememberCoroutineScope()
    return remember(session, shells, notices) {
        val notes = NoteDeletionRegistry()
        val turns = session.models?.turns
        val drafts = session.models?.drafts
        val pipeline = session.models?.sendPipeline
        val coordinator =
            DocumentDeleteCoordinator(
                repository = session.repository,
                index = session.indexStore,
                scope = scope,
                notices = notices,
                prune = { id -> shells.forEach { it.pruneDocument(id) } },
                canReloadSource = { it.kind == DocumentKind.AIOUT },
                reserveSourceReload = { document, sourceId ->
                    if (document.kind != DocumentKind.AIOUT) {
                        null
                    } else {
                        val pending = notes.beginSourceDetachment(document.id, sourceId)
                        object : PendingDocumentDelete {
                            override suspend fun awaitIdle() = pending.awaitIdle()

                            override fun validateReadyForCommit() {
                                if (!pending.isReadyForCommit()) throw FileDeletionTargetChangedException()
                            }

                            override fun commit() = pending.commit()

                            override suspend fun afterCommit(): Boolean = pending.reload()

                            override suspend fun rollback() = pending.rollback()
                        }
                    }
                },
                reserve = { document ->
                    if (document.kind == DocumentKind.CHAT) {
                        if (turns != null && !turns.tryBeginDelete(document.id)) {
                            null
                        } else {
                            object : PendingDocumentDelete {
                                override fun commit() {
                                    turns?.finishDelete(document.id)
                                    drafts?.discardCommitted(document.id)
                                    if (turns == null) pipeline?.invalidateCommittedDocument(document.id)
                                }

                                override suspend fun rollback() {
                                    turns?.cancelDelete(document.id)
                                }
                            }
                        }
                    } else {
                        val pending = notes.beginDelete(document.id)
                        object : PendingDocumentDelete {
                            override suspend fun awaitIdle() = pending.awaitIdle()

                            override fun commit() {
                                pending.commit()
                                if (turns != null) {
                                    turns.invalidateSourceCommitted(document.id)
                                } else {
                                    pipeline?.invalidateCommittedDocument(document.id)
                                }
                            }

                            override suspend fun rollback() = pending.rollback()
                        }
                    }
                },
            )
        DocumentDeletionServices(coordinator, notes)
    }
}
