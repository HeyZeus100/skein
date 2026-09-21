// skein-pya (E3.I3a) — why a lock is being requested. Communicated to
// [LockObserver]s and rendered in the state event stream. Kept as a small
// closed enum so callers can `when` over it exhaustively; new reasons are
// additive and require plan-doc discussion (spec §7).

package app.skein.core.vault.session

/** Cause of a lock transition, threaded through observers and event payloads. */
public enum class LockReason {
    /** The user tapped "Lock now" or otherwise explicitly demanded a lock. */
    USER_REQUESTED,

    /**
     * The idle timer expired: `now - lastPoke > idleTimeout` and the state was
     * [UnlockState.Unlocked]. Configured via [UnlockManager]'s `idleTimeout`
     * ctor arg; the tick cadence is separate (see [UnlockManager.idleTickInterval]).
     */
    IDLE_TIMEOUT,

    /**
     * The device screen turned off (`Intent.ACTION_SCREEN_OFF`) while
     * [LockPolicy.lockOnScreenOff] was enabled (the secure default; plan
     * `E3.I14`, `LOCK_POLICY_INDEXING.md` §4.5). Fires regardless of
     * whether Skein is currently the foreground app — see
     * [BACKGROUND_POLICY] for the separate "left foreground" signal.
     */
    SCREEN_OFF_POLICY,

    /**
     * `ProcessLifecycleOwner` reported Skein leaving the foreground
     * (`Lifecycle.Event.ON_STOP` — task switch, home button, screen off)
     * while [LockPolicy.lockOnBackground] was enabled. Off by default;
     * this is a stricter opt-in on top of [SCREEN_OFF_POLICY], not a
     * replacement for it (screen-off with the screen still resting on the
     * lock screen fires [SCREEN_OFF_POLICY] independently of this).
     */
    BACKGROUND_POLICY,

    /**
     * The process is shutting down. Wired to `Runtime.addShutdownHook` in
     * production; the observer budget still applies, but any observer that
     * misses it goes down with the JVM regardless.
     */
    SESSION_ENDED,

    /**
     * A `KeyPermanentlyInvalidatedException` arrived on an async operation
     * after the unlock — see `ATTACHMENT_ENCRYPTION.md` §3.5. The subsequent
     * state is [UnlockState.RecoveryRequired] rather than [UnlockState.Locked].
     */
    KEY_INVALIDATED,

    /**
     * Locked because the caller left [UnlockState.RecoveryRequired] via
     * cancel rather than [UnlockManager.recoverAndRewrap]. The subsequent
     * state is [UnlockState.Locked].
     */
    RECOVERY_CANCELLED,

    /** A background/error path forced a lock; observers may not have completed. */
    ERROR,

    /**
     * The lock proceeded despite one or more observers failing to acknowledge
     * within the deadline (`LOCK_POLICY_INDEXING.md` §4.2 hard-backstop). The
     * key was still zeroed on the ordered schedule; the "force" here refers
     * to observer wait, not to any relaxation of the zeroization contract.
     */
    FORCE_TIMEOUT,
}
