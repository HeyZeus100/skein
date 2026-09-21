// skein-up0 (E3.I14) — JVM tests for [LockPolicyObserver]'s gating logic:
// given a fake current [LockPolicy] and a recording `requestLock` lambda
// (standing in for `UnlockManager.lock`, a "fake owner" of the lock
// request), does `onScreenOff`/`onAppBackground` call it with the right
// [LockReason], gated correctly by the policy in effect at the moment the
// trigger fires. AAA, one assertion focus per test.

package app.skein.vault

import app.skein.core.vault.session.LockPolicy
import app.skein.core.vault.session.LockReason
import org.junit.Assert.assertEquals
import org.junit.Test

class LockPolicyObserverTest {
    @Test
    fun `onScreenOff requests a lock when lockOnScreenOff is enabled`() {
        // Arrange
        val requested = mutableListOf<LockReason>()
        val observer =
            LockPolicyObserver(
                currentPolicy = { LockPolicy(lockOnScreenOff = true) },
                requestLock = { requested += it },
            )
        // Act
        observer.onScreenOff()
        // Assert
        assertEquals(listOf(LockReason.SCREEN_OFF_POLICY), requested)
    }

    @Test
    fun `onScreenOff is a no-op when lockOnScreenOff is disabled`() {
        // Arrange
        val requested = mutableListOf<LockReason>()
        val observer =
            LockPolicyObserver(
                currentPolicy = { LockPolicy(lockOnScreenOff = false) },
                requestLock = { requested += it },
            )
        // Act
        observer.onScreenOff()
        // Assert
        assertEquals(emptyList<LockReason>(), requested)
    }

    @Test
    fun `onAppBackground is a no-op when lockOnBackground is disabled (the default)`() {
        // Arrange
        val requested = mutableListOf<LockReason>()
        val observer =
            LockPolicyObserver(
                currentPolicy = { LockPolicy() },
                requestLock = { requested += it },
            )
        // Act
        observer.onAppBackground()
        // Assert
        assertEquals(emptyList<LockReason>(), requested)
    }

    @Test
    fun `onAppBackground requests a lock when lockOnBackground is enabled`() {
        // Arrange
        val requested = mutableListOf<LockReason>()
        val observer =
            LockPolicyObserver(
                currentPolicy = { LockPolicy(lockOnBackground = true) },
                requestLock = { requested += it },
            )
        // Act
        observer.onAppBackground()
        // Assert
        assertEquals(listOf(LockReason.BACKGROUND_POLICY), requested)
    }

    @Test
    fun `each trigger reads the policy fresh, so a live Settings change applies on the next fire`() {
        // Arrange: policy starts disabled, then flips on before the second trigger
        val requested = mutableListOf<LockReason>()
        var lockOnScreenOff = false
        val observer =
            LockPolicyObserver(
                currentPolicy = { LockPolicy(lockOnScreenOff = lockOnScreenOff) },
                requestLock = { requested += it },
            )
        // Act
        observer.onScreenOff()
        lockOnScreenOff = true
        observer.onScreenOff()
        // Assert
        assertEquals(listOf(LockReason.SCREEN_OFF_POLICY), requested)
    }
}
