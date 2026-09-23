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
// message instead of the generic "Authentication failed." `NotInitialised`
// can now route to the host's setup screen ([onNotInitialised]) instead of
// dead-ending in a retry.
//
// skein-v3wb: the corrupt/unreadable-envelope state is the ONLY place in the
// app that ever offers the destructive "reset vault" flow — never for a
// cancellation or any other failure reason. [onResetRequested], when
// supplied, adds a "Reset vault…" affordance alongside that state's retry
// button; omitting it (the default) renders the message with no reset
// affordance at all, e.g. for a host that hasn't wired the flow yet.

package app.skein.feature.shell.auth

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.skein.core.vault.key.VaultKeyProvider
import app.skein.core.vault.session.UnlockManager
import app.skein.core.vault.session.UnlockOutcome
import app.skein.feature.shell.testing.ShellTestTags
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
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
 *  - [UnlockOutcome.Failed] for a corrupt / unreadable key envelope
 *    ([EnvelopeUnreadable]) shows a distinct message and, when
 *    [onResetRequested] is supplied, a "Reset vault…" affordance; every
 *    other failure shows the generic retry text with no reset affordance.
 *  - [UnlockOutcome.DeviceLocked] (skein-9psb) is NOT a failure: it means
 *    `Cipher.init` on the Layer-0 alias raced the keyguard's own unlock
 *    signal. The screen shows [BiometricUnlockUiState.WaitingForUnlock] (no
 *    failure text) and silently re-presents once the device reports
 *    unlocked — see [deviceUnlockWakeSignals] below.
 *
 * The prompt is presented automatically on first composition (and again on
 * every retry tap) — there is no separate "tap to unlock" gate in front of
 * it, since the host is expected to only show this screen while
 * `UnlockManager.state` is `Locked`/`RecoveryRequired` in the first place.
 * skein-9psb: that auto-present is itself gated on [isDeviceLocked] reporting
 * `false` AND the host `FragmentActivity`'s lifecycle being at least
 * [Lifecycle.State.RESUMED] — on screen-on with a keyguard, the activity
 * resumes ~100ms BEFORE the keystore itself learns the device is unlocked
 * (hardware-verified, bd skein-9psb), and presenting into that window makes
 * `Cipher.init` on the biometric alias throw immediately. When the gate is
 * shut, the screen waits for `ACTION_USER_PRESENT` (or the next `RESUME`) and
 * presents exactly once when it opens — see [isReadyToPresent] and
 * [deviceUnlockWakeSignals], both `internal` for [BiometricUnlockDeviceLockGateTest].
 *
 * Requires a `FragmentActivity` host, resolved by unwrapping
 * [LocalContext]; when the current context is not (wrapped around) a
 * `FragmentActivity` — e.g. a plain `@Preview` — the screen renders the
 * retry state with an explanatory message instead of crashing.
 *
 * @param isDeviceLocked test seam for the [KeyguardManager.isDeviceLocked]
 *   check above — `null` (the default/production value) resolves it from
 *   the real [KeyguardManager] via [LocalContext]. Tests inject a fake to
 *   drive the gate without a real keyguard.
 */
