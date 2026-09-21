// skein-v3wb — state holder for [VaultResetScreen]: the two-step typed
// confirmation in front of `VaultReset.reset()`, following the same
// `runX: suspend () -> Result` seam `VaultSetupState`/`UnlockManager` use so
// the mapping is JVM-testable without Robolectric.
//
// Non-negotiables this file is the one place that enforces at the UI layer
// (the destructive semantics themselves live in `:core:vault`'s
// `VaultReset`):
//  - the confirm step is reachable only by typing the exact word "RESET";
//  - a SECOND, explicit confirmation is required before `reset()` runs —
//    typing the word alone never triggers deletion;
//  - neither confirm button is ever the one a caller should auto-focus (the
//    Composable simply never requests focus on either — see its own file).

package app.skein.feature.shell.auth

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skein.core.vault.lifecycle.VaultResetResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Which of the two confirmation steps [VaultResetScreen] is showing. */
public enum class VaultResetStep {
    /** Type the word "RESET" to continue. */
    TypeConfirmation,

    /** The second, explicit "this cannot be undone" confirmation. */
    FinalConfirm,
}

/**
 * Drives the two-step confirmation and, once both are satisfied, runs
 * [performReset] exactly once.
 */
@Stable
public class VaultResetConfirmState(
    private val scope: CoroutineScope,
    private val performReset: suspend () -> VaultResetResult,
    private val onReset: () -> Unit,
) {
    public var step: VaultResetStep by mutableStateOf(VaultResetStep.TypeConfirmation)
        private set

    public var typedText: String by mutableStateOf("")
        private set

    public var resetting: Boolean by mutableStateOf(false)
        private set

    public var errorMessage: String? by mutableStateOf(null)
        private set

    /** `true` only when [typedText] matches [CONFIRMATION_PHRASE] exactly (case-sensitive). */
    public val canContinue: Boolean get() = typedText == CONFIRMATION_PHRASE

    public fun onTypedTextChange(text: String) {
        typedText = text
    }

    /** Advances to [VaultResetStep.FinalConfirm]. A no-op unless [canContinue]. */
    public fun continueToFinalConfirm() {
        if (!canContinue) return
        step = VaultResetStep.FinalConfirm
    }

    /** Backs out to the first step, clearing the typed text and any error. */
    public fun cancel() {
        step = VaultResetStep.TypeConfirmation
        typedText = ""
        errorMessage = null
    }

    /**
     * The second, explicit confirmation: runs `VaultReset.reset()`. A no-op
     * unless [step] is already [VaultResetStep.FinalConfirm] (i.e. the typed
     * confirmation already happened) and no reset is already in flight —
     * both confirms are required, and a double-tap never issues a second
     * call.
     */
    public fun confirmReset() {
        if (step != VaultResetStep.FinalConfirm || resetting) return
        resetting = true
        errorMessage = null
        scope.launch {
            val result =
                try {
                    performReset()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    resetting = false
                    errorMessage = FAILED_MESSAGE
                    return@launch
                }
            when (result) {
                VaultResetResult.Success -> onReset()
                VaultResetResult.RefusedUnlocked -> {
                    resetting = false
                    errorMessage = REFUSED_UNLOCKED_MESSAGE
                }
                is VaultResetResult.Failed -> {
                    resetting = false
                    errorMessage = FAILED_MESSAGE
                }
            }
        }
    }

    internal companion object {
        const val CONFIRMATION_PHRASE: String = "RESET"
        const val REFUSED_UNLOCKED_MESSAGE: String =
            "The vault is still unlocked. Lock it first, then try resetting again."
        const val FAILED_MESSAGE: String =
            "The vault could not be reset. Please try again."
    }
}
