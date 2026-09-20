package app.skein.feature.editor

import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration

/**
 * Host state for [SkeinEditor]. Holds the raw Markdown [value] (source of
 * truth; passed unchanged to autosave in `E7.I4`) plus the wikilink open
 * callback the caller uses to route `[[Note]]` clicks through the vault.
 *
 * ## Why the caller resolves wikilinks
 *
 * `:feature:editor` is deliberately vault-free (bd `skein-03f` guardrails,
 * spec §8.5): a note titled `[[some-secret]]` typed into the editor should
 * never trigger a `VaultRepository.searchTitles(...)` fetch as a side
 * effect of a keystroke. Instead this state exposes an [onLinkOpen]
 * callback the parent — a note-tab host that already holds a repository
 * reference (`E6.I9`) — wires in. The state hands the parent a
 * [WikilinkTarget] and the parent decides whether to open, prompt, or
 * ignore.
 *
 * ## Broken-link dimming
 *
 * When [knownWikilinkTitles] is non-null it is passed to the transformer;
 * wikilinks whose title is NOT in the set render dimmed (broken-link
 * cue). `null` (the default) means "don't dim anything" — a caller that
 * doesn't yet have vault access can still use the editor; nothing renders
 * broken by mistake.
 *
 * ## Autosave (`E7.I4`, bd `skein-twb`)
 *
 * This module never touches `VaultRepository` directly (same guardrail as
 * wikilink resolution above). Instead the caller — the coordinator that
 * owns a repository reference — supplies [onSave], a suspend callback
 * invoked with the current [TextFieldValue] whenever a debounced save
 * should happen. Internally, [value] writes are observed via
 * `snapshotFlow`, coalesced with [autosaveDebounce] (default 500ms) so a
 * burst of keystrokes produces a single [onSave] call, and the result is
 * surfaced through [autosaveStatus] for a "saved" / "saving" / "unsaved
 * changes" indicator in the note header.
 *
 * On lock, `LOCK_POLICY_INDEXING.md` §4.3 requires the editor's pending
 * save to be flushed (not silently discarded) within a bounded deadline
 * before the vault closes. The coordinator calls [flush] from its
 * `LockObserver` hook for this; [flush] does not itself know about locks
 * or `recovery_drafts` — it only guarantees the current content is handed
 * to [onSave] and bounds the wait, returning whether it completed in
 * time.
 */
