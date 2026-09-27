package app.skein.feature.shell.layout

import androidx.compose.material3.adaptive.Posture
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.WindowSizeClass.Companion.WIDTH_DP_EXPANDED_LOWER_BOUND
import androidx.window.core.layout.WindowSizeClass.Companion.WIDTH_DP_EXTRA_LARGE_LOWER_BOUND
import androidx.window.core.layout.WindowSizeClass.Companion.WIDTH_DP_LARGE_LOWER_BOUND
import androidx.window.core.layout.WindowSizeClass.Companion.WIDTH_DP_MEDIUM_LOWER_BOUND
import app.skein.core.designsystem.theme.SkeinLayout
import app.skein.core.designsystem.theme.SkeinSize

// skein-xtov.24.3 (AL-04): the window decision function of
// `docs/ux/ADAPTIVE_LAYOUT_SPEC.md` §2.3, and its §2.5 presentation and §5.4
// partition rules. `SkeinLayoutDecisionTest` pins every §2.6 truth-table row.

/** The one navigation container a window gets (spec §3.1). */
enum class SkeinNavContainer(
    /** What the container takes from the content width. */
    val width: Dp,
) {
    /** Modal: it overlays the content, so it takes no width. */
    DRAWER(0.dp),
    RAIL(SkeinSize.rail),

    /** XL windows only. */
    EXPANDED_RAIL(SkeinSize.railExpanded),
}

/** Spec §5.1's three-case projection of Material's [Posture]. Hinge bounds are window dp. */
sealed interface SkeinPosture {
    /** No separating hinge (includes the open Fold, whose hinge reports flat and non-separating). */
    data object Flat : SkeinPosture

    /** A vertical, separating hinge: two pages, split at the fold. */
    data class Book(
        val hinge: DpRect,
    ) : SkeinPosture

    /** A horizontal, separating hinge: content above, controls below. */
    data class Tabletop(
        val hinge: DpRect,
    ) : SkeinPosture

    companion object {
        // ponytail: first separating hinge only; a multi-hinge device (tri-fold) needs one partition per hinge.
        fun from(
            posture: Posture,
            density: Density,
        ): SkeinPosture {
            val hinge = posture.hingeList.firstOrNull { it.isSeparating } ?: return Flat
            val bounds =
                with(density) {
                    hinge.bounds.run { DpRect(left.toDp(), top.toDp(), right.toDp(), bottom.toDp()) }
                }
            return if (hinge.isVertical) Book(bounds) else Tabletop(bounds)
        }
    }
}

/** Secondary surfaces (IA §3.3), whose presentation depends on the window. */
enum class SecondarySurface {
    CONTEXT_INSPECTOR,
    CONNECTIONS,
    NODE_DETAIL,

    /** A cited note or file opened from a chat (`CHAT_UX_SPEC.md` §17.6). */
    OPENED_SOURCE,
    MODEL_DETAILS,
    SETTINGS_CATEGORY,
    MODEL_SHEET,
    ATTACH_PICKER,
    COMMAND_PALETTE,
    RENAME_DIALOG,
    CONFIRM_DIALOG,
}

enum class SurfacePresentation {
    PANE,
    BOTTOM_SHEET,
    SIDE_SHEET,
    FULL_SCREEN,
    CENTERED_PANEL,
    ANCHORED_PANEL,
    DIALOG,
}

/** Which half of a tabletop window a surface belongs in (spec §5.4); [ANY] without a horizontal hinge. */
enum class SurfacePartition {
    ANY,
    TOP,
    BOTTOM,
}

data class SkeinLayoutDecision(
    val size: DpSize,
    /** As reported; never re-derived. */
    val windowSizeClass: WindowSizeClass,
    /** Height below [SkeinLayout.twoPaneMinHeight]. */
    val isShort: Boolean,
    val nav: SkeinNavContainer,
    /** Horizontal content panes, 1..3. */
    val maxPanes: Int,
    /** List and extra/supporting pane width; with one pane, the preferred width to hand a directive. */
    val sidePaneWidth: Dp,
    /** The detail pane's full width; with one pane, the whole content width. */
    val detailWidth: Dp,
    val posture: SkeinPosture,
)

