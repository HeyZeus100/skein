// skein-2ige — the single process-wide holder `SkeinApplication` owns.
// Plain Kotlin, no DI framework: `forDevice` is the production composition
// root; tests construct the class directly over fakes.

package app.skein.vault

import android.content.Context
import app.skein.core.vault.key.VaultKeyProvider
import app.skein.core.vault.lifecycle.VaultPaths
import app.skein.core.vault.session.UnlockManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Everything the vault needs across lock/unlock cycles. [keyProvider] and
 * [unlockManager] live as long as the process; the per-session service
 * graph is [session] (`null` while locked), managed by [bootstrap].
 */
class VaultServices(
    val keyProvider: VaultKeyProvider,
    val unlockManager: UnlockManager,
    val bootstrap: VaultBootstrap,
) {
    /** The open vault's services, or `null` while locked / not yet brought up. */
    val session: StateFlow<VaultSession?> get() = bootstrap.session

    companion object {
        /** `attachments/<uuidv7>` beside `vault.db` under `filesDir` (spec §3). */
        const val ATTACHMENTS_DIR: String = "attachments"

        /**
         * Production wiring: the real `UnlockManager` (idle poller on a
         * process-wide scope, shutdown-hook lock), [DeviceVaultOpener] under
         * `filesDir`, and the real `VaultDocumentsProvider`. [keyProvider]
         * defaults to the fail-closed [UnprovisionedVaultKeyProvider] until
         * `:core:vault` exposes a factory for the device implementation.
         */
        fun forDevice(
            context: Context,
            keyProvider: VaultKeyProvider = UnprovisionedVaultKeyProvider,
        ): VaultServices {
            val app = context.applicationContext
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val unlockManager =
                UnlockManager(
                    keyProvider = keyProvider,
                    scope = scope,
                    installShutdownHook = true,
                )
            val opener =
                DeviceVaultOpener(
                    keyProvider = keyProvider,
                    paths = VaultPaths(vaultDir = app.filesDir),
                    attachmentsDir = File(app.filesDir, ATTACHMENTS_DIR),
                )
            val bootstrap =
                VaultBootstrap(
                    unlockManager = unlockManager,
                    openVault = opener::open,
                    provider = DocumentsProviderPort.forContext(app),
                    scope = scope,
                )
            return VaultServices(keyProvider, unlockManager, bootstrap)
        }
    }
}
