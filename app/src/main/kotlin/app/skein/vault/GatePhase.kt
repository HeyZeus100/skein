// skein-ank2 — the vault gate's routing, as a pure function so the gate
// logic `MainActivity` renders is unit-testable without Compose or
// Robolectric (`VaultGatePhaseTest`) and asserted on device by
// `FirstRunInstrumentedTest`.
//
// skein-1bx4 adds the gate's second decision, [gateOpenFailure]: what
// `MainActivity`'s `OpeningVault` does with a `BringUpResult`, including
// the log line a failed open leaves behind.

package app.skein.vault

import app.skein.core.model.SkeinLog
import app.skein.core.vault.session.UnlockState

/** What `MainActivity`'s vault gate shows. */
sealed class GatePhase {
    /** The vault is open: render the shell over [session]. */
    data class Open(
        val session: VaultSession,
    ) : GatePhase()

    /** Unlocked but not yet brought up: run `VaultBootstrap.bringUp`. */
    object Opening : GatePhase()

    /** A Layer-0 key was permanently invalidated; the rewrap UI is not wired yet. */
    object RecoveryRequired : GatePhase()

    /** `VaultKeyProvider.isInitialised()` has not answered yet: show nothing actionable. */
    object Probing : GatePhase()

    /** No key envelope exists: first-run `VaultSetupScreen`. */
    object Setup : GatePhase()

    /** An envelope exists (readable or not): `BiometricUnlockScreen`. */
    object Unlock : GatePhase()
}

/**
 * The gate, in priority order. [provisioned] is `VaultKeyProvider.isInitialised()`
 * once probed, `null` before; it is consulted only while the vault is
 * locked and no recovery is pending, so a corrupt envelope (which the
 * provider reports as initialised) lands on the unlock screen — the only
 * place a non-destructive message for it lives — and never on setup.
 */
fun gatePhase(
    session: VaultSession?,
    unlockState: UnlockState,
    recoveryRequired: Boolean,
    provisioned: Boolean?,
): GatePhase =
    when {
        session != null -> GatePhase.Open(session)
        unlockState is UnlockState.Unlocked -> GatePhase.Opening
        recoveryRequired -> GatePhase.RecoveryRequired
        provisioned == null -> GatePhase.Probing
        !provisioned -> GatePhase.Setup
        else -> GatePhase.Unlock
    }

/** `SkeinLog` tag for the gate's bring-up outcome. */
internal const val VAULT_GATE_TAG: String = "VaultGate"

/**
 * What `MainActivity`'s `OpeningVault` shows after a
 * [VaultBootstrap.bringUp]: the failure reason, or `null` when there is
 * nothing to report.
 *
 * skein-1bx4 — this exists to make sure a failed open is never again
 * invisible. The device that motivated it showed "The vault could not be
 * opened: …" on screen while logcat recorded nothing at all about the open,
 * which left the only evidence of a P0 in a screenshot of a phone.
 *
 * Logging the reason is safe by construction, and the two places that build
 * one keep it that way: [BringUpResult.Failed.reason] is either a
 * [VaultOpenException] message (fixed text, plus an exception class name
 * from `DeviceVaultOpener`, or one of `VaultLifecycle`'s key-material-free
 * `Failed` reasons) or `VaultBootstrap`'s own "vault could not be prepared:
 * <class name>". No vault content, no path, no key-derived text ever
 * reaches it — spec §9, and the reason is user-presentable in the first
 * place, which is a strictly stronger bar than loggable.
 *
 * [BringUpResult.NotUnlocked] is not a failure: a lock landing mid-open is
 * ordinary, and the gate re-routes to the unlock screen on its own.
 */
fun gateOpenFailure(result: BringUpResult): String? =
    when (result) {
        is BringUpResult.Ready, BringUpResult.NotUnlocked -> null
        is BringUpResult.Failed -> {
            SkeinLog.w(VAULT_GATE_TAG, "vault bring-up failed: ${result.reason}")
            result.reason
        }
    }
