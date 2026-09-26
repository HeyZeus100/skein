// JVM fake for `ModelRegistry` (skein-cyq, E4.I5). Same style as
// `InMemoryVaultRepository`: one `Mutex` guarding a plain `Map`, no real
// transactional isolation — that is the SQL-backed impl's job.

package app.skein.testing

import app.skein.core.model.ModelId
import app.skein.core.model.ModelRecord
import app.skein.core.model.ModelRegistry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory [ModelRegistry], proven by [ModelRegistryContractTest] to
 * satisfy the same contract as `:core:vault`'s SQL-backed
 * `ModelRegistryImpl`.
 */
public class InMemoryModelRegistry : ModelRegistry {
    private val mutex: Mutex = Mutex()
    private val records: MutableMap<ModelId, ModelRecord> = linkedMapOf()
    private var defaultId: ModelId? = null

    override suspend fun list(): List<ModelRecord> = mutex.withLock { records.values.toList() }

    override suspend fun get(id: ModelId): ModelRecord? = mutex.withLock { records[id] }

    override suspend fun upsert(record: ModelRecord) {
        mutex.withLock { records[record.model.id] = record }
    }

    override suspend fun delete(id: ModelId) {
        mutex.withLock { records.remove(id) }
    }

    override suspend fun setDefault(id: ModelId?) {
        mutex.withLock { defaultId = id }
    }

    override suspend fun default(): ModelId? = mutex.withLock { defaultId }
}
