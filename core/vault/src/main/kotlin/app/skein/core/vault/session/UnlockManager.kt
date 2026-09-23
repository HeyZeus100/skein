// skein-pya (E3.I3a) — [UnlockManager] state machine and idle-lock policy.
//
// This file owns the second half of the vault's lock/unlock contract, above
// `VaultKeyProvider` (`E3.I2`, `skein-3el`):
//  - a five-state machine (LOCKED / UNLOCKING / UNLOCKED / LOCKING /
//    RECOVERY_REQUIRED) implementing the phase model described in
//    `docs/design/LOCK_POLICY_INDEXING.md` §5.1 and spec §7;
//  - the notify-and-await bounded window on lock (`LOCK_POLICY_INDEXING.md`
//    §4.2, §5.1), sketched here with in-manager observer storage — a fuller
//    `SessionLockRegistry` lands in `E3.I3b` and will replace the storage
//    without changing the observer interface;
//  - idle-lock via a poke-driven activity timestamp and a caller-driven tick
//    entry point (the manager also spins a background poller when a
//    `CoroutineScope` is supplied; tests drive `pollIdleTimerForTest` directly
//    against a `TestScope` clock);
//  - shutdown-hook lock via `Runtime.addShutdownHook` (opt-in via ctor arg —
//    tests do NOT install it, to avoid polluting the JVM's hook list).
//
// Zeroization discipline: the master `ByteArray` is owned by `VaultKeyProvider`
// and this file NEVER touches it. What this file guarantees is the ORDER of
// operations on entry to `LOCKED`:
//    1. Transition state → `Locking`.
//    2. Notify the HIGH then LOW observers within a bounded budget
//       (`observerBudgetMillis`); on timeout, tag the reason as
//       `FORCE_TIMEOUT` but do not extend.
//    3. Notify the TEARDOWN tier — the vault connection close, the plan's
//       `E3.I3a` step (3) — under its own fresh window of the same budget,
//       whether or not step 2 acknowledged in time (skein-1bx4). The vault
//       is therefore always closed BEFORE the key is zeroed.
//    4. Call `keyProvider.lock()` — this is what zeros the key.
//    5. Only then transition state → `Locked` (or `RecoveryRequired` if the
//       lock was triggered by a `KeyPermanentlyInvalidated` async fault).
//    6. Fire `onLocked` on every observer (post-zero cleanup only).
// The unit tests assert this ordering by injecting a fake `VaultKeyProvider`
// that snapshots `state.value` at the moment `lock()` is invoked; the
// snapshot MUST be `Locking(_)` on every lock cycle, never `Locked` (which
// would mean the zeroize happened after the transition and any observer
// racing on `state == Locked` could have accessed a still-live key).
//
// Concurrency: an unlock in flight serialises against any concurrent unlock
// via a single `Mutex`. The second unlock call, on winning the mutex,
// observes `state == Unlocked` and returns `UnlockOutcome.Coalesced` — no
// second `keyProvider.unlock()` (and therefore no second biometric prompt)
// is issued. This is verified by `FakeVaultKeyProvider.authCallCount`.
//
// The public `unlock(...)` and `recoverAndRewrap(...)` surface takes
// `FragmentActivity` + `BiometricPrompt.PromptInfo`, which are Android types
// unavailable on the host JVM. Following the same pattern as
// `VaultKeyProviderImpl` (`skein-3el`), the orchestration is expressed in
// terms of a suspend factory that produces the underlying `UnlockResult` /
// `RewrapResult`; the public methods pass a factory that calls
// `VaultKeyProvider` with the real Android arguments, and the JVM unit tests
// exercise `unlockWith(factor, factory)` / `recoverAndRewrapWith(factory)`
// directly. This keeps the state-machine path unit-testable without
// Robolectric or an emulator.

package app.skein.core.vault.session

