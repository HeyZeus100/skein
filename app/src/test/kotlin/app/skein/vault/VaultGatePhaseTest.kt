package app.skein.vault

import app.skein.core.vault.session.LockReason
import app.skein.core.vault.session.UnlockState
import app.skein.export.stage.FakeExportStageRepository
import org.junit.Assert.assertEquals
import org.junit.Test
import us.aherrera.skein.core.model.AuthorizationToken
import us.aherrera.skein.testing.FakeExportService
import us.aherrera.skein.testing.FakeImportService
import us.aherrera.skein.testing.InMemoryIndexStore
import us.aherrera.skein.testing.InMemoryPersonaService
import us.aherrera.skein.testing.InMemoryVaultRepository

/**
 * skein-ank2: the vault gate's routing, in isolation from Compose. The
 * three cases the bd acceptance criteria name — fresh install → setup,
 * second launch → unlock only, corrupt envelope → unlock (never setup) —
 * plus the priority order of the states above them.
 */
class VaultGatePhaseTest {
    private val session =
        VaultSession(
            repository = InMemoryVaultRepository(),
            indexStore = InMemoryIndexStore(),
            personaService = InMemoryPersonaService(),
            exportService = FakeExportService(),
            importService = FakeImportService(),
            exportStages = FakeExportStageRepository(),
        ) {}

    private val unlocked = UnlockState.Unlocked(since = 1L, token = AuthorizationToken(1L))

    @Test
    fun `a fresh install (no envelope) routes to setup`() {
        assertEquals(GatePhase.Setup, gatePhase(null, UnlockState.Locked, false, provisioned = false))
    }

    @Test
    fun `a provisioned device routes to unlock, never setup`() {
        assertEquals(GatePhase.Unlock, gatePhase(null, UnlockState.Locked, false, provisioned = true))
    }

    @Test
    fun `an unanswered probe routes nowhere actionable`() {
        assertEquals(GatePhase.Probing, gatePhase(null, UnlockState.Locked, false, provisioned = null))
    }

    @Test
    fun `an open session wins over everything`() {
        assertEquals(GatePhase.Open(session), gatePhase(session, UnlockState.Locked, true, provisioned = false))
    }

    @Test
    fun `unlocked without a session opens the vault`() {
        assertEquals(GatePhase.Opening, gatePhase(null, unlocked, false, provisioned = false))
    }

    @Test
    fun `recovery pending wins over the probe result`() {
        assertEquals(GatePhase.RecoveryRequired, gatePhase(null, UnlockState.Locked, true, provisioned = false))
    }

    @Test
    fun `a lock in flight on a provisioned device stays on unlock`() {
        val locking = UnlockState.Locking(LockReason.IDLE_TIMEOUT)

        assertEquals(GatePhase.Unlock, gatePhase(null, locking, false, provisioned = true))
    }
}
