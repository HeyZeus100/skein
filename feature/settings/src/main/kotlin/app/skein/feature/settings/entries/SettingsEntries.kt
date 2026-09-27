// skein-xtov.24.9 (AL-09b): the Settings destination's NavDisplay entries
// (ADAPTIVE_LAYOUT_SPEC.md §8.2, §8.9): categories │ category — splitting
// today's single `SettingsScreen` column (this module's own
// `SettingsCategoryScreen`/`AppearanceSection` etc.) into one screen per
// category without touching any setting's behaviour.
package app.skein.feature.settings.entries

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.skein.core.designsystem.icons.SkeinIcons
import app.skein.core.navigation.Destination
import app.skein.core.navigation.SettingsCategory
import app.skein.core.navigation.SettingsCategoryKey
import app.skein.core.navigation.SettingsHomeKey
import app.skein.core.navigation.SkeinKey
import app.skein.feature.settings.SettingsCategoryScreen
import app.skein.feature.settings.SettingsViewModel
import app.skein.feature.shell.host.EntryTopBar
import app.skein.feature.shell.host.SkeinShellState

/** What the Settings entries need: the same view model/app version [app.skein.feature.settings.SettingsRoute] used. */
class SettingsEntryDeps(
    val viewModel: SettingsViewModel,
    val appVersion: String,
)

object SettingsEntryTestTags {
    const val LIST = "settings_entry_list"
}

/**
 * Phone-categories order (spec §8.9): Appearance · Privacy & security ·
 * [SettingsCategory.SPACES] hidden until `skein-3iw` (IA §8a, matching the
 * drawer's Personas row) · Knowledge & search · About · Advanced (no real
 * settings yet).
 */
private val VISIBLE_CATEGORIES =
    listOf(
        SettingsCategory.APPEARANCE,
        SettingsCategory.PRIVACY_AND_SECURITY,
        SettingsCategory.KNOWLEDGE_AND_SEARCH,
        SettingsCategory.ABOUT,
        SettingsCategory.ADVANCED,
    )

private fun SettingsCategory.label(): String =
    when (this) {
        SettingsCategory.APPEARANCE -> "Appearance"
        SettingsCategory.PRIVACY_AND_SECURITY -> "Privacy & security"
        SettingsCategory.SPACES -> "Spaces"
        SettingsCategory.KNOWLEDGE_AND_SEARCH -> "Knowledge & search"
        SettingsCategory.ABOUT -> "About"
        SettingsCategory.ADVANCED -> "Advanced"
    }

/** One Settings key's content (§8.2). */
@Composable
fun SettingsEntry(
    key: SkeinKey,
    shell: SkeinShellState,
    deps: SettingsEntryDeps,
) {
    when (key) {
        SettingsHomeKey -> SettingsListEntry(shell)
        is SettingsCategoryKey -> SettingsCategoryEntry(key, shell, deps)
        else -> Unit
    }
}

/** spec §8.9: "Appearance preselected as the placeholder detail so the right side is never empty." */
@Composable
fun SettingsDetailPlaceholder(deps: SettingsEntryDeps) {
    SettingsCategoryScreen(
        category = SettingsCategory.APPEARANCE,
        viewModel = deps.viewModel,
        appVersion = deps.appVersion,
    )
}

@Composable
private fun SettingsListEntry(shell: SkeinShellState) {
    val selected = (shell.nav.stack(Destination.SETTINGS).lastOrNull() as? SettingsCategoryKey)?.category
    Column(Modifier.fillMaxSize()) {
        shell.EntryTopBar(SettingsHomeKey, "Settings")
        LazyColumn(Modifier.weight(1f).fillMaxSize().testTag(SettingsEntryTestTags.LIST)) {
            items(VISIBLE_CATEGORIES) { category ->
                SettingsCategoryRow(
                    label = category.label(),
                    selected = category == selected,
                    onClick = { shell.navigate { goTo(it, SettingsCategoryKey(category)) } },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun SettingsCategoryRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        // Decorative — the label already says where the row goes (matches this module's own `SettingsLinkRow`).
        Icon(
            painter = painterResource(SkeinIcons.Expand),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsCategoryEntry(
    key: SettingsCategoryKey,
    shell: SkeinShellState,
    deps: SettingsEntryDeps,
) {
    Column(Modifier.fillMaxSize()) {
        shell.EntryTopBar(key, key.category.label())
        SettingsCategoryScreen(
            category = key.category,
            viewModel = deps.viewModel,
            appVersion = deps.appVersion,
            modifier = Modifier.weight(1f).fillMaxSize(),
        )
    }
}
