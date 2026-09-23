// skein-pya (E3.I3a) — UnlockManager JVM unit tests. One behaviour per
// test, AAA structure. Covers:
//   * every transition of the state machine described in the brief
//     (LOCKED / UNLOCKING / UNLOCKED / LOCKING / RECOVERY_REQUIRED);
//   * idle-lock via a controllable clock + explicit poll entry point;
//   * observer ack deadline (all-ack path and FORCE_TIMEOUT path);
//   * zeroization ordering — `VaultKeyProvider.lock()` MUST run BEFORE the
//     state transitions to `Locked` (verified via
//     `FakeVaultKeyProvider.stateAtLock`);
//   * concurrent unlock coalescing — the second call MUST NOT trigger a
//     second `keyProvider.unlock()` call;
//   * `poke()` is a no-op outside `Unlocked`.
//
// The public UnlockManager surface takes `FragmentActivity` +
// `BiometricPrompt.PromptInfo` (Android types). Tests drive the state
// machine via the `internal` entry points (`unlockWith`, `recoverAndRewrapWith`)
// that accept a suspend factory instead — same pattern as
// `VaultKeyProviderImplTest`.

package app.skein.core.vault.session

import app.skein.core.model.AuthorizationToken
import app.skein.core.vault.key.RewrapResult
import app.skein.core.vault.key.UnlockResult
import app.skein.core.vault.key.VaultKeyProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test
import java.time.Duration

private val UNLOCK_BIOMETRIC = VaultKeyProvider.Factor.BIOMETRIC

class UnlockManagerTest {
    // -------------------------------------------------------------- helpers

    private class Harness(
        val budgetMillis: Long = 500L,
        idleTimeoutMillis: Long = 60_000L,
    ) {
        val clock = TestClock()
        val provider = FakeVaultKeyProvider(stateAtLockSink = { managerState() })
        val manager =
            UnlockManager(
                keyProvider = provider,
                clock = clock,
                idleTimeout = Duration.ofMillis(idleTimeoutMillis),
                idleTickInterval = Duration.ofMillis(idleTimeoutMillis / 10),
                observerBudgetMillis = budgetMillis,
                scope = null,
                installShutdownHook = false,
            )

        private var managerRef: UnlockManager = manager

        private fun managerState(): UnlockState = managerRef.state.value

        suspend fun unlockOk(): UnlockOutcome =
            manager.unlockWith(UNLOCK_BIOMETRIC) {
                UnlockResult.Success(AuthorizationToken(1L))
            }
    }

    // -------------------------------------------------------------- unlock

    @Test
    fun `initial state is Locked`() =
        runTest {
            // Arrange
            val h = Harness()
            // Act / Assert
            assertThat(h.manager.state.value).isEqualTo(UnlockState.Locked)
            assertThat(h.manager.authorizationToken.value).isNull()
        }

    @Test
    fun `unlock success transitions Locked to Unlocked and publishes token`() =
        runTest {
            // Arrange
            val h = Harness()
            // Act
            val outcome = h.unlockOk()
            // Assert
            assertThat(outcome).isInstanceOf(UnlockOutcome.Success::class.java)
            assertThat(h.manager.state.value).isInstanceOf(UnlockState.Unlocked::class.java)
            assertThat(h.manager.authorizationToken.value).isEqualTo(AuthorizationToken(1L))
        }

    @Test
    fun `unlock user cancel leaves state Locked`() =
        runTest {
            // Arrange
            val h = Harness()
            // Act
            val outcome =
                h.manager.unlockWith(UNLOCK_BIOMETRIC) { UnlockResult.UserCancelled }
            // Assert
            assertThat(outcome).isEqualTo(UnlockOutcome.UserCancelled)
            assertThat(h.manager.state.value).isEqualTo(UnlockState.Locked)
            assertThat(h.manager.authorizationToken.value).isNull()
        }

    @Test
    fun `unlock KeyPermanentlyInvalidated transitions to RecoveryRequired`() =
        runTest {
            // Arrange
            val h = Harness()
            // Act
            val outcome =
                h.manager.unlockWith(UNLOCK_BIOMETRIC) {
                    UnlockResult.KeyPermanentlyInvalidated(UNLOCK_BIOMETRIC)
                }
            // Assert
            assertThat(outcome)
                .isEqualTo(UnlockOutcome.KeyPermanentlyInvalidated(UNLOCK_BIOMETRIC))
            assertThat(h.manager.state.value).isEqualTo(UnlockState.RecoveryRequired)
        }