/**
 * The decision (spec §2.3), pure. Width breakpoints come only from the
 * [WindowSizeClass], and the pane count starts from Material's directive
 * table; Skein adds the **height gate** ([SkeinLayout.twoPaneMinHeight]),
 * the **pane-width guard** and the **book-posture tie-breaker**. [density]
 * only turns Material's px hinge bounds into dp; it never decides anything.
 *
 * [info], [size] and [density] are parameters so tests, previews and
 * screenshots can force any window or posture; in the app, read them through
 * [currentSkeinWindowLayout].
 */
fun skeinWindowLayout(
    info: WindowAdaptiveInfo,
    size: DpSize,
    density: Density,
): SkeinLayoutDecision {
    val sizeClass = info.windowSizeClass
    val atLeastMedium = sizeClass.isWidthAtLeastBreakpoint(WIDTH_DP_MEDIUM_LOWER_BOUND)
    val isShort = size.height < SkeinLayout.twoPaneMinHeight
    val posture = SkeinPosture.from(info.windowPosture, density)

    // 1. One navigation container per window: a drawer where a rail does not fit, in width or height.
    val nav =
        when {
            !atLeastMedium || isShort -> SkeinNavContainer.DRAWER
            sizeClass.isWidthAtLeastBreakpoint(WIDTH_DP_EXTRA_LARGE_LOWER_BOUND) -> SkeinNavContainer.EXPANDED_RAIL
            else -> SkeinNavContainer.RAIL
        }
    val content = size.width - nav.width

    // A book hinge splits two panes at the fold (Material's `HingePolicy.AvoidSeparating`); the spacer straddles it.
    // ponytail: LTR only — in RTL the rail and the list pane sit on the right of the hinge.
    val bookSplit =
        (posture as? SkeinPosture.Book)?.let {
            val halfSpacer = SkeinSize.paneSpacer / 2
            (it.hinge.left - nav.width - halfSpacer) to (size.width - it.hinge.right - halfSpacer)
        }

    // 2. Material's `calculatePaneScaffoldDirective` table: 1 · 1 · 2 · 3 by width class.
    var panes =
        when {
            sizeClass.isWidthAtLeastBreakpoint(WIDTH_DP_LARGE_LOWER_BOUND) -> 3
            sizeClass.isWidthAtLeastBreakpoint(WIDTH_DP_EXPANDED_LOWER_BOUND) -> 2
            else -> 1
        }
    // 3. Book tie-breaker: two pages on a Medium window rather than one pane across the crease.
    if (bookSplit != null && atLeastMedium && panes == 1) panes = 2
    // 4. The height gate: a short window is a phone, whatever its width (prompt §22).
    if (isShort) panes = 1
    // 5. Tabletop keeps the flat layout in Wave 3 (hinge guards are AL-19); AL-20 makes it one pane.
    // 6. The guard, on real widths: drop panes until the detail and every side pane fit.
    while (panes > 1 && !fits(panes, content, bookSplit)) panes--

    // 7. Side panes are 320 dp when the detail keeps its comfortable width, else 280 dp.
    val preferredLeaves = content - (panes - 1) * (SkeinSize.paneSpacer + SkeinSize.listPane)
    val side =
        when {
            panes == 2 && bookSplit != null -> bookSplit.first
            panes > 1 && preferredLeaves < SkeinSize.detailPaneComfort -> SkeinSize.sidePaneMin
            else -> SkeinSize.listPane
        }
    val detail =
        when {
            panes == 1 -> content
            panes == 2 && bookSplit != null -> bookSplit.second
            else -> content - (panes - 1) * (SkeinSize.paneSpacer + side)
        }

    return SkeinLayoutDecision(size, sizeClass, isShort, nav, panes, side, detail, posture)
}

