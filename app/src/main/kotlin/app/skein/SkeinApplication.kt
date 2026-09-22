package app.skein

import android.app.Application
import androidx.work.Configuration
import androidx.work.DelegatingWorkerFactory
import app.skein.core.model.SkeinLog
import app.skein.export.stage.StagedPlaintextSweeper
import app.skein.ingest.IngestWorker
import app.skein.system.AndroidSkeinLogSink
import app.skein.vault.VaultServices

/**
 * The `:app` process's `Application` (skein-2ige). Owns the one
 * [VaultServices] holder for the process — the composition root for the
 * vault stack (`UnlockManager` → `VaultBootstrap` → open-vault services →
 * `VaultDocumentsProvider`). Nothing else is created here.
 *
 * [vault] is created on first access, not in `onCreate`: the manifest's
 * `android:name` also instantiates this class in the isolated `:inference`
 * and `:embedder` processes, which must never touch the vault (spec §2.6) —
 * only `MainActivity`, in the main process, reads it. [onCreate] installs
 * [AndroidSkeinLogSink] unconditionally, though: every process this
 * `Application` runs in should get real logcat output through [SkeinLog].
 *
 * E5.I10 (skein-7v3): also WorkManager's [Configuration.Provider], so the
 * `IngestWorker` is built by [IngestWorker.Factory] over the process-wide
 * `IngestScheduler` rather than by reflection. WorkManager's automatic
 * initializer is removed from the manifest for this (on-demand
 * initialization); the first `WorkManager.getInstance` call — from
 * `VaultServices.forDevice`, main process only — reads this property, so
 * the isolated processes never initialize WorkManager at all.
 *
 * `open` only so Robolectric tests can substitute fakes through
 * [createVaultServices] (`@Config(application = ...)`); there is no DI
 * framework in this repo and this seam is deliberately the only one.
 */
open class SkeinApplication :
    Application(),
    Configuration.Provider {
    val vault: VaultServices by lazy { createVaultServices() }

    /**
     * skein-0m1z adds a second custom worker ([StagedPlaintextSweeper]), so
     * the single factory becomes a [DelegatingWorkerFactory] chain. Each
     * factory returns `null` for classes that are not its own, and
     * WorkManager falls back to reflection once every delegate has declined
     * — so adding one here never changes how any existing worker is built.
     */
    override val workManagerConfiguration: Configuration
        get() =
            Configuration
                .Builder()
                .setWorkerFactory(
                    DelegatingWorkerFactory().apply {
                        addFactory(IngestWorker.Factory { vault.ingest })
                        addFactory(StagedPlaintextSweeper.Factory { vault.exportStages })
                    },
                ).build()

    override fun onCreate() {
        super.onCreate()
        SkeinLog.sink = AndroidSkeinLogSink
        // Real (harmless) SkeinLog.d/i call sites, deliberately kept here:
        // AC(a) of E1.I11/skein-4je is proven by their *absence* from the
        // release dex despite existing in source — see
        // tools/logging/check-release-dex.sh's run log for the proof. A
        // build with no call sites at all wouldn't actually exercise R8's
        // `-assumenosideeffects` stripping.
        SkeinLog.d("SkeinApplication", "log sink installed")
        SkeinLog.i("SkeinApplication", "process started")
    }

    protected open fun createVaultServices(): VaultServices = VaultServices.forDevice(this)
}
