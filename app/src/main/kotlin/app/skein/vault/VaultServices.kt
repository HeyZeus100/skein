// skein-2ige — the single process-wide holder `SkeinApplication` owns.
// Plain Kotlin, no DI framework: `forDevice` is the production composition
// root; tests construct the class directly over fakes.

package app.skein.vault

import android.content.Context
import android.os.PowerManager
import androidx.work.WorkManager
import app.skein.core.export.pdf.PdfStaging
import app.skein.core.inference.thermal.ThermalGovernor
import app.skein.core.vault.key.VaultKeyProvider
import app.skein.core.vault.key.VaultKeyProviders
import app.skein.core.vault.lifecycle.VaultPaths
import app.skein.core.vault.lifecycle.VaultReset
import app.skein.core.vault.session.LockPolicy
import app.skein.core.vault.session.UnlockManager
import app.skein.core.vault.session.UnlockState
import app.skein.export.stage.ExportStageCoordinator
import app.skein.ingest.IngestPipelines
import app.skein.ingest.IngestScheduler
import app.skein.ingest.ThermalIngestPacer
import app.skein.ingest.WorkManagerIngestWorkPort
import app.skein.system.SecurityPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File
import java.time.Duration

/**
 * Everything the vault needs across lock/unlock cycles. [keyProvider],
 * [unlockManager] and [ingest] live as long as the process; the per-session
 * service graph is [session] (`null` while locked), managed by [bootstrap].
 * [vaultReset] (skein-v3wb) is the one sanctioned destructive flow — the
 * corrupt-envelope UI on `BiometricUnlockScreen` is its only production
 * entry point today.
 */
class VaultServices(
    val keyProvider: VaultKeyProvider,
    val unlockManager: UnlockManager,
    val bootstrap: VaultBootstrap,
    /** E5.I10 (skein-7v3): enqueues ingest on open/document change, cancels it on lock, runs `IngestWorker`'s pass. */
    val ingest: IngestScheduler,
    val vaultReset: VaultReset,
    /**
     * skein-0m1z (POST_REVIEW_RESOLUTIONS.md §4.3): records staged export
     * plaintext, runs `StagedPlaintextSweeper`'s pass, and sweeps on lock.
     * Also the `ExportStageRecorder` to hand `PdfExportService` when E2.I11's
     * PDF export UI is wired up.
     */
    val exportStages: ExportStageCoordinator,
) {
    /** The open vault's services, or `null` while locked / not yet brought up. */
    val session: StateFlow<VaultSession?> get() = bootstrap.session

    companion object {
        /** `attachments/<uuidv7>` beside `vault.db` under `filesDir` (spec §3). */
        const val ATTACHMENTS_DIR: String = "attachments"

        /**
         * `cache/staging_export/` — the export staging directory. skein-0m1z
         * made `:app` depend on `:core:export` (for the `ExportStageRecorder`
         * port [ExportStageCoordinator] implements), so this is now the real
         * `PdfStaging.STAGING_DIR_NAME` rather than a literal kept in step by
         * hand. See `VaultReset`'s own doc on why this directory, under
         * `cacheDir` rather than `filesDir`, is still in [VaultReset]'s
         * deletion set.
         */
        private const val EXPORT_STAGING_DIR_NAME: String = PdfStaging.STAGING_DIR_NAME

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
            val vaultReset =
                VaultReset.forDevice(
                    context = app,
                    paths = paths,
                    attachmentsDir = File(app.filesDir, ATTACHMENTS_DIR),
                    stagingDir = File(app.cacheDir, EXPORT_STAGING_DIR_NAME),
                    isUnlocked = { unlockManager.state.value is UnlockState.Unlocked },
                )
            // skein-v3wb: finish a reset interrupted by a previous process
            // death BEFORE anything below (or the gate's `isInitialised()`
            // probe) touches the vault, so a half-done reset never looks
            // like a usable — or ambiguously corrupt — vault.
            vaultReset.resumeIfPending()
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
            wireLockPolicy(app, unlockManager, scope)
            val ingest = wireIngest(app, unlockManager, bootstrap, scope)
            val exportStages = wireExportStages(app, unlockManager, bootstrap, scope)
            return VaultServices(keyProvider, unlockManager, bootstrap, ingest, vaultReset, exportStages)
        }

        /**
         * skein-0m1z (POST_REVIEW_RESOLUTIONS.md §4.3): the staged-plaintext
         * lifetime machinery. Registers itself as a HIGH-priority lock
         * observer (like [wireIngest]'s scheduler) so the sweep runs while
         * the master key is still live and the vault still open — see
         * [ExportStageCoordinator]'s header for why that is `onLocking` and
         * not `onLocked`.
         */
        private fun wireExportStages(
            context: Context,
            unlockManager: UnlockManager,
            bootstrap: VaultBootstrap,
            scope: CoroutineScope,
        ): ExportStageCoordinator =
            ExportStageCoordinator(
                unlockManager = unlockManager,
                repository = { bootstrap.session.value?.exportStages },
                workManager = { WorkManager.getInstance(context) },
                stagingDir = File(context.cacheDir, EXPORT_STAGING_DIR_NAME),
                scope = scope,
            )

        /**
         * E5.I10 (skein-7v3): the ingest pass over the open session —
         * WorkManager for dispatch/retry, `ThermalGovernor` (E4.I9) plus the
         * battery signal for batch pacing, and `IngestPipelines.forSession`
         * for the steps. No embedder yet (skein-079): lexical (FTS) rows and
         * wikilink/tag edges populate now, vectors stay "pending". The
         * scheduler registers itself as a HIGH-priority lock observer so the
         * work is cancelled before [VaultBootstrap] closes the session.
         */
        private fun wireIngest(
            context: Context,
            unlockManager: UnlockManager,
            bootstrap: VaultBootstrap,
            scope: CoroutineScope,
        ): IngestScheduler {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val thermal = ThermalGovernor(context, powerManager)
            return IngestScheduler(
                unlockManager = unlockManager,
                session = bootstrap.session,
                port = WorkManagerIngestWorkPort(WorkManager.getInstance(context)),
                pacer = ThermalIngestPacer.forGovernor(thermal),
                pipelines = { session, pace -> IngestPipelines.forSession(session, pace) },
                scope = scope,
            ).also { it.start() }
        }

        /**
         * `E3.I14` (skein-up0): applies Settings › Security's lock-policy
         * prefs into [UnlockManager.configure] live, for the lifetime of the
         * process, and wires the screen-off/background lock triggers
         * ([LockPolicyObserver]). This — not `MainActivity`, which only
         * reads/writes `SecurityPrefs` for display — is the single source of
         * truth that turns a persisted preference into an effective policy,
         * so a change made from any host (Settings screen, a future
         * quick-settings tile, …) takes effect without that host needing to
         * know about `UnlockManager` at all.
         */
        private fun wireLockPolicy(
            context: Context,
            unlockManager: UnlockManager,
            scope: CoroutineScope,
        ) {
            val securityPrefs = SecurityPrefs(context)
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
            LockPolicyObserver.registerWith(context, unlockManager)
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
