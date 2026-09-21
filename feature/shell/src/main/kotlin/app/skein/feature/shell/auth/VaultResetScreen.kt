// skein-v3wb — the app's one sanctioned destructive-flow UI: confirms, then
// runs `VaultReset.reset()`.
//
// Reachable ONLY from `BiometricUnlockScreen`'s corrupt/unreadable-envelope
// state today (its `onResetRequested` param) — never from a generic
// failure, never auto-shown. A future Settings › Security entry point may
// reuse this same composable behind the identical two-step confirmation;
// wiring that is out of scope here (see the bd's concurrency note).
//
// Confirmation UX (non-negotiable): the user must type the literal word
// "RESET" to unlock the "Continue" button, then face a SECOND, separate
// screen with its own explicit "Permanently delete vault" button before
// anything is deleted. Neither button is focus-requested — the Cancel
// button is composed first on both steps and nothing here calls
// `FocusRequester.requestFocus()`, so there is no default-focused
// destructive control.

package app.skein.feature.shell.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.skein.core.vault.lifecycle.VaultReset
import app.skein.feature.shell.testing.ShellTestTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Drives [VaultResetConfirmState] over [vaultReset] and renders its two
 * steps. [onReset] is called once `VaultReset.reset()` has succeeded — the
 * host is expected to route to `VaultSetupScreen` from there, since the
 * envelope (and therefore `VaultKeyProvider.isInitialised()`) is now gone.
 * [onDismiss] backs out at any point without deleting anything.
 */
@Composable
public fun VaultResetScreen(
    vaultReset: VaultReset,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val state =
        remember(vaultReset) {
            VaultResetConfirmState(
                scope = scope,
                performReset = { withContext(Dispatchers.IO) { vaultReset.reset() } },
                onReset = onReset,
            )
        }

    Box(
        modifier = modifier.fillMaxSize().testTag(ShellTestTags.VAULT_RESET_ROOT),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SPACING),
            modifier = Modifier.padding(horizontal = GUTTER).widthIn(max = MAX_CONTENT_WIDTH),
        ) {
            when (state.step) {
                VaultResetStep.TypeConfirmation -> TypeConfirmationStep(state, onDismiss)
                VaultResetStep.FinalConfirm -> FinalConfirmStep(state, onDismiss)
            }
        }
    }
}

@Composable
private fun TypeConfirmationStep(
    state: VaultResetConfirmState,
    onDismiss: () -> Unit,
) {
    Text(
        text = "Reset vault",
        style = MaterialTheme.typography.titleLarge,
        textAlign = TextAlign.Center,
    )
    Text(
        text = TYPE_STEP_EXPLANATION,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
    OutlinedTextField(
        value = state.typedText,
        onValueChange = state::onTypedTextChange,
        label = { Text("Type RESET to continue") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(ShellTestTags.VAULT_RESET_CONFIRM_FIELD),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(SPACING)) {
        // Cancel composed first and never focus-requested: the non-destructive
        // control is the natural first stop, not the (still-disabled) continue button.
        OutlinedButton(
            onClick = onDismiss,
            modifier = Modifier.testTag(ShellTestTags.VAULT_RESET_CANCEL_BUTTON),
        ) {
            Text("Cancel")
        }
        Button(
            onClick = state::continueToFinalConfirm,
            enabled = state.canContinue,
            modifier = Modifier.testTag(ShellTestTags.VAULT_RESET_CONTINUE_BUTTON),
        ) {
            Text("Continue")
        }
    }
}

@Composable
private fun FinalConfirmStep(
    state: VaultResetConfirmState,
    onDismiss: () -> Unit,
) {
    Text(
        text = "This cannot be undone",
        style = MaterialTheme.typography.titleLarge,
        textAlign = TextAlign.Center,
    )
    Text(
        text = FINAL_STEP_EXPLANATION,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
    state.errorMessage?.let { message ->
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag(ShellTestTags.VAULT_RESET_MESSAGE),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(SPACING)) {
        // Cancel composed first, never focus-requested — same discipline as
        // the first step. Backing out here also clears the typed text, so a
        // re-entry starts the confirmation over from scratch.
        OutlinedButton(
            onClick = {
                state.cancel()
                onDismiss()
            },
            modifier = Modifier.testTag(ShellTestTags.VAULT_RESET_CANCEL_BUTTON),
        ) {
            Text("Cancel")
        }
        Button(
            onClick = state::confirmReset,
            enabled = !state.resetting,
            modifier = Modifier.testTag(ShellTestTags.VAULT_RESET_FINAL_BUTTON),
        ) {
            Text(if (state.resetting) "Resetting…" else "Permanently delete vault")
        }
    }
}

private val SPACING = 12.dp
private val GUTTER = 24.dp
private val MAX_CONTENT_WIDTH = 480.dp

private const val TYPE_STEP_EXPLANATION =
    "Your vault's key file cannot be read, so it cannot be unlocked. Resetting deletes your notes, " +
        "attachments, and vault keys from this device — this cannot be undone. To continue, type RESET below."

private const val FINAL_STEP_EXPLANATION =
    "All notes, attachments, and keys on this device will be permanently deleted. There is no way " +
        "to recover them afterward. This is the last step before that happens."
