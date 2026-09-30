package app.skein.vault

import app.skein.core.vault.key.VaultKeyProvider
import app.skein.core.vault.session.LockReason
import app.skein.core.vault.session.UnlockState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FactorRecoveryGateTest {
    @Test fun `known recovery prompt remains mounted while authentication runs`() {
        for (factor in VaultKeyProvider.Factor.entries) {
            assertTrue(isFactorRecoveryActive(UnlockState.RecoveryRequired, factor))
            assertTrue(isFactorRecoveryActive(UnlockState.Unlocking, factor))
            assertEquals(
                GatePhase.RecoveryRequired,
                gatePhase(
                    null,
                    UnlockState.Unlocking,
                    isFactorRecoveryActive(UnlockState.Unlocking, factor),
                    provisioned = true,
                ),
            )
        }
    }

    @Test fun `unknown invalidation is recovery guidance and never setup or automatic biometric`() {
        assertTrue(isFactorRecoveryActive(UnlockState.RecoveryRequired, null))
        assertEquals(
            GatePhase.RecoveryRequired,
            gatePhase(null, UnlockState.RecoveryRequired, false, provisioned = false),
        )
        assertFalse(isFactorRecoveryActive(UnlockState.Unlocking, null))
    }

    @Test fun `explicit lock does not inherit a stale UI recovery flag`() {
        assertFalse(isFactorRecoveryActive(UnlockState.Locked, null))
        assertFalse(
            isFactorRecoveryActive(UnlockState.Locking(LockReason.USER_REQUESTED), VaultKeyProvider.Factor.BIOMETRIC),
        )
        assertEquals(
            GatePhase.Unlock,
            gatePhase(
                null,
                UnlockState.Locked,
                isFactorRecoveryActive(UnlockState.Locked, null),
                provisioned = true,
            ),
        )
    }
}
