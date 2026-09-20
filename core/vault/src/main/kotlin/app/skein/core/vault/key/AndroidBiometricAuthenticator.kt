// skein-3el (E3.I2) — real `BiometricPrompt` backend for
// [BiometricAuthenticator]. See that interface for the contract.

package app.skein.core.vault.key

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.crypto.Cipher
import kotlin.coroutines.resume

internal class AndroidBiometricAuthenticator(
    private val context: Context,
) : BiometricAuthenticator {
    override fun canAuthenticate(factor: VaultKeyProvider.Factor): CanAuthenticate {
        val authenticators =
            when (factor) {
                VaultKeyProvider.Factor.BIOMETRIC -> BiometricManager.Authenticators.BIOMETRIC_STRONG
                VaultKeyProvider.Factor.DEVICE_CREDENTIAL -> BiometricManager.Authenticators.DEVICE_CREDENTIAL
            }
        return when (BiometricManager.from(context).canAuthenticate(authenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> CanAuthenticate.Yes
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> CanAuthenticate.NoneEnrolled
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            -> CanAuthenticate.HardwareUnavailable
            else -> CanAuthenticate.Unknown(-1)
        }
    }

    override suspend fun authenticate(
        activity: FragmentActivity,
        prompt: BiometricPrompt.PromptInfo,
        factor: VaultKeyProvider.Factor,
        cipher: Cipher,
    ): AuthResult =
        suspendCancellableCoroutine { cont ->
            val executor = ContextCompat.getMainExecutor(context)
            val callback =
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        val authorized = result.cryptoObject?.cipher
                        if (authorized == null) {
                            cont.resume(AuthResult.Error(-1, "no cipher on success"))
                        } else {
                            cont.resume(AuthResult.Success(authorized))
                        }
                    }

                    override fun onAuthenticationError(
                        errorCode: Int,
                        errString: CharSequence,
                    ) {
                        val res =
                            when (errorCode) {
                                BiometricPrompt.ERROR_USER_CANCELED,
                                BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                                BiometricPrompt.ERROR_CANCELED,
                                -> AuthResult.UserCancelled
                                else -> AuthResult.Error(errorCode, errString.toString())
                            }
                        cont.resume(res)
                    }

                    override fun onAuthenticationFailed() {
                        // Do not resume — subsequent onAuthenticationError finalises
                        // the flow. onAuthenticationFailed is a retriable "wrong
                        // finger" event, not a terminal state.
                    }
                }
            val bp = BiometricPrompt(activity, executor, callback)
            bp.authenticate(prompt, BiometricPrompt.CryptoObject(cipher))
        }
}
