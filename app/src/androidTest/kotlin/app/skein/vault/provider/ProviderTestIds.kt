// skein-vn6m: `VaultDocumentsProviderTest` moved here from `core/vault`'s
// androidTest source set (see that file's header) so it exercises the
// REAL `<provider>` declaration in `app/src/main/AndroidManifest.xml`
// instead of failing with `SecurityException: Failed to find provider
// app.skein.documents` in a test APK that never declares it.
//
// The test asserts against the exact same document-id / mime-type
// grammar `core.vault.provider.ProviderIds` and `ProviderCursors` define,
// but both of those are `internal` to the `:core:vault` Gradle module —
// invisible from `:app` regardless of the `implementation(project(":core:vault"))`
// dependency edge, because Kotlin's `internal` is scoped per compilation
// module, not per source set. They are also full of production-only
// machinery (`Frontmatter`/`SafeFileName`/`AttachmentExtensions`-backed
// row building) that has no business living in a test-support module, so
// neither a `:core:vault` test-fixtures source set nor `:testing` (checked
// first, per skein-vn6m's dispatch note) is the right home: moving the
// real objects would force `core/vault`'s production code to import from
// a test module, or `:testing` to depend on `core/vault`'s internal export
// package. Both are out of scope for this bead.
//
// This is therefore the sanctioned last resort: the handful of constants
// and pure id-building functions this ONE test actually reads, duplicated
// verbatim from `core/vault/src/main/kotlin/app/skein/core/vault/provider/`
// (`ProviderIds.kt` / `ProviderCursors.kt`). If the real grammar ever
// changes, this copy goes stale on purpose — the test starts failing
// against the real provider's output rather than silently agreeing with
// itself, which is the correct failure mode for a duplicated fixture.

package app.skein.vault.provider

import app.skein.core.model.DocId

/** Minimal duplicate of `app.skein.core.vault.provider.ProviderIds` — see this file's header. */
internal object ProviderIds {
    /** `DocumentsContract.Root.COLUMN_ROOT_ID` of the single root. */
    const val ROOT_ID: String = "skein-vault"

    /** Document id of the root directory (`DocumentsContract.Root.COLUMN_DOCUMENT_ID`). */
    const val ROOT_DOCUMENT_ID: String = "root"
    const val NOTES_DOCUMENT_ID: String = "notes"
    const val ATTACHMENTS_DOCUMENT_ID: String = "attachments"

    private const val NOTE_PREFIX: String = "note:"
    private const val ATTACHMENT_PREFIX: String = "att:"

    fun note(docId: DocId): String = NOTE_PREFIX + docId

    fun attachment(docId: DocId): String = ATTACHMENT_PREFIX + docId
}

/** Minimal duplicate of `app.skein.core.vault.provider.ProviderCursors` — see this file's header. */
internal object ProviderCursors {
    const val MARKDOWN_MIME_TYPE: String = "text/markdown"
}