    @Test
    fun `unlock failed leaves state Locked`() =
        runTest {
            // Arrange
            val h = Harness()
            // Act
            val outcome =
                h.manager.unlockWith(UNLOCK_BIOMETRIC) {
                    UnlockResult.Failed("bad tag")
                }
            // Assert
            assertThat(outcome).isEqualTo(UnlockOutcome.Failed("bad tag"))
            assertThat(h.manager.state.value).isEqualTo(UnlockState.Locked)
        }

    @Test
    fun `unlock DeviceLocked leaves state Locked and is not a Failed`() =
        runTest {
            // Arrange
            val h = Harness()
            // Act
            val outcome =
                h.manager.unlockWith(UNLOCK_BIOMETRIC) {
                    UnlockResult.DeviceLocked
                }
            // Assert — skein-9psb: typed, distinct from Failed; no reason string exists.
            assertThat(outcome).isEqualTo(UnlockOutcome.DeviceLocked)
            assertThat(h.manager.state.value).isEqualTo(UnlockState.Locked)
        }

    @Test
    fun `unlock when already Unlocked returns Coalesced`() =
        runTest {
            // Arrange: already unlocked
            val h = Harness()
            h.unlockOk()
            // Act: second unlock
            val outcome =
                h.manager.unlockWith(UNLOCK_BIOMETRIC) {
                    error("must not be called — state was already Unlocked")
                }
            // Assert
            assertThat(outcome).isInstanceOf(UnlockOutcome.Coalesced::class.java)
        }

    // -------------------------------------------------------------- lock

    @Test
    fun `lock from Unlocked zeros key then transitions to Locked`() =
        runTest {
            // Arrange: unlocked
            val h = Harness()
            h.unlockOk()
            // Act
            h.manager.lockAndAwait(LockReason.USER_REQUESTED)
            // Assert: zeroize happened BEFORE state transition
            assertThat(h.provider.lockCallCount.get()).isEqualTo(1)
            assertThat(h.provider.stateAtLock).isInstanceOf(UnlockState.Locking::class.java)
            assertThat(h.manager.state.value).isEqualTo(UnlockState.Locked)
            assertThat(h.manager.authorizationToken.value).isNull()
        }

    @Test
    fun `lock is idempotent when already Locked`() =
        runTest {
            // Arrange
            val h = Harness()
            // Act
            h.manager.lockAndAwait(LockReason.USER_REQUESTED)
            h.manager.lockAndAwait(LockReason.USER_REQUESTED)
            // Assert
            assertThat(h.provider.lockCallCount.get()).isEqualTo(0)
            assertThat(h.manager.state.value).isEqualTo(UnlockState.Locked)
        }

    @Test
    fun `lock with KEY_INVALIDATED lands in RecoveryRequired`() =
        runTest {
            // Arrange: unlocked
            val h = Harness()
            h.unlockOk()
            // Act
            h.manager.lockAndAwait(LockReason.KEY_INVALIDATED)
            // Assert
            assertThat(h.provider.lockCallCount.get()).isEqualTo(1)
            assertThat(h.manager.state.value).isEqualTo(UnlockState.RecoveryRequired)
        }

    // -------------------------------------------------------------- observers

    @Test
    fun `observers see Locking then Locked within budget`() =
        runTest {
            // Arrange
            val h = Harness()
            val calls = mutableListOf<String>()
            val obs =
                object : LockObserver {
                    override val priority = LockObserverPriority.HIGH

                    override suspend fun onLocking(
                        epoch: Long,
                        budgetMillis: Long,
                    ) {
                        calls.add("onLocking")
                    }

                    override fun onLocked(epoch: Long) {
                        calls.add("onLocked")
                    }

                    override fun onUnlocked(epoch: Long) {
                        calls.add("onUnlocked")
                    }
                }
            h.manager.addLockObserver(obs)
            h.unlockOk()
            // Act
            h.manager.lockAndAwait(LockReason.USER_REQUESTED)
            // Assert
            assertThat(calls).containsExactly("onUnlocked", "onLocking", "onLocked").inOrder()
        }

