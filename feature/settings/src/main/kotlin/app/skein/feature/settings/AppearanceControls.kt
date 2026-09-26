package app.skein.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.skein.core.designsystem.components.SkeinSegmentedControl
import app.skein.core.designsystem.theme.SkeinSpacing
import app.skein.core.designsystem.theme.SkeinThemeMode

// Settings › Appearance row for bd `skein-l9oi`: a three-way System / Light /
// Dark control. `:feature:settings` cannot depend on `:app` (see
// `SettingsViewModel`'s class doc) — same stateless value-in/callback-out
// shape as `FlagSecureToggle`/`LockPolicyControls`.
//
// skein-xtov.23.8 (DS8, docs/ux/DESIGN_SYSTEM.md §10.9): rebuilt on
// `SkeinSegmentedControl` — 48 dp touch targets, radio semantics and a
// `check` on the selected segment — replacing the hand-rolled ≈ 34 dp row.

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
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = SkeinSpacing.space16, vertical = SkeinSpacing.space12),
    ) {
        Text(text = "Theme", style = MaterialTheme.typography.bodyLarge)
        SkeinSegmentedControl(
            options = OPTIONS,
            selected = mode,
            onSelect = onModeChange,
            label = ::labelOf,
            modifier = Modifier.padding(top = SkeinSpacing.space8),
        )
    }
}

private val OPTIONS = listOf(SkeinThemeMode.SYSTEM, SkeinThemeMode.LIGHT, SkeinThemeMode.DARK)

private fun labelOf(mode: SkeinThemeMode): String =
    when (mode) {
        SkeinThemeMode.SYSTEM -> "System"
        SkeinThemeMode.LIGHT -> "Light"
        SkeinThemeMode.DARK -> "Dark"
    }
