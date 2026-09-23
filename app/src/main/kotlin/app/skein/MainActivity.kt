package app.skein

import android.app.ActivityManager
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import app.skein.core.vault.key.PassphraseKeyExport
import app.skein.core.vault.session.UnlockState
import app.skein.feature.editor.notetab.NoteTab
import app.skein.feature.graph.GraphScreen
import app.skein.feature.settings.SettingsScreen
import app.skein.feature.settings.rememberSettingsViewModel
import app.skein.feature.shell.DestinationPlaceholder
import app.skein.feature.shell.SkeinApp
import app.skein.feature.shell.auth.BiometricUnlockScreen
import app.skein.feature.shell.auth.VaultResetScreen
import app.skein.feature.shell.auth.VaultSetupScreen
import app.skein.feature.shell.layout.EdgeToEdgeSurface
import app.skein.feature.shell.nav.Destination
import app.skein.feature.shell.tabs.FlushRegistry
import app.skein.feature.shell.theme.SkeinTheme
import app.skein.feature.shell.theme.SkeinThemeMode
import app.skein.feature.timeline.TimelineRail
import app.skein.feature.timeline.TimelineScreen
import app.skein.feature.timeline.rememberTimelineState
import app.skein.system.AppearancePrefs
import app.skein.system.SecurityPrefs
import app.skein.vault.GatePhase
import app.skein.vault.VaultBootstrap
import app.skein.vault.VaultServices
import app.skein.vault.VaultSession
import app.skein.vault.gateOpenFailure
import app.skein.vault.gatePhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import us.aherrera.skein.core.model.DocId
import kotlin.coroutines.resume
import android.graphics.Color as AndroidColor

