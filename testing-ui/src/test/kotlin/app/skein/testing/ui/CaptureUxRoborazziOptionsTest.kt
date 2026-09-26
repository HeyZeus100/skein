// skein-xtov.23.23 (UT-3b) — pins `uxRoborazziOptions()`'s two effects so a
// future edit can't silently regress either without a test noticing: the
// cross-platform colour tolerance (`UX_MAX_COLOR_DISTANCE`, this bead's
// report) and the per-device `compare` output directory (the flat-output
// collision this bead fixed — see `ux-baselines/README.md` "CI").
package app.skein.testing.ui

import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.RoborazziOptions
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// `RoborazziOptions`'s own constructor calls into Robolectric's sandbox
// (`canScreenshot()`), so this needs the Robolectric runner like every other
// self-test in this module that touches Roborazzi/Compose types.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureUxRoborazziOptionsTest {
    @Test
    fun usesTheSharedColorDistanceTolerance() {
        val comparator = uxRoborazziOptions(SkeinDevice.PHONE).compareOptions.imageComparator
        check(comparator is SimpleImageComparator)
        assertThat(comparator.maxDistance).isEqualTo(UX_MAX_COLOR_DISTANCE)
        // No pixel-shift tolerance: only colour distance, never position.
        assertThat(comparator.hShift).isEqualTo(0)
        assertThat(comparator.vShift).isEqualTo(0)
    }

    @Test
    fun nestsTheCompareOutputDirectoryOneLevelPerDevice() {
        val defaultOutputDirectoryPath = RoborazziOptions.CompareOptions().outputDirectoryPath
        for (device in SkeinDevice.entries) {
            val options = uxRoborazziOptions(device)
            assertThat(options.compareOptions.outputDirectoryPath)
                .isEqualTo("$defaultOutputDirectoryPath/${device.dir}")
        }
    }

    @Test
    fun everyDeviceGetsItsOwnCompareOutputDirectory() {
        val directories = SkeinDevice.entries.map { uxRoborazziOptions(it).compareOptions.outputDirectoryPath }
        assertThat(directories.toSet()).hasSize(SkeinDevice.entries.size)
    }
}