    @Test
    fun `observer that never acks triggers FORCE_TIMEOUT but zeroize still runs`() =
        runTest {
            // Arrange: an observer that blocks forever
            val h = Harness(budgetMillis = 100L)
            val hang = CompletableDeferred<Unit>()
            val hangingObs =
                object : LockObserver {
                    override val priority = LockObserverPriority.HIGH

                    override suspend fun onLocking(
                        epoch: Long,
                        budgetMillis: Long,
                    ) {
                        hang.await()
                    }

                    override fun onLocked(epoch: Long) = Unit

                    override fun onUnlocked(epoch: Long) = Unit
                }
            h.manager.addLockObserver(hangingObs)
            h.unlockOk()
            // Act
            h.manager.lockAndAwait(LockReason.USER_REQUESTED)
            // Assert
            assertThat(h.provider.lockCallCount.get()).isEqualTo(1)
            assertThat(h.provider.stateAtLock).isInstanceOf(UnlockState.Locking::class.java)
            assertThat(h.manager.state.value).isEqualTo(UnlockState.Locked)
            hang.complete(Unit) // release the observer for cleanup
        }

    @Test
    fun `HIGH observers run before LOW observers`() =
        runTest {
            // Arrange
            val h = Harness()
            val order = mutableListOf<String>()

            fun obs(
                tag: String,
                p: LockObserverPriority,
            ) = object : LockObserver {
                override val priority = p

                override suspend fun onLocking(
                    epoch: Long,
                    budgetMillis: Long,
                ) {
                    order.add(tag)
                }

                override fun onLocked(epoch: Long) = Unit

                override fun onUnlocked(epoch: Long) = Unit
            }
            h.manager.addLockObserver(obs("low", LockObserverPriority.LOW))
            h.manager.addLockObserver(obs("high", LockObserverPriority.HIGH))
            h.unlockOk()
            // Act
            h.manager.lockAndAwait(LockReason.USER_REQUESTED)
            // Assert
            assertThat(order).containsExactly("high", "low").inOrder()
        }

    // ------------------------------------------------ TEARDOWN (skein-1bx4)

    @Test
    fun `TEARDOWN observers run after both HIGH and LOW`() =
        runTest {
            // Arrange
            val h = Harness()
            val order = mutableListOf<String>()

            fun obs(
                tag: String,
                p: LockObserverPriority,
            ) = object : LockObserver {
                override val priority = p

                override suspend fun onLocking(
                    epoch: Long,
                    budgetMillis: Long,
                ) {
                    order.add(tag)
                }

                override fun onLocked(epoch: Long) = Unit

                override fun onUnlocked(epoch: Long) = Unit
            }
            h.manager.addLockObserver(obs("teardown", LockObserverPriority.TEARDOWN))
            h.manager.addLockObserver(obs("low", LockObserverPriority.LOW))
            h.manager.addLockObserver(obs("high", LockObserverPriority.HIGH))
            h.unlockOk()
            // Act
            h.manager.lockAndAwait(LockReason.USER_REQUESTED)
            // Assert
            assertThat(order).containsExactly("high", "low", "teardown").inOrder()
        }

    @Test
    fun `TEARDOWN runs before the key is zeroed`() =
        runTest {
            // Arrange — the non-negotiable this tier exists to keep: the
            // vault closes while the master key is still live.
            val h = Harness()
            var zeroizedBeforeTeardown: Boolean? = null
            val teardown =
                object : LockObserver {
                    override val priority = LockObserverPriority.TEARDOWN

                    override suspend fun onLocking(
                        epoch: Long,
                        budgetMillis: Long,
                    ) {
                        // `lock()` is the call that zeroes the master key.
                        zeroizedBeforeTeardown = h.provider.lockCallCount.get() > 0
                    }

                    override fun onLocked(epoch: Long) = Unit

                    override fun onUnlocked(epoch: Long) = Unit
                }
            h.manager.addLockObserver(teardown)
            h.unlockOk()
            // Act
            h.manager.lockAndAwait(LockReason.USER_REQUESTED)
            // Assert
            assertThat(zeroizedBeforeTeardown).isFalse()
            assertThat(h.provider.lockCallCount.get()).isEqualTo(1)
        }

