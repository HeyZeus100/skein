package app.skein.core.designsystem.components

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class SkeinWindowPartitionTest {
    @Test
    fun `IME clips bottom partition without moving it above hinge`() {
        assertEquals(
            DpRect(0.dp, 510.dp, 1000.dp, 650.dp),
            intersectWindowPartition(DpRect(0.dp, 510.dp, 1000.dp, 1000.dp), DpRect(0.dp, 24.dp, 1000.dp, 650.dp)),
        )
    }

    @Test
    fun `fully covered partition is empty rather than inverted`() {
        assertEquals(
            DpRect(0.dp, 510.dp, 1000.dp, 510.dp),
            intersectWindowPartition(DpRect(0.dp, 510.dp, 1000.dp, 1000.dp), DpRect(0.dp, 24.dp, 1000.dp, 400.dp)),
        )
    }

    @Test
    fun `menu moves up from anchor to fit before tabletop crease`() {
        assertEquals(
            DpOffset(0.dp, (-212).dp),
            menuPartitionOffset(
                DpRect(20.dp, 430.dp, 80.dp, 478.dp),
                DpRect(0.dp, 48.dp, 1000.dp, 490.dp),
                DpSize(200.dp, 224.dp),
                LayoutDirection.Ltr,
            ),
        )
    }

    @Test
    fun `RTL menu aligns to launcher end within book start page`() {
        assertEquals(
            DpOffset(24.dp, 0.dp),
            menuPartitionOffset(
                DpRect(460.dp, 60.dp, 514.dp, 108.dp),
                DpRect(0.dp, 48.dp, 490.dp, 952.dp),
                DpSize(200.dp, 224.dp),
                LayoutDirection.Rtl,
            ),
        )
    }
}
