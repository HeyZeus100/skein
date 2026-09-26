package app.skein.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The corner-radius scale (spec §5.1, DS5): radius follows element size —
 * about a quarter to a third of the height of a small control — never one
 * radius everywhere. [radiusFull] is a shape (a 50 % pill), not a fixed dp;
 * [userBubbleAuthorCorner] is the one corner that breaks the uniform radius
 * (see [SkeinUserBubbleShape]).
 */
object SkeinRadius {
    /** Panes, top bars, full-screen surfaces, rail, table cells. */
    val radiusNone: Dp = 0.dp

    /** Inline code, citation markers, keycaps, tooltips, graph label plates, progress bar ends. */
    val radiusXs: Dp = 4.dp

    /** Chips, menus, code blocks, tables, snackbars, the reasoning lane. */
    val radiusSm: Dp = 8.dp

    /** Buttons, segmented buttons, text and search fields, cards and notices, selected-row indicators. */
    val radiusMd: Dp = 12.dp

    /** The user message bubble's three uniform corners (the fourth is [userBubbleAuthorCorner]). */
    val radiusLg: Dp = 16.dp

    /** Composer, dialogs, bottom-sheet top corners, palette overlay, drawer end corners. */
    val radiusXl: Dp = 20.dp

    /** The user bubble's corner nearest its author — 4 dp against its otherwise-16 dp shape. */
    val userBubbleAuthorCorner: Dp = 4.dp

    /** Send/Stop button, status dots, rail active indicator, switches. */
    val radiusFull: Shape = RoundedCornerShape(percent = 50)
}

/**
 * The user message bubble's shape (§5.1): [SkeinRadius.radiusLg] on three
 * corners, [SkeinRadius.userBubbleAuthorCorner] on the corner nearest its
 * author. `topStart`/`topEnd` resolve against
 * [androidx.compose.ui.unit.LayoutDirection], so in LTR the author corner is
 * the top-right and in RTL the top-left, matching the bubble's actual
 * leading/trailing edge (spec §7's rows-in-RTL rule) without a second shape.
 */
val SkeinUserBubbleShape: Shape =
    RoundedCornerShape(
        topStart = SkeinRadius.radiusLg,
        topEnd = SkeinRadius.userBubbleAuthorCorner,
        bottomEnd = SkeinRadius.radiusLg,
        bottomStart = SkeinRadius.radiusLg,
    )

/**
 * Material `Shapes` (§5.1, §13.2 DS5) wired into [SkeinTheme]: [SkeinRadius]'s
 * scale mapped onto Material's five *public* slots. 1.4.0's `Shapes` has
 * eight slots in total, but the three "expressive" ones added alongside
 * `large`/`extraLarge` — `largeIncreased`, `extraLargeIncreased`,
 * `extraExtraLarge` — sit behind an `internal` constructor
 * (`INVISIBLE_REFERENCE` from outside the `material3` module, verified
 * against the 1.4.0 jar), so they aren't settable here and keep Material's
 * own defaults (20 / 24 / 28 dp). That's a fine outcome for Skein anyway:
 * the spec's scale tops out at [SkeinRadius.radiusXl] (20 dp) with nothing
 * bigger, and Skein's flat, no-shadow surfaces don't call for a dialog/sheet
 * radius past what the composer already uses.
 *
 * Several Material components ignore or undershoot `Shapes` outright
 * (buttons/drawer items/segmented buttons default to pills; text fields and
 * menus to 4 dp), so Skein's own component wrappers (DS7/DS8/DS9) pass a
 * shape explicitly rather than relying on this object alone.
 */
val SkeinShapes: Shapes =
    Shapes(
        extraSmall = RoundedCornerShape(SkeinRadius.radiusXs),
        small = RoundedCornerShape(SkeinRadius.radiusSm),
        medium = RoundedCornerShape(SkeinRadius.radiusMd),
        large = RoundedCornerShape(SkeinRadius.radiusLg),
        extraLarge = RoundedCornerShape(SkeinRadius.radiusXl),
    )
