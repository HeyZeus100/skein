package app.skein.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.skein.core.designsystem.preview.SkeinDevicePreviews
import app.skein.core.designsystem.preview.SkeinFoldPreviews
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.core.designsystem.theme.SkeinThemeMode
import app.skein.core.navigation.SettingsCategory
import kotlinx.coroutines.flow.flowOf

/** Privacy category previews at the supported phone and Fold widths. */
@Composable
private fun SettingsScreenPreviewScaffold(
    flagSecureEnabled: Boolean = true,
    appVersion: String = "0.1.0 (1)",
) {
    SkeinTheme(mode = SkeinThemeMode.DARK) {
        Surface(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        ) {
            SettingsCategoryScreen(
                category = SettingsCategory.PRIVACY_AND_SECURITY,
                viewModel = rememberSettingsViewModel(flowOf(flagSecureEnabled), {}),
                appVersion = appVersion,
            )
        }
    }
}

/**
 * Default/empty state: no settings changed from their defaults (FLAG_SECURE
 * on). skein-xtov.23.20 (ML-1 example): the measured Fold windows, replacing
 * the ad hoc 400 dp width this preview used before (no density).
 */
@SkeinFoldPreviews
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

/**
 * Folded phone: narrow single-column width (spec §8.2 compact breakpoint).
 * skein-xtov.23.20 (ML-1 example): tier T1's four measured windows in place
 * of the single ad hoc 360 dp width this preview used before.
 */
@SkeinDevicePreviews
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