    /**
     * The regression this tier was added for: a HIGH observer that burns the
     * shared budget used to cancel the enclosing `withTimeoutOrNull` and take
     * the whole rest of the notify pass with it, so the vault close never ran
     * on the lock path at all — it was left to `onLocked`'s backstop, AFTER
     * zeroization, on another coroutine. See `LockObserverPriority.TEARDOWN`.
     */
    @Test
    fun `TEARDOWN still runs when a HIGH observer exhausts the shared budget`() =
        runTest {
            // Arrange
            val h = Harness(budgetMillis = 100L)
            val hang = CompletableDeferred<Unit>()
            val hangingHigh =
                object : LockObserver {
                    override val priority = LockObserverPriority.HIGH

                    override suspend fun onLocking(
                        epoch: Long,
                        budgetMillis: Long,
                    ) {
                        hang.await()
                    }

                    override fun onLocked(epoch: Long) = Unit

                    override fun onUnlocked(epoch: Long) = Unit
                }
            var tornDown = false
            var zeroizedBeforeTeardown: Boolean? = null
            val teardown =
                object : LockObserver {
                    override val priority = LockObserverPriority.TEARDOWN

                    override suspend fun onLocking(
                        epoch: Long,
                        budgetMillis: Long,
                    ) {
                        tornDown = true
                        zeroizedBeforeTeardown = h.provider.lockCallCount.get() > 0
                    }

                    override fun onLocked(epoch: Long) = Unit

                    override fun onUnlocked(epoch: Long) = Unit
                }
            h.manager.addLockObserver(hangingHigh)
            h.manager.addLockObserver(teardown)
            h.unlockOk()
            // Act
            h.manager.lockAndAwait(LockReason.USER_REQUESTED)
            // Assert — the close ran, on the lock path, before zeroization.
            assertThat(tornDown).isTrue()
            assertThat(zeroizedBeforeTeardown).isFalse()
            assertThat(h.provider.lockCallCount.get()).isEqualTo(1)
            assertThat(h.manager.state.value).isEqualTo(UnlockState.Locked)
            assertThat(h.manager.lastEffectiveLockReasonForTest()).isEqualTo(LockReason.FORCE_TIMEOUT)
            hang.complete(Unit) // release the observer for cleanup
        }

    @Test
    fun `a TEARDOWN observer that never acks still cannot hold the key alive`() =
        runTest {
            // Arrange — the teardown window is bounded too: a wedged close
            // must not be able to postpone zeroization indefinitely.
            val h = Harness(budgetMillis = 100L)
            val hang = CompletableDeferred<Unit>()
            val hangingTeardown =
                object : LockObserver {
                    override val priority = LockObserverPriority.TEARDOWN

                    override suspend fun onLocking(
                        epoch: Long,
                        budgetMillis: Long,
                    ) {
                        hang.await()
                    }

                    override fun onLocked(epoch: Long) = Unit

                    override fun onUnlocked(epoch: Long) = Unit
                }
            h.manager.addLockObserver(hangingTeardown)
            h.unlockOk()
            // Act
            h.manager.lockAndAwait(LockReason.USER_REQUESTED)
            // Assert
            assertThat(h.provider.lockCallCount.get()).isEqualTo(1)
            assertThat(h.provider.stateAtLock).isInstanceOf(UnlockState.Locking::class.java)
            assertThat(h.manager.state.value).isEqualTo(UnlockState.Locked)
            assertThat(h.manager.lastEffectiveLockReasonForTest()).isEqualTo(LockReason.FORCE_TIMEOUT)
            hang.complete(Unit) // release the observer for cleanup
        }

    @Test
    fun `a throwing TEARDOWN observer does not skip keyProvider lock`() =
        runTest {
            // Arrange
            val h = Harness()
            val throwingTeardown =
                object : LockObserver {
                    override val priority = LockObserverPriority.TEARDOWN

                    override suspend fun onLocking(
                        epoch: Long,
                        budgetMillis: Long,
                    ): Unit = throw IllegalStateException("boom")

                    override fun onLocked(epoch: Long) = Unit

                    override fun onUnlocked(epoch: Long) = Unit
                }
            h.manager.addLockObserver(throwingTeardown)
            h.unlockOk()
            // Act
            h.manager.lockAndAwait(LockReason.USER_REQUESTED)
            // Assert
            assertThat(h.provider.lockCallCount.get()).isEqualTo(1)
            assertThat(h.manager.state.value).isEqualTo(UnlockState.Locked)
            assertThat(h.provider.currentKey()).isNull()
            assertThat(h.manager.lastEffectiveLockReasonForTest()).isEqualTo(LockReason.FORCE_TIMEOUT)
        }

