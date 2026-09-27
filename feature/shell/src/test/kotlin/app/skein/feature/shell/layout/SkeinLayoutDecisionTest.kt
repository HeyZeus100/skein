package app.skein.feature.shell.layout

import androidx.compose.material3.adaptive.HingeInfo
import androidx.compose.material3.adaptive.Posture
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.computeWindowSizeClass
import app.skein.feature.shell.layout.SkeinNavContainer.DRAWER
import app.skein.feature.shell.layout.SkeinNavContainer.EXPANDED_RAIL
import app.skein.feature.shell.layout.SkeinNavContainer.RAIL
import app.skein.feature.shell.layout.SurfacePresentation.ANCHORED_PANEL
import app.skein.feature.shell.layout.SurfacePresentation.DIALOG
import app.skein.feature.shell.layout.SurfacePresentation.PANE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import app.skein.feature.shell.layout.SurfacePresentation.BOTTOM_SHEET as BOTTOM
import app.skein.feature.shell.layout.SurfacePresentation.CENTERED_PANEL as CENTRED
import app.skein.feature.shell.layout.SurfacePresentation.FULL_SCREEN as FULL
import app.skein.feature.shell.layout.SurfacePresentation.SIDE_SHEET as SIDE

/** The table gives "≈" widths for the book rows; everything else is exact. */
private const val DP_TOLERANCE = 0.5f
private const val HINGE_PX = 1038f
private const val INNER_LONG_PX = 2152f

private fun decide(
    w: Int,
    h: Int,
    posture: Posture = Posture(),
    dpi: Int = 160,
): SkeinLayoutDecision =
    skeinWindowLayout(
        info = WindowAdaptiveInfo(WindowSizeClass.BREAKPOINTS_V2.computeWindowSizeClass(w, h), posture),
        size = DpSize(w.dp, h.dp),
        density = Density(dpi / 160f),
    )

/** A Material-shaped posture with one hinge line at [atPx] (window px). */
private fun hinge(
    vertical: Boolean,
    separating: Boolean,
    atPx: Float = HINGE_PX,
) = Posture(
    isTabletop = !vertical && separating,
    hingeList =
        listOf(
            HingeInfo(
                bounds =
                    if (vertical) Rect(atPx, 0f, atPx, INNER_LONG_PX) else Rect(0f, atPx, INNER_LONG_PX, atPx),
                isFlat = !separating,
                isVertical = vertical,
                isSeparating = separating,
                isOccluding = false,
            ),
        ),
)

/** The open inner panel: a hinge is reported, but it is flat and non-separating (§5.2). */
private val flatPortrait = hinge(vertical = true, separating = false)
private val flatLand = hinge(vertical = false, separating = false)
private val book = hinge(vertical = true, separating = true)
private val tabletop = hinge(vertical = false, separating = true)

/**
 * skein-xtov.24.3 (AL-04, UT-6): one case per `ADAPTIVE_LAYOUT_SPEC.md` §2.6
 * truth-table row (`row-NN`), plus the UT-6 breakpoint edges (`edge-…`).
 *
 * Hinges are given the way Material reports them — window-coordinate **px**
 * at the row's density — so the px→dp conversion is exercised too. The
 * Fold's hinge is the line at x (or y) = 1038 px on the 2076 × 2152 px inner
 * panel; `@330`/`@390`/`@420` are the densities the row names.
 *
 * `detail` is the detail pane's full width. With one pane that is the whole
 * content width: the table's "column 720" is the in-pane text cap
 * (`SkeinSize.readingMax`), not a pane width, so it is not asserted here.
 */