@Composable
public fun BiometricUnlockScreen(
    unlockManager: UnlockManager,
    onUnlocked: (AuthorizationToken) -> Unit,
    onRecoveryRequired: () -> Unit,
    modifier: Modifier = Modifier,
    onNotInitialised: (() -> Unit)? = null,
    onResetRequested: (() -> Unit)? = null,
    biometricPromptTitle: String = "Unlock Skein",
    biometricPromptSubtitle: String = "Authenticate to open your vault",
    biometricPromptNegativeButton: String = "Cancel",
    isDeviceLocked: (() -> Boolean)? = null,
) {
    val context = LocalContext.current
    val hostActivity = remember(context) { context.findFragmentActivity() }
    val scope = rememberCoroutineScope()

    val effectiveIsDeviceLocked =
        remember(context, isDeviceLocked) {
            isDeviceLocked ?: { context.isDeviceLockedNow() }
        }

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
            when {
                hostActivity == null -> BiometricUnlockUiState.Retry(NO_HOST_ACTIVITY_MESSAGE)
                !isReadyToPresent(hostActivity, effectiveIsDeviceLocked) -> BiometricUnlockUiState.WaitingForUnlock
                else -> BiometricUnlockUiState.Prompting
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
                onEnvelopeUnreadable = { message -> uiState = BiometricUnlockUiState.EnvelopeUnreadableRetry(message) },
                onDeviceLocked = {
                    // skein-9psb: NOT a failure — wait quietly, then retry
                    // the exact same prompt once the device reports unlocked.
                    uiState = BiometricUnlockUiState.WaitingForUnlock
                    scope.launch {
                        deviceUnlockWakeSignals(context, activity).first { !effectiveIsDeviceLocked() }
                        presentPrompt()
                    }
                },
            )
        }
    }

    LaunchedEffect(unlockManager, hostActivity) {
        val activity = hostActivity ?: return@LaunchedEffect
        if (!isReadyToPresent(activity, effectiveIsDeviceLocked)) {
            uiState = BiometricUnlockUiState.WaitingForUnlock
            deviceUnlockWakeSignals(context, activity).first { isReadyToPresent(activity, effectiveIsDeviceLocked) }
        }
        presentPrompt()
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

            is BiometricUnlockUiState.WaitingForUnlock ->
                // skein-9psb: NOT a failure state — no message copy implies
                // anything went wrong, and there is deliberately no retry
                // button; [deviceUnlockWakeSignals] drives the transition
                // back to [BiometricUnlockUiState.Prompting] on its own.
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(SPACING),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.testTag(ShellTestTags.BIOMETRIC_UNLOCK_PROGRESS),
                    )
                    Text(
                        text = "Waiting for the device to unlock…",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag(ShellTestTags.BIOMETRIC_UNLOCK_WAITING_MESSAGE),
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

            is BiometricUnlockUiState.EnvelopeUnreadableRetry ->
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
                    // skein-v3wb: reachable ONLY from this state — never for
                    // a cancellation or any other failure — and only when
                    // the host has wired a reset destination at all.
                    val reset = onResetRequested
                    if (reset != null) {
                        Button(
                            onClick = reset,
                            modifier = Modifier.testTag(ShellTestTags.BIOMETRIC_UNLOCK_RESET_BUTTON),
                        ) {
                            Text("Reset vault…")
                        }
                    }
                }
        }
    }
}

/** UI-visible phase of [BiometricUnlockScreen]. Carries no key material. */
private sealed class BiometricUnlockUiState {
    /** A `BiometricPrompt` is on screen (or the crypto unwrap is running). */
    object Prompting : BiometricUnlockUiState()

    /**
     * skein-9psb: the auto-present gate is shut (device locked / host not
     * yet `RESUMED`), or [UnlockOutcome.DeviceLocked] came back from an
     * actual attempt. NOT a failure — carries no message text describing an
     * error, and offers no retry button; [deviceUnlockWakeSignals] alone
     * drives the transition out of this state.
     */
    object WaitingForUnlock : BiometricUnlockUiState()

    /** [message] is a user-facing string only — never a raw exception/reason. */
    data class Retry(
        val message: String,
    ) : BiometricUnlockUiState()

