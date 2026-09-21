package app.skein.feature.shell.auth

import app.skein.core.vault.session.UnlockOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * skein-ank2: the pure outcome → effect mapping behind [BiometricUnlockScreen].
 * A corrupt / unreadable key envelope must read differently from an
 * ordinary authentication failure (and must never invite a re-setup), and
 * a `NotInitialised` outcome routes to setup when the host offers a route.
 */
class BiometricUnlockOutcomeTest {
    private class Effects {
        var unlocked = false
        var recovery = false
        var notInitialised = false
        var retryMessage: String? = null
        var envelopeUnreadableMessage: String? = null
    }

    private fun run(
        outcome: UnlockOutcome,
        withSetupRoute: Boolean = true,
        withEnvelopeUnreadableRoute: Boolean = false,
    ): Effects {
        val effects = Effects()
        handleOutcome(
            outcome = outcome,
            onUnlocked = { effects.unlocked = true },
            onRecoveryRequired = { effects.recovery = true },
            onNotInitialised = if (withSetupRoute) ({ effects.notInitialised = true }) else null,
            onRetry = { effects.retryMessage = it },
            onEnvelopeUnreadable =
                if (withEnvelopeUnreadableRoute) {
                    { effects.envelopeUnreadableMessage = it }
                } else {
                    null
                },
        )
        return effects
    }

    @Test
    fun `a corrupt envelope shows a message distinct from the generic failure`() {
        val corrupt = run(UnlockOutcome.Failed(EnvelopeUnreadable.REASON_CORRUPT))
        val generic = run(UnlockOutcome.Failed("cipher init failed: KeyStoreException"))

        assertNotEquals(generic.retryMessage, corrupt.retryMessage)
    }

    @Test
    fun `an unreadable envelope shows the same message as a corrupt one`() {
        val corrupt = run(UnlockOutcome.Failed(EnvelopeUnreadable.REASON_CORRUPT))
        val io = run(UnlockOutcome.Failed(EnvelopeUnreadable.REASON_IO))

        assertEquals(corrupt.retryMessage, io.retryMessage)
    }

    @Test
    fun `a corrupt envelope never routes to setup`() {
        val effects = run(UnlockOutcome.Failed(EnvelopeUnreadable.REASON_CORRUPT))

        assertFalse(effects.notInitialised)
    }

    @Test
    fun `the corrupt-envelope message promises nothing was changed`() {
        val effects = run(UnlockOutcome.Failed(EnvelopeUnreadable.REASON_CORRUPT))

        assertTrue(effects.retryMessage!!.contains("Nothing has been changed"))
    }

    @Test
    fun `a generic failure never surfaces its reason`() {
        val reason = "unwrap failed: AEADBadTagException"

        val effects = run(UnlockOutcome.Failed(reason))

        assertFalse(effects.retryMessage!!.contains(reason))
    }

    @Test
    fun `NotInitialised routes to setup when the host offers a route`() {
        val effects = run(UnlockOutcome.NotInitialised, withSetupRoute = true)

        assertTrue(effects.notInitialised)
        assertNull(effects.retryMessage)
    }

    @Test
    fun `NotInitialised falls back to a retry message without a setup route`() {
        val effects = run(UnlockOutcome.NotInitialised, withSetupRoute = false)

        assertFalse(effects.notInitialised)
        assertEquals("The vault has not been set up yet.", effects.retryMessage)
    }

    @Test
    fun `a coalesced NotInitialised is unwrapped before routing`() {
        val effects = run(UnlockOutcome.Coalesced(UnlockOutcome.NotInitialised))

        assertTrue(effects.notInitialised)
    }

    // ---- skein-v3wb: the reset-affordance routing ---------------------------

    @Test
    fun `a corrupt envelope routes to onEnvelopeUnreadable when the host offers it`() {
        val effects = run(UnlockOutcome.Failed(EnvelopeUnreadable.REASON_CORRUPT), withEnvelopeUnreadableRoute = true)

        assertEquals(EnvelopeUnreadable.UNLOCK_MESSAGE, effects.envelopeUnreadableMessage)
        assertNull(effects.retryMessage)
    }

    @Test
    fun `an unreadable envelope also routes to onEnvelopeUnreadable when offered`() {
        val effects = run(UnlockOutcome.Failed(EnvelopeUnreadable.REASON_IO), withEnvelopeUnreadableRoute = true)

        assertEquals(EnvelopeUnreadable.UNLOCK_MESSAGE, effects.envelopeUnreadableMessage)
    }

    @Test
    fun `a corrupt envelope falls back to onRetry without an onEnvelopeUnreadable route`() {
        val effects = run(UnlockOutcome.Failed(EnvelopeUnreadable.REASON_CORRUPT), withEnvelopeUnreadableRoute = false)

        assertEquals(EnvelopeUnreadable.UNLOCK_MESSAGE, effects.retryMessage)
        assertNull(effects.envelopeUnreadableMessage)
    }

    @Test
    fun `a generic failure never routes to onEnvelopeUnreadable even when the host offers it`() {
        val effects =
            run(
                UnlockOutcome.Failed("cipher init failed: KeyStoreException"),
                withEnvelopeUnreadableRoute = true,
            )

        assertNull(effects.envelopeUnreadableMessage)
        assertEquals("Authentication failed.", effects.retryMessage)
    }

    @Test
    fun `user cancellation never routes to onEnvelopeUnreadable even when the host offers it`() {
        val effects = run(UnlockOutcome.UserCancelled, withEnvelopeUnreadableRoute = true)

        assertNull(effects.envelopeUnreadableMessage)
    }

    @Test
    fun `a coalesced corrupt envelope is unwrapped before routing to onEnvelopeUnreadable`() {
        val effects =
            run(
                UnlockOutcome.Coalesced(UnlockOutcome.Failed(EnvelopeUnreadable.REASON_CORRUPT)),
                withEnvelopeUnreadableRoute = true,
            )

        assertEquals(EnvelopeUnreadable.UNLOCK_MESSAGE, effects.envelopeUnreadableMessage)
    }
}
