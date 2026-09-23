// skein-3el (E3.I2) — real `BiometricPrompt` backend for
// [BiometricAuthenticator]. See that interface for the contract.
//
// skein-f9ls (hardware-verified on a Pixel 9 Pro Fold): `authenticate` used
// to present the caller's `PromptInfo` unchanged for both Layer-0 factors.
// `VaultSetupScreen` built ONE `PromptInfo` with
// `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` (androidx requires that
// combination — or a negative button — to build at all), so the SAME
// authenticator set was offered for the credential-bound wrap prompt. On
// the Fold the system sheet showed the fingerprint sensor first for that
// second prompt; the resulting FINGERPRINT auth token does not satisfy the
// PASSWORD-bound per-op key (`skein_master_cred_v1`), so keystore2 rejected
// it with `KEY_USER_NOT_AUTHENTICATED` and setup failed. `promptInfoForFactor`
// below derives a narrowed `PromptInfo` per [VaultKeyProvider.Factor] so the
// OS can never offer the wrong authenticator for either wrap/unlock/rewrap
// prompt — every call site funnels through this one function.

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
            val derivedPrompt = promptInfoForFactor(prompt, factor)
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
            bp.authenticate(derivedPrompt, BiometricPrompt.CryptoObject(cipher))
        }
}

/**
 * Narrows [caller]'s `PromptInfo` to exactly the authenticator [factor]'s
 * Layer-0 alias requires (skein-f9ls). This is the ONE place that decides
 * what `allowedAuthenticators` / negative-button combination reaches
 * `BiometricPrompt` — every `VaultKeyProviderImpl` entry point (setup,
 * unlock, rewrap) calls [BiometricAuthenticator.authenticate] with the same
 * `prompt` regardless of factor, so deriving it here covers all three paths
 * uniformly:
 *
 *  - [VaultKeyProvider.Factor.BIOMETRIC]: `BIOMETRIC_STRONG` only. androidx
 *    requires a non-empty negative button whenever `DEVICE_CREDENTIAL` is
 *    not among the allowed authenticators, so one is always set — [caller]'s
 *    own negative button text if it set one (e.g. `BiometricUnlockScreen`'s
 *    customisable "Cancel"), else [DEFAULT_NEGATIVE_BUTTON_TEXT].
 *  - [VaultKeyProvider.Factor.DEVICE_CREDENTIAL]: `DEVICE_CREDENTIAL` only,
 *    with NO negative button — androidx's builder throws if one is set
 *    alongside `DEVICE_CREDENTIAL`. A `CryptoObject` bound to a
 *    credential-only prompt requires API 30+, which is this app's minSdk.
 *
 * [caller]'s title and description are always copied verbatim. Its subtitle
 * is copied too when it set one — `BiometricUnlockScreen` and the rewrap
 * path always do, so their wording is unaffected by this function — and
 * otherwise falls back to a factor-specific default that tells the user
 * which of setup's two prompts is showing ("Step 1 of 2" / "Step 2 of 2").
 * `VaultSetupScreen` deliberately leaves its subtitle unset so it gets that
 * default (skein-f9ls item 2), rather than building two different
 * `PromptInfo` objects itself.
 */
internal fun promptInfoForFactor(
    caller: BiometricPrompt.PromptInfo,
    factor: VaultKeyProvider.Factor,
): BiometricPrompt.PromptInfo {
    val builder =
        BiometricPrompt.PromptInfo
            .Builder()
            .setTitle(caller.title)
            .setSubtitle(caller.subtitle ?: defaultSubtitleFor(factor))
    caller.description?.let(builder::setDescription)
    return when (factor) {
        VaultKeyProvider.Factor.BIOMETRIC ->
            builder
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText(
                    caller.negativeButtonText?.takeIf { it.isNotEmpty() } ?: DEFAULT_NEGATIVE_BUTTON_TEXT,
                ).build()
        VaultKeyProvider.Factor.DEVICE_CREDENTIAL ->
            builder
                .setAllowedAuthenticators(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build()
    }
}

private fun defaultSubtitleFor(factor: VaultKeyProvider.Factor): String =
    when (factor) {
        VaultKeyProvider.Factor.BIOMETRIC -> "Step 1 of 2: confirm with your fingerprint"
        VaultKeyProvider.Factor.DEVICE_CREDENTIAL -> "Step 2 of 2: confirm with your device PIN, pattern or password"
    }

private const val DEFAULT_NEGATIVE_BUTTON_TEXT = "Cancel"
