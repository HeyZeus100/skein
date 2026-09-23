// skein-cyq: the `ContentResolver`-backed source for `ImportSource.Picked`.
// Follows the one existing SAF-read precedent in this codebase
// (`feature/shell/.../VaultSetupScreen.kt`'s `readRecoveryFile`): open via
// `ContentResolver.openInputStream`, treat `IOException` and
// `SecurityException` identically (a revoked or expired grant must refuse
// like a cancelled pick, never crash `ModelManager`). Unlike that
// precedent, a model is multi-gigabyte, so this reads a `Stream`, never
// buffers the whole file.

package app.skein.core.inference.models

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.Closeable
import java.io.IOException
import java.io.InputStream

/**
 * An open, once-readable handle on a picked file: its size/name hints (both
 * untrusted display data, never used for anything load-bearing) and a
 * [stream] positioned at byte 0.
 */
public class PickedFileHandle(
    public val displayName: String?,
    public val sizeBytes: Long?,
    public val stream: InputStream,
) : Closeable {
    override fun close() {
        stream.close()
    }
}

/**
 * Resolves a picked [Uri] into a readable stream plus display hints.
 * `ModelManager` calls this at most twice per [ImportSource.Picked] import
 * — once to hash the content (no disk write), once for
 * [ImmutableModelStore.import]'s real, hashed copy — both within the life
 * of the same grant.
 */
public fun interface PickedFileReader {
    @Throws(IOException::class, SecurityException::class)
    public fun open(uri: Uri): PickedFileHandle
}

/** The production [PickedFileReader], over the app's real [ContentResolver]. */
public class ContentResolverPickedFileReader(
    private val contentResolver: ContentResolver,
) : PickedFileReader {
    override fun open(uri: Uri): PickedFileHandle {
        val metadata = queryMetadata(uri)
        val stream =
            contentResolver.openInputStream(uri)
                ?: throw IOException("ContentResolver.openInputStream returned null")
        return PickedFileHandle(displayName = metadata?.first, sizeBytes = metadata?.second, stream = stream)
    }

    private fun queryMetadata(uri: Uri): Pair<String?, Long?>? {
        val cursor: Cursor =
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            ) ?: return null
        return cursor.use {
            if (!it.moveToFirst()) return null
            val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
            val name = if (nameIndex >= 0 && !it.isNull(nameIndex)) it.getString(nameIndex) else null
            val size = if (sizeIndex >= 0 && !it.isNull(sizeIndex)) it.getLong(sizeIndex) else null
            name to size
        }
    }
}
