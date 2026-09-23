// E2.I5 (skein-1nr): exception hierarchy for `FileAttachmentStore`. Every
// thrown instance is constructed from an [app.skein.core.vault.blob.]
// `DocId` only -- no plaintext bytes, ciphertext bytes, or key material ever
// appear in a message, per the coordinator's non-negotiables for this issue.

package app.skein.core.vault.blob

import app.skein.core.model.DocId
import java.io.IOException

/** Base type for every failure [FileAttachmentStore] surfaces to callers. */
public sealed class AttachmentException(
    message: String,
) : IOException(message) {
    /** No attachment is stored under [id]. */
    public class NotFound(
        id: DocId,
    ) : AttachmentException("attachment not found: $id")

    /**
     * An attachment already exists under [id]. `AttachmentStore.write`'s
     * contract leaves overwrite behaviour undefined; this store's choice is
     * to refuse it outright and leave the existing file untouched, matching
     * the write-once invariant `docs/design/ATTACHMENT_ENCRYPTION.md` §1
     * describes.
     *
     * Since `SKAT` v2 (skein-yn8d) every write draws a fresh random
     * `file_salt`, so two containers under one id could no longer share an
     * AES-GCM (key, nonce) pair even if both reached the disk. The write-once
     * rule is therefore no longer load-bearing for GCM safety -- but it is
     * still enforced, because an attachment id is defined to name exactly one
     * plaintext for its whole lifetime and silently replacing one is a data-loss
     * bug regardless of the crypto.
     */
    public class AlreadyExists(
        id: DocId,
    ) : AttachmentException("attachment already exists: $id")

    /**
     * The stored container for [id] is not a `SKAT` container this build can
     * read: either the magic is wrong or [version] is a format version this
     * reader does not support.
     *
     * `SKAT` v1 containers land here. There is no released Skein vault and
     * therefore no v1 data in the field, so v2 refuses v1 outright rather
     * than carrying a reader for a format whose whole point was that it was
     * insufficiently authenticated (see `SkatFormat`).
     */
    public class UnsupportedVersion(
        id: DocId,
        public val version: Int,
    ) : AttachmentException("unsupported attachment container version $version: $id")

    /**
     * The stored bytes for [id] failed authentication. Usually [cause] is the
     * underlying `javax.crypto.AEADBadTagException` (a flipped bit, or a
     * tampered chunk / frame header / file salt, all of which are covered by
     * a chunk tag). It is a plain `IOException` for a structural fault that is
     * detected without needing a tag -- an unparseable chunk frame, or
     * ciphertext still following an authenticated final chunk.
     */
    public class AttachmentCorruptException(
        id: DocId,
        cause: Throwable,
    ) : AttachmentException("attachment corrupt: $id") {
        init {
            initCause(cause)
        }
    }

    /**
     * The stored file ends before the container does: the reader reached EOF
     * without ever decrypting a chunk carrying the authenticated final flag,
     * or a chunk's frame promised more bytes than the file holds.
     *
     * Note this is decided from the authenticated final flag, never from the
     * header's `total_plaintext_length` -- that field is attacker-mutable and
     * trusting it was the silent-truncation hole skein-0nh8 closes.
     */
    public class AttachmentTruncatedException(
        id: DocId,
    ) : AttachmentException("attachment truncated: $id")
}