    @Test
    fun `throwing HIGH observer does not skip keyProvider lock and reaches Locked`() =
        runTest {
            // Arrange: a HIGH observer whose onLocking always throws
            val h = Harness()
            val throwingObs =
                object : LockObserver {
                    override val priority = LockObserverPriority.HIGH

                    override suspend fun onLocking(
                        epoch: Long,
                        budgetMillis: Long,
                    ): Unit = throw IllegalStateException("boom")

                    override fun onLocked(epoch: Long) = Unit

                    override fun onUnlocked(epoch: Long) = Unit
                }
            h.manager.addLockObserver(throwingObs)
            h.unlockOk()
            // Act
            h.manager.lockAndAwait(LockReason.USER_REQUESTED)
            // Assert: zeroize still ran, state reached Locked, key is gone,
            // and the throw is reported like a timed-out observer.
            assertThat(h.provider.lockCallCount.get()).isEqualTo(1)
            assertThat(h.provider.stateAtLock).isInstanceOf(UnlockState.Locking::class.java)
            assertThat(h.manager.state.value).isEqualTo(UnlockState.Locked)
            assertThat(h.provider.currentKey()).isNull()
            assertThat(h.manager.lastEffectiveLockReasonForTest()).isEqualTo(LockReason.FORCE_TIMEOUT)
        }

    @Test
    fun `throwing LOW observer does not skip keyProvider lock and reaches Locked`() =
        runTest {
            // Arrange: a LOW observer whose onLocking always throws
            val h = Harness()
            val throwingObs =
                object : LockObserver {
                    override val priority = LockObserverPriority.LOW

                    override suspend fun onLocking(
                        epoch: Long,
                        budgetMillis: Long,
                    ): Unit = throw IllegalStateException("boom")

                    override fun onLocked(epoch: Long) = Unit

                    override fun onUnlocked(epoch: Long) = Unit
                }
            h.manager.addLockObserver(throwingObs)
            h.unlockOk()
            // Act
            h.manager.lockAndAwait(LockReason.USER_REQUESTED)
            // Assert
            assertThat(h.provider.lockCallCount.get()).isEqualTo(1)
            assertThat(h.provider.stateAtLock).isInstanceOf(UnlockState.Locking::class.java)
            assertThat(h.manager.state.value).isEqualTo(UnlockState.Locked)
            assertThat(h.provider.currentKey()).isNull()
            assertThat(h.manager.lastEffectiveLockReasonForTest()).isEqualTo(LockReason.FORCE_TIMEOUT)
        }

    @Test
    fun `throwing observer does not prevent later same- and lower-priority observers from running`() =
        runTest {
            // Arrange: a throwing HIGH observer alongside a surviving HIGH
            // sibling and a surviving LOW observer.
            val h = Harness()
            val called = mutableSetOf<String>()
            val throwingHigh =
                object : LockObserver {
                    override val priority = LockObserverPriority.HIGH

                    override suspend fun onLocking(
                        epoch: Long,
                        budgetMillis: Long,
                    ): Unit = throw IllegalStateException("boom")

                    override fun onLocked(epoch: Long) = Unit

                    override fun onUnlocked(epoch: Long) = Unit
                }

            fun okObs(
                tag: String,
                p: LockObserverPriority,
            ) = object : LockObserver {
                override val priority = p

                override suspend fun onLocking(
                    epoch: Long,
                    budgetMillis: Long,
                ) {
                    called.add(tag)
                }

                override fun onLocked(epoch: Long) = Unit

                override fun onUnlocked(epoch: Long) = Unit
            }
            h.manager.addLockObserver(throwingHigh)
            h.manager.addLockObserver(okObs("high-sibling", LockObserverPriority.HIGH))
            h.manager.addLockObserver(okObs("low", LockObserverPriority.LOW))
            h.unlockOk()
            // Act
            h.manager.lockAndAwait(LockReason.USER_REQUESTED)
            // Assert: both the surviving HIGH sibling and the LOW observer
            // ran despite the earlier throw.
            assertThat(called).containsExactly("high-sibling", "low")
            assertThat(h.manager.state.value).isEqualTo(UnlockState.Locked)
        }

