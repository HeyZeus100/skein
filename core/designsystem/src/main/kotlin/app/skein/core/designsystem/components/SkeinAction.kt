// skein-xtov.23.8 (DS8, docs/ux/DESIGN_SYSTEM.md §10.9, §10.12, §10.20): the
// one "labelled thing a user can do" shape shared by row menus (and their
// TalkBack custom actions), empty-state actions and notice actions.
package app.skein.core.designsystem.components

import androidx.annotation.DrawableRes

/**
 * An action with a visible [label] (§11: a verb, sentence case; a trailing
 * "…" when it asks for more before acting, e.g. "Rename…").
 *
 * [icon] is a [app.skein.core.designsystem.icons.SkeinIcons] id, drawn
 * decoratively beside the label. [destructive] puts a menu item last, after
 * a divider, in `error` (§10.12).
 */
data class SkeinAction(
    val label: String,
    @DrawableRes val icon: Int? = null,
    val destructive: Boolean = false,
    /** Menu availability; disabled menu items are also omitted from row accessibility actions. */
    val enabled: Boolean = true,
    /** Optional visible menu explanation, such as why an action is temporarily unavailable. */
    val supportingText: String? = null,
    val onClick: () -> Unit,
) {
    /** The label a screen reader announces: [label] without the menu's trailing "…" ("Rename", not "Rename…"). */
    val accessibilityLabel: String get() = label.removeSuffix("…").trimEnd()
}
