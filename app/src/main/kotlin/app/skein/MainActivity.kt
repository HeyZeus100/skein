package app.skein

import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.core.designsystem.theme.SkeinThemeMode
import app.skein.core.vault.key.PassphraseKeyExport
import app.skein.core.vault.key.VaultKeyProvider
import app.skein.core.vault.session.UnlockState
import app.skein.feature.editor.entries.KnowledgePreparation
import app.skein.feature.settings.rememberSettingsViewModel
import app.skein.feature.shell.auth.BiometricUnlockScreen
import app.skein.feature.shell.auth.VaultFactorRecoveryScreen
import app.skein.feature.shell.auth.VaultResetScreen
import app.skein.feature.shell.auth.VaultSetupScreen
import app.skein.feature.shell.host.WorkspacePane
import app.skein.feature.shell.host.rememberSkeinWorkspaceState
import app.skein.feature.shell.layout.EdgeToEdgeSurface
import app.skein.shell.DocumentDeleteNotices
import app.skein.shell.NavShell
import app.skein.shell.NotificationDeepLinks
import app.skein.system.AppearancePrefs
import app.skein.system.SecurityPrefs
import app.skein.vault.GatePhase
import app.skein.vault.VaultBootstrap
import app.skein.vault.VaultServices
import app.skein.vault.VaultSession
import app.skein.vault.gateOpenFailure
import app.skein.vault.gatePhase
import app.skein.vault.isFactorRecoveryActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import android.graphics.Color as AndroidColor

/**
 * Single Activity for the `:app` process (spec §4.1). Hosts the NavDisplay
 * shell ([NavShell], ADAPTIVE_LAYOUT_SPEC.md §8) behind the vault gate
 * ([VaultGate], skein-2ige / skein-ank2): on a device whose vault has never
 * been set up the activity shows [VaultSetupScreen]; while
 * `UnlockManager.state` is not `Unlocked` it shows [BiometricUnlockScreen];
 * once unlocked it runs `VaultBootstrap.bringUp()` and, with the vault open,
 * renders the shell. The shell's navigation state is hoisted above the gate.
 *
 * A [FragmentActivity] because `UnlockManager.unlock` presents the
 * `BiometricPrompt` against one (`E3.I2`/`E3.I4`).
 *
 * The whole activity is a vault surface (spec §9), so `FLAG_SECURE` is
 * applied here based on [SecurityPrefs.flagSecureEnabled] (E3.I8). Future
 * activities (e.g. onboarding) must opt in to this explicitly rather than
 * inheriting it — do not move this into a shared base class without
 * re-checking whether every subclass should actually be secure.
 */
class MainActivity : FragmentActivity() {
    private lateinit var securityPrefs: SecurityPrefs
    private lateinit var appearancePrefs: AppearancePrefs
    private lateinit var notificationLinks: NotificationDeepLinks

    // Stable field references (unlike e.g. `securityPrefs::setFlagSecureEnabled`
    // evaluated inline, which allocates a new bound-reference instance on
    // every call) so `rememberSettingsViewModel`'s `remember(...)` keys
    // don't change every recomposition and needlessly rebuild
    // `SettingsViewModel` (and re-launch its collectors) each time. E3.I14
    // (skein-up0/skein-qsux) adds the three lock-policy setters below,
    // following the same shape.
    private val setFlagSecureEnabled: suspend (Boolean) -> Unit = { securityPrefs.setFlagSecureEnabled(it) }
    private val setIdleTimeoutMinutes: suspend (Int) -> Unit = { securityPrefs.setIdleTimeoutMinutes(it) }
    private val setLockOnScreenOff: suspend (Boolean) -> Unit = { securityPrefs.setLockOnScreenOff(it) }
    private val setLockOnBackground: suspend (Boolean) -> Unit = { securityPrefs.setLockOnBackground(it) }

    // bd `skein-l9oi`: Settings > Appearance. Same stable-field-reference
    // reasoning as the setters above.
    private val setThemeMode: suspend (SkeinThemeMode) -> Unit = { appearancePrefs.setThemeMode(it) }

