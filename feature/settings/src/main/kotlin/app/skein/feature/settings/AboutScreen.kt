package app.skein.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.skein.feature.shell.input.SecureTextField

/**
 * Full-screen third-party notices view (plan `E9.I8`), reached from
 * Settings' About section ("Open-source licenses" — see [SettingsScreen]). Renders
 * [appName]/[appVersion], a short attribution blurb, and every entry from
 * `licenses.json` (loaded via [licensesRepository]), grouped by SPDX license
 * identifier and filtered by the search field.
 *
 * `licenses.json` comes solely from [LicensesRepository]/the generated asset
 * — no license data is hardcoded here (see `bd show skein-dun`'s
 * guardrails). If the asset is missing or fails to parse,
 * [LicensesRepository.load] returns an empty list and this screen shows an
 * empty-state message instead of crashing.
 *
 * The search field uses [SecureTextField] — never a raw `TextField` (threat
 * model §9, enforced repo-wide by `RawTextFieldTest`) — even though this
 * particular field's content (a license/library name substring) isn't
 * vault-sensitive; matching the one-allowed-input-widget rule keeps the
 * guard simple and this screen exempt-free.
 *
 * @param appVersion the app's version string (e.g. `"0.1.0 (1)"`, from
 *   `:app`'s `BuildConfig` — mirrors [SettingsScreen]'s `appVersion` param).
 * @param onBack invoked when the user taps the back row.
 * @param appName the app's display name; defaults to `"Skein"` since
 *   `:feature:settings` has no access to `:app`'s `R.string.app_name`.
 */
@Composable
fun AboutScreen(
    appVersion: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    appName: String = "Skein",
    licensesRepository: LicensesRepository = remember { LicensesRepository() },
) {
    val context = LocalContext.current
    var licenses by remember { mutableStateOf<List<LicenseEntry>?>(null) }
    var query by remember { mutableStateOf("") }
    var urlDialogEntry by remember { mutableStateOf<LicenseEntry?>(null) }

    LaunchedEffect(licensesRepository) {
        licenses = licensesRepository.load(context)
    }

    val loadedLicenses = licenses
    val filteredGroups =
        remember(loadedLicenses, query) {
            (loadedLicenses ?: emptyList())
                .filter { entry ->
                    query.isBlank() ||
                        entry.name.contains(query, ignoreCase = true) ||
                        entry.license.contains(query, ignoreCase = true)
                }.groupedBySpdx()
        }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = MAX_CONTENT_WIDTH)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
        ) {
            AboutHeader(appName = appName, appVersion = appVersion, onBack = onBack)

            SecureTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                singleLine = true,
                label = { Text("Search licenses") },
            )

            when {
                loadedLicenses == null -> LoadingState()
                loadedLicenses.isEmpty() -> EmptyState()
                filteredGroups.isEmpty() -> NoSearchResultsState(query = query)
                else ->
                    LicenseList(
                        groups = filteredGroups,
                        onViewClick = { urlDialogEntry = it },
                    )
            }
        }
    }

    urlDialogEntry?.let { entry ->
        LicenseUrlDialog(entry = entry, onDismiss = { urlDialogEntry = null })
    }
}

/** App name, version, and a short non-legal attribution blurb above the search field. */
@Composable
private fun AboutHeader(
    appName: String,
    appVersion: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(top = 16.dp, bottom = 8.dp)) {
        TextButton(onClick = onBack, modifier = Modifier.padding(bottom = 4.dp)) {
            Text("Back")
        }
        Text(text = appName, style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "Version $appVersion",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text =
                "Skein is built with the open-source components listed below, each under its " +
                    "original license.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
        Text(text = "Loading licenses…", style = MaterialTheme.typography.bodyMedium)
    }
}

/** Shown when `licenses.json` is missing from the APK's assets or failed to parse. */
@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
        Text(
            text = "License information isn't available in this build.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Shown when licenses loaded fine but the search query matched nothing. */
@Composable
private fun NoSearchResultsState(
    query: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
        Text(
            text = "No licenses match \"$query\".",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The grouped, scrollable license list: one section header per SPDX identifier, then its rows. */
@Composable
private fun LicenseList(
    groups: List<LicenseGroup>,
    onViewClick: (LicenseEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        groups.forEach { group ->
            item(key = "header:${group.spdxId}") {
                Text(
                    text = "${group.spdxId} (${group.entries.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
            }
            items(group.entries, key = { "${group.spdxId}:${it.name}:${it.version}" }) { entry ->
                LicenseRow(entry = entry, onViewClick = { onViewClick(entry) })
                HorizontalDivider()
            }
        }
    }
}

/** One `licenses.json` entry: name/version, its SPDX chip, and a "View" button for the URL. */
@Composable
private fun LicenseRow(
    entry: LicenseEntry,
    onViewClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = entry.name, style = MaterialTheme.typography.bodyLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (entry.version.isNotBlank()) {
                    Text(
                        text = entry.version,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                LicenseChip(license = entry.license)
            }
        }
        TextButton(onClick = onViewClick) {
            Text("View")
        }
    }
}

/** Small SPDX-identifier pill, e.g. `Apache-2.0`, `MIT`, `BSD-3-Clause`. */
@Composable
private fun LicenseChip(
    license: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = license.ifBlank { UNKNOWN_LICENSE_GROUP },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/**
 * Displays [entry]'s URL as plain text with a copy-to-clipboard action.
 * Deliberately never opens a browser or share sheet (`skein-dun`'s
 * guardrails — Skein is offline-first; a tap here must not trigger network
 * activity or leave the app).
 */
@Composable
private fun LicenseUrlDialog(
    entry: LicenseEntry,
    onDismiss: () -> Unit,
) {
    val clipboardManager = LocalClipboardManager.current
    val hasUrl = entry.url.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.name) },
        text = {
            Text(
                text = if (hasUrl) entry.url else "No source URL was recorded for this entry.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(
                enabled = hasUrl,
                onClick = {
                    clipboardManager.setText(AnnotatedString(entry.url))
                    onDismiss()
                },
            ) {
                Text("Copy")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}

/** Caps line length on unfolded/tablet-width screens, matching [SettingsScreen]. */
private val MAX_CONTENT_WIDTH = 640.dp
