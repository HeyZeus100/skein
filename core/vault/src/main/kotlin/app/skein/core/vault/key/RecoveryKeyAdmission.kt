package app.skein.core.vault.key

import app.skein.core.vault.lifecycle.VaultRecoveryExclusion
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable

/** Keeps recovery excluded through authentication AND the lifetime of the resulting live key. */
internal class RecoveryKeyAdmission(
    private val rawKey: () -> ByteArray?,
    private val clearKey: () -> Unit,
    private val exclusion: VaultRecoveryExclusion,
) {
    private val operations = Mutex()
    private val guard = Any()
    private var generation = 0L
    private var retained: Closeable? = null

    fun currentKey(): ByteArray? =
        synchronized(guard) {
            if (retained == null) null else rawKey()
        }

    fun lock() {
        exclusion.invalidateRecovery()
        synchronized(guard) {
            generation++
            clearKey()
            retained?.close()
            retained = null
        }
    }

    internal suspend fun <T> guarded(
        refused: T,
        cancelled: T,
        action: suspend () -> T,
    ): T {
        val requested = synchronized(guard) { generation }
        return operations.withLock {
            if (synchronized(guard) { generation != requested }) return@withLock cancelled
            val admission = exclusion.admit() ?: return@withLock refused
            val started = requested
            var keep = false
            var completed = false
            try {
                currentCoroutineContext().ensureActive()
                val result = action()
                currentCoroutineContext().ensureActive()
                synchronized(guard) {
                    if (generation != started) {
                        clearKey()
                        return@synchronized cancelled
                    }
                    if (rawKey() != null && retained == null) {
                        retained = admission
                        keep = true
                    }
                    completed = true
                    result
                }
            } finally {
                synchronized(guard) {
                    if (!completed) {
                        clearKey()
                        retained?.close()
                        retained = null
                    }
                    if (!keep) admission.close()
                }
            }
        }
    }
}
