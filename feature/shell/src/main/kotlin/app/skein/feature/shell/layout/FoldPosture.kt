package app.skein.feature.shell.layout

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Hinge state of a foldable device (spec `E6.I2`), read from
 * `androidx.window`'s [FoldingFeature]. `UNKNOWN` covers non-foldable
 * devices and any window with no reported folding feature: the safe
 * fallback for layout purposes is the same as `FLAT`.
 */
enum class FoldPostureState { FLAT, HALF_OPENED, UNKNOWN }

/** Hinge axis, mirroring [FoldingFeature.Orientation]. `NONE` means no hinge is reported. */
enum class FoldOrientation { NONE, VERTICAL, HORIZONTAL }

/**
 * Immutable snapshot of the device's fold posture. A vertical hinge in
 * [FoldPostureState.HALF_OPENED] is "book" mode (left/right split); a
 * horizontal hinge in that state is "tabletop" mode (top/bottom split).
 */
data class FoldPosture(
    val state: FoldPostureState = FoldPostureState.UNKNOWN,
    val orientation: FoldOrientation = FoldOrientation.NONE,
) {
    /** True only for a horizontal hinge held half-open ("tabletop"): panes side-by-side don't fit. */
    val isTabletop: Boolean
        get() = state == FoldPostureState.HALF_OPENED && orientation == FoldOrientation.HORIZONTAL

    companion object {
        val Unknown = FoldPosture()
    }
}

private fun FoldingFeature.State.toPostureState(): FoldPostureState =
    when (this) {
        FoldingFeature.State.FLAT -> FoldPostureState.FLAT
        FoldingFeature.State.HALF_OPENED -> FoldPostureState.HALF_OPENED
        else -> FoldPostureState.UNKNOWN
    }

private fun FoldingFeature.Orientation.toFoldOrientation(): FoldOrientation =
    when (this) {
        FoldingFeature.Orientation.VERTICAL -> FoldOrientation.VERTICAL
        FoldingFeature.Orientation.HORIZONTAL -> FoldOrientation.HORIZONTAL
        else -> FoldOrientation.NONE
    }

/** Extracts [FoldPosture] from the first reported [FoldingFeature], or [FoldPosture.Unknown]. */
fun WindowLayoutInfo.toFoldPosture(): FoldPosture {
    val feature = displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull() ?: return FoldPosture.Unknown
    return FoldPosture(
        state = feature.state.toPostureState(),
        orientation = feature.orientation.toFoldOrientation(),
    )
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

/** [WindowInfoTracker.windowLayoutInfo] for [activity], mapped down to our [FoldPosture] domain type. */
fun foldPostureFlow(
    activity: Activity,
    windowInfoTracker: WindowInfoTracker = WindowInfoTracker.getOrCreate(activity),
): Flow<FoldPosture> = windowInfoTracker.windowLayoutInfo(activity).map { it.toFoldPosture() }

/**
 * Observes the current [FoldPosture] for the host [Activity]. Falls back to
 * [FoldPosture.Unknown] when the current [LocalContext] has no enclosing
 * `Activity` (e.g. a plain `@Preview`).
 */
@Composable
fun rememberFoldPosture(): State<FoldPosture> {
    val activity = LocalContext.current.findActivity()
    val flow =
        remember(activity) {
            activity?.let { foldPostureFlow(it) }
        }
    return if (flow != null) {
        flow.collectAsState(initial = FoldPosture.Unknown)
    } else {
        remember {
            object : State<FoldPosture> {
                override val value = FoldPosture.Unknown
            }
        }
    }
}