@RunWith(Parameterized::class)
class SkeinLayoutDecisionTest(
    private val row: Row,
) {
    data class Row(
        val name: String,
        val w: Int,
        val h: Int,
        val nav: SkeinNavContainer,
        val panes: Int,
        /** List/extra pane width; only asserted with two or more panes. */
        val side: Int?,
        val detail: Int,
        /** The inspector, Connections and node detail. */
        val extra: SurfacePresentation,
        val palette: SurfacePresentation,
        val attach: SurfacePresentation,
        val posture: Posture = Posture(),
        val dpi: Int = 160,
    ) {
        override fun toString() = name
    }

    @Test
    fun decides() {
        val decision = decide(row.w, row.h, row.posture, row.dpi)

        assertEquals("nav", row.nav, decision.nav)
        assertEquals("panes", row.panes, decision.maxPanes)
        row.side?.let { assertEquals("side", it.toFloat(), decision.sidePaneWidth.value, DP_TOLERANCE) }
        assertEquals("detail", row.detail.toFloat(), decision.detailWidth.value, DP_TOLERANCE)
        assertEquals("extra", row.extra, decision.presentationOf(SecondarySurface.CONTEXT_INSPECTOR))
        assertEquals("palette", row.palette, decision.presentationOf(SecondarySurface.COMMAND_PALETTE))
        assertEquals("attach", row.attach, decision.presentationOf(SecondarySurface.ATTACH_PICKER))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun rows() =
            listOf(
                // §2.6 rows. name, W, H, nav, panes, side, detail, extra, palette, attach, posture, dpi
                Row("row-01 phone", 360, 800, DRAWER, 1, null, 360, BOTTOM, FULL, FULL),
                Row("row-02 phone land", 800, 360, DRAWER, 1, null, 800, SIDE, CENTRED, FULL),
                Row("row-03 outer stock", 443, 994, DRAWER, 1, null, 443, BOTTOM, FULL, FULL),
                Row("row-04 outer @330", 524, 1175, DRAWER, 1, null, 524, BOTTOM, FULL, FULL),
                Row("row-05 outer stock land", 994, 443, DRAWER, 1, null, 994, SIDE, CENTRED, FULL),
                Row("row-06 outer @330 land", 1175, 524, DRAWER, 1, null, 1175, SIDE, CENTRED, FULL),
                Row("row-07 inner stock", 852, 883, RAIL, 2, 280, 468, PANE, CENTRED, CENTRED, flatPortrait, 390),
                Row("row-08 inner stock land", 883, 852, RAIL, 2, 280, 499, PANE, CENTRED, CENTRED, flatLand, 390),
                Row("row-09 inner @330", 1006, 1043, RAIL, 2, 320, 582, PANE, CENTRED, CENTRED, flatPortrait, 330),
                Row("row-10 inner @330 land", 1043, 1006, RAIL, 2, 320, 619, PANE, CENTRED, CENTRED, flatLand, 330),
                Row("row-11 inner medium", 791, 820, RAIL, 1, null, 711, SIDE, CENTRED, CENTRED, flatPortrait, 420),
                Row("row-12 inner medium land", 820, 791, RAIL, 1, null, 740, SIDE, CENTRED, CENTRED, flatLand, 420),
                Row("row-13 book @330", 1006, 1043, RAIL, 2, 411, 491, PANE, CENTRED, CENTRED, book, 330),
                Row("row-14 book medium", 791, 820, RAIL, 2, 303, 384, PANE, CENTRED, CENTRED, book, 420),
                Row("row-15 tabletop @330", 1043, 1006, RAIL, 2, 320, 619, PANE, CENTRED, CENTRED, tabletop, 330),
                Row("row-16 split half @330", 517, 1006, DRAWER, 1, null, 517, BOTTOM, FULL, FULL),
                Row("row-17 split half @390", 437, 852, DRAWER, 1, null, 437, BOTTOM, FULL, FULL),
                Row("row-18 split stacked", 1006, 515, DRAWER, 1, null, 1006, SIDE, CENTRED, FULL),
                Row("row-19 split 2/3", 690, 1006, RAIL, 1, null, 610, SIDE, CENTRED, CENTRED),
                Row("row-20 split 1/3", 340, 1006, DRAWER, 1, null, 340, BOTTOM, FULL, FULL),
                Row("row-21 outer split", 443, 490, DRAWER, 1, null, 443, BOTTOM, FULL, FULL),
                Row("row-22 free-form small", 700, 500, DRAWER, 1, null, 700, SIDE, CENTRED, FULL),
                Row("row-23 free-form medium", 1000, 700, RAIL, 2, 320, 576, PANE, CENTRED, CENTRED),
                Row("row-24 large", 1280, 800, RAIL, 3, 320, 512, PANE, CENTRED, CENTRED),
                Row("row-25 large edge", 1200, 800, RAIL, 3, 280, 512, PANE, CENTRED, CENTRED),
                Row("row-26 tablet portrait", 800, 1280, RAIL, 1, null, 720, SIDE, CENTRED, CENTRED),
                Row("row-27 desktop XL", 1920, 1080, EXPANDED_RAIL, 3, 320, 992, PANE, CENTRED, CENTRED),
                Row("row-28 short wide", 1400, 560, DRAWER, 1, null, 1400, SIDE, CENTRED, FULL),
                // UT-6 breakpoint edges: 599/600, 839/840, 1599/1600 wide; 599/600 tall (the two-pane gate).
                Row("edge-599w", 599, 900, DRAWER, 1, null, 599, BOTTOM, FULL, FULL),
                Row("edge-600w", 600, 900, RAIL, 1, null, 520, SIDE, CENTRED, CENTRED),
                Row("edge-839w", 839, 900, RAIL, 1, null, 759, SIDE, CENTRED, CENTRED),
                Row("edge-840w", 840, 900, RAIL, 2, 280, 456, PANE, CENTRED, CENTRED),
                Row("edge-1199w", 1199, 900, RAIL, 2, 320, 775, PANE, CENTRED, CENTRED),
                Row("edge-1599w", 1599, 900, RAIL, 3, 320, 831, PANE, CENTRED, CENTRED),
                Row("edge-1600w", 1600, 900, EXPANDED_RAIL, 3, 320, 672, PANE, CENTRED, CENTRED),
                Row("edge-gate-599h", 1043, 599, DRAWER, 1, null, 1043, SIDE, CENTRED, FULL),
                Row("edge-gate-600h", 1043, 600, RAIL, 2, 320, 619, PANE, CENTRED, CENTRED),
                // The width guard: a book hinge 300 dp from the start leaves a 208 dp side pane (< 280), so one pane.
                Row(
                    "edge-guard-book-hinge-near-start",
                    1006,
                    1043,
                    RAIL,
                    1,
                    null,
                    926,
                    SIDE,
                    CENTRED,
                    CENTRED,
                    hinge(vertical = true, separating = true, atPx = 300f * 330 / 160),
                    330,
                ),
            )
    }
}

