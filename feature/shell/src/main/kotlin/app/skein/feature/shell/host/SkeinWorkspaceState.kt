package app.skein.feature.shell.host

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import app.skein.core.navigation.ChatKey
import app.skein.core.navigation.Destination
import app.skein.core.navigation.ChatSourceKey
import app.skein.core.navigation.FileKey
import app.skein.core.navigation.NoteKey
import app.skein.core.navigation.NewChatKey
import app.skein.core.navigation.NewNoteKey
import app.skein.core.navigation.SkeinId
import app.skein.core.navigation.SkeinKey
import app.skein.core.navigation.SkeinNavigationState
import app.skein.core.navigation.TransientKey
import app.skein.core.navigation.TransientKind
import app.skein.core.vault.session.UnlockManager

enum class WorkspacePane { PRIMARY, SECONDARY }

/** Two navigation owners over one unlocked vault. Presentation choices contain no document data. */
@Stable
class SkeinWorkspaceState internal constructor(
    val primary: SkeinShellState,
    val secondary: SkeinShellState,
    initialPane: WorkspacePane = WorkspacePane.PRIMARY,
    initiallySplit: Boolean = false,
    initiallySwapped: Boolean = false,
) {
    var activePane by mutableStateOf(initialPane)
        private set

    var splitRequested by mutableStateOf(initiallySplit)
        private set

    var panesSwapped by mutableStateOf(initiallySwapped)
        private set

    val activeShell: SkeinShellState get() = shell(activePane)
    internal var beforeActivate: (() -> Unit)? = null

    init {
        // Saved state is untrusted input. Resolve duplicate mutable owners before either host composes.
        val owned = primary.nav.currentStack.mapNotNull { mutableDocument(it, primary.nav) }.toSet()
        var restored = secondary.nav
        owned.forEach { id -> restored = secondary.navigator.prune(restored, id) }
        if (restored.currentStack.any { mutableDocument(it, restored) in owned }) {
            restored = secondary.navigator.goTo(restored, restored.currentStack.first())
        }
        val primaryDrafts = primary.nav.stacks.values.flatten().filterIsInstance<NewChatKey>().toSet()
        if (restored.stacks.values.flatten().any { it in primaryDrafts }) {
            val previousDestination = restored.topLevel
            restored = secondary.navigator.goTo(restored, NewChatKey(SkeinId.random()))
            if (previousDestination != Destination.CHAT) restored = secondary.navigator.switchTo(restored, previousDestination)
        }
        secondary.replaceNavigation(secondary.navigator.switchSpace(restored, primary.nav.space))
        primary.navigationGuard = { accept(WorkspacePane.PRIMARY, it) }
        secondary.navigationGuard = { accept(WorkspacePane.SECONDARY, it) }
    }

    fun shell(pane: WorkspacePane): SkeinShellState = if (pane == WorkspacePane.PRIMARY) primary else secondary

    fun activate(pane: WorkspacePane) {
        if (activePane != pane) beforeActivate?.invoke()
        activePane = pane
    }

    fun toggleSplit() {
        splitRequested = !splitRequested
    }

    /** Move whole owners visually; their entry composition, writers and drafts never transfer. */
    fun swapPanes() { panesSwapped = !panesSwapped }

    fun paneAtPosition(position: Int): WorkspacePane =
        WorkspacePane.entries[if (panesSwapped) 1 - position else position]

    internal fun positionOf(pane: WorkspacePane): Int = if (panesSwapped) 1 - pane.ordinal else pane.ordinal

    fun resetForNewVault() {
        primary.resetForNewVault()
        secondary.resetForNewVault()
        activePane = WorkspacePane.PRIMARY
        splitRequested = false
        panesSwapped = false
    }

    private fun accept(
        pane: WorkspacePane,
        next: SkeinNavigationState,
    ): Boolean {
        val other = other(pane)
        val otherShell = shell(other)
        val selected = next.currentStack.mapNotNull { mutableDocument(it, next) }.toSet()
        val selectedDrafts = next.currentStack.filterIsInstance<NewChatKey>().toSet()
        // Retained, unplaced editors still have pending writers. Never give one document two owners.
        if (otherShell.nav.currentStack.any { mutableDocument(it, otherShell.nav) in selected || it in selectedDrafts }) {
            activate(other)
            return false
        }
        if (next.space != otherShell.nav.space) {
            otherShell.replaceNavigation(otherShell.navigator.switchSpace(otherShell.nav, next.space))
        }
        return true
    }
}

@Composable
fun rememberSkeinWorkspaceState(unlockManager: UnlockManager): SkeinWorkspaceState {
    val primary = key(WorkspacePane.PRIMARY) { rememberSkeinShellState(unlockManager, "primary") }
    val secondary = key(WorkspacePane.SECONDARY) { rememberSkeinShellState(unlockManager, "secondary") }
    return rememberSaveable(
        primary,
        secondary,
        saver =
            Saver<SkeinWorkspaceState, IntArray>(
                save = { intArrayOf(it.activePane.ordinal, if (it.splitRequested) 1 else 0, if (it.panesSwapped) 1 else 0) },
                restore = {
                    SkeinWorkspaceState(
                        primary,
                        secondary,
                        WorkspacePane.entries.getOrElse(it.getOrNull(0) ?: 0) { WorkspacePane.PRIMARY },
                        it.getOrNull(1) == 1,
                        it.getOrNull(2) == 1,
                    )
                },
            ),
    ) { SkeinWorkspaceState(primary, secondary) }
}

private fun other(pane: WorkspacePane): WorkspacePane =
    if (pane == WorkspacePane.PRIMARY) WorkspacePane.SECONDARY else WorkspacePane.PRIMARY

private fun mutableDocument(
    key: SkeinKey,
    state: SkeinNavigationState,
): String? =
    when (key) {
        is ChatKey -> key.chatId.value
        is NoteKey -> key.docId.value
        is NewNoteKey -> key.draftId.value
        is FileKey -> key.docId.value
        is ChatSourceKey -> key.docId.value
        is TransientKey ->
            if (key.kind in setOf(TransientKind.CHAT, TransientKind.NOTE, TransientKind.FILE, TransientKind.SOURCE)) {
                state.rawIdOf(key)
            } else {
                null
            }
        else -> null
    }
