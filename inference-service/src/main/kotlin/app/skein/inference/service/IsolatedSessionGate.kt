// skein-nxk (E4.I3): `IsolatedSessionGate`, the service-side half of
// LOCK_POLICY_INDEXING.md §5.3, implemented to the shape that document gives
// verbatim.
//
// WHY A COLD START IS UNAUTHORIZED. `authorizedEpoch` starts at
// [SessionEpoch.NONE] and nothing but an explicit unlock push can change that.
// If `:inference` is killed (LMK, crash) and rebound, it comes back refusing
// everything — it must never implicitly trust whatever epoch a client still
// holds, because "the service restarted" and "the vault locked" look identical
// from the client's side. `:app` re-sends the unlock on every fresh bind, so a
// legitimately-unlocked session recovers in one call; a service rebound after a
// lock does not. This is §6.1 invariants I1 and I6.
//
// WHY THE EPOCH AS WELL AS THE PUSH. The push (`onSessionLocking`) tells the
// service to stop admitting new work. It cannot help with calls already sitting
// on Binder's thread pool when it arrives — Binder delivers concurrently — so
// every request Parcelable also carries `sessionEpoch` and [guard] is the first
// statement of every entry point that touches plaintext. Belt and suspenders,
// closing a race the push alone cannot.
//
// WHY EVERY HANDLER FLIPS THE FLAG FIRST (bd skein-gg11.8). LOCK_POLICY_INDEXING
// §4.1 is normative about this: the revocation "happens on the Binder thread
// handling `onSessionLocking` and is visible to every subsequent call before
// `onSessionLocking` even returns". [onLocking] and [onLocked] therefore write
// `authorizedEpoch` BEFORE they cancel anything or free anything: the callbacks
// they then invoke can take real time (a native context free zeroes the KV
// cache first), and for the whole of that window the gate must already be shut.
// Reordering those two statements would admit a `generate` that arrived while a
// lock push was still unwinding. `IsolatedSessionGateTest`'s "has already
// revoked by the time …" pair asks the gate what it answers from INSIDE each
// callback; `InferenceEngineStateTest`'s "a generate arriving while a lock push
// is still unwinding is refused" parks the release inside `freeContextSecure`
// on a second thread and issues a real `generate` against it. Both fail if the
// two statements swap, so the ordering cannot be lost silently.
//
// `onSessionUnlocked` is the one push that is NOT `oneway` (skein-gg11.8; see
// the note above the three methods in `IInferenceService.aidl`): a two-way
// transaction returns to `:app` only after [onUnlocked] has run, so the caller's
// next `load`/`generate` cannot be dispatched ahead of the push that authorizes
// it. Nothing here blocks, which is what makes that round trip cheap enough to
// be worth making: [onUnlocked] is two atomic writes.

package app.skein.inference.service

import app.skein.ipc.ErrorCode
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Sentinel epochs. Mirrors `app.skein.core.vault.session`'s value without depending on it. */
object SessionEpoch {
    /** "No session is authorized." The cold-start value. */
    const val NONE: Long = 0L
}

/** What [IsolatedSessionGate.guard] decided. */
sealed interface GateResult {
    data object Admit : GateResult

    data class Refuse(
        val code: Int,
    ) : GateResult
}

/**
 * @param onCancelRequests invoked from `onLocking` with the epoch being locked,
 *   to signal cancellation to any in-flight request. Must not block: the caller
 *   is a `oneway` Binder thread and LOCKING has a budget.
 * @param onReleaseState invoked from `onLocked`, the hard backstop: free
 *   everything still holding request state, with no further grace period.
 */
class IsolatedSessionGate(
    private val onCancelRequests: (Long) -> Unit = {},
    private val onReleaseState: () -> Unit = {},
) {
    private val authorizedEpoch = AtomicLong(SessionEpoch.NONE)
    private val released = AtomicBoolean(false)

    /** The epoch currently admitted, or [SessionEpoch.NONE]. */
    val authorized: Long get() = authorizedEpoch.get()

    /**
     * First statement of every AIDL entry point that touches plaintext.
     *
     * [SessionEpoch.NONE] never matches, even against an unauthorized gate:
     * "not authorized" and "authorized for the null session" must not collapse
     * into the same admit.
     */
    fun guard(requestEpoch: Long): GateResult =
        if (requestEpoch != SessionEpoch.NONE && requestEpoch == authorizedEpoch.get()) {
            GateResult.Admit
        } else {
            GateResult.Refuse(ErrorCode.SESSION_LOCKED)
        }

    /**
     * `:app` authorized [epoch]; sent on unlock and again on every fresh bind.
     *
     * Reached from a TWO-WAY binder transaction (skein-gg11.8), so it must stay
     * as short as it is: the caller is blocked until it returns, and what the
     * caller is buying with that round trip is the guarantee that its next
     * request will be admitted. Idempotent — re-sending the same epoch (the
     * fresh-bind re-send of §5.3) authorizes the same session again.
     */
    fun onUnlocked(epoch: Long) {
        released.set(false)
        authorizedEpoch.set(epoch)
    }

    /**
     * SessionState entered LOCKING. Revokes authorization and starts
     * cancellation. Synchronous and fast, per §5.2.
     *
     * A push naming an epoch that is not the authorized one is stale — the
     * session already moved on — and is ignored rather than revoking the
     * current one. (`onSessionUnlocked` is two-way and the lock pushes are
     * `oneway`, so a newer unlock really can overtake an older queued lock;
     * this check is what makes that harmless.)
     *
     * Revocation first, cancellation second — see this file's header.
     */
    fun onLocking(
        epoch: Long,
        budgetMillis: Long,
    ) {
        if (!appliesTo(epoch)) return
        authorizedEpoch.set(SessionEpoch.NONE)
        onCancelRequests(epoch)
    }

    /**
     * LOCKING's budget elapsed (or everything acknowledged). Unconditionally
     * frees remaining request state. Idempotent, and valid even if the
     * `onLocking` push was lost — that push is `oneway`.
     *
     * Revocation first, release second — see this file's header. The release
     * is the slow half (it frees a native context, zeroing the KV cache on the
     * way), and the gate is already shut for all of it.
     */
    fun onLocked(epoch: Long) {
        if (!appliesTo(epoch)) return
        authorizedEpoch.set(SessionEpoch.NONE)
        if (released.compareAndSet(false, true)) onReleaseState()
    }

    /**
     * True when a lock push for [epoch] concerns the current session: either it
     * names the authorized epoch, or authorization has already been revoked (so
     * the push is the second half of a lock we already began).
     */
    private fun appliesTo(epoch: Long): Boolean {
        val current = authorizedEpoch.get()
        return current == SessionEpoch.NONE || current == epoch
    }
}
