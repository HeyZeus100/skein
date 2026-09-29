package app.skein.feature.editor.entries

import androidx.lifecycle.ViewModel
import app.skein.core.model.TimelineFilter

/** Session-only filter selection. No repository, documents, persona objects or saved-state handle. */
internal class KnowledgeListFilterState : ViewModel() {
    private var current = TimelineFilter(kinds = KNOWLEDGE_KINDS)
    private var cleared = false

    val filter: TimelineFilter
        @Synchronized get() = current

    @Synchronized
    fun record(filter: TimelineFilter) {
        // A stopped Activity may still have a composed collector when the lock clears T3.
        if (!cleared) current = filter
    }

    @Synchronized
    override fun onCleared() {
        cleared = true
        current = TimelineFilter(kinds = KNOWLEDGE_KINDS)
    }
}
