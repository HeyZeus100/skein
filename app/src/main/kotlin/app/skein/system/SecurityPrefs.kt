package app.skein.system

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
 * Runtime security settings (E3.I8, spec §9):
 *  - whether [app.skein.MainActivity] sets `FLAG_SECURE`. Default is `true`
 *    (secure by default) — some users need screen recording for
 *    accessibility, so the setting can be turned off, but never defaults off;
 *  - whether first-run vault setup fell back from StrongBox to a TEE-backed
 *    Layer-0 key (skein-ank2, recorded from `SetupResult` for Settings ›
 *    Security per skein-3el). Informational: it changes no behaviour.
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

    /**
     * True when `VaultKeyProvider.setup` reported
     * `SetupResult.StrongBoxUnavailableFallback` — the vault's Layer-0 keys
     * live in the TEE, not StrongBox. False (the default) until setup has
     * run, and after a setup that got StrongBox.
     */
    val strongBoxUnavailableFallback: Flow<Boolean> =
        context.securityPrefsDataStore.data.map { prefs ->
            prefs[Keys.STRONGBOX_UNAVAILABLE_FALLBACK] ?: DEFAULT_STRONGBOX_UNAVAILABLE_FALLBACK
        }

    suspend fun setStrongBoxUnavailableFallback(fallback: Boolean) {
        context.securityPrefsDataStore.edit { prefs ->
            prefs[Keys.STRONGBOX_UNAVAILABLE_FALLBACK] = fallback
        }
    }

    // ---- E3.I14 (skein-up0) — lock policy settings ------------------------
    //
    // Applied live into `UnlockManager.configure(LockPolicy)` by
    // `app.skein.vault.VaultServices.forDevice`, which collects these three
    // flows for the lifetime of the process. Defaults and the idle-timeout
    // ceiling mirror `docs/design/LOCK_POLICY_INDEXING.md` §3 and
    // `LockPolicy`'s own defaults/clamp in `:core:vault` — kept in sync
    // deliberately rather than shared as a single constant, since `:app`
    // cannot depend on `:core:vault`'s `LockPolicy` type from a plain
    // DataStore-backed prefs class without pulling in Android Keystore/
    // BiometricPrompt transitively for no benefit here.

    /** One of `ALLOWED_IDLE_TIMEOUT_MINUTES`. Defaults to 5 minutes. */
    val idleTimeoutMinutes: Flow<Int> =
        context.securityPrefsDataStore.data.map { prefs ->
            prefs[Keys.IDLE_TIMEOUT_MINUTES] ?: DEFAULT_IDLE_TIMEOUT_MINUTES
        }

    /**
     * Persists [minutes], clamped to `[1, 60]` (the plan's ceiling —
     * `UnlockManager.configure` clamps again independently, so this is
     * belt-and-suspenders, not the only enforcement point). Settings ›
     * Security only ever offers [ALLOWED_IDLE_TIMEOUT_MINUTES], but this
     * setter does not trust the caller to have respected that.
     */
    suspend fun setIdleTimeoutMinutes(minutes: Int) {
        val clamped = minutes.coerceIn(MIN_IDLE_TIMEOUT_MINUTES, MAX_IDLE_TIMEOUT_MINUTES)
        context.securityPrefsDataStore.edit { prefs ->
            prefs[Keys.IDLE_TIMEOUT_MINUTES] = clamped
        }
    }

    /** "Lock when screen turns off." True (secure) by default. */
    val lockOnScreenOff: Flow<Boolean> =
        context.securityPrefsDataStore.data.map { prefs ->
            prefs[Keys.LOCK_ON_SCREEN_OFF] ?: DEFAULT_LOCK_ON_SCREEN_OFF
        }

    suspend fun setLockOnScreenOff(enabled: Boolean) {
        context.securityPrefsDataStore.edit { prefs ->
            prefs[Keys.LOCK_ON_SCREEN_OFF] = enabled
        }
    }

    /** "Lock when app leaves foreground." False by default (opt-in). */
    val lockOnBackground: Flow<Boolean> =
        context.securityPrefsDataStore.data.map { prefs ->
            prefs[Keys.LOCK_ON_BACKGROUND] ?: DEFAULT_LOCK_ON_BACKGROUND
        }

    suspend fun setLockOnBackground(enabled: Boolean) {
        context.securityPrefsDataStore.edit { prefs ->
            prefs[Keys.LOCK_ON_BACKGROUND] = enabled
        }
    }

    // ---- E6.I18 (skein-fsn) — notification permissions ---------------------------
    //
    // Lazy POST_NOTIFICATIONS runtime request from MainActivity when the first
    // indexing or model notification would be posted while an Activity is in the
    // foreground. A denial is remembered so it is asked at most once per install.

    /**
     * True after [setPostNotificationsAsked] has been called (permission was
     * requested once, whether granted or denied). Used by MainActivity to avoid
     * re-prompting after a denial.
     */
    val postNotificationsAsked: Flow<Boolean> =
        context.securityPrefsDataStore.data.map { prefs ->
            prefs[Keys.POST_NOTIFICATIONS_ASKED] ?: DEFAULT_POST_NOTIFICATIONS_ASKED
        }

    suspend fun setPostNotificationsAsked(asked: Boolean) {
        context.securityPrefsDataStore.edit { prefs ->
            prefs[Keys.POST_NOTIFICATIONS_ASKED] = asked
        }
    }

    /**
     * Test-only: `Context.securityPrefsDataStore` is a `preferencesDataStore`
     * delegate — its underlying `DataStore` instance (and in-memory
     * Preferences cache) is a singleton keyed by this file's static
     * initialization, not by the [Context] instance, so it outlives any one
     * Robolectric "fresh Application per test" reset and bleeds state across
     * test methods (observed: a "persists false" test polluting a later
     * "defaults to true" assertion). Tests that assert a bare default call
     * this in `@Before` to guarantee a clean slate regardless of what ran
     * earlier in the same JVM/test class.
     */
    internal suspend fun clearAllForTest() {
        context.securityPrefsDataStore.edit { it.clear() }
    }

    private object Keys {
        val FLAG_SECURE_ENABLED = booleanPreferencesKey("flag_secure_enabled")
        val STRONGBOX_UNAVAILABLE_FALLBACK = booleanPreferencesKey("strongbox_unavailable_fallback")
        val IDLE_TIMEOUT_MINUTES = intPreferencesKey("idle_timeout_minutes")
        val LOCK_ON_SCREEN_OFF = booleanPreferencesKey("lock_on_screen_off")
        val LOCK_ON_BACKGROUND = booleanPreferencesKey("lock_on_background")
        val POST_NOTIFICATIONS_ASKED = booleanPreferencesKey("post_notifications_asked")
    }

    companion object {
        const val DEFAULT_FLAG_SECURE_ENABLED = true
        const val DEFAULT_STRONGBOX_UNAVAILABLE_FALLBACK = false

        const val DEFAULT_IDLE_TIMEOUT_MINUTES = 5
        const val DEFAULT_LOCK_ON_SCREEN_OFF = true
        const val DEFAULT_LOCK_ON_BACKGROUND = false
        const val MIN_IDLE_TIMEOUT_MINUTES = 1
        const val MAX_IDLE_TIMEOUT_MINUTES = 60

        const val DEFAULT_POST_NOTIFICATIONS_ASKED = false

        /** The values Settings › Security offers for idle timeout. */
        val ALLOWED_IDLE_TIMEOUT_MINUTES = listOf(1, 5, 15, 30, 60)
    }
}
