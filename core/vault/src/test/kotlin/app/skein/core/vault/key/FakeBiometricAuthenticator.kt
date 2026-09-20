// skein-3el (E3.I2) — trivial biometric fake. Unit tests bypass the
// biometric prompt entirely via `VaultKeyProviderImpl.*NoUi(...)`, so
// this fake exists only to satisfy the constructor signature — it's
// never actually invoked from a `*NoUi` test path.

package app.skein.core.vault.key

import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher

internal class FakeBiometricAuthenticator(
    var canAuth: CanAuthenticate = CanAuthenticate.Yes,
) : BiometricAuthenticator {
    override fun canAuthenticate(factor: VaultKeyProvider.Factor): CanAuthenticate = canAuth

    override suspend fun authenticate(
        activity: FragmentActivity,
        prompt: BiometricPrompt.PromptInfo,
        factor: VaultKeyProvider.Factor,
        cipher: Cipher,
    ): AuthResult = AuthResult.Success(cipher)
}
