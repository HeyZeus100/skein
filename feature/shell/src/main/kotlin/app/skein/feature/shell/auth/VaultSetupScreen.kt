// skein-ank2 (E6.I12, spec §8.7) — first-run vault setup UI.
//
// Sibling of `BiometricUnlockScreen` (skein-ugo). Where that screen drives
// `UnlockManager.unlock`, this one drives `VaultKeyProvider.setup(activity,
// promptInfo)` exactly once per tap: the provider generates the two Layer-0
// Keystore aliases (biometric-bound and device-credential-bound,
// `ATTACHMENT_ENCRYPTION.md` §3.2), the 32-byte master, wraps it under each
// and persists the envelope. The user therefore confirms TWICE — once per
// factor — and the explanation says so up front.
//
// PromptInfo: this screen builds ONE `PromptInfo` and `VaultKeyProvider.setup`
// passes it unchanged to both Layer-0 wrap prompts (biometric-bound, then
// device-credential-bound) — but `AndroidBiometricAuthenticator.authenticate`
// (skein-f9ls) derives a factor-narrowed `PromptInfo` from it per prompt, so
// the biometric wrap only ever offers `BIOMETRIC_STRONG` and the
// device-credential wrap only ever offers `DEVICE_CREDENTIAL`. That
// derivation also supplies the "Step 1 of 2" / "Step 2 of 2" subtitle since
// no subtitle is set below — see `promptInfoForFactor`'s KDoc in
// `:core:vault`. `setAllowedAuthenticators` here only needs to satisfy this
// PromptInfo's OWN `.build()` validation (which requires a supported
// combination), not describe what either prompt actually shows.
//
// No destructive path exists on this screen: a refused setup (an envelope
// already exists, readable or not) routes the host to unlock via
// [VaultSetupState]. No key material reaches Compose state — see
// `VaultSetupState`'s header.

package app.skein.feature.shell.auth

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.skein.core.vault.key.SetupResult
import app.skein.core.vault.key.VaultKeyProvider
import app.skein.feature.shell.input.SecureTextField
import app.skein.feature.shell.testing.ShellTestTags
import java.io.IOException

/**
 * Explains what setup does, then runs [VaultKeyProvider.setup] on tap and
 * renders the resulting [VaultSetupUiState]. See [VaultSetupState] for how
 * each [SetupResult] is routed; the host is expected to show this screen
 * only while [VaultKeyProvider.isInitialised] is `false`.
 *
 * @param onProvisioned setup persisted an envelope; the argument says
 *   whether the Layer-0 keys are StrongBox-backed (`false` on the TEE
 *   fallback — the host records it for Settings › Security). The host then
 *   routes to unlock.
 * @param onAlreadyInitialised an envelope already exists (readable or not),
 *   so setup was refused; the host routes to unlock.
 */
