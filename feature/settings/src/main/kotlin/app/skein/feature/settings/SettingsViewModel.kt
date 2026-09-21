package app.skein.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * State holder for [SettingsScreen] (plan `E6.I14`).
 *
 * `:feature:settings` cannot depend on `:app`, where
 * `app.skein.system.SecurityPrefs`/DataStore live (spec §4.1 — `:app` is the
 * single-Activity process that assembles every feature module, so a
 * feature depending back on `:app` would be a Gradle dependency cycle
 * anyway). [FlagSecureToggle] established the pattern of taking the current
 * value and a change callback instead of the concrete prefs type; this
 * class only adds the plumbing (collecting the caller's [Flow] into Compose
 * state, and forwarding writes) so [SettingsScreen] itself stays stateless.
 * The host — today `:app`'s `MainActivity`/`SkeinApp` wiring — passes in
 * `SecurityPrefs.flagSecureEnabled` and `SecurityPrefs::setFlagSecureEnabled`
 * directly.
 *
 * Deliberately a plain `@Stable` class, not an `androidx.lifecycle.ViewModel`
 * — matching `NavState`/`TabsState`/`AdaptiveLayoutState` in
 * `:feature:shell`, none of which use the AAC ViewModel either. `E3.I14`,
 * `E6.I7`, `E6.I10`, and `E6.I12` are expected to grow this same shape
 * (a `Flow` in, a suspend setter out) for their own persisted prefs.
 */
@Stable
class SettingsViewModel(
    private val scope: CoroutineScope,
    flagSecureEnabledFlow: Flow<Boolean>,
    private val onSetFlagSecureEnabled: suspend (Boolean) -> Unit,
    // E3.I14 (skein-up0): additive trailing params, all defaulted, so the
    // existing 3-arg call site (today, `MainActivity`) keeps compiling
    // unchanged — see this class's own doc above. A host that wires
    // `SecurityPrefs`'s new lock-policy flows in only needs to pass these.
    idleTimeoutMinutesFlow: Flow<Int> = flowOf(DEFAULT_IDLE_TIMEOUT_MINUTES),
    private val onSetIdleTimeoutMinutes: suspend (Int) -> Unit = {},
    lockOnScreenOffFlow: Flow<Boolean> = flowOf(DEFAULT_LOCK_ON_SCREEN_OFF),
    private val onSetLockOnScreenOff: suspend (Boolean) -> Unit = {},
    lockOnBackgroundFlow: Flow<Boolean> = flowOf(DEFAULT_LOCK_ON_BACKGROUND),
    private val onSetLockOnBackground: suspend (Boolean) -> Unit = {},
    strongBoxUnavailableFallbackFlow: Flow<Boolean> = flowOf(DEFAULT_STRONGBOX_UNAVAILABLE_FALLBACK),
    // E3.I11 (skein-v9g): the opt-in passphrase export of the vault key.
    // Same additive, defaulted shape — an unwired host gets a disabled row
    // whose callbacks refuse, never a half-wired export.
    vaultUnlockedFlow: Flow<Boolean> = flowOf(false),
    /** Presents a FRESH biometric / device-credential prompt. Wired by the host. */
    val reauthenticate: suspend () -> Boolean = { false },
    /** Wraps the in-memory master under the passphrase, or `null` while locked. */
    val buildRecoveryExport: suspend (CharArray) -> ByteArray? = { null },
) {
    private var flagSecureEnabledState: Boolean by mutableStateOf(DEFAULT_FLAG_SECURE_ENABLED)
    private var idleTimeoutMinutesState: Int by mutableStateOf(DEFAULT_IDLE_TIMEOUT_MINUTES)
    private var lockOnScreenOffState: Boolean by mutableStateOf(DEFAULT_LOCK_ON_SCREEN_OFF)
    private var lockOnBackgroundState: Boolean by mutableStateOf(DEFAULT_LOCK_ON_BACKGROUND)
    private var strongBoxUnavailableFallbackState: Boolean by mutableStateOf(DEFAULT_STRONGBOX_UNAVAILABLE_FALLBACK)
    private var vaultUnlockedState: Boolean by mutableStateOf(false)

    /** Mirrors `SecurityPrefs.flagSecureEnabled`. Defaults secure until the first emission arrives. */
    val flagSecureEnabled: Boolean get() = flagSecureEnabledState

    /** Mirrors `SecurityPrefs.idleTimeoutMinutes`. Defaults to 5 minutes until the first emission arrives. */
    val idleTimeoutMinutes: Int get() = idleTimeoutMinutesState

    /** Mirrors `SecurityPrefs.lockOnScreenOff`. Defaults to `true` (secure) until the first emission arrives. */
    val lockOnScreenOff: Boolean get() = lockOnScreenOffState

    /** Mirrors `SecurityPrefs.lockOnBackground`. Defaults to `false` until the first emission arrives. */
    val lockOnBackground: Boolean get() = lockOnBackgroundState

    /** Mirrors `SecurityPrefs.strongBoxUnavailableFallback`. Read-only — no setter. */
    val strongBoxUnavailableFallback: Boolean get() = strongBoxUnavailableFallbackState

    /** Whether the vault is currently unlocked — the export row's hard gate. Read-only. */
    val vaultUnlocked: Boolean get() = vaultUnlockedState

    init {
        scope.launch {
            flagSecureEnabledFlow.collect { enabled -> flagSecureEnabledState = enabled }
        }
        scope.launch {
            idleTimeoutMinutesFlow.collect { minutes -> idleTimeoutMinutesState = minutes }
        }
        scope.launch {
            lockOnScreenOffFlow.collect { enabled -> lockOnScreenOffState = enabled }
        }
        scope.launch {
            lockOnBackgroundFlow.collect { enabled -> lockOnBackgroundState = enabled }
        }
        scope.launch {
            strongBoxUnavailableFallbackFlow.collect { fallback -> strongBoxUnavailableFallbackState = fallback }
        }
        scope.launch {
            vaultUnlockedFlow.collect { unlocked -> vaultUnlockedState = unlocked }
        }
    }

    /**
     * Updates state immediately (so the switch never visually lags a tap)
     * and forwards the write to [onSetFlagSecureEnabled]; if persistence
     * fails, the next emission from the source flow (the caller's
     * [Flow]) will correct [flagSecureEnabled] back.
     */
    fun setFlagSecureEnabled(enabled: Boolean) {
        flagSecureEnabledState = enabled
        scope.launch { onSetFlagSecureEnabled(enabled) }
    }

    /** Same optimistic-update shape as [setFlagSecureEnabled], for the idle-timeout selector. */
    fun setIdleTimeoutMinutes(minutes: Int) {
        idleTimeoutMinutesState = minutes
        scope.launch { onSetIdleTimeoutMinutes(minutes) }
    }

    /** Same optimistic-update shape as [setFlagSecureEnabled], for "lock when screen turns off". */
    fun setLockOnScreenOff(enabled: Boolean) {
        lockOnScreenOffState = enabled
        scope.launch { onSetLockOnScreenOff(enabled) }
    }

    /** Same optimistic-update shape as [setFlagSecureEnabled], for "lock when app leaves foreground". */
    fun setLockOnBackground(enabled: Boolean) {
        lockOnBackgroundState = enabled
        scope.launch { onSetLockOnBackground(enabled) }
    }

    private companion object {
        const val DEFAULT_FLAG_SECURE_ENABLED = true
        const val DEFAULT_IDLE_TIMEOUT_MINUTES = 5
        const val DEFAULT_LOCK_ON_SCREEN_OFF = true
        const val DEFAULT_LOCK_ON_BACKGROUND = false
        const val DEFAULT_STRONGBOX_UNAVAILABLE_FALLBACK = false
    }
}