import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import app.skein.core.model.SkeinLog
import app.skein.core.vault.key.RewrapResult
import app.skein.core.vault.key.UnlockResult
import app.skein.core.vault.key.VaultKeyProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import us.aherrera.skein.core.model.AuthorizationToken
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * Coordinates the vault's lock/unlock lifecycle around [VaultKeyProvider].
 *
 * The manager is not itself a `CoroutineScope`: an optional [scope] arg is
 * used only for the background idle poller. Callers that never call
 * [poke]/[idleTimeout] can pass `scope = null` (the default) and drive the
 * idle tick from tests via [pollIdleTimerForTest].
 *
 * @param keyProvider the Layer-0/Layer-1 crypto owner. Not modified here.
 * @param clock source of "now" for idle-lock math and [UnlockState.Unlocked.since].
 * @param idleTimeout the initial value seeded into [policy] (`LockPolicy.idleTimeout`),
 *   NOT clamped to the plan's allowed range — this is an internal/test seam
 *   (production always passes the [LockPolicy.DEFAULT_IDLE_TIMEOUT] default;
 *   tests use short values for a fast idle-tick clock). After this much time
 *   without a [poke], and while `state == Unlocked`, the poller fires [lock]
 *   with [LockReason.IDLE_TIMEOUT]. Callers that need to change this live
 *   (`E3.I14`, Settings › Security) use [configure] instead, which DOES
 *   clamp — see its doc.
 * @param idleTickInterval how often the background poller wakes up. Only
 *   consulted when [scope] is non-null.
 * @param observerBudgetMillis the shared deadline the [LockObserver] notify
 *   pass runs under. Observers that miss it produce a
 *   [LockReason.FORCE_TIMEOUT]-tagged lock.
 * @param scope where the background idle poller runs. `null` disables the
 *   background poller entirely; callers can still drive [pollIdleTimerForTest].
 * @param installShutdownHook when true, register a `Runtime.addShutdownHook`
 *   that calls `lock(SESSION_ENDED)` on JVM shutdown. Off by default so unit
 *   tests do not pollute the JVM hook list.
 */
