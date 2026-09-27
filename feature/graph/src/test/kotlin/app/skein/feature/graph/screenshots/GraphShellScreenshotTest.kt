// skein-xtov.24.9 (AL-09b): goldens for the Graph destination in the
// NavDisplay shell on the owner's Fold — outer (524 dp: drawer, one pane) and
// inner (1007 dp: rail), light and dark. Recorded with `tools/ux/shots
// record graph --tests "app.skein.feature.graph.screenshots.GraphShellScreenshotTest"`.
package app.skein.feature.graph.screenshots

import androidx.compose.ui.test.onRoot
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.core.navigation.Destination
import app.skein.feature.graph.entries.GraphHost
import app.skein.feature.shell.host.SkeinShellState
import app.skein.testing.FakeClock
import app.skein.testing.InMemoryIndexStore
import app.skein.testing.InMemoryVaultRepository
import app.skein.testing.fakeVault
import app.skein.testing.ui.SkeinDevice
import app.skein.testing.ui.UxDeviceRule
import app.skein.testing.ui.UxSpec
import app.skein.testing.ui.captureUx
import app.skein.testing.ui.skeinComposeRule
import app.skein.testing.ui.uxSpecs
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val MINUTE = 60_000L

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GraphShellScreenshotTest(
    private val spec: UxSpec,
) {
    @get:Rule(order = 0)
    val deviceRule = UxDeviceRule(spec)

    @get:Rule(order = 1)
    val composeRule = skeinComposeRule()

    private lateinit var shell: SkeinShellState

    private fun assumeFold() {
        assumeTrue(spec.fontScale == 1f)
        assumeTrue(spec.device == SkeinDevice.FOLD_OUTER_524 || spec.device == SkeinDevice.FOLD_INNER_1007)
    }

    /** IA §3.2: an empty vault shows a purposeful empty state, never a blank canvas. */
    @Test
    fun empty() {
        assumeFold()
        val vault = InMemoryVaultRepository()
        composeRule.setContent {
            SkeinTheme { GraphHost(vault, InMemoryIndexStore(), size = null) { shell = it } }
        }
        composeRule.runOnIdle { shell.navigate { switchTo(it, Destination.GRAPH) } }
        composeRule.waitForIdle()
        composeRule.onRoot().captureUx(spec, "graph-empty")
    }

    /** The canvas, centred on the most recently updated note (no personal data: synthetic fixture titles). */
    @Test
    fun centred() {
        assumeFold()
        // Distinct, ordered timestamps (§8.3's "most recently updated" is real-clock
        // ordering): two notes created in the same wall-clock millisecond would make
        // `observeTimeline`'s tie-break — and so which one centres the canvas — undefined.
        val clock = FakeClock(1_790_000_000_000L)
        val vault =
            fakeVault(clock = { clock.advanceBy(MINUTE) }) {
                note("Mycology research", "Straw colonised in 14 days.")
                note("Fold launch plan", "Targets M2 for the ask path.")
            }
        composeRule.setContent {
            SkeinTheme { GraphHost(vault, InMemoryIndexStore(), size = null) { shell = it } }
        }
        composeRule.runOnIdle { shell.navigate { switchTo(it, Destination.GRAPH) } }
        composeRule.waitForIdle()
        composeRule.onRoot().captureUx(spec, "graph-centred")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun parameters(): List<Array<Any>> = uxSpecs()
    }
}
