// Forward-declaration for `E2.I5` (skein-1nr, "Encrypted attachment blob
// store"). `VaultRepositoryImpl` (`E2.I4`) needs an injectable blob store
// for `createAttachment`/`openAttachment` today; the real, SQLCipher-key-
// hierarchy-backed implementation (`docs/design/ATTACHMENT_ENCRYPTION.md`
// §2/§3/§5) is a separate, larger issue. This file lands only the
// interface shape the plan already specifies for `E2.I4`'s "Produces"
// list, plus an in-memory stand-in so `VaultRepositoryImpl` has something
// to run against until `E2.I5` lands.
//
// The shape matches `us.aherrera.skein.testing.AttachmentStore` (the JVM
// fake `InMemoryVaultRepository` uses) on purpose — see that file's header
// for why `:testing` can't depend on `:core:vault` and ships its own copy
// instead. When `E2.I5` replaces [InMemoryAttachmentStore] here with a
// real, encrypted-at-rest implementation, both call sites keep compiling
// unchanged.

package app.skein.core.vault.blob

import us.aherrera.skein.core.model.DocId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/** Blob store `VaultRepositoryImpl` delegates attachment bytes to. Real impl: `E2.I5` (skein-1nr). */
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
 * In-memory `AttachmentStore` — NOT encrypted, NOT durable across process
 * restarts. Placeholder only: production wiring must switch to the real
 * `E2.I5` implementation before this vault ever holds a real user's data.
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
