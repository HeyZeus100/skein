package app.skein.feature.editor.entries

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import app.skein.core.designsystem.components.SkeinDropdownMenu
import app.skein.core.designsystem.components.rememberSkeinMenuAnchor
import app.skein.core.designsystem.components.skeinMenuAnchor
import app.skein.core.designsystem.icons.SkeinIcons
import app.skein.feature.shell.host.EntryAction

/** Requests the shared file confirmation; opening or dismissing this menu never mutates the vault. */
@Composable
internal fun FileDeleteMenu(onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val anchor = rememberSkeinMenuAnchor()
    val color = MaterialTheme.colorScheme.error
    Box(Modifier.skeinMenuAnchor(anchor)) {
        EntryAction(SkeinIcons.More, "File options", Modifier.testTag(FileRouteTestTags.DELETE_MENU)) {
            expanded = true
        }
        SkeinDropdownMenu(expanded, { expanded = false }, anchor.boundsInWindow) {
            DropdownMenuItem(
                text = { Text("Delete…", color = color) },
                leadingIcon = { Icon(painterResource(SkeinIcons.Delete), contentDescription = null, tint = color) },
                onClick = {
                    expanded = false
                    onDelete()
                },
            )
        }
    }
}
