// skein-2qv (E6.I7): design-time `@Preview`s for `TimelineScreen` and
// `TimelineRail`, seeded with `InMemoryVaultRepository` +
// `SyntheticVault.Preset.MEDIUM` (500 documents) so Android Studio's
// preview panel renders realistic content without an emulator.
//
// Lives under `src/debug/` (not `main`): `:testing` — the fake repository
// and the synthetic vault — is a `debugImplementation` dependency only, so
// neither ever reaches a release build.

package app.skein.feature.timeline

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import us.aherrera.skein.core.model.Persona
import us.aherrera.skein.testing.InMemoryVaultRepository
import us.aherrera.skein.testing.fixtures.SyntheticVault
import java.util.concurrent.atomic.AtomicLong

@Preview(showBackground = true, widthDp = 380, heightDp = 720, name = "Medium vault (compact)")
@Composable
private fun TimelineScreen_MediumVault_Compact() {
    val state = rememberPreviewState { SyntheticVault.seed(it, size = SyntheticVault.Preset.MEDIUM) }
    Surface(color = MaterialTheme.colorScheme.background) {
        TimelineScreen(
            state = state,
            onEntryClick = {},
            onNewNote = {},
            onNewChat = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Preview(showBackground = true, widthDp = 840, heightDp = 600, name = "Medium vault (expanded)")
@Composable
private fun TimelineScreen_MediumVault_Expanded() {
    val state = rememberPreviewState { SyntheticVault.seed(it, size = SyntheticVault.Preset.MEDIUM) }
    Surface(color = MaterialTheme.colorScheme.background) {
        TimelineScreen(
            state = state,
            onEntryClick = {},
            onNewNote = {},
            onNewChat = {},
            expanded = true,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Preview(showBackground = true, widthDp = 380, heightDp = 480, name = "Empty vault")
@Composable
private fun TimelineScreen_Empty() {
    val state = rememberPreviewState()
    Surface(color = MaterialTheme.colorScheme.background) {
        TimelineScreen(state = state, onEntryClick = {}, modifier = Modifier.fillMaxSize())
    }
}

@Preview(showBackground = true, widthDp = 40, heightDp = 720, name = "Rail")
@Composable
private fun TimelineRail_MediumVault() {
    val state = rememberPreviewState { SyntheticVault.seed(it, size = SyntheticVault.Preset.MEDIUM) }
    Surface(color = MaterialTheme.colorScheme.surface) {
        TimelineRail(state = state, onEntryClick = {})
    }
}

/**
 * Builds a seeded [TimelineState] for a preview. The repository's clock
 * steps *backwards* from now on every write so the 500 synthetic documents
 * spread across several days (exercising "Today" / "Yesterday" / dated
 * headers and every relative-time bucket) instead of all landing in one
 * "just now" section.
 *
 * The state's scope uses [Dispatchers.Unconfined] rather than
 * `rememberCoroutineScope()`: the design-time renderer does not pump a
 * main dispatcher, and the fake repository emits synchronously on
 * subscription, so an unconfined scope lets `collectAsState` observe the
 * seeded list in the very first frame.
 */
@Composable
private fun rememberPreviewState(seed: (InMemoryVaultRepository) -> Unit = {}): TimelineState {
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Unconfined) }
    DisposableEffect(scope) { onDispose { scope.cancel() } }
    return remember {
        val tick = AtomicLong(System.currentTimeMillis())
        val repo = InMemoryVaultRepository(clock = { tick.getAndAdd(-PREVIEW_STEP_MILLIS) }).also(seed)
        TimelineState(repo = repo, scope = scope, personaSource = flowOf(PREVIEW_PERSONAS))
    }
}

/** 41 minutes per write: MEDIUM's ~600 clock ticks span roughly two and a half weeks. */
private const val PREVIEW_STEP_MILLIS: Long = 41 * 60_000L

/** Matches the persona ids `SyntheticVault` stamps onto its notes. */
private val PREVIEW_PERSONAS: List<Persona> =
    listOf("default" to "Default", "work" to "Work", "research" to "Research", "personal" to "Personal")
        .map { (id, name) -> Persona(id = id, name = name, systemPrompt = null, defaultModel = null, createdAt = 0L) }
