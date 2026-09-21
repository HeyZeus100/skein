package app.skein

import android.app.Application
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
 * only `MainActivity`, in the main process, reads it.
 *
 * `open` only so Robolectric tests can substitute fakes through
 * [createVaultServices] (`@Config(application = ...)`); there is no DI
 * framework in this repo and this seam is deliberately the only one.
 */
open class SkeinApplication : Application() {
    val vault: VaultServices by lazy { createVaultServices() }

    protected open fun createVaultServices(): VaultServices = VaultServices.forDevice(this)
}
