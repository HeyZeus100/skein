// skein-1uw (E4.I4) — the bind seam between `LlamaCppEngine` and the
// `:inference` isolated process.
//
// WHY A SEAM AT ALL
// ============================================================================
//
// `LlamaCppEngine` is the app-side half of a security boundary: everything it
// does — error mapping, the status machine, the per-message spill, the death
// handling, the session epoch on every request — is logic that must be tested
// exhaustively, and none of it is about *Android service binding*. Binding is
// the one part that cannot run on the JVM: `Context.bindService` needs a real
// `ActivityManager`, and a local `Binder` never delivers `linkToDeath` (the
// platform documents `linkToDeath` as a no-op for a binder in the same
// process), so "the service died" would be untestable off-device.
//
// Splitting the bind out behind this interface is what lets the JVM suite in
// `core/inference/src/test/kotlin/app/skein/core/inference/engine/` inject a
// fake `IInferenceService` AND fire the death callback by hand, while the real
// [AndroidServiceConnector] is exercised by the instrumented contract subclass
// (`app/src/androidTest/.../LlamaCppEngineInstrumentedTest`) against the real
// isolated process.
//
// It is one interface with two methods, not a framework: no lifecycle owner,
// no reconnection policy, no queueing. Reconnection is the engine's business
// (plan `E4.I4`: "the next `load` rebinds") and stays there.

package app.skein.core.inference.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import app.skein.core.model.InferenceException
import app.skein.core.model.SkeinLog
import app.skein.ipc.IInferenceService
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Binds the `:inference` isolated service and hands back its AIDL interface.
 *
 * Implementations are single-connection: [connect] either returns the live
 * service or throws, and [disconnect] tears the binding down. The engine calls
 * [connect] lazily (first `load`, `inspect` or `count`) and again after a death,
 * which is what "the next `load` rebinds" means.
 */
public interface ServiceConnector {
    /**
     * Binds the service, suspending until it is connected, and returns it.
     *
     * @param onDeath invoked, at most once per successful [connect], when the
     *   service process goes away — either through `linkToDeath` or through
     *   `ServiceConnection.onServiceDisconnected`. It is called on a binder
     *   thread, so implementations of it must not block.
     * @throws app.skein.core.model.InferenceException.ServiceDied when the
     *   bind cannot be established at all. (A bind that never lands and a bind
     *   that lands and dies are the same thing to a caller: no service.)
     */
    public suspend fun connect(onDeath: () -> Unit): IInferenceService

    /** Unbinds. Idempotent; safe to call without a preceding [connect]. */
    public fun disconnect()
}

/**
 * The production [ServiceConnector]: `bindService` with
 * `BIND_AUTO_CREATE or BIND_IMPORTANT` (plan `E4.I4`).
 *
 * `BIND_AUTO_CREATE` starts the isolated process on demand — nothing else in
 * the app starts it. `BIND_IMPORTANT` raises its oom-adj toward the app's own
 * while the binding is held: the model is hundreds of megabytes of mapped
 * pages, and having the platform reclaim the process mid-answer (and force a
 * full reload on the next turn) is worse for the user than the memory pressure
 * the flag adds. Neither flag grants the process anything: it stays
 * `isolatedProcess=true` with no permissions (`app/src/main/AndroidManifest.xml`).
 *
 * The component is named by string rather than by class literal on purpose:
 * `:core:inference` must not depend on `:inference-service` (the isolated
 * module's own dependency allowlist runs the other way, and `:app` is the only
 * module that sees both). The name matches the `<service>` element in
 * `app/src/main/AndroidManifest.xml`; [SERVICE_CLASS_NAME] is the single place
 * it is written down.
 *
 * @param context any context; [ServiceConnector] holds `applicationContext`
 *   so a bound service never outlives an `Activity` it was bound from.
 */
public class AndroidServiceConnector(
    context: Context,
    private val serviceClassName: String = SERVICE_CLASS_NAME,
) : ServiceConnector {
    private val appContext: Context = context.applicationContext

    private val lock = Any()
    private var connection: ServiceConnection? = null

    override suspend fun connect(onDeath: () -> Unit): IInferenceService {
        val connected = CompletableDeferred<IInferenceService>()
        // `onDeath` must fire at most once even though two platform callbacks
        // can report the same death (linkToDeath and onServiceDisconnected
        // both fire when a bound process is killed).
        val died = AtomicBoolean(false)
        val reportDeath = {
            if (died.compareAndSet(false, true)) onDeath()
        }

        val serviceConnection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName?,
                    binder: IBinder?,
                ) {
                    if (binder == null) {
                        connected.completeExceptionally(InferenceException.ServiceDied())
                        return
                    }
                    val service = IInferenceService.Stub.asInterface(binder)
                    runCatching { binder.linkToDeath({ reportDeath() }, 0) }
                        .onFailure {
                            // The process was already gone between bind and
                            // link. Report it rather than handing back a
                            // service every call would fail on.
                            SkeinLog.w(TAG, "binder died before linkToDeath")
                            reportDeath()
                        }
                    connected.complete(service)
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    SkeinLog.w(TAG, "inference service disconnected")
                    reportDeath()
                    if (!connected.isCompleted) {
                        connected.completeExceptionally(InferenceException.ServiceDied())
                    }
                }

                override fun onBindingDied(name: ComponentName?) {
                    SkeinLog.w(TAG, "inference service binding died")
                    reportDeath()
                    if (!connected.isCompleted) {
                        connected.completeExceptionally(InferenceException.ServiceDied())
                    }
                }

                override fun onNullBinding(name: ComponentName?) {
                    SkeinLog.w(TAG, "inference service returned a null binding")
                    if (!connected.isCompleted) {
                        connected.completeExceptionally(InferenceException.ServiceDied())
                    }
                }
            }

        disconnect()
        val intent = Intent().setComponent(ComponentName(appContext.packageName, serviceClassName))
        val bound =
            runCatching {
                appContext.bindService(intent, serviceConnection, BIND_FLAGS)
            }.getOrDefault(false)
        if (!bound) {
            runCatching { appContext.unbindService(serviceConnection) }
            SkeinLog.w(TAG, "bindService refused for the inference service")
            throw InferenceException.ServiceDied()
        }
        synchronized(lock) { connection = serviceConnection }
        return connected.await()
    }

    override fun disconnect() {
        val current = synchronized(lock) { connection.also { connection = null } } ?: return
        runCatching { appContext.unbindService(current) }
    }

    public companion object {
        /**
         * The `<service android:name>` in `app/src/main/AndroidManifest.xml`.
         * Changing it there means changing it here; `ManifestPolicyTest`
         * (`:app`) is what keeps the declaration itself honest.
         */
        public const val SERVICE_CLASS_NAME: String = "app.skein.inference.service.InferenceService"

        private const val TAG = "LlamaCppEngine"

        private const val BIND_FLAGS: Int = Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
    }
}
