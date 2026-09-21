// skein-2ige — fail-closed stand-in for the device `VaultKeyProvider`.
//
// `:app` cannot build the real provider yet: `VaultKeyProviderImpl`'s
// constructor and its `AndroidKeystoreFacade` / `AndroidBiometricAuthenticator`
// collaborators are `internal` to `:core:vault`, and `MasterKeyStorage` has
// no production backend (the wrapped master must also live OUTSIDE the
// SQLCipher file it keys — see bd `skein-txrh`, which owns the fix and the
// swap: `VaultServices.forDevice(keyProvider = <real>)`).
//
// Until then this object makes every unlock report `NotInitialised` —
// `BiometricUnlockScreen` renders that as "The vault has not been set up
// yet." — holds no key, and never lets the vault open. It is the same
// posture the DocumentsProvider has before `install`: closed.

package app.skein.vault

import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import app.skein.core.vault.key.RewrapResult
import app.skein.core.vault.key.SetupResult
import app.skein.core.vault.key.UnlockResult
import app.skein.core.vault.key.VaultKeyProvider

internal object UnprovisionedVaultKeyProvider : VaultKeyProvider {
    private const val REASON = "device VaultKeyProvider not available yet (skein-txrh)"

    override suspend fun setup(
        activity: FragmentActivity,
        prompt: BiometricPrompt.PromptInfo,
    ): SetupResult = SetupResult.Failed(REASON)

    override suspend fun unlock(
        activity: FragmentActivity,
        prompt: BiometricPrompt.PromptInfo,
        factor: VaultKeyProvider.Factor,
    ): UnlockResult = UnlockResult.NotInitialised

    override fun currentKey(): ByteArray? = null

    override fun lock() = Unit

    override suspend fun rewrapAfterInvalidation(
        activity: FragmentActivity,
        prompt: BiometricPrompt.PromptInfo,
        survivingFactor: VaultKeyProvider.Factor,
    ): RewrapResult = RewrapResult.Failed(REASON)
}
