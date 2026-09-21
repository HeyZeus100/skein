// skein-v9g (E3.I11) — the "Restore from a passphrase export" half of the
// first-run screen: the recovery path out of `ATTACHMENT_ENCRYPTION.md`
// §3.8 ("both Layer-0 factors invalidated" → no automated recovery) and the
// device-migration path.
//
// Flow, once the user has picked a file with `ACTION_OPEN_DOCUMENT`:
//
//   file bytes + passphrase
//     → PassphraseKeyExport.import  (600 000 PBKDF2 iterations, AES-256-GCM)
//     → VaultKeyProvider.setup(activity, prompt, existingMaster = master)
//     → the host routes to unlock, and `bringUp()` opens the EXISTING vault.db
//
// The second step is what makes this a restore rather than a fresh start:
// `setup(existingMaster)` adopts the recovered 32 bytes, so the `vault.db`
// and attachments already on disk stay decryptable. See
// `VaultKeyProviderImportTest.importingAfterAliasAndEnvelopeLoss…`.
//
// Sibling of `VaultSetupState`, and the same shape: a plain `@Stable` class
// with the Android-typed call behind a `suspend` seam, so the whole
// mapping is JVM-testable (`VaultRestoreStateTest`) without Robolectric.
//
// Key material discipline. The recovered master exists only inside
// [restoreWith], is handed straight to `setup`, and is zeroed in a
// `finally` before that function returns — it never reaches Compose state,
// a log line, or a result type. The passphrase `CharArray` is likewise
// zeroed by this state holder after the attempt; the `String` the text
// field necessarily holds is dropped but, being immutable, cannot be wiped
// (a limitation of Compose's text APIs, noted rather than papered over —
// the process is `FLAG_SECURE` and the field is a `SecureTextField`).

package app.skein.feature.shell.auth

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skein.core.model.SkeinLog
import app.skein.core.vault.key.PassphraseKeyExport
import app.skein.core.vault.key.RecoveryFailedException
import app.skein.core.vault.key.SetupResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Outcome of one restore attempt. Carries no key or passphrase material. */
public sealed class RestoreResult {
    /** The recovered master was adopted; [strongBoxBacked] mirrors [SetupResult]. */
    public data class Provisioned(
        public val strongBoxBacked: Boolean,
    ) : RestoreResult()

    /** The passphrase did not unlock the file, or the file was altered. Retryable. */
    public object WrongPassphrase : RestoreResult()

    /** The chosen file is not a readable Skein recovery export. Retryable with another file. */
    public object UnreadableFile : RestoreResult()

    /** The file was written by a newer Skein, or asks for parameters this build refuses. */
    public object UnsupportedFile : RestoreResult()

    /** An envelope already exists on this device, so setup was refused. The host routes to unlock. */
    public object AlreadyInitialised : RestoreResult()

    /** The user dismissed one of the wrap prompts. Nothing was persisted. */
    public object Cancelled : RestoreResult()

    /** Any other failure. [reason] is a diagnostic, never shown verbatim. */
    public data class Failed(
        public val reason: String,
    ) : RestoreResult()
}

/**
 * Imports [fileBytes] under [passphrase] and hands the recovered master to
 * [setup], mapping both stages onto one [RestoreResult].
 *
 * Neither argument is mutated — the caller owns wiping them. The recovered
 * master IS owned here and is zeroed before returning, on every path.
 */
internal suspend fun restoreWith(
    fileBytes: ByteArray,
    passphrase: CharArray,
    setup: suspend (ByteArray) -> SetupResult,
): RestoreResult {
    val master =
        try {
            PassphraseKeyExport.import(fileBytes, passphrase)
        } catch (e: RecoveryFailedException) {
            return when (e.reason) {
                RecoveryFailedException.Reason.WRONG_PASSPHRASE -> RestoreResult.WrongPassphrase
                RecoveryFailedException.Reason.MALFORMED -> RestoreResult.UnreadableFile
                RecoveryFailedException.Reason.UNSUPPORTED_VERSION,
                RecoveryFailedException.Reason.WEAK_PARAMETERS,
                -> RestoreResult.UnsupportedFile
            }
        }
    return try {
        when (val result = setup(master)) {
            is SetupResult.Success -> RestoreResult.Provisioned(result.strongBoxBacked)
            is SetupResult.StrongBoxUnavailableFallback -> RestoreResult.Provisioned(false)
            SetupResult.AlreadyInitialised -> RestoreResult.AlreadyInitialised
            SetupResult.UserCancelled -> RestoreResult.Cancelled
            SetupResult.NoBiometricEnrolled -> RestoreResult.Failed(NO_BIOMETRIC_REASON)
            is SetupResult.Failed ->
                if (EnvelopeUnreadable.matches(result.reason)) {
                    RestoreResult.AlreadyInitialised
                } else {
                    RestoreResult.Failed(result.reason)
                }
        }
    } finally {
        master.fill(0)
    }
}

/** UI-visible phase of the restore flow. Carries no key or passphrase material. */
public sealed class VaultRestoreUiState {
    /** Nothing started; the setup screen shows its normal call to action. */
    public object Idle : VaultRestoreUiState()

    /** The document picker is open. */
    public object ChoosingFile : VaultRestoreUiState()

    /** A file is in hand; the passphrase field is on screen. [message] is a prior failure, if any. */
    public data class EnteringPassphrase(
        public val message: String? = null,
    ) : VaultRestoreUiState()

    /**
     * The 600 000-iteration derivation and the wrap prompts are running.
     * The screen shows a progress indicator: on real hardware the KDF alone
     * is seconds, and an unexplained freeze would read as a crash.
     */
    public object InProgress : VaultRestoreUiState()
}

