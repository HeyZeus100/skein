package app.skein.core.vault.key

import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.Cipher

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CredentialSurvivorUnlockTest {
    @Test
    fun `missing biometric and cancelled then successful credential leave envelope and survivor unchanged`() =
        runTest {
            val keys = FakeKeystoreFacade()
            val store = FakeMasterKeyStorage()
            var initialWrites = 0
            val storage =
                object : MasterKeyStorage by store {
                    override fun writeInitial(row: MasterKeyRow): Int {
                        initialWrites++
                        return store.writeInitial(row)
                    }
                }
            var cancels = true
            val factors = mutableListOf<VaultKeyProvider.Factor>()
            val auth =
                object : BiometricAuthenticator {
                    override fun canAuthenticate(factor: VaultKeyProvider.Factor) = CanAuthenticate.Yes

                    override suspend fun authenticate(
                        activity: FragmentActivity,
                        prompt: BiometricPrompt.PromptInfo,
                        factor: VaultKeyProvider.Factor,
                        cipher: Cipher,
                    ): AuthResult {
                        factors += factor
                        return if (cancels) AuthResult.UserCancelled else AuthResult.Success(cipher)
                    }
                }
            val provider = VaultKeyProviderImpl(keys, auth, storage)
            val master = ByteArray(32) { (it + 1).toByte() }
            assertThat(provider.setupNoUi(master.copyOf())).isInstanceOf(SetupResult.Success::class.java)
            provider.lock()
            keys.deleteEntry(VaultKeyProviderImpl.ALIAS_BIOMETRIC)
            val envelopeBefore = storage.readActive()
            val createsBefore = keys.createCalls.toList()
            val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
            val prompt =
                BiometricPrompt.PromptInfo
                    .Builder()
                    .setTitle("Unlock")
                    .setNegativeButtonText("Cancel")
                    .build()

            assertThat(provider.unlock(activity, prompt, VaultKeyProvider.Factor.BIOMETRIC))
                .isEqualTo(UnlockResult.KeyMaterialGone(VaultKeyProvider.Factor.BIOMETRIC))
            assertThat(factors).isEmpty()
            assertThat(provider.unlock(activity, prompt, VaultKeyProvider.Factor.DEVICE_CREDENTIAL))
                .isEqualTo(UnlockResult.UserCancelled)
            assertThat(provider.currentKey()).isNull()
            cancels = false
            assertThat(provider.unlock(activity, prompt, VaultKeyProvider.Factor.DEVICE_CREDENTIAL))
                .isInstanceOf(UnlockResult.Success::class.java)
            val recovered = provider.currentKey()!!
            assertThat(recovered).isEqualTo(master)
            provider.lock()

            assertThat(recovered).isEqualTo(ByteArray(32))
            assertThat(
                factors,
            ).containsExactly(VaultKeyProvider.Factor.DEVICE_CREDENTIAL, VaultKeyProvider.Factor.DEVICE_CREDENTIAL)
            assertThat(keys.createCalls).containsExactlyElementsIn(createsBefore)
            assertThat(keys.containsAlias(VaultKeyProviderImpl.ALIAS_BIOMETRIC)).isFalse()
            assertThat(keys.containsAlias(VaultKeyProviderImpl.ALIAS_CREDENTIAL)).isTrue()
            assertThat(storage.readActive()).isEqualTo(envelopeBefore)
            assertThat(initialWrites).isEqualTo(1)
            assertThat(store.rewrapCalls).isEmpty()
            // A fresh decrypt proves the surviving alias still wraps the same master.
            assertThat(
                provider.unlockNoUi(VaultKeyProvider.Factor.DEVICE_CREDENTIAL),
            ).isInstanceOf(UnlockResult.Success::class.java)
            assertThat(provider.currentKey()).isEqualTo(master)
            provider.lock()
        }
}
