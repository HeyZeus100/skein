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
// PromptInfo: `AndroidBiometricAuthenticator` presents the SAME `PromptInfo`
// for both wrap prompts, and the second wraps under a key bound to
// `AUTH_DEVICE_CREDENTIAL`, so the prompt must allow `DEVICE_CREDENTIAL` as
// well as `BIOMETRIC_STRONG` (androidx.biometric supports crypto-backed
// device-credential prompts from API 30, this app's minSdk). A prompt that
// allows device credential may not carry a negative button (the builder
// throws), which is why — unlike the unlock screen — none is set here.
// Deriving a factor-specific `PromptInfo` inside `:core:vault` is a
// follow-up noted on the bd; the shape here is what the provider's current
// contract supports.
//
// No destructive path exists on this screen: a refused setup (an envelope
// already exists, readable or not) routes the host to unlock via
// [VaultSetupState]. No key material reaches Compose state — see
// `VaultSetupState`'s header.

package app.skein.feature.shell.auth

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.skein.core.vault.key.SetupResult
import app.skein.core.vault.key.VaultKeyProvider
import app.skein.feature.shell.testing.ShellTestTags

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
    promptSubtitle: String = "Confirm with your fingerprint or face, then with your screen lock",
) {
    val context = LocalContext.current
    val hostActivity = remember(context) { context.findFragmentActivity() }
    val scope = rememberCoroutineScope()
    val currentOnProvisioned by rememberUpdatedState(onProvisioned)
    val currentOnAlreadyInitialised by rememberUpdatedState(onAlreadyInitialised)

    val promptInfo =
        remember(promptTitle, promptSubtitle) {
            BiometricPrompt.PromptInfo
                .Builder()
                .setTitle(promptTitle)
                .setSubtitle(promptSubtitle)
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

    Box(
        modifier = modifier.fillMaxSize().testTag(ShellTestTags.VAULT_SETUP_ROOT),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SPACING),
            modifier = Modifier.padding(horizontal = GUTTER).widthIn(max = MAX_CONTENT_WIDTH),
        ) {
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
