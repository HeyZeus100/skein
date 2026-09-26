// skein-xtov.23.6 (DS6, docs/ux/DESIGN_SYSTEM.md §9): Material Symbols
// Outlined (Apache-2.0, weight 400 / grade 0 / optical size 24 / fill 0),
// bundled as vector drawables under `res/drawable/ic_skein_*.xml` by
// `tools/icons/fetch_material_symbols.py` (pinned upstream commit in that
// script's header). This object is the one place a screen names a concept
// ("Send", "Delete") instead of a drawable file, so a redraw or a symbol
// swap touches one file, per §9.1's "Accessed through one object" rule.
//
// Every property here is a `@DrawableRes` resource id — the smallest thing
// that works with both `painterResource()` in Compose (`Icon(painter =
// painterResource(SkeinIcons.Send), ...)`) and plain `ContextCompat`
// callers, without forcing a `@Composable` context just to look up an id.
//
// `_Filled` companions exist only for the fill-1 "selected destination"
// variant (§9.1) of the five primary destinations (IA §3.2: Chat,
// Knowledge, Graph, Models, Settings); every other concept has one glyph.
//
// `SkeinIconsTest` asserts this object has no orphaned drawable and no
// dangling id — keep the two in lockstep.
package app.skein.core.designsystem.icons

import androidx.annotation.DrawableRes
import app.skein.core.designsystem.R

@Suppress("unused")
object SkeinIcons {
    // Navigation / chrome
    @DrawableRes val Menu: Int = R.drawable.ic_skein_menu

    @DrawableRes val Back: Int = R.drawable.ic_skein_back

    @DrawableRes val Close: Int = R.drawable.ic_skein_close

    @DrawableRes val More: Int = R.drawable.ic_skein_more

    @DrawableRes val Search: Int = R.drawable.ic_skein_search

    @DrawableRes val Expand: Int = R.drawable.ic_skein_expand

    // Primary destinations (outlined + selected fill-1)
    @DrawableRes val Chat: Int = R.drawable.ic_skein_chat

    @DrawableRes val ChatFilled: Int = R.drawable.ic_skein_chat_filled

    @DrawableRes val Knowledge: Int = R.drawable.ic_skein_knowledge

    @DrawableRes val KnowledgeFilled: Int = R.drawable.ic_skein_knowledge_filled

    @DrawableRes val Graph: Int = R.drawable.ic_skein_graph

    @DrawableRes val GraphFilled: Int = R.drawable.ic_skein_graph_filled

    @DrawableRes val Model: Int = R.drawable.ic_skein_model

    @DrawableRes val ModelFilled: Int = R.drawable.ic_skein_model_filled

    @DrawableRes val Settings: Int = R.drawable.ic_skein_settings

    @DrawableRes val SettingsFilled: Int = R.drawable.ic_skein_settings_filled

    // Chat / knowledge objects
    @DrawableRes val NewChat: Int = R.drawable.ic_skein_new_chat

    @DrawableRes val Note: Int = R.drawable.ic_skein_note

    @DrawableRes val NewNote: Int = R.drawable.ic_skein_new_note

    @DrawableRes val File: Int = R.drawable.ic_skein_file

    @DrawableRes val Pdf: Int = R.drawable.ic_skein_pdf

    @DrawableRes val Image: Int = R.drawable.ic_skein_image

    @DrawableRes val ImportFile: Int = R.drawable.ic_skein_import_file

    @DrawableRes val AiOutput: Int = R.drawable.ic_skein_ai_output

    @DrawableRes val Persona: Int = R.drawable.ic_skein_persona

    // Composer
    @DrawableRes val Attach: Int = R.drawable.ic_skein_attach

    @DrawableRes val AttachFile: Int = R.drawable.ic_skein_attach_file

    @DrawableRes val Send: Int = R.drawable.ic_skein_send

    @DrawableRes val Stop: Int = R.drawable.ic_skein_stop

    @DrawableRes val JumpToLatest: Int = R.drawable.ic_skein_jump_to_latest

    // Message / activity actions
    @DrawableRes val Retry: Int = R.drawable.ic_skein_retry

    @DrawableRes val Copy: Int = R.drawable.ic_skein_copy

    @DrawableRes val Check: Int = R.drawable.ic_skein_check

    @DrawableRes val Context: Int = R.drawable.ic_skein_context

    @DrawableRes val Sources: Int = R.drawable.ic_skein_sources

    @DrawableRes val ActivityFailed: Int = R.drawable.ic_skein_activity_failed

    // Row / object actions
    @DrawableRes val Delete: Int = R.drawable.ic_skein_delete

    @DrawableRes val Rename: Int = R.drawable.ic_skein_rename

    @DrawableRes val Open: Int = R.drawable.ic_skein_open

    @DrawableRes val Share: Int = R.drawable.ic_skein_share

    @DrawableRes val Pin: Int = R.drawable.ic_skein_pin

    // Security / misc
    @DrawableRes val Lock: Int = R.drawable.ic_skein_lock

    @DrawableRes val Keyboard: Int = R.drawable.ic_skein_keyboard

    @DrawableRes val Link: Int = R.drawable.ic_skein_link

    @DrawableRes val LinkOff: Int = R.drawable.ic_skein_link_off

    @DrawableRes val Warning: Int = R.drawable.ic_skein_warning

    @DrawableRes val Info: Int = R.drawable.ic_skein_info

    @DrawableRes val Success: Int = R.drawable.ic_skein_success
}
