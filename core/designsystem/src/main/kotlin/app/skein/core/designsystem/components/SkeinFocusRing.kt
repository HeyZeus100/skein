// skein-xtov.23.9 (DS9, docs/ux/DESIGN_SYSTEM.md §7.3): the keyboard-only
// focus ring. Compose's own `LocalInputModeManager` already distinguishes
// "the last input was a key/dpad event" from "the last input was a touch" —
// fed by real key/touch dispatch at the window (`AndroidComposeView`), so
// there is no separate event-tracking system to build from scratch.
// [LocalSkeinKeyboardMode] wraps that signal in an overridable
// `CompositionLocal`: production code never sets it (falls through to the
// real `LocalInputModeManager` signal), but a preview or a screenshot test
// can force keyboard mode on without simulating a real hardware key press.
package app.skein.core.designsystem.components

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.Dp
import app.skein.core.designsystem.theme.LocalSkeinColors
import app.skein.core.designsystem.theme.SkeinRadius
import app.skein.core.designsystem.theme.SkeinSize

/**
 * Overrides the keyboard-input-mode signal [Modifier.skeinFocusRing] reads.
 * `null` (the default, always in production) defers to
 * [LocalInputModeManager] — the real thing. Previews and tests provide
 * `true`/`false` directly so a keyboard-mode capture doesn't need a
 * simulated key press.
 */
val LocalSkeinKeyboardMode = compositionLocalOf<Boolean?> { null }

/**
 * Pure so the on/off rule is unit-testable with no composition at all
 * (§7.3: the ring shows only while the element is focused *and* the last
 * input was a hardware keyboard — touch users never see it, TalkBack draws
 * its own).
 */
internal fun skeinFocusRingVisible(
    focused: Boolean,
    keyboardMode: Boolean,
): Boolean = focused && keyboardMode

/**
 * Draws the keyboard-only focus ring (§7.3, §5.4): a 2 dp
 * [focusRing][app.skein.core.designsystem.theme.SkeinExtendedColors.focusRing]
 * stroke, outside the component with a 2 dp gap so it stays visible even on
 * a filled `primary` control, following the component's own shape offset
 * outward by that gap (roughly [cornerRadius] `+ 4.dp`, per §5.1). Apply to
 * any already-focusable component (a `Button`, a chip, an `IconButton`) —
 * this modifier only draws; it does not itself request focus.
 */
fun Modifier.skeinFocusRing(cornerRadius: Dp = SkeinRadius.radiusMd): Modifier =
    composed {
        var focused by remember { mutableStateOf(false) }
        val keyboardMode =
            LocalSkeinKeyboardMode.current
                ?: (LocalInputModeManager.current.inputMode == InputMode.Keyboard)
        val ringColor = LocalSkeinColors.current.focusRing
        val strokeWidth = SkeinSize.focusRing
        val gap = SkeinSize.focusRingGap

        this
            .onFocusEvent { focused = it.isFocused }
            .drawWithContent {
                drawContent()
                if (skeinFocusRingVisible(focused, keyboardMode)) {
                    val strokeWidthPx = strokeWidth.toPx()
                    val outset = gap.toPx() + strokeWidthPx / 2f
                    inset(-outset) {
                        drawRoundRect(
                            color = ringColor,
                            style = Stroke(width = strokeWidthPx),
                            cornerRadius = CornerRadius(cornerRadius.toPx() + outset),
                        )
                    }
                }
            }
    }
