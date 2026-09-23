package app.skein.feature.shell.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Renders every token this issue produces on one screen: type scale, both
 * accent colors, and the glyph set. Used as the target of the golden
 * screenshot test in both themes (spec `E6.I1` acceptance criteria).
 */
@Composable
fun ThemeGallery(modifier: Modifier = Modifier) {
    val tokens = LocalSkeinTokens.current
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Skein", style = MaterialTheme.typography.displayMedium)
            Text("terminal / editor shell", style = MaterialTheme.typography.headlineSmall)
            Text("Body text sets in IBM Plex Mono.", style = MaterialTheme.typography.bodyLarge)
            Text("Secondary / label text.", style = MaterialTheme.typography.labelMedium)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Swatch("primary", MaterialTheme.colorScheme.primary)
                Swatch("tertiary", MaterialTheme.colorScheme.tertiary)
                Swatch("surface", MaterialTheme.colorScheme.surface)
                Swatch("background", MaterialTheme.colorScheme.background)
            }

            // bd `skein-jit3`: the editor's own surface, rendered here so a
            // theme-gallery screenshot catches a regression back to
            // rendering editor text on `background`/`surface` instead.
            val editorColors = LocalSkeinEditorColors.current
            Surface(color = editorColors.surface, contentColor = editorColors.onSurface) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("editor surface", style = MaterialTheme.typography.labelSmall)
                    Text(
                        "The quick brown fox jumps over the lazy dog.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = editorColors.onSurface,
                    )
                    Text(
                        "muted / frontmatter / broken wikilink",
                        style = MaterialTheme.typography.bodyMedium,
                        color = editorColors.onSurfaceMuted,
                    )
                }
            }

            Text(
                text =
                    with(tokens.glyphs) {
                        "$modelActive $modelPaused $pending $alert $collapse $split $graph $context"
                    },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun Swatch(
    label: String,
    color: Color,
) {
    Column {
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
            color = color,
        ) {
            Box(modifier = Modifier.padding(24.dp)) {}
        }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Preview(name = "Theme gallery — dark", showBackground = true)
@Composable
private fun ThemeGalleryDarkPreview() {
    SkeinTheme(mode = SkeinThemeMode.DARK) {
        ThemeGallery()
    }
}

@Preview(name = "Theme gallery — light", showBackground = true)
@Composable
private fun ThemeGalleryLightPreview() {
    SkeinTheme(mode = SkeinThemeMode.LIGHT) {
        ThemeGallery()
    }
}
