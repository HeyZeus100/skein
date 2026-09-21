// `GraphLegend` (bd `skein-z2u`, plan `E6.I11`, spec §8.6: "legend for
// kinds"): a compact key mapping [DocumentKind] colors and the
// entity/tag diamond marker to their labels. Pure rendering, no state.
package app.skein.feature.graph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
public fun GraphLegend(modifier: Modifier = Modifier) {
    val colors = rememberGraphColors()
    Surface(
        modifier = modifier.testTag(GraphTestTags.LEGEND),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shape = MaterialTheme.shapes.small,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            LegendRow(color = colors.note, label = "Note")
            LegendRow(color = colors.chat, label = "Chat")
            LegendRow(color = colors.attachment, label = "Attachment")
            LegendRow(color = colors.aiout, label = "AI output")
            LegendRow(color = colors.sentinel, label = "Entity / tag", diamond = true)
        }
    }
}

@Composable
private fun LegendRow(
    color: Color,
    label: String,
    diamond: Boolean = false,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (diamond) {
            Canvas(modifier = Modifier.size(10.dp)) {
                val path =
                    Path().apply {
                        moveTo(size.width / 2f, 0f)
                        lineTo(size.width, size.height / 2f)
                        lineTo(size.width / 2f, size.height)
                        lineTo(0f, size.height / 2f)
                        close()
                    }
                drawPath(path, color = color)
            }
        } else {
            Box(
                modifier =
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(color),
            )
        }
        Text(text = label, style = MaterialTheme.typography.labelSmall)
    }
}
