package app.skein

import app.skein.core.vault.lifecycle.VaultReset
import app.skein.core.vault.provider.VaultDocumentsProvider
import app.skein.core.vault.session.LockPolicy
import app.skein.core.vault.session.UnlockManager
import app.skein.core.vault.session.UnlockState
import app.skein.system.SecurityPrefs
import app.skein.vault.DocumentsProviderPort
import app.skein.vault.ScriptedVaultKeyProvider
import app.skein.vault.VaultBootstrap
import app.skein.vault.VaultOpenException
import app.skein.vault.VaultServices
import app.skein.vault.VaultSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import us.aherrera.skein.testing.FakeExportService
import us.aherrera.skein.testing.FakeImportService
import us.aherrera.skein.testing.InMemoryIndexStore
import us.aherrera.skein.testing.InMemoryPersonaService
import us.aherrera.skein.testing.InMemoryVaultRepository
import java.io.File
import java.time.Duration

/**
 * Robolectric stand-in for [SkeinApplication] (`@Config(application = ...)`):
 * the same [VaultServices] shape over a [ScriptedVaultKeyProvider], an
 * in-memory vault, and a no-op provider port — so `MainActivity` tests can
 * drive the real setup → unlock → bring-up → shell path without SQLCipher
 * or a biometric prompt. The bootstrap runs the production first-persona
 * seed ([VaultServices.seedFirstPersona]) over [personaService].
 */
class TestSkeinApplication : SkeinApplication() {
    val keyProvider = ScriptedVaultKeyProvider()
    val repository = InMemoryVaultRepository()
    val personaService = InMemoryPersonaService()

    /** When non-null, every open fails with this reason (exercises the gate's failure state). */
    @Volatile
    var failOpenWith: String? = null

    /** skein-v3wb: aliases the fake keystore in [createVaultServices] recorded a delete call for. */
    val deletedKeystoreAliases: MutableList<String> = mutableListOf()

    override fun createVaultServices(): VaultServices {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val unlockManager = UnlockManager(keyProvider = keyProvider, scope = scope)
        val vaultReset =
            VaultReset(
                vaultDir = filesDir,
                databaseFile = File(filesDir, "vault.db"),
                attachmentsDir = File(filesDir, VaultServices.ATTACHMENTS_DIR),
                stagingDir = File(cacheDir, "staging_export"),
                keystore = { alias -> deletedKeystoreAliases += alias },
                isUnlocked = { unlockManager.state.value is UnlockState.Unlocked },
            )
        val bootstrap =
            VaultBootstrap(
                unlockManager = unlockManager,
                openVault = {
                    failOpenWith?.let { throw VaultOpenException(it) }
                    VaultSession(
                        repository = repository,
                        indexStore = InMemoryIndexStore(),
                        personaService = personaService,
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
                seed = VaultServices::seedFirstPersona,
            )
        wireLockPolicyForTest(unlockManager, scope)
        return VaultServices(keyProvider, unlockManager, bootstrap, vaultReset)
    }

    /**
     * Test-only mirror of `VaultServices.forDevice`'s private
     * `wireLockPolicy` (skein-up0/skein-qsux): collects this same
     * [SecurityPrefs] instance `MainActivity` reads/writes into
     * [UnlockManager.configure], live, for the lifetime of [scope] — so a
     * Robolectric test that drives Settings › Security through the real
     * `MainActivity` UI can assert `unlockManager.policy` reflects a change
     * without `MainActivity` itself ever calling `configure` (that stays the
     * production wiring's sole responsibility; see `VaultServices.forDevice`'s
     * doc). Duplicated rather than reused because `wireLockPolicy` is a
     * private implementation detail of `VaultServices`'s companion, and this
     * worktree's task is scoped to `:app`'s test sources only.
     */
    private fun wireLockPolicyForTest(
        unlockManager: UnlockManager,
        scope: CoroutineScope,
    ) {
        val securityPrefs = SecurityPrefs(this)
        scope.launch {
            combine(
                securityPrefs.idleTimeoutMinutes,
                securityPrefs.lockOnScreenOff,
                securityPrefs.lockOnBackground,
            ) { minutes, lockOnScreenOff, lockOnBackground ->
                LockPolicy(
                    idleTimeout = Duration.ofMinutes(minutes.toLong()),
                    lockOnScreenOff = lockOnScreenOff,
                    lockOnBackground = lockOnBackground,
                )
            }.collect { policy -> unlockManager.configure(policy) }
        }
    }
}