private fun fits(
    panes: Int,
    content: Dp,
    bookSplit: Pair<Dp, Dp>?,
): Boolean =
    if (panes == 2 && bookSplit != null) {
        bookSplit.first >= SkeinSize.sidePaneMin && bookSplit.second >= SkeinSize.detailPaneMin
    } else {
        content - (panes - 1) * (SkeinSize.paneSpacer + SkeinSize.sidePaneMin) >= SkeinSize.detailPaneMin
    }

/**
 * [skeinWindowLayout] for the current window. The size is the *measured*
 * container ([LocalWindowInfo]), never `LocalConfiguration` (it lags a live
 * resize) and never [WindowSizeClass.minHeightDp] (a bucket's lower bound,
 * which cannot express the 600 dp gate).
 */
@Composable
fun currentSkeinWindowLayout(
    info: WindowAdaptiveInfo = currentWindowAdaptiveInfoV2(),
    size: DpSize = LocalWindowInfo.current.containerDpSize,
): SkeinLayoutDecision = skeinWindowLayout(info, size, LocalDensity.current)

/** How [surface] appears in this window (spec §2.5). Pane surfaces are back-stack keys; the rest are UI state. */
fun SkeinLayoutDecision.presentationOf(surface: SecondarySurface): SurfacePresentation {
    val compactWidth = !windowSizeClass.isWidthAtLeastBreakpoint(WIDTH_DP_MEDIUM_LOWER_BOUND)
    return when (surface) {
        SecondarySurface.CONTEXT_INSPECTOR, SecondarySurface.CONNECTIONS, SecondarySurface.NODE_DETAIL ->
            when {
                maxPanes >= 2 -> SurfacePresentation.PANE
                compactWidth -> SurfacePresentation.BOTTOM_SHEET
                else -> SurfacePresentation.SIDE_SHEET
            }
        // On one pane these are routes, never sheets, so they have no peek.
        SecondarySurface.OPENED_SOURCE, SecondarySurface.MODEL_DETAILS, SecondarySurface.SETTINGS_CATEGORY ->
            if (maxPanes >= 2) SurfacePresentation.PANE else SurfacePresentation.FULL_SCREEN
        SecondarySurface.MODEL_SHEET ->
            if (compactWidth) SurfacePresentation.BOTTOM_SHEET else SurfacePresentation.ANCHORED_PANEL
        SecondarySurface.ATTACH_PICKER ->
            if (compactWidth || isShort) SurfacePresentation.FULL_SCREEN else SurfacePresentation.CENTERED_PANEL
        SecondarySurface.COMMAND_PALETTE ->
            if (compactWidth) SurfacePresentation.FULL_SCREEN else SurfacePresentation.CENTERED_PANEL
        SecondarySurface.RENAME_DIALOG, SecondarySurface.CONFIRM_DIALOG -> SurfacePresentation.DIALOG
    }
}

/**
 * Tabletop placement (spec §5.4): a modal never straddles a separating hinge.
 * Text entry and reading go on top, because the IME opens in the bottom half;
 * sheets and confirmations go in the bottom, touch half.
 */
fun SkeinLayoutDecision.partitionOf(surface: SecondarySurface): SurfacePartition =
    when {
        posture !is SkeinPosture.Tabletop -> SurfacePartition.ANY
        surface in topPartitionSurfaces -> SurfacePartition.TOP
        else -> SurfacePartition.BOTTOM
    }

private val topPartitionSurfaces =
    setOf(
        SecondarySurface.COMMAND_PALETTE,
        SecondarySurface.RENAME_DIALOG,
        SecondarySurface.ATTACH_PICKER,
        SecondarySurface.MODEL_SHEET,
        SecondarySurface.OPENED_SOURCE,
    )
