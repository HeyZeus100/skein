// skein-xtov.23.15 (UT-1) — `captureUx` moved unchanged from the seven
// per-module copies of `UxScreenshots.kt`. `skeinComposeRule()` is new: the
// one place that hosts Roborazzi's `RoborazziActivity`
// (docs/ux/UX_TEST_PLAN.md §2.2), so a module needs no per-module debug
// `ComponentActivity` manifest entry (`docs/TESTING.md`) to host a screenshot
// test.
// skein-xtov.23.23 (UT-3b) — `captureUx` now passes an explicit
// `RoborazziOptions` (docs/ux/UX_TEST_PLAN.md §4.3's measurement, this
// bead's own report): a per-pixel colour-distance tolerance so a
// macOS-recorded golden verifies on `ubuntu-latest`'s different
// `nativeruntime-dist-compat` renderer, plus a per-device `compare`
// output directory (each module's `compare.outputDir` — `build/outputs/
// roborazzi` — was flat before this fix: a parameterised test's
// `_actual.png`/`_compare.png` for one state name overwrote itself once
// per device, so only the last device processed survived on disk; see
// this bead's report and `ux-baselines/README.md` "CI").
package app.skein.testing.ui

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziActivity
import com.github.takahirom.roborazzi.RoborazziOptions
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
 * The per-pixel colour-distance tolerance `verify`/`compare` allow between a
 * golden (recorded on macOS arm64 only, docs/ux/UX_TEST_PLAN.md §4.2) and the
 * image a run just captured, on any host (skein-xtov.23.23, UT-3b). Units
 * match `com.dropbox.differ.Color.distance` (the metric
 * `SimpleImageComparator` uses): each of a pixel's R/G/B/A channels
 * normalised to `0f..1f`, then Euclidean distance over the four channels —
 * so e.g. a single channel differing by 5 of 255 levels is `5/255 ≈ 0.0196`.
 *
 * Measured (this bead's report; GitHub run 36277942699, the first
 * `ubuntu-latest` `verifyRoborazziDebug` against these goldens): every
 * differing pixel across the three failing modules'
 * `:feature:models`/`:feature:graph`/`:feature:shell` images was
 * anti-aliasing on graph canvas lines or a rounding difference across a
 * translucent fill (legend surface, a disabled button container, the drawer
 * scrim) — never a layout change. Max observed distance 0.01176, p99.9
 * 0.00679. [UX_MAX_COLOR_DISTANCE] sits well above that (and Roborazzi's own
 * library default of 0.007, which is why the exact-comparison default still
 * failed) while staying far below a real edit: a single 1px line or a
 * colour change of >= 10/255 on any run of pixels already exceeds it several
 * times over (this bead's perturbation proof).
 *
 * `internal` (not `private`): [CaptureUxRoborazziOptionsTest] pins the value
 * and the [uxRoborazziOptions] wiring so a future edit here can't silently
 * loosen or re-flatten either without a test noticing.
 */
internal const val UX_MAX_COLOR_DISTANCE = 0.02f

/**
 * [RoborazziOptions] for one [captureUx] call: [UX_MAX_COLOR_DISTANCE] as the
 * comparator (`hShift`/`vShift = 0` — no pixel-shift tolerance, only colour),
 * plus a `compareOptions.outputDirectoryPath` nested one level deeper than
 * each module's `roborazzi { compare { outputDir } }` (`build/outputs/
 * roborazzi`, `RoborazziOptions.CompareOptions`'s own default — read from the
 * `roborazzi.compare.output.dir` system property the Gradle plugin sets) by
 * this capture's device.
 *
 * Without this, every device's `compare`/`verify` run for the same state id
 * writes `<stateId>__<theme>__fsNNN_actual.png` to that one flat directory:
 * Roborazzi's own subdirectory-mirroring only reconstructs a golden's
 * subdirectory when the naming strategy is one of its built-in
 * directory-encoding ones (`ux-baselines/README.md`'s `dumpUiTree` caveat
 * describes the same mechanism) — ours is the explicit relative path
 * `filePathStrategy=relativePathFromRoborazziContextOutputDirectory` needs
 * (bead UT-2), so it never fires, and a parameterised test's five devices'
 * writes for one state+theme overwrite each other, leaving only the last
 * device processed on disk. Setting the per-device directory here directly
 * (rather than relying on that mirroring) sidesteps it: every device gets
 * its own `<device>/<stateId>__<theme>__fsNNN_{actual,compare}.png`, mirroring
 * the golden layout (`ux-baselines/feature-<module>/<device>/…`) instead of
 * colliding.
 */
internal fun uxRoborazziOptions(device: SkeinDevice): RoborazziOptions {
    val defaultCompareOptions = RoborazziOptions.CompareOptions()
    return RoborazziOptions(
        compareOptions =
            defaultCompareOptions.copy(
                outputDirectoryPath = "${defaultCompareOptions.outputDirectoryPath}/${device.dir}",
                imageComparator =
                    SimpleImageComparator(
                        maxDistance = UX_MAX_COLOR_DISTANCE,
                        hShift = 0,
                        vShift = 0,
                    ),
            ),
    )
}

/**
 * Captures this node to
 * `<device>/<stateId>__<theme>__fs<NNN>.png` (docs/ux/UX_TEST_PLAN.md §3.5), a
 * path *relative to the Roborazzi output directory* — resolved by each
 * module's `roborazzi { outputDir.set(...) }` (bead UT-2) plus
 * `roborazzi.record.filePathStrategy=relativePathFromRoborazziContextOutputDirectory`
 * in the root `gradle.properties`, which is what makes a relative
 * `filePath` here resolve against that directory instead of the JVM's
 * working directory (`FileWithRecordFilePathStrategy`, roborazzi-core 1.75.0).
 * A no-op unless Gradle runs with `-Proborazzi.test.record=true` (or
 * `verify`/`compare`); plain `testDebugUnitTest` still composes the screen.
 *
 * `compare`/`verify` additionally use [uxRoborazziOptions]: the cross-platform
 * colour tolerance and the per-device compare output directory (skein-xtov.23.23,
 * UT-3b) — the one place every module's screenshot tests share it.
 */
fun SemanticsNodeInteraction.captureUx(
    spec: UxSpec,
    stateId: String,
) {
    captureRoboImage(
        "${spec.device.dir}/$stateId${spec.nameSuffix}.png",
        uxRoborazziOptions(spec.device),
    )
}