/**
 * Single Activity for the `:app` process (spec §4.1). Hosts [SkeinApp], the
 * Compose shell (theme, typography, tokens) landed in `E6.I1`, behind the
 * vault gate ([VaultGate], skein-2ige / skein-ank2): on a device whose
 * vault has never been set up the activity shows [VaultSetupScreen]; while
 * `UnlockManager.state` is not `Unlocked` it shows [BiometricUnlockScreen];
 * once unlocked it runs `VaultBootstrap.bringUp()` and, with the vault
 * open, renders the shell with the Timeline destination fed by the live
 * repository. Wires the
 * Settings destination (`E6.I14`) to the real
 * [app.skein.feature.settings.SettingsScreen] via `destinationContent`; the
 * remaining drawer destinations still fall back to [DestinationPlaceholder]
 * until their own issues land (`E6.I8`+ / `E7.I3`+).
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
                    .setSubtitle("Skein is about to export your vault key")
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
        enableEdgeToEdge(
            statusBarStyle = edgeToEdgeStyleFor(initialThemeMode),
            navigationBarStyle = edgeToEdgeStyleFor(initialThemeMode),
        )

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
        setContent {
            // bd `skein-l9oi`: the single collection point for the whole
            // activity — every `SkeinTheme`/`SkeinApp` call site below
            // (VaultGate's own screens and, inside `unlockedContent`,
            // `SkeinApp` itself) takes this same value, so setup/unlock
            // honour the user's choice exactly like the shell does. Live:
            // flipping Settings › Appearance recomposes immediately, same
            // as `FLAG_SECURE`'s live-update handling above.
            val themeMode by appearancePrefs.themeMode.collectAsState(initial = SkeinThemeMode.SYSTEM)
            LaunchedEffect(themeMode) {
                enableEdgeToEdge(
                    statusBarStyle = edgeToEdgeStyleFor(themeMode),
                    navigationBarStyle = edgeToEdgeStyleFor(themeMode),
                )
            }
            VaultGate(
                vault = vault,
                themeMode = themeMode,
                onUnlocked = { lifecycleScope.launch { vault.bootstrap.bringUp() } },
                onProvisioned = { strongBoxBacked ->
                    // skein-ank2: recorded for Settings › Security (skein-3el).
                    lifecycleScope.launch { securityPrefs.setStrongBoxUnavailableFallback(!strongBoxBacked) }
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
                    UnlockedShell(session, themeMode)
                },
            )
        }
    }

    @Composable
    private fun UnlockedShell(
        session: VaultSession,
        themeMode: SkeinThemeMode,
    ) {
        // skein-v9g: Settings › Security's recovery export needs the key
        // provider and the unlock state. Read from the Application rather
        // than threaded through `VaultGate`'s `unlockedContent` lambda, so
        // the gate's own wiring is untouched.
        val vaultForSettings = remember { (application as SkeinApplication).vault }

        // Derived once, outside composition (lint's
        // `FlowOperatorInvokedInComposition`) and keyed on the services, so
        // `rememberSettingsViewModel`'s `remember(...)` key is stable.
        val vaultUnlockedFlow =
            remember(vaultForSettings) {
                vaultForSettings.unlockManager.state.map { it is UnlockState.Unlocked }
            }
        // skein-u01 (E6.I9): held here (rather than letting `SkeinApp`
        // default one internally) so a future `E3.I3b` `SessionState`/
        // `LockObserver` registry has something to call `flushAll()` on
        // before `VaultBootstrap`/`UnlockManager` close the vault
        // (`docs/design/LOCK_POLICY_INDEXING.md` §4.3). Not wired to the
        // lock sequence yet — `E3.I3b` owns that — this only keeps the
        // handle from being thrown away.
        val flushRegistry = remember { FlushRegistry() }
        // skein-z2u (E6.I11): the ✦ button's real navigation target.
        // `GraphScreen` is its own `SkeinTheme` wrapper drawn as an overlay
        // *alongside* `SkeinApp`'s panes (see that composable's own file
        // header) — not a `Destination`/`noteTabContent` slot — so which
        // document is open stays local `mutableStateOf` state here in
        // `MainActivity`, not something `SkeinApp` needs to know about.
        // skein-0td0: actually opening the tapped node as a tab needs
        // `SkeinApp`'s `primaryTabsState`, which lives inside `SkeinApp`
        // itself, so *rendering* the overlay (and wiring its
        // `openPreview`/`openPinned` callbacks) now goes through `SkeinApp`'s
        // `overlay` slot instead of a `Box` this composable used to wrap
        // `SkeinApp` in — see `overlay`'s own kdoc on `SkeinApp` for why that
        // slot exists rather than reusing one of the other three.
        var graphDocId by remember { mutableStateOf<DocId?>(null) }
        val coroutineScope = rememberCoroutineScope()
        // skein-64y9: hoisted here (rather than inside `TimelineDestination`)
        // so `SkeinApp`'s `timelinePane` slot — composed by `AdaptivePaneHost`
        // itself, outside any tab — shares one `TimelineState`/subscription
        // with the rest of this shell's timeline surface.
        val timelinePersonaSource = remember(session) { session.personaService.observeAll() }
        val timelinePaneState = rememberTimelineState(repo = session.repository, personaSource = timelinePersonaSource)
        SkeinApp(
            // bd `skein-l9oi`: the same activity-wide mode `VaultGate`'s
            // pre-unlock screens already got.
            themeMode = themeMode,
            // E6.I4 slice A (skein-ps0): the command bar's `/new note` and
            // plain-text search. Same `session.repository` instance the
            // timeline above observes, so a note created via `/new note`
            // shows up there with no extra wiring (`VaultRepositoryImpl`'s
            // change bus re-emits `observeTimeline` on any write through
            // this repository). No "current persona" concept exists in this
            // shell yet (only the full list `personaService.observeAll()`
            // surfaces), so `personaId` stays the `SkeinApp` default (null).
            vaultRepository = session.repository,
            destinationContent = { destination ->
                when (destination) {
                    Destination.TIMELINE -> TimelineDestination(session)
                    Destination.SETTINGS -> {
                        // E3.I14 (skein-up0/skein-qsux): the idle-timeout/
                        // lock-on-screen-off/lock-on-background flows and
                        // setters, plus the read-only StrongBox status flow.
                        // `VaultServices.forDevice`'s own live `combine(...)`
                        // collection (not this activity) remains the single
                        // writer into `UnlockManager.configure` — these are
                        // wired here purely so Settings › Security can display
                        // and persist them via `SecurityPrefs`.
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
                                // E3.I11 (skein-v9g): the opt-in passphrase
                                // export of the vault key. `vaultUnlockedFlow`
                                // is the row's hard gate; `reauthenticate`
                                // presents a FRESH prompt at the moment of
                                // export; `buildRecoveryExport` is the only
                                // place the in-memory master is read, and it
                                // wipes its own copy.
                                vaultUnlockedFlow = vaultUnlockedFlow,
                                reauthenticate = reauthenticateForExport,
                                buildRecoveryExport = buildRecoveryExport,
                                // bd `skein-l9oi`: Settings › Appearance.
                                themeModeFlow = appearancePrefs.themeMode,
                                onSetThemeMode = setThemeMode,
                            )
                        SettingsScreen(
                            viewModel = settingsViewModel,
                            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        )
                    }
                    else -> DestinationPlaceholder(label = destination.name)
                }
            },
            flushRegistry = flushRegistry,
            noteTabContent = { tab, onPin, onOpenDocument, registry ->
                NoteTab(
                    docId = tab.docId,
                    vaultRepository = session.repository,
                    indexStore = session.indexStore,
                    onPin = onPin,
                    onOpenDocument = onOpenDocument,
                    onOpenGraph = { docId -> graphDocId = docId },
                    registerFlush = { flush -> registry.register(tab.id, flush) },
                    unregisterFlush = { registry.unregister(tab.id) },
                )
            },
            timelinePane = { expanded, onEntryOpen, onEntryPin ->
                if (expanded) {
                    TimelineScreen(
                        state = timelinePaneState,
                        onEntryClick = { document -> onEntryOpen(document.id, document.title) },
                        onEntryLongPress = { document -> onEntryPin(document.id, document.title) },
                        expanded = true,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    TimelineRail(
                        state = timelinePaneState,
                        onEntryClick = { document -> onEntryOpen(document.id, document.title) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            },
            overlay = { openPreview, openPinned ->
                // skein-0td0: node tap/long-press now resolve the tapped
                // document's title via `session.repository` (the same
                // `getDocument` lookup `NoteTabState.resolveTitleThenOpen`
                // uses for a backlink tap, since a graph node hands back only
                // a `DocId` too) and hand it to `SkeinApp`'s `overlay`
                // callbacks — real `TabsState.openPreview`/`openPinned` calls
                // on the primary pane — then dismiss the overlay, same as
                // `onClose` already did.
                graphDocId?.let { docId ->
                    GraphScreen(
                        docId = docId,
                        vaultRepository = session.repository,
                        indexStore = session.indexStore,
                        onOpenPreview = { tappedId ->
                            coroutineScope.launch {
                                val title = session.repository.getDocument(tappedId)?.title ?: tappedId
                                openPreview(tappedId, title)
                                graphDocId = null
                            }
                        },
                        onOpenPinned = { tappedId ->
                            coroutineScope.launch {
                                val title = session.repository.getDocument(tappedId)?.title ?: tappedId
                                openPinned(tappedId, title)
                                graphDocId = null
                            }
                        },
                        onClose = { graphDocId = null },
                    )
                }
            },
        )
    }

    /**
     * bd `skein-l9oi`: SYSTEM keeps the platform's own auto day/night
     * detection (`enableEdgeToEdge()`'s own default); an explicit LIGHT/DARK
     * override forces status/navigation bar icon contrast to match,
     * regardless of the device's own day/night setting.
     */
    private fun edgeToEdgeStyleFor(mode: SkeinThemeMode): SystemBarStyle =
        when (mode) {
            SkeinThemeMode.SYSTEM -> SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
            SkeinThemeMode.LIGHT -> SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
            SkeinThemeMode.DARK -> SystemBarStyle.dark(AndroidColor.TRANSPARENT)
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
 * The Timeline destination over the open vault: `TimelineScreen` fed by the
 * live `VaultRepository` and the persona list. Wired through `SkeinApp`'s
 * `destinationContent` for `Destination.TIMELINE` — the same path Settings
 * uses — which today `TabHost` composes only while a tab is active, and the
 * dedicated left timeline pane is `SkeinApp`'s own slot-less placeholder
 * (not composed at all on compact widths). Making the timeline the landing
 * surface therefore needs the shell slot tracked in bd `skein-64y9`; this
 * bring-up owns the data wiring, not the shell's IA. Entry taps open nothing
 * yet (note/chat tabs are `E6.I8`+ / `E7.I3`+).
 */
@Composable
private fun TimelineDestination(session: VaultSession) {
    val personaSource = remember(session) { session.personaService.observeAll() }
    val state = rememberTimelineState(repo = session.repository, personaSource = personaSource)
    TimelineScreen(state = state, onEntryClick = {}, modifier = Modifier.fillMaxSize())
}

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
 *  - recovery pending → [RecoveryRequiredNotice];
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
    // bd `skein-l9oi`: setup/unlock must honour the user's choice too, not
    // just the shell — these screens render before there is a session to
    // thread it through `unlockedContent`, so it comes in as its own param.
    themeMode: SkeinThemeMode = SkeinThemeMode.SYSTEM,
) {
    val unlockState by vault.unlockManager.state.collectAsState()
    val session by vault.session.collectAsState()
    var recoveryRequired by remember { mutableStateOf(false) }
    var provisioned by remember { mutableStateOf<Boolean?>(null) }
    // skein-v3wb: local to the gate — reachable ONLY via
    // BiometricUnlockScreen's corrupt/unreadable-envelope affordance
    // (onResetRequested below), never a `GatePhase` of its own.
    var resetRequested by remember { mutableStateOf(false) }
    LaunchedEffect(vault) {
        provisioned = withContext(Dispatchers.IO) { vault.keyProvider.isInitialised() }
    }
    when (val phase = gatePhase(session, unlockState, recoveryRequired, provisioned)) {
        is GatePhase.Open -> unlockedContent(phase.session)
        GatePhase.Opening ->
            SkeinTheme(mode = themeMode) { EdgeToEdgeSurface { m -> OpeningVault(vault.bootstrap, modifier = m) } }
        GatePhase.RecoveryRequired ->
            SkeinTheme(mode = themeMode) { EdgeToEdgeSurface { m -> RecoveryRequiredNotice(modifier = m) } }
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
                                provisioned = false
                            },
                            onDismiss = { resetRequested = false },
                            modifier = m,
                        )
                    } else {
                        BiometricUnlockScreen(
                            unlockManager = vault.unlockManager,
                            onUnlocked = { onUnlocked() },
                            onRecoveryRequired = { recoveryRequired = true },
                            onNotInitialised = { provisioned = false },
                            onResetRequested = { resetRequested = true },
                            modifier = m,
                        )
                    }
                }
            }
    }
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
                Text(text = "Opening vault…", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(GATE_SPACING),
                modifier = Modifier.padding(horizontal = GATE_GUTTER).testTag(VaultGateTestTags.OPEN_FAILED),
            ) {
                Text(
                    text = "The vault could not be opened: $reason",
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

/** `KeyPermanentlyInvalidated` surfaced; the rewrap UI (`E3.I5`+) is not wired in this bring-up. */
@Composable
private fun RecoveryRequiredNotice(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = "The biometric key was invalidated. Recovery is not available in this build yet.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = GATE_GUTTER).testTag(VaultGateTestTags.RECOVERY_REQUIRED),
        )
    }
}

private val GATE_SPACING = 12.dp
private val GATE_GUTTER = 24.dp
