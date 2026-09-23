// skein-f9ls — unit coverage for `promptInfoForFactor`
// (`AndroidBiometricAuthenticator.kt`), the pure derivation that fixes the
// Pixel 9 Pro Fold hardware bug: a single `BIOMETRIC_STRONG or
// DEVICE_CREDENTIAL` `PromptInfo` let the device-credential-bound wrap
// prompt be satisfied with a fingerprint, which keystore2 rejects. Robolectric
// is required here (not a bare JVM test, `RobolectricTestRunner`, sdk 34 —
// bd memory `robolectric-sdk37-needs-java21`, matching
// `BiometricUnlockResetAffordanceTest`'s setup) because
// `BiometricPrompt.PromptInfo.Builder.build()` validates the authenticator
// combination against `Build.VERSION.SDK_INT`, which a bare
// `unitTests.isReturnDefaultValues = true` JVM test reads as `0` — every
// combination would then fail validation, including the ones under test.
package app.skein.core.vault.key

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PromptInfoForFactorTest {
    private fun promptWith(
        title: String = "Set up your vault",
        subtitle: String? = null,
        description: String? = null,
        negativeButtonText: String? = null,
        allowedAuthenticators: Int =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
    ): BiometricPrompt.PromptInfo {
        val builder = BiometricPrompt.PromptInfo.Builder().setTitle(title)
        subtitle?.let(builder::setSubtitle)
        description?.let(builder::setDescription)
        negativeButtonText?.let(builder::setNegativeButtonText)
        return builder.setAllowedAuthenticators(allowedAuthenticators).build()
    }

    // ---- allowed authenticators ----------------------------------------

    @Test
    fun `BIOMETRIC factor allows only BIOMETRIC_STRONG`() {
        // Arrange
        val caller = promptWith()
        // Act
        val derived = promptInfoForFactor(caller, VaultKeyProvider.Factor.BIOMETRIC)
        // Assert
        assertThat(derived.allowedAuthenticators).isEqualTo(BiometricManager.Authenticators.BIOMETRIC_STRONG)
    }

    @Test
    fun `BIOMETRIC factor never allows DEVICE_CREDENTIAL`() {
        // Arrange
        val caller = promptWith()
        // Act
        val derived = promptInfoForFactor(caller, VaultKeyProvider.Factor.BIOMETRIC)
        // Assert
        assertThat(derived.allowedAuthenticators and BiometricManager.Authenticators.DEVICE_CREDENTIAL).isEqualTo(0)
    }

    @Test
    fun `DEVICE_CREDENTIAL factor allows only DEVICE_CREDENTIAL`() {
        // Arrange
        val caller = promptWith()
        // Act
        val derived = promptInfoForFactor(caller, VaultKeyProvider.Factor.DEVICE_CREDENTIAL)
        // Assert
        assertThat(derived.allowedAuthenticators).isEqualTo(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
    }

    @Test
    fun `DEVICE_CREDENTIAL factor never allows BIOMETRIC_STRONG`() {
        // Arrange
        val caller = promptWith()
        // Act
        val derived = promptInfoForFactor(caller, VaultKeyProvider.Factor.DEVICE_CREDENTIAL)
        // Assert
        assertThat(derived.allowedAuthenticators and BiometricManager.Authenticators.BIOMETRIC_STRONG).isEqualTo(0)
    }

    // ---- negative button rule -------------------------------------------

    @Test
    fun `BIOMETRIC factor always carries a non-empty negative button`() {
        // Arrange — caller set none (androidx forbids one alongside DEVICE_CREDENTIAL).
        val caller = promptWith(negativeButtonText = null)
        // Act
        val derived = promptInfoForFactor(caller, VaultKeyProvider.Factor.BIOMETRIC)
        // Assert
        assertThat(derived.negativeButtonText.toString()).isNotEmpty()
    }

    @Test
    fun `BIOMETRIC factor keeps the caller's negative button text when it set one`() {
        // Arrange — mirrors BiometricUnlockScreen's own PromptInfo: androidx
        // forbids a negative button alongside DEVICE_CREDENTIAL, so a caller
        // that sets one must already be BIOMETRIC_STRONG-only.
        val caller =
            promptWith(
                negativeButtonText = "Use screen lock instead",
                allowedAuthenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG,
            )
        // Act
        val derived = promptInfoForFactor(caller, VaultKeyProvider.Factor.BIOMETRIC)
        // Assert
        assertThat(derived.negativeButtonText.toString()).isEqualTo("Use screen lock instead")
    }

    @Test
    fun `DEVICE_CREDENTIAL factor never carries a negative button`() {
        // Arrange
        val caller = promptWith()
        // Act
        val derived = promptInfoForFactor(caller, VaultKeyProvider.Factor.DEVICE_CREDENTIAL)
        // Assert — androidx's own builder would have thrown at `build()`
        // above had a negative button leaked through alongside
        // DEVICE_CREDENTIAL, so reaching this line is itself a partial
        // proof. `PromptInfo.getNegativeButtonText()` substitutes "" for an
        // unset field (never `null`), so assert emptiness, not nullity.
        assertThat(derived.negativeButtonText.toString()).isEmpty()
    }

    // ---- title / description preserved verbatim -------------------------

    @Test
    fun `title is preserved verbatim for both factors`() {
        // Arrange
        val caller = promptWith(title = "Set up your vault")
        // Act / Assert
        assertThat(promptInfoForFactor(caller, VaultKeyProvider.Factor.BIOMETRIC).title.toString())
            .isEqualTo("Set up your vault")
        assertThat(promptInfoForFactor(caller, VaultKeyProvider.Factor.DEVICE_CREDENTIAL).title.toString())
            .isEqualTo("Set up your vault")
    }

    @Test
    fun `description is preserved verbatim when the caller set one`() {
        // Arrange
        val caller = promptWith(description = "Nothing leaves your device.")
        // Act
        val derived = promptInfoForFactor(caller, VaultKeyProvider.Factor.BIOMETRIC)
        // Assert
        assertThat(derived.description.toString()).isEqualTo("Nothing leaves your device.")
    }

    @Test
    fun `description is absent when the caller set none`() {
        // Arrange
        val caller = promptWith(description = null)
        // Act
        val derived = promptInfoForFactor(caller, VaultKeyProvider.Factor.BIOMETRIC)
        // Assert
        assertThat(derived.description).isNull()
    }

    // ---- subtitle: caller's subtitle wins, factor default is the fallback

    @Test
    fun `caller's subtitle is preserved when it set one, for BIOMETRIC`() {
        // Arrange — e.g. BiometricUnlockScreen's customisable subtitle.
        val caller = promptWith(subtitle = "Authenticate to open your vault")
        // Act
        val derived = promptInfoForFactor(caller, VaultKeyProvider.Factor.BIOMETRIC)
        // Assert
        assertThat(derived.subtitle.toString()).isEqualTo("Authenticate to open your vault")
    }

    @Test
    fun `caller's subtitle is preserved when it set one, for DEVICE_CREDENTIAL`() {
        // Arrange
        val caller = promptWith(subtitle = "Confirm to recover your vault")
        // Act
        val derived = promptInfoForFactor(caller, VaultKeyProvider.Factor.DEVICE_CREDENTIAL)
        // Assert
        assertThat(derived.subtitle.toString()).isEqualTo("Confirm to recover your vault")
    }

    @Test
    fun `BIOMETRIC factor falls back to a step-1 subtitle when the caller set none`() {
        // Arrange — VaultSetupScreen's promptInfo, which sets no subtitle.
        val caller = promptWith(subtitle = null)
        // Act
        val derived = promptInfoForFactor(caller, VaultKeyProvider.Factor.BIOMETRIC)
        // Assert
        assertThat(derived.subtitle.toString()).isEqualTo("Step 1 of 2: confirm with your fingerprint")
    }

    @Test
    fun `DEVICE_CREDENTIAL factor falls back to a step-2 subtitle when the caller set none`() {
        // Arrange
        val caller = promptWith(subtitle = null)
        // Act
        val derived = promptInfoForFactor(caller, VaultKeyProvider.Factor.DEVICE_CREDENTIAL)
        // Assert
        assertThat(derived.subtitle.toString())
            .isEqualTo("Step 2 of 2: confirm with your device PIN, pattern or password")
    }
}
