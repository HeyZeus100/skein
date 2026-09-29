package app.skein.feature.shell.layout

import androidx.compose.material3.adaptive.HingeInfo
import androidx.compose.material3.adaptive.Posture
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.computeWindowSizeClass
import org.junit.Assert.assertEquals
import org.junit.Test

class SkeinHingeGeometryTest {
    @Test
    fun `non-separating crease leaves every surface unchanged`() {
        val decision = decision(vertical = false, separating = false)
        for (surface in SecondarySurface.entries) {
            assertEquals(DpRect(0.dp, 0.dp, 1000.dp, 1000.dp), decision.surfaceBounds(surface))
        }
    }

    @Test
    fun `tabletop preserves the actual band between top reading and bottom confirmation`() {
        val decision = decision(vertical = false, separating = true)
        assertEquals(DpRect(0.dp, 0.dp, 1000.dp, 490.dp), decision.surfaceBounds(SecondarySurface.COMMAND_PALETTE))
        assertEquals(DpRect(0.dp, 510.dp, 1000.dp, 1000.dp), decision.surfaceBounds(SecondarySurface.CONFIRM_DIALOG))
    }

    @Test
    fun `zero-width book crease chooses the end page in both layout directions`() {
        val decision = decision(vertical = true, separating = true, start = 500f, end = 500f)
        assertEquals(DpRect(500.dp, 0.dp, 1000.dp, 1000.dp), decision.surfaceBounds(SecondarySurface.SETTINGS_CATEGORY))
        assertEquals(
            DpRect(0.dp, 0.dp, 500.dp, 1000.dp),
            decision.surfaceBounds(SecondarySurface.SETTINGS_CATEGORY, LayoutDirection.Rtl),
        )
    }

    @Test
    fun `reversed and out of window band coordinates are bounded deterministically`() {
        val decision = decision(vertical = false, separating = true, start = 1200f, end = -20f)
        assertEquals(DpRect(0.dp, 0.dp, 1000.dp, 0.dp), decision.surfaceBounds(SecondarySurface.RENAME_DIALOG))
        assertEquals(DpRect(0.dp, 1000.dp, 1000.dp, 1000.dp), decision.surfaceBounds(SecondarySurface.CONFIRM_DIALOG))
    }
}

internal fun decision(
    vertical: Boolean,
    separating: Boolean,
    start: Float = 490f,
    end: Float = 510f,
): SkeinLayoutDecision =
    skeinWindowLayout(
        WindowAdaptiveInfo(
            WindowSizeClass.BREAKPOINTS_V2.computeWindowSizeClass(1000, 1000),
            Posture(
                isTabletop = !vertical && separating,
                hingeList =
                    listOf(
                        HingeInfo(
                            bounds = if (vertical) Rect(start, 0f, end, 1000f) else Rect(0f, start, 1000f, end),
                            isFlat = !separating,
                            isVertical = vertical,
                            isSeparating = separating,
                            isOccluding = start != end,
                        ),
                    ),
            ),
        ),
        DpSize(1000.dp, 1000.dp),
        Density(1f),
    )
