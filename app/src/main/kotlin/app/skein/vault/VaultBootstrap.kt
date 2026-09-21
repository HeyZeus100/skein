// skein-2ige — `:app` vault bring-up: the one place that turns an unlocked
// `UnlockManager` into an open vault, hands the live services to
// `VaultDocumentsProvider`, and tears all of it down again on lock.
//
// Unlock sequence (`bringUp`, driven by the shell once `UnlockManager.state`
// is `Unlocked`):
//   1. `openVault()` — create-or-open the SQLCipher file and build the
//      service graph (`DeviceVaultOpener` on a device).
//   1b. `seed(session)` (skein-ank2) — first-run content the shell expects
//      before it lands: production ensures the first persona exists
//      (`PersonaService.default()`, spec §8.7 "create first persona"),
//      which is idempotent, so it runs on every open rather than only the
//      one that created `vault.db` — a crash between create and seed can
//      never leave a persona-less vault. Runs before the session is
//      exposed or the provider installed; a seed failure closes the vault
//      and reports `BringUpResult.Failed`.
//   2. `VaultDocumentsProvider.install(Services(repository, exportService,
//      unlockManager.state))` — the provider stops failing closed.
//   3. `VaultDocumentsProvider.notifyRootsChanged(context)` — an open
//      picker re-queries and sees the root.
//
// Lock sequence (`LockObserver.onLocking`, i.e. while the master key is
// still live — `LOCK_POLICY_INDEXING.md` §4.4 closes the vault BEFORE the
// key is zeroed, and `UnlockManager` zeroes it only after every observer
// has returned):
//   1. `install(null)` — the provider fails closed again.
//   2. `notifyRootsChanged` — an open picker drops the root.
//   3. `session.close()` — every connection closes (`VaultLifecycle.close`
//      last, so its WAL checkpoint sees no other readers).
//   4. the session reference is dropped.
// `onLocked` (after zeroization) is the hard backstop: if `onLocking` was
// cut off by the observer budget (`FORCE_TIMEOUT`) the reference is dropped
// regardless and the leftover release is retried off the lock path.
//
// Priority is LOW so isolated-service proxies (HIGH, §5.1) push their
// `onSessionLocking` before the connection goes away. Within LOW the
// current `UnlockManager` runs observers concurrently; once the editor's
// autosave flush (`E7.I4`, also LOW) exists, `E3.I3b`'s registry must order
// it ahead of this observer (§4.4 step 1 before step 2) — noted on the bd.
//
// No key material is held here: `openVault` copies the key from
// `VaultKeyProvider` per connection and the callee zeroes each copy.

package app.skein.vault

import android.content.Context
import app.skein.core.vault.provider.VaultDocumentsProvider
import app.skein.core.vault.session.LockObserver
import app.skein.core.vault.session.LockObserverPriority
import app.skein.core.vault.session.UnlockManager
import app.skein.core.vault.session.UnlockState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The bootstrap's only view of the `DocumentsProvider`. Production wraps
 * the provider's static entry points ([forContext]); tests record calls.
 */
interface DocumentsProviderPort {
    /** [VaultDocumentsProvider.install]: the live services, or `null` to fail closed. */
    fun install(services: VaultDocumentsProvider.Services?)

    /** [VaultDocumentsProvider.notifyRootsChanged]: tell DocumentsUI to re-query roots. */
    fun notifyRootsChanged()

    companion object {
        /** The real provider, notified through [context]'s `ContentResolver`. */
        fun forContext(context: Context): DocumentsProviderPort {
            val appContext = context.applicationContext
            return object : DocumentsProviderPort {
                override fun install(services: VaultDocumentsProvider.Services?) =
                    VaultDocumentsProvider.install(services)

                override fun notifyRootsChanged() = VaultDocumentsProvider.notifyRootsChanged(appContext)
            }
        }
    }
}

/** An opener could not produce a [VaultSession]. [message] carries no key material. */
class VaultOpenException(
    message: String,
) : RuntimeException(message)

/** Outcome of [VaultBootstrap.bringUp]. */
sealed class BringUpResult {
    /** The vault is open and the provider is installed. */
    data class Ready(
        val session: VaultSession,
    ) : BringUpResult()

