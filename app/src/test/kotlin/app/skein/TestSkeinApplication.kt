package app.skein

import app.skein.core.rag.ingest.IngestPace
import app.skein.core.vault.provider.VaultDocumentsProvider
import app.skein.core.vault.session.UnlockManager
import app.skein.ingest.IngestPacer
import app.skein.ingest.IngestPipelines
import app.skein.ingest.IngestScheduler
import app.skein.ingest.IngestWorkPort
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
                pipelines = { session, attempts, pace -> IngestPipelines.forSession(session, pace, attempts) },
                scope = scope,
            ).also { it.start() }
        return VaultServices(keyProvider, unlockManager, bootstrap, ingest)
    }
}
