// skein-nxk (E4.I3): `IsolatedSessionGate`, LOCK_POLICY_INDEXING.md §5.3.
//
// The invariant these tests exist for is §6.1's I1/I6: a service that restarts
// comes back UNAUTHORIZED. If `:inference` is killed by LMK mid-session and
// rebound, it must refuse every request until `:app` explicitly re-authorizes
// the epoch — never implicitly trust whatever epoch a stale client still sends.
// A gate that defaulted to "authorized" would turn a process restart into a
// lock bypass.

package app.skein.inference.service

import app.skein.ipc.ErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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

    // ------------------------- a push that is still unwinding (bd skein-gg11.8)
    //
    // The two lock pushes are `oneway`, so `:app` cannot be blocked by this
    // process while it tears a session down — which means "the gate is shut"
    // can never be something the CALLER waits for. It has to be true from the
    // first instruction of the handler instead, because everything after that
    // instruction takes real time: `onCancelRequests` reaches into a running
    // generation and `onReleaseState` frees a native context, zeroing the KV
    // cache on the way. LOCK_POLICY_INDEXING.md §4.1 states this as a
    // requirement ("visible to every subsequent call before `onSessionLocking`
    // even returns"); these two hold the handler open at exactly that point —
    // the callbacks run INSIDE it — and ask the gate what it would answer.

    @Test
    fun `onLocking has already revoked by the time cancellation runs`() {
        lateinit var gate: IsolatedSessionGate
        var duringCancel: GateResult? = null
        gate = IsolatedSessionGate(onCancelRequests = { duringCancel = gate.guard(7L) })
        gate.onUnlocked(7L)

        gate.onLocking(7L, 500L)

        assertTrue(duringCancel is GateResult.Refuse)
    }

    @Test
    fun `onLocked has already revoked by the time the release runs`() {
        lateinit var gate: IsolatedSessionGate
        var duringRelease: GateResult? = null
        gate = IsolatedSessionGate(onReleaseState = { duringRelease = gate.guard(7L) })
        gate.onUnlocked(7L)

        gate.onLocked(7L)

        assertTrue(duringRelease is GateResult.Refuse)
    }

    // ------------------------------- the unlock push is two-way (skein-gg11.8)
    //
    // `onSessionUnlocked` returns to `:app` only once [IsolatedSessionGate.onUnlocked]
    // has run, so `:app` re-sends it on every fresh bind (§5.3) knowing the
    // next request will be admitted. Re-sending the epoch that is already
    // authorized is therefore the COMMON case, not an edge one.

    @Test
    fun `unlocking twice with the same epoch is idempotent`() {
        val gate = IsolatedSessionGate()
        gate.onUnlocked(7L)
        gate.onUnlocked(7L)

        assertEquals(GateResult.Admit, gate.guard(7L))
    }

    @Test
    fun `unlocking twice does not resurrect an epoch a lock revoked in between`() {
        val gate = IsolatedSessionGate()
        gate.onUnlocked(7L)
        gate.onLocked(7L)
        gate.onUnlocked(8L)
        gate.onUnlocked(8L)

        assertTrue(gate.guard(7L) is GateResult.Refuse)
    }
}
