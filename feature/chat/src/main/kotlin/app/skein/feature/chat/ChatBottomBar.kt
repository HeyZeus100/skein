// skein-6as (E6.I8). Bottom bar (spec §8.4): `$` prompt glyph,
// `SecureBasicTextField` reply, `[[` wikilink autocomplete (E7.I5, shared),
// `/` command palette (an injected callback — see [onSlashCommand]'s doc:
// the palette itself lives in `:feature:shell`'s `CommandRegistry`, out of
// this bead's reach per the hard boundary), 📎 attach (SAF `OpenDocument` ->
// `ImportService` -> `[[attachment title]]`), ⏎ send.
//
// Uses `SecureBasicTextField` (not the String-only `SecureTextField`) —
// both live in `:feature:shell`'s `input` package and both are on
// `RawTextFieldTest`'s allowlist; this bar needs the `TextFieldValue`
// overload specifically for caret position, which `[[` trigger detection
// (`AutocompleteHost.textBeforeCursor`) depends on. `SecureBasicTextField`'s
// own KDoc names exactly this need ("the cursor/selection carried by
// TextFieldValue — the Material 3 String overload does not expose that").
package app.skein.feature.chat

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.skein.feature.editor.autocomplete.AutocompleteHost
import app.skein.feature.editor.autocomplete.Suggestion
import app.skein.feature.editor.autocomplete.WikilinkAutocompletePopup
import app.skein.feature.editor.autocomplete.rememberWikilinkAutocompleteState
import app.skein.feature.editor.autocomplete.wikilinkAutocompleteKeyEvents
import app.skein.feature.shell.input.SecureBasicTextField
import app.skein.feature.shell.theme.LocalSkeinTokens
import kotlinx.coroutines.launch
import java.io.InputStream

public const val PROMPT_GLYPH_TEST_TAG: String = "app.skein.feature.chat.PromptGlyph"
public const val COMPOSER_TEST_TAG: String = "app.skein.feature.chat.Composer"
public const val ATTACH_BUTTON_TEST_TAG: String = "app.skein.feature.chat.AttachButton"
public const val SEND_BUTTON_TEST_TAG: String = "app.skein.feature.chat.SendButton"
public const val CANCEL_BUTTON_TEST_TAG: String = "app.skein.feature.chat.CancelButton"

/**
 * @param onSlashCommand fired when the composer's entire content becomes
 *   `"/"` (its first character). The bead allows an injected callback here
 *   in place of a direct `CommandRegistry` call: `:feature:shell`'s command
 *   palette API scoped to a single surface is not reachable from this
 *   module (hard boundary — this bead does not touch `:feature:shell`
 *   internals beyond `SecureBasicTextField`/theme tokens). Noted for the
 *   wiring bead (`skein-whg8`).
 * @param onAttach resolves an imported attachment to the `[[title]]` text
 *   to insert — see `ChatViewModel.attach`.
 */
@Composable
public fun ChatBottomBar(
    isGenerating: Boolean,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    wikilinkSuggest: suspend (String) -> List<Suggestion>,
    onAttach: suspend (displayName: String, mimeType: String, input: InputStream) -> String?,
    modifier: Modifier = Modifier,
    onCreateWikilink: suspend (String) -> Unit = {},
    onSlashCommand: () -> Unit = {},
) {
    var fieldValue by remember { mutableStateOf(TextFieldValue("")) }
    val scope = rememberCoroutineScope()

    val host =
        remember {
            object : AutocompleteHost {
                override val textBeforeCursor: String
                    get() = fieldValue.text.substring(0, fieldValue.selection.end.coerceIn(0, fieldValue.text.length))

                override fun replaceRange(
                    start: Int,
                    end: Int,
                    with: String,
                ) {
                    val text = fieldValue.text
                    val newText = text.substring(0, start) + with + text.substring(end)
                    fieldValue = TextFieldValue(text = newText, selection = TextRange(start + with.length))
                }
            }
        }
    val autocompleteState =
        rememberWikilinkAutocompleteState(host = host, suggest = wikilinkSuggest, onCreate = onCreateWikilink)

    fun sendCurrentText() {
        val text = fieldValue.text
        if (text.isBlank() || isGenerating) return
        onSend(text)
        fieldValue = TextFieldValue("")
        autocompleteState.dismiss()
    }

    val context = LocalContext.current
    val attachLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val resolver = context.contentResolver
                val mimeType = resolver.getType(uri) ?: DEFAULT_MIME_TYPE
                val displayName = queryDisplayName(resolver, uri) ?: uri.lastPathSegment ?: DEFAULT_ATTACHMENT_NAME
                resolver.openInputStream(uri)?.use { input ->
                    val inserted = onAttach(displayName, mimeType, input)
                    if (inserted != null) {
                        val separator = if (fieldValue.text.isEmpty() || fieldValue.text.endsWith(" ")) "" else " "
                        val newText = fieldValue.text + separator + inserted
                        fieldValue = TextFieldValue(text = newText, selection = TextRange(newText.length))
                    }
                }
            }
        }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().padding(8.dp),
    ) {
        Text(
            text = LocalSkeinTokens.current.glyphs.searchPrompt,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 4.dp).testTag(PROMPT_GLYPH_TEST_TAG),
        )
        WikilinkAutocompletePopup(state = autocompleteState)
        SecureBasicTextField(
            value = fieldValue,
            onValueChange = { newValue ->
                val wasEmpty = fieldValue.text.isEmpty()
                fieldValue = newValue
                autocompleteState.onTextChanged()
                if (wasEmpty && newValue.text == "/") onSlashCommand()
            },
            textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
            imeAction = ImeAction.Send,
            keyboardActions = KeyboardActions(onSend = { sendCurrentText() }),
            modifier =
                Modifier
                    .weight(1f)
                    .testTag(COMPOSER_TEST_TAG)
                    .wikilinkAutocompleteKeyEvents(autocompleteState),
        )
        IconButton(
            onClick = { attachLauncher.launch(arrayOf("*/*")) },
            modifier = Modifier.testTag(ATTACH_BUTTON_TEST_TAG),
        ) {
            Text("📎")
        }
        if (isGenerating) {
            IconButton(onClick = onCancel, modifier = Modifier.testTag(CANCEL_BUTTON_TEST_TAG)) {
                Text("■")
            }
        } else {
            IconButton(
                onClick = { sendCurrentText() },
                enabled = fieldValue.text.isNotBlank(),
                modifier = Modifier.testTag(SEND_BUTTON_TEST_TAG),
            ) {
                Text("⏎")
            }
        }
    }
}

private fun queryDisplayName(
    resolver: ContentResolver,
    uri: Uri,
): String? =
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
    }

private const val DEFAULT_MIME_TYPE = "application/octet-stream"
private const val DEFAULT_ATTACHMENT_NAME = "attachment"
