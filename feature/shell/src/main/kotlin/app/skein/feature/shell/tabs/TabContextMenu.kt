package app.skein.feature.shell.tabs

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import app.skein.feature.shell.theme.LocalSkeinTokens

/**
 * Long-press / middle-click menu for a single tab (spec §8.3, plan `E6.I5`
 * acceptance: "long-press menu shows the four actions"): Pin (hidden once
 * already pinned), Close, Close others (hidden when it's the only tab), and
 * Open in split — the last one only appears when split view is actually
 * available (`E6.I2`'s `SplitHost`; wiring the ⧉ toggle itself is `E6.I6`).
 */
@Composable
fun TabContextMenu(
    expanded: Boolean,
    tab: Tab,
    canCloseOthers: Boolean,
    splitAvailable: Boolean,
    onDismiss: () -> Unit,
    onPin: () -> Unit,
    onClose: () -> Unit,
    onCloseOthers: () -> Unit,
    onOpenInSplit: () -> Unit,
) {
    val glyphs = LocalSkeinTokens.current.glyphs
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (!tab.pinned) {
            DropdownMenuItem(
                text = { Text("Pin") },
                onClick = {
                    onPin()
                    onDismiss()
                },
            )
        }
        DropdownMenuItem(
            text = { Text("Close") },
            onClick = {
                onClose()
                onDismiss()
            },
        )
        if (canCloseOthers) {
            DropdownMenuItem(
                text = { Text("Close others") },
                onClick = {
                    onCloseOthers()
                    onDismiss()
                },
            )
        }
        if (splitAvailable) {
            DropdownMenuItem(
                text = { Text("Open in split ${glyphs.split}") },
                onClick = {
                    onOpenInSplit()
                    onDismiss()
                },
            )
        }
    }
}
