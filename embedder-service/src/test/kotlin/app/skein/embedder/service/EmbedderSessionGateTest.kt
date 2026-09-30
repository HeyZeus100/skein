package app.skein.embedder.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbedderSessionGateTest {
    @Test
    fun `cold and zero epochs never authorize work`() {
        val gate = EmbedderSessionGate()
        assertFalse(gate.admits(0))
        assertFalse(gate.admits(1))
        assertFalse(gate.authorize(0))
        assertFalse(gate.authorize(-1))
    }

    @Test
    fun `revocation rejects a delayed unlock of the same epoch`() {
        val gate = EmbedderSessionGate()
        assertTrue(gate.authorize(5))
        assertTrue(gate.admits(5))
        assertTrue(gate.revoke(5))
        assertFalse(gate.authorize(5))
        assertFalse(gate.admits(5))
        assertTrue(gate.authorize(6))
        assertFalse(gate.revoke(5))
        assertTrue(gate.admits(6))
    }

    @Test
    fun `lock before unlock prevents resurrection after a fresh bind`() {
        val gate = EmbedderSessionGate()
        assertTrue(gate.revoke(8))
        assertFalse(gate.authorize(8))
        assertTrue(gate.authorize(9))
        assertFalse(gate.authorize(7))
        assertTrue(gate.admits(9))
    }

    @Test
    fun `future lock revokes older authorization and rejects its delayed unlock`() {
        val gate = EmbedderSessionGate()
        assertTrue(gate.authorize(5))
        assertTrue(gate.revoke(6))
        assertFalse(gate.admits(5))
        assertFalse(gate.authorize(6))
        assertFalse(gate.authorize(5))
        assertTrue(gate.authorize(7))
        assertFalse(gate.revoke(6))
        assertTrue(gate.admits(7))
    }

    @Test
    fun `revoked publication never runs its action`() {
        val gate = EmbedderSessionGate()
        gate.authorize(1)
        gate.revoke(1)
        var published = false
        assertFalse(gate.whileAuthorized(1) { published = true })
        assertFalse(published)
    }
}
