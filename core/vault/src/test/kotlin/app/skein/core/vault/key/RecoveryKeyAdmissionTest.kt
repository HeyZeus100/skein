package app.skein.core.vault.key

import app.skein.core.vault.lifecycle.VaultRecoveryExclusion
import app.skein.core.vault.session.FakeVaultKeyProvider
import app.skein.testing.TempDirRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class RecoveryKeyAdmissionTest {
    @get:Rule val temp = TempDirRule()

    @Test
    fun `authentication and retained master both exclude recovery`() =
        runTest {
            val exclusion = VaultRecoveryExclusion.forDirectory(temp.root)
            val delegate = FakeVaultKeyProvider()
            val provider = RecoveryKeyAdmission(delegate::currentKey, delegate::lock, exclusion)
            val finish = CompletableDeferred<Unit>()
            val pending =
                async {
                    provider.guarded("refused", "cancelled") {
                        finish.await()
                        delegate.master = ByteArray(32) { 8 }
                        "accepted"
                    }
                }
            runCurrent()
            assertThat(exclusion.acquireRecovery()).isNull()
            finish.complete(Unit)
            assertThat(pending.await()).isEqualTo("accepted")
            assertThat(provider.currentKey()).isEqualTo(ByteArray(32) { 8 })
            assertThat(exclusion.acquireRecovery()).isNull()
            provider.lock()
            assertThat(provider.currentKey()).isNull()
            exclusion.acquireRecovery()!!.close()
        }

    @Test
    fun `lock blocks late completion and keeps reservation until operation finishes`() =
        runTest {
            val exclusion = VaultRecoveryExclusion.forDirectory(temp.root)
            val delegate = FakeVaultKeyProvider()
            val provider = RecoveryKeyAdmission(delegate::currentKey, delegate::lock, exclusion)
            val finish = CompletableDeferred<Unit>()
            val late = ByteArray(32) { 7 }
            val pending =
                async {
                    provider.guarded("refused", "cancelled") {
                        finish.await()
                        delegate.master = late
                        assertThat(provider.currentKey()).isNull()
                        "accepted"
                    }
                }
            runCurrent()
            provider.lock()
            assertThat(exclusion.acquireRecovery()).isNull()
            finish.complete(Unit)
            assertThat(pending.await()).isEqualTo("cancelled")
            assertThat(late).isEqualTo(ByteArray(32))
            assertThat(provider.currentKey()).isNull()
            exclusion.acquireRecovery()!!.close()
        }

    @Test
    fun `recovery refuses auth before delegate and lock revokes recovery without releasing it`() =
        runTest {
            val exclusion = VaultRecoveryExclusion.forDirectory(temp.root)
            val provider = FakeVaultKeyProvider().let { RecoveryKeyAdmission(it::currentKey, it::lock, exclusion) }
            exclusion.acquireRecovery()!!.use {
                assertThat(
                    provider.guarded("refused", "cancelled") { error("must not authenticate") },
                ).isEqualTo("refused")
                provider.lock()
                assertThat(exclusion.admit()).isNull()
            }
            exclusion.acquireRecovery()!!.close()
        }

    @Test
    fun `cancelled operation zeros delegate key and releases admission`() =
        runTest {
            val exclusion = VaultRecoveryExclusion.forDirectory(temp.root)
            val delegate = FakeVaultKeyProvider()
            val provider = RecoveryKeyAdmission(delegate::currentKey, delegate::lock, exclusion)
            val bytes = ByteArray(32) { 4 }
            val pending =
                async {
                    provider.guarded("refused", "cancelled") {
                        delegate.master = bytes
                        CompletableDeferred<Unit>().await()
                    }
                }
            runCurrent()
            pending.cancel()
            pending.join()
            assertThat(bytes).isEqualTo(ByteArray(32))
            assertThat(provider.currentKey()).isNull()
            exclusion.acquireRecovery()!!.close()
        }

    @Test
    fun `production provider shared orchestration guards setup unlock and live master`() =
        runTest {
            val exclusion = VaultRecoveryExclusion.forDirectory(temp.root)
            val provider =
                VaultKeyProviderImpl(
                    FakeKeystoreFacade(),
                    FakeBiometricAuthenticator(),
                    FakeMasterKeyStorage(),
                    recoveryExclusion = exclusion,
                )
            exclusion.acquireRecovery()!!.use {
                assertThat(provider.setupNoUi()).isInstanceOf(SetupResult.Failed::class.java)
                assertThat(
                    provider.unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC),
                ).isInstanceOf(UnlockResult.Failed::class.java)
                assertThat(
                    provider.rewrapNoUi(VaultKeyProvider.Factor.BIOMETRIC),
                ).isInstanceOf(RewrapResult.Failed::class.java)
            }
            assertThat(provider.setupNoUi()).isInstanceOf(SetupResult.Success::class.java)
            assertThat(
                provider.unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC),
            ).isInstanceOf(UnlockResult.Success::class.java)
            assertThat(exclusion.acquireRecovery()).isNull()
            provider.lock()
            exclusion.acquireRecovery()!!.close()
        }

    @Test
    fun `lock invalidates an operation already queued behind authentication`() =
        runTest {
            val exclusion = VaultRecoveryExclusion.forDirectory(temp.root)
            val delegate = FakeVaultKeyProvider()
            val provider = RecoveryKeyAdmission(delegate::currentKey, delegate::lock, exclusion)
            val finish = CompletableDeferred<Unit>()
            val first =
                async {
                    provider.guarded("refused", "cancelled") {
                        finish.await()
                        "accepted"
                    }
                }
            runCurrent()
            val second = async { provider.guarded("refused", "cancelled") { error("stale queued authentication") } }
            runCurrent()
            provider.lock()
            finish.complete(Unit)
            assertThat(first.await()).isEqualTo("cancelled")
            assertThat(second.await()).isEqualTo("cancelled")
            exclusion.acquireRecovery()!!.close()
        }
}
