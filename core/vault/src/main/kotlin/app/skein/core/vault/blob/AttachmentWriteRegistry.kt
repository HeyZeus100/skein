package app.skein.core.vault.blob

import app.skein.core.model.DocId
import java.io.File

/**
 * Shared by store instances for the same vault, including a previous session unwinding
 * after lock. Reservation registration never waits for a database lock; no suspend work
 * runs under this monitor. A sweep holds the database writer first, then this monitor,
 * and skips active ids rather than waiting for their metadata commit (the reverse order).
 */
internal class AttachmentWriteRegistry private constructor() {
    private val active = mutableMapOf<DocId, Int>()

    suspend fun <T> reserve(
        id: DocId,
        block: suspend () -> T,
    ): T {
        synchronized(this) { active[id] = (active[id] ?: 0) + 1 }
        try {
            return block()
        } finally {
            synchronized(this) {
                val count = checkNotNull(active[id])
                if (count == 1) active.remove(id) else active[id] = count - 1
            }
        }
    }

    fun <T> sweep(block: (Set<DocId>) -> T): T = synchronized(this) { block(active.keys) }

    companion object {
        private val directories = mutableMapOf<String, AttachmentWriteRegistry>()

        fun inMemory(): AttachmentWriteRegistry = AttachmentWriteRegistry()

        fun forDirectory(dir: File): AttachmentWriteRegistry =
            synchronized(directories) {
                directories.getOrPut(dir.canonicalPath) { AttachmentWriteRegistry() }
            }
    }
}