/** The non-table half of AL-04: posture projection, the other surfaces, and tabletop partitions. */
class SkeinLayoutDecisionSurfacesTest {
    private val phone = decide(360, 800)
    private val inner = decide(1043, 1006)
    private val shortWide = decide(1175, 524)

    @Test
    fun `posture is Flat without a separating hinge, Book for a vertical one, Tabletop for a horizontal one`() {
        assertEquals(SkeinPosture.Flat, decide(1006, 1043).posture)
        assertEquals(SkeinPosture.Flat, decide(1006, 1043, flatPortrait, 330).posture)

        // 1038 px at 330 dpi = 503.27 dp from the window's start / top.
        val vertical = decide(1006, 1043, book, 330).posture
        assertTrue(vertical is SkeinPosture.Book)
        assertEquals(503.27f, (vertical as SkeinPosture.Book).hinge.left.value, 0.01f)

        val horizontal = decide(1043, 1006, tabletop, 330).posture
        assertTrue(horizontal is SkeinPosture.Tabletop)
        assertEquals(503.27f, (horizontal as SkeinPosture.Tabletop).hinge.top.value, 0.01f)
    }

    @Test
    fun `routes are panes beside the detail, full screen on one pane`() {
        listOf(SecondarySurface.OPENED_SOURCE, SecondarySurface.MODEL_DETAILS, SecondarySurface.SETTINGS_CATEGORY)
            .forEach { surface ->
                assertEquals(surface.name, FULL, phone.presentationOf(surface))
                assertEquals(surface.name, FULL, shortWide.presentationOf(surface))
                assertEquals(surface.name, PANE, inner.presentationOf(surface))
            }
    }

    @Test
    fun `model sheet anchors from 600 dp, dialogs stay dialogs, Connections and node detail follow the inspector`() {
        assertEquals(BOTTOM, phone.presentationOf(SecondarySurface.MODEL_SHEET))
        assertEquals(ANCHORED_PANEL, shortWide.presentationOf(SecondarySurface.MODEL_SHEET))
        assertEquals(ANCHORED_PANEL, inner.presentationOf(SecondarySurface.MODEL_SHEET))
        listOf(phone, shortWide, inner).forEach { decision ->
            assertEquals(DIALOG, decision.presentationOf(SecondarySurface.RENAME_DIALOG))
            assertEquals(DIALOG, decision.presentationOf(SecondarySurface.CONFIRM_DIALOG))
            val inspector = decision.presentationOf(SecondarySurface.CONTEXT_INSPECTOR)
            assertEquals(inspector, decision.presentationOf(SecondarySurface.CONNECTIONS))
            assertEquals(inspector, decision.presentationOf(SecondarySurface.NODE_DETAIL))
        }
    }

    @Test
    fun `tabletop puts text entry and reading on top, touch surfaces below, otherwise anywhere`() {
        val decision = decide(1043, 1006, tabletop, 330)
        val top =
            setOf(
                SecondarySurface.COMMAND_PALETTE,
                SecondarySurface.RENAME_DIALOG,
                SecondarySurface.ATTACH_PICKER,
                SecondarySurface.MODEL_SHEET,
                SecondarySurface.OPENED_SOURCE,
            )
        SecondarySurface.entries.forEach { surface ->
            val expected = if (surface in top) SurfacePartition.TOP else SurfacePartition.BOTTOM
            assertEquals(surface.name, expected, decision.partitionOf(surface))
            assertEquals(surface.name, SurfacePartition.ANY, inner.partitionOf(surface))
        }
    }
}
