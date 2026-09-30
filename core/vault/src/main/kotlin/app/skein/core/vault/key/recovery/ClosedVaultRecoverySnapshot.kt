package app.skein.core.vault.key.recovery

import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest

/** Issued by VaultRecoveryExclusion; an isOpen poll alone does not implement this contract. */
internal interface ClosedVaultRecoveryLease {
    val vaultDirectory: File

    /** Must cover open/create/reset/other recovery for the entire operation, or throw. */
    fun assertExclusiveAndClosed()

    /** Serializes the final publication with revocation; production overrides with its admission guard. */
    fun <T> whileExclusiveAndClosed(action: () -> T): T {
        assertExclusiveAndClosed()
        return action()
    }
}

internal class RecoverySnapshotRefused : IllegalStateException("recovery snapshot unavailable")

/** Private ciphertext copy. No live vault connection is opened or checkpointed by this class. */
internal class ClosedVaultRecoverySnapshot private constructor(
    private val lease: ClosedVaultRecoveryLease,
    private val sources: List<File>,
    private val identity: List<String?>,
    private val privateIdentity: List<String?>,
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
        if (copies.map(::fingerprint) != privateIdentity) throw RecoverySnapshotRefused()
        lease.assertExclusiveAndClosed()
    }

    override fun close() {
        if (!closed) {
            closed = true
            directory.deleteRecursively()
        }
    }

    companion object {
        private const val EMPTY_FILE_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

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
                // SQLite opens the WAL with CREATE even for a read-only main database.
                // Stage that empty sidecar deliberately when the source has no WAL, so
                // native reads cannot change the sealed private evidence from absent to
                // empty. Source absence remains pinned separately and never normalized.
                if (identity[1] == null) {
                    Files.createFile(File(privateDirectory, "vault.db-wal").toPath())
                }
                // Derive expected identities only from captured evidence or known empty
                // bytes, never from a fresh fingerprint that could bless changed copies.
                val privateIdentity = listOf(identity[0], identity[1] ?: EMPTY_FILE_SHA256)
                return ClosedVaultRecoverySnapshot(
                    lease,
                    sources,
                    identity,
                    privateIdentity,
                    privateDirectory,
                    copied,
                ).also {
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
