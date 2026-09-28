package app.skein.feature.chat.drafts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.skein.core.model.ChatDraft
import app.skein.core.model.ChatDraftKey
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/**
 * Controlled composer seam for AL-10. Text and selection come from the session store. Only the IME
 * composition range is local, and it is never persisted. No SavedStateHandle or text Saver is used.
 * Callers show [DraftLoadState.LoadError] explicitly and keep the field disabled until [enabled].
 * This adapter deliberately offers no send/clear: only atomic USER+draft-delete may acknowledge a send.
 */
@Stable
public class DraftComposerState internal constructor(
    private val store: SessionDraftStore,
    private val key: ChatDraftKey,
    private val source: StateFlow<DraftLoadState>,
    private val observed: State<DraftRenderStamp>,
) {
    private var composition by mutableStateOf<TextRange?>(null)
    private var composedVersion: Long? = null

    public val status: DraftLoadState
        get() {
            // Subscribe only to content-free invalidation; read the scrubbed source synchronously.
            observed.value
            return source.value
        }
    public val enabled: Boolean get() = status is DraftLoadState.Ready && store.isWritable
    public val snapshot: DraftSnapshot? get() = (status as? DraftLoadState.Ready)?.snapshot
    public val value: TextFieldValue
        get() {
            val current = snapshot ?: return TextFieldValue("")
            return TextFieldValue(
                current.draft.text,
                TextRange(current.draft.selectionStart, current.draft.selectionEnd),
                composition.takeIf { composedVersion == current.version },
            )
        }

    public fun onValueChange(value: TextFieldValue) {
        val accepted =
            store.update(
                key,
                ChatDraft(value.text, value.selection.start, value.selection.end),
            ) ?: return
        composedVersion = accepted.version
        composition = value.composition
    }

    public fun retryLoad() {
        store.retryLoad(key)
    }
}

/** Observes the session-owned flow; stopping the Activity schedules a session-owned, phase-checked flush. */
@Composable
public fun rememberDraftComposerState(
    store: SessionDraftStore,
    key: ChatDraftKey,
): DraftComposerState {
    val source = remember(store, key) { store.state(key) }
    val signals = remember(source) { source.map(DraftLoadState::renderStamp) }
    val observed = signals.collectAsState(initial = DraftRenderStamp(0))
    val adapter = remember(store, key) { DraftComposerState(store, key, source, observed) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { store.requestStopFlush() }
    return adapter
}

/** No text in the Compose observation State while its collector is paused or awaiting dispatch. */
internal data class DraftRenderStamp(
    val phase: Int,
    val version: Long = 0,
    val saveFailed: Boolean = false,
)

private fun DraftLoadState.renderStamp(): DraftRenderStamp =
    when (this) {
        DraftLoadState.Loading -> DraftRenderStamp(0)
        is DraftLoadState.Ready -> DraftRenderStamp(1, snapshot.version, saveFailed)
        DraftLoadState.LoadError -> DraftRenderStamp(2)
        DraftLoadState.Closed -> DraftRenderStamp(3)
    }