    // E3.I11 (skein-v9g): the two seams Settings › Security's recovery export
    // needs. Stable field references for the same `remember(...)`-keying
    // reason as the setters above.
    //
    // `reauthenticateForExport` presents a FRESH `BiometricPrompt` — the
    // export must not ride on an unlock that happened minutes ago, and a
    // phone handed over while unlocked must not be able to walk out with the
    // vault key. It is user-presence only (no `CryptoObject`): the master is
    // already unwrapped in memory at this point, so binding a Keystore cipher
    // here would prove nothing extra; what is being checked is that the
    // person holding the phone right now is the owner.
    //
    // `buildRecoveryExport` is the ONLY place the in-memory master is read
    // for export. It copies `currentKey()` (per `VaultKeyProvider`'s contract
    // — the returned buffer is the one `lock()` zeroes, so it must not be
    // stashed), wraps the copy, and zeroes the copy in a `finally`. `null`
    // means the vault locked in between, which the UI reports as a refusal.
    private val reauthenticateForExport: suspend () -> Boolean = { promptForExportReauth() }
    private val buildRecoveryExport: suspend (CharArray) -> ByteArray? = { passphrase ->
        withContext(Dispatchers.Default) {
            val master =
                (application as SkeinApplication)
                    .vault.keyProvider
                    .currentKey()
                    ?.copyOf()
            if (master == null) {
                null
            } else {
                try {
                    PassphraseKeyExport.export(master, passphrase)
                } finally {
                    master.fill(0)
                }
            }
        }
    }

