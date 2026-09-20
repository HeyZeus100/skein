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
) {
    private var flagSecureEnabledState: Boolean by mutableStateOf(DEFAULT_FLAG_SECURE_ENABLED)

    /** Mirrors `SecurityPrefs.flagSecureEnabled`. Defaults secure until the first emission arrives. */
    val flagSecureEnabled: Boolean get() = flagSecureEnabledState

    init {
        scope.launch {
            flagSecureEnabledFlow.collect { enabled -> flagSecureEnabledState = enabled }
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

    private companion object {
        const val DEFAULT_FLAG_SECURE_ENABLED = true
    }
}

/** Remembers a [SettingsViewModel] scoped to this composition, per [rememberCoroutineScope]. */
@Composable
fun rememberSettingsViewModel(
    flagSecureEnabledFlow: Flow<Boolean>,
    onSetFlagSecureEnabled: suspend (Boolean) -> Unit,
): SettingsViewModel {
    val scope = rememberCoroutineScope()
    return remember(flagSecureEnabledFlow, onSetFlagSecureEnabled) {
        SettingsViewModel(
            scope = scope,
            flagSecureEnabledFlow = flagSecureEnabledFlow,
            onSetFlagSecureEnabled = onSetFlagSecureEnabled,
        )
    }
}
