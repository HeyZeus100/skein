package app.skein

import app.skein.core.vault.provider.VaultDocumentsProvider
import app.skein.core.vault.session.UnlockManager
import app.skein.vault.DocumentsProviderPort
import app.skein.vault.ScriptedVaultKeyProvider
import app.skein.vault.VaultBootstrap
import app.skein.vault.VaultOpenException
import app.skein.vault.VaultServices
import app.skein.vault.VaultSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import us.aherrera.skein.testing.FakeExportService
import us.aherrera.skein.testing.FakeImportService
import us.aherrera.skein.testing.InMemoryIndexStore
import us.aherrera.skein.testing.InMemoryPersonaService
import us.aherrera.skein.testing.InMemoryVaultRepository

/**
 * Robolectric stand-in for [SkeinApplication] (`@Config(application = ...)`):
 * the same [VaultServices] shape over a [ScriptedVaultKeyProvider], an
 * in-memory vault, and a no-op provider port — so `MainActivity` tests can
 * drive the real unlock → bring-up → shell path without SQLCipher or a
 * biometric prompt.
 */
class TestSkeinApplication : SkeinApplication() {
    val keyProvider = ScriptedVaultKeyProvider()
    val repository = InMemoryVaultRepository()

    /** When non-null, every open fails with this reason (exercises the gate's failure state). */
    @Volatile
    var failOpenWith: String? = null

    override fun createVaultServices(): VaultServices {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val unlockManager = UnlockManager(keyProvider = keyProvider, scope = scope)
        val bootstrap =
            VaultBootstrap(
                unlockManager = unlockManager,
                openVault = {
                    failOpenWith?.let { throw VaultOpenException(it) }
                    VaultSession(
                        repository = repository,
                        indexStore = InMemoryIndexStore(),
                        personaService = InMemoryPersonaService(),
                        exportService = FakeExportService(),
                        importService = FakeImportService(),
                    ) {}
                },
                provider =
                    object : DocumentsProviderPort {
                        override fun install(services: VaultDocumentsProvider.Services?) = Unit

                        override fun notifyRootsChanged() = Unit
                    },
                scope = scope,
            )
        return VaultServices(keyProvider, unlockManager, bootstrap)
    }
}
