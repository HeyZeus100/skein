// skein-ugo (E3.I4) — Biometric prompt UI with `CryptoObject`.
//
// This screen is the UI seam above `UnlockManager` (`skein-pya`, `E3.I3a`),
// which in turn sits above `VaultKeyProvider` (`skein-3el`, `E3.I2`). All
// `BiometricPrompt.CryptoObject` construction and presentation already
// happens *inside* `core/vault`:
//
//  - `VaultKeyProviderImpl.unlockWith` builds the `Cipher` from the
//    biometric-bound Keystore alias (`setUserAuthenticationRequired` +
//    per-use auth, `ATTACHMENT_ENCRYPTION.md` §2/§3) and hands it to
//    `BiometricAuthenticator.authenticate`.
//  - `AndroidBiometricAuthenticator.authenticate` is the one call site that
//    constructs `BiometricPrompt.CryptoObject(cipher)` and calls
//    `BiometricPrompt.authenticate(prompt, cryptoObject)`.
//
// So this composable's job is narrower than "wire up a CryptoObject": it
// builds the `BiometricPrompt.PromptInfo` (BIOMETRIC_STRONG only — no
// device-credential fallback, matching the plan's "spec says biometric-gated"
// note for `E3.I4`), drives `UnlockManager.unlock(activity, promptInfo,
// Factor.BIOMETRIC)`, and renders the observable `UnlockOutcome`. No
// CryptoObject/Cipher type appears in this file, and `VaultKeyProvider` /
// `UnlockManager` are unmodified — see the read-first verification in the
// bd issue for why (their current contracts, not the stale plan-doc/bd
// description text that predates them, already do this internally).
//
// Zeroization / no-key-material discipline (bd non-negotiable): the
// `AuthorizationToken` from `UnlockOutcome.Success` is forwarded to
// [onUnlocked] and never stored in `remember { }`/mutable Compose state —
// the only state this file keeps is the narrow [BiometricUnlockUiState]
// (Prompting/Retry-with-a-user-facing-message), which carries no key
// material and no raw `UnlockOutcome.Failed.reason` text (that string is
// an internal diagnostic, not user data, but is deliberately not surfaced
// or logged either — spec §7 / `LOCK_POLICY_INDEXING.md` §5.1 "no key
// material in state event payloads", extended here to "no internal
// diagnostics in the UI users see").
//
// skein-ank2: two `Failed` reasons ARE inspected (never shown) — the
// bounded phrases the provider uses for a corrupt / unreadable key envelope
// (`EnvelopeUnreadable`) — so that state gets its own non-destructive
// message instead of the generic "Authentication failed." No reset is
// offered here; see that file. `NotInitialised` can now route to the
// host's setup screen ([onNotInitialised]) instead of dead-ending in a
// retry.

package app.skein.feature.shell.auth

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.skein.core.vault.key.VaultKeyProvider
import app.skein.core.vault.session.UnlockManager
import app.skein.core.vault.session.UnlockOutcome
import app.skein.feature.shell.testing.ShellTestTags
import kotlinx.coroutines.launch
import us.aherrera.skein.core.model.AuthorizationToken

/**
 * Presents a `BiometricPrompt` (via [UnlockManager.unlock]) and routes on
 * the resulting [UnlockOutcome]:
 *
 *  - [UnlockOutcome.Success] and [UnlockOutcome.Coalesced] wrapping a
 *    success (already unlocked this session — no second prompt is shown,
 *    per `UnlockManager`'s coalescing contract) both call [onUnlocked] with
 *    the fresh/existing [AuthorizationToken].
 *  - [UnlockOutcome.UserCancelled] and [UnlockOutcome.Failed] show a retry
 *    affordance; tapping it re-presents the prompt.
 *  - [UnlockOutcome.KeyPermanentlyInvalidated] calls [onRecoveryRequired]
 *    so the host can route to `UnlockManager.recoverAndRewrap`'s UI
 *    (`E3.I5`+ — out of scope here).
 *  - [UnlockOutcome.NotInitialised] calls [onNotInitialised] when the host
 *    supplies one (skein-ank2: the gate then shows `VaultSetupScreen`);
 *    without it, the retry affordance with an explanatory message.
 *  - [UnlockOutcome.Failed] for a corrupt / unreadable key envelope shows a
 *    distinct message that promises nothing was changed and offers no
 *    reset ([EnvelopeUnreadable]); every other failure shows the generic
 *    retry text.
 *
 * The prompt is presented automatically on first composition (and again on
 * every retry tap) — there is no separate "tap to unlock" gate in front of
 * it, since the host is expected to only show this screen while
 * `UnlockManager.state` is `Locked`/`RecoveryRequired` in the first place.
 *
 * Requires a `FragmentActivity` host, resolved by unwrapping
 * [LocalContext]; when the current context is not (wrapped around) a
 * `FragmentActivity` — e.g. a plain `@Preview` — the screen renders the
 * retry state with an explanatory message instead of crashing.
 */
