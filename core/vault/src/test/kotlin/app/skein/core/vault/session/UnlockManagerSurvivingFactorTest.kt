package app.skein.core.vault.session

import app.skein.core.model.AuthorizationToken
import app.skein.core.vault.key.UnlockResult
import app.skein.core.vault.key.VaultKeyProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UnlockManagerSurvivingFactorTest {
    private val biometric = VaultKeyProvider.Factor.BIOMETRIC
    private val credential = VaultKeyProvider.Factor.DEVICE_CREDENTIAL

    @Test
    fun `known invalidation derives opposite factor and publishes ordinary success without rewrap`() =
        runTest {
            for (invalidated in VaultKeyProvider.Factor.entries) {
                val provider = FakeVaultKeyProvider()
                val manager = UnlockManager(provider)
                manager.unlockWith(invalidated) { UnlockResult.KeyPermanentlyInvalidated(invalidated) }
                assertThat(manager.recoveryFactor.value).isEqualTo(invalidated)
                assertThat(manager.state.value).isEqualTo(UnlockState.RecoveryRequired)
                var usedFactor: VaultKeyProvider.Factor? = null
                val token = AuthorizationToken(201)

                val result =
                    manager.unlockSurvivingFactorWith { factor ->
                        usedFactor = factor
                        UnlockResult.Success(token)
                    }

                assertThat(usedFactor).isEqualTo(if (invalidated == biometric) credential else biometric)
                assertThat(result).isEqualTo(UnlockOutcome.Success(token))
                assertThat(manager.authorizationToken.value).isEqualTo(token)
                assertThat(manager.recoveryFactor.value).isNull()
                assertThat(provider.rewrapCallCount.get()).isEqualTo(0)
            }
        }

    @Test
    fun `survivor failures stay distinct and retain recovery context`() =
        runTest {
            val manager = UnlockManager(FakeVaultKeyProvider())
            manager.unlockWith(biometric) { UnlockResult.KeyPermanentlyInvalidated(biometric) }
            val cases =
                listOf(
                    UnlockResult.UserCancelled to UnlockOutcome.UserCancelled,
                    UnlockResult.KeyMaterialGone(credential) to UnlockOutcome.KeyMaterialGone(credential),
                    UnlockResult.KeyPermanentlyInvalidated(credential) to
                        UnlockOutcome.KeyPermanentlyInvalidated(credential),
                    UnlockResult.Failed("transient") to UnlockOutcome.Failed("transient"),
                    UnlockResult.DeviceLocked to UnlockOutcome.DeviceLocked,
                    UnlockResult.NotInitialised to UnlockOutcome.NotInitialised,
                )
            for ((providerResult, outcome) in cases) {
                assertThat(manager.unlockSurvivingFactorWith { providerResult }).isEqualTo(outcome)
                assertThat(manager.state.value).isEqualTo(UnlockState.RecoveryRequired)
                assertThat(manager.recoveryFactor.value).isEqualTo(biometric)
                assertThat(manager.authorizationToken.value).isNull()
            }
        }

    @Test
    fun `normal unlock cannot bypass recovery and unknown invalidation never guesses a factor`() =
        runTest {
            val manager = UnlockManager(FakeVaultKeyProvider())
            var calls = 0
            assertThat(
                manager.unlockSurvivingFactorWith {
                    calls++
                    UnlockResult.UserCancelled
                },
            ).isInstanceOf(UnlockOutcome.IllegalTransition::class.java)
            manager.unlockWith(biometric) { UnlockResult.KeyPermanentlyInvalidated(biometric) }
            assertThat(
                manager.unlockWith(credential) {
                    calls++
                    UnlockResult.UserCancelled
                },
            ).isInstanceOf(UnlockOutcome.IllegalTransition::class.java)
            manager.lockAndAwait(LockReason.USER_REQUESTED)
            assertThat(manager.recoveryFactor.value).isNull()
            manager.unlockWith(credential) { UnlockResult.Success(AuthorizationToken(202)) }
            manager.lockAndAwait(LockReason.KEY_INVALIDATED)
            assertThat(manager.state.value).isEqualTo(UnlockState.RecoveryRequired)
            assertThat(manager.recoveryFactor.value).isNull()
            assertThat(
                manager.unlockSurvivingFactorWith {
                    calls++
                    UnlockResult.UserCancelled
                },
            ).isInstanceOf(UnlockOutcome.IllegalTransition::class.java)
            assertThat(calls).isEqualTo(0)
        }

    @Test
    fun `key-invalidated lock preserves known recovery context while explicit cancel clears it`() =
        runTest {
            val manager = UnlockManager(FakeVaultKeyProvider())
            manager.unlockWith(biometric) { UnlockResult.KeyPermanentlyInvalidated(biometric) }
            manager.lockAndAwait(LockReason.KEY_INVALIDATED)
            assertThat(manager.state.value).isEqualTo(UnlockState.RecoveryRequired)
            assertThat(manager.recoveryFactor.value).isEqualTo(biometric)
            manager.lockAndAwait(LockReason.RECOVERY_CANCELLED)
            assertThat(manager.state.value).isEqualTo(UnlockState.Locked)
            assertThat(manager.recoveryFactor.value).isNull()
        }

    @Test
    fun `lock cancels late survivor success and rejects a new unlock admitted after lock request`() =
        runTest {
            val provider = FakeVaultKeyProvider()
            val manager = UnlockManager(provider, scope = backgroundScope)
            manager.unlockWith(biometric) { UnlockResult.KeyPermanentlyInvalidated(biometric) }
            val release = CompletableDeferred<Unit>()
            val candidate = ByteArray(32) { 4 }
            val attempt =
                async {
                    manager.unlockSurvivingFactorWith {
                        withContext(NonCancellable) {
                            release.await()
                            provider.master = candidate
                            UnlockResult.Success(AuthorizationToken(203))
                        }
                    }
                }
            runCurrent()
            manager.lock(LockReason.USER_REQUESTED)
            var lateAuthCalls = 0
            val late =
                async(start = CoroutineStart.UNDISPATCHED) {
                    manager.unlockWith(credential) {
                        lateAuthCalls++
                        UnlockResult.Success(AuthorizationToken(204))
                    }
                }
            release.complete(Unit)
            assertThat(attempt.await()).isEqualTo(UnlockOutcome.UserCancelled)
            assertThat(late.await()).isEqualTo(UnlockOutcome.UserCancelled)
            runCurrent()

            assertThat(lateAuthCalls).isEqualTo(0)
            assertThat(manager.state.value).isEqualTo(UnlockState.Locked)
            assertThat(manager.authorizationToken.value).isNull()
            assertThat(manager.recoveryFactor.value).isNull()
            assertThat(provider.currentKey()).isNull()
            assertThat(candidate).isEqualTo(ByteArray(32))
            manager.close()
        }

    @Test
    fun `caller cancellation retains invalidation context but clears any late unwrapped key`() =
        runTest {
            val provider = FakeVaultKeyProvider()
            val manager = UnlockManager(provider)
            manager.unlockWith(biometric) { UnlockResult.KeyPermanentlyInvalidated(biometric) }
            val release = CompletableDeferred<Unit>()
            val candidate = ByteArray(32) { 5 }
            val attempt =
                async {
                    manager.unlockSurvivingFactorWith {
                        withContext(NonCancellable) {
                            release.await()
                            provider.master = candidate
                            UnlockResult.Success(AuthorizationToken(205))
                        }
                    }
                }
            runCurrent()
            attempt.cancel()
            release.complete(Unit)
            attempt.join()

            assertThat(manager.state.value).isEqualTo(UnlockState.RecoveryRequired)
            assertThat(manager.recoveryFactor.value).isEqualTo(biometric)
            assertThat(manager.authorizationToken.value).isNull()
            assertThat(provider.currentKey()).isNull()
            assertThat(candidate).isEqualTo(ByteArray(32))
        }
}
