// skein-xtov.24.6 (AL-07, UX Wave 3): the shell's five primary destinations
// (INFORMATION_ARCHITECTURE.md §3.2), in drawer/rail order. A tiny enum of
// its own on purpose — AL-06's `:core:navigation` keys are concurrent work
// this bead does not depend on yet; AL-08 maps the two together.
package app.skein.feature.shell.container

import androidx.annotation.DrawableRes
import app.skein.core.designsystem.icons.SkeinIcons

/**
 * One primary destination (IA §3.2): Chat · Knowledge · Graph · Models ·
 * Settings, in the order [SkeinDrawerContent]/[SkeinRailContent] render
 * them. [iconSelected] is the filled variant shown for the current
 * [destination] (§9.1's "selected fill-1" rule).
 */
enum class SkeinDestination(
    val label: String,
    @DrawableRes val icon: Int,
    @DrawableRes val iconSelected: Int,
) {
    CHAT("Chat", SkeinIcons.Chat, SkeinIcons.ChatFilled),
    KNOWLEDGE("Knowledge", SkeinIcons.Knowledge, SkeinIcons.KnowledgeFilled),
    GRAPH("Graph", SkeinIcons.Graph, SkeinIcons.GraphFilled),
    MODELS("Models", SkeinIcons.Model, SkeinIcons.ModelFilled),
    SETTINGS("Settings", SkeinIcons.Settings, SkeinIcons.SettingsFilled),
}
