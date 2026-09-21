// skein-v9g (E3.I11) — Settings › Security › "Export vault key
// (passphrase)".
//
// Opt-in, and gated three ways before any key material is touched:
//
//   1. the vault must be UNLOCKED (the row is disabled otherwise — there is
//      no master key in memory to export while locked);
//   2. a FRESH biometric / device-credential re-auth, requested at the
//      moment of export rather than inherited from the session's unlock
//      (`onReauthenticate`, wired by the host to the same
//      `BiometricPrompt` the unlock screen uses);
//   3. the passphrase, typed twice, at or above
//      `PassphraseStrength.MINIMUM_LENGTH`.
//
// Only then is the envelope built, and it goes straight to a
// `ACTION_CREATE_DOCUMENT` destination the user picks
// (`POST_REVIEW_RESOLUTIONS.md` §4 — never app-external staging, never a
// fixed path). The app keeps no copy and never learns the passphrase.
//
// This file owns the UI and the step sequencing only. The crypto lives in
// `:core:vault`'s `PassphraseKeyExport`, and reaching the in-memory master
// lives in the host (`:app`'s composition root) behind [onBuildExport] —
// `:feature:settings` never holds a `VaultKeyProvider`, matching how it
// already takes `SecurityPrefs` as a flow-plus-callback pair rather than
// depending back on `:app`.
//
// The warning is not a footnote: the file plus the passphrase decrypts
// everything, forever, with no biometric and no device binding. That
// sentence is on screen before the passphrase field, not after it.

package app.skein.feature.settings

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.skein.core.model.SkeinLog
import app.skein.core.vault.key.PassphraseStrength
import app.skein.feature.shell.input.SecureTextField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.IOException

/** Phase of the export flow. Carries no key or passphrase material. */
sealed class RecoveryExportUiState {
    /** The dialog is closed. */
    data object Closed : RecoveryExportUiState()

    /** Warning + passphrase entry. [message] reports a previous failure, if any. */
    data class Entering(
        val message: String? = null,
    ) : RecoveryExportUiState()

    /** The fresh re-auth prompt is on screen. */
    data object Reauthenticating : RecoveryExportUiState()

    /** The key derivation is running. Seconds by design — the UI must say so. */
    data object Deriving : RecoveryExportUiState()

    /** The envelope is built; the document picker is open. */
    data object ChoosingDestination : RecoveryExportUiState()

    /** The file was written to the user's chosen destination. */
    data object Saved : RecoveryExportUiState()
}

/**
 * Sequences the export. Every host seam is a plain lambda, so the whole
 * flow is JVM-testable without Android types.
 *
 * @param onReauthenticate presents a FRESH biometric / device-credential
 *   prompt and reports whether it succeeded. Never reuses the session's
 *   unlock authorisation.
 * @param onBuildExport derives the KEK and wraps the in-memory master,
 *   returning the recovery file's bytes — or `null` if the vault locked
 *   between the tap and here, which is a refusal, not a failure.
 * @param onEnvelopeReady hands the finished bytes and a suggested file name
 *   to the caller, which launches `ACTION_CREATE_DOCUMENT` and then reports
 *   back through [onSaved] / [onSaveFailed] / [onSaveCancelled].
 */
