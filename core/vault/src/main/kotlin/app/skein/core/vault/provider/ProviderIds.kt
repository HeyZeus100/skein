// `E2.I6` (bd `skein-75x`): the document-id grammar `VaultDocumentsProvider`
// speaks to the Storage Access Framework. SAF document ids are opaque
// strings the framework echoes back on every call; ours never carry a
// filesystem path (bd acceptance criterion: "document ids are
// `note:<uuid>` / `att:<uuid>` and never leak file paths"). The vault is a
// SQLCipher database and attachments are keyed by UUID inside the blob
// store, so there is no path to leak in the first place — this object
// makes sure none can be smuggled in later either: a suffix containing a
// path separator, whitespace, or a control character is refused outright.
//
// Tree:
//   root (`ROOT_DOCUMENT_ID`)
//   ├── notes        (`NOTES_DOCUMENT_ID`)       → `note:<DocId>` children
//   └── attachments  (`ATTACHMENTS_DOCUMENT_ID`) → `att:<DocId>` children

package app.skein.core.vault.provider

import us.aherrera.skein.core.model.DocId

internal object ProviderIds {
    /** `DocumentsContract.Root.COLUMN_ROOT_ID` of the single root. */
    const val ROOT_ID: String = "skein-vault"

    /** Document id of the root directory (`DocumentsContract.Root.COLUMN_DOCUMENT_ID`). */
    const val ROOT_DOCUMENT_ID: String = "root"
    const val NOTES_DOCUMENT_ID: String = "notes"
    const val ATTACHMENTS_DOCUMENT_ID: String = "attachments"

    const val NOTE_PREFIX: String = "note:"
    const val ATTACHMENT_PREFIX: String = "att:"

    /** A successfully parsed SAF document id. */
    sealed class Parsed {
        object Root : Parsed()

        object Notes : Parsed()

        object Attachments : Parsed()

        data class Note(
            val docId: DocId,
        ) : Parsed()

        data class Attachment(
            val docId: DocId,
        ) : Parsed()
    }

    fun note(docId: DocId): String = NOTE_PREFIX + docId

    fun attachment(docId: DocId): String = ATTACHMENT_PREFIX + docId

    /** `null` for anything outside the grammar — callers translate that into `FileNotFoundException`. */
    fun parse(documentId: String): Parsed? =
        when {
            documentId == ROOT_DOCUMENT_ID -> Parsed.Root
            documentId == NOTES_DOCUMENT_ID -> Parsed.Notes
            documentId == ATTACHMENTS_DOCUMENT_ID -> Parsed.Attachments
            documentId.startsWith(NOTE_PREFIX) ->
                documentId.removePrefix(NOTE_PREFIX).takeIf(::isPlausibleDocId)?.let(Parsed::Note)
            documentId.startsWith(ATTACHMENT_PREFIX) ->
                documentId.removePrefix(ATTACHMENT_PREFIX).takeIf(::isPlausibleDocId)?.let(Parsed::Attachment)
            else -> null
        }

    /**
     * `DocumentsProvider.isChildDocument` semantics: is [documentId] a
     * descendant of [parentDocumentId]? A document is never its own child
     * (the framework short-circuits equality before asking), directories
     * own only their own kind of child, and documents own nothing.
     */
    fun isChild(
        parentDocumentId: String,
        documentId: String,
    ): Boolean {
        val parent = parse(parentDocumentId) ?: return false
        val child = parse(documentId) ?: return false
        return when (parent) {
            Parsed.Root -> child !is Parsed.Root
            Parsed.Notes -> child is Parsed.Note
            Parsed.Attachments -> child is Parsed.Attachment
            is Parsed.Note, is Parsed.Attachment -> false
        }
    }

    // Every real `DocId` is a lowercase UUID (`Uuid7`, `E2.I3`; the
    // `:testing` fake uses `UUID.randomUUID()`), but the repository contract
    // does not forbid other opaque strings, so only characters that could
    // ever read as a path are rejected rather than pinning a UUID regex.
    private fun isPlausibleDocId(s: String): Boolean =
        s.isNotEmpty() && s.none { it == '/' || it == '\\' || it.isWhitespace() || it.isISOControl() }
}
