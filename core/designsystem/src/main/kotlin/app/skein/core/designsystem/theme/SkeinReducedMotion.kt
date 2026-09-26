package app.skein.core.designsystem.theme

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * True when Android's *Remove animations* accessibility setting (or the
 * matching "Animator duration scale" developer option) has zeroed the
 * system animator duration scale (spec §8.3). Provided by [SkeinTheme] via
 * [rememberSkeinReducedMotion].
 *
 * Compose's own `animate*` / `Animatable` / `tween` already honour that
 * scale through `MotionDurationScale` — never bypass it with a hand-rolled
 * `withFrameNanos` loop for UI motion. This local only needs to cover the
 * non-animation-API cases §8.3 calls out: the graph simulation, auto-scroll
 * smoothness, and the streaming caret (removed entirely, not shortened).
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

/**
 * The live `Settings.Global.ANIMATOR_DURATION_SCALE` value; `0f` is "remove
 * animations" (or the developer option set to "Animation off"). Internal —
 * exercised directly by `SkeinReducedMotionTest` against a faked setting;
 * app code reads [LocalReducedMotion] instead.
 */
internal fun animatorDurationScale(context: Context): Float =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

/**
 * Resolves [LocalReducedMotion]'s value: the current animator-duration
 * scale, re-read whenever the system setting changes so a mid-session
 * toggle (Settings, or a developer-options change) takes effect without a
 * recreate.
 */
@Composable
fun rememberSkeinReducedMotion(): Boolean {
    val context = LocalContext.current
    var reduced by remember(context) { mutableStateOf(animatorDurationScale(context) == 0f) }
    DisposableEffect(context) {
        val resolver = context.contentResolver
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    reduced = animatorDurationScale(context) == 0f
                }
            }
        val notifyForDescendants = false
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            notifyForDescendants,
            observer,
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduced
}
