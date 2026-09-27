// skein-xtov.24.8 (AL-09a): the `NewNoteKey` draft (ADAPTIVE_LAYOUT_SPEC.md
// §8.2; OBJECT_LIFECYCLE_SPEC.md §6.2): the note editor runs unchanged over
// this view of the vault, in which the draft id reads as an empty note until
// the first non-blank save creates the row under that id.
package app.skein.feature.editor.entries

import app.skein.core.model.DocId
import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.NewDocument
import app.skein.core.model.VaultRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/**
 * Everything but [draftId]'s reads and writes goes straight to [vault]. The key
 * stays `NewNoteKey` after the row exists, so the editor is not re-created
 * mid-typing; on restore it simply opens the row (§8.2: "never stale").
 * ponytail: nothing is named from the first line or deleted when left empty
 * (KNOWLEDGE_UX_SPEC.md §6.3); that and `promoteDraft` come with AL-10's DraftStore.
 */
internal class DraftNoteRepository(
    private val vault: VaultRepository,
    private val draftId: DocId,
) : VaultRepository by vault {
    private val creating = Mutex()

    override suspend fun getDocument(id: DocId): Document? =
        vault.getDocument(id) ?: if (id == draftId) empty() else null

    // The editor saves a title through renameDocument and a body through
    // replaceBody (skein-cash LC-05); either one's first non-blank write creates the row.
    override suspend fun renameDocument(
        id: DocId,
        title: String,
        ifTitleIs: String?,
    ): Document? {
        if (id != draftId) return vault.renameDocument(id, title, ifTitleIs)
        return creating.withLock {
            when {
                vault.getDocument(id) != null -> vault.renameDocument(id, title, ifTitleIs)
                !ifTitleIs.isNullOrEmpty() -> null
                title.isBlank() -> empty()
                else -> vault.createDocument(NewDocument(DocumentKind.NOTE, title, "", id = id))
            }
        }
    }

    override suspend fun replaceBody(
        id: DocId,
        bodyMd: String,
    ): Document {
        if (id != draftId) return vault.replaceBody(id, bodyMd)
        return creating.withLock {
            when {
                vault.getDocument(id) != null -> vault.replaceBody(id, bodyMd)
                bodyMd.isBlank() -> empty()
                else -> vault.createDocument(NewDocument(DocumentKind.NOTE, "", bodyMd, id = id))
            }
        }
    }

    override suspend fun updateFrontmatter(
        id: DocId,
        frontmatter: JsonObject,
    ): Document {
        if (id == draftId) creating.withLock { if (vault.getDocument(id) == null) return empty() }
        return vault.updateFrontmatter(id, frontmatter)
    }

    private fun empty() =
        Document(
            id = draftId,
            kind = DocumentKind.NOTE,
            title = "",
            bodyMd = "",
            createdAt = 0L,
            updatedAt = 0L,
            personaId = null,
            frontmatter = JsonObject(emptyMap()),
            contentHash = null,
        )
}
