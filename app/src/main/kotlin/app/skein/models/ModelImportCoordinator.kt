package app.skein.models

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import app.skein.core.inference.models.ContentResolverPickedFileReader
import app.skein.core.inference.models.ImmutableModelStore
import app.skein.core.inference.models.ImportOutcome
import app.skein.core.inference.models.ModelCopyResult
import app.skein.core.inference.models.ModelImportStager
import app.skein.core.inference.models.ModelManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * One import per app process. UI only admits and observes; its coroutine/lifecycle never owns copy.
 * Lock detaches the session and cancels only inspection/registration, leaving opaque copy running.
 * State contains no URI, model name, vault data or registry record. Process death does not replay a
 * provider grant: completed sealed copies use the existing next-unlock orphan adoption path.
 */
public class ModelImportCoordinator internal constructor(
    public val store: ImmutableModelStore,
    private val copy: suspend (Uri, (Long, Long) -> Unit) -> ModelCopyResult,
    private val startExecution: (Uri) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val monitor = Any()
    private val mutableState = MutableStateFlow<ModelImportState>(ModelImportState.Idle)
    public val state: StateFlow<ModelImportState> = mutableState.asStateFlow()
    private var pending: Uri? = null
    private var operation: Job? = null
    private var session: RegistrationSession? = null

    /** False means an import already owns admission, there is no unlocked session, or FGS refused. */
    public fun startImport(uri: Uri): Boolean =
        synchronized(monitor) {
            if (mutableState.value is ModelImportState.Running || session == null) return false
            pending = uri
            mutableState.value = ModelImportState.Running(null)
            try {
                startExecution(uri)
                true
            } catch (_: RuntimeException) {
                pending = null
                mutableState.value = ModelImportState.Done(ModelImportOutcome.FAILED)
                false
            }
        }

    public fun dismissResult() {
        synchronized(monitor) {
            if (mutableState.value is ModelImportState.Done) mutableState.value = ModelImportState.Idle
        }
    }

    public fun attach(
        manager: ModelManager,
        onRegistered: suspend () -> Unit,
    ) {
        attach(manager) { copied ->
            val result = manager.registerCopied(copied)
            if (result is ImportOutcome.Imported) {
                manager.setDefault(result.record.model.id)
                currentCoroutineContext().ensureActive()
                onRegistered()
            }
            result
        }
    }

    internal fun attach(
        owner: Any,
        register: suspend (ModelCopyResult.Copied) -> ImportOutcome,
    ) {
        synchronized(monitor) {
            session?.scope?.cancel()
            session = RegistrationSession(owner, register, CoroutineScope(scope.coroutineContext + SupervisorJob()))
        }
    }

    /** No joining or two-way RPC on HIGH. Identity prevents stale teardown revoking a newer unlock. */
    public fun detach(manager: ModelManager) = detachOwner(manager)

    internal fun detachOwner(owner: Any) {
        synchronized(monitor) {
            if (session?.owner !== owner) return
            session?.scope?.cancel()
            session = null
        }
    }

    /** Called only after the Android service successfully entered foreground. Duplicate starts no-op. */
    internal fun executePending(onFinished: () -> Unit): Boolean =
        synchronized(monitor) {
            val uri = pending ?: return false
            pending = null
            operation =
                scope
                    .launch(start = CoroutineStart.LAZY) {
                        var result = ModelImportOutcome.FAILED
                        try {
                            result =
                                when (val copied = copy(uri, ::publishProgress)) {
                                    is ModelCopyResult.Refused -> ModelImportOutcome.REFUSED
                                    is ModelCopyResult.Copied -> registerIfUnlocked(copied)
                                }
                        } catch (_: CancellationException) {
                            // Any sealed file remains recoverable; incomplete .tmp bytes are never loadable.
                        } catch (_: Exception) {
                            // Deliberately do not retain provider messages, paths or closed-session failures.
                        } finally {
                            synchronized(monitor) {
                                operation = null
                                mutableState.value = ModelImportState.Done(result)
                            }
                            onFinished()
                        }
                    }.also { it.start() }
            true
        }

    private suspend fun registerIfUnlocked(copied: ModelCopyResult.Copied): ModelImportOutcome {
        val registration =
            synchronized(monitor) {
                val current = session ?: return ModelImportOutcome.SAVED_FOR_UNLOCK
                current.scope.async { current.register(copied) }
            }
        return try {
            when (registration.await()) {
                is ImportOutcome.Imported -> ModelImportOutcome.IMPORTED
                is ImportOutcome.Refused -> ModelImportOutcome.REFUSED
            }
        } catch (_: CancellationException) {
            ModelImportOutcome.SAVED_FOR_UNLOCK
        } finally {
            registration.cancel()
        }
    }

    private fun publishProgress(
        processed: Long,
        total: Long,
    ) {
        synchronized(monitor) {
            val fraction = if (total > 0) ((processed * 100 / total).coerceIn(0, 100) / 100f) else null
            mutableState.value = ModelImportState.Running(fraction)
        }
    }

    /** Android timeout/destruction revokes copy; admission stays occupied until its actual finally. */
    internal fun executionStopped() {
        synchronized(monitor) {
            pending = null
            if (operation != null) {
                operation?.cancel()
            } else if (mutableState.value is ModelImportState.Running) {
                mutableState.value = ModelImportState.Done(ModelImportOutcome.FAILED)
            }
        }
    }

    private class RegistrationSession(
        val owner: Any,
        val register: suspend (ModelCopyResult.Copied) -> ImportOutcome,
        val scope: CoroutineScope,
    )

    public companion object {
        @Volatile private var instance: ModelImportCoordinator? = null

        public fun forApplication(context: Context): ModelImportCoordinator =
            instance ?: synchronized(this) {
                instance ?: create(context.applicationContext).also { instance = it }
            }

        private fun create(context: Context): ModelImportCoordinator {
            val root = File(context.filesDir, "models")
            val store = ImmutableModelStore(root)
            val stager =
                ModelImportStager(
                    store,
                    ContentResolverPickedFileReader(context.contentResolver),
                    { context.filesDir.usableSpace },
                )
            return ModelImportCoordinator(
                store = store,
                copy = stager::copy,
                startExecution = { uri ->
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, ModelImportService::class.java)
                            .setData(uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    )
                },
            )
        }
    }
}

public sealed interface ModelImportState {
    public data object Idle : ModelImportState

    public data class Running(
        val fraction: Float?,
    ) : ModelImportState

    public data class Done(
        val outcome: ModelImportOutcome,
    ) : ModelImportState
}

public enum class ModelImportOutcome { IMPORTED, SAVED_FOR_UNLOCK, REFUSED, FAILED }