    @Test
    fun `unlock succeeds after a lock in which an observer threw`() =
        runTest {
            // Arrange: a HIGH observer that always throws in onLocking
            val h = Harness()
            val throwingObs =
                object : LockObserver {
                    override val priority = LockObserverPriority.HIGH

                    override suspend fun onLocking(
                        epoch: Long,
                        budgetMillis: Long,
                    ): Unit = throw IllegalStateException("boom")

                    override fun onLocked(epoch: Long) = Unit

                    override fun onUnlocked(epoch: Long) = Unit
                }
            h.manager.addLockObserver(throwingObs)
            h.unlockOk()
            h.manager.lockAndAwait(LockReason.USER_REQUESTED)
            // Act: a second unlock after the throwing-observer lock
            val outcome = h.unlockOk()
            // Assert: the manager is not wedged — unlock proceeds normally
            assertThat(outcome).isInstanceOf(UnlockOutcome.Success::class.java)
            assertThat(h.manager.state.value).isInstanceOf(UnlockState.Unlocked::class.java)
        }

    @Test
    fun `addLockObserver disposable removes the observer`() =
        runTest {
            // Arrange
            val h = Harness()
            val obs =
                object : LockObserver {
                    override val priority = LockObserverPriority.HIGH

                    override suspend fun onLocking(
                        epoch: Long,
                        budgetMillis: Long,
                    ) = Unit

                    override fun onLocked(epoch: Long) = Unit

                    override fun onUnlocked(epoch: Long) = Unit
                }
            val handle = h.manager.addLockObserver(obs)
            assertThat(h.manager.observerCountForTest()).isEqualTo(1)
            // Act
            handle.dispose()
            // Assert
            assertThat(h.manager.observerCountForTest()).isEqualTo(0)
        }

    // -------------------------------------------------------------- idle-lock

    @Test
    fun `idle poll locks when timeout elapsed`() =
        runTest {
            // Arrange
            val h = Harness(idleTimeoutMillis = 1_000L)
            h.unlockOk()
            // Act
            h.clock.advance(2_000L)
            h.manager.pollIdleTimerForTest()
            // Assert
            assertThat(h.manager.state.value).isEqualTo(UnlockState.Locked)
            assertThat(h.provider.lockCallCount.get()).isEqualTo(1)
        }

    @Test
    fun `idle poll does not lock before timeout`() =
        runTest {
            // Arrange
            val h = Harness(idleTimeoutMillis = 1_000L)
            h.unlockOk()
            // Act
            h.clock.advance(500L)
            h.manager.pollIdleTimerForTest()
            // Assert
            assertThat(h.manager.state.value).isInstanceOf(UnlockState.Unlocked::class.java)
        }

    @Test
    fun `poke extends idle timeout`() =
        runTest {
            // Arrange
            val h = Harness(idleTimeoutMillis = 1_000L)
            h.unlockOk()
            // Act
            h.clock.advance(900L)
            h.manager.poke()
            h.clock.advance(900L)
            h.manager.pollIdleTimerForTest()
            // Assert
            assertThat(h.manager.state.value).isInstanceOf(UnlockState.Unlocked::class.java)
        }

    @Test
    fun `poke is no-op when Locked`() =
        runTest {
            // Arrange
            val h = Harness()
            val beforePoke = h.manager.lastActivityForTest()
            // Act
            h.manager.poke()
            // Assert
            assertThat(h.manager.lastActivityForTest()).isEqualTo(beforePoke)
        }

    // -------------------------------------------------------------- lock policy (E3.I14)

    @Test
    fun `initial policy matches the ctor idleTimeout and secure defaults`() =
        runTest {
            // Arrange
            val h = Harness(idleTimeoutMillis = 60_000L)
            // Act / Assert
            assertThat(h.manager.policy.value.idleTimeout).isEqualTo(Duration.ofMillis(60_000L))
            assertThat(h.manager.policy.value.lockOnScreenOff).isTrue()
            assertThat(h.manager.policy.value.lockOnBackground).isFalse()
        }

    @Test
    fun `configure updates idle timeout used by the idle poller`() =
        runTest {
            // Arrange: 5-minute timeout would NOT lock after 90s
            val h = Harness(idleTimeoutMillis = Duration.ofMinutes(5).toMillis())
            h.unlockOk()
            // Act: tighten to the 1-minute floor
            h.manager.configure(LockPolicy(idleTimeout = Duration.ofMinutes(1)))
            h.clock.advance(90_000L)
            h.manager.pollIdleTimerForTest()
            // Assert: the new, shorter timeout applied immediately
            assertThat(h.manager.state.value).isEqualTo(UnlockState.Locked)
        }

