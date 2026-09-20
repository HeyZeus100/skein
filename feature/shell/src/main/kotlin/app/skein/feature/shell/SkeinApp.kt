package app.skein.feature.shell

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.skein.feature.shell.theme.SkeinTheme
import app.skein.feature.shell.theme.SkeinThemeMode

/**
 * The app shell: theme + a single root surface. This is `E6.I1` — theme,
 * typography, tokens, and the `MainActivity` host only. Real navigation
 * (command bar, hamburger drawer, adaptive panes, tabs, screens) lands in
 * `E6.I2`+ and is intentionally not here.
 */
@Composable
fun SkeinApp(themeMode: SkeinThemeMode = SkeinThemeMode.SYSTEM) {
    SkeinTheme(mode = themeMode) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Text(
                text = "Skein",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}
