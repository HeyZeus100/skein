// skein-pya (E3.I3a) — minimal sketch of the [LockObserver] +
// [LockObserverPriority] interfaces that will be fully implemented,
// alongside a `SessionLockRegistry`, in skein-<E3.I3b>. `UnlockManager`
// owns the observer list here so its lock() sequence can drive the
// bounded notify-and-await window described in
// `docs/design/LOCK_POLICY_INDEXING.md` §5.1 without a hard dependency
// on the yet-unimplemented registry.
//
// E3.I3b will replace the in-manager list with an external registry that
// implements the same call ordering (HIGH first, then LOW, bounded by a
// shared budget). The interface's method shapes here are exactly the
// shapes LOCK_POLICY_INDEXING.md §5.1 specifies, so the E3.I3b swap is a
// structural refactor rather than an API redesign.

package app.skein.core.vault.session

/**
 * A component in `:app` (or an isolated service's proxy in `:app`) that owns
 * state which must be flushed or cleared as part of the lock sequence.
 *
 * Contract per `LOCK_POLICY_INDEXING.md` §5.1:
 *  - [onLocking] MAY use the still-live master key (e.g. to flush a pending
 *    encrypted write). It MUST respect [budgetMillis] as a hard deadline —
 *    UnlockManager runs the whole notify-and-await under a single
 *    `withTimeoutOrNull(budgetMillis)`, and a slow observer will not block
 *    others past that budget.
 *  - [onLocked] fires AFTER the key is zeroed and the vault connection is
 *    closed. It MUST NOT touch any key-derived state; pure in-memory cleanup
 *    only.
 *  - [onUnlocked] fires after a successful unlock; observers that lazily
 *    materialise per-session caches populate them here.
 */
public interface LockObserver {
    /** Ordering: `HIGH` observers run before `LOW` in the notify-and-await pass. */
    public val priority: LockObserverPriority

    /**
     * Called on entry to [UnlockState.Locking]. Master key is still live.
     * MUST return (or throw a `CancellationException`) within [budgetMillis].
     */
    public suspend fun onLocking(
        epoch: Long,
        budgetMillis: Long,
    )

    /** Called after the key is zeroed and the state has advanced to [UnlockState.Locked]. */
    public fun onLocked(epoch: Long)

    /** Called after a successful transition to [UnlockState.Unlocked]. */
    public fun onUnlocked(epoch: Long)
}

/**
 * Two-tier ordering: flush-needing observers (e.g. editor autosave) run first
 * so they can consume the live key; pure-clear observers run after so their
 * work is not blocked by a slow flush.
 */
public enum class LockObserverPriority {
    /**
     * Runs first in the notify-and-await pass. Isolated-service proxies
     * (`:inference`, `:embedder`) live here so their `onSessionLocking` AIDL
     * pushes go out before the editor flush ties up the remaining budget.
     */
    HIGH,

    /**
     * Runs after all `HIGH` observers have returned. Editor autosave hooks
     * (`E7.I4`) live here; they need to see any earlier observer's writes
     * before deciding whether to short-circuit to a recovery draft.
     */
    LOW,
}