    /**
     * Presents a fresh biometric / device-credential prompt and reports
     * whether the user cleared it. Resumes exactly once — `BiometricPrompt`
     * can call both `onAuthenticationFailed` (a non-terminal "try again")
     * and then a terminal callback, so the continuation is guarded.
     */
    private suspend fun promptForExportReauth(): Boolean =
        suspendCancellableCoroutine { continuation ->
            val prompt =
                BiometricPrompt(
                    this,
                    ContextCompat.getMainExecutor(this),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            if (continuation.isActive) continuation.resume(true)
                        }

                        override fun onAuthenticationError(
                            errorCode: Int,
                            errString: CharSequence,
                        ) {
                            if (continuation.isActive) continuation.resume(false)
                        }
                    },
                )
            prompt.authenticate(
                BiometricPrompt.PromptInfo
                    .Builder()
                    .setTitle("Confirm it is you")
                    .setSubtitle("Skein is about to export your recovery key")
                    .setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG or
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                    ).build(),
            )
            continuation.invokeOnCancellation { prompt.cancelAuthentication() }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        securityPrefs = SecurityPrefs(applicationContext)
        appearancePrefs = AppearancePrefs(applicationContext)

        // skein-1vfg: targetSdk 37 already forces edge-to-edge on Android
        // 15+ regardless of this call, but minSdk is 30 — `enableEdgeToEdge`
        // is what makes the status/navigation bar scrims transparent (rather
        // than the opaque platform default) on API 30-34 too, so the same
        // Compose-side inset handling below looks the same on every
        // supported OS version instead of only on 15+.
        //
        // bd `skein-l9oi`: the style must follow the resolved theme mode —
        // SYSTEM keeps the platform's own auto day/night detection (the
        // default `enableEdgeToEdge()` behavior), LIGHT/DARK force status/
        // navigation bar icon contrast to match the explicit override
        // regardless of the device's own day/night setting. Applied
        // synchronously here (same "never a frame where the default is
        // briefly wrong" reasoning as `applyFlagSecure` below) from a
        // blocking read of the DataStore's in-memory cache; live changes
        // (Settings, or the system flipping day/night while mode is SYSTEM)
        // are re-applied from Compose below.
        val initialThemeMode = runBlocking { appearancePrefs.themeMode.first() }
        applyEdgeToEdgeStyle(initialThemeMode)

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
            // skein-xtov.24.7 (SECURITY_REVIEW_D7.md M5a): no Recents snapshot of
            // the pre-lock screen, whatever the FLAG_SECURE setting says.
            setRecentsScreenshotEnabled(false)
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

        // E6.I18 (skein-fsn): lazy POST_NOTIFICATIONS request. Ask at most once
        // per install (denial is remembered in SecurityPrefs). The actual
        // notification posting happens via IngestScheduler's notifier, but the
        // permission request must come from an Activity.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            lifecycleScope.launch {
                val alreadyAsked = securityPrefs.postNotificationsAsked.first()
                if (!alreadyAsked) {
                    securityPrefs.setPostNotificationsAsked(true)
                    requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0)
                }
            }
        }

        val vault = (application as SkeinApplication).vault
        notificationLinks =
            ViewModelProvider(
                this,
                viewModelFactory { initializer { NotificationDeepLinks(vault.unlockManager) } },
            )[NotificationDeepLinks::class.java]
        notificationLinks.onCreate(intent, restoring = savedInstanceState != null)
        val knowledgePreparation =
            vault.ingest.progress.map {
                KnowledgePreparation(it.running, it.processed, awaitingMeaningSearch = it.vectorsPending)
            }
        setContent {
            // bd `skein-l9oi`: the single collection point for the whole
            // activity — every `SkeinTheme` call site below (VaultGate's own
            // screens and, inside `unlockedContent`, the shell) takes this
            // same value, so setup/unlock honour the user's choice exactly
            // like the shell does. Live:
            // flipping Settings › Appearance recomposes immediately, same
            // as `FLAG_SECURE`'s live-update handling above.
            val themeMode by appearancePrefs.themeMode.collectAsState(initial = SkeinThemeMode.SYSTEM)
            LaunchedEffect(themeMode) { applyEdgeToEdgeStyle(themeMode) }
            // bd `skein-l9oi`/DS3: the system bars only reliably reflect the
            // resolved theme right after `enableEdgeToEdge` runs — a
            // `BiometricPrompt`/keyguard round trip (the vault gate's
            // `Unlock` phase) and an activity resume (backgrounding,
            // returning from Settings' own system screens) have both been
            // observed to reset the window's appearance flags on-device
            // (`DEVICE_BEFORE_PASS.md` row 10, row 01's white status-bar
            // icons), so both are re-applied here rather than only when
            // `themeMode`'s own value changes.
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { applyEdgeToEdgeStyle(themeMode) }
            // skein-xtov.24.7 (AL-08) / .24.23 (AL-09c): the NavDisplay shell. Its state sits above the gate
            // (spec §8.8), so a lock keeps the user's place; a vault reset clears it (M4e).
            val workspace = rememberSkeinWorkspaceState(vault.unlockManager)
            val deletionNotices = remember(workspace) { DocumentDeleteNotices() }
            val navShell = workspace.primary
            // skein-xtov.24.20 (UT-14, `UX_TEST_PLAN.md` §2.6): the Compose
            // root, so `tools/ux/fold-watch.sh`'s `uiautomator dump` can find
            // every tagged node below it by `resource-id` — debug builds
            // only (see [debugTestTagsModifier]'s doc).
            Box(modifier = debugTestTagsModifier()) {
                VaultGate(
                    vault = vault,
                    themeMode = themeMode,
                    onUnlocked = {
                        // "After the vault gate returns" (§6.6): re-apply
                        // immediately on unlock, not only on the next ON_RESUME.
                        applyEdgeToEdgeStyle(themeMode)
                        lifecycleScope.launch { vault.bootstrap.bringUp() }
                    },
                    onProvisioned = { strongBoxBacked ->
                        // skein-ank2: recorded for Settings › Security (skein-3el).
                        lifecycleScope.launch { securityPrefs.setStrongBoxUnavailableFallback(!strongBoxBacked) }
                    },
                    // skein-xtov.24.21 (SECURITY_REVIEW_D7.md M4e): no id from the reset vault survives.
                    onVaultReset = {
                        notificationLinks.clear()
                        workspace.resetForNewVault()
                    },
                    unlockedContent = { session ->
                        // E6.I18 (skein-fsn): wire IndexingNotifier to observe and post
                        // progress notifications. Use in-memory permission check to skip
                        // posting attempts when POST_NOTIFICATIONS is denied.
                        LaunchedEffect(Unit) {
                            val notifier =
                                app.skein.notify.IndexingNotifier(
                                    applicationContext,
                                    vault.ingest.progress,
                                    hasPermission = {
                                        ContextCompat.checkSelfPermission(
                                            applicationContext,
                                            android.Manifest.permission.POST_NOTIFICATIONS,
                                        ) == PackageManager.PERMISSION_GRANTED
                                    },
                                )
                            lifecycleScope.launch { notifier.observeAndNotify() }
                        }
                        SkeinTheme(mode = themeMode) {
                            // skein-xtov.24.9 (AL-09b): the Settings destination's view model (Activity-scoped
                            // `SecurityPrefs`/`AppearancePrefs`/biometric reauthentication), built here because
                            // `NavShell` is a plain top-level composable with no `FragmentActivity` of its own.
                            // `vaultUnlockedFlow` is derived once, outside composition (lint's
                            // `FlowOperatorInvokedInComposition`), so `rememberSettingsViewModel`'s keys are stable.
                            val vaultUnlockedFlow =
                                remember(vault) { vault.unlockManager.state.map { it is UnlockState.Unlocked } }
                            val settingsViewModel =
                                rememberSettingsViewModel(
                                    flagSecureEnabledFlow = securityPrefs.flagSecureEnabled,
                                    onSetFlagSecureEnabled = setFlagSecureEnabled,
                                    idleTimeoutMinutesFlow = securityPrefs.idleTimeoutMinutes,
                                    onSetIdleTimeoutMinutes = setIdleTimeoutMinutes,
                                    lockOnScreenOffFlow = securityPrefs.lockOnScreenOff,
                                    onSetLockOnScreenOff = setLockOnScreenOff,
                                    lockOnBackgroundFlow = securityPrefs.lockOnBackground,
                                    onSetLockOnBackground = setLockOnBackground,
                                    strongBoxUnavailableFallbackFlow = securityPrefs.strongBoxUnavailableFallback,
                                    // E3.I11 (skein-v9g): the recovery-key export's hard gate, fresh prompt and
                                    // the one place the in-memory master is read (it wipes its own copy).
                                    vaultUnlockedFlow = vaultUnlockedFlow,
                                    reauthenticate = reauthenticateForExport,
                                    buildRecoveryExport = buildRecoveryExport,
                                    // bd `skein-l9oi`: Settings › Appearance.
                                    themeModeFlow = appearancePrefs.themeMode,
                                    onSetThemeMode = setThemeMode,
                                )
                            key(session, workspace) {
                                var navigationReady by remember { mutableStateOf(false) }
                                val pending by notificationLinks.pending.collectAsState()
                                LaunchedEffect(navigationReady, pending) {
                                    if (navigationReady && pending != null) {
                                        workspace.activate(WorkspacePane.PRIMARY)
                                        notificationLinks.applyPending(navShell)
                                    }
                                }
                                // AL-11: each entry owns its insets; gate-safeDrawing would consume IME here.
                                NavShell(
                                    session,
                                    workspace,
                                    settingsViewModel,
                                    Modifier.fillMaxSize(),
                                    onNavigationReady = {
                                        if (notificationLinks.pending.value != null) {
                                            workspace.activate(WorkspacePane.PRIMARY)
                                            notificationLinks.applyPending(navShell)
                                        }
                                        navigationReady = true
                                    },
                                    knowledgePreparation = knowledgePreparation,
                                    deletionNotices = deletionNotices,
                                )
                            }
                        }
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Do not replace Activity.intent: a consumed notification must not replay on recreation.
        notificationLinks.onNewIntent(intent)
    }

    /**
     * DS3 (skein-xtov.23.3): the single call site for applying the resolved
     * theme to the status/navigation bars, so every trigger — cold start,
     * a live Settings › Appearance change, [Lifecycle.Event.ON_RESUME], and
     * the vault gate's unlock callback — goes through the same mapping
     * ([edgeToEdgeStyleFor]) instead of each re-deriving it.
     */
    private fun applyEdgeToEdgeStyle(mode: SkeinThemeMode) {
        enableEdgeToEdge(
            statusBarStyle = edgeToEdgeStyleFor(mode),
            navigationBarStyle = edgeToEdgeStyleFor(mode),
        )
    }

    private fun applyFlagSecure(enabled: Boolean) {
        if (enabled) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

/**
 * bd `skein-l9oi`/DS3: SYSTEM keeps the platform's own auto day/night
 * detection (`enableEdgeToEdge()`'s own default); an explicit LIGHT/DARK
 * override forces status/navigation bar icon contrast to match, regardless
 * of the device's own day/night setting. A top-level function (not a
 * `MainActivity` member) — it closes over nothing instance-specific — so
 * it's directly unit-testable (`MainActivitySystemBarsTest`) against a
 * disposable `ComponentActivity`, without needing `MainActivity`'s own
 * vault/DataStore/Compose bring-up.
 */
internal fun edgeToEdgeStyleFor(mode: SkeinThemeMode): SystemBarStyle =
    when (mode) {
        SkeinThemeMode.SYSTEM -> SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        SkeinThemeMode.LIGHT -> SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        SkeinThemeMode.DARK -> SystemBarStyle.dark(AndroidColor.TRANSPARENT)
    }

/**
 * skein-xtov.24.20 (UT-14, `UX_TEST_PLAN.md` §2.6): exposes every Compose
 * `testTag` below it as `resource-id` in `adb shell uiautomator dump`, which
 * `tools/ux/fold-watch.sh`'s journeys need to find nodes on the owner's
 * Fold — no module sets this today. Debug builds only: a release APK must
 * not carry this (or any other) extra accessibility-tree metadata beyond
 * what real users' assistive tech needs. [debug] defaults to
 * [BuildConfig.DEBUG] but is a parameter so a test can exercise both
 * branches directly rather than needing a second Gradle build type.
 */
internal fun debugTestTagsModifier(debug: Boolean = BuildConfig.DEBUG): Modifier =
    if (debug) Modifier.semantics { testTagsAsResourceId = true } else Modifier

/** Test tags for the vault gate's own states (the unlock/setup screens and the shell carry their own). */
object VaultGateTestTags {
    const val PROBING = "vault_gate_probing"
    const val OPENING = "vault_gate_opening"
    const val OPEN_FAILED = "vault_gate_open_failed"
    const val RETRY = "vault_gate_retry"
    const val RECOVERY_REQUIRED = "vault_gate_recovery_required"
}

/**
 * Renders [gatePhase] over `VaultBootstrap.session`, `UnlockManager.state`,
 * and a one-shot, prompt-free `VaultKeyProvider.isInitialised()` probe
 * (skein-ank2, off the main thread — it reads the key-envelope file):
 *  - session present → [unlockedContent];
 *  - `Unlocked` but no session → [OpeningVault] (runs `bringUp`; covers an
 *    Activity recreated mid-open and lets a failed open be retried);
 *  - known invalidated factor → explicit surviving-factor recovery;
 *  - probe outstanding → a blank surface ([ProbingVault]);
 *  - not initialised → [VaultSetupScreen]; a provisioned or refused-as-
 *    already-initialised setup flips the probe result to `true`;
 *  - otherwise → [BiometricUnlockScreen], whose `NotInitialised` outcome
 *    flips it back to `false` (the provider is the source of truth).
 * [onUnlocked] kicks off the bring-up the moment the prompt succeeds; both
 * paths funnel into the same idempotent `bringUp`. `setup()` is only ever
 * called from the setup screen, i.e. never while an envelope exists.
 */
@Composable
private fun VaultGate(
    vault: VaultServices,
    onUnlocked: () -> Unit,
    onProvisioned: (strongBoxBacked: Boolean) -> Unit,
    unlockedContent: @Composable (VaultSession) -> Unit,
    onVaultReset: () -> Unit = {},
    // bd `skein-l9oi`: setup/unlock must honour the user's choice too, not
    // just the shell — these screens render before there is a session to
    // thread it through `unlockedContent`, so it comes in as its own param.
    themeMode: SkeinThemeMode = SkeinThemeMode.SYSTEM,
) {
    val unlockState by vault.unlockManager.state.collectAsState()
    val session by vault.session.collectAsState()
    val recoveryFactor by vault.unlockManager.recoveryFactor.collectAsState()
    val recoveryRequired = isFactorRecoveryActive(unlockState, recoveryFactor)
    var provisioned by remember { mutableStateOf<Boolean?>(null) }
    // Reset remains an explicit choice followed by both confirmation steps.
    // Missing-key and unreadable-envelope paths carry distinct explanations.
    var resetRequested by remember { mutableStateOf(false) }
    var missingKeyFactor by remember { mutableStateOf<VaultKeyProvider.Factor?>(null) }
    LaunchedEffect(vault) {
        provisioned = withContext(Dispatchers.IO) { vault.keyProvider.isInitialised() }
    }
    when (val phase = gatePhase(session, unlockState, recoveryRequired, provisioned)) {
        is GatePhase.Open -> unlockedContent(phase.session)
        GatePhase.Opening ->
            SkeinTheme(mode = themeMode) { EdgeToEdgeSurface { m -> OpeningVault(vault.bootstrap, modifier = m) } }
        GatePhase.RecoveryRequired ->
            SkeinTheme(mode = themeMode) {
                EdgeToEdgeSurface { m ->
                    VaultFactorRecoveryScreen(
                        unlockManager = vault.unlockManager,
                        onUnlocked = { onUnlocked() },
                        modifier = m.testTag(VaultGateTestTags.RECOVERY_REQUIRED),
                    )
                }
            }
        GatePhase.Probing ->
            SkeinTheme(mode = themeMode) { EdgeToEdgeSurface { m -> ProbingVault(modifier = m) } }
        GatePhase.Setup ->
            SkeinTheme(mode = themeMode) {
                EdgeToEdgeSurface { m ->
                    VaultSetupScreen(
                        keyProvider = vault.keyProvider,
                        onProvisioned = { strongBoxBacked ->
                            onProvisioned(strongBoxBacked)
                            provisioned = true
                        },
                        onAlreadyInitialised = { provisioned = true },
                        modifier = m,
                    )
                }
            }
        GatePhase.Unlock ->
            SkeinTheme(mode = themeMode) {
                EdgeToEdgeSurface { m ->
                    if (resetRequested) {
                        VaultResetScreen(
                            vaultReset = vault.vaultReset,
                            onReset = {
                                // The envelope is gone: isInitialised() would
                                // now report false too, but setting it
                                // directly avoids a redundant file probe —
                                // the same pattern onNotInitialised uses.
                                resetRequested = false
                                missingKeyFactor = null
                                provisioned = false
                                onVaultReset()
                            },
                            onDismiss = { resetRequested = false },
                            modifier = m,
                            explanation = missingKeyFactor?.let(::missingKeyResetExplanation),
                        )
                    } else {
                        BiometricUnlockScreen(
                            unlockManager = vault.unlockManager,
                            onUnlocked = { onUnlocked() },
                            onRecoveryRequired = {}, // The manager state/factor drive recovery routing.
                            onNotInitialised = { provisioned = false },
                            onResetRequested = {
                                missingKeyFactor = null
                                resetRequested = true
                            },
                            onKeyMaterialGoneResetRequested = { factor ->
                                missingKeyFactor = factor
                                resetRequested = true
                            },
                            modifier = m,
                        )
                    }
                }
            }
    }
}

private fun missingKeyResetExplanation(factor: VaultKeyProvider.Factor): String {
    val unavailable =
        when (factor) {
            VaultKeyProvider.Factor.BIOMETRIC -> "fingerprint or face"
            VaultKeyProvider.Factor.DEVICE_CREDENTIAL -> "device credential"
        }
    return "Skein's $unavailable key is unavailable. This does not mean every recovery option is lost. " +
        "Resetting permanently deletes your notes, chats, attachments, and keys from this device. " +
        "Imported models are kept. To continue, type RESET below."
}

/** The envelope probe is a sub-millisecond file read; a bare surface avoids a spinner flash. */
@Composable
private fun ProbingVault(modifier: Modifier = Modifier) {
    Box(modifier = modifier.testTag(VaultGateTestTags.PROBING))
}

@Composable
private fun OpeningVault(
    bootstrap: VaultBootstrap,
    modifier: Modifier = Modifier,
) {
    var attempt by remember { mutableIntStateOf(0) }
    var failure by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(bootstrap, attempt) {
        failure = null
        // skein-1bx4: `gateOpenFailure` also logs the reason at W — see its
        // KDoc for why that is safe and why it is not done inline here.
        failure = gateOpenFailure(bootstrap.bringUp())
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        val reason = failure
        if (reason == null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(GATE_SPACING),
                modifier = Modifier.testTag(VaultGateTestTags.OPENING),
            ) {
                CircularProgressIndicator()
                Text(text = "Unlocking Skein…", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(GATE_SPACING),
                modifier = Modifier.padding(horizontal = GATE_GUTTER).testTag(VaultGateTestTags.OPEN_FAILED),
            ) {
                // `reason` is a diagnostic for `gateOpenFailure`'s own log
                // line (see its call site above) — never shown verbatim
                // (DESIGN_SYSTEM.md §11.5: no stack traces / exception
                // class names in user-facing copy).
                Text(
                    text = "Couldn't unlock Skein. Try again.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = { attempt++ }, modifier = Modifier.testTag(VaultGateTestTags.RETRY)) {
                    Text("Try again")
                }
            }
        }
    }
}

private val GATE_SPACING = 12.dp
private val GATE_GUTTER = 24.dp