    /** `UnlockManager.state` was not `Unlocked` (before, or by the time the vault had opened). */
    object NotUnlocked : BringUpResult()

    /** The opener failed; [reason] is user-presentable and free of key material. */
    data class Failed(
        val reason: String,
    ) : BringUpResult()
}

/**
 * See the file header for the sequences. [openVault] runs on [scope] so a
 * caller cancelled mid-open (an Activity being destroyed) cannot leave a
 * half-opened vault behind; concurrent [bringUp] calls share one open.
 * [seed] runs against the freshly opened session on the same scope before
 * anything else sees it (file header, step 1b).
 */
class VaultBootstrap(
    private val unlockManager: UnlockManager,
    private val openVault: suspend () -> VaultSession,
    private val provider: DocumentsProviderPort,
    private val scope: CoroutineScope,
    private val seed: suspend (VaultSession) -> Unit = {},
) {
    private val mutex = Mutex()
    private val sessionState = MutableStateFlow<VaultSession?>(null)

    /** The open vault, or `null` while locked / not yet brought up. */
    val session: StateFlow<VaultSession?> = sessionState.asStateFlow()

    @Volatile
    private var inFlight: Deferred<BringUpResult>? = null

    init {
        unlockManager.addLockObserver(LockHandler())
    }

    /**
     * Opens the vault and installs the provider (file header, "Unlock
     * sequence"). Idempotent: returns the existing session when already up,
     * joins an in-flight open, and refuses ([BringUpResult.NotUnlocked])
     * unless `UnlockManager.state` is `Unlocked`.
     */
    suspend fun bringUp(): BringUpResult {
        val pending =
            mutex.withLock {
                sessionState.value?.let { return BringUpResult.Ready(it) }
                if (unlockManager.state.value !is UnlockState.Unlocked) return BringUpResult.NotUnlocked
                inFlight ?: scope.async { openAndInstall() }.also { inFlight = it }
            }
        return pending.await()
    }

    private suspend fun openAndInstall(): BringUpResult =
        try {
            val opened = openVault()
            seedOrClose(opened)?.let { return it }
            mutex.withLock {
                if (unlockManager.state.value !is UnlockState.Unlocked) {
                    // A lock landed while the file was opening: never expose
                    // a session whose key is already being zeroed.
                    opened.close()
                    BringUpResult.NotUnlocked
                } else {
                    sessionState.value = opened
                    provider.install(
                        VaultDocumentsProvider.Services(
                            repository = opened.repository,
                            exportService = opened.exportService,
                            unlockState = unlockManager.state,
                        ),
                    )
                    provider.notifyRootsChanged()
                    BringUpResult.Ready(opened)
                }
            }
        } catch (e: VaultOpenException) {
            BringUpResult.Failed(e.message ?: "vault open failed")
        } finally {
            inFlight = null
        }

    /** Runs [seed] on [opened]; on failure closes it and returns the [BringUpResult] to report, else `null`. */
    private suspend fun seedOrClose(opened: VaultSession): BringUpResult? =
        try {
            seed(opened)
            null
        } catch (t: Throwable) {
            runCatching { opened.close() }
            if (t is kotlin.coroutines.cancellation.CancellationException) throw t
            // Class name only: nothing key-derived, nothing from the vault.
            BringUpResult.Failed("vault could not be prepared: ${t.javaClass.simpleName}")
        }

    private inner class LockHandler : LockObserver {
        override val priority: LockObserverPriority = LockObserverPriority.LOW

        override suspend fun onLocking(
            epoch: Long,
            budgetMillis: Long,
        ) {
            mutex.withLock {
                val current = sessionState.value ?: return@withLock
                provider.install(null)
                provider.notifyRootsChanged()
                current.close()
                sessionState.value = null
            }
        }

        override fun onLocked(epoch: Long) {
            // Backstop after zeroization: nothing key-derived is touched here,
            // only the reference is dropped and the (idempotent) release retried.
            val leftover = sessionState.getAndUpdate { null } ?: return
            scope.launch { runCatching { leftover.close() } }
        }

        override fun onUnlocked(epoch: Long) = Unit
    }
}