    /**
     * skein-v3wb: the corrupt/unreadable-envelope [Retry], kept as its own
     * variant (rather than a flag on [Retry]) so the Composable's rendering
     * of the reset affordance is driven by state, not by re-matching the
     * message string.
     */
    data class EnvelopeUnreadableRetry(
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
    onEnvelopeUnreadable: ((String) -> Unit)? = null,
    onDeviceLocked: () -> Unit = {},
) {
    when (outcome) {
        is UnlockOutcome.Success -> onUnlocked(outcome.token)
        is UnlockOutcome.Coalesced ->
            handleOutcome(
                outcome.outcome,
                onUnlocked,
                onRecoveryRequired,
                onNotInitialised,
                onRetry,
                onEnvelopeUnreadable,
                onDeviceLocked,
            )
        is UnlockOutcome.KeyPermanentlyInvalidated -> onRecoveryRequired()
        UnlockOutcome.UserCancelled ->
            onRetry("Authentication was cancelled.")
        UnlockOutcome.NotInitialised ->
            if (onNotInitialised != null) onNotInitialised() else onRetry(NOT_SET_UP_MESSAGE)
        is UnlockOutcome.IllegalTransition ->
            onRetry("Unlock is not available right now.")
        // skein-9psb: NOT routed through onRetry — no failure text, no
        // reason string (there is none to carry: `UnlockOutcome.DeviceLocked`
        // is an object, matched by type only). The screen waits and retries
        // silently instead.
        UnlockOutcome.DeviceLocked -> onDeviceLocked()
        is UnlockOutcome.Failed ->
            if (EnvelopeUnreadable.matches(outcome.reason)) {
                if (onEnvelopeUnreadable != null) {
                    onEnvelopeUnreadable(EnvelopeUnreadable.UNLOCK_MESSAGE)
                } else {
                    onRetry(EnvelopeUnreadable.UNLOCK_MESSAGE)
                }
            } else {
                onRetry(GENERIC_FAILURE_MESSAGE)
            }
    }
}

/**
 * skein-9psb: true iff [activity]'s hosting lifecycle is at least
 * [Lifecycle.State.RESUMED] AND [isDeviceLocked] reports `false`. Both
 * conditions must hold before [BiometricUnlockScreen] ever calls
 * `UnlockManager.unlock` — presenting into a resumed-but-still-locked window
 * is exactly the skein-9psb race (`Cipher.init` throws immediately because
 * the Layer-0 alias requires `setUnlockedDeviceRequired`). `internal` (not
 * `private`) so `BiometricUnlockDeviceLockGateTest` can drive it directly
 * against a real, Robolectric-hosted `FragmentActivity`.
 */
internal fun isReadyToPresent(
    activity: FragmentActivity,
    isDeviceLocked: () -> Boolean,
): Boolean = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !isDeviceLocked()

/**
 * skein-9psb: a cold [Flow] that emits once for every event that might mean
 * the device just became unlocked — the system [Intent.ACTION_USER_PRESENT]
 * broadcast (fired on a successful keyguard dismissal) and every
 * [Lifecycle.Event.ON_RESUME] of [activity] (the "next RESUME" half of the
 * bd fix contract, for the case where the broadcast is missed/coalesced).
 * Callers re-check [isReadyToPresent] / [isDeviceLocked] themselves on each
 * emission — this flow is just the wake-up, never a truth value on its own.
 * The receiver/observer are unregistered when the flow's collector is
 * cancelled (`awaitClose`), so nothing outlives the `LaunchedEffect`/
 * `scope.launch` that collects it.
 */
internal fun deviceUnlockWakeSignals(
    context: Context,
    activity: FragmentActivity,
): Flow<Unit> =
    callbackFlow {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    receivedContext: Context?,
                    intent: Intent?,
                ) {
                    trySend(Unit)
                }
            }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_USER_PRESENT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) trySend(Unit)
            }
        activity.lifecycle.addObserver(observer)
        awaitClose {
            context.unregisterReceiver(receiver)
            activity.lifecycle.removeObserver(observer)
        }
    }

/** skein-9psb: the real, production [KeyguardManager]-backed check behind [BiometricUnlockScreen]'s `isDeviceLocked`. */
private fun Context.isDeviceLockedNow(): Boolean =
    ContextCompat.getSystemService(this, KeyguardManager::class.java)?.isDeviceLocked ?: false

private val SPACING = 12.dp

private const val NO_HOST_ACTIVITY_MESSAGE =
    "Unable to present the biometric prompt: no host activity available."
private const val NOT_SET_UP_MESSAGE = "The vault has not been set up yet."
private const val GENERIC_FAILURE_MESSAGE = "Authentication failed."