    @Test
    fun `configure clamps an idle timeout above the plan ceiling`() =
        runTest {
            // Arrange
            val h = Harness()
            // Act
            h.manager.configure(LockPolicy(idleTimeout = Duration.ofMinutes(999)))
            // Assert
            assertThat(h.manager.policy.value.idleTimeout).isEqualTo(LockPolicy.MAX_IDLE_TIMEOUT)
        }

    @Test
    fun `configure clamps an idle timeout below the plan floor`() =
        runTest {
            // Arrange
            val h = Harness()
            // Act
            h.manager.configure(LockPolicy(idleTimeout = Duration.ZERO))
            // Assert
            assertThat(h.manager.policy.value.idleTimeout).isEqualTo(LockPolicy.MIN_IDLE_TIMEOUT)
        }

    @Test
    fun `configure updates lockOnScreenOff and lockOnBackground flags`() =
        runTest {
            // Arrange
            val h = Harness()
            // Act
            h.manager.configure(LockPolicy(lockOnScreenOff = false, lockOnBackground = true))
            // Assert
            assertThat(h.manager.policy.value.lockOnScreenOff).isFalse()
            assertThat(h.manager.policy.value.lockOnBackground).isTrue()
        }

    // -------------------------------------------------------------- recovery

    @Test
    fun `recoverAndRewrap on success transitions RecoveryRequired to Unlocked`() =
        runTest {
            // Arrange: land in RecoveryRequired via unlock
            val h = Harness()
            h.manager.unlockWith(UNLOCK_BIOMETRIC) {
                UnlockResult.KeyPermanentlyInvalidated(UNLOCK_BIOMETRIC)
            }
            // Act — simulate the skein-22su contract: a successful rewrap
            // leaves the master key accessible via currentKey() (the real
            // `VaultKeyProviderImpl.rewrapAfterInvalidation` does this by
            // transferring ownership into its `master` field).
            val outcome =
                h.manager.recoverAndRewrapWith {
                    h.provider.master = ByteArray(32) { 9 }
                    RewrapResult.Success(newKeyVersion = 7)
                }
            // Assert
            assertThat(outcome).isInstanceOf(RecoveryOutcome.Success::class.java)
            assertThat(h.manager.state.value).isInstanceOf(UnlockState.Unlocked::class.java)
            assertThat(h.manager.authorizationToken.value).isNotNull()
            // skein-22su: state == Unlocked must never be reachable with a
            // null in-memory key.
            assertThat(h.provider.currentKey()).isNotNull()
        }

    @Test
    fun `recoverAndRewrap success throws IllegalStateException when contract violated`() =
        runTest {
            // Arrange: land in RecoveryRequired via unlock. The rewrap lambda
            // reports Success WITHOUT populating currentKey() — simulating a
            // VaultKeyProvider that violates the skein-22su contract.
            val h = Harness()
            h.manager.unlockWith(UNLOCK_BIOMETRIC) {
                UnlockResult.KeyPermanentlyInvalidated(UNLOCK_BIOMETRIC)
            }
            // Act / Assert
            val thrown =
                try {
                    h.manager.recoverAndRewrapWith {
                        RewrapResult.Success(newKeyVersion = 7)
                    }
                    null
                } catch (e: IllegalStateException) {
                    e
                }
            assertThat(thrown).isNotNull()
            assertThat(thrown!!.message).isEqualTo(
                "rewrapAfterInvalidation succeeded but currentKey is null; VaultKeyProvider contract violated",
            )
        }

    @Test
    fun `recoverAndRewrap cancel drops to Locked`() =
        runTest {
            // Arrange
            val h = Harness()
            h.manager.unlockWith(UNLOCK_BIOMETRIC) {
                UnlockResult.KeyPermanentlyInvalidated(UNLOCK_BIOMETRIC)
            }
            // Act
            val outcome =
                h.manager.recoverAndRewrapWith { RewrapResult.UserCancelled }
            // Assert
            assertThat(outcome).isEqualTo(RecoveryOutcome.UserCancelled)
            assertThat(h.manager.state.value).isEqualTo(UnlockState.Locked)
        }

