package app.skein

import android.content.res.Configuration
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AL-02 / UX-P0-08 part (Stage H8, skein-xtov.22): unfolding the Fold (cover
 * screen 443 x 994 dp to inner 1006 x 1043 dp) used to recreate
 * `MainActivity`, closing the graph and models overlays and cancelling a
 * model import. With the `configChanges` of `ADAPTIVE_LAYOUT_SPEC.md` §7.5
 * the same instance handles the change. Robolectric's
 * `ActivityController.configurationChange` recreates exactly when the
 * manifest does not declare every changed bit, so a new instance here means
 * the declaration regressed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestSkeinApplication::class, qualifiers = "w443dp-h994dp")
class MainActivityConfigChangeTest {
    @Test
    fun `unfolding keeps the same MainActivity instance`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val folded = controller.get()

        val unfolded =
            Configuration(folded.resources.configuration).apply {
                screenWidthDp = 1006
                screenHeightDp = 1043
                smallestScreenWidthDp = 1006
            }
        controller.configurationChange(unfolded)

        assertSame(folded, controller.get())
        controller.pause().stop().destroy()
    }
}
