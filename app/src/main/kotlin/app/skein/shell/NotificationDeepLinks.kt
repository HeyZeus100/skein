package app.skein.shell

import android.content.Intent
import androidx.lifecycle.ViewModel
import app.skein.core.navigation.KnowledgeHomeKey
import app.skein.core.navigation.ModelsHomeKey
import app.skein.core.navigation.SkeinKey
import app.skein.core.vault.session.LockObserver
import app.skein.core.vault.session.LockObserverPriority
import app.skein.core.vault.session.UnlockManager
import app.skein.core.vault.session.UnlockState
import app.skein.feature.shell.host.SkeinShellState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/** AL-14: only these two content-free notification destinations may enter from the exported Activity. */
internal enum class NotificationDestination(
    val root: SkeinKey,
) {
    MODELS(ModelsHomeKey),
    KNOWLEDGE(KnowledgeHomeKey),
}

/**
 * Exact spellings deliberately reject queries, fragments, ids, encoded paths, credentials and ports.
 * Notification producers use ACTION_MAIN; shares, arbitrary actions and extras have no meaning here.
 * Never decode extras (which may even contain hostile Parcelables), retain a URI or log rejected input.
 */
internal fun notificationDestination(intent: Intent?): NotificationDestination? =
    if (intent?.action != Intent.ACTION_MAIN) {
        null
    } else {
        when (intent.dataString) {
            "app://skein/models" -> NotificationDestination.MODELS
            "app://skein/ingest" -> NotificationDestination.KNOWLEDGE
            else -> null
        }
    }

/**
 * T4, held by the Activity's ViewModelStore, never SavedStateHandle or a Bundle. Configuration
 * recreation retains a pending enum; process death drops it and must not replay the old launch Intent.
 * A real lock clears the old session's request even while the Activity/recomposer is stopped. A new
 * notification received while already locked can wait for the next successful unlock and restoration.
 */
internal class NotificationDeepLinks(
    private val unlockManager: UnlockManager,
) : ViewModel(),
    LockObserver {
    private val mutablePending = MutableStateFlow<NotificationDestination?>(null)
    val pending = mutablePending.asStateFlow()
    private var initialIntentRead = false

    // Last: an observer can be called from another thread as soon as it is registered.
    private val registration = unlockManager.addLockObserver(this)

    fun onCreate(
        intent: Intent?,
        restoring: Boolean,
    ) {
        if (initialIntentRead) return
        initialIntentRead = true
        if (!restoring) onNewIntent(intent)
    }

    fun onNewIntent(intent: Intent?) {
        // Invalid input is ignored; the newest valid destination replaces the pending one.
        notificationDestination(intent)?.let { mutablePending.value = it }
    }

    /** Called only below VaultGate, after the host has sanitised every restored stack. */
    fun applyPending(shell: SkeinShellState) {
        if (unlockManager.state.value !is UnlockState.Unlocked) return
        val destination = mutablePending.getAndUpdate { null } ?: return
        shell.dismissTransientSurfaces()
        shell.navigate { goTo(it, destination.root) }
    }

    fun clear() {
        mutablePending.value = null
    }

    override val priority = LockObserverPriority.LOW

    override suspend fun onLocking(
        epoch: Long,
        budgetMillis: Long,
    ) = Unit

    override fun onLocked(epoch: Long) = clear()

    override fun onUnlocked(epoch: Long) = Unit

    override fun onCleared() {
        registration.dispose()
        clear()
    }
}
