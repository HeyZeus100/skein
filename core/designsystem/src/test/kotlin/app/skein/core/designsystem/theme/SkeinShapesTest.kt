package app.skein.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * skein-xtov.23.5 (DS5): `docs/ux/DESIGN_SYSTEM.md` §5.1's radius scale and
 * the `Shapes` it's mapped onto. [SkeinShapes] only sets the five slots
 * `androidx.compose.material3.Shapes`'s public constructor exposes — see
 * that file's doc for why the other three (`largeIncreased`,
 * `extraLargeIncreased`, `extraExtraLarge`) are Material's own defaults.
 */
class SkeinShapesTest {
    @Test
    fun `radius scale matches §5-1`() {
        assertEquals(0.dp, SkeinRadius.radiusNone)
        assertEquals(4.dp, SkeinRadius.radiusXs)
        assertEquals(8.dp, SkeinRadius.radiusSm)
        assertEquals(12.dp, SkeinRadius.radiusMd)
        assertEquals(16.dp, SkeinRadius.radiusLg)
        assertEquals(20.dp, SkeinRadius.radiusXl)
        assertEquals(4.dp, SkeinRadius.userBubbleAuthorCorner)
    }

    @Test
    fun `radiusFull is a 50 percent pill`() {
        assertEquals(RoundedCornerShape(percent = 50), SkeinRadius.radiusFull)
    }

    @Test
    fun `the five public Material slots use the radius scale`() {
        assertEquals(RoundedCornerShape(SkeinRadius.radiusXs), SkeinShapes.extraSmall)
        assertEquals(RoundedCornerShape(SkeinRadius.radiusSm), SkeinShapes.small)
        assertEquals(RoundedCornerShape(SkeinRadius.radiusMd), SkeinShapes.medium)
        assertEquals(RoundedCornerShape(SkeinRadius.radiusLg), SkeinShapes.large)
        assertEquals(RoundedCornerShape(SkeinRadius.radiusXl), SkeinShapes.extraLarge)
    }

    @Test
    fun `the user bubble is 16 dp with a 4 dp author-side corner`() {
        assertEquals(
            RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 4.dp,
                bottomEnd = 16.dp,
                bottomStart = 16.dp,
            ),
            SkeinUserBubbleShape,
        )
    }
}
