package app.skein.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.skein.feature.shell.theme.SkeinThemeMode

// Settings › Appearance row for bd `skein-l9oi`: a three-way System / Light /
// Dark control. `:feature:settings` cannot depend on `:app` (see
// `SettingsViewModel`'s class doc) — same stateless value-in/callback-out
// shape as `FlagSecureToggle`/`LockPolicyControls`. Hand-rolled rather than
// Material3's `SingleChoiceSegmentedButtonRow` (still `@ExperimentalMaterial3Api`
// and, per spec §8.1, this shell already avoids Material chrome — glyph
// buttons, no elevation, no shadows — everywhere else) so it matches the
// project's restrained, zero-elevation look with no experimental API opt-in.

/**
 * Three-way System / Light / Dark control (bd `skein-l9oi`, spec §8.1:
 * "follows system, override in Settings"). [mode] is the current selection;
 * [onModeChange] is called with the tapped option.
 */
@Composable
fun ThemeModeRow(
    mode: SkeinThemeMode,
    onModeChange: (SkeinThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(text = "Appearance", style = MaterialTheme.typography.bodyLarge)
        Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OPTIONS.forEach { (option, label) ->
                ThemeModeOption(
                    label = label,
                    selected = option == mode,
                    onClick = { onModeChange(option) },
                    modifier = Modifier.testTag(AppearanceTestTags.forMode(option)),
                )
            }
        }
    }
}

@Composable
private fun ThemeModeOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val content = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Row(
        modifier =
            modifier
                .clickable(onClick = onClick)
                .semantics {
                    this.selected = selected
                    contentDescription = label
                }.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                .background(background, RoundedCornerShape(4.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = content)
    }
}

private val OPTIONS =
    listOf(
        SkeinThemeMode.SYSTEM to "System",
        SkeinThemeMode.LIGHT to "Light",
        SkeinThemeMode.DARK to "Dark",
    )

/** Stable test tags for [ThemeModeRow]'s three options. */
object AppearanceTestTags {
    private const val PREFIX = "app.skein.feature.settings.ThemeModeRow"

    fun forMode(mode: SkeinThemeMode): String = "$PREFIX.${mode.name}"
}
