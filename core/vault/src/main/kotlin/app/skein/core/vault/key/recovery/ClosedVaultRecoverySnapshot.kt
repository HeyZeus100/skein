package app.skein.core.vault.key.recovery

import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest

/** No production issuer exists yet. An isOpen poll alone does not implement this contract. */
internal interface ClosedVaultRecoveryLease {
    val vaultDirectory: File

    /** Must cover open/create/reset/other recovery for the entire operation, or throw. */
    fun assertExclusiveAndClosed()
}

internal class RecoverySnapshotRefused : IllegalStateException("recovery snapshot unavailable")

/** Private ciphertext copy. No live vault connection is opened or checkpointed by this class. */
internal class ClosedVaultRecoverySnapshot private constructor(
    private val lease: ClosedVaultRecoveryLease,
    private val sources: List<File>,
    private val identity: List<String?>,
    private val directory: File,
    val database: File,
) : Closeable {
    private var closed = false

    fun assertSourceUnchanged() {
        if (closed) throw RecoverySnapshotRefused()
        lease.assertExclusiveAndClosed()
        refuseMarkers(lease.vaultDirectory)
        if (sources.map(::fingerprint) != identity) throw RecoverySnapshotRefused()
        // Success must attest the exact captured ciphertext, not another file at the private path.
        // SQLite may create its private shared-memory index, but may not replace DB/WAL evidence.
        val copies = listOf(database, File(directory, "vault.db-wal"))
        if (copies.map(::fingerprint) != identity.take(2)) throw RecoverySnapshotRefused()
        lease.assertExclusiveAndClosed()
    }

    override fun close() {
        if (!closed) {
            closed = true
            directory.deleteRecursively()
        }
    }

    companion object {
        fun capture(lease: ClosedVaultRecoveryLease): ClosedVaultRecoverySnapshot {
            lease.assertExclusiveAndClosed()
            val root = lease.vaultDirectory.absoluteFile
            if (!root.isDirectory || root.canonicalFile != root) throw RecoverySnapshotRefused()
            refuseMarkers(root)
            val database = File(root, "vault.db")
            val sources =
                listOf(
                    database,
                    File(root, "vault.db-wal"),
                    File(root, "vault.db-shm"),
                    File(root, "keys/key-envelope.v1"),
                )
            val identity = sources.map(::fingerprint)
            if (identity.first() == null ||
                database.length() < 16 ||
                identity.last() == null
            ) {
                throw RecoverySnapshotRefused()
            }
            val staging = File(root, "keys/recovery-proofs")
            if (staging.canonicalFile != staging ||
                (!staging.isDirectory && !staging.mkdirs())
            ) {
                throw RecoverySnapshotRefused()
            }
            val privateDirectory = Files.createTempDirectory(staging.toPath(), "proof-").toFile()
            try {
                val copied = File(privateDirectory, "vault.db")
                sources.take(2).forEachIndexed { index, source ->
                    if (identity[index] != null) {
                        source.inputStream().use { input ->
                            File(privateDirectory, source.name).outputStream().use(input::copyTo)
                        }
                        if (fingerprint(File(privateDirectory, source.name)) !=
                            identity[index]
                        ) {
                            throw RecoverySnapshotRefused()
                        }
                    }
                    lease.assertExclusiveAndClosed()
                }
                return ClosedVaultRecoverySnapshot(lease, sources, identity, privateDirectory, copied).also {
                    it.assertSourceUnchanged()
                }
            } catch (failure: Throwable) {
                privateDirectory.deleteRecursively()
                throw failure
            }
        }

        private fun refuseMarkers(root: File) {
            if (File(root, ".vault_reset_in_progress").exists() || File(root, "vault.db-journal").exists()) {
                throw RecoverySnapshotRefused()
            }
        }

        private fun fingerprint(file: File): String? {
            val path = file.toPath()
            if (Files.isSymbolicLink(path) || file.canonicalFile != file.absoluteFile) throw RecoverySnapshotRefused()
            if (!Files.exists(path, NOFOLLOW_LINKS)) return null
            if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) throw RecoverySnapshotRefused()
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}
