package app.skein.core.vault.lifecycle

import app.skein.core.vault.key.recovery.ClosedVaultRecoveryLease
import app.skein.core.vault.key.recovery.RecoverySnapshotRefused
import java.io.Closeable
import java.io.File

/**
 * Process-wide admission for the app's vault owners, including distinct lifecycle/provider instances.
 * A normal reservation covers the whole operation or live connection/key, not an isOpen sample.
 * The isolated inference process does not open vault files. Direct test/native connections are not
 * covered; callers outside these production owners must not use this as a cross-process file lock.
 */
internal class VaultRecoveryExclusion private constructor(
    private val directory: File,
) {
    private val guard = Any()
    private var users = 0
    private var exclusive: Reservation? = null
    private var uncertainClosure = false

    fun admit(): Closeable? =
        synchronized(guard) {
            if (exclusive != null) return@synchronized null
            users++
            var closed = false
            Closeable {
                synchronized(guard) {
                    if (!closed) {
                        closed = true
                        users--
                    }
                }
            }
        }

    fun acquireRecovery(): Reservation? = acquire(recovery = true)

    fun acquireReset(): Reservation? = acquire(recovery = false)

    private fun acquire(recovery: Boolean): Reservation? =
        synchronized(guard) {
            if (exclusive != null || users != 0 || uncertainClosure) return@synchronized null
            Reservation(recovery).also { exclusive = it }
        }

    /** Revocation does not release exclusion: the in-flight operation must finish its cleanup. */
    fun invalidateRecovery() =
        synchronized(guard) {
            exclusive?.takeIf { it.recovery }?.valid = false
        }

    /** Native close/finalization was not established. Only a clean process restart resets this. */
    fun poisonRecovery() =
        synchronized(guard) {
            uncertainClosure = true
            exclusive?.valid = false
        }

    inner class Reservation internal constructor(
        internal val recovery: Boolean,
    ) : ClosedVaultRecoveryLease,
        Closeable {
        internal var valid = true
        override val vaultDirectory: File = directory

        override fun assertExclusiveAndClosed() =
            synchronized(guard) {
                if (!recovery ||
                    !valid ||
                    exclusive !== this ||
                    users != 0 ||
                    uncertainClosure
                ) {
                    throw RecoverySnapshotRefused()
                }
            }

        override fun <T> whileExclusiveAndClosed(action: () -> T): T =
            synchronized(guard) {
                assertExclusiveAndClosed()
                action()
            }

        override fun close() =
            synchronized(guard) {
                valid = false
                if (exclusive === this) exclusive = null
            }
    }

    companion object {
        private val instances = mutableMapOf<File, VaultRecoveryExclusion>()

        fun forDirectory(directory: File): VaultRecoveryExclusion =
            synchronized(instances) {
                val canonical = directory.canonicalFile
                instances.getOrPut(canonical) { VaultRecoveryExclusion(canonical) }
            }
    }
}
