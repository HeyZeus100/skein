// skein-3el (E3.I2) — `BiometricPrompt` abstraction.
//
// Wraps `androidx.biometric.BiometricPrompt` so JVM unit tests can drive
// success / cancel / lockout paths without instantiating the real prompt.
// The real implementation lives in `AndroidBiometricAuthenticator`; tests
// use `FakeBiometricAuthenticator` (test source set).

package app.skein.core.vault.key

import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher

/**
 * Presents a `BiometricPrompt` (or `AUTH_DEVICE_CREDENTIAL` prompt) bound
 * to a `CryptoObject(cipher)` and returns the authorised `Cipher` on
 * success. On success the returned `Cipher` is the SAME instance that was
 * passed in, now authorised for one `doFinal()` call (matching the
 * zero-second-validity Keystore semantics of `ATTACHMENT_ENCRYPTION.md`
 * §2.3).
 */
internal interface BiometricAuthenticator {
    /**
     * Reports whether the requested [factor] can currently authenticate.
     * On Android this is a thin wrapper over
     * `BiometricManager.canAuthenticate(...)`.
     */
    fun canAuthenticate(factor: VaultKeyProvider.Factor): CanAuthenticate

    /**
     * Presents [prompt] to the user on [activity], bound to [cipher] via a
     * `CryptoObject`. Suspends until the user authenticates, cancels, or
     * the OS reports an error. See
     * https://developer.android.com/reference/androidx/biometric/BiometricPrompt
     * for the underlying callback surface.
     */
    suspend fun authenticate(
        activity: FragmentActivity,
        prompt: BiometricPrompt.PromptInfo,
        factor: VaultKeyProvider.Factor,
        cipher: Cipher,
    ): AuthResult
}

/** Whether the requested factor is currently usable. */
internal sealed class CanAuthenticate {
    object Yes : CanAuthenticate()

    object NoneEnrolled : CanAuthenticate()

    object HardwareUnavailable : CanAuthenticate()

    data class Unknown(
        val code: Int,
    ) : CanAuthenticate()
}

/** Outcome of a single [BiometricAuthenticator.authenticate] call. */
internal sealed class AuthResult {
    /** The authorised `Cipher` — safe to `doFinal()` exactly once. */
    data class Success(
        val cipher: Cipher,
    ) : AuthResult()

    /** User dismissed the prompt (negative button, back, or timeout). */
    object UserCancelled : AuthResult()

    /**
     * `BiometricPrompt.AuthenticationCallback.onAuthenticationError` fired
     * with a non-cancel error code (lockout, hardware failure, ...). The
     * [code] is the `BiometricPrompt.ERROR_*` constant; [message] is the
     * user-facing string surfaced by the OS. Neither carries key material.
     */
    data class Error(
        val code: Int,
        val message: String,
    ) : AuthResult()
}
