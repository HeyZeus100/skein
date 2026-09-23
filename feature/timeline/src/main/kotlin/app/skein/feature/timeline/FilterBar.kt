package app.skein.feature.timeline

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.skein.core.model.DocumentKind
import app.skein.core.model.Persona
import app.skein.core.model.PersonaId
import app.skein.core.model.TimelineFilter

/**
 * Plan `E6.I7` filter bar: persona dropdown, kind toggles, tag chips — one
 * horizontally scrolling strip. Selection state is read straight off the
 * [filter]; every tap reports back through the callbacks and the state
 * layer decides whether anything actually changed.
 */
@Composable
internal fun FilterBar(
    filter: TimelineFilter,
    personas: List<Persona>,
    tags: List<String>,
    onPersona: (PersonaId?) -> Unit,
    onKind: (DocumentKind) -> Unit,
    onTag: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag(TimelineTestTags.FILTER_BAR),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (personas.isNotEmpty() || filter.personaId != null) {
            PersonaDropdownChip(
                selectedId = filter.personaId,
                personas = personas,
                onPersona = onPersona,
            )
        }
        DocumentKind.entries.forEach { kind ->
            FilterChip(
                selected = kind in filter.kinds,
                onClick = { onKind(kind) },
                label = { Text(kindChipLabel(kind)) },
                modifier = Modifier.testTag(TimelineTestTags.kindChip(kind)),
            )
        }
        tags.forEach { tag ->
            FilterChip(
                selected = filter.tag == tag,
                onClick = { onTag(tag) },
                label = { Text("#$tag") },
                modifier = Modifier.testTag(TimelineTestTags.tagChip(tag)),
            )
        }
    }
}

/**
 * A chip that opens a dropdown of personas plus an "All personas" entry.
 * When the selected id is not in the list (a persona deleted since the
 * filter was set, or a deep link), the raw id is shown so the chip is
 * never blank while a filter is active.
 */
@Composable
private fun PersonaDropdownChip(
    selectedId: PersonaId?,
    personas: List<Persona>,
    onPersona: (PersonaId?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val label = personas.firstOrNull { it.id == selectedId }?.name ?: selectedId ?: "Persona"
    Box {
        FilterChip(
            selected = selectedId != null,
            onClick = { open = true },
            label = { Text(label) },
            trailingIcon = { Text("▾") },
            modifier = Modifier.testTag(TimelineTestTags.PERSONA_CHIP),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("All personas") },
                onClick = {
                    open = false
                    onPersona(null)
                },
                modifier = Modifier.testTag(TimelineTestTags.PERSONA_MENU_ALL),
            )
            personas.forEach { persona ->
                DropdownMenuItem(
                    text = { Text(persona.name) },
                    onClick = {
                        open = false
                        onPersona(persona.id)
                    },
                    modifier = Modifier.testTag(TimelineTestTags.personaMenuItem(persona.id)),
                )
            }
        }
    }
}
