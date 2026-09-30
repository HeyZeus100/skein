package app.skein.core.vault.key

import android.os.Looper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import javax.crypto.Cipher

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidBiometricAuthenticatorTest {
    private class Prompt : AuthenticationPrompt {
        lateinit var callback: BiometricPrompt.AuthenticationCallback
        lateinit var info: BiometricPrompt.PromptInfo
        lateinit var crypto: BiometricPrompt.CryptoObject
        var calls = 0
        var cancels = 0

        override fun authenticate(
            info: BiometricPrompt.PromptInfo,
            crypto: BiometricPrompt.CryptoObject,
        ) {
            calls++
            this.info = info
            this.crypto = crypto
        }

        override fun cancelAuthentication() {
            cancels++
            callback.onAuthenticationError(BiometricPrompt.ERROR_CANCELED, "cancelled")
        }

        fun success(cipher: Cipher?) {
            callback.onAuthenticationSucceeded(
                BiometricPrompt.AuthenticationResult::class.java
                    .getDeclaredConstructor(BiometricPrompt.CryptoObject::class.java, Integer.TYPE)
                    .apply { isAccessible = true }
                    .newInstance(
                        cipher?.let { BiometricPrompt.CryptoObject(it) },
                        BiometricPrompt.AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL,
                    ),
            )
        }
    }

    private fun authenticator(prompt: Prompt) =
        AndroidBiometricAuthenticator(RuntimeEnvironment.getApplication()) { _, _, callback ->
            prompt.callback = callback
            prompt
        }

    private fun callerPrompt() =
        BiometricPrompt.PromptInfo
            .Builder()
            .setTitle("Unlock Skein")
            .setSubtitle("Authenticate to open Skein")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Cancel")
            .build()

    @Test
    fun `credential attempt binds the same cipher to only device credentials and completes once`() =
        runTest {
            val prompt = Prompt()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
            val result =
                async {
                    authenticator(
                        prompt,
                    ).authenticate(activity, callerPrompt(), VaultKeyProvider.Factor.DEVICE_CREDENTIAL, cipher)
                }
            runCurrent()

            assertThat(prompt.calls).isEqualTo(1)
            assertThat(prompt.info.allowedAuthenticators).isEqualTo(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            assertThat(prompt.info.negativeButtonText.toString()).isEmpty()
            assertThat(prompt.crypto.cipher).isSameInstanceAs(cipher)
            prompt.success(cipher)
            prompt.success(cipher)
            prompt.callback.onAuthenticationError(BiometricPrompt.ERROR_CANCELED, "late cancellation")

            assertThat((result.await() as AuthResult.Success).cipher).isSameInstanceAs(cipher)
            assertThat(prompt.cancels).isEqualTo(0)
        }

    @Test
    fun `cancellation dismisses the platform prompt and ignores late success and error callbacks`() =
        runTest {
            val prompt = Prompt()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
            var published = false
            val result =
                async {
                    authenticator(
                        prompt,
                    ).authenticate(activity, callerPrompt(), VaultKeyProvider.Factor.DEVICE_CREDENTIAL, cipher)
                    published = true
                }
            runCurrent()

            result.cancel()
            runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
            prompt.success(cipher)
            prompt.callback.onAuthenticationError(BiometricPrompt.ERROR_HW_UNAVAILABLE, "late error")
            result.join()

            assertThat(result.isCancelled).isTrue()
            assertThat(prompt.cancels).isEqualTo(1)
            assertThat(published).isFalse()
        }

    @Test
    fun `a missing or different successful cipher never authorizes the requested operation`() =
        runTest {
            for (returnedCipher in listOf(null, Cipher.getInstance("AES/GCM/NoPadding"))) {
                val prompt = Prompt()
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
                val result =
                    async {
                        authenticator(
                            prompt,
                        ).authenticate(activity, callerPrompt(), VaultKeyProvider.Factor.DEVICE_CREDENTIAL, cipher)
                    }
                runCurrent()

                prompt.success(returnedCipher)

                assertThat(result.await()).isInstanceOf(AuthResult.Error::class.java)
            }
        }

    @Test
    fun `a failed biometric match is nonterminal and user cancellation stays distinct`() =
        runTest {
            val prompt = Prompt()
            val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
            val result =
                async {
                    authenticator(prompt).authenticate(
                        activity,
                        callerPrompt(),
                        VaultKeyProvider.Factor.BIOMETRIC,
                        Cipher.getInstance("AES/GCM/NoPadding"),
                    )
                }
            runCurrent()
            prompt.callback.onAuthenticationFailed()
            assertThat(result.isCompleted).isFalse()

            prompt.callback.onAuthenticationError(BiometricPrompt.ERROR_USER_CANCELED, "cancelled")

            assertThat(result.await()).isEqualTo(AuthResult.UserCancelled)
        }
}
