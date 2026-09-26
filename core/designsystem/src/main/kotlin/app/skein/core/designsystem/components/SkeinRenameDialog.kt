// skein-xtov.23.7 (DS7, docs/ux/DESIGN_SYSTEM.md §10.11 "Rename").
package app.skein.core.designsystem.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import app.skein.core.designsystem.theme.SkeinRadius

/** Default cap on a rename dialog's [SkeinRenameDialog.maxLength] — no name spec picks a number, so this is a generous, generic ceiling. */
const val SKEIN_RENAME_DEFAULT_MAX_LENGTH: Int = 200

/**
 * The rename dialog (spec §10.11 "Rename"): [initialName] prefilled with all
 * its text selected (so typing replaces it outright), [confirmLabel]
 * disabled while the field is blank or unchanged from [initialName], and a
 * blank field shows [blankMessage] as the field's own error/supporting text
 * (§10.21 "Field" error pattern) rather than a separate banner.
 *
 * [title] is the caller's own ("Rename chat" / "Rename note", §10.11);
 * [onConfirm] receives the field's current text exactly as typed (not
 * trimmed) so a caller can apply its own name-validation rules beyond
 * blank/unchanged/too-long.
 */
@Composable
fun SkeinRenameDialog(
    title: String,
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Name",
    maxLength: Int = SKEIN_RENAME_DEFAULT_MAX_LENGTH,
    confirmLabel: String = "Save",
    cancelLabel: String = "Cancel",
    blankMessage: String = "Name can't be empty",
) {
    var field by
        remember(initialName) {
            mutableStateOf(TextFieldValue(text = initialName, selection = TextRange(0, initialName.length)))
        }
    val isBlank = field.text.isBlank()
    val isUnchanged = field.text == initialName
    val confirmEnabled = !isBlank && !isUnchanged
    val supportingText: (@Composable () -> Unit)? = if (isBlank) ({ Text(blankMessage) }) else null

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier.testTag(SKEIN_RENAME_DIALOG_TEST_TAG),
        shape = SkeinDialogDefaults.shape,
        containerColor = SkeinDialogDefaults.containerColor,
        tonalElevation = SkeinDialogDefaults.tonalElevation,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = field,
                // Caps at maxLength (§10.11 doesn't name a copy for this — a
                // silent truncation is enough; a caller wanting a
                // counter/message wraps this component's own state instead).
                onValueChange = { candidate ->
                    field =
                        if (candidate.text.length <= maxLength) {
                            candidate
                        } else {
                            candidate.copy(text = candidate.text.take(maxLength), selection = TextRange(maxLength))
                        }
                },
                label = { Text(label) },
                singleLine = true,
                isError = isBlank,
                supportingText = supportingText,
                shape = RoundedCornerShape(SkeinRadius.radiusMd),
                modifier = Modifier.fillMaxWidth().testTag(SKEIN_RENAME_DIALOG_FIELD_TEST_TAG),
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(SKEIN_RENAME_DIALOG_CANCEL_TEST_TAG)) {
                Text(cancelLabel)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(field.text) },
                enabled = confirmEnabled,
                modifier = Modifier.testTag(SKEIN_RENAME_DIALOG_CONFIRM_TEST_TAG),
            ) { Text(confirmLabel) }
        },
    )
}

const val SKEIN_RENAME_DIALOG_TEST_TAG: String = "app.skein.core.designsystem.components.SkeinRenameDialog"
const val SKEIN_RENAME_DIALOG_FIELD_TEST_TAG: String = "$SKEIN_RENAME_DIALOG_TEST_TAG.Field"
const val SKEIN_RENAME_DIALOG_CANCEL_TEST_TAG: String = "$SKEIN_RENAME_DIALOG_TEST_TAG.Cancel"
const val SKEIN_RENAME_DIALOG_CONFIRM_TEST_TAG: String = "$SKEIN_RENAME_DIALOG_TEST_TAG.Confirm"
