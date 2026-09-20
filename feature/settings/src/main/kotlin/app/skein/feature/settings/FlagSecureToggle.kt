package app.skein.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Placeholder row for the eventual Settings › Security screen (`E3.I14`).
 *
 * Deliberately stateless: `:feature:settings` cannot depend on `:app`, where
 * `SecurityPrefs`/DataStore live (spec §4.1 — `:app` is the single-Activity
 * process), so whoever hosts this screen owns reading/writing the persisted
 * value and passes it in, the same shape `E3.I14`'s other rows (idle
 * timeout, lock-on-screen-off) will use.
 *
 * `checked` mirrors `SecurityPrefs.flagSecureEnabled` directly (on = secure,
 * matching the default). The real screen — icons, copy explaining the
 * screen-recording/accessibility trade-off, grouping with the other E3.I14
 * rows — lands later; this only proves the toggle wiring shape end-to-end.
 */
@Composable
fun FlagSecureToggle(
    flagSecureEnabled: Boolean,
    onFlagSecureEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Block screenshots & screen recording",
                    style = MaterialTheme.typography.bodyLarge,
                )
                val description =
                    "On by default. Hides vault and chat content from recents, " +
                        "screenshots, and screen recording apps."
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = flagSecureEnabled,
                onCheckedChange = onFlagSecureEnabledChange,
            )
        }
    }
}
