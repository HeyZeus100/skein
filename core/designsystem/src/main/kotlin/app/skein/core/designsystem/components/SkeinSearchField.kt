// skein-xtov.23.8 (DS8, docs/ux/DESIGN_SYSTEM.md §10.24, §5.4): the filled
// search field (list pane "Search chats", Knowledge search). Built on
// `BasicTextField`: Material's `TextField` is 56 dp minimum and `SearchBar`
// is still experimental with its own expanding layout (§10.24).
package app.skein.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.skein.core.designsystem.icons.SkeinIcons
import app.skein.core.designsystem.theme.SkeinSize
import app.skein.core.designsystem.theme.SkeinSpacing

/**
 * A search field (§10.24): filled `surfaceContainerHigh`, radius 12, at least
 * 48 dp tall (it grows with font scale), a leading `search` icon, the
 * [placeholder] in `onSurfaceVariant` (also the field's accessible name,
 * kept after the user types), and a trailing `close` button (48 dp,
 * labelled [clearLabel]) whenever [query] is not empty — clearing calls
 * [onQueryChange] with "". The keyboard shows a Search action that calls
 * [onSearch]. Focus draws a 2 dp `primary` outline in every input mode (§5.4).
 *
 * Filtering in place is the caller's: [onQueryChange] fires on every edit.
 */
@Composable
fun SkeinSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSearch: (String) -> Unit = {},
    clearLabel: String = "Clear search",
) {
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = MaterialTheme.shapes.medium
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
        interactionSource = interactionSource,
        decorationBox = { innerTextField ->
            Row(
                modifier =
                    Modifier
                        .heightIn(min = SkeinSize.touchTarget)
                        .background(colors.surfaceContainerHigh, shape)
                        .then(if (focused) Modifier.border(FOCUSED_BORDER, colors.primary, shape) else Modifier)
                        .padding(start = SkeinSpacing.space12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(SkeinIcons.Search),
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(SkeinSize.iconStandard),
                )
                Spacer(Modifier.width(SkeinSpacing.space12))
                Box(Modifier.weight(1f).padding(vertical = SkeinSpacing.space12)) {
                    // Always composed, only hidden once there's a query: it stays the field's
                    // accessible name ("fold, edit box, Search chats") after the user types.
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.alpha(if (query.isEmpty()) 1f else 0f),
                    )
                    innerTextField()
                }
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            painter = painterResource(SkeinIcons.Close),
                            contentDescription = clearLabel,
                            tint = colors.onSurfaceVariant,
                        )
                    }
                } else {
                    Spacer(Modifier.width(SkeinSpacing.space12))
                }
            }
        },
    )
}

/** §5.4: focused text fields use a 2 dp `primary` outline. */
private val FOCUSED_BORDER = 2.dp
