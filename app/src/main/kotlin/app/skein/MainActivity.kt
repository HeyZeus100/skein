package app.skein

import android.app.ActivityManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import app.skein.feature.settings.SettingsScreen
import app.skein.feature.settings.rememberSettingsViewModel
import app.skein.feature.shell.DestinationPlaceholder
import app.skein.feature.shell.SkeinApp
import app.skein.feature.shell.nav.Destination
import app.skein.system.SecurityPrefs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Single Activity for the `:app` process (spec §4.1). Hosts [SkeinApp], the
 * Compose shell (theme, typography, tokens) landed in `E6.I1`. Wires the
 * Settings destination (`E6.I14`) to the real
 * [app.skein.feature.settings.SettingsScreen] via `destinationContent`; the
 * remaining four drawer destinations still fall back to
 * [DestinationPlaceholder] until their own issues land (`E6.I8`+ /
 * `E7.I3`+).
 *
 * The whole activity is a vault surface (spec §9), so `FLAG_SECURE` is
 * applied here based on [SecurityPrefs.flagSecureEnabled] (E3.I8). Future
 * activities (e.g. onboarding) must opt in to this explicitly rather than
 * inheriting it — do not move this into a shared base class without
 * re-checking whether every subclass should actually be secure.
 */
class MainActivity : ComponentActivity() {
    private lateinit var securityPrefs: SecurityPrefs

    // A stable field reference (unlike `securityPrefs::setFlagSecureEnabled`
    // evaluated inline, which allocates a new bound-reference instance on
    // every call) so `rememberSettingsViewModel`'s `remember(...)` keys
    // don't change every recomposition and needlessly rebuild
    // `SettingsViewModel` (and re-launch its collector) each time.
    private val setFlagSecureEnabled: suspend (Boolean) -> Unit = { securityPrefs.setFlagSecureEnabled(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        securityPrefs = SecurityPrefs(applicationContext)

        // Recents thumbnail suppression (spec §9): FLAG_SECURE already stops
        // the OS from capturing a snapshot at all, but the task description
        // is stubbed too, so the recents card can only ever show the app
        // name/icon, never a label derived from on-screen content.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setTaskDescription(
                ActivityManager.TaskDescription
                    .Builder()
                    .setLabel(getString(R.string.app_name))
                    .build(),
            )
        } else {
            setTaskDescription(ActivityManager.TaskDescription(getString(R.string.app_name)))
        }

        // Apply synchronously on the very first frame so there is never a
        // window where FLAG_SECURE is briefly unset while the DataStore read
        // completes (default is secure — spec §9 non-negotiable). This is a
        // tiny boolean read that DataStore serves from its in-memory cache
        // after the first access.
        applyFlagSecure(runBlocking { securityPrefs.flagSecureEnabled.first() })

        // Live updates: flipping the toggle in Settings takes effect
        // immediately without recreating the activity.
        lifecycleScope.launch {
            securityPrefs.flagSecureEnabled.collect { enabled -> applyFlagSecure(enabled) }
        }

        setContent {
            SkeinApp(
                destinationContent = { destination ->
                    when (destination) {
                        Destination.SETTINGS -> {
                            val settingsViewModel =
                                rememberSettingsViewModel(
                                    flagSecureEnabledFlow = securityPrefs.flagSecureEnabled,
                                    onSetFlagSecureEnabled = setFlagSecureEnabled,
                                )
                            SettingsScreen(
                                viewModel = settingsViewModel,
                                appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                            )
                        }
                        else -> DestinationPlaceholder(label = destination.name)
                    }
                },
            )
        }
    }

    private fun applyFlagSecure(enabled: Boolean) {
        if (enabled) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
