package app.skein.core.designsystem.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing

/**
 * Motion tokens (spec §8.1, DS5). Exits run at about two thirds of the enter
 * duration (not its own token — computed at the call site). No bounce
 * anywhere: a Skein spring always uses `dampingRatio = 1f`. Material
 * components (drawer, bottom sheet, dialog, menu, adaptive panes) keep
 * Material's own motion rather than these tokens.
 */
object SkeinMotion {
    /** Anything invoked from the keyboard: palette open/close, list selection by arrow keys. */
    val durationInstant: Int = 0

    /** Colour and state-layer changes, icon crossfade (Send ↔ Stop), tooltips. */
    val durationShort: Int = 100

    /** Expand/collapse (activity block, reasoning lane, disclosure rows), chevron rotation, chip selection. */
    val durationMedium: Int = 200

    /** Enter of Skein-animated sheets/overlays (Material components keep their own). */
    val durationLong: Int = 300

    /** On-screen changes: expand, collapse, move. */
    val easingStandard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Elements entering. */
    val easingEnter: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** Elements leaving — short durations only. */
    val easingExit: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** Determinate progress value changes. */
    val easingLinear: Easing = LinearEasing
}
