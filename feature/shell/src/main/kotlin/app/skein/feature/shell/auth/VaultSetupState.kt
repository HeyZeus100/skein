// skein-ank2 (E6.I12, spec §8.7 "unlock biometric") — state holder for
// `VaultSetupScreen`: runs `VaultKeyProvider.setup` once and maps every
// `SetupResult` onto either a UI phase or a host callback.
//
// Same shape as `SettingsViewModel` / `NavState`: a plain `@Stable` class,
// not an AAC ViewModel, with the Android-typed `setup(activity, prompt)`
// call abstracted behind a `suspend () -> SetupResult` so the mapping is
// JVM-testable (`VaultSetupStateTest`) — the pattern `UnlockManager` and
// `VaultKeyProviderImpl` use for their own `*With(...)` seams.
//
// No key material passes through here: `SetupResult` carries only a key
// version and a StrongBox flag, and the `Failed.reason` diagnostic is
// mapped onto a fixed user-facing string, never shown or logged (the same
// discipline as `BiometricUnlockScreen`).

package app.skein.feature.shell.auth

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skein.core.vault.key.SetupResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** UI-visible phase of [VaultSetupScreen]. Carries no key material. */
public sealed class VaultSetupUiState {
    /** The explanation and its call to action; nothing has been prompted yet. */
    public object Ready : VaultSetupUiState()

    /**
     * `setup()` is running — the wrap prompts are on screen. Also the
     * terminal phase after a success: the host swaps this screen out for
     * the unlock screen, so nothing further is rendered here.
     */
    public object InProgress : VaultSetupUiState()

    /** Setup stopped short without persisting anything. [message] is user-facing only. */
    public data class Retry(
        public val message: String,
    ) : VaultSetupUiState()

    /** No strong biometric is enrolled — guide the user to enrolment, then let them retry. */
    public object NoBiometricEnrolled : VaultSetupUiState()
}

/**
 * Drives one setup attempt at a time and routes on the result:
 *
 *  - [SetupResult.Success] / [SetupResult.StrongBoxUnavailableFallback] →
 *    [onProvisioned] with whether the Layer-0 keys are StrongBox-backed
 *    (the host persists the fallback flag for Settings › Security);
 *  - [SetupResult.AlreadyInitialised] → [onAlreadyInitialised]: an envelope
 *    exists, so the host routes to unlock instead;
 *  - [SetupResult.Failed] whose reason says the envelope exists but cannot
 *    be read ([EnvelopeUnreadable]) → [onAlreadyInitialised] as well: setup
 *    is refused in that state and the unlock screen owns the distinct,
 *    non-destructive message — this screen must never look like a way to
 *    start over;
 *  - [SetupResult.UserCancelled], any other [SetupResult.Failed], or
 *    `setup()` throwing → [VaultSetupUiState.Retry];
 *  - [SetupResult.NoBiometricEnrolled] → [VaultSetupUiState.NoBiometricEnrolled].
 */
@Stable
public class VaultSetupState(
    private val scope: CoroutineScope,
    private val runSetup: suspend () -> SetupResult,
    private val onProvisioned: (strongBoxBacked: Boolean) -> Unit,
    private val onAlreadyInitialised: () -> Unit,
) {
    public var uiState: VaultSetupUiState by mutableStateOf(VaultSetupUiState.Ready)
        private set

    /**
     * Runs `setup()` once. A call while a run is in flight is ignored — a
     * second tap must never issue a second pair of prompts.
     */
    public fun begin() {
        if (uiState is VaultSetupUiState.InProgress) return
        uiState = VaultSetupUiState.InProgress
        scope.launch {
            val result =
                try {
                    runSetup()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    // The provider returns typed results for every expected
                    // outcome; anything thrown is a bug, and its message is
                    // not for users. No partial state: `setup()` cleans up
                    // its own aliases on every failure path.
                    uiState = VaultSetupUiState.Retry(FAILED_MESSAGE)
                    return@launch
                }
            apply(result)
        }
    }

    internal fun apply(result: SetupResult) {
        when (result) {
            is SetupResult.Success -> onProvisioned(result.strongBoxBacked)
            is SetupResult.StrongBoxUnavailableFallback -> onProvisioned(false)
            SetupResult.AlreadyInitialised -> onAlreadyInitialised()
            SetupResult.UserCancelled -> uiState = VaultSetupUiState.Retry(CANCELLED_MESSAGE)
            SetupResult.NoBiometricEnrolled -> uiState = VaultSetupUiState.NoBiometricEnrolled
            is SetupResult.Failed ->
                if (EnvelopeUnreadable.matches(result.reason)) {
                    onAlreadyInitialised()
                } else {
                    uiState = VaultSetupUiState.Retry(FAILED_MESSAGE)
                }
        }
    }

    internal companion object {
        const val CANCELLED_MESSAGE: String =
            "Setup was cancelled before your vault key was created. Nothing has been saved yet."
        const val FAILED_MESSAGE: String =
            "Your vault key could not be created. Nothing has been saved. Please try again."
    }
}
