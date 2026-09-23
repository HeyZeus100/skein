package app.skein.system

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.skein.feature.shell.theme.SkeinThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * `:app`-scoped DataStore instance for appearance settings — a separate
 * store from `SecurityPrefs`' `security_prefs` (spec §9 keeps security state
 * on its own store; this one is plain UI preference, but the project-wide
 * DataStore-not-SharedPreferences convention still applies).
 */
private val Context.appearancePrefsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "appearance_prefs",
)

/**
 * Persists the user's Settings › Appearance choice (bd `skein-l9oi`, spec
 * §8.1: "follows system, override in Settings"). Mirrors [SecurityPrefs]'
 * shape (a `Flow` in, a suspend setter out) so [MainActivity] and
 * `rememberSettingsViewModel` wire it the same way they already wire the
 * Security section.
 *
 * [SkeinThemeMode] itself has no natural DataStore representation, so it is
 * persisted as its enum name; an unrecognized or missing value (a fresh
 * install, or a future build removing a mode) falls back to [SkeinThemeMode.SYSTEM] —
 * the same "secure/sane default until proven otherwise" posture
 * `SecurityPrefs` uses for its own flags.
 */
class AppearancePrefs(
    private val context: Context,
) {
    /** Defaults to [SkeinThemeMode.SYSTEM] until the user picks an explicit override. */
    val themeMode: Flow<SkeinThemeMode> =
        context.appearancePrefsDataStore.data.map { prefs ->
            prefs[Keys.THEME_MODE]?.let { stored ->
                runCatching { SkeinThemeMode.valueOf(stored) }.getOrDefault(DEFAULT_THEME_MODE)
            } ?: DEFAULT_THEME_MODE
        }

    suspend fun setThemeMode(mode: SkeinThemeMode) {
        context.appearancePrefsDataStore.edit { prefs ->
            prefs[Keys.THEME_MODE] = mode.name
        }
    }

    /**
     * Test-only: see [SecurityPrefs.clearAllForTest] for why a `preferencesDataStore`
     * delegate needs an explicit reset between Robolectric tests.
     */
    internal suspend fun clearAllForTest() {
        context.appearancePrefsDataStore.edit { it.clear() }
    }

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
    }

    companion object {
        val DEFAULT_THEME_MODE = SkeinThemeMode.SYSTEM
    }
}
