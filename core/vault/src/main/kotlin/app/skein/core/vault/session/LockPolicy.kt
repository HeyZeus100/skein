// skein-up0 (E3.I14) — user-configurable lock policy. Settings › Security
// may only ever tighten these values relative to the secure defaults below
// (`docs/design/LOCK_POLICY_INDEXING.md` §3, Handoff §3's "default
// screen-off locking" non-negotiable); it can never disable idle-lock or
// push the idle timeout past [MAX_IDLE_TIMEOUT]. [UnlockManager.configure]
// is the only writer of a live [LockPolicy] and enforces the clamp itself,
// so this ceiling holds regardless of what a caller (including a future,
// buggy Settings screen) supplies.

package app.skein.core.vault.session

import java.time.Duration

/**
 * The three lock-policy settings plan `E3.I14` / bead `skein-up0` calls for:
 * idle-timeout duration, whether the idle-timer's "leave it locked" default
 * should also fire on screen-off, and whether it should also fire the
 * instant Skein leaves the foreground.
 *
 * @param idleTimeout how long the vault may sit [UnlockState.Unlocked]
 *   without a [UnlockManager.poke] before [UnlockManager] locks it with
 *   [LockReason.IDLE_TIMEOUT]. Always clamped to
 *   `[MIN_IDLE_TIMEOUT, MAX_IDLE_TIMEOUT]` by [UnlockManager.configure].
 * @param lockOnScreenOff when true (the secure default), the device's
 *   screen turning off locks the vault immediately
 *   ([LockReason.SCREEN_OFF_POLICY]), independent of [idleTimeout] and
 *   independent of whether Skein itself is the foreground app.
 * @param lockOnBackground when true, Skein leaving the foreground (task
 *   switch, home button — `ProcessLifecycleOwner.onStop`, whether or not
 *   the screen itself turns off) locks the vault immediately
 *   ([LockReason.BACKGROUND_POLICY]). Off by default: this is a stricter,
 *   opt-in behaviour beyond the plan's secure baseline, not a required one.
 */
public data class LockPolicy(
    public val idleTimeout: Duration = DEFAULT_IDLE_TIMEOUT,
    public val lockOnScreenOff: Boolean = DEFAULT_LOCK_ON_SCREEN_OFF,
    public val lockOnBackground: Boolean = DEFAULT_LOCK_ON_BACKGROUND,
) {
    public companion object {
        /** Floor: idle-lock can be tightened, but never disabled outright. */
        public val MIN_IDLE_TIMEOUT: Duration = Duration.ofMinutes(1)

        /** Ceiling from `LOCK_POLICY_INDEXING.md` §3's allowed value set. */
        public val MAX_IDLE_TIMEOUT: Duration = Duration.ofMinutes(60)

        /** Secure default (`E3.I3`'s original ctor default, unchanged). */
        public val DEFAULT_IDLE_TIMEOUT: Duration = Duration.ofMinutes(5)

        public const val DEFAULT_LOCK_ON_SCREEN_OFF: Boolean = true
        public const val DEFAULT_LOCK_ON_BACKGROUND: Boolean = false

        /** The five values Settings › Security offers for idle timeout. */
        public val ALLOWED_IDLE_TIMEOUT_MINUTES: List<Long> = listOf(1L, 5L, 15L, 30L, 60L)

        public val DEFAULT: LockPolicy = LockPolicy()
    }
}
