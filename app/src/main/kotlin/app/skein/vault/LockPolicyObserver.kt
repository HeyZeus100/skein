// skein-up0 (E3.I14) — wires the two opt-in/opt-out lock triggers Settings ›
// Security exposes ("lock when screen turns off", "lock when app leaves
// foreground") into `UnlockManager.lock(...)`. Split into a plain-Kotlin
// core ([LockPolicyObserver], JVM-testable with a fake manager) and a thin
// Android adapter ([LockPolicyObserver.registerWith]) that subscribes it to
// `ProcessLifecycleOwner` and `Intent.ACTION_SCREEN_OFF` — the two signals
// the bead's two settings map to (see `LockReason.SCREEN_OFF_POLICY` /
// `.BACKGROUND_POLICY` for why they're independent).
//
// Registered from `VaultServices.forDevice` (not `SkeinApplication`, and
// never `MainActivity` — see that file's own note on why registration lives
// there and only fires once per process, in the main process only).

package app.skein.vault

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.skein.core.vault.session.LockPolicy
import app.skein.core.vault.session.LockReason
import app.skein.core.vault.session.UnlockManager

/**
 * Takes [currentPolicy]/[requestLock] as plain function references, not a
 * concrete [UnlockManager], so the gating logic here is testable with a
 * fake policy + a recording lambda — no real `UnlockManager`/`VaultKeyProvider`
 * needed. [currentPolicy] is called at the moment each Android signal fires
 * (not once at construction) so a live Settings change takes effect on the
 * very next trigger, with no re-registration needed.
 */
internal class LockPolicyObserver(
    private val currentPolicy: () -> LockPolicy,
    private val requestLock: (LockReason) -> Unit,
) {
    /** `Intent.ACTION_SCREEN_OFF` handler. */
    fun onScreenOff() {
        if (currentPolicy().lockOnScreenOff) {
            requestLock(LockReason.SCREEN_OFF_POLICY)
        }
    }

    /** `ProcessLifecycleOwner`'s `Lifecycle.Event.ON_STOP` handler. */
    fun onAppBackground() {
        if (currentPolicy().lockOnBackground) {
            requestLock(LockReason.BACKGROUND_POLICY)
        }
    }

    companion object {
        /**
         * Production wiring: subscribes [LockPolicyObserver] to the process's
         * single `ProcessLifecycleOwner` and to a dynamically registered
         * `ACTION_SCREEN_OFF` receiver (no manifest edit — matches the
         * pattern `LOCK_POLICY_INDEXING.md` uses elsewhere for push-only,
         * no-permission-required system signals). Both registrations live
         * for the process's lifetime; there is no matching `unregister`
         * because [VaultServices] is a process-scoped singleton
         * ([VaultServices.forDevice] runs at most once per process, via
         * `SkeinApplication.vault`'s `by lazy`).
         */
        fun registerWith(
            context: Context,
            unlockManager: UnlockManager,
        ): LockPolicyObserver {
            val observer =
                LockPolicyObserver(
                    currentPolicy = { unlockManager.policy.value },
                    requestLock = unlockManager::lock,
                )

            ProcessLifecycleOwner.get().lifecycle.addObserver(
                object : DefaultLifecycleObserver {
                    override fun onStop(owner: LifecycleOwner) = observer.onAppBackground()
                },
            )

            val screenOffReceiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        receiverContext: Context?,
                        intent: Intent?,
                    ) = observer.onScreenOff()
                }
            context.applicationContext.registerReceiver(
                screenOffReceiver,
                IntentFilter(Intent.ACTION_SCREEN_OFF),
            )

            return observer
        }
    }
}
