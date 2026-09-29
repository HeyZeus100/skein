// skein-xtov.23.7 (DS7, docs/ux/DESIGN_SYSTEM.md §10.11, §11.4, §14.17;
// docs/ux/OBJECT_LIFECYCLE_SPEC.md: no-undo delete, copy per object).
package app.skein.core.designsystem.components

import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import app.skein.core.designsystem.theme.LocalSkeinColors

/**
 * The destructive confirmation (spec §10.11, the pattern named again at
 * §11.4): title names the object ("Delete “X”?"), one consequence sentence,
 * Cancel · Delete in destructive styling. [title] and [consequence] are
 * supplied whole by the caller — the object's own name, curly quotes, and
 * what is lost/kept/undoable are that object kind's own words
 * (`OBJECT_LIFECYCLE_SPEC.md`'s "copy per object"); this component only lays
 * the pattern out.
 *
 * Two behaviours the acceptance criteria name directly (§14.17): keyboard
 * focus starts on **Cancel**, so a bare Enter never confirms a delete
 * (Material's `AlertDialogFlowRow` already renders `dismissButton` before
 * `confirmButton`, i.e. Cancel left of Delete, matching §10.11's anatomy
 * unassisted); and the dialog's container carries a TalkBack pane title
 * (via [androidx.compose.ui.semantics.paneTitle]) so a screen reader
 * announces the dialog by its own heading, the nearest semantics primitive
 * Compose has to the web's `role="alertdialog"`.
 *
 * @param confirmLabel the verb the confirm button repeats (§11.4: "the
 *   confirm button repeats the verb…, not 'OK' or 'Yes'") — default "Delete"
 *   covers the common case; a caller doing something else destructive
 *   (e.g. "Erase Skein") passes its own.
 */
@Composable
fun SkeinDestructiveDialog(
    title: String,
    consequence: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    confirmLabel: String = "Delete",
    cancelLabel: String = "Cancel",
) {
    if (!LocalSkeinWindowActive.current) return
    val cancelFocusRequester = remember { FocusRequester() }
    val partitioned = LocalSkeinWindowPartitions.current != null
    LaunchedEffect(partitioned) { if (!partitioned) cancelFocusRequester.requestFocus() }

    SkeinAlertDialog(
        onDismissRequest = onDismiss,
        modifier =
            modifier
                .testTag(SKEIN_DESTRUCTIVE_DIALOG_TEST_TAG)
                .semantics { paneTitle = title },
        shape = SkeinDialogDefaults.shape,
        containerColor = SkeinDialogDefaults.containerColor,
        tonalElevation = SkeinDialogDefaults.tonalElevation,
        title = { Text(title) },
        text = { Text(consequence) },
        dismissButton = {
            LaunchedEffect(partitioned) {
                if (partitioned) {
                    withFrameNanos { }
                    cancelFocusRequester.requestFocus()
                }
            }
            TextButton(
                onClick = onDismiss,
                modifier =
                    Modifier
                        .then(if (partitioned) Modifier.focusProperties { canFocus = true } else Modifier)
                        .focusRequester(cancelFocusRequester)
                        .testTag(SKEIN_DESTRUCTIVE_DIALOG_CANCEL_TEST_TAG),
            ) { Text(cancelLabel) }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = LocalSkeinColors.current.destructive),
                modifier = Modifier.testTag(SKEIN_DESTRUCTIVE_DIALOG_CONFIRM_TEST_TAG),
            ) { Text(confirmLabel) }
        },
    )
}

const val SKEIN_DESTRUCTIVE_DIALOG_TEST_TAG: String = "app.skein.core.designsystem.components.SkeinDestructiveDialog"
const val SKEIN_DESTRUCTIVE_DIALOG_CANCEL_TEST_TAG: String = "$SKEIN_DESTRUCTIVE_DIALOG_TEST_TAG.Cancel"
const val SKEIN_DESTRUCTIVE_DIALOG_CONFIRM_TEST_TAG: String = "$SKEIN_DESTRUCTIVE_DIALOG_TEST_TAG.Confirm"
