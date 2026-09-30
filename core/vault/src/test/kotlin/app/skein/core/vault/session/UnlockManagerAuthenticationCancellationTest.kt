package app.skein.core.vault.session

import app.skein.core.model.AuthorizationToken
import app.skein.core.vault.key.UnlockResult
import app.skein.core.vault.key.VaultKeyProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UnlockManagerAuthenticationCancellationTest {
    private val credential = VaultKeyProvider.Factor.DEVICE_CREDENTIAL

    @Test
    fun `missing biometric followed by credential success retains normal observer semantics`() =
        runTest {
            val provider = FakeVaultKeyProvider()
            val manager = UnlockManager(provider)
            val observer = RecordingObserver(manager)
            manager.addLockObserver(observer)
            assertThat(
                manager.unlockWith(VaultKeyProvider.Factor.BIOMETRIC) {
                    UnlockResult.KeyMaterialGone(VaultKeyProvider.Factor.BIOMETRIC)
                },
            ).isEqualTo(UnlockOutcome.KeyMaterialGone(VaultKeyProvider.Factor.BIOMETRIC))

            val token = AuthorizationToken(71)
            val result = manager.unlockWith(credential) { UnlockResult.Success(token) }

            assertThat(result).isEqualTo(UnlockOutcome.Success(token))
            assertThat(observer.unlocked).containsExactly(token.epoch)
            assertThat(observer.tokensAtNotification).containsExactly(token)
            assertThat(manager.state.value).isInstanceOf(UnlockState.Unlocked::class.java)
            assertThat(provider.rewrapCallCount.get()).isEqualTo(0)
        }

    @Test
    fun `lock wins over noncooperative late authentication without publishing any authorization`() =
        runTest {
            val provider = FakeVaultKeyProvider()
            val manager = UnlockManager(provider)
            val states = mutableListOf<UnlockState>()
            val tokens = mutableListOf<AuthorizationToken?>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { manager.state.collect { states += it } }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                manager.authorizationToken.collect {
                    tokens +=
                        it
                }
            }
            val observer = RecordingObserver(manager)
            manager.addLockObserver(observer)
            val release = CompletableDeferred<Unit>()
            val candidate = ByteArray(32) { 9 }
            val attempt =
                async {
                    manager.unlockWith(credential) {
                        withContext(NonCancellable) {
                            release.await()
                            provider.master = candidate
                            UnlockResult.Success(AuthorizationToken(72))
                        }
                    }
                }
            runCurrent()
            var queuedAuthCalls = 0
            val queued =
                async {
                    manager.unlockWith(credential) {
                        queuedAuthCalls++
                        UnlockResult.UserCancelled
                    }
                }
            runCurrent()
            val locking = async { manager.lockAndAwait(LockReason.USER_REQUESTED) }
            runCurrent()
            release.complete(Unit)

            assertThat(attempt.await()).isEqualTo(UnlockOutcome.UserCancelled)
            assertThat(queued.await()).isEqualTo(UnlockOutcome.UserCancelled)
            locking.await()
            assertThat(queuedAuthCalls).isEqualTo(0)
            assertThat(states.filterIsInstance<UnlockState.Unlocked>()).isEmpty()
            assertThat(tokens.filterNotNull()).isEmpty()
            assertThat(observer.unlocked).isEmpty()
            assertThat(manager.state.value).isEqualTo(UnlockState.Locked)
            assertThat(provider.currentKey()).isNull()
            assertThat(candidate.toList()).containsExactlyElementsIn(List(32) { 0.toByte() })
        }

    @Test
    fun `caller cancellation discards a key produced by a late callback`() =
        runTest {
            val provider = FakeVaultKeyProvider()
            val manager = UnlockManager(provider)
            val observer = RecordingObserver(manager)
            manager.addLockObserver(observer)
            val release = CompletableDeferred<Unit>()
            val candidate = ByteArray(32) { 3 }
            val attempt =
                async {
                    manager.unlockWith(credential) {
                        withContext(NonCancellable) {
                            release.await()
                            provider.master = candidate
                            UnlockResult.Success(AuthorizationToken(73))
                        }
                    }
                }
            runCurrent()
            attempt.cancel()
            release.complete(Unit)
            attempt.join()

            assertThat(attempt.isCancelled).isTrue()
            assertThat(manager.state.value).isEqualTo(UnlockState.Locked)
            assertThat(manager.authorizationToken.value).isNull()
            assertThat(provider.currentKey()).isNull()
            assertThat(candidate.toList()).containsExactlyElementsIn(List(32) { 0.toByte() })
            assertThat(observer.unlocked).isEmpty()
        }

    @Test
    fun `concurrent credential attempts authenticate once and coalesce the same token`() =
        runTest {
            val manager = UnlockManager(FakeVaultKeyProvider())
            val release = CompletableDeferred<Unit>()
            var calls = 0
            val token = AuthorizationToken(74)
            val first =
                async {
                    manager.unlockWith(credential) {
                        calls++
                        release.await()
                        UnlockResult.Success(token)
                    }
                }
            runCurrent()
            val second =
                async {
                    manager.unlockWith(credential) {
                        calls++
                        UnlockResult.UserCancelled
                    }
                }
            runCurrent()
            release.complete(Unit)

            assertThat(first.await()).isEqualTo(UnlockOutcome.Success(token))
            assertThat(second.await()).isEqualTo(UnlockOutcome.Coalesced(UnlockOutcome.Success(token)))
            assertThat(calls).isEqualTo(1)
        }

    private class RecordingObserver(
        private val manager: UnlockManager,
    ) : LockObserver {
        override val priority = LockObserverPriority.LOW
        val unlocked = mutableListOf<Long>()
        val tokensAtNotification = mutableListOf<AuthorizationToken?>()

        override suspend fun onLocking(
            epoch: Long,
            budgetMillis: Long,
        ) = Unit

        override fun onLocked(epoch: Long) = Unit

        override fun onUnlocked(epoch: Long) {
            unlocked += epoch
            tokensAtNotification += manager.authorizationToken.value
        }
    }
}
