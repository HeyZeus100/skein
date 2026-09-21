// skein-2ige — the single process-wide holder `SkeinApplication` owns.
// Plain Kotlin, no DI framework: `forDevice` is the production composition
// root; tests construct the class directly over fakes.

package app.skein.vault

import android.content.Context
import app.skein.core.vault.key.VaultKeyProvider
import app.skein.core.vault.key.VaultKeyProviders
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
         * Production wiring: the device `VaultKeyProvider`
         * (`VaultKeyProviders.forDevice`, skein-txrh — real Keystore +
         * BiometricPrompt, wrapped master in `filesDir/keys/key-envelope.v1`),
         * the real `UnlockManager` (idle poller on a process-wide scope,
         * shutdown-hook lock), [DeviceVaultOpener] under `filesDir`, and the
         * real `VaultDocumentsProvider`. One [VaultPaths] is shared by the key
         * provider and the opener so the envelope sits beside the `vault.db`
         * it unlocks. Until `setup()` has run once on the device
         * (`VaultSetupScreen`, skein-ank2 — gated on
         * `keyProvider.isInitialised()`), every unlock reports
         * `NotInitialised` and the vault stays closed. Every open seeds the
         * first persona ([seedFirstPersona]) before the session is exposed.
         */
        fun forDevice(context: Context): VaultServices {
            val app = context.applicationContext
            val paths = VaultPaths(vaultDir = app.filesDir)
            val keyProvider = VaultKeyProviders.forDevice(app, paths)
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
                    paths = paths,
                    attachmentsDir = File(app.filesDir, ATTACHMENTS_DIR),
                    // E2.I8 (skein-qdo): real `AssetManager` for
                    // `ImportServiceImpl.importPdf`'s `PDFBoxResourceLoader.init`.
                    context = app,
                )
            val bootstrap =
                VaultBootstrap(
                    unlockManager = unlockManager,
                    openVault = opener::open,
                    provider = DocumentsProviderPort.forContext(app),
                    scope = scope,
                    seed = ::seedFirstPersona,
                )
            return VaultServices(keyProvider, unlockManager, bootstrap)
        }

        /**
         * Spec §8.7 "create first persona": `PersonaService.default()` returns
         * the first-created persona or creates "Default" when there is none —
         * idempotent, so every open may run it (skein-ank2).
         */
        suspend fun seedFirstPersona(session: VaultSession) {
            session.personaService.default()
        }
    }
}
