// skein-xtov.9 — shared plumbing for the Roborazzi UX baselines
// (docs/ux/research/ROBORAZZI_SPIKE.md). Test source sets cannot share code
// without a new module, so every feature module that captures screenshots
// carries an identical copy of this file in its own `…screenshots` package;
// change every copy together.
package app.skein.feature.shell.screenshots

import androidx.compose.ui.test.SemanticsNodeInteraction
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.registerRoborazziActivityToRobolectricIfNeeded
import org.junit.Assume.assumeTrue
import org.junit.rules.ExternalResource
import org.robolectric.RuntimeEnvironment
import kotlin.math.roundToInt

/**
 * The device sizes UX captures are taken at (dp = px / (dpi / 160)).
 *
 * The Fold inner display is 2076x2152 px, stock density 390; the owner's
 * device runs a forced density override of 330, so [FOLD_INNER] and
 * [FOLD_LANDSCAPE] use 330 dpi (1006x1043 dp) — what the owner actually
 * sees. [FOLD_INNER_STOCK] is the stock 390 dpi profile (Android Studio's
 * `pixel_9_pro_fold` = Roborazzi's `RobolectricDeviceQualifiers.Pixel9ProFold`).
 * The cover display is 1080x2424 px; its dp size under the override is
 * unmeasured, so [FOLD_OUTER] provisionally assumes 420 dpi (411x923 dp).
 */
enum class UxDevice(
    val dir: String,
    val qualifiers: String,
) {
    PHONE("phone", "w360dp-h800dp-port-xhdpi"),
    FOLD_OUTER("fold-outer", "w411dp-h923dp-port-420dpi"),
    FOLD_INNER("fold-inner", "w1006dp-h1043dp-port-330dpi"),
    FOLD_LANDSCAPE("fold-landscape", "w1043dp-h1006dp-land-330dpi"),
    FOLD_INNER_STOCK("fold-inner-stock", "w852dp-h883dp-port-390dpi"),
}

/** One capture configuration: a device, light/dark, and a font scale. */
data class UxSpec(
    val device: UxDevice,
    val dark: Boolean,
    val fontScale: Float = 1f,
) {
    /** File-name suffix, e.g. `-font150-dark`. */
    val suffix: String =
        (if (fontScale != 1f) "-font${(fontScale * 100).roundToInt()}" else "") + (if (dark) "-dark" else "")

    /** Phone and Fold cover: the single-pane, "closed Fold" postures. */
    val isFolded: Boolean get() = device == UxDevice.PHONE || device == UxDevice.FOLD_OUTER

    /** One of the four main devices at font scale 1. */
    val isStandard: Boolean get() = fontScale == 1f && device != UxDevice.FOLD_INNER_STOCK

    override fun toString(): String = device.dir + suffix
}

/** Skips this parameterisation unless it is one of the four main devices at font scale 1. */
fun UxSpec.assumeStandard() = assumeTrue(isStandard)

/** The four main devices, light and dark, plus [extra], as `ParameterizedRobolectricTestRunner` parameters. */
fun uxSpecs(vararg extra: UxSpec): List<Array<Any>> =
    (
        UxDevice.entries
            .filter { it != UxDevice.FOLD_INNER_STOCK }
            .flatMap { listOf(UxSpec(it, dark = false), UxSpec(it, dark = true)) } + extra
    ).map { arrayOf<Any>(it) }

/** Fold cover at font scale 1.5, light and dark. */
val UX_FONT_150: Array<UxSpec> =
    arrayOf(UxSpec(UxDevice.FOLD_OUTER, dark = false, 1.5f), UxSpec(UxDevice.FOLD_OUTER, dark = true, 1.5f))

/**
 * Applies [spec]'s Robolectric qualifiers, night mode and font scale
 * *before* the Compose rule launches its activity (give this rule a lower
 * `order`), so the window, `LocalConfiguration` and `WindowSizeClass` all
 * see the target device from the first frame.
 */
@OptIn(ExperimentalRoborazziApi::class)
class UxDeviceRule(
    private val spec: UxSpec,
) : ExternalResource() {
    override fun before() {
        registerRoborazziActivityToRobolectricIfNeeded()
        RuntimeEnvironment.setQualifiers(spec.device.qualifiers)
        RuntimeEnvironment.setQualifiers(if (spec.dark) "+night" else "+notnight")
        RuntimeEnvironment.setFontScale(spec.fontScale)
    }
}

/**
 * Captures this node to `build/outputs/roborazzi/<device>/<screen><suffix>.png`.
 * A no-op unless Gradle runs with `-Proborazzi.test.record=true` (or
 * `verify`/`compare`); plain `testDebugUnitTest` still composes the screen.
 */
fun SemanticsNodeInteraction.captureUx(
    spec: UxSpec,
    screen: String,
) {
    captureRoboImage("build/outputs/roborazzi/${spec.device.dir}/$screen${spec.suffix}.png")
}
