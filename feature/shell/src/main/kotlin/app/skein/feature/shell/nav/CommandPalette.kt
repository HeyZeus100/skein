package app.skein.feature.shell.nav

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.skein.feature.shell.testing.ShellTestTags

/**
 * The `/` command palette (spec §8.2): [commands] — already filtered by
 * [CommandBarState.paletteCommands] as the user types after the leading
 * `/` — rendered as a list below [CommandBar]. Tapping a row runs that
 * command with no argument via [onSelect] (`/new note` makes an "Untitled"
 * note); Enter ([CommandBarState.onSubmit]) runs a typed `/<keyword> arg`.
 */
@Composable
fun CommandPalette(
    commands: List<Command>,
    onSelect: (Command) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().testTag(ShellTestTags.COMMAND_PALETTE),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        if (commands.isEmpty()) {
            Text(
                text = "No matching commands",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp),
            )
        } else {
            LazyColumn {
                items(commands, key = { it.keyword }) { command ->
                    Text(
                        text = "/${command.keyword} ${command.hint}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(command) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}
