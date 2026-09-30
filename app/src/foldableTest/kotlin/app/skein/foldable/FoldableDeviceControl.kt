package app.skein.foldable

import android.app.Activity
import android.app.UiAutomation
import android.content.res.Configuration
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Guarded host console posture requests and UiAutomation rotation. No target networking. */
internal class FoldableDeviceControl(
    private val observeWindow: () -> ActivityWindowGeometry,
) {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private var changedDevice = false
    private var consoleHealthy = true
    private var lastWindow: ActivityWindowGeometry? = null
    private val stateDescription =
        "DeviceState\\{identifier=(\\d+), name='([^']+)', app_accessible=(?:true|false), " +
            "cancel_when_requester_not_on_top=(?:true|false)\\}"
    private val committedState = Regex("Committed state: $stateDescription")

    fun requireEmulator() {
        check(InstrumentationRegistry.getArguments().getString("skein.foldable.ci") == "true") {
            "Device controls require the guarded disposable CI lane"
        }
        check(shell("getprop ro.kernel.qemu").trim() == "1") { "Refusing non-emulator device controls" }
        check(shell("getprop ro.boot.qemu.avd_name").trim() == "skein_foldable_gate") {
            "Refusing unexpected emulator AVD"
        }
    }

    fun requireTransportReady() {
        requireEmulator()
        if (transportReady) return
        console("ready")
        transportReady = true
    }

    fun closed() = requestState("CLOSED") { it.configuration.screenWidthDp < 600 && it.widthDp < 600 }

    fun flat() = requestState("OPENED") { it.configuration.screenWidthDp >= 600 && it.widthDp >= 600 }

    fun portrait() = rotate(UiAutomation.ROTATION_FREEZE_0, Configuration.ORIENTATION_PORTRAIT)

    fun landscape() = rotate(UiAutomation.ROTATION_FREEZE_90, Configuration.ORIENTATION_LANDSCAPE)

    fun reset() {
        if (!changedDevice) return
        try {
            requireEmulator()
            if (consoleHealthy) flat()
            check(consoleHealthy) { "Console transport failed; host owns bounded cleanup" }
            check(committedState.matches(shell("cmd device_state state").trim())) {
                "Unexpected device-state override after console cleanup"
            }
        } finally {
            requireEmulator()
            check(automation.setRotation(UiAutomation.ROTATION_UNFREEZE)) { "Rotation unfreeze failed" }
        }
    }

    private fun requestState(
        name: String,
        geometry: (ActivityWindowGeometry) -> Boolean,
    ) {
        requireEmulator()
        val catalog = shell("cmd device_state print-states")
        File(instrumentation.targetContext.filesDir, "foldable-device-states.txt").appendText(catalog + "\n")
        // Android 15 DeviceState.toString(). Reject drift or partial output; never guess numeric IDs.
        val lines =
            catalog
                .lineSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toList()
        check(lines.firstOrNull() == "Supported states: [" && lines.lastOrNull() == "]") {
            "Malformed device-state catalog: $catalog"
        }
        val statePattern = Regex("$stateDescription,")
        val states =
            lines.drop(1).dropLast(1).map {
                statePattern.matchEntire(it) ?: error("Malformed device state: $it")
            }
        val identifiers = states.map { it.groupValues[1].toInt() }
        check(identifiers.distinct().size == identifiers.size) { "Device state IDs are not unique: $catalog" }
        val closed = states.filter { it.groupValues[2] == "CLOSED" }
        val opened = states.filter { it.groupValues[2] == "OPENED" }
        check(closed.size == 1 && opened.size == 1) { "Missing or ambiguous CLOSED/OPENED states: $catalog" }
        val selected = if (name == "CLOSED") closed.single() else opened.single()
        val identifier = selected.groupValues[1].toInt()
        beginChange()
        console(if (name == "CLOSED") "fold" else "unfold")
        await("committed $name state $identifier") { shell("cmd device_state print-state").trim() == "$identifier" }
        await("$name Activity window geometry") { geometry(currentWindow()) }
    }

    private fun console(action: String) {
        requireEmulator()
        check(consoleHealthy) { "Failed console requests cannot be retried" }
        check(action == "ready" || action == "fold" || action == "unfold")
        check(action == "ready" || transportReady) { "Transport readiness must precede posture" }
        val runId = InstrumentationRegistry.getArguments().getString("skein.foldable.runId")
        check(runId != null && Regex("[0-9a-f]{32}").matches(runId)) { "Missing fresh host run ID" }
        val sequence = if (action == "ready") 0 else requestSequence.incrementAndGet()
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val request =
            JSONObject()
                .put("run_id", runId)
                .put("sequence", sequence)
                .put("nonce", nonce)
                .put("action", action)
        val directory = instrumentation.targetContext.filesDir
        val requestFile = AtomicFile(File(directory, "foldable-console-request.json"))
        try {
            val output = requestFile.startWrite()
            try {
                output.write(request.toString().toByteArray(Charsets.UTF_8))
                requestFile.finishWrite(output)
            } catch (error: Exception) {
                requestFile.failWrite(output)
                throw error
            }
            val ackFile = File(directory, "foldable-console-ack.json")
            await("console $action acknowledgment", timeoutMillis = 90_000) {
                if (!ackFile.exists()) return@await false
                check(ackFile.length() in 1..1024) { "Invalid console acknowledgment size" }
                val ack = JSONObject(ackFile.readText())
                check(ack.keys().asSequence().toSet() == setOf("run_id", "sequence", "nonce", "action", "status")) {
                    "Invalid console acknowledgment shape"
                }
                check(ack.getString("run_id") == runId) { "Stale console acknowledgment run" }
                if (ack.getInt("sequence") < sequence) return@await false
                check(
                    ack.get("sequence") == sequence &&
                        ack.getString("nonce") == nonce &&
                        ack.getString("action") == action,
                ) { "Mismatched console acknowledgment" }
                check(ack.getString("status") == "ok") { "Host console request failed: $ack" }
                if (action == "ready") {
                    // Written only after the instrumentation consumer verified the nonce-bound ACK.
                    File(directory, "foldable-console-ready.json").writeText(ack.toString())
                }
                true
            }
        } catch (error: Exception) {
            consoleHealthy = false
            throw error
        }
    }

    private fun rotate(
        rotation: Int,
        orientation: Int,
    ) {
        beginChange()
        check(automation.setRotation(rotation)) { "Rotation request failed: $rotation" }
        await("orientation $orientation") {
            val window = currentWindow()
            window.configuration.orientation == orientation &&
                if (orientation == Configuration.ORIENTATION_PORTRAIT) {
                    window.bounds.height() > window.bounds.width()
                } else {
                    window.bounds.width() > window.bounds.height()
                }
        }
    }

    private fun currentWindow(): ActivityWindowGeometry =
        observeWindow().also {
            check(it.configuration.densityDpi > 0 && it.bounds.width() > 0 && it.bounds.height() > 0) {
                "Invalid Activity window geometry: $it"
            }
            lastWindow = it
        }

    private fun beginChange() {
        requireEmulator()
        if (!changedDevice) {
            val current = shell("cmd device_state state").trim()
            check(committedState.matches(current)) {
                "Refusing to replace an unknown or pre-existing device-state override: $current"
            }
            changedDevice = true
        }
    }

    private fun await(
        description: String,
        timeoutMillis: Long = 15_000,
        ready: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        do {
            instrumentation.waitForIdleSync()
            if (ready()) return
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        error(
            "Timed out waiting for $description; activityWindow=$lastWindow; " +
                "nonUiTargetConfiguration=${instrumentation.targetContext.resources.configuration}",
        )
    }

    private fun shell(command: String): String {
        val descriptor = automation.executeShellCommand(command)
        val executor = Executors.newSingleThreadExecutor()
        val output =
            executor.submit<String> {
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
            }
        try {
            return output.get(5, TimeUnit.SECONDS)
        } finally {
            descriptor.close()
            output.cancel(true)
            executor.shutdownNow()
        }
    }

    private companion object {
        val requestSequence = AtomicInteger()
        var transportReady = false
    }
}

/** Read only on the Activity thread; non-UI targetContext resources are diagnostic, never the gate. */
internal data class ActivityWindowGeometry(
    val configuration: Configuration,
    val bounds: Rect,
    val activityIdentity: Int,
) {
    val widthDp: Float get() = bounds.width() * 160f / configuration.densityDpi
    val heightDp: Float get() = bounds.height() * 160f / configuration.densityDpi

    companion object {
        fun capture(activity: Activity): ActivityWindowGeometry =
            ActivityWindowGeometry(
                Configuration(activity.resources.configuration),
                Rect(activity.windowManager.currentWindowMetrics.bounds),
                System.identityHashCode(activity),
            )
    }
}
