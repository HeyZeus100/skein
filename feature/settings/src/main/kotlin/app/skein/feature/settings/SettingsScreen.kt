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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Settings screen (plan `E6.I14`): Security, Models, Vault, and About
 * sections, plus room for the sections later issues add (`E3.I14`, `E6.I7`,
 * `E6.I10`, `E6.I12`, `E9.I8`). Only the Security section is wired to real
 * state today ([FlagSecureToggle], via [SettingsViewModel]) — Models and
 * Vault are placeholder rows on purpose (their functionality lands in
 * `skein-bxk` + the first-run picker, and in `E2.I10`/vault-erase issues,
 * respectively); About shows the version the host passes in and a "View
 * NOTICE" row — [onViewNoticeClick] fires on tap, and [SettingsRoute] below
 * is what actually wires it to [AboutScreen] (`E9.I8`/`skein-dun`)'s real
 * licenses screen and `assets/licenses.json`.
 *
 * Stateless: takes the current [flagSecureEnabled] value and a change
 * callback rather than a [SettingsViewModel] directly, so it can be
 * previewed and tested without standing up coroutines. The
 * [SettingsViewModel] overload below is the convenience entry point for
 * real hosts; [SettingsRoute] additionally wires up the About/NOTICE
 * navigation for hosts that don't need to customize it.
 *
 * @param appVersion the app's version to show in About (e.g. `"0.1.0 (1)"`
 *   from `:app`'s `BuildConfig` — `:feature:settings` has no access to it).
 */
@Composable
fun SettingsScreen(
    flagSecureEnabled: Boolean,
    onFlagSecureEnabledChange: (Boolean) -> Unit,
    appVersion: String,
    modifier: Modifier = Modifier,
    onExportVaultClick: () -> Unit = {},
    onEraseVaultClick: () -> Unit = {},
    onViewNoticeClick: () -> Unit = {},
    // E3.I14 (skein-up0): additive, defaulted to the plan's secure defaults —
    // see [SettingsViewModel]'s ctor doc for why the existing call sites
    // that don't pass these keep compiling unchanged.
    idleTimeoutMinutes: Int = 5,
    onIdleTimeoutMinutesChange: (Int) -> Unit = {},
    lockOnScreenOff: Boolean = true,
    onLockOnScreenOffChange: (Boolean) -> Unit = {},
    lockOnBackground: Boolean = false,
    onLockOnBackgroundChange: (Boolean) -> Unit = {},
    strongBoxUnavailableFallback: Boolean = false,
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = MAX_CONTENT_WIDTH)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
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
                SettingsPlaceholderRow(label = "Biometric unlock", caption = "Coming in v1.1")
            }

            SettingsSection(title = "Models") {
                val modelCaption = "Available once the first-run model picker lands (skein-bxk)"
                SettingsPlaceholderRow(label = "Qwen 2.5 3B Instruct", caption = modelCaption)
                SettingsPlaceholderRow(label = "Gemma 4 E4B", caption = modelCaption)
                SettingsPlaceholderRow(label = "Import your own GGUF", caption = modelCaption)
            }

            SettingsSection(title = "Vault") {
                SettingsPlaceholderRow(label = "Export vault", onClick = onExportVaultClick)
                SettingsPlaceholderRow(label = "Erase vault", destructive = true, onClick = onEraseVaultClick)
            }

            SettingsSection(title = "About", showDivider = false) {
                SettingsInfoRow(label = "Version", value = appVersion)
                SettingsPlaceholderRow(label = "View NOTICE", onClick = onViewNoticeClick)
            }
        }
    }
}

/** Convenience overload that reads [SettingsViewModel.flagSecureEnabled] and wires its setter. */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    appVersion: String,
    modifier: Modifier = Modifier,
    onExportVaultClick: () -> Unit = {},
    onEraseVaultClick: () -> Unit = {},
    onViewNoticeClick: () -> Unit = {},
) {
    SettingsScreen(
        flagSecureEnabled = viewModel.flagSecureEnabled,
        onFlagSecureEnabledChange = viewModel::setFlagSecureEnabled,
        appVersion = appVersion,
        modifier = modifier,
        onExportVaultClick = onExportVaultClick,
        onEraseVaultClick = onEraseVaultClick,
        onViewNoticeClick = onViewNoticeClick,
        idleTimeoutMinutes = viewModel.idleTimeoutMinutes,
        onIdleTimeoutMinutesChange = viewModel::setIdleTimeoutMinutes,
        lockOnScreenOff = viewModel.lockOnScreenOff,
        onLockOnScreenOffChange = viewModel::setLockOnScreenOff,
        lockOnBackground = viewModel.lockOnBackground,
        onLockOnBackgroundChange = viewModel::setLockOnBackground,
        strongBoxUnavailableFallback = viewModel.strongBoxUnavailableFallback,
    )
}

/**
 * Self-contained Settings entry point (`E9.I8`): renders [SettingsScreen]
 * and swaps to [AboutScreen] as a full-screen overlay when "View NOTICE" is
 * tapped, swapping back on [AboutScreen]'s back action. This is the
 * "overlay — simplest for v1" wiring called for in `skein-dun`; a real
 * nav-graph destination for About is a followup once `:feature:shell`'s
 * `Destination`/nav-graph work lands. [SettingsScreen] itself is untouched
 * by this — hosts that want to own the About navigation themselves (e.g. a
 * future real nav graph) can keep calling [SettingsScreen] directly and
 * wire [SettingsScreen]'s `onViewNoticeClick` to their own destination
 * instead of using this wrapper.
 */
@Composable
fun SettingsRoute(
    viewModel: SettingsViewModel,
    appVersion: String,
    modifier: Modifier = Modifier,
    onExportVaultClick: () -> Unit = {},
    onEraseVaultClick: () -> Unit = {},
) {
    var showAbout by remember { mutableStateOf(false) }

    if (showAbout) {
        AboutScreen(
            appVersion = appVersion,
            onBack = { showAbout = false },
            modifier = modifier,
        )
    } else {
        SettingsScreen(
            viewModel = viewModel,
            appVersion = appVersion,
            modifier = modifier,
            onExportVaultClick = onExportVaultClick,
            onEraseVaultClick = onEraseVaultClick,
            onViewNoticeClick = { showAbout = true },
        )
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

/**
 * An inert row naming a setting that doesn't exist yet, optionally with a
 * [caption] explaining when it will (e.g. "Coming in v1.1"). Only clickable
 * when [onClick] is supplied — Security's v1.1 rows and every Models row
 * pass none, since there is nothing to navigate to yet; Vault's rows pass
 * one so the host can wire the real SAF/erase flows in later issues without
 * this screen changing shape.
 */
@Composable
private fun SettingsPlaceholderRow(
    label: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    destructive: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val rowModifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier
    Row(
        modifier = rowModifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            if (caption != null) {
                Text(
                    text = caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