/** Remembers a [SettingsViewModel] scoped to this composition, per [rememberCoroutineScope]. */
@Composable
fun rememberSettingsViewModel(
    flagSecureEnabledFlow: Flow<Boolean>,
    onSetFlagSecureEnabled: suspend (Boolean) -> Unit,
    // E3.I14 (skein-up0): additive, defaulted — see SettingsViewModel's ctor
    // doc for why the existing 2-arg call site keeps compiling unchanged.
    idleTimeoutMinutesFlow: Flow<Int> = flowOf(5),
    onSetIdleTimeoutMinutes: suspend (Int) -> Unit = {},
    lockOnScreenOffFlow: Flow<Boolean> = flowOf(true),
    onSetLockOnScreenOff: suspend (Boolean) -> Unit = {},
    lockOnBackgroundFlow: Flow<Boolean> = flowOf(false),
    onSetLockOnBackground: suspend (Boolean) -> Unit = {},
    strongBoxUnavailableFallbackFlow: Flow<Boolean> = flowOf(false),
    // E3.I11 (skein-v9g): additive, defaulted — see SettingsViewModel's ctor.
    vaultUnlockedFlow: Flow<Boolean> = flowOf(false),
    reauthenticate: suspend () -> Boolean = { false },
    buildRecoveryExport: suspend (CharArray) -> ByteArray? = { null },
): SettingsViewModel {
    val scope = rememberCoroutineScope()
    return remember(
        flagSecureEnabledFlow,
        onSetFlagSecureEnabled,
        idleTimeoutMinutesFlow,
        onSetIdleTimeoutMinutes,
        lockOnScreenOffFlow,
        onSetLockOnScreenOff,
        lockOnBackgroundFlow,
        onSetLockOnBackground,
        strongBoxUnavailableFallbackFlow,
        vaultUnlockedFlow,
        reauthenticate,
        buildRecoveryExport,
    ) {
        SettingsViewModel(
            scope = scope,
            flagSecureEnabledFlow = flagSecureEnabledFlow,
            onSetFlagSecureEnabled = onSetFlagSecureEnabled,
            idleTimeoutMinutesFlow = idleTimeoutMinutesFlow,
            onSetIdleTimeoutMinutes = onSetIdleTimeoutMinutes,
            lockOnScreenOffFlow = lockOnScreenOffFlow,
            onSetLockOnScreenOff = onSetLockOnScreenOff,
            lockOnBackgroundFlow = lockOnBackgroundFlow,
            onSetLockOnBackground = onSetLockOnBackground,
            strongBoxUnavailableFallbackFlow = strongBoxUnavailableFallbackFlow,
            vaultUnlockedFlow = vaultUnlockedFlow,
            reauthenticate = reauthenticate,
            buildRecoveryExport = buildRecoveryExport,
        )
    }
}
