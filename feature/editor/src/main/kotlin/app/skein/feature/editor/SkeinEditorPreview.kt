package app.skein.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Design-time preview of the Obsidian-style live-preview flip. Andriod
 * Studio's `@Preview` panel renders this without needing an emulator,
 * matching the "screenshot/preview" acceptance criterion of bd
 * `skein-03f`. The document intentionally exercises every construct the
 * transformer handles: headings, bold/italic, inline code, fenced code
 * (with a wikilink inside — it must render as literal text), lists,
 * blockquotes, and a bracketed wikilink at start of line.
 */
@Preview(showBackground = true, widthDp = 380, heightDp = 560)
@Composable
private fun SkeinEditor_LivePreview_CursorInsideHeading() {
    val state =
        remember {
            EditorState(initial = TextFieldValue(sampleDocument, selection = TextRange(3, 3)))
        }
    Surface(color = MaterialTheme.colorScheme.background) {
        SkeinEditor(state = state, modifier = Modifier.fillMaxWidth().padding(12.dp))
    }
}

@Preview(showBackground = true, widthDp = 380, heightDp = 560)
@Composable
private fun SkeinEditor_LivePreview_CursorOutsideAllBlocks() {
    val state =
        remember {
            // Selection past end-of-text → every line is inactive → the
            // whole document renders styled.
            EditorState(
                initial =
                    TextFieldValue(
                        text = sampleDocument,
                        selection = TextRange(sampleDocument.length, sampleDocument.length),
                    ),
            )
        }
    Surface(color = MaterialTheme.colorScheme.background) {
        SkeinEditor(state = state, modifier = Modifier.fillMaxWidth().padding(12.dp))
    }
}

@Preview(showBackground = true, widthDp = 380, heightDp = 320)
@Composable
private fun SkeinEditor_ReadOnlyRender_Snapshot() {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(12.dp).background(MaterialTheme.colorScheme.background)) {
            SkeinEditorReadOnlyPreview(text = sampleDocument)
        }
    }
}

private val sampleDocument =
    """
    # Skein — live preview

    This paragraph mixes *italic*, **bold**, and `inline code` on one line.

    - a bullet
    - another one
    1. ordered
    2. list

    > A block quote reminds the reader why this note exists.

    A link to [[Note Title|the note]] — click when the caret is elsewhere.

    ```kotlin
    // Wikilinks inside a fence stay literal: [[Not A Link]]
    val greeting = "hello"
    ```
    """.trimIndent()

/**
 * Compose Preview harness — placed in this file (not `SkeinEditor.kt`)
 * so the preview code doesn't creep into the release APK once
 * `@Preview` composables get stripped. `debugImplementation`'s
 * `compose-ui-tooling` supplies the preview panel; nothing here runs
 * off the design-time preview surface.
 */
@Composable
@Suppress("unused")
private fun __PreviewHarnessAnchor() = Unit