public class EditorState(
    initial: TextFieldValue = TextFieldValue(""),
    public val onLinkOpen: (WikilinkTarget) -> Unit = {},
    public val knownWikilinkTitles: Set<String>? = null,
    private val onSave: suspend (TextFieldValue) -> Unit = {},
    public val autosaveDebounce: Duration = Duration.ofMillis(500),
    internal val autosaveScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    public var value: TextFieldValue by mutableStateOf(initial)
        internal set

    private val autosaveStatusState = mutableStateOf(AutosaveStatus.IDLE)

    /** "saved" / "saving" / "unsaved changes" / error state for a note-header chip. */
    public val autosaveStatus: State<AutosaveStatus> get() = autosaveStatusState

    private val autosaveErrorState = mutableStateOf<String?>(null)

    /** Non-null only while [autosaveStatus] is [AutosaveStatus.ERROR]. */
    public val autosaveError: State<String?> get() = autosaveErrorState

    /** Guards [onSave] so a debounced save and a forced [flush] never overlap. */
    private val saveMutex = Mutex()

    /** Text most recently handed to [onSave] successfully. */
    private var lastSavedText: String = initial.text

    init {
        autosaveScope.launch {
            snapshotFlow { value }
                .debounce(autosaveDebounce.toMillis())
                .collect { pending -> performSave(pending) }
        }
    }

    /**
     * Updates [value] from user input, or from a programmatic edit. Kept
     * as a member function (not just direct field mutation) so callers
     * have one hook to observe every text change — used by `E7.I4`
     * autosave to snapshot the raw Markdown on debounce.
     */
    public fun onValueChange(newValue: TextFieldValue) {
        value = newValue
        if (newValue.text != lastSavedText) {
            autosaveStatusState.value = AutosaveStatus.UNSAVED
        }
        // Real Compose UI (AndroidComposeView's GlobalSnapshotManager) batches
        // and flushes apply notifications every frame automatically, but a
        // plain JVM caller (EditorAutosaveTest, or any non-Compose coordinator
        // driving EditorState directly) has no such loop running — without an
        // explicit flush here, the debounce collector's `snapshotFlow { value }`
        // below never observes this write. Safe/idempotent to call unconditionally.
        Snapshot.sendApplyNotifications()
    }

    /** The current raw Markdown source. Handed unchanged to autosave. */
    public val source: String get() = value.text

    /** The current caret offset (or the start of the selection). */
    public val cursor: Int get() = value.selection.start

    /**
     * Force-flushes whatever [onSave] hasn't committed yet, bounded by
     * [deadline]. Called by the coordinator's `LockObserver` hook
     * (`LOCK_POLICY_INDEXING.md` §4.3) — a no-op returning `true`
     * immediately if autosave already ran within the last debounce
     * interval.
     *
     * Returns `true` if the content is confirmed saved (or there was
     * nothing pending); `false` if [deadline] elapsed before the save
     * completed, in which case the in-flight [onSave] call is cancelled
     * cleanly and [autosaveStatus] reverts to [AutosaveStatus.UNSAVED] so
     * the caller knows content may still be pending.
     */
    public suspend fun flush(deadline: Duration = Duration.ofSeconds(2)): Boolean {
        val current = value
        if (current.text == lastSavedText && autosaveStatusState.value != AutosaveStatus.SAVING) {
            return true
        }
        val completed = withTimeoutOrNull(deadline.toMillis()) { performSave(current) }
        if (completed != true) {
            autosaveStatusState.value = AutosaveStatus.UNSAVED
            return false
        }
        return true
    }

    /** Runs [onSave] for [pending], updating [autosaveStatus]/[autosaveError]. Serialized by [saveMutex]. */
    private suspend fun performSave(pending: TextFieldValue): Boolean =
        saveMutex.withLock {
            if (pending.text == lastSavedText) return@withLock true
            autosaveStatusState.value = AutosaveStatus.SAVING
            try {
                onSave(pending)
                lastSavedText = pending.text
                autosaveErrorState.value = null
                autosaveStatusState.value = AutosaveStatus.SAVED
                true
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                autosaveErrorState.value = error.message
                autosaveStatusState.value = AutosaveStatus.ERROR
                false
            }
        }

    public companion object {
        /** For `rememberSaveable`: persists [TextFieldValue.text] and selection. */
        public val Saver: Saver<EditorState, *> =
            listSaver(
                save = { state ->
                    listOf(state.value.text, state.value.selection.start, state.value.selection.end)
                },
                restore = { saved ->
                    EditorState(
                        initial =
                            TextFieldValue(
                                text = saved[0] as String,
                                selection = TextRange(saved[1] as Int, saved[2] as Int),
                            ),
                    )
                },
            )
    }
}

/**
 * Autosave lifecycle for the note-header chip (`E7.I4`, bd `skein-twb`).
 *
 * - [IDLE]: no edits since the editor was opened; nothing to save.
 * - [UNSAVED]: [EditorState.value] changed since the last successful save
 *   and a debounced (or forced) save hasn't completed yet.
 * - [SAVING]: [EditorState.onSave] is currently in flight.
 * - [SAVED]: the last save completed successfully.
 * - [ERROR]: the last [EditorState.onSave] call threw; see
 *   [EditorState.autosaveError] for the message.
 */
public enum class AutosaveStatus { IDLE, SAVING, SAVED, UNSAVED, ERROR }