@Composable
public fun BiometricUnlockScreen(
    unlockManager: UnlockManager,
    onUnlocked: (AuthorizationToken) -> Unit,
    onRecoveryRequired: () -> Unit,
    modifier: Modifier = Modifier,
    onNotInitialised: (() -> Unit)? = null,
    biometricPromptTitle: String = "Unlock Skein",
    biometricPromptSubtitle: String = "Authenticate to open your vault",
    biometricPromptNegativeButton: String = "Cancel",
) {
    val context = LocalContext.current
    val hostActivity = remember(context) { context.findFragmentActivity() }
    val scope = rememberCoroutineScope()

    val promptInfo =
        remember(biometricPromptTitle, biometricPromptSubtitle, biometricPromptNegativeButton) {
            BiometricPrompt.PromptInfo
                .Builder()
                .setTitle(biometricPromptTitle)
                .setSubtitle(biometricPromptSubtitle)
                .setNegativeButtonText(biometricPromptNegativeButton)
                // BIOMETRIC_STRONG only: the plan's E3.I4 note is explicit
                // that there is no device-credential fallback here — this
                // screen is the *biometric* unlock path. DEVICE_CREDENTIAL
                // is a distinct `VaultKeyProvider.Factor` with its own
                // (separate, out-of-scope-here) recovery UI.
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build()
        }

    var uiState: BiometricUnlockUiState by remember {
        mutableStateOf(
            if (hostActivity != null) {
                BiometricUnlockUiState.Prompting
            } else {
                BiometricUnlockUiState.Retry(NO_HOST_ACTIVITY_MESSAGE)
            },
        )
    }

    fun presentPrompt() {
        val activity = hostActivity ?: return
        uiState = BiometricUnlockUiState.Prompting
        scope.launch {
            val outcome = unlockManager.unlock(activity, promptInfo, VaultKeyProvider.Factor.BIOMETRIC)
            handleOutcome(
                outcome = outcome,
                onUnlocked = onUnlocked,
                onRecoveryRequired = onRecoveryRequired,
                onNotInitialised = onNotInitialised,
                onRetry = { message -> uiState = BiometricUnlockUiState.Retry(message) },
            )
        }
    }

    LaunchedEffect(unlockManager, hostActivity) {
        if (hostActivity != null) presentPrompt()
    }

    Box(
        modifier = modifier.fillMaxSize().testTag(ShellTestTags.BIOMETRIC_UNLOCK_ROOT),
        contentAlignment = Alignment.Center,
    ) {
        when (val state = uiState) {
            is BiometricUnlockUiState.Prompting ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(SPACING),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.testTag(ShellTestTags.BIOMETRIC_UNLOCK_PROGRESS),
                    )
                    Text(
                        text = "Waiting for biometric authentication…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

            is BiometricUnlockUiState.Retry ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(SPACING),
                    modifier = Modifier.padding(horizontal = 24.dp),
                ) {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag(ShellTestTags.BIOMETRIC_UNLOCK_MESSAGE),
                    )
                    Button(
                        onClick = ::presentPrompt,
                        modifier = Modifier.testTag(ShellTestTags.BIOMETRIC_UNLOCK_RETRY_BUTTON),
                    ) {
                        Text("Try again")
                    }
                }
        }
    }
}

/** UI-visible phase of [BiometricUnlockScreen]. Carries no key material. */
private sealed class BiometricUnlockUiState {
    /** A `BiometricPrompt` is on screen (or the crypto unwrap is running). */
    object Prompting : BiometricUnlockUiState()

    /** [message] is a user-facing string only — never a raw exception/reason. */
    data class Retry(
        val message: String,
    ) : BiometricUnlockUiState()
}

/**
 * Maps an [UnlockOutcome] to the UI-facing effects, unwrapping
 * [UnlockOutcome.Coalesced] (already unlocked this session — no second
 * prompt was shown) down to its underlying outcome first. Internal so the
 * mapping is unit-testable on the JVM (`BiometricUnlockOutcomeTest`).
 */
internal tailrec fun handleOutcome(
    outcome: UnlockOutcome,
    onUnlocked: (AuthorizationToken) -> Unit,
    onRecoveryRequired: () -> Unit,
    onNotInitialised: (() -> Unit)?,
    onRetry: (String) -> Unit,
) {
    when (outcome) {
        is UnlockOutcome.Success -> onUnlocked(outcome.token)
        is UnlockOutcome.Coalesced ->
            handleOutcome(outcome.outcome, onUnlocked, onRecoveryRequired, onNotInitialised, onRetry)
        is UnlockOutcome.KeyPermanentlyInvalidated -> onRecoveryRequired()
        UnlockOutcome.UserCancelled ->
            onRetry("Authentication was cancelled.")
        UnlockOutcome.NotInitialised ->
            if (onNotInitialised != null) onNotInitialised() else onRetry(NOT_SET_UP_MESSAGE)
        is UnlockOutcome.IllegalTransition ->
            onRetry("Unlock is not available right now.")
        is UnlockOutcome.Failed ->
            onRetry(
                if (EnvelopeUnreadable.matches(outcome.reason)) {
                    EnvelopeUnreadable.UNLOCK_MESSAGE
                } else {
                    GENERIC_FAILURE_MESSAGE
                },
            )
    }
}

private val SPACING = 12.dp

private const val NO_HOST_ACTIVITY_MESSAGE =
    "Unable to present the biometric prompt: no host activity available."
private const val NOT_SET_UP_MESSAGE = "The vault has not been set up yet."
private const val GENERIC_FAILURE_MESSAGE = "Authentication failed."
