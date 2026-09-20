package app.skein.system

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * `:app`-scoped DataStore instance (per spec §9, never SharedPreferences —
 * this file backs a *security* posture toggle, and DataStore is the
 * project-wide standard for anything beyond trivial, non-secret UI state).
 */
private val Context.securityPrefsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "security_prefs",
)

/**
 * Runtime security settings (E3.I8, spec §9). Today this is a single flag:
 * whether [app.skein.MainActivity] sets `FLAG_SECURE`. Default is `true`
 * (secure by default) — some users need screen recording for accessibility,
 * so the setting can be turned off, but never defaults off.
 */
class SecurityPrefs(
    private val context: Context,
) {
    /** True unless the user has explicitly disabled it. */
    val flagSecureEnabled: Flow<Boolean> =
        context.securityPrefsDataStore.data.map { prefs ->
            prefs[Keys.FLAG_SECURE_ENABLED] ?: DEFAULT_FLAG_SECURE_ENABLED
        }

    suspend fun setFlagSecureEnabled(enabled: Boolean) {
        context.securityPrefsDataStore.edit { prefs ->
            prefs[Keys.FLAG_SECURE_ENABLED] = enabled
        }
    }

    private object Keys {
        val FLAG_SECURE_ENABLED = booleanPreferencesKey("flag_secure_enabled")
    }

    companion object {
        const val DEFAULT_FLAG_SECURE_ENABLED = true
    }
}
