// skein-xtov.24.8 (AL-09a): the real `SkeinShellHost` over the Chat and
// Knowledge entries, as `:app`'s `NavShell` wires them, for the entry tests
// and the layout goldens.
package app.skein.feature.chat.entries

import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.skein.core.model.Capability
import app.skein.core.model.Model
import app.skein.core.model.ModelFormat
import app.skein.core.model.Persona
import app.skein.core.model.TokenBudget
import app.skein.core.model.VaultRepository
import app.skein.core.navigation.Destination
import app.skein.core.navigation.destination
import app.skein.core.vault.key.VaultKeyProvider
import app.skein.core.vault.session.UnlockManager
import app.skein.feature.chat.SendPipeline
import app.skein.feature.chat.SimplePromptAssembler
import app.skein.feature.editor.entries.KnowledgeDetailPlaceholder
import app.skein.feature.editor.entries.KnowledgeEntry
import app.skein.feature.editor.entries.KnowledgeEntryDeps
import app.skein.feature.shell.host.SkeinShellHost
import app.skein.feature.shell.host.SkeinShellState
import app.skein.feature.shell.host.navKindsOf
import app.skein.feature.shell.host.rememberSkeinShellState
import app.skein.testing.FakeInferenceEngine
import app.skein.testing.FakeRetrievalService
import app.skein.testing.InMemoryIndexStore
import java.lang.reflect.Proxy
import java.time.ZoneId
import java.time.ZoneOffset

/** The Fold's outer display: Compact, a drawer, one pane. */
internal val COMPACT = DpSize(524.dp, 1175.dp)

/** The Fold's inner display: Expanded, a rail, two panes. */
internal val EXPANDED = DpSize(1006.dp, 1043.dp)

/** The session stores register with an [UnlockManager]; these tests never unlock or lock through it. */
internal fun idleUnlockManager(): UnlockManager =
    UnlockManager(
        keyProvider =
            Proxy.newProxyInstance(
                VaultKeyProvider::class.java.classLoader,
                arrayOf(VaultKeyProvider::class.java),
            ) { _, method, _ ->
                if (method.returnType == Boolean::class.javaPrimitiveType) false else null
            } as VaultKeyProvider,
    )

internal val TEXT_MODEL =
    Model(
        id = "fake-model",
        name = "Fake",
        path = "/dev/null/fake.gguf",
        sha256 = "a".repeat(64),
        format = ModelFormat.GGUF,
        capabilities = setOf(Capability.TEXT),
        sizeBytes = 1_000L,
    )

internal fun pipelineOver(
    vault: VaultRepository,
    engine: FakeInferenceEngine,
): SendPipeline =
    SendPipeline(
        vaultRepository = vault,
        retrievalService = FakeRetrievalService(emptyList()),
        promptAssembler = SimplePromptAssembler(),
        engine = engine,
        personaProvider = { null },
        personaById = { Persona(it, "Fixture Space", null, null, 0) },
        budgetFor = { _, _ -> TokenBudget(contextLength = 16_384, reserveForAnswer = 1024, maxRetrievedTokens = 3072) },
        countTokens = { it.length / 4 },
    )

/**
 * The shell with this bead's two destinations. [size] overrides the window
 * (live flips); null keeps the device's (the screenshot qualifiers).
 */
@Composable
internal fun EntriesHost(
    vault: VaultRepository,
    pipeline: SendPipeline?,
    size: DpSize?,
    onShell: (SkeinShellState) -> Unit,
    clock: () -> Long = System::currentTimeMillis,
    zone: ZoneId = ZoneOffset.UTC,
    windowAdaptiveInfo: WindowAdaptiveInfo? = null,
) {
    if (size == null) {
        Host(vault, pipeline, onShell, clock, zone, windowAdaptiveInfo)
    } else {
        DeviceConfigurationOverride(DeviceConfigurationOverride.WindowSize(size)) {
            Host(vault, pipeline, onShell, clock, zone, windowAdaptiveInfo)
        }
    }
}

@Composable
private fun Host(
    vault: VaultRepository,
    pipeline: SendPipeline?,
    onShell: (SkeinShellState) -> Unit,
    clock: () -> Long,
    zone: ZoneId,
    windowAdaptiveInfo: WindowAdaptiveInfo?,
) {
    val manager = remember { idleUnlockManager() }
    val shell = rememberSkeinShellState(manager)
    onShell(shell)
    val knowledge = remember(vault) { KnowledgeEntryDeps(vault, InMemoryIndexStore(), clock = clock, zone = zone) }
    val handoff = remember { ChatHandoff() }
    val history = rememberChatHistory(vault, shell, zone = zone, now = clock)
    val chat =
        ChatEntryDeps(vault, knowledge, pipeline, hasModel = pipeline != null, handoff = handoff, history = history)
    SkeinShellHost(
        shell = shell,
        resolveKinds = navKindsOf(vault),
        history = history,
        windowAdaptiveInfo = windowAdaptiveInfo ?: currentWindowAdaptiveInfoV2(),
        zone = zone,
        now = clock,
        detailPlaceholder = { destination ->
            when (destination) {
                Destination.CHAT -> ChatDetailPlaceholder(shell, chat)
                else -> KnowledgeDetailPlaceholder(shell)
            }
        },
    ) { key ->
        when (key.destination) {
            Destination.CHAT -> ChatEntry(key, shell, chat)
            else -> KnowledgeEntry(key, shell, knowledge)
        }
    }
}
