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
// *write*, derived from the vault master key and a fresh random per-file salt
// via HKDF-SHA256 (RFC 5869, hand-rolled in `Hkdf.kt`) and never persisted.
//
// Hardened for skein-yn8d (random per-write file salt) and skein-0nh8
// (authenticated final-chunk marker) -- together these are `SKAT` v2.

package app.skein.core.vault.blob

import app.skein.core.model.DocId
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
 * **Per-file key (`SKAT` v2, skein-yn8d):**
 * `HKDF-SHA256(salt = masterKey(), ikm = id.utf8Bytes, info = "skein-attachment-v2" || fileSalt, L = 32)`,
 * where `fileSalt` is 16 fresh `SecureRandom` bytes drawn for each [write] and
 * stored in the container header. The key is therefore unique per *write*, not
 * merely per id: even if two containers for one id reach the disk out of band
 * (backup restore, file manager, an adversary with filesystem write), they
 * cannot share an AES-GCM `(key, nonce)` pair. The salt is inside every
 * chunk's AAD, so swapping it invalidates every tag in the file.
 *
 * v1's deterministic "same id re-derives the same key with nothing persisted"
 * property is deliberately given up: re-deriving an attachment's key from the
 * id alone was itself the exposure this closes, and retries already fail at
 * the store layer with [AttachmentException.AlreadyExists].
 *
 * **Compatibility:** this reader refuses `SKAT` v1 containers with
 * [AttachmentException.UnsupportedVersion]. No released Skein vault exists, so
 * there is no v1 data in the field to migrate; carrying a reader for a format
 * whose termination and key derivation were the things being fixed would keep
 * both weaknesses reachable.
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
    ): Long = writeContainer(id, allowOverwrite = false, write = write)

    /**
     * Test seam (skein-yn8d): the same write path with the write-once check
     * lifted, so tests can land two containers on one id and assert that they
     * got distinct salts, keys and ciphertext. Production callers go through
     * [write], which always refuses an existing id.
     */
    internal suspend fun writeContainer(
        id: DocId,
        allowOverwrite: Boolean,
        write: suspend (OutputStream) -> Unit,
    ): Long {
        requireSafeId(id)
        dir.mkdirs()
        val finalFile = File(dir, id)
        val tempFile = File(dir, ".$id.${System.nanoTime()}.tmp")
        try {
            val written = writeToTempFile(id, tempFile, write)
            // An attachment id names exactly one plaintext for its lifetime;
            // replacing one silently is data loss. (Before v2 this check was
            // also what kept AES-GCM safe, since the key and nonce sequence
            // were deterministic in the id alone -- the random per-write salt
            // now carries that part on its own.) Checked right before the
            // rename, not up front, to keep the TOCTOU window as small as
            // possible; a concurrent writer losing this race gets
            // ATOMIC_MOVE's own FileAlreadyExistsException instead, which
            // still cannot land a silent overwrite.
            if (!allowOverwrite && finalFile.exists()) throw AttachmentException.AlreadyExists(id)
            val options =
                if (allowOverwrite) {
                    arrayOf(StandardCopyOption.REPLACE_EXISTING)
                } else {
                    arrayOf(StandardCopyOption.ATOMIC_MOVE)
                }
            Files.move(tempFile.toPath(), finalFile.toPath(), *options)
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
        val fileSalt = SkatFormat.newFileSalt()
        val fileKey = deriveFileKey(id, fileSalt)
        try {
            RandomAccessFile(tempFile, "rw").use { raf ->
                val out = SkeinAttachmentOutputStream(raf, fileKey, fileSalt)
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
        val header = readHeader(id, file)
        val fileSalt = SkatFormat.readFileSalt(header)
        val fileKey = deriveFileKey(id, fileSalt)
        try {
            return SkeinAttachmentInputStream(file, fileKey, header, id)
        } finally {
            fileKey.fill(0)
            fileSalt.fill(0)
        }
    }

    override suspend fun delete(id: DocId) {
        requireSafeId(id)
        File(dir, id).delete()
    }

    /**
     * The plaintext length recorded in the container header, without
     * decrypting anything.
     *
     * **Caveat (skein-0nh8):** this field is not covered by any chunk tag, so
     * a tampered file can report any length it likes here. It is kept because
     * it makes [size] an O(1) header read, and it is safe *as a hint* --
     * nothing in the reader uses it, so a forged value cannot truncate,
     * extend or re-frame the plaintext that [open] returns. Callers that need
     * a length they can rely on must count the bytes [open] actually yields.
     */
    override suspend fun size(id: DocId): Long? {
        requireSafeId(id)
        val file = File(dir, id)
        if (!file.isFile) return null
        return SkatFormat.readTotalPlaintextBytes(readHeader(id, file))
    }

    /** Reads and validates the fixed-size container header, or throws the matching typed failure. */
    private fun readHeader(
        id: DocId,
        file: File,
    ): ByteArray {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(SkatFormat.HEADER_SIZE)
            var readSoFar = 0
            while (readSoFar < header.size) {
                val n = raf.read(header, readSoFar, header.size - readSoFar)
                if (n < 0) break
                readSoFar += n
            }
            if (readSoFar < header.size) throw AttachmentException.AttachmentTruncatedException(id)
            if (!SkatFormat.hasValidMagic(header)) {
                throw AttachmentException.AttachmentCorruptException(id, IOException("not a SKAT container"))
            }
            val version = SkatFormat.readVersion(header)
            if (version != SkatFormat.VERSION) {
                throw AttachmentException.UnsupportedVersion(id, version.toInt() and 0xFF)
            }
            return header
        }
    }

    /** HKDF-SHA256 per-file key derivation; wipes its own copy of the master key before returning. */
    private fun deriveFileKey(
        id: DocId,
        fileSalt: ByteArray,
    ): ByteArray {
        val master = masterKey()
        val info = SkatFormat.infoFor(fileSalt)
        try {
            return Hkdf.deriveKey(
                salt = master,
                ikm = id.toByteArray(Charsets.UTF_8),
                info = info,
                length = 32,
            )
        } finally {
            master.fill(0)
            info.fill(0)
        }
    }

    /** Rejects ids that could escape [dir] (path separators, `.`/`..`) -- ids are meant to be bare UUIDv7 hex strings. */
    private fun requireSafeId(id: DocId) {
        require(id.isNotEmpty() && id != "." && id != ".." && File(id).name == id) {
            "invalid attachment id"
        }
    }
}
