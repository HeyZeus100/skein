// skein-a0mm: a `DocumentsProvider` over a temp directory, so
// `FolderImportJobTest` drives `FolderImportJob` through the real SAF tree
// calls (`DocumentsContract` children queries, `openInputStream`) rather than
// a stand-in. Document ids are paths under [ROOT_ID], which names [root].

package app.skein.transfer

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File

class DirectoryDocumentsProvider : DocumentsProvider() {
    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: arrayOf(Root.COLUMN_ROOT_ID))

    override fun queryDocument(
        documentId: String,
        projection: Array<out String>?,
    ): Cursor = MatrixCursor(projection ?: DEFAULT_PROJECTION).also { add(it, documentId) }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_PROJECTION)
        file(parentDocumentId).listFiles()?.sortedBy { it.name }?.forEach { child ->
            add(cursor, "$parentDocumentId/${child.name}")
        }
        return cursor
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor = ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun isChildDocument(
        parentDocumentId: String,
        documentId: String,
    ): Boolean = documentId.startsWith("$parentDocumentId/")

    private fun file(documentId: String): File = File(root, documentId.removePrefix(ROOT_ID).removePrefix("/"))

    private fun add(
        cursor: MatrixCursor,
        documentId: String,
    ) {
        val file = file(documentId)
        cursor
            .newRow()
            .add(Document.COLUMN_DOCUMENT_ID, documentId)
            .add(Document.COLUMN_DISPLAY_NAME, file.name)
            .add(
                Document.COLUMN_MIME_TYPE,
                if (file.isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream",
            ).add(Document.COLUMN_SIZE, if (file.isDirectory) null else file.length())
    }

    companion object {
        const val AUTHORITY: String = "app.skein.test.directory"
        const val ROOT_ID: String = "root"

        /** The directory served; set by the test before any query. */
        lateinit var root: File

        private val DEFAULT_PROJECTION =
            arrayOf(
                Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE,
                Document.COLUMN_SIZE,
            )
    }
}
