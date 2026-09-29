package app.skein.foldable

import android.app.UiAutomation
import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** API 35 framework state overrides, not emulator hinge-sensor actuation. No target networking. */
internal class FoldableDeviceControl {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private var changedDevice = false
    private val stateDescription =
        "DeviceState\\{identifier=(\\d+), name='([^']+)', app_accessible=(?:true|false), " +
            "cancel_when_requester_not_on_top=(?:true|false)\\}"
    private val committedState = Regex("Committed state: $stateDescription")

    fun requireEmulator() {
        check(InstrumentationRegistry.getArguments().getString("skein.foldable.ci") == "true") {
            "Device controls require the guarded disposable CI lane"
        }
        check(shell("getprop ro.kernel.qemu").trim() == "1") { "Refusing non-emulator device controls" }
    }

    fun closed() = requestState("CLOSED") { it.screenWidthDp < 600 }

    fun flat() = requestState("OPENED") { it.screenWidthDp >= 600 }

    fun portrait() = rotate(UiAutomation.ROTATION_FREEZE_0, Configuration.ORIENTATION_PORTRAIT)

    fun landscape() = rotate(UiAutomation.ROTATION_FREEZE_90, Configuration.ORIENTATION_LANDSCAPE)

    fun reset() {
        if (!changedDevice) return
        try {
            requireEmulator()
            shell("cmd device_state state reset")
            await("device-state override cleanup") { committedState.matches(shell("cmd device_state state").trim()) }
        } finally {
            requireEmulator()
            check(automation.setRotation(UiAutomation.ROTATION_UNFREEZE)) { "Rotation unfreeze failed" }
        }
    }

    private fun requestState(
        name: String,
        geometry: (Configuration) -> Boolean,
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
        shell("cmd device_state state $identifier")
        await("committed $name state $identifier") { shell("cmd device_state print-state").trim() == "$identifier" }
        await("$name window geometry") { geometry(instrumentation.targetContext.resources.configuration) }
    }

    private fun rotate(
        rotation: Int,
        orientation: Int,
    ) {
        beginChange()
        check(automation.setRotation(rotation)) { "Rotation request failed: $rotation" }
        await("orientation $orientation") {
            instrumentation.targetContext.resources.configuration.orientation == orientation
        }
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
        ready: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        do {
            instrumentation.waitForIdleSync()
            if (ready()) return
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        error(
            "Timed out waiting for $description; configuration=${instrumentation.targetContext.resources.configuration}",
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
}
