package app.skein.feature.shell.layout

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Fills the window edge-to-edge with the theme background while [content]
 * stays inset from the status bar, display cutout, navigation bar, and IME
 * (bd `skein-1vfg`): targetSdk 37 forces edge-to-edge on Android 15+, so
 * every full-screen root drawn *outside* [app.skein.feature.shell.SkeinApp]'s
 * own `Box` — `MainActivity`'s vault-gate screens (setup / unlock / opening /
 * open-failed / recovery / reset) and `app.skein.feature.graph.GraphScreen`'s
 * overlay — needs this exact pair of modifiers: the background [Surface]
 * painted to the true window edges (so the status/navigation bar area shows
 * the theme color, not a blank/black band), and the interactive [content]
 * padded off those edges (so nothing sits under the clock or behind the
 * gesture nav). One shared composable rather than each root re-deriving the
 * same two modifiers (Ponytail rung 2).
 *
 * [content] receives the already-inset [Modifier] to apply to its own root
 * — typically that root's existing `modifier` parameter — so the background
 * [Surface] itself is never shrunk by the padding.
 *
 * [WindowInsets.safeDrawing] already unions `ime()` with the system bars and
 * display cutout, so a screen with a text field (e.g. `VaultResetScreen`'s
 * "type RESET" field, or `VaultSetupScreen`'s recovery passphrase field)
 * gets keyboard clearance from this same call — no second `imePadding()`
 * needed alongside it.
 */
@Composable
fun EdgeToEdgeSurface(
    modifier: Modifier = Modifier,
    content: @Composable (contentModifier: Modifier) -> Unit,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        content(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing))
    }
}