/**
 * Drives one restore attempt at a time.
 *
 *  - [beginFileChoice] → [VaultRestoreUiState.ChoosingFile]; the screen
 *    launches `ACTION_OPEN_DOCUMENT` and calls [onFileChosen] (or
 *    [onFileChoiceCancelled]) with the result;
 *  - [submit] runs [restoreWith] against [runSetup] and routes:
 *    [RestoreResult.Provisioned] → [onProvisioned],
 *    [RestoreResult.AlreadyInitialised] → [onAlreadyInitialised],
 *    everything else back to [VaultRestoreUiState.EnteringPassphrase] with
 *    a user-facing message.
 */
@Stable
public class VaultRestoreState(
    private val scope: CoroutineScope,
    private val runSetup: suspend (existingMaster: ByteArray) -> SetupResult,
    private val onProvisioned: (strongBoxBacked: Boolean) -> Unit,
    private val onAlreadyInitialised: () -> Unit,
) {
    public var uiState: VaultRestoreUiState by mutableStateOf(VaultRestoreUiState.Idle)
        private set

    /**
     * The passphrase the field currently holds. Never logged; cleared on
     * every terminal transition. A `String` because that is what Compose's
     * text field API takes — see this file's header on what that costs.
     */
    public var passphrase: String by mutableStateOf("")

    private var fileBytes: ByteArray? = null

    /** Whether the Restore button should be enabled — a file is in hand and something has been typed. */
    public val canSubmit: Boolean
        get() = uiState is VaultRestoreUiState.EnteringPassphrase && passphrase.isNotEmpty()

    /** Moves to [VaultRestoreUiState.ChoosingFile]; the screen owns launching the picker. */
    public fun beginFileChoice() {
        if (uiState is VaultRestoreUiState.InProgress) return
        clearFile()
        passphrase = ""
        uiState = VaultRestoreUiState.ChoosingFile
    }

    /** The picker returned [bytes]. */
    public fun onFileChosen(bytes: ByteArray) {
        clearFile()
        fileBytes = bytes
        uiState = VaultRestoreUiState.EnteringPassphrase()
    }

    /** The picker was dismissed, or the chosen document could not be read at all. */
    public fun onFileChoiceCancelled() {
        clearFile()
        uiState = VaultRestoreUiState.Idle
    }

    /** Abandons the restore and returns the screen to its normal setup call to action. */
    public fun cancel() {
        if (uiState is VaultRestoreUiState.InProgress) return
        clearFile()
        passphrase = ""
        uiState = VaultRestoreUiState.Idle
    }

    /** Runs one attempt. A call while one is in flight is ignored — never two sets of wrap prompts. */
    public fun submit() {
        if (uiState !is VaultRestoreUiState.EnteringPassphrase) return
        val bytes = fileBytes ?: return
        val typed = passphrase.toCharArray()
        uiState = VaultRestoreUiState.InProgress
        scope.launch {
            val result =
                try {
                    restoreWith(bytes, typed, runSetup)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    // Every expected outcome is a typed result; anything thrown
                    // is a bug and its message is not for users.
                    RestoreResult.Failed("unexpected")
                } finally {
                    typed.fill('\u0000')
                }
            apply(result)
        }
    }

    internal fun apply(result: RestoreResult) {
        // Counts only (spec §9): the outcome's variant name, which is a fixed
        // string from this file. No file bytes, no passphrase, no key, no
        // `Failed.reason` — that diagnostic is not for the log either.
        SkeinLog.i(LOG_TAG, "vault key restore attempt: ${result.javaClass.simpleName}")
        when (result) {
            is RestoreResult.Provisioned -> {
                clearAll()
                onProvisioned(result.strongBoxBacked)
            }
            RestoreResult.AlreadyInitialised -> {
                clearAll()
                onAlreadyInitialised()
            }
            RestoreResult.WrongPassphrase -> retry(WRONG_PASSPHRASE_MESSAGE)
            RestoreResult.UnreadableFile -> retry(UNREADABLE_FILE_MESSAGE)
            RestoreResult.UnsupportedFile -> retry(UNSUPPORTED_FILE_MESSAGE)
            RestoreResult.Cancelled -> retry(CANCELLED_MESSAGE)
            is RestoreResult.Failed -> retry(FAILED_MESSAGE)
        }
    }

    private fun retry(message: String) {
        passphrase = ""
        uiState = VaultRestoreUiState.EnteringPassphrase(message)
    }

    private fun clearAll() {
        clearFile()
        passphrase = ""
        uiState = VaultRestoreUiState.Idle
    }

    private fun clearFile() {
        fileBytes?.fill(0)
        fileBytes = null
    }

    internal companion object {
        const val WRONG_PASSPHRASE_MESSAGE: String =
            "That passphrase did not unlock the recovery file. Check it and try again — " +
                "the file itself is unchanged."
        const val UNREADABLE_FILE_MESSAGE: String =
            "That file is not a Skein recovery export. Choose the file you saved when you exported " +
                "your vault key."
        const val UNSUPPORTED_FILE_MESSAGE: String =
            "That recovery file was written by a newer version of Skein. Update the app, then try again."
        const val CANCELLED_MESSAGE: String =
            "Restore was cancelled before your vault key was saved. Nothing has been changed."
        const val FAILED_MESSAGE: String =
            "Your vault key could not be restored. Nothing has been changed. Please try again."
    }
}

private const val LOG_TAG = "VaultRestore"

/** Mapped onto the generic retry copy — this screen does not send a restoring user to enrolment. */
private const val NO_BIOMETRIC_REASON = "no biometric enrolled"