public class UnlockManager
    @JvmOverloads
    constructor(
        private val keyProvider: VaultKeyProvider,
        private val clock: Clock = Clock.systemUTC(),
        idleTimeout: Duration = LockPolicy.DEFAULT_IDLE_TIMEOUT,
        private val idleTickInterval: Duration = Duration.ofSeconds(30),
        private val observerBudgetMillis: Long = DEFAULT_OBSERVER_BUDGET_MILLIS,
        private val scope: CoroutineScope? = null,
        installShutdownHook: Boolean = false,
    ) {
        private val _state = MutableStateFlow<UnlockState>(UnlockState.Locked)
        public val state: StateFlow<UnlockState> = _state.asStateFlow()

        private val _authorizationToken = MutableStateFlow<AuthorizationToken?>(null)
        public val authorizationToken: StateFlow<AuthorizationToken?> = _authorizationToken.asStateFlow()

        // `E3.I14` (skein-up0) — live-updatable lock policy. Seeded verbatim
        // from the ctor's `idleTimeout` — the ctor is an internal/test seam
        // (existing JVM tests rely on sub-minute values for a fast idle-tick
        // clock), NOT the user-facing Settings path, so it is deliberately
        // NOT clamped here. [configure] IS the user-facing path (Settings ›
        // Security, via `SecurityPrefs`) and clamps every value it accepts —
        // that is where the plan's "never allow a value outside the allowed
        // range/ceiling" non-negotiable is actually enforced.
        private val _policy = MutableStateFlow(LockPolicy(idleTimeout = idleTimeout))
        public val policy: StateFlow<LockPolicy> = _policy.asStateFlow()

        // Serialises every state-transitioning entry point (`unlock`, `lock`,
        // `recoverAndRewrap`). Read-only access to `_state.value` outside the
        // mutex is intentionally unsynchronised — StateFlow itself is safe;
        // the mutex protects against interleaved *transitions*.
        private val transitionMutex = Mutex()

        // Independent, monotonic — bumped once per successful unlock or
        // successful rewrap, and once per lock. Used to mint the
        // `AuthorizationToken` we surface AFTER a rewrap (where
        // `VaultKeyProvider.rewrapAfterInvalidation` returns a `RewrapResult`
        // that does not carry a token of its own).
        private val recoveryEpoch = AtomicLong(0L)

        // Last successful poke() or unlock timestamp, in `clock.millis()`.
        // Read outside the mutex; volatile guarantees the read gets the
        // latest write on modern Android/JVMs.
        @Volatile
        private var lastActivityMillis: Long = 0L

        // Test-only observability for the `effectiveReason` computed on the
        // most recent lock cycle — see `lastEffectiveLockReasonForTest`.
        @Volatile
        private var lastEffectiveLockReason: LockReason? = null

        // Observers registered in this process. Copy-on-read snapshot when
        // notifying, so registrations arriving mid-notify are picked up on
        // the *next* lock cycle rather than racing with the current one.
        // Replaced by `E3.I3b`'s `SessionLockRegistry` (skein-<E3.I3b>).
        private val observersLock = Any()
        private val observers = mutableListOf<LockObserver>()

        // Background idle poller job, when [scope] is non-null. Stopped by
        // [close]; also stopped automatically when [scope] is cancelled.
        private val idleJob =
            scope?.launch {
                while (true) {
                    delay(idleTickInterval.toMillis())
                    tryIdleLock()
                }
            }

        private val shutdownHookThread: Thread? =
            if (installShutdownHook) {
                Thread { runBlocking { lockAndAwait(LockReason.SESSION_ENDED) } }
                    .also { Runtime.getRuntime().addShutdownHook(it) }
            } else {
                null
            }

        // ---- unlock ------------------------------------------------------

        /**
         * Attempts to transition to [UnlockState.Unlocked] via [factor].
         *
         * Concurrency: if another unlock is in flight when this call arrives,
         * this call blocks on [transitionMutex] until that call completes; if
         * the winner ended in `Unlocked`, this call returns
         * [UnlockOutcome.Coalesced] wrapping the winner's outcome — no second
         * biometric prompt is issued.
         */
        public suspend fun unlock(
            activity: FragmentActivity,
            prompt: BiometricPrompt.PromptInfo,
            factor: VaultKeyProvider.Factor,
        ): UnlockOutcome = unlockWith(factor) { keyProvider.unlock(activity, prompt, factor) }

        /**
         * Internal state-machine driver. The public [unlock] and JVM unit
         * tests both funnel through here — the tests supply an [auth] lambda
         * that fabricates an `UnlockResult` directly, bypassing the biometric
         * prompt.
         */
        internal suspend fun unlockWith(
            factor: VaultKeyProvider.Factor,
            auth: suspend () -> UnlockResult,
        ): UnlockOutcome =
            transitionMutex.withLock {
                val cur = _state.value
                when (cur) {
                    is UnlockState.Unlocked -> return@withLock UnlockOutcome.Coalesced(UnlockOutcome.Success(cur.token))
                    is UnlockState.Locking, is UnlockState.RecoveryRequired ->
                        return@withLock UnlockOutcome.IllegalTransition(cur)
                    is UnlockState.Unlocking -> {
                        // Cannot occur: we hold the transition mutex, and no
                        // other transition sets Unlocking without releasing.
                        // Treat as an illegal state to surface bugs early.
                        return@withLock UnlockOutcome.IllegalTransition(cur)
                    }
                    UnlockState.Locked -> Unit // fall through
                }
                _state.value = UnlockState.Unlocking
                val result: UnlockResult =
                    try {
                        auth()
                    } catch (ce: CancellationException) {
                        _state.value = UnlockState.Locked
                        throw ce
                    } catch (t: Throwable) {
                        _state.value = UnlockState.Locked
                        return@withLock UnlockOutcome.Failed(
                            "unlock threw ${t.javaClass.simpleName}",
                        )
                    }
                when (result) {
                    is UnlockResult.Success -> {
                        val now = clock.millis()
                        lastActivityMillis = now
                        _authorizationToken.value = result.token
                        _state.value = UnlockState.Unlocked(now, result.token)
                        notifyOnUnlocked(result.token.epoch)
                        UnlockOutcome.Success(result.token)
                    }
                    is UnlockResult.KeyPermanentlyInvalidated -> {
                        _state.value = UnlockState.RecoveryRequired
                        UnlockOutcome.KeyPermanentlyInvalidated(result.factor)
                    }
                    UnlockResult.UserCancelled -> {
                        _state.value = UnlockState.Locked
                        UnlockOutcome.UserCancelled
                    }
                    UnlockResult.NotInitialised -> {
                        _state.value = UnlockState.Locked
                        UnlockOutcome.NotInitialised
                    }
                    is UnlockResult.Failed -> {
                        _state.value = UnlockState.Locked
                        UnlockOutcome.Failed(result.reason)
                    }
                }
            }

        // ---- lock --------------------------------------------------------

        /**
         * Requests a lock. Non-suspending: the actual lock sequence runs on
         * the manager's [scope] when supplied, otherwise inline via
         * `runBlocking` (which is only expected on shutdown-hook paths and in
         * tests that construct the manager without a scope).
         *
         * Tests should prefer [lockAndAwait] for deterministic ordering.
         */
        public fun lock(reason: LockReason) {
            val s = scope
            if (s != null) {
                s.launch { lockAndAwait(reason) }
            } else {
                runBlocking { lockAndAwait(reason) }
            }
        }

        /**
         * Suspending form of [lock]. Public for callers already inside a
         * `suspend` context (e.g. shutdown-hook wrappers, integration tests).
         */
        public suspend fun lockAndAwait(reason: LockReason) {
            transitionMutex.withLock { doLockLocked(reason) }
        }

        private suspend fun doLockLocked(reason: LockReason) {
            val cur = _state.value
            when (cur) {
                is UnlockState.Locked, is UnlockState.Locking -> return
                is UnlockState.RecoveryRequired -> {
                    // Recovery cancelled: no key in memory to zero, no
                    // observers to notify (they saw `onLocked` when we
                    // originally entered RECOVERY_REQUIRED, if applicable).
                    _authorizationToken.value = null
                    _state.value = UnlockState.Locked
                    return
                }
                is UnlockState.Unlocked, is UnlockState.Unlocking -> Unit
            }
            _state.value = UnlockState.Locking(reason)
            val epoch = _authorizationToken.value?.epoch ?: recoveryEpoch.get()

            val allAcked = notifyOnLocking(epoch, observerBudgetMillis)
            // skein-1bx4 — the plan's step (3), `VaultManager.close()`, runs
            // after the notify-and-await window regardless of how that window
            // ended ("elapsed OR all acknowledged") and still BEFORE the key
            // is zeroed. It gets its own fresh window of the same budget: a
            // `HIGH` observer burning the shared one must not be able to skip
            // the vault close onto `onLocked`'s post-zeroization backstop,
            // and a wedged close must not be able to hold the key alive.
            val torndown = notifyTeardown(epoch, observerBudgetMillis)
            val effectiveReason = if (allAcked && torndown) reason else LockReason.FORCE_TIMEOUT
            lastEffectiveLockReason = effectiveReason

            // ORDER MATTERS — the key MUST be zeroed BEFORE state → Locked
            // so no observer racing on state.value == Locked can access a
            // still-live key. `FakeVaultKeyProvider` captures state.value
            // at this line and the test asserts it is Locking.
            keyProvider.lock()

            _authorizationToken.value = null
            _state.value =
                if (reason == LockReason.KEY_INVALIDATED) {
                    UnlockState.RecoveryRequired
                } else {
                    UnlockState.Locked
                }
            notifyOnLocked(epoch, effectiveReason)
        }

        // ---- recovery ----------------------------------------------------

        /**
         * Attempts to rewrap the master under a fresh Layer-0 alias for the
         * factor that reported `KeyPermanentlyInvalidatedException`.
         *
         * On success, the state transitions RECOVERY_REQUIRED → UNLOCKED
         * without a second biometric prompt — the rewrap operation itself
         * consumed the surviving factor's authentication. A fresh
         * [AuthorizationToken] is minted from the manager's internal epoch
         * counter (rewrap does not itself surface one via `RewrapResult`).
         */
        public suspend fun recoverAndRewrap(
            activity: FragmentActivity,
            prompt: BiometricPrompt.PromptInfo,
            survivingFactor: VaultKeyProvider.Factor,
        ): RecoveryOutcome =
            recoverAndRewrapWith {
                keyProvider.rewrapAfterInvalidation(activity, prompt, survivingFactor)
            }

        internal suspend fun recoverAndRewrapWith(rewrap: suspend () -> RewrapResult): RecoveryOutcome =
            transitionMutex.withLock {
                val cur = _state.value
                if (cur !is UnlockState.RecoveryRequired) {
                    return@withLock RecoveryOutcome.IllegalTransition(cur)
                }
                _state.value = UnlockState.Unlocking
                val result: RewrapResult =
                    try {
                        rewrap()
                    } catch (ce: CancellationException) {
                        _state.value = UnlockState.RecoveryRequired
                        throw ce
                    } catch (t: Throwable) {
                        _state.value = UnlockState.RecoveryRequired
                        return@withLock RecoveryOutcome.Failed(
                            "rewrap threw ${t.javaClass.simpleName}",
                        )
                    }
                when (result) {
                    is RewrapResult.Success -> {
                        // Invariant (skein-22su): `rewrapAfterInvalidation`
                        // succeeding MUST leave the master key accessible via
                        // `currentKey()` — see `VaultKeyProvider.rewrapAfterInvalidation`'s
                        // contract. Verify before advancing to `Unlocked` so a
                        // contract violation surfaces immediately instead of as
                        // a downstream null-key failure on the next vault op.
                        // The exception message deliberately carries no key
                        // material.
                        check(keyProvider.currentKey() != null) {
                            "rewrapAfterInvalidation succeeded but currentKey is null; " +
                                "VaultKeyProvider contract violated"
                        }
                        val now = clock.millis()
                        lastActivityMillis = now
                        val minted = AuthorizationToken(recoveryEpoch.incrementAndGet())
                        _authorizationToken.value = minted
                        _state.value = UnlockState.Unlocked(now, minted)
                        notifyOnUnlocked(minted.epoch)
                        RecoveryOutcome.Success(minted, result.newKeyVersion)
                    }
                    RewrapResult.UserCancelled -> {
                        _state.value = UnlockState.Locked
                        _authorizationToken.value = null
                        RecoveryOutcome.UserCancelled
                    }
                    RewrapResult.BothFactorsInvalidated -> {
                        // Unrecoverable — drop the manager into `Locked` so
                        // the caller can retry setup or surface the
                        // catastrophic-loss UI. `LOCK_POLICY_INDEXING.md`
                        // §3.8 residual-risk.
                        _state.value = UnlockState.Locked
                        _authorizationToken.value = null
                        RecoveryOutcome.BothFactorsInvalidated
                    }
                    is RewrapResult.Failed -> {
                        _state.value = UnlockState.RecoveryRequired
                        RecoveryOutcome.Failed(result.reason)
                    }
                }
            }

        // ---- idle-lock ---------------------------------------------------

        /**
         * Records user activity. No-op when the state is not [UnlockState.Unlocked]
         * (in every other state either the timer is not running, or a lock is
         * in flight and cannot be extended).
         */
        public fun poke() {
            if (_state.value is UnlockState.Unlocked) {
                lastActivityMillis = clock.millis()
            }
        }

        /**
         * Test-only entry point: runs one iteration of the idle-timer poll
         * against the injected [clock] and requests a lock if the deadline
         * has expired. Production code drives this from the background job
         * spawned on [scope].
         */
        internal suspend fun pollIdleTimerForTest() {
            tryIdleLock()
        }

        private suspend fun tryIdleLock() {
            val cur = _state.value
            if (cur !is UnlockState.Unlocked) return
            val elapsed = clock.millis() - lastActivityMillis
            if (elapsed > _policy.value.idleTimeout.toMillis()) {
                lockAndAwait(LockReason.IDLE_TIMEOUT)
            }
        }

        // ---- lock policy (E3.I14 / skein-up0) -----------------------------

        /**
         * Applies a new [LockPolicy] live — the idle-timeout half takes
         * effect on the very next idle-timer tick (or the next
         * [pollIdleTimerForTest] in tests), no restart or re-unlock needed.
         * [LockPolicy.idleTimeout] is always clamped to
         * `[LockPolicy.MIN_IDLE_TIMEOUT, LockPolicy.MAX_IDLE_TIMEOUT]` —
         * callers (Settings › Security, via `SecurityPrefs`) can only ever
         * tighten the effective policy relative to the plan's ceiling, never
         * weaken it, regardless of what value they pass in.
         *
         * [LockPolicy.lockOnScreenOff]/[LockPolicy.lockOnBackground] are read
         * by the `:app`-side screen-off/`ProcessLifecycleOwner` hooks
         * (`app.skein.vault.LockPolicyObserver`) at the moment each trigger
         * fires; this method only stores the value they read.
         */
        public fun configure(newPolicy: LockPolicy) {
            _policy.value = newPolicy.copy(idleTimeout = clampIdleTimeout(newPolicy.idleTimeout))
        }

        private fun clampIdleTimeout(duration: Duration): Duration =
            when {
                duration < LockPolicy.MIN_IDLE_TIMEOUT -> LockPolicy.MIN_IDLE_TIMEOUT
                duration > LockPolicy.MAX_IDLE_TIMEOUT -> LockPolicy.MAX_IDLE_TIMEOUT
                else -> duration
            }

        // ---- observers ---------------------------------------------------

        /**
         * Registers a [LockObserver]. Registrations arriving during a lock
         * sequence are picked up on the *next* cycle rather than joining an
         * in-flight notification. Returns a [DisposableHandle]; call
         * `dispose()` to unregister.
         *
         * Registry lives in-manager for now. `E3.I3b` will replace this with
         * `SessionLockRegistry` while preserving the [LockObserver] shape.
         */
        public fun addLockObserver(observer: LockObserver): DisposableHandle {
            synchronized(observersLock) { observers.add(observer) }
            return DisposableHandle {
                synchronized(observersLock) { observers.remove(observer) }
            }
        }

        private fun snapshotObservers(): List<LockObserver> = synchronized(observersLock) { observers.toList() }

        /**
         * Runs the notify-and-await pass under a single shared deadline. HIGH
         * priority runs before LOW, per `LOCK_POLICY_INDEXING.md` §5.1.
         *
         * skein-1bx4: [LockObserverPriority.TEARDOWN] is deliberately NOT
         * part of this pass — see [notifyTeardown].
         *
         * skein-va7y: a throwing `onLocking` used to propagate straight out
         * of this function, which left `doLockLocked` BEFORE
         * `keyProvider.lock()` and wedged `_state` in `Locking` forever (a
         * later `lock()` no-ops on `is Locking`, `unlock()` refuses with
         * `IllegalTransition`) — and crashed the process on the production
         * scope, which installs no `CoroutineExceptionHandler`. Each
         * observer now runs inside its own [notifyTier] catch, so a throw
         * from one observer can never stop [doLockLocked] from reaching
         * `keyProvider.lock()`, and never stops any other observer — same
         * tier or the next — from being notified.
         *
         * Returns `true` only if every observer returned within the budget
         * AND none of them threw; `false` in either case, and the caller
         * (deliberately, per the fix above) tags the outcome as
         * [LockReason.FORCE_TIMEOUT] just as it would a real timeout — from
         * the caller's side a throwing observer and a timed-out one look
         * identical: the budget was not cleanly honoured.
         */
        private suspend fun notifyOnLocking(
            epoch: Long,
            budgetMillis: Long,
        ): Boolean {
            val snapshot = snapshotObservers()
            if (snapshot.isEmpty()) return true
            val high = snapshot.filter { it.priority == LockObserverPriority.HIGH }
            val low = snapshot.filter { it.priority == LockObserverPriority.LOW }
            var allOk = true
            val outcome =
                withTimeoutOrNull(budgetMillis) {
                    if (!notifyTier(high, epoch, budgetMillis)) allOk = false
                    if (!notifyTier(low, epoch, budgetMillis)) allOk = false
                }
            return outcome != null && allOk
        }

        /**
         * skein-1bx4 — the vault-close tier, run after [notifyOnLocking] has
         * finished (however it finished) and before `keyProvider.lock()`.
         *
         * Its own `withTimeoutOrNull` window, not a share of
         * [notifyOnLocking]'s, for the two reasons
         * [LockObserverPriority.TEARDOWN] documents: a `HIGH`/`LOW` observer
         * that exhausts the shared budget used to cancel the enclosing
         * timeout and take the vault close down with it — leaving the vault
         * open across `keyProvider.lock()` and the connections to be closed
         * later, off the lock path, by `onLocked`'s backstop — and a close
         * that never returns must still not be able to keep the master key
         * alive. The budget VALUE is unchanged: this is the same
         * [observerBudgetMillis], granted a second time to a tier that the
         * plan places outside the notify-and-await window entirely.
         *
         * Returns `true` only if the tier is empty, or every observer in it
         * returned within the window without throwing.
         */
        private suspend fun notifyTeardown(
            epoch: Long,
            budgetMillis: Long,
        ): Boolean {
            val tier = snapshotObservers().filter { it.priority == LockObserverPriority.TEARDOWN }
            if (tier.isEmpty()) return true
            var allOk = true
            val outcome =
                withTimeoutOrNull(budgetMillis) {
                    if (!notifyTier(tier, epoch, budgetMillis)) allOk = false
                }
            return outcome != null && allOk
        }

        /**
         * Notifies one [LockObserverPriority] tier concurrently. Each
         * observer's `onLocking` is isolated in its own [runCatching]: a
         * throwing observer is caught, logged (its class name only — never
         * the exception message, which could embed caller-supplied content;
         * spec §9) and counted as a per-observer failure, but it never
         * cancels its siblings in this tier and never stops the caller from
         * moving on to the next tier or to `keyProvider.lock()`.
         *
         * `CancellationException` (notably [kotlinx.coroutines.TimeoutCancellationException]
         * from the enclosing [withTimeoutOrNull] expiring) is deliberately
         * NOT swallowed here — it is rethrown so structured concurrency and
         * the shared budget deadline keep cooperating exactly as before this
         * fix.
         */
        private suspend fun notifyTier(
            tier: List<LockObserver>,
            epoch: Long,
            budgetMillis: Long,
        ): Boolean =
            if (tier.isEmpty()) {
                true
            } else {
                coroutineScope {
                    tier
                        .map { observer ->
                            async {
                                runCatching { observer.onLocking(epoch, budgetMillis) }
                                    .onFailure { t ->
                                        if (t is CancellationException) throw t
                                        SkeinLog.w(
                                            TAG,
                                            "onLocking observer threw ${t.javaClass.simpleName}; " +
                                                "proceeding to keyProvider.lock() regardless",
                                        )
                                    }.isSuccess
                            }
                        }.awaitAll()
                        .all { it }
                }
            }

        private fun notifyOnLocked(
            epoch: Long,
            @Suppress("UNUSED_PARAMETER") reason: LockReason,
        ) {
            // Post-zero cleanup. Runs synchronously — observers must be fast
            // and MUST NOT touch key-derived state.
            snapshotObservers().forEach { runCatching { it.onLocked(epoch) } }
        }

        private fun notifyOnUnlocked(epoch: Long) {
            snapshotObservers().forEach { runCatching { it.onUnlocked(epoch) } }
        }

        // ---- teardown ----------------------------------------------------

        /**
         * Stops the background idle poller and removes the shutdown hook, if
         * either was installed. Does NOT itself request a lock — callers that
         * want a lock at teardown should call `lock(SESSION_ENDED)` first.
         */
        public fun close() {
            idleJob?.cancel()
            val hook = shutdownHookThread
            if (hook != null) {
                runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
            }
        }

        // ---- test-only accessors ----------------------------------------

        internal fun lastActivityForTest(): Long = lastActivityMillis

        internal fun observerCountForTest(): Int = synchronized(observersLock) { observers.size }

        /**
         * The `effectiveReason` computed on the most recent [doLockLocked]
         * pass (`null` before any lock cycle has run). skein-va7y: a
         * throwing observer is treated exactly like a timed-out one, so this
         * is `LockReason.FORCE_TIMEOUT` in both cases — tests assert on this
         * to confirm the two are indistinguishable from the caller's side.
         */
        internal fun lastEffectiveLockReasonForTest(): LockReason? = lastEffectiveLockReason

        public companion object {
            public const val DEFAULT_OBSERVER_BUDGET_MILLIS: Long = 500L
            private const val TAG: String = "UnlockManager"
        }
    }
