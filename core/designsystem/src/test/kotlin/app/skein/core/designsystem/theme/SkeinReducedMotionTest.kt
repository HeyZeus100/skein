package app.skein.core.designsystem.theme

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * skein-xtov.23.5 (DS5, spec §8.3): [animatorDurationScale] resolves
 * [LocalReducedMotion] from `Settings.Global.ANIMATOR_DURATION_SCALE` — the
 * setting both the "Animator duration scale" developer option and
 * accessibility's "Remove animations" toggle write to. Faked here via
 * Robolectric's `Settings.Global` shadow; `rememberSkeinReducedMotion`'s own
 * `ContentObserver` wiring is Compose-host plumbing around this resolver,
 * not separately re-tested.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkeinReducedMotionTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `default scale (no setting written) is not reduced motion`() {
        assertEquals(1f, animatorDurationScale(context))
    }

    @Test
    fun `scale zero — Remove animations — resolves to reduced motion`() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)

        assertEquals(0f, animatorDurationScale(context))
        assertTrue(animatorDurationScale(context) == 0f)
    }

    @Test
    fun `a non-zero scale is not reduced motion`() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0.5f)

        assertFalse(animatorDurationScale(context) == 0f)
    }
}
