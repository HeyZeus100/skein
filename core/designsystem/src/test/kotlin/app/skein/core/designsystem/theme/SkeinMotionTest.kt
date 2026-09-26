package app.skein.core.designsystem.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import org.junit.Assert.assertEquals
import org.junit.Test

/** skein-xtov.23.5 (DS5): durations and eastings exactly `docs/ux/DESIGN_SYSTEM.md` §8.1. */
class SkeinMotionTest {
    @Test
    fun `durations match §8-1`() {
        assertEquals(0, SkeinMotion.durationInstant)
        assertEquals(100, SkeinMotion.durationShort)
        assertEquals(200, SkeinMotion.durationMedium)
        assertEquals(300, SkeinMotion.durationLong)
    }

    @Test
    fun `eastings match §8-1`() {
        assertEquals(CubicBezierEasing(0.2f, 0f, 0f, 1f), SkeinMotion.easingStandard)
        assertEquals(CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f), SkeinMotion.easingEnter)
        assertEquals(CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f), SkeinMotion.easingExit)
        assertEquals(LinearEasing, SkeinMotion.easingLinear)
    }
}
