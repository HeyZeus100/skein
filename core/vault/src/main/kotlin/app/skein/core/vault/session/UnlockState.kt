// skein-pya (E3.I3a) — public state variants for the [UnlockManager] state
// machine. Deliberately narrow: the state carries no key material, only the
// phase, the unlock timestamp (for the idle-timeout math), and the current
// [AuthorizationToken] where applicable. Any log emitted with an [UnlockState]
// as a payload is safe to persist — spec §7 and
// `docs/design/LOCK_POLICY_INDEXING.md` §5.1 ("no key material in state event
// payloads").
//
// The `Locked → Unlocking → Unlocked → Locking → Locked` cycle mirrors
// `LOCK_POLICY_INDEXING.md` §5.1's `SessionPhase` enum. `RecoveryRequired` is
// the terminal state entered when a Layer-0 alias reports
// `KeyPermanentlyInvalidatedException`; the caller escapes it only through
// [UnlockManager.recoverAndRewrap] (or [UnlockManager.lock] with a
// `RECOVERY_REQUIRED` reason, which drops back to `Locked`).

package app.skein.core.vault.session

import us.aherrera.skein.core.model.AuthorizationToken

/** One of the phases in the [UnlockManager] state machine. */
public sealed class UnlockState {
    /** Initial state, and every state after a successful [UnlockManager.lock]. */
    public object Locked : UnlockState()

    /**
     * A [UnlockManager.unlock] call is in flight — `BiometricPrompt` is on
     * screen or the crypto unwrap is running.
     */
    public object Unlocking : UnlockState()

    /**
     * Master key is in memory and the session is authorised. [since] is the
     * millisecond clock reading at which the session became active; idle-lock
     * math anchors on it plus every [UnlockManager.poke]. [token] is the
     * generation stamp downstream write paths compare against.
     */
    public data class Unlocked(
        public val since: Long,
        public val token: AuthorizationToken,
    ) : UnlockState()

    /**
     * The bounded flush-and-notify window between a lock request and the key
     * actually being zeroed. Observers are being called in priority order and
     * the key is still valid; a lock() call that arrives here is a no-op.
     */
    public data class Locking(
        public val reason: LockReason,
    ) : UnlockState()

    /**
     * A Layer-0 alias reported `KeyPermanentlyInvalidatedException`.
     * The only forward path is [UnlockManager.recoverAndRewrap]; the only
     * backward path is [UnlockManager.lock] with `LockReason.USER_REQUESTED`.
     */
    public object RecoveryRequired : UnlockState()
}
