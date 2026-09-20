package app.skein.feature.shell.testing

/**
 * Stable test tags for Compose UI tests in the shell layer.
 * These allow tests to locate shell elements by a stable identifier
 * rather than brittle text assertions that break when display strings change.
 */
object ShellTestTags {
    /**
     * Root container of the Skein shell ([SkeinApp]).
     * Used to verify the shell has been rendered without depending on
     * specific display text or UI layout details.
     */
    const val SKEIN_SHELL_ROOT = "skein_shell_root"

    // skein-ugo (E3.I4): BiometricUnlockScreen tags.

    /** Root container of `app.skein.feature.shell.auth.BiometricUnlockScreen`. */
    const val BIOMETRIC_UNLOCK_ROOT = "biometric_unlock_root"

    /** Shown while a `BiometricPrompt` is on screen or the unwrap is running. */
    const val BIOMETRIC_UNLOCK_PROGRESS = "biometric_unlock_progress"

    /** User-facing message shown on cancellation/error, before a retry. */
    const val BIOMETRIC_UNLOCK_MESSAGE = "biometric_unlock_message"

    /** Re-presents the biometric prompt after a cancellation/error. */
    const val BIOMETRIC_UNLOCK_RETRY_BUTTON = "biometric_unlock_retry_button"
}
