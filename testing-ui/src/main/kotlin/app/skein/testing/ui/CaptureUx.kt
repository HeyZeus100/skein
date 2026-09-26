// skein-xtov.23.15 (UT-1) — `captureUx` moved unchanged from the seven
// per-module copies of `UxScreenshots.kt`. `skeinComposeRule()` is new: the
// one place that hosts Roborazzi's `RoborazziActivity`
// (docs/ux/UX_TEST_PLAN.md §2.2), so a module needs no per-module debug
// `ComponentActivity` manifest entry (`docs/TESTING.md`) to host a screenshot
// test.
package app.skein.testing.ui

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziActivity
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.registerRoborazziActivityToRobolectricIfNeeded

/**
 * A Compose test rule hosted by Roborazzi's `RoborazziActivity`
 * (`Theme.Translucent.NoTitleBar.Fullscreen`, no action bar), registering it
 * with Robolectric's package manager first so modules without their own
 * debug `ComponentActivity` manifest entry still work. Screens that need a
 * `FragmentActivity` host (the unlock/setup screens) keep declaring their
 * own activity and rule instead.
 */
@OptIn(ExperimentalRoborazziApi::class)
fun skeinComposeRule(): AndroidComposeTestRule<ActivityScenarioRule<RoborazziActivity>, RoborazziActivity> {
    registerRoborazziActivityToRobolectricIfNeeded()
    return createAndroidComposeRule<RoborazziActivity>()
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
