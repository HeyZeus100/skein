package app.skein.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.skein.feature.shell.theme.SkeinTheme
import app.skein.feature.shell.theme.SkeinThemeMode

/**
 * Previews for [SettingsScreen] (plan `E6.I14`): the empty/default state,
 * the Security toggle flipped off, and both ends of the fold-width range —
 * a folded phone (single narrow column, spec §8.2's compact breakpoint) and
 * an unfolded/tablet width (where [MAX_CONTENT_WIDTH] keeps rows from
 * stretching edge to edge).
 */
@Composable
private fun SettingsScreenPreviewScaffold(
    flagSecureEnabled: Boolean = true,
    appVersion: String = "0.1.0 (1)",
) {
    SkeinTheme(mode = SkeinThemeMode.DARK) {
        Surface(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        ) {
            SettingsScreen(
                flagSecureEnabled = flagSecureEnabled,
                onFlagSecureEnabledChange = {},
                appVersion = appVersion,
            )
        }
    }
}

/** Default/empty state: no settings changed from their defaults (FLAG_SECURE on). */
@Preview(name = "Settings — default state", widthDp = 400, heightDp = 900, showBackground = true)
@Composable
private fun SettingsScreenDefaultPreview() {
    SettingsScreenPreviewScaffold(flagSecureEnabled = true)
}

/** Security's FLAG_SECURE toggle switched off. */
@Preview(name = "Settings — screenshot block toggled off", widthDp = 400, heightDp = 900, showBackground = true)
@Composable
private fun SettingsScreenToggledOffPreview() {
    SettingsScreenPreviewScaffold(flagSecureEnabled = false)
}

/** Folded phone: narrow single-column width (spec §8.2 compact breakpoint). */
@Preview(name = "Settings — folded phone (360dp)", widthDp = 360, heightDp = 800, showBackground = true)
@Composable
private fun SettingsScreenFoldedPreview() {
    SettingsScreenPreviewScaffold()
}

/** Unfolded: wide window — content stays capped at [MAX_CONTENT_WIDTH] and centers instead of stretching. */
@Preview(name = "Settings — unfolded (900dp)", widthDp = 900, heightDp = 900, showBackground = true)
@Composable
private fun SettingsScreenUnfoldedPreview() {
    SettingsScreenPreviewScaffold()
}
