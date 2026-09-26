package app.skein.feature.shell.nav

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.skein.feature.shell.input.SecureTextField
import app.skein.feature.shell.theme.LocalSkeinTokens

/**
 * Test tag on [CommandBar]'s placeholder [Text] (`skein-wr7m`) — lets a
 * Robolectric test find that specific node via the unmerged semantics tree
 * (the field's own semantics otherwise merge the placeholder into the
 * enclosing [SecureTextField] node) to assert it isn't vertically clipped by
 * [app.skein.feature.shell.theme.SkeinTokens.commandBarHeight].
 */
const val COMMAND_BAR_PLACEHOLDER_TEST_TAG = "commandBarPlaceholder"

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
        // `heightIn(min = ...)`, not `height(...)` (skein-wr7m): a fixed exact
        // height coerced SecureTextField's decoration box below the space its
        // bodyMedium placeholder line + 16dp top/bottom content padding
        // actually need, clipping the bottom of "search or /command". A floor
        // lets the bar grow to fit that content on any device/font metrics
        // while still matching `commandBarHeight`'s intended compact height
        // whenever the content already fits within it.
        modifier = modifier.fillMaxWidth().heightIn(min = tokens.commandBarHeight),
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
                placeholder = {
                    Text("search or /command", modifier = Modifier.testTag(COMMAND_BAR_PLACEHOLDER_TEST_TAG))
                },
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
            // UX-P0-02: one line, ellipsized and width-capped, so a long model
            // id can never crush the command field on the outer screen.
            Text(
                text = "$modelName · $statusGlyph",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .padding(horizontal = 8.dp)
                        .widthIn(max = MODEL_CHIP_MAX_WIDTH)
                        .semantics {
                            contentDescription =
                                "Model status: $modelName, ${if (modelActive) "active" else "paused"}"
                        },
            )
        }
    }
}

/** Stage H6 stop-gap (the chip leaves in Wave 3): the field keeps >= ~220 dp on a 411 dp cover screen. */
private val MODEL_CHIP_MAX_WIDTH = 120.dp
