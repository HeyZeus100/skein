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
 * Unwired filesystem primitive, NOT a recovery authorization API. A future coordinator must bind
 * candidate proof to authenticated, readback-verified wraps before calling it. No aliases are touched.
 * Retained old/new bytes are never automatically deleted, including after a successful replacement.
 */
internal class RecoveryEnvelopeTransaction(
    private val lease: ClosedVaultRecoveryLease,
    private val syncDirectory: (File) -> Unit = { directory ->
        FileChannel.open(directory.toPath(), READ).use { it.force(true) }
    },
    private val boundary: (Boundary) -> Unit = {},
) {
    internal enum class Boundary { OLD_SYNCED, NEW_SYNCED, PARENT_SYNCED, BEFORE_RENAME, AFTER_RENAME }

    internal enum class State { NOT_COMMITTED, COMMITTED, UNKNOWN }

    internal data class Result(
        val state: State,
        val directorySynced: Boolean = false,
    )

    fun replace(
        expectedOld: ByteArray,
        proposed: RecoveryEnvelopeV2,
    ): Result {
        require(expectedOld.isNotEmpty() && expectedOld.size <= MAX_OLD_BYTES) { "invalid prior envelope size" }
        lease.assertExclusiveAndClosed()
        val active = owned("keys/key-envelope.v1")
        if (!read(active).contentEquals(expectedOld)) return Result(State.NOT_COMMITTED)
        val transaction = owned("keys/recovery-envelopes/${proposed.transactionId}")
        val encoded = proposed.encode()
        // A transaction identifier is single-use: never overwrite retained evidence from a prior try.
        if (transaction.exists()) return Result(State.UNKNOWN)
        val parent = transaction.parentFile
        if (!parent.isDirectory && !parent.mkdirs()) return Result(State.NOT_COMMITTED)
        if (!transaction.mkdir()) return Result(State.UNKNOWN)
        try {
            syncWrite(File(transaction, "old.envelope"), expectedOld)
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
            lease.assertExclusiveAndClosed()
            boundary(Boundary.BEFORE_RENAME)
            val replaced =
                lease.whileExclusiveAndClosed {
                    if (!read(active).contentEquals(expectedOld)) {
                        false
                    } else {
                        Files.move(next.toPath(), active.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                        true
                    }
                }
            if (!replaced) return Result(State.UNKNOWN)
            boundary(Boundary.AFTER_RENAME)
            syncDirectory(active.parentFile)
            return Result(State.COMMITTED, directorySynced = true)
        } catch (_: IOException) {
            return reconcile(proposed.transactionId)
        }
    }

    /** Exact active bytes decide restart state; no phase marker is treated as a commit receipt. */
    fun reconcile(id: UUID): Result {
        lease.assertExclusiveAndClosed()
        return try {
            val transaction = owned("keys/recovery-envelopes/$id")
            val old = read(File(transaction, "old.envelope"))
            val new = read(File(transaction, "new.envelope"))
            if (old.isEmpty() || RecoveryEnvelopeV2.decode(new).transactionId != id) return Result(State.UNKNOWN)
            val active = read(owned("keys/key-envelope.v1"))
            when {
                active.contentEquals(new) -> Result(State.COMMITTED)
                active.contentEquals(old) -> Result(State.NOT_COMMITTED)
                else -> Result(State.UNKNOWN)
            }
        } catch (_: IOException) {
            Result(State.UNKNOWN)
        } catch (_: RecoveryEnvelopeFormatException) {
            Result(State.UNKNOWN)
        }
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