@Stable
class RecoveryExportState(
    private val scope: CoroutineScope,
    private val onReauthenticate: suspend () -> Boolean,
    private val onBuildExport: suspend (CharArray) -> ByteArray?,
    private val onEnvelopeReady: (envelope: ByteArray, suggestedFileName: String) -> Unit,
    private val suggestedFileName: () -> String = ::defaultRecoveryFileName,
) {
    var uiState: RecoveryExportUiState by mutableStateOf(RecoveryExportUiState.Closed)
        private set

    /** What the first field holds. A `String` because that is Compose's text API; see this file's header. */
    var passphrase: String by mutableStateOf("")

    /** What the confirmation field holds. */
    var confirmation: String by mutableStateOf("")

    /** Live feedback for the meter under the first field. */
    val strength: PassphraseStrength.Score
        get() = PassphraseStrength.evaluate(passphrase.toCharArray())

    /** Whether the two fields agree. Only meaningful once something has been typed in both. */
    val matches: Boolean
        get() = passphrase == confirmation

    /**
     * Whether "Export" should be enabled: the floor is cleared and the two
     * entries agree. The floor is ALSO enforced inside
     * `PassphraseKeyExport.export`, so a UI bug cannot weaken it.
     */
    val canExport: Boolean
        get() =
            uiState is RecoveryExportUiState.Entering &&
                PassphraseStrength.isAcceptable(strength) &&
                matches

    /** Opens the dialog. Ignored while an attempt is already running. */
    fun open() {
        if (isBusy()) return
        passphrase = ""
        confirmation = ""
        uiState = RecoveryExportUiState.Entering()
    }

    /** Closes the dialog and drops what was typed. Ignored mid-attempt. */
    fun dismiss() {
        if (isBusy()) return
        passphrase = ""
        confirmation = ""
        uiState = RecoveryExportUiState.Closed
    }

    /** Runs re-auth, then the derivation, then hands the envelope to the picker. */
    fun export() {
        if (!canExport) return
        val typed = passphrase.toCharArray()
        uiState = RecoveryExportUiState.Reauthenticating
        scope.launch {
            try {
                if (!onReauthenticate()) {
                    fail(REAUTH_FAILED_MESSAGE)
                    return@launch
                }
                uiState = RecoveryExportUiState.Deriving
                val envelope = onBuildExport(typed)
                if (envelope == null) {
                    fail(LOCKED_MESSAGE)
                    return@launch
                }
                // Nothing typed survives past the derivation.
                passphrase = ""
                confirmation = ""
                uiState = RecoveryExportUiState.ChoosingDestination
                onEnvelopeReady(envelope, suggestedFileName())
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                fail(FAILED_MESSAGE)
            } finally {
                typed.fill('\u0000')
            }
        }
    }

    /** The picker wrote the file. */
    fun onSaved() {
        // Counts only (spec §9): that one export happened, nothing about it.
        SkeinLog.i(LOG_TAG, "vault key recovery export written")
        uiState = RecoveryExportUiState.Saved
    }

    /** The picker was dismissed without choosing a destination. Nothing was written. */
    fun onSaveCancelled() {
        uiState = RecoveryExportUiState.Closed
    }

    /** A destination was chosen but could not be written. */
    fun onSaveFailed() {
        uiState = RecoveryExportUiState.Entering(WRITE_FAILED_MESSAGE)
    }

    private fun fail(message: String) {
        passphrase = ""
        confirmation = ""
        uiState = RecoveryExportUiState.Entering(message)
    }

    private fun isBusy(): Boolean =
        uiState is RecoveryExportUiState.Reauthenticating ||
            uiState is RecoveryExportUiState.Deriving ||
            uiState is RecoveryExportUiState.ChoosingDestination

    companion object {
        const val REAUTH_FAILED_MESSAGE: String =
            "Skein could not confirm it is you, so nothing was exported."
        const val LOCKED_MESSAGE: String =
            "Your vault locked before the export finished. Unlock it and try again."
        const val WRITE_FAILED_MESSAGE: String =
            "That file could not be written. Nothing was saved. Try a different location."
        const val FAILED_MESSAGE: String =
            "The export did not finish. Nothing was saved."
    }
}

/** `skein-recovery-YYYY-MM-DD.json`, the name bd `skein-v9g` specifies. */
fun defaultRecoveryFileName(): String {
    val today = java.time.LocalDate.now()
    return "skein-recovery-%04d-%02d-%02d.json".format(today.year, today.monthValue, today.dayOfMonth)
}

/**
 * The whole Settings › Security export feature: the row, the dialog, and
 * the `ACTION_CREATE_DOCUMENT` destination picker.
 *
 * The envelope lives in a local `remember` only between "built" and
 * "written", and is zeroed the moment either happens — success, failure or
 * a dismissed picker. It is never written anywhere but the `Uri` the user
 * picked (`POST_REVIEW_RESOLUTIONS.md` §4): there is no staging file, no
 * cache copy and no fallback path.
 */
@Composable
fun RecoveryKeyExportSection(
    vaultUnlocked: Boolean,
    onReauthenticate: suspend () -> Boolean,
    onBuildExport: suspend (CharArray) -> ByteArray?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pending = remember { PendingEnvelope() }
    var launchWith by remember { mutableStateOf<String?>(null) }

    val state =
        remember(scope, onReauthenticate, onBuildExport) {
            RecoveryExportState(
                scope = scope,
                onReauthenticate = onReauthenticate,
                onBuildExport = onBuildExport,
                onEnvelopeReady = { envelope, name ->
                    pending.set(envelope)
                    launchWith = name
                },
            )
        }

    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(RECOVERY_MIME_TYPE)) { uri ->
            val envelope = pending.take()
            when {
                uri == null -> state.onSaveCancelled()
                envelope == null -> state.onSaveFailed()
                writeEnvelope(context.contentResolver, uri, envelope) -> state.onSaved()
                else -> state.onSaveFailed()
            }
            envelope?.fill(0)
        }

    LaunchedEffect(launchWith) {
        launchWith?.let { name ->
            launchWith = null
            picker.launch(name)
        }
    }

    Column(modifier = modifier) {
        RecoveryKeyExportRow(vaultUnlocked = vaultUnlocked, onClick = state::open)
        RecoveryKeyExportDialog(state = state)
    }
}

/** Holds the built envelope across the picker round trip, and only that long. */
private class PendingEnvelope {
    private var bytes: ByteArray? = null

    fun set(value: ByteArray) {
        bytes?.fill(0)
        bytes = value
    }

    fun take(): ByteArray? = bytes.also { bytes = null }
}

