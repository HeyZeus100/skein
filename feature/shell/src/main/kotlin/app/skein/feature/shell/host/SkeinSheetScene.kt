// skein-xtov.24.7 (AL-08): spec §8.4 step 1, `SkeinSheetSceneStrategy`, as
// the AL-05 spike built it (gate criterion G3; §8.9 item 6: a custom scene, not
// `AdaptStrategy.Levitate`). On one pane, a sheet surface (inspector,
// Connections, node detail) on top of the stack renders over the entry below
// it: a PEEK docked under that entry (no scrim; the entry stays interactive)
// or, once the user expands it, a bottom or side sheet over a scrim. Both are
// one scene with one key, so peek ↔ expanded never runs a scene transition,
// and the sheet's content moves between the two slots through Nav3's
// per-entry movableContentOf.
package app.skein.feature.shell.host

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import app.skein.core.designsystem.components.SkeinSheetDefaults
import app.skein.core.designsystem.theme.SkeinSize
import app.skein.feature.shell.layout.SecondarySurface
import app.skein.feature.shell.layout.SkeinLayoutDecision
import app.skein.feature.shell.layout.SurfacePresentation
import app.skein.feature.shell.layout.presentationOf

/** Entry metadata key: the entry is a §2.5 sheet surface on one pane. */
internal const val SHEET_METADATA_KEY = "app.skein.feature.shell.host.sheet"

enum class SheetMode { PANE, PEEK, EXPANDED }

/** How a sheet entry is being shown, so it can render its own peek row. */
val LocalSheetMode = compositionLocalOf { SheetMode.PANE }

/**
 * Which sheets the user expanded, by content key. T8 (never saved): a shrink,
 * a restore or a lock shows the peek (§2.5, §7.4 item 2). An entry that opens a
 * sheet surface on one pane calls [expand], because the user asked for it.
 */
@Stable
class SheetPresentation {
    private val expanded = mutableStateSetOf<Any>()

    /** Read in composition, so an expand recomposes the host and re-runs the strategies. */
    val expandedKeys: Set<Any> get() = expanded.toSet()

    fun expand(contentKey: Any) {
        expanded += contentKey
    }

    fun collapseAll() = expanded.clear()
}

object SheetTestTags {
    const val PEEK = "skein_sheet_peek"
    const val SCRIM = "skein_sheet_scrim"
    const val EXPANDED = "skein_sheet_expanded"
}

/**
 * Compared by value (§8.9 item 5): `rememberSceneState` recomputes scenes only
 * when the strategy list or the entries change, so the expanded set is an
 * input here, never state read inside [calculateScene].
 */
internal class SkeinSheetSceneStrategy<T : Any>(
    private val layout: SkeinLayoutDecision,
    private val expanded: Set<Any>,
    private val onExpand: (Any) -> Unit,
) : SceneStrategy<T> {
    override fun SceneStrategyScope<T>.calculateScene(entries: List<NavEntry<T>>): Scene<T>? {
        if (layout.maxPanes != 1 || entries.size < 2) return null
        val sheet = entries.last()
        val surface = sheet.metadata[SHEET_METADATA_KEY] as? SecondarySurface ?: return null
        return SheetScene(
            underlying = entries[entries.lastIndex - 1],
            sheet = sheet,
            previousEntries = entries.dropLast(1),
            expanded = sheet.contentKey in expanded,
            side = layout.presentationOf(surface) == SurfacePresentation.SIDE_SHEET,
            onExpand = { onExpand(sheet.contentKey) },
            onDismiss = onBack,
        )
    }

    override fun equals(other: Any?): Boolean =
        other is SkeinSheetSceneStrategy<*> && other.layout == layout && other.expanded == expanded

    override fun hashCode(): Int = layout.hashCode() * 31 + expanded.hashCode()
}

private class SheetScene<T : Any>(
    val underlying: NavEntry<T>,
    val sheet: NavEntry<T>,
    override val previousEntries: List<NavEntry<T>>,
    val expanded: Boolean,
    val side: Boolean,
    val onExpand: () -> Unit,
    val onDismiss: () -> Unit,
) : Scene<T> {
    // Keyed by the entry underneath: peek and expanded are the same scene.
    override val key: Any = underlying.contentKey
    override val entries: List<NavEntry<T>> = listOf(underlying, sheet)

    override val content: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) { underlying.Content() }
                if (!expanded) {
                    Surface(
                        color = SkeinSheetDefaults.containerColor,
                        shape = SkeinSheetDefaults.shape,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = SkeinSize.touchTarget)
                                .clickable(onClickLabel = "Expand", onClick = onExpand)
                                .testTag(SheetTestTags.PEEK),
                    ) { CompositionLocalProvider(LocalSheetMode provides SheetMode.PEEK) { sheet.Content() } }
                }
            }
            if (expanded) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(SkeinSheetDefaults.scrimColor)
                        .clickable(onClickLabel = "Close", onClick = onDismiss)
                        .testTag(SheetTestTags.SCRIM),
                )
                // Tone, not elevation (DESIGN_SYSTEM.md §5.3, §10.10).
                Surface(
                    color = SkeinSheetDefaults.containerColor,
                    shape = if (side) RectangleShape else SkeinSheetDefaults.shape,
                    modifier =
                        Modifier
                            .align(if (side) Alignment.CenterEnd else Alignment.BottomCenter)
                            .then(
                                if (side) {
                                    Modifier.width(SkeinSize.extraPaneLarge).fillMaxHeight()
                                } else {
                                    Modifier.fillMaxWidth().fillMaxHeight(BOTTOM_SHEET_FRACTION)
                                },
                            ).testTag(SheetTestTags.EXPANDED),
                ) { CompositionLocalProvider(LocalSheetMode provides SheetMode.EXPANDED) { sheet.Content() } }
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        other is SheetScene<*> &&
            other.underlying == underlying &&
            other.sheet == sheet &&
            other.previousEntries == previousEntries &&
            other.expanded == expanded &&
            other.side == side

    override fun hashCode(): Int =
        ((underlying.hashCode() * 31 + sheet.hashCode()) * 31 + expanded.hashCode()) * 31 + side.hashCode()
}

private const val BOTTOM_SHEET_FRACTION = 0.5f
