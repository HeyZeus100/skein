// E2.I5 (skein-1nr): exception hierarchy for `FileAttachmentStore`. Every
// thrown instance is constructed from an [app.skein.core.vault.blob.]
// `DocId` only -- no plaintext bytes, ciphertext bytes, or key material ever
// appear in a message, per the coordinator's non-negotiables for this issue.

package app.skein.core.vault.blob

import us.aherrera.skein.core.model.DocId
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
     * An attachment already exists under [id]. `FileAttachmentStore` derives
     * a deterministic per-file key (and a deterministic per-chunk nonce
     * sequence) from [id] alone, so silently overwriting an existing id
     * would reuse the same AES-GCM (key, nonce) pair for two different
     * plaintexts -- catastrophic for both confidentiality and authenticity
     * under GCM. `AttachmentStore.write`'s contract leaves overwrite
     * behaviour undefined; this store's choice is to refuse it outright and
     * leave the existing file untouched, matching the write-once invariant
     * `docs/design/ATTACHMENT_ENCRYPTION.md` §1 describes.
     */
    public class AlreadyExists(
        id: DocId,
    ) : AttachmentException("attachment already exists: $id")

    /**
     * The stored bytes for [id] failed AEAD tag verification -- either a
     * single flipped bit or a deliberate tamper. [cause] is always the
     * underlying `javax.crypto.AEADBadTagException`.
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
     * The stored file is shorter than the plaintext length recorded in its
     * `SKAT` header -- one or more trailing chunks (or bytes of the final
     * chunk) are missing.
     */
    public class AttachmentTruncatedException(
        id: DocId,
    ) : AttachmentException("attachment truncated: $id")
}
