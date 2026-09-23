// skein-2ige — the open-vault service graph. Exists only between a
// successful `VaultBootstrap.bringUp()` and the next lock.

package app.skein.vault

import app.skein.core.model.ExportService
import app.skein.core.model.ImportService
import app.skein.core.model.IndexStore
import app.skein.core.model.PersonaService
import app.skein.core.model.VaultRepository
import app.skein.core.vault.export.stage.ExportStageRepository
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * Every service that exists only while the vault is open. Built by an
 * opener ([DeviceVaultOpener] on a device, in-memory fakes in tests) and
 * owned by [VaultBootstrap], which drops it on lock. Holds the
 * `:core:model` contracts rather than the SQL implementations so consumers
 * (and tests) never see a connection or any key-derived state.
 *
 * [release] closes every connection behind the services; [close] runs it
 * at most once. A release cut short by cancellation (a lock that overran
 * its observer budget, `LOCK_POLICY_INDEXING.md` §4.2) is retried by the
 * next [close] call rather than silently counted as done.
 */
class VaultSession(
    val repository: VaultRepository,
    val indexStore: IndexStore,
    val personaService: PersonaService,
    val exportService: ExportService,
    val importService: ImportService,
    /**
     * skein-0m1z: `export_stages` (migration 005) for this open vault —
     * where `ExportStageCoordinator` records staged plaintext and marks it
     * swept. A `:core:vault` interface rather than a `:core:model` contract
     * (see its KDoc), and `null`-able only in the sense that the whole
     * session is: while locked there is no session and nothing to sweep by
     * row.
     */
    val exportStages: ExportStageRepository,
    private val release: suspend () -> Unit,
) {
    private val closed = AtomicBoolean(false)

    /** Releases the connections behind this session. Idempotent. */
    suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            release()
        } catch (e: CancellationException) {
            closed.set(false)
            throw e
        }
    }
}
