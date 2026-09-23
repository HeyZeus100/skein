package app.skein.feature.shell.nav

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.skein.feature.shell.input.SecureTextField
import app.skein.feature.shell.theme.LocalSkeinTokens

/**
 * Persistent top command bar (spec §8.2/§8.4, plan `E6.I3`): `≡` hamburger ·
 * `$`-prompt search/slash-command field · model status. State-hoisted —
 * [query] and its callback are owned by [NavState] (or a caller's own
 * `mutableStateOf`), so this composable has no state of its own beyond what
 * Compose needs for the text field's cursor/selection.
 *
 * Slash-command *execution* is out of scope for this issue: recognizing a
 * leading `/` only changes the trailing hint to "press ⏎ to run" — nothing
 * is dispatched.
 *
 * @param query current command bar text.
 * @param onQueryChange fired on every keystroke.
 * @param onMenuClick `≡` tap — caller wires this to [NavState.openDrawer].
 * @param modelName short model identifier, e.g. `"qwen"`.
 * @param modelActive `true` renders [app.skein.feature.shell.theme.SkeinTokens.Glyphs.modelActive]
 *   (`●`), `false` renders [app.skein.feature.shell.theme.SkeinTokens.Glyphs.modelPaused] (`⏸`).
 * @param onSubmit fired on the IME's Enter/Search action — `CommandBarHost`
 *   wires this to [CommandBarState.onSubmit] (E6.I4 slice A): runs the
 *   matched `/` command, or opens the top plain-text search hit.
 */
@Composable
fun CommandBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onMenuClick: () -> Unit,
    modelName: String,
    modelActive: Boolean,
    modifier: Modifier = Modifier,
    onSubmit: () -> Unit = {},
) {
    val tokens = LocalSkeinTokens.current
    val isCommand = query.startsWith("/")

    Surface(
        modifier = modifier.fillMaxWidth().height(tokens.commandBarHeight),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onMenuClick,
                modifier = Modifier.semantics { contentDescription = "Open navigation drawer" },
            ) {
                Text(text = tokens.glyphs.hamburger, style = MaterialTheme.typography.titleMedium)
            }

            SecureTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                placeholder = { Text("search or /command") },
                leadingIcon = { Text(text = tokens.glyphs.searchPrompt, style = MaterialTheme.typography.bodyMedium) },
                imeAction = ImeAction.Search,
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                colors =
                    TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
            )

            if (isCommand) {
                Text(
                    text = "Press ⏎ to run",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }

            val statusGlyph = if (modelActive) tokens.glyphs.modelActive else tokens.glyphs.modelPaused
            Text(
                text = "$modelName · $statusGlyph",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier =
                    Modifier
                        .padding(horizontal = 8.dp)
                        .semantics {
                            contentDescription =
                                "Model status: $modelName, ${if (modelActive) "active" else "paused"}"
                        },
            )
        }
    }
}
