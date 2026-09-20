// A minimal blob-store contract used by `InMemoryVaultRepository` (`E0.I11`).
//
// The real `AttachmentStore` interface lives in `:core:vault`
// (`app.skein.core.vault.blob.AttachmentStore`, filed as `E2.I5`), coupled
// to the SQLCipher DB and the three-layer key hierarchy from
// `docs/design/ATTACHMENT_ENCRYPTION.md`. `:testing` is a pure Kotlin/JVM
// module and cannot depend on `:core:vault` (that would drag the Android
// SDK onto every JVM consumer). We therefore ship a slim, model-only shape
// here that the `InMemoryVaultRepository` fake and its consumers can wire
// up on the JVM.
//
// When `E2.I5` lands, the JVM fake will migrate to the vault-side interface
// via a thin adapter — the shape below is intentionally the same
// (write/open/delete over a `DocId`, streaming I/O, no key material) so the
// migration is mechanical.

package us.aherrera.skein.testing

import us.aherrera.skein.core.model.DocId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * A minimal attachment blob store — a stand-in for
 * `app.skein.core.vault.blob.AttachmentStore` (`E2.I5`) usable from pure
 * Kotlin/JVM tests. Implementations decide how to persist the bytes;
 * `InMemoryAttachmentStore` below keeps them in a map.
 */
public interface AttachmentStore {
    /** Writes bytes for [id], returning the total bytes written. Overwriting an existing id is undefined. */
    public suspend fun write(
        id: DocId,
        write: suspend (OutputStream) -> Unit,
    ): Long

    public suspend fun open(id: DocId): InputStream

    public suspend fun delete(id: DocId)

    /** Byte count previously written for [id], or `null` when the id is unknown. */
    public suspend fun size(id: DocId): Long?
}

/**
 * JVM in-memory `AttachmentStore` backed by a `ConcurrentHashMap<DocId,
 * ByteArray>`. Suitable for `InMemoryVaultRepository` contract tests; not
 * suitable for anything else.
 */
public class InMemoryAttachmentStore : AttachmentStore {
    private val blobs: MutableMap<DocId, ByteArray> = ConcurrentHashMap()

    override suspend fun write(
        id: DocId,
        write: suspend (OutputStream) -> Unit,
    ): Long {
        val sink = ByteArrayOutputStream()
        write(sink)
        val bytes = sink.toByteArray()
        blobs[id] = bytes
        return bytes.size.toLong()
    }

    override suspend fun open(id: DocId): InputStream {
        val bytes = blobs[id] ?: throw NoSuchElementException("no attachment blob for id=$id")
        return ByteArrayInputStream(bytes)
    }

    override suspend fun delete(id: DocId) {
        blobs.remove(id)
    }

    override suspend fun size(id: DocId): Long? = blobs[id]?.size?.toLong()
}
