// skein-xtov.24.4 (AL-05, throwaway): §8.4 step 1, `SkeinSheetSceneStrategy`,
// measured against gate criterion G3 (≤ 150 lines, no fork). On one pane, a
// sheet surface (inspector, Connections, node detail) on top of the stack is
// rendered over the entry below it: a PEEK bar docked under that entry (no
// scrim, the entry stays fully interactive) or, once the user expands it, a
// bottom/side sheet over a scrim. Both states are one scene with one key, so
// peek <-> expanded never runs a scene transition, and the sheet's own content
// moves between the two slots through Nav3's per-entry movableContentOf.
package app.skein.prototype.nav3

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import app.skein.feature.shell.layout.SecondarySurface
import app.skein.feature.shell.layout.SkeinLayoutDecision
import app.skein.feature.shell.layout.SurfacePresentation
import app.skein.feature.shell.layout.presentationOf

/** Entry metadata: this key is a §2.5 sheet surface. */
object SheetMetadata {
    internal const val KEY = "app.skein.prototype.nav3.sheet"

    fun sheet(surface: SecondarySurface): Map<String, Any> = mapOf(KEY to surface)
}

enum class SheetMode { PANE, PEEK, EXPANDED }

/** How the entry is being shown, so a sheet entry can render its own peek row. */
val LocalSheetMode = compositionLocalOf { SheetMode.PANE }

/** Which sheets the user expanded. T8 (never saved): a shrink or a restore shows the peek (§2.5). */
@Stable
class SheetPresentation {
    private val expanded = mutableStateSetOf<Any>()

    /** Read in composition, so an expand recomposes the shell and re-runs the strategies. */
    val expandedKeys: Set<Any> get() = expanded.toSet()

    fun expand(contentKey: Any) {
        expanded += contentKey
    }

    fun collapseAll() = expanded.clear()
}

const val PEEK_TAG = "sheet-peek"
const val SCRIM_TAG = "sheet-scrim"
const val SHEET_TAG = "sheet-expanded"

/**
 * Compared by value: `rememberSceneState` recomputes scenes only when the
 * strategy list or the entries change, so the expanded set is an input here,
 * not something read inside [calculateScene].
 */
class SkeinSheetSceneStrategy<T : Any>(
    private val layout: SkeinLayoutDecision,
    private val expanded: Set<Any>,
    private val onExpand: (Any) -> Unit,
) : SceneStrategy<T> {
    override fun SceneStrategyScope<T>.calculateScene(entries: List<NavEntry<T>>): Scene<T>? {
        if (layout.maxPanes != 1 || entries.size < 2) return null
        val sheet = entries.last()
        val surface = sheet.metadata[SheetMetadata.KEY] as? SecondarySurface ?: return null
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
                        tonalElevation = 3.dp,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(
                                    min = 48.dp,
                                ).clickable(onClick = onExpand)
                                .testTag(PEEK_TAG),
                    ) { CompositionLocalProvider(LocalSheetMode provides SheetMode.PEEK) { sheet.Content() } }
                }
            }
            if (expanded) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                        .clickable(onClick = onDismiss)
                        .testTag(SCRIM_TAG),
                )
                Surface(
                    tonalElevation = 6.dp,
                    modifier =
                        Modifier
                            .align(if (side) Alignment.CenterEnd else Alignment.BottomCenter)
                            .then(
                                if (side) {
                                    Modifier
                                        .width(
                                            360.dp,
                                        ).fillMaxHeight()
                                } else {
                                    Modifier.fillMaxWidth().fillMaxHeight(0.5f)
                                },
                            ).testTag(SHEET_TAG),
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
