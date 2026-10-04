package app.skein.core.vault.key.recovery

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.util.UUID

/**
 * Filesystem primitive, NOT a recovery authorization API. Its caller must bind fresh candidate
 * proof to authenticated, readback-verified wraps in the same operation. No aliases are touched.
 * Retained old/new bytes are never automatically deleted, including after a successful replacement.
 */
internal class RecoveryEnvelopeTransaction(
    private val lease: ClosedVaultRecoveryLease,
    private val syncDirectory: (File) -> Unit = { directory ->
        FileChannel.open(directory.toPath(), READ).use { it.force(true) }
    },
    private val atomicMove: (File, File) -> Unit = { source, target ->
        Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    },
    private val boundary: (Boundary) -> Unit = {},
) {
    internal enum class Boundary { OLD_SYNCED, NEW_SYNCED, PARENT_SYNCED, INSTALL_SYNCED, BEFORE_RENAME, AFTER_RENAME }

    internal enum class State { NOT_COMMITTED, COMMITTED, UNKNOWN }

    /**
     * State is an exact-byte observation, never authentication or session authority. COMMITTED with
     * directorySynced=false means the new envelope is visible but durability is not established.
     * UNKNOWN must not be presented as a failed attempt that left the active envelope untouched.
     */
    internal data class Result(
        val state: State,
        val directorySynced: Boolean = false,
    )

    /**
     * The callback runs after durable preparation and BEFORE_RENAME, outside the revocation monitor.
     * It must revalidate the caller's fresh proof/authentication authority. Bounded envelope readbacks
     * follow it; no full ciphertext hashing or fsync occurs while revocation is excluded.
     * Entry refusal can throw before any files are created. Once preparation starts, IO, cancellation
     * and runtime failures return an observation or UNKNOWN, including failures after a possible move.
     */
    fun replace(
        expectedOld: ByteArray,
        proposed: RecoveryEnvelopeV2,
        checkCancellation: () -> Unit = {},
        validateBeforeCommit: () -> Unit = {},
    ): Result {
        require(expectedOld.isNotEmpty() && expectedOld.size <= MAX_OLD_BYTES) { "invalid prior envelope size" }
        // The caller's buffers and the codec's mutable wrap arrays are not live commit authority.
        val old = expectedOld.copyOf()
        val encoded = proposed.encode()
        lease.assertExclusiveAndClosed()
        val active = owned("keys/key-envelope.v1")
        if (!read(active).contentEquals(old)) return Result(State.NOT_COMMITTED)
        val transaction = owned("keys/recovery-envelopes/${proposed.transactionId}")
        // A transaction identifier is single-use: never overwrite retained evidence from a prior try.
        if (transaction.exists()) return Result(State.UNKNOWN)
        val parent = transaction.parentFile
        if (!parent.isDirectory && !parent.mkdirs()) return Result(State.NOT_COMMITTED)
        if (!transaction.mkdir()) return Result(State.UNKNOWN)
        var renamed = false
        try {
            syncWrite(File(transaction, "old.envelope"), old)
            boundary(Boundary.OLD_SYNCED)
            syncWrite(File(transaction, "new.envelope"), encoded)
            syncDirectory(transaction)
            syncDirectory(parent)
            boundary(Boundary.NEW_SYNCED)
            // parent may have been created above. Publish its name durably in the already-existing
            // keys directory BEFORE an active-envelope rename can make the backups necessary.
            syncDirectory(active.parentFile)
            boundary(Boundary.PARENT_SYNCED)
            val next = File(transaction, "install.envelope")
            syncWrite(next, encoded)
            boundary(Boundary.INSTALL_SYNCED)
            assertReadbacks(transaction, next, old, encoded)
            lease.assertExclusiveAndClosed()
            boundary(Boundary.BEFORE_RENAME)
            validateBeforeCommit()
            // Reject any altered install/retained bytes, including changes at the final callback.
            assertReadbacks(transaction, next, old, encoded)
            val replaced =
                lease.whileExclusiveAndClosed {
                    // Only a cheap cancellation assertion belongs under this monitor.
                    checkCancellation()
                    lease.assertExclusiveAndClosed()
                    if (!read(active).contentEquals(old)) {
                        false
                    } else {
                        atomicMove(next, active)
                        renamed = true
                        true
                    }
                }
            if (!replaced) return Result(State.UNKNOWN)
            boundary(Boundary.AFTER_RENAME)
            syncDirectory(active.parentFile)
            // Even a normally returning move is not a success receipt for different active bytes.
            val observed = observe(transaction, old, encoded)
            return if (observed.state == State.COMMITTED) {
                Result(State.COMMITTED, directorySynced = true)
            } else {
                Result(State.UNKNOWN)
            }
        } catch (_: Exception) {
            // A move may throw after changing the directory entry. Never translate that into a
            // generic pre-commit refusal, and never bless modified receipts as the authorized bytes.
            val observed = observe(transaction, old, encoded)
            return if (renamed && observed.state != State.COMMITTED) Result(State.UNKNOWN) else observed
        }
    }

    /**
     * Observational restart inspection only: retained bytes/checksums never authorize activation.
     * A fresh valid lease is required, and this never creates, repairs, deletes or syncs any file.
     */
    fun reconcile(id: UUID): Result {
        lease.assertExclusiveAndClosed()
        return try {
            val transaction = owned("keys/recovery-envelopes/$id")
            val old = read(File(transaction, "old.envelope"))
            val new = read(File(transaction, "new.envelope"))
            if (old.isEmpty() || RecoveryEnvelopeV2.decode(new).transactionId != id) return Result(State.UNKNOWN)
            observe(transaction, old, new)
        } catch (_: Exception) {
            Result(State.UNKNOWN)
        }
    }

    private fun assertReadbacks(
        transaction: File,
        install: File,
        old: ByteArray,
        new: ByteArray,
    ) {
        if (!read(File(transaction, "old.envelope")).contentEquals(old) ||
            !read(File(transaction, "new.envelope")).contentEquals(new) ||
            !read(install).contentEquals(new)
        ) {
            throw IOException("recovery envelope readback differs")
        }
    }

    private fun observe(
        transaction: File,
        old: ByteArray,
        new: ByteArray,
    ): Result =
        try {
            lease.assertExclusiveAndClosed()
            if (!read(File(transaction, "old.envelope")).contentEquals(old) ||
                !read(File(transaction, "new.envelope")).contentEquals(new)
            ) {
                Result(State.UNKNOWN)
            } else {
                val active = owned("keys/key-envelope.v1")
                lease.whileExclusiveAndClosed {
                    val current = read(active)
                    when {
                        current.contentEquals(new) -> Result(State.COMMITTED)
                        current.contentEquals(old) -> Result(State.NOT_COMMITTED)
                        else -> Result(State.UNKNOWN)
                    }
                }
            }
        } catch (_: Exception) {
            Result(State.UNKNOWN)
        }

    private fun owned(relative: String): File {
        val root = lease.vaultDirectory.absoluteFile
        val file = File(root, relative)
        if (root.canonicalFile != root || file.canonicalFile != file) throw RecoverySnapshotRefused()
        if (File(root, ".vault_reset_in_progress").exists()) throw RecoverySnapshotRefused()
        return file
    }

    private fun read(file: File): ByteArray {
        if (file.canonicalFile != file.absoluteFile || !file.isFile || file.length() > MAX_OLD_BYTES) {
            throw IOException("recovery envelope unavailable")
        }
        file.inputStream().use { input ->
            val bytes = ByteArray(MAX_OLD_BYTES + 1)
            var count = 0
            while (count < bytes.size) {
                val read = input.read(bytes, count, bytes.size - count)
                if (read < 0) break
                count += read
            }
            if (count > MAX_OLD_BYTES) throw IOException("recovery envelope unavailable")
            return bytes.copyOf(count)
        }
    }

    private fun syncWrite(
        file: File,
        bytes: ByteArray,
    ) {
        Files.createFile(file.toPath())
        FileOutputStream(file).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
    }

    private companion object {
        const val MAX_OLD_BYTES = 32768
    }
}
