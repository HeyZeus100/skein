package app.skein.feature.timeline

import app.skein.core.model.DocumentKind
import app.skein.core.model.PersonaId
import java.time.LocalDate

/**
 * Stable test tags for Compose UI tests against the timeline surface —
 * same convention as `ShellTestTags` (snake_case, prefixed by surface) so
 * tests locate nodes by identity rather than display strings.
 */
public object TimelineTestTags {
    public const val ROOT: String = "timeline_root"
    public const val FILTER_BAR: String = "timeline_filter_bar"
    public const val LIST: String = "timeline_list"
    public const val EMPTY: String = "timeline_empty"
    public const val CLEAR_FILTERS: String = "timeline_clear_filters"
    public const val PERSONA_CHIP: String = "timeline_persona_chip"
    public const val PERSONA_MENU_ALL: String = "timeline_persona_menu_all"

    /** Prefix shared by every [entryRow] tag — for "count the rows" matchers. */
    public const val ENTRY_ROW_PREFIX: String = "timeline_entry_"

    /** Prefix shared by every [dayHeader] tag. */
    public const val DAY_HEADER_PREFIX: String = "timeline_day_"

    public fun personaMenuItem(personaId: PersonaId): String = "timeline_persona_menu_$personaId"

    public fun kindChip(kind: DocumentKind): String = "timeline_kind_chip_${kind.db}"

    public fun tagChip(tag: String): String = "timeline_tag_chip_$tag"

    public fun entryRow(docId: String): String = ENTRY_ROW_PREFIX + docId

    public fun dayHeader(day: LocalDate): String = DAY_HEADER_PREFIX + day.toEpochDay()
}
