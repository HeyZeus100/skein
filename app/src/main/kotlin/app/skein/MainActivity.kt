package app.skein

import android.app.ActivityManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import app.skein.feature.editor.notetab.NoteTab
import app.skein.feature.graph.GraphScreen
import app.skein.feature.settings.SettingsScreen
import app.skein.feature.settings.rememberSettingsViewModel
import app.skein.feature.shell.DestinationPlaceholder
import app.skein.feature.shell.SkeinApp
import app.skein.feature.shell.auth.BiometricUnlockScreen
import app.skein.feature.shell.auth.VaultSetupScreen
import app.skein.feature.shell.nav.Destination
import app.skein.feature.shell.tabs.FlushRegistry
import app.skein.feature.shell.theme.SkeinTheme
import app.skein.feature.timeline.TimelineScreen
import app.skein.feature.timeline.rememberTimelineState
import app.skein.system.SecurityPrefs
import app.skein.vault.BringUpResult
import app.skein.vault.GatePhase
import app.skein.vault.VaultBootstrap
import app.skein.vault.VaultServices
import app.skein.vault.VaultSession
import app.skein.vault.gatePhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import us.aherrera.skein.core.model.DocId

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

        val vault = (application as SkeinApplication).vault
        setContent {
            VaultGate(
                vault = vault,
                onUnlocked = { lifecycleScope.launch { vault.bootstrap.bringUp() } },
                onProvisioned = { strongBoxBacked ->
                    // skein-ank2: recorded for Settings › Security (skein-3el).
                    lifecycleScope.launch { securityPrefs.setStrongBoxUnavailableFallback(!strongBoxBacked) }
                },
                unlockedContent = { session -> UnlockedShell(session) },
            )
        }
    }

    @Composable
    private fun UnlockedShell(session: VaultSession) {
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
        // *alongside* `SkeinApp` (see that composable's own file header) —
        // not a `Destination`/`noteTabContent` slot — so it is local
        // `mutableStateOf` state here in `MainActivity`, not a new
        // `:feature:shell` API. This is not a shell-slot change (no new
        // `SkeinApp` parameter, no change to `Destination`); it is purely
        // this private composable's own body, same as `VaultGate` already
        // layering screens outside `SkeinApp`.
        var graphDocId by remember { mutableStateOf<DocId?>(null) }
        Box(Modifier.fillMaxSize()) {
            SkeinApp(
                destinationContent = { destination ->
                    when (destination) {
                        Destination.TIMELINE -> TimelineDestination(session)
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
            )
            graphDocId?.let { docId ->
                // `onOpenPreview`/`onOpenPinned` (tap/long-press on a node)
                // only dismiss the overlay for now: actually opening the
                // tapped document as a preview/pinned tab from here would
                // need a way to reach the active `TabHost`'s `TabsState`,
                // which `SkeinApp` does not expose outside its own
                // `noteTabContent`/`destinationContent` slots. That is a new
                // `:feature:shell` API (bd non-negotiable: no such change
                // without a bead) — filed as skein follow-up rather than
                // guessed at here (precedent: skein-64y9).
                GraphScreen(
                    docId = docId,
                    vaultRepository = session.repository,
                    indexStore = session.indexStore,
                    onOpenPreview = { graphDocId = null },
                    onOpenPinned = { graphDocId = null },
                    onClose = { graphDocId = null },
                )
            }
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
) {
    val unlockState by vault.unlockManager.state.collectAsState()
    val session by vault.session.collectAsState()
    var recoveryRequired by remember { mutableStateOf(false) }
    var provisioned by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(vault) {
        provisioned = withContext(Dispatchers.IO) { vault.keyProvider.isInitialised() }
    }
    when (val phase = gatePhase(session, unlockState, recoveryRequired, provisioned)) {
        is GatePhase.Open -> unlockedContent(phase.session)
        GatePhase.Opening -> SkeinTheme { OpeningVault(vault.bootstrap) }
        GatePhase.RecoveryRequired -> SkeinTheme { RecoveryRequiredNotice() }
        GatePhase.Probing -> SkeinTheme { ProbingVault() }
        GatePhase.Setup ->
            SkeinTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    VaultSetupScreen(
                        keyProvider = vault.keyProvider,
                        onProvisioned = { strongBoxBacked ->
                            onProvisioned(strongBoxBacked)
                            provisioned = true
                        },
                        onAlreadyInitialised = { provisioned = true },
                    )
                }
            }
        GatePhase.Unlock ->
            SkeinTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    BiometricUnlockScreen(
                        unlockManager = vault.unlockManager,
                        onUnlocked = { onUnlocked() },
                        onRecoveryRequired = { recoveryRequired = true },
                        onNotInitialised = { provisioned = false },
                    )
                }
            }
    }
}

/** The envelope probe is a sub-millisecond file read; a bare surface avoids a spinner flash. */
@Composable
private fun ProbingVault() {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize().testTag(VaultGateTestTags.PROBING))
    }
}

@Composable
private fun OpeningVault(bootstrap: VaultBootstrap) {
    var attempt by remember { mutableIntStateOf(0) }
    var failure by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(bootstrap, attempt) {
        failure = null
        failure =
            when (val result = bootstrap.bringUp()) {
                is BringUpResult.Ready, BringUpResult.NotUnlocked -> null
                is BringUpResult.Failed -> result.reason
            }
    }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
}

/** `KeyPermanentlyInvalidated` surfaced; the rewrap UI (`E3.I5`+) is not wired in this bring-up. */
@Composable
private fun RecoveryRequiredNotice() {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "The biometric key was invalidated. Recovery is not available in this build yet.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = GATE_GUTTER).testTag(VaultGateTestTags.RECOVERY_REQUIRED),
            )
        }
    }
}

private val GATE_SPACING = 12.dp
private val GATE_GUTTER = 24.dp
