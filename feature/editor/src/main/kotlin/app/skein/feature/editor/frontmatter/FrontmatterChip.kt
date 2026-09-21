// bd `skein-6rr` (plan `E7.I3`): "the editor shows the frontmatter block
// collapsed to a one-line chip (`— id 0192… · 3 tags ▸`); expanding shows
// it as an editable block". The chip is a plain composable the caller
// (`SkeinEditor`) places above the text field; the field itself keeps
// owning the frontmatter block's actual text (via
// `LivePreviewTransformer` hiding it while collapsed) so there is exactly
// one source of truth for the raw bytes.

package app.skein.feature.editor.frontmatter

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.skein.feature.editor.FenceKind
import app.skein.feature.editor.Line

/** Public test tag: instrumented tests locate the collapse/expand affordance. */
public const val FRONTMATTER_CHIP_TEST_TAG: String = "app.skein.feature.editor.FrontmatterChip"

/** Public test tag: instrumented tests locate the protected-`id` rejection indicator. */
public const val FRONTMATTER_ID_PROTECTED_TEST_TAG: String = "app.skein.feature.editor.FrontmatterIdProtected"

/**
 * The collapsed/expanded toggle row for a document's frontmatter block.
 * Renders [label] when collapsed (`— id 0192… · 3 tags ▸`, see
 * [FrontmatterChipLabel]); when [expanded] shows a plain "▾ frontmatter"
 * affordance instead, since the block's actual lines are then visible
 * inline in the field below. [showIdProtectedHint] is the "subtle
 * indicator" bd `skein-6rr` calls for when an `id:` edit was just
 * rejected — see [app.skein.feature.editor.EditorState.idEditRejected].
 */
@Composable
public fun FrontmatterChip(
    label: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    showIdProtectedHint: Boolean = false,
) {
    Row(
        modifier =
            modifier
                .clickable(onClick = onToggle)
                .padding(horizontal = 4.dp, vertical = 2.dp)
                .testTag(FRONTMATTER_CHIP_TEST_TAG)
                .semantics { contentDescription = if (expanded) "Collapse frontmatter" else "Expand frontmatter" },
    ) {
        Text(
            text = if (expanded) "▾ frontmatter" else label,
            style = MaterialTheme.typography.labelMedium,
            color = LocalContentColor.current,
        )
        if (showIdProtectedHint) {
            Text(
                text = "  id is protected",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(FRONTMATTER_ID_PROTECTED_TEST_TAG),
            )
        }
    }
}

/**
 * Builds the collapsed chip's one-line summary (`— id 0192… · 3 tags ▸`)
 * straight from the raw source — no `:core:vault` `Frontmatter.parse`
 * dependency needed for a display-only label, and this runs on every
 * keystroke's `remember` recomposition so it stays a cheap line scan.
 */
internal object FrontmatterChipLabel {
    private const val ID_PREFIX_LEN = 8

    fun build(
        source: String,
        lines: List<Line>,
        endLineIndex: Int,
    ): String {
        var idValue: String? = null
        var tagCount: Int? = null
        for (i in 1 until endLineIndex) {
            val line = lines[i]
            if (line.fence != FenceKind.NONE) continue
            val text = line.text(source).trim()
            when {
                idValue == null && text.startsWith("id:") -> idValue = text.removePrefix("id:").trim()
                tagCount == null && text.startsWith("tags:") -> tagCount = countTags(text.removePrefix("tags:").trim())
            }
        }
        val shortId = idValue?.let { if (it.length > ID_PREFIX_LEN) it.take(ID_PREFIX_LEN) + "…" else it }
        return buildString {
            append("— ")
            if (shortId != null) append("id $shortId") else append("frontmatter")
            if (tagCount != null) append(" · $tagCount tags")
            append(" ▸")
        }
    }

    private fun countTags(rest: String): Int {
        if (rest.startsWith("[") && rest.endsWith("]")) {
            val inner = rest.substring(1, rest.length - 1).trim()
            return if (inner.isEmpty()) 0 else inner.split(",").size
        }
        return if (rest.isEmpty()) 0 else 1
    }
}
