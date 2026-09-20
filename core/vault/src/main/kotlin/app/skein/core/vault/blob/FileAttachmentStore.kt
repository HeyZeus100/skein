// E2.I5 (skein-1nr): real `AttachmentStore` implementation. Redispatched
// after an earlier pass invented an API shape from the
// `docs/design/ATTACHMENT_ENCRYPTION.md` design doc's `WriteOnceAttachmentStore`
// sketch; the authoritative surface is the plan's (`docs/superpowers/plans/
// 2026-09-19-skein-v1-plan.md` §E2.I5) `FileAttachmentStore(dir, masterKey)`
// implementing the real `AttachmentStore` interface from `skein-2my`
// (this package's `AttachmentStore.kt`, which this file does not modify).
//
// Each attachment is one file at `<dir>/<id>`, a streaming `SKAT` container
// (see `SkatFormat`) encrypted chunk-by-chunk with a key unique to that
// attachment, derived from the vault master key via HKDF-SHA256 (RFC 5869,
// hand-rolled in `Hkdf.kt`) and never persisted anywhere.

package app.skein.core.vault.blob

import us.aherrera.skein.core.model.DocId
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Encrypted-at-rest [AttachmentStore] backed by plain files under [dir].
 *
 * [masterKey] is invoked once per file-key derivation (once per [write] and
 * once per [open]) and is expected to return a **fresh copy** of the vault
 * master key each call -- e.g. `vaultKeyProvider.currentKey()?.copyOf()`,
 * per [app.skein.core.vault.key.VaultKeyProvider.currentKey]'s own
 * "caller must copy and zero" contract. This store treats the array it gets
 * back as its own to wipe (`fill(0)`) the moment the per-file key has been
 * derived from it, so the underlying master key material touches this
 * store's memory only as long as one HKDF call takes and is never written
 * to disk.
 *
 * Per-file key: `HKDF-SHA256(salt = masterKey(), ikm = id.utf8Bytes, info =
 * "skein-attachment-v1", L = 32)`. Distinct ids deterministically derive
 * distinct keys; re-deriving for the same id (e.g. on retry) reproduces the
 * same key without anything having been persisted in between.
 */
public class FileAttachmentStore(
    private val dir: File,
    private val masterKey: () -> ByteArray,
) : AttachmentStore {
    init {
        dir.mkdirs()
    }

    override suspend fun write(
        id: DocId,
        write: suspend (OutputStream) -> Unit,
    ): Long {
        requireSafeId(id)
        dir.mkdirs()
        val finalFile = File(dir, id)
        val tempFile = File(dir, ".$id.${System.nanoTime()}.tmp")
        try {
            val written = writeToTempFile(id, tempFile, write)
            // Deterministic per-id key + per-chunk nonce sequence means an
            // overwrite would reuse (key, nonce) under AES-GCM for different
            // plaintext -- refuse it instead of REPLACE_EXISTING. Checked
            // right before the rename (not up front) to keep the TOCTOU
            // window as small as possible; a concurrent writer losing this
            // race gets ATOMIC_MOVE's own FileAlreadyExistsException instead,
            // which still can't land a silent overwrite.
            if (finalFile.exists()) throw AttachmentException.AlreadyExists(id)
            Files.move(tempFile.toPath(), finalFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
            return written
        } catch (t: Throwable) {
            tempFile.delete()
            throw t
        }
    }

    private suspend fun writeToTempFile(
        id: DocId,
        tempFile: File,
        write: suspend (OutputStream) -> Unit,
    ): Long {
        val fileKey = deriveFileKey(id)
        try {
            RandomAccessFile(tempFile, "rw").use { raf ->
                val out = SkeinAttachmentOutputStream(raf, fileKey)
                write(out)
                out.close()
                return out.totalPlaintextBytes
            }
        } finally {
            fileKey.fill(0)
        }
    }

    override suspend fun open(id: DocId): InputStream {
        requireSafeId(id)
        val file = File(dir, id)
        if (!file.isFile) throw AttachmentException.NotFound(id)
        val fileKey = deriveFileKey(id)
        try {
            return SkeinAttachmentInputStream(file, fileKey, id)
        } finally {
            fileKey.fill(0)
        }
    }

    override suspend fun delete(id: DocId) {
        requireSafeId(id)
        File(dir, id).delete()
    }

    override suspend fun size(id: DocId): Long? {
        requireSafeId(id)
        val file = File(dir, id)
        if (!file.isFile) return null
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(SkatFormat.HEADER_SIZE)
            var readSoFar = 0
            while (readSoFar < header.size) {
                val n = raf.read(header, readSoFar, header.size - readSoFar)
                if (n < 0) break
                readSoFar += n
            }
            if (readSoFar < header.size) throw AttachmentException.AttachmentTruncatedException(id)
            if (!SkatFormat.hasValidMagicAndVersion(header)) {
                throw AttachmentException.AttachmentCorruptException(id, java.io.IOException("not a SKAT container"))
            }
            return SkatFormat.readTotalPlaintextBytes(header)
        }
    }

    /** HKDF-SHA256 per-file key derivation; wipes its own copy of the master key before returning. */
    private fun deriveFileKey(id: DocId): ByteArray {
        val master = masterKey()
        try {
            return Hkdf.deriveKey(
                salt = master,
                ikm = id.toByteArray(Charsets.UTF_8),
                info = SkatFormat.INFO,
                length = 32,
            )
        } finally {
            master.fill(0)
        }
    }

    /** Rejects ids that could escape [dir] (path separators, `.`/`..`) -- ids are meant to be bare UUIDv7 hex strings. */
    private fun requireSafeId(id: DocId) {
        require(id.isNotEmpty() && id != "." && id != ".." && File(id).name == id) {
            "invalid attachment id"
        }
    }
}
