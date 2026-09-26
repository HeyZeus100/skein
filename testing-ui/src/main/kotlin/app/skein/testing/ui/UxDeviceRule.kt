// skein-xtov.23.15 (UT-1) — moved from the seven per-module copies of
// `UxScreenshots.kt` (docs/ux/research/ROBORAZZI_SPIKE.md); logic unchanged.
package app.skein.testing.ui

import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.registerRoborazziActivityToRobolectricIfNeeded
import org.junit.rules.ExternalResource
import org.robolectric.RuntimeEnvironment

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