    @Test
    fun `recoverAndRewrap both-factors-invalidated drops to Locked`() =
        runTest {
            // Arrange
            val h = Harness()
            h.manager.unlockWith(UNLOCK_BIOMETRIC) {
                UnlockResult.KeyPermanentlyInvalidated(UNLOCK_BIOMETRIC)
            }
            // Act
            val outcome =
                h.manager.recoverAndRewrapWith {
                    RewrapResult.BothFactorsInvalidated
                }
            // Assert
            assertThat(outcome).isEqualTo(RecoveryOutcome.BothFactorsInvalidated)
            assertThat(h.manager.state.value).isEqualTo(UnlockState.Locked)
        }

    @Test
    fun `recoverAndRewrap failed stays in RecoveryRequired`() =
        runTest {
            // Arrange
            val h = Harness()
            h.manager.unlockWith(UNLOCK_BIOMETRIC) {
                UnlockResult.KeyPermanentlyInvalidated(UNLOCK_BIOMETRIC)
            }
            // Act
            val outcome =
                h.manager.recoverAndRewrapWith {
                    RewrapResult.Failed("no active row")
                }
            // Assert
            assertThat(outcome).isEqualTo(RecoveryOutcome.Failed("no active row"))
            assertThat(h.manager.state.value).isEqualTo(UnlockState.RecoveryRequired)
        }

    @Test
    fun `recoverAndRewrap from wrong state returns IllegalTransition`() =
        runTest {
            // Arrange: still Locked
            val h = Harness()
            // Act
            val outcome =
                h.manager.recoverAndRewrapWith {
                    error("must not be called from wrong state")
                }
            // Assert
            assertThat(outcome).isInstanceOf(RecoveryOutcome.IllegalTransition::class.java)
        }

    @Test
    fun `lock from RecoveryRequired transitions to Locked without invoking keyProvider`() =
        runTest {
            // Arrange
            val h = Harness()
            h.manager.unlockWith(UNLOCK_BIOMETRIC) {
                UnlockResult.KeyPermanentlyInvalidated(UNLOCK_BIOMETRIC)
            }
            val locksBefore = h.provider.lockCallCount.get()
            // Act
            h.manager.lockAndAwait(LockReason.RECOVERY_CANCELLED)
            // Assert
            assertThat(h.manager.state.value).isEqualTo(UnlockState.Locked)
            assertThat(h.provider.lockCallCount.get()).isEqualTo(locksBefore)
        }

    // -------------------------------------------------------------- concurrency

    @Test
    fun `two concurrent unlock calls result in exactly one auth invocation`() =
        runTest {
            // Arrange
            val h = Harness()
            val gate = CompletableDeferred<Unit>()
            // First call blocks inside `auth` until we release `gate`. Second
            // call queues on the transition mutex.
            var firstAuthStarted = false
            val outcomes =
                coroutineScope {
                    val a =
                        async {
                            h.manager.unlockWith(UNLOCK_BIOMETRIC) {
                                firstAuthStarted = true
                                gate.await()
                                UnlockResult.Success(AuthorizationToken(1L))
                            }
                        }
                    // yield until the first call is inside `auth`, then start the second
                    while (!firstAuthStarted) yield()
                    val b =
                        async {
                            h.manager.unlockWith(UNLOCK_BIOMETRIC) {
                                error("second call must not invoke auth — state should be Unlocked")
                            }
                        }
                    // Release the first
                    gate.complete(Unit)
                    listOf(a, b).awaitAll()
                }
            // Assert
            assertThat(outcomes[0]).isInstanceOf(UnlockOutcome.Success::class.java)
            assertThat(outcomes[1]).isInstanceOf(UnlockOutcome.Coalesced::class.java)
        }

    // -------------------------------------------------------------- token

    @Test
    fun `authorizationToken StateFlow reflects unlock and lock transitions`() =
        runTest {
            // Arrange
            val h = Harness()
            assertThat(h.manager.authorizationToken.value).isNull()
            // Act
            val out = h.unlockOk() as UnlockOutcome.Success
            assertThat(h.manager.authorizationToken.value).isEqualTo(out.token)
            h.manager.lockAndAwait(LockReason.USER_REQUESTED)
            // Assert
            assertThat(h.manager.authorizationToken.value).isNull()
        }
}
