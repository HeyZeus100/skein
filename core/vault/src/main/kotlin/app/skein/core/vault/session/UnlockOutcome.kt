// skein-pya (E3.I3a) — result variants surfaced by [UnlockManager] entry
// points. Deliberately distinct from `VaultKeyProvider.UnlockResult` /
// `RewrapResult` so the manager can layer its own outcomes (e.g.
// `AlreadyUnlocking` when concurrent unlocks race) without leaking the
// key-provider's shape at the UI seam.
//
// Messages carry no key material, per spec §7 and
// `docs/design/LOCK_POLICY_INDEXING.md` §5.1.

package app.skein.core.vault.session

import app.skein.core.model.AuthorizationToken
import app.skein.core.vault.key.RewrapResult
import app.skein.core.vault.key.UnlockResult
import app.skein.core.vault.key.VaultKeyProvider

/** Outcome of [UnlockManager.unlock]. */
public sealed class UnlockOutcome {
    public data class Success(
        public val token: AuthorizationToken,
    ) : UnlockOutcome()

    public object UserCancelled : UnlockOutcome()

    /**
     * Layer-0 alias reported `KeyPermanentlyInvalidatedException`. State is
     * left in [UnlockState.RecoveryRequired]; caller routes to
     * [UnlockManager.recoverAndRewrap] with the surviving factor.
     */
    public data class KeyPermanentlyInvalidated(
        public val factor: VaultKeyProvider.Factor,
    ) : UnlockOutcome()

    public object NotInitialised : UnlockOutcome()

    /**
     * skein-9psb: the Layer-0 alias reported that the device is currently
     * locked — `Cipher.init` raced the keyguard's own unlock signal (see
     * `VaultKeyProviderImpl.isDeviceLockedFailure` / `UnlockResult.DeviceLocked`).
     * NOT a failure: the caller should wait (e.g. for `ACTION_USER_PRESENT`
     * or the next lifecycle `RESUME`) and retry silently, with no failure
     * text shown.
     */
    public object DeviceLocked : UnlockOutcome()

    /**
     * Another `unlock()` call was already in flight; this call rejoined that
     * call's outcome. The returned [outcome] is the resolved result of the
     * first call, verbatim.
     */
    public data class Coalesced(
        public val outcome: UnlockOutcome,
    ) : UnlockOutcome()

    /** Not a valid transition — e.g. `unlock()` called while `Unlocked`. */
    public data class IllegalTransition(
        public val from: UnlockState,
    ) : UnlockOutcome()

    public data class Failed(
        public val reason: String,
    ) : UnlockOutcome()
}

/** Outcome of [UnlockManager.recoverAndRewrap]. */
public sealed class RecoveryOutcome {
    /**
     * Rewrap succeeded and the session transitioned directly to
     * [UnlockState.Unlocked] — the same crypto operation that produced the
     * fresh Layer-0 alias also produced the unwrapped master, so a second
     * `BiometricPrompt` is unnecessary. Token epoch is bumped once.
     */
    public data class Success(
        public val token: AuthorizationToken,
        public val newKeyVersion: Int,
    ) : RecoveryOutcome()

    public object UserCancelled : RecoveryOutcome()

    public object BothFactorsInvalidated : RecoveryOutcome()

    public data class Failed(
        public val reason: String,
    ) : RecoveryOutcome()

    /** Not a valid transition — e.g. `recoverAndRewrap()` called while `Unlocked`. */
    public data class IllegalTransition(
        public val from: UnlockState,
    ) : RecoveryOutcome()
}

// ---- internal mapping helpers used by UnlockManager -----------------------

internal fun UnlockResult.toOutcomeWithoutToken(): UnlockOutcome =
    when (this) {
        is UnlockResult.Success -> UnlockOutcome.Success(token)
        is UnlockResult.UserCancelled -> UnlockOutcome.UserCancelled
        is UnlockResult.KeyPermanentlyInvalidated -> UnlockOutcome.KeyPermanentlyInvalidated(factor)
        is UnlockResult.NotInitialised -> UnlockOutcome.NotInitialised
        is UnlockResult.DeviceLocked -> UnlockOutcome.DeviceLocked
        is UnlockResult.Failed -> UnlockOutcome.Failed(reason)
    }

internal fun RewrapResult.toOutcome(token: AuthorizationToken?): RecoveryOutcome =
    when (this) {
        is RewrapResult.Success ->
            if (token != null) {
                RecoveryOutcome.Success(token, newKeyVersion)
            } else {
                RecoveryOutcome.Failed(
                    "rewrap succeeded but no AuthorizationToken available",
                )
            }
        is RewrapResult.UserCancelled -> RecoveryOutcome.UserCancelled
        is RewrapResult.BothFactorsInvalidated -> RecoveryOutcome.BothFactorsInvalidated
        is RewrapResult.Failed -> RecoveryOutcome.Failed(reason)
    }
