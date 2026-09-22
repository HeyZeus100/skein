package app.skein

import androidx.work.WorkManager
import app.skein.core.rag.ingest.IngestPace
import app.skein.core.vault.lifecycle.VaultReset
import app.skein.core.vault.provider.VaultDocumentsProvider
import app.skein.core.vault.session.LockPolicy
import app.skein.core.vault.session.UnlockManager
import app.skein.core.vault.session.UnlockState
import app.skein.export.stage.ExportStageCoordinator
import app.skein.export.stage.FakeExportStageRepository
import app.skein.ingest.IngestPacer
import app.skein.ingest.IngestPipelines
import app.skein.ingest.IngestScheduler
import app.skein.ingest.IngestWorkPort
import app.skein.system.SecurityPrefs
import app.skein.vault.DocumentsProviderPort
import app.skein.vault.ScriptedVaultKeyProvider
import app.skein.vault.VaultBootstrap
import app.skein.vault.VaultOpenException
import app.skein.vault.VaultServices
import app.skein.vault.VaultSession
import kotlinx.coroutines.CompletableDeferred
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
 *
 * E5.I10 (skein-7v3): the `IngestScheduler` is wired over a recording
 * [IngestWorkPort] ([enqueuedEpochs]) instead of WorkManager, so no
 * Robolectric test here initializes WorkManager as a side effect.
 */
class TestSkeinApplication : SkeinApplication() {
    val keyProvider = ScriptedVaultKeyProvider()
    val repository = InMemoryVaultRepository()
    val personaService = InMemoryPersonaService()

    /** Session epochs the scheduler asked the (fake) work port to enqueue, in order. */
    val enqueuedEpochs = java.util.Collections.synchronizedList(mutableListOf<Long>())

    /** When non-null, every open fails with this reason (exercises the gate's failure state). */
    @Volatile
    var failOpenWith: String? = null

    /**
     * Controls when a successful vault open completes for deterministic testing (skein-spe3).
     * Used by the retry test to gate the open until the test explicitly allows it.
     * Starts as a completed deferred so normal tests proceed without waiting.
     */
    var openReadySignal: CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { it.complete(Unit) }

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
                    // For deterministic testing (skein-spe3): gate successful opens behind
                    // a signal the test controls, so the retry path is not timing-sensitive.
                    openReadySignal.await()
                    VaultSession(
                        repository = repository,
                        indexStore = InMemoryIndexStore(),
                        personaService = personaService,
                        exportService = FakeExportService(),
                        importService = FakeImportService(),
                        exportStages = FakeExportStageRepository(),
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
        val ingest =
            IngestScheduler(
                unlockManager = unlockManager,
                session = bootstrap.session,
                port =
                    object : IngestWorkPort {
                        override fun enqueue(sessionEpoch: Long) {
                            enqueuedEpochs += sessionEpoch
                        }

                        override fun cancel() = Unit
                    },
                pacer =
                    object : IngestPacer {
                        override fun pace(): IngestPace = IngestPace.FULL
                    },
                pipelines = { session, pace -> IngestPipelines.forSession(session, pace) },
                scope = scope,
            ).also { it.start() }
        // skein-0m1z: the real coordinator over the test session's fake
        // repository. `workManager` is a lambda and is only resolved by
        // `record()`, which no test in this source set calls, so a test that
        // never initialises WorkManager is unaffected.
        val exportStages =
            ExportStageCoordinator(
                unlockManager = unlockManager,
                repository = { bootstrap.session.value?.exportStages },
                workManager = { WorkManager.getInstance(this) },
                stagingDir = File(cacheDir, "staging_export"),
                scope = scope,
            )
        return VaultServices(keyProvider, unlockManager, bootstrap, ingest, vaultReset, exportStages)
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
