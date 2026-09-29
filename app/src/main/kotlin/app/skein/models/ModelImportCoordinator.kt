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
    private val startExecution: (Uri, Long) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val monitor = Any()
    private val mutableState = MutableStateFlow<ModelImportState>(ModelImportState.Idle)
    public val state: StateFlow<ModelImportState> = mutableState.asStateFlow()
    private var nextToken = 0L
    private var pending: Request? = null
    private var operation: Execution? = null
    private var session: RegistrationSession? = null

    /** False means an import already owns admission, there is no unlocked session, or FGS refused. */
    public fun startImport(uri: Uri): Boolean {
        val token =
            synchronized(monitor) {
                if (mutableState.value is ModelImportState.Running || session == null) return false
                val admitted = ++nextToken
                pending = Request(admitted, uri)
                mutableState.value = ModelImportState.Running(null)
                admitted
            }
        // Starting an Android service can block in Binder. HIGH detach never waits for that call.
        return try {
            startExecution(uri, token)
            true
        } catch (_: RuntimeException) {
            executionStopped(token)
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
    internal fun executePending(
        token: Long,
        onFinished: () -> Unit,
    ): Boolean {
        var result = ModelImportOutcome.FAILED
        val job =
            synchronized(monitor) {
                val request = pending?.takeIf { it.token == token } ?: return false
                pending = null
                scope
                    .launch(start = CoroutineStart.LAZY) {
                        try {
                            result =
                                when (val copied = copy(request.uri, ::publishProgress)) {
                                    is ModelCopyResult.Refused -> ModelImportOutcome.REFUSED
                                    is ModelCopyResult.Copied -> registerIfUnlocked(copied)
                                }
                        } catch (_: CancellationException) {
                            // Any sealed file remains recoverable; incomplete .tmp bytes are never loadable.
                        } catch (_: Exception) {
                            // Do not retain provider messages, paths or closed-session failures.
                        }
                    }.also { operation = Execution(token, it) }
            }
        // Completion is registered even if Android stops us before the coroutine first executes.
        // A finally inside the coroutine body would never run in that cancellation window.
        job.invokeOnCompletion {
            synchronized(monitor) {
                if (operation?.token == token) {
                    operation = null
                    mutableState.value = ModelImportState.Done(result)
                }
            }
            onFinished()
        }
        job.start()
        return true
    }

    private suspend fun registerIfUnlocked(copied: ModelCopyResult.Copied): ModelImportOutcome {
        val current = synchronized(monitor) { session ?: return ModelImportOutcome.SAVED_FOR_UNLOCK }
        val registration = current.scope.async { current.register(copied) }
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

    internal fun isRunning(token: Long): Boolean =
        synchronized(monitor) { pending?.token == token || operation?.token == token }

    /** A stale service instance must never revoke a later admission, even before its intent arrives. */
    internal fun executionStopped(token: Long) {
        synchronized(monitor) {
            if (pending?.token == token) {
                pending = null
                mutableState.value = ModelImportState.Done(ModelImportOutcome.FAILED)
            }
            if (operation?.token == token) operation?.job?.cancel()
        }
    }

    private data class Request(
        val token: Long,
        val uri: Uri,
    )

    private data class Execution(
        val token: Long,
        val job: Job,
    )

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
                startExecution = { uri, token ->
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, ModelImportService::class.java)
                            .setData(uri)
                            .putExtra(ModelImportService.EXTRA_REQUEST_TOKEN, token)
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
