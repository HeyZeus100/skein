package app.skein.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.skein.core.designsystem.icons.SkeinIcons
import app.skein.core.designsystem.theme.SkeinThemeMode
import app.skein.core.navigation.SettingsCategory

/** Shared sections for the NavDisplay Settings category entries. */
@Composable
fun AppearanceSection(
    themeMode: SkeinThemeMode,
    onThemeModeChange: (SkeinThemeMode) -> Unit,
) {
    SettingsSection(title = "Appearance") {
        ThemeModeRow(mode = themeMode, onModeChange = onThemeModeChange)
    }
}

@Composable
fun PrivacyAndSecuritySection(
    flagSecureEnabled: Boolean,
    onFlagSecureEnabledChange: (Boolean) -> Unit,
    idleTimeoutMinutes: Int,
    onIdleTimeoutMinutesChange: (Int) -> Unit,
    lockOnScreenOff: Boolean,
    onLockOnScreenOffChange: (Boolean) -> Unit,
    lockOnBackground: Boolean,
    onLockOnBackgroundChange: (Boolean) -> Unit,
    strongBoxUnavailableFallback: Boolean,
    vaultUnlocked: Boolean,
    onReauthenticate: suspend () -> Boolean,
    onBuildRecoveryExport: suspend (CharArray) -> ByteArray?,
) {
    SettingsSection(title = "Security") {
        FlagSecureToggle(
            flagSecureEnabled = flagSecureEnabled,
            onFlagSecureEnabledChange = onFlagSecureEnabledChange,
        )
        IdleTimeoutRow(
            minutes = idleTimeoutMinutes,
            onMinutesChange = onIdleTimeoutMinutesChange,
        )
        LockOnScreenOffToggle(
            enabled = lockOnScreenOff,
            onEnabledChange = onLockOnScreenOffChange,
        )
        LockOnBackgroundToggle(
            enabled = lockOnBackground,
            onEnabledChange = onLockOnBackgroundChange,
        )
        StrongBoxStatusRow(strongBoxUnavailableFallback = strongBoxUnavailableFallback)
        RecoveryKeyExportSection(
            vaultUnlocked = vaultUnlocked,
            onReauthenticate = onReauthenticate,
            onBuildExport = onBuildRecoveryExport,
        )
    }
}

@Composable
fun KnowledgeAndSearchSection() {
    SettingsSection(title = "Search") {
        SettingsInfoRow(
            label = "Notifications",
            value = "Shows a notification while documents are prepared for search",
            modifier = Modifier.testTag("settings_indexing_hint"),
        )
    }
}

@Composable
fun AboutSection(
    appVersion: String,
    onViewNoticeClick: () -> Unit,
) {
    SettingsSection(title = "About", showDivider = false) {
        SettingsInfoRow(label = "Version", value = appVersion)
        SettingsLinkRow(label = "Open-source licenses", onClick = onViewNoticeClick)
    }
}

/** About category with its licenses view and a return action. */
@Composable
fun AboutCategoryRoute(
    appVersion: String,
    modifier: Modifier = Modifier,
) {
    var showAbout by remember { mutableStateOf(false) }
    if (showAbout) {
        AboutScreen(appVersion = appVersion, onBack = { showAbout = false }, modifier = modifier)
    } else {
        CategoryColumn(modifier) {
            AboutSection(appVersion = appVersion, onViewNoticeClick = { showAbout = true })
        }
    }
}

/**
 * One [SettingsCategory]'s content (`:feature:shell`'s `SettingsCategoryEntry`
 * calls this per `SettingsCategoryKey`, and once more, preselected on
 * [SettingsCategory.APPEARANCE], as the Settings list's detail placeholder on
 * Expanded — spec §8.2). [SettingsCategory.SPACES] is hidden until
 * `skein-3iw` (IA §8a, matching the drawer's Personas row) and
 * [SettingsCategory.ADVANCED] has no real settings yet (IA §7: "Local API /
 * desktop tooling … nothing now") — both show a short placeholder rather than
 * an empty column.
 */
@Composable
fun SettingsCategoryScreen(
    category: SettingsCategory,
    viewModel: SettingsViewModel,
    appVersion: String,
    modifier: Modifier = Modifier,
) {
    when (category) {
        SettingsCategory.ABOUT -> AboutCategoryRoute(appVersion = appVersion, modifier = modifier)
        else ->
            CategoryColumn(modifier) {
                when (category) {
                    SettingsCategory.APPEARANCE ->
                        AppearanceSection(themeMode = viewModel.themeMode, onThemeModeChange = viewModel::setThemeMode)
                    SettingsCategory.PRIVACY_AND_SECURITY ->
                        PrivacyAndSecuritySection(
                            flagSecureEnabled = viewModel.flagSecureEnabled,
                            onFlagSecureEnabledChange = viewModel::setFlagSecureEnabled,
                            idleTimeoutMinutes = viewModel.idleTimeoutMinutes,
                            onIdleTimeoutMinutesChange = viewModel::setIdleTimeoutMinutes,
                            lockOnScreenOff = viewModel.lockOnScreenOff,
                            onLockOnScreenOffChange = viewModel::setLockOnScreenOff,
                            lockOnBackground = viewModel.lockOnBackground,
                            onLockOnBackgroundChange = viewModel::setLockOnBackground,
                            strongBoxUnavailableFallback = viewModel.strongBoxUnavailableFallback,
                            vaultUnlocked = viewModel.vaultUnlocked,
                            onReauthenticate = viewModel.reauthenticate,
                            onBuildRecoveryExport = viewModel.buildRecoveryExport,
                        )
                    SettingsCategory.KNOWLEDGE_AND_SEARCH -> KnowledgeAndSearchSection()
                    SettingsCategory.SPACES ->
                        SettingsInfoRow(label = "Spaces", value = "Coming soon")
                    SettingsCategory.ADVANCED ->
                        SettingsInfoRow(label = "Advanced", value = "Nothing here yet")
                    SettingsCategory.ABOUT -> Unit // handled above
                }
            }
    }
}

@Composable
private fun CategoryColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .widthIn(max = MAX_CONTENT_WIDTH)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                content = content,
            )
        }
    }
}

/** One titled group of settings rows, with a trailing divider unless it's the last section on screen. */
@Composable
private fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    showDivider: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        content()
        if (showDivider) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }
    }
}

/** A tappable row that opens another screen (About's licenses). */
@Composable
private fun SettingsLinkRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.clickable(onClick = onClick).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        // skein-xtov.23.6 (DS6, §9.3 "Expand / collapse"): decorative — the
        // label already says where the row goes; contentDescription = null.
        Icon(
            painter = painterResource(SkeinIcons.Expand),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A non-interactive label/value pair, e.g. About's version row.
 * Internal (not private) so [StrongBoxStatusRow] can reuse it for `E3.I14`'s
 * read-only hardware-backing row.
 */
@Composable
internal fun SettingsInfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Caps line length on unfolded/tablet-width screens so rows don't stretch edge to edge. */
private val MAX_CONTENT_WIDTH = 640.dp
