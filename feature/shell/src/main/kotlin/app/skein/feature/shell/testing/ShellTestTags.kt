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

    // skein-ank2: VaultSetupScreen tags.

    /** Root container of `app.skein.feature.shell.auth.VaultSetupScreen`. */
    const val VAULT_SETUP_ROOT = "vault_setup_root"

    /** Starts `VaultKeyProvider.setup` (the explanation's call to action). */
    const val VAULT_SETUP_BEGIN_BUTTON = "vault_setup_begin_button"

    /** Shown while the setup wrap prompts are on screen. */
    const val VAULT_SETUP_PROGRESS = "vault_setup_progress"

    /** User-facing message shown when setup stopped short (cancel / failure / no biometric). */
    const val VAULT_SETUP_MESSAGE = "vault_setup_message"

    /** Re-runs setup after a cancellation, failure, or enrolment detour. */
    const val VAULT_SETUP_RETRY_BUTTON = "vault_setup_retry_button"

    /** Opens the OS biometric-enrolment settings when no strong biometric is enrolled. */
    const val VAULT_SETUP_ENROL_BUTTON = "vault_setup_enrol_button"

    // skein-v9g (E3.I11): "Restore from a passphrase export" on VaultSetupScreen.

    /** Opens the document picker for a `skein-recovery-*.json` file. */
    const val VAULT_RESTORE_BUTTON = "vault_restore_button"

    /** Passphrase entry for the chosen recovery file. */
    const val VAULT_RESTORE_PASSPHRASE_FIELD = "vault_restore_passphrase_field"

    /** Runs the import + `setup(existingMaster)` attempt. */
    const val VAULT_RESTORE_SUBMIT_BUTTON = "vault_restore_submit_button"

    /** Abandons the restore and returns to the normal setup call to action. */
    const val VAULT_RESTORE_CANCEL_BUTTON = "vault_restore_cancel_button"

    /** Shown while the key derivation and the wrap prompts are running. */
    const val VAULT_RESTORE_PROGRESS = "vault_restore_progress"

    /** User-facing message after a failed restore attempt. */
    const val VAULT_RESTORE_MESSAGE = "vault_restore_message"
}