/** Writes [envelope] to the user-chosen [uri], truncating whatever was there. */
private fun writeEnvelope(
    resolver: ContentResolver,
    uri: Uri,
    envelope: ByteArray,
): Boolean =
    try {
        resolver.openOutputStream(uri, "wt")?.use { out ->
            out.write(envelope)
            out.flush()
            true
        } ?: false
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

/**
 * The Settings › Security row. Disabled — with the reason shown — while the
 * vault is locked, because there is no in-memory master to export then.
 */
@Composable
fun RecoveryKeyExportRow(
    vaultUnlocked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .let { if (vaultUnlocked) it.clickable(onClick = onClick) else it }
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column {
            Text(
                text = "Export vault key (passphrase)",
                style = MaterialTheme.typography.bodyLarge,
                color =
                    if (vaultUnlocked) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
            Text(
                text = if (vaultUnlocked) ROW_CAPTION else ROW_LOCKED_CAPTION,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The warning + passphrase-×2 dialog. Renders nothing while [RecoveryExportUiState.Closed]. */
@Composable
fun RecoveryKeyExportDialog(
    state: RecoveryExportState,
    modifier: Modifier = Modifier,
) {
    when (val phase = state.uiState) {
        RecoveryExportUiState.Closed -> Unit

        is RecoveryExportUiState.Entering ->
            AlertDialog(
                modifier = modifier,
                onDismissRequest = state::dismiss,
                title = { Text("Export vault key") },
                text = {
                    Column {
                        Text(text = EXPORT_WARNING, style = MaterialTheme.typography.bodyMedium)
                        if (phase.message != null) {
                            Text(
                                text = phase.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        SecureTextField(
                            value = state.passphrase,
                            onValueChange = { state.passphrase = it },
                            label = { Text("Passphrase") },
                            singleLine = true,
                            imeAction = ImeAction.Next,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        )
                        Text(
                            text = strengthCaption(state.strength),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SecureTextField(
                            value = state.confirmation,
                            onValueChange = { state.confirmation = it },
                            label = { Text("Repeat passphrase") },
                            singleLine = true,
                            imeAction = ImeAction.Done,
                            isError = state.confirmation.isNotEmpty() && !state.matches,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                        if (state.confirmation.isNotEmpty() && !state.matches) {
                            Text(
                                text = "The two passphrases do not match.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = state::export, enabled = state.canExport) { Text("Export") }
                },
                dismissButton = { TextButton(onClick = state::dismiss) { Text("Cancel") } },
            )

        RecoveryExportUiState.Reauthenticating,
        RecoveryExportUiState.Deriving,
        RecoveryExportUiState.ChoosingDestination,
        ->
            AlertDialog(
                modifier = modifier,
                onDismissRequest = {},
                title = { Text("Export vault key") },
                text = { Text(busyCaption(phase), style = MaterialTheme.typography.bodyMedium) },
                confirmButton = {},
            )

        RecoveryExportUiState.Saved ->
            AlertDialog(
                modifier = modifier,
                onDismissRequest = state::dismiss,
                title = { Text("Recovery file saved") },
                text = { Text(SAVED_MESSAGE, style = MaterialTheme.typography.bodyMedium) },
                confirmButton = { TextButton(onClick = state::dismiss) { Text("Done") } },
            )
    }
}

private fun busyCaption(phase: RecoveryExportUiState): String =
    when (phase) {
        RecoveryExportUiState.Reauthenticating -> "Confirm it is you…"
        RecoveryExportUiState.Deriving -> DERIVING_MESSAGE
        else -> "Choose where to save the file…"
    }

internal fun strengthCaption(score: PassphraseStrength.Score): String =
    when (score) {
        PassphraseStrength.Score.TOO_SHORT ->
            "At least ${PassphraseStrength.MINIMUM_LENGTH} characters."
        PassphraseStrength.Score.WEAK -> "Weak — longer, or a wider mix of characters, would be safer."
        PassphraseStrength.Score.FAIR -> "Fair."
        PassphraseStrength.Score.STRONG -> "Strong."
    }

private const val LOG_TAG = "RecoveryExport"

/** What the export writes, and what the restore picker offers first. */
private const val RECOVERY_MIME_TYPE = "application/json"

private const val ROW_CAPTION =
    "Save a passphrase-protected copy of your vault key, so you can recover it on another device."

private const val ROW_LOCKED_CAPTION = "Unlock your vault to export its key."

private const val EXPORT_WARNING =
    "Anyone who has this file AND this passphrase can read everything in your vault — on any device, " +
        "with no fingerprint and no screen lock. Choose a passphrase you use nowhere else, store the " +
        "file somewhere you trust, and never keep the two together. Skein cannot recover the " +
        "passphrase for you."

private const val DERIVING_MESSAGE =
    "Protecting your key. This takes a few seconds on purpose — it is what makes the passphrase hard " +
        "to guess."

private const val SAVED_MESSAGE =
    "Your recovery file has been written to the location you chose. Keep it somewhere you trust, apart " +
        "from the passphrase. To use it, choose \"Restore from a passphrase export\" when setting Skein " +
        "up again."
