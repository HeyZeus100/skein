// skein-ank2 — the vault gate's routing, as a pure function so the gate
// logic `MainActivity` renders is unit-testable without Compose or
// Robolectric (`VaultGatePhaseTest`) and asserted on device by
// `FirstRunInstrumentedTest`.

package app.skein.vault

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