@Composable
public fun VaultSetupScreen(
    keyProvider: VaultKeyProvider,
    onProvisioned: (strongBoxBacked: Boolean) -> Unit,
    onAlreadyInitialised: () -> Unit,
    modifier: Modifier = Modifier,
    promptTitle: String = "Set up your vault",
) {
    val context = LocalContext.current
    val hostActivity = remember(context) { context.findFragmentActivity() }
    val scope = rememberCoroutineScope()
    val currentOnProvisioned by rememberUpdatedState(onProvisioned)
    val currentOnAlreadyInitialised by rememberUpdatedState(onAlreadyInitialised)

    // No subtitle is set here — `promptInfoForFactor` (skein-f9ls) supplies
    // the per-prompt "Step 1 of 2" / "Step 2 of 2" text instead. See the
    // header comment above.
    val promptInfo =
        remember(promptTitle) {
            BiometricPrompt.PromptInfo
                .Builder()
                .setTitle(promptTitle)
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                ).build()
        }

    val state =
        remember(keyProvider, hostActivity, promptInfo) {
            VaultSetupState(
                scope = scope,
                runSetup = {
                    val activity = hostActivity
                    if (activity == null) {
                        SetupResult.Failed(NO_HOST_ACTIVITY_REASON)
                    } else {
                        keyProvider.setup(activity, promptInfo)
                    }
                },
                onProvisioned = { currentOnProvisioned(it) },
                onAlreadyInitialised = { currentOnAlreadyInitialised() },
            )
        }

    // skein-v9g (E3.I11): the recovery / device-migration entry point. Same
    // `keyProvider`, same `promptInfo`, but `setup(existingMaster)` so the
    // vault.db already on disk stays decryptable.
    val restore =
        remember(keyProvider, hostActivity, promptInfo) {
            VaultRestoreState(
                scope = scope,
                runSetup = { existingMaster ->
                    val activity = hostActivity
                    if (activity == null) {
                        SetupResult.Failed(NO_HOST_ACTIVITY_REASON)
                    } else {
                        keyProvider.setup(activity, promptInfo, existingMaster)
                    }
                },
                onProvisioned = { currentOnProvisioned(it) },
                onAlreadyInitialised = { currentOnAlreadyInitialised() },
            )
        }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val bytes = uri?.let { readRecoveryFile(context.contentResolver, it) }
            if (bytes == null) restore.onFileChoiceCancelled() else restore.onFileChosen(bytes)
        }

    Box(
        modifier = modifier.fillMaxSize().testTag(ShellTestTags.VAULT_SETUP_ROOT),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SPACING),
            modifier = Modifier.padding(horizontal = GUTTER).widthIn(max = MAX_CONTENT_WIDTH),
        ) {
            when (val restorePhase = restore.uiState) {
                VaultRestoreUiState.Idle -> Unit
                VaultRestoreUiState.ChoosingFile ->
                    Text(
                        text = "Choose your recovery file…",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                is VaultRestoreUiState.EnteringPassphrase -> {
                    RestorePassphraseEntry(state = restore, message = restorePhase.message)
                    return@Column
                }
                VaultRestoreUiState.InProgress -> {
                    CircularProgressIndicator(modifier = Modifier.testTag(ShellTestTags.VAULT_RESTORE_PROGRESS))
                    Text(
                        text = RESTORE_IN_PROGRESS,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    return@Column
                }
            }

            when (val phase = state.uiState) {
                VaultSetupUiState.Ready -> {
                    Text(
                        text = "Set up your vault",
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = EXPLANATION,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Button(
                        onClick = state::begin,
                        modifier = Modifier.testTag(ShellTestTags.VAULT_SETUP_BEGIN_BUTTON),
                    ) {
                        Text("Set up vault")
                    }
                    Text(
                        text = RESTORE_EXPLANATION,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                    OutlinedButton(
                        onClick = {
                            restore.beginFileChoice()
                            picker.launch(RECOVERY_MIME_TYPES)
                        },
                        modifier = Modifier.testTag(ShellTestTags.VAULT_RESTORE_BUTTON),
                    ) {
                        Text("Restore from a passphrase export")
                    }
                }

                VaultSetupUiState.InProgress -> {
                    CircularProgressIndicator(modifier = Modifier.testTag(ShellTestTags.VAULT_SETUP_PROGRESS))
                    Text(
                        text = "Waiting for you to confirm…",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }

                is VaultSetupUiState.Retry -> {
                    Text(
                        text = phase.message,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag(ShellTestTags.VAULT_SETUP_MESSAGE),
                    )
                    Button(
                        onClick = state::begin,
                        modifier = Modifier.testTag(ShellTestTags.VAULT_SETUP_RETRY_BUTTON),
                    ) {
                        Text("Try again")
                    }
                }

                VaultSetupUiState.NoBiometricEnrolled -> {
                    Text(
                        text = NO_BIOMETRIC_MESSAGE,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag(ShellTestTags.VAULT_SETUP_MESSAGE),
                    )
                    Button(
                        onClick = { openBiometricEnrolment(context) },
                        modifier = Modifier.testTag(ShellTestTags.VAULT_SETUP_ENROL_BUTTON),
                    ) {
                        Text("Open security settings")
                    }
                    OutlinedButton(
                        onClick = state::begin,
                        modifier = Modifier.testTag(ShellTestTags.VAULT_SETUP_RETRY_BUTTON),
                    ) {
                        Text("Try again")
                    }
                }
            }
        }
    }
}

/**
 * Passphrase entry for a chosen recovery file (skein-v9g). A
 * [SecureTextField] under a [PasswordVisualTransformation] — never a raw
 * `TextField` — so the IME is told not to learn, suggest or back up what is
 * typed (E3.I9, threat model §9).
 */
@Composable
private fun RestorePassphraseEntry(
    state: VaultRestoreState,
    message: String?,
) {
    Text(
        text = "Restore from a passphrase export",
        style = MaterialTheme.typography.titleLarge,
        textAlign = TextAlign.Center,
    )
    Text(
        text = RESTORE_PASSPHRASE_EXPLANATION,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
    if (message != null) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag(ShellTestTags.VAULT_RESTORE_MESSAGE),
        )
    }
    SecureTextField(
        value = state.passphrase,
        onValueChange = { state.passphrase = it },
        label = { Text("Recovery passphrase") },
        singleLine = true,
        imeAction = ImeAction.Done,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().testTag(ShellTestTags.VAULT_RESTORE_PASSPHRASE_FIELD),
    )
    Button(
        onClick = state::submit,
        enabled = state.canSubmit,
        modifier = Modifier.testTag(ShellTestTags.VAULT_RESTORE_SUBMIT_BUTTON),
    ) {
        Text("Restore vault key")
    }
    OutlinedButton(
        onClick = state::cancel,
        modifier = Modifier.testTag(ShellTestTags.VAULT_RESTORE_CANCEL_BUTTON),
    ) {
        Text("Cancel")
    }
}

/**
 * Reads a user-chosen recovery document through the `ContentResolver`,
 * refusing anything larger than [MAX_RECOVERY_FILE_BYTES] — a real export is
 * a few hundred bytes, and this is the one place the app reads a file the
 * user picked from outside its sandbox. Returns `null` on any I/O or
 * security failure; the caller treats that exactly like a cancelled pick,
 * so a `SecurityException` from a revoked grant cannot crash setup.
 */
private fun readRecoveryFile(
    resolver: ContentResolver,
    uri: Uri,
): ByteArray? =
    try {
        resolver.openInputStream(uri)?.use { input ->
            val bytes = input.readBytes()
            if (bytes.size > MAX_RECOVERY_FILE_BYTES) null else bytes
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

/**
 * Sends the user to the OS biometric-enrolment flow for a STRONG biometric
 * (`Settings.ACTION_BIOMETRIC_ENROLL`, API 30 = this app's minSdk), falling
 * back to the general security settings on devices that do not resolve it.
 * No permission is involved; both are ordinary implicit intents.
 */
private fun openBiometricEnrolment(context: Context) {
    val candidates =
        listOf(
            Intent(Settings.ACTION_BIOMETRIC_ENROLL).putExtra(
                Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                BiometricManager.Authenticators.BIOMETRIC_STRONG,
            ),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
        )
    for (intent in candidates) {
        try {
            context.startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            // Try the next, more general destination.
        }
    }
}

private val SPACING = 12.dp
private val GUTTER = 24.dp
private val MAX_CONTENT_WIDTH = 480.dp

private const val EXPLANATION =
    "Skein keeps your notes in an encrypted vault on this device. Setting it up creates a key in " +
        "your phone's secure hardware, protected by your fingerprint or face and by your screen lock. " +
        "You will be asked to confirm twice. Nothing leaves your device."

private const val NO_BIOMETRIC_MESSAGE =
    "No fingerprint or face is enrolled on this device. Skein needs a strong biometric to protect " +
        "your vault key. Add one in your device's security settings, then come back and try again."

/** Reason for a preview/no-activity host; mapped to the generic retry text, never shown. */
private const val NO_HOST_ACTIVITY_REASON = "no host activity"

// skein-v9g (E3.I11) — restore-from-export copy and limits.

private const val RESTORE_EXPLANATION =
    "Moving from another device, or lost your fingerprint and screen lock at the same time? If you " +
        "exported your vault key to a file, you can restore it here instead."

private const val RESTORE_PASSPHRASE_EXPLANATION =
    "Enter the passphrase you chose when you exported this file. Skein will unlock the key inside it " +
        "and protect it with this device's fingerprint and screen lock. Your existing notes stay readable."

private const val RESTORE_IN_PROGRESS =
    "Unlocking your recovery file. This takes a few seconds on purpose — then you will be asked to " +
        "confirm twice."

/** A recovery export is a few hundred bytes; anything larger is not one. */
private const val MAX_RECOVERY_FILE_BYTES = 64 * 1024

/**
 * What `ACTION_OPEN_DOCUMENT` offers. `application/json` is what the export
 * writes, with the wildcard type alongside because providers routinely
 * report a user-renamed or re-downloaded file as `application/octet-stream`
 * — and a user locked out of their vault must not also be locked out of
 * their own recovery file by a MIME-type guess.
 */
private val RECOVERY_MIME_TYPES = arrayOf("application/json", "*/*")
