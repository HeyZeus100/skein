package app.skein.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// Settings › Security rows for plan `E3.I14` / bead `skein-up0`: idle
// timeout, "lock when screen turns off", "lock when app leaves foreground",
// and a read-only hardware-backing status row. Same stateless shape as
// `FlagSecureToggle` — `:feature:settings` cannot depend on `:app`, so the
// host owns reading/writing `SecurityPrefs` and passes the current value
// plus a change callback.

/**
 * Idle-timeout selector. [minutes] must be one of [allowedMinutes] (the
 * plan's five allowed values); [onMinutesChange] is called with a member of
 * that same list when the user picks a new one — persistence-layer clamping
 * (`SecurityPrefs.setIdleTimeoutMinutes`, `UnlockManager.configure`) is the
 * actual enforcement point, this row only ever offers valid choices.
 */
@Composable
fun IdleTimeoutRow(
    minutes: Int,
    onMinutesChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    allowedMinutes: List<Int> = DEFAULT_ALLOWED_IDLE_TIMEOUT_MINUTES,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column {
            Text(text = "Lock after inactivity", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = formatMinutes(minutes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            allowedMinutes.forEach { option ->
                DropdownMenuItem(
                    text = { Text(formatMinutes(option)) },
                    onClick = {
                        expanded = false
                        onMinutesChange(option)
                    },
                )
            }
        }
    }
}

/** "Lock when screen turns off." Secure default is `checked = true`. */
@Composable
fun LockOnScreenOffToggle(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    SwitchRow(
        label = "Lock when screen turns off",
        checked = enabled,
        onCheckedChange = onEnabledChange,
        modifier = modifier,
    )
}

/** "Lock when app leaves foreground." Default is `checked = false` (opt-in). */
@Composable
fun LockOnBackgroundToggle(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    SwitchRow(
        label = "Lock when app leaves foreground",
        checked = enabled,
        onCheckedChange = onEnabledChange,
        modifier = modifier,
    )
}

/**
 * Read-only hardware-backing status (bead `skein-up0`'s AC): "Hardware:
 * StrongBox" when [strongBoxUnavailableFallback] is false (the happy path —
 * `VaultKeyProvider.setup` reported `SetupResult.Success`), or "Hardware:
 * TEE (StrongBox unavailable)" when it fell back (`skein-ank2`).
 */
@Composable
fun StrongBoxStatusRow(
    strongBoxUnavailableFallback: Boolean,
    modifier: Modifier = Modifier,
) {
    val value = if (strongBoxUnavailableFallback) "TEE (StrongBox unavailable)" else "StrongBox"
    SettingsInfoRow(label = "Hardware", value = value, modifier = modifier)
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun formatMinutes(minutes: Int): String = if (minutes == 1) "1 minute" else "$minutes minutes"

private val DEFAULT_ALLOWED_IDLE_TIMEOUT_MINUTES = listOf(1, 5, 15, 30, 60)
