// skein-nxk (E4.I3): `IsolatedSessionGate`, LOCK_POLICY_INDEXING.md §5.3.
//
// The invariant these tests exist for is §6.1's I1/I6: a service that restarts
// comes back UNAUTHORIZED. If `:inference` is killed by LMK mid-session and
// rebound, it must refuse every request until `:app` explicitly re-authorizes
// the epoch — never implicitly trust whatever epoch a stale client still sends.
// A gate that defaulted to "authorized" would turn a process restart into a
// lock bypass.

package app.skein.inference.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import us.aherrera.skein.ipc.ErrorCode

class IsolatedSessionGateTest {
    @Test
    fun `a fresh gate admits nothing`() {
        val gate = IsolatedSessionGate()

        assertTrue(gate.guard(1L) is GateResult.Refuse)
    }

    @Test
    fun `a fresh gate refuses with SESSION_LOCKED`() {
        val gate = IsolatedSessionGate()

        assertEquals(ErrorCode.SESSION_LOCKED, (gate.guard(1L) as GateResult.Refuse).code)
    }

    @Test
    fun `a fresh gate refuses even the NONE epoch a stale client might send`() {
        val gate = IsolatedSessionGate()

        assertTrue(gate.guard(SessionEpoch.NONE) is GateResult.Refuse)
    }

    @Test
    fun `an unlocked epoch is admitted`() {
        val gate = IsolatedSessionGate()
        gate.onUnlocked(7L)

        assertEquals(GateResult.Admit, gate.guard(7L))
    }

    @Test
    fun `a different epoch is refused while another is authorized`() {
        val gate = IsolatedSessionGate()
        gate.onUnlocked(7L)

        assertTrue(gate.guard(8L) is GateResult.Refuse)
    }

    @Test
    fun `locking revokes authorization immediately`() {
        val gate = IsolatedSessionGate()
        gate.onUnlocked(7L)
        gate.onLocking(7L, budgetMillis = 500L)

        assertTrue(gate.guard(7L) is GateResult.Refuse)
    }

    @Test
    fun `locking signals cancellation for the locking epoch`() {
        val cancelled = mutableListOf<Long>()
        val gate = IsolatedSessionGate(onCancelRequests = { epoch -> cancelled += epoch })
        gate.onUnlocked(7L)
        gate.onLocking(7L, budgetMillis = 500L)

        assertEquals(listOf(7L), cancelled)
    }

    @Test
    fun `onLocked releases request state`() {
        var released = 0
        val gate = IsolatedSessionGate(onReleaseState = { released++ })
        gate.onUnlocked(7L)
        gate.onLocking(7L, 500L)
        gate.onLocked(7L)

        assertEquals(1, released)
    }

    @Test
    fun `onLocked is idempotent`() {
        var released = 0
        val gate = IsolatedSessionGate(onReleaseState = { released++ })
        gate.onUnlocked(7L)
        gate.onLocking(7L, 500L)
        gate.onLocked(7L)
        gate.onLocked(7L)

        assertEquals(1, released)
    }

    @Test
    fun `onLocked without a preceding onLocking still revokes and releases`() {
        // The LOCKING push is oneway and can be lost if the service was being
        // restarted; `onSessionLocked` is the hard backstop and must stand alone.
        var released = 0
        val gate = IsolatedSessionGate(onReleaseState = { released++ })
        gate.onUnlocked(7L)
        gate.onLocked(7L)

        assertTrue(gate.guard(7L) is GateResult.Refuse)
        assertEquals(1, released)
    }

    @Test
    fun `a later unlock re-authorizes after a lock`() {
        val gate = IsolatedSessionGate()
        gate.onUnlocked(7L)
        gate.onLocking(7L, 500L)
        gate.onLocked(7L)
        gate.onUnlocked(8L)

        assertEquals(GateResult.Admit, gate.guard(8L))
    }

    @Test
    fun `the old epoch stays refused after re-authorization`() {
        val gate = IsolatedSessionGate()
        gate.onUnlocked(7L)
        gate.onLocked(7L)
        gate.onUnlocked(8L)

        assertTrue(gate.guard(7L) is GateResult.Refuse)
    }

    @Test
    fun `a stale onLocked for an older epoch does not revoke the current one`() {
        val gate = IsolatedSessionGate()
        gate.onUnlocked(8L)
        gate.onLocked(7L)

        assertEquals(GateResult.Admit, gate.guard(8L))
    }

    @Test
    fun `a stale onLocking for an older epoch does not revoke the current one`() {
        val gate = IsolatedSessionGate()
        gate.onUnlocked(8L)
        gate.onLocking(7L, 500L)

        assertEquals(GateResult.Admit, gate.guard(8L))
    }
}
