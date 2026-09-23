// skein-6as (E6.I8). Assistant message rendering: Markdown via `core/markdown`
// (E7.I2) with inline `[N]` citation chips, driven by the same
// marker-offer that `CitationParser`/`SendPipeline` validated against
// (spec §8.4). Each citation marker becomes its own small, independently
// tappable inline node (`Text`'s `inlineContent`/`appendInlineContent`)
// rather than a styled span within one big `Text` — so a citation tap is an
// ordinary Compose click target (`onNodeWithTag(citationChipTestTag(marker))
// .performClick()`), not a raw-touch-coordinate guess against a
// `TextLayoutResult`. Everything between markers still goes through
// `MarkdownAst`/`MarkdownRenderer` so bold/italic/etc. render correctly.
package app.skein.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skein.core.markdown.MarkdownAst
import app.skein.core.markdown.render.MarkdownRenderer

/** Test tag for [MarkdownWithCitations]' rendered text (per message/bubble id, see `MessageList`). */
public fun assistantTextTestTag(messageId: String): String = "app.skein.feature.chat.AssistantBubble.$messageId"

/** Test tag for a tapped-open excerpt row under marker [marker] within [messageId]'s bubble. */
public fun citationExcerptTestTag(
    messageId: String,
    marker: Int,
): String = "app.skein.feature.chat.CitationExcerpt.$messageId.$marker"

private sealed interface TextOrMarker {
    data class Text(
        val text: String,
    ) : TextOrMarker

    data class Marker(
        val n: Int,
    ) : TextOrMarker
}

private val MARKER_REGEX = Regex("\\[(\\d+)]")

/** Splits [text] on `[N]` markers, in order. Grouped `[N, M]` markers are left as plain text (see `CitationParser`'s KDoc for that case; only single markers become interactive chips here). */
private fun splitOnCitationMarkers(text: String): List<TextOrMarker> {
    val result = mutableListOf<TextOrMarker>()
    var last = 0
    for (match in MARKER_REGEX.findAll(text)) {
        if (match.range.first > last) result += TextOrMarker.Text(text.substring(last, match.range.first))
        result += TextOrMarker.Marker(match.groupValues[1].toInt())
        last = match.range.last + 1
    }
    if (last < text.length) result += TextOrMarker.Text(text.substring(last))
    return result
}

/**
 * Renders [text] as Markdown, with every `[N]` present in [citations]
 * becoming its own tappable inline chip — a `[9]` not in the offer (the
 * allow-list `CitationParser` also enforces while streaming) renders as
 * plain text instead.
 *
 * A chip tap calls [onCitationTap]; per spec §8.4 the *second* tap on the
 * same marker additionally reveals that citation's excerpt below the text —
 * [isExcerptExpanded] and the caller's own tap-counting (`ChatViewModel
 * .onCitationTap`) implement the "first tap opens, second tap expands" rule.
 */
@Composable
public fun MarkdownWithCitations(
    messageId: String,
    text: String,
    citations: Map<Int, ChatCitation>,
    isExcerptExpanded: (Int) -> Boolean,
    onCitationTap: (ChatCitation) -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val segments = remember(text) { splitOnCitationMarkers(text) }

    val inlineContent =
        remember(segments, citations) {
            buildMap<String, InlineTextContent> {
                segments.forEachIndexed { index, segment ->
                    val citation = (segment as? TextOrMarker.Marker)?.n?.let(citations::get) ?: return@forEachIndexed
                    put(
                        inlineContentId(index),
                        InlineTextContent(
                            Placeholder(
                                width = INLINE_CHIP_WIDTH,
                                height = INLINE_CHIP_HEIGHT,
                                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                            ),
                        ) {
                            CitationChip(
                                marker = citation.marker,
                                modifier = Modifier.clickable { onCitationTap(citation) },
                            )
                        },
                    )
                }
            }
        }

    val annotated =
        remember(segments, citations) {
            buildAnnotatedString {
                segments.forEachIndexed { index, segment ->
                    when (segment) {
                        is TextOrMarker.Text ->
                            append(
                                MarkdownRenderer.toAnnotatedString(MarkdownAst.parse(segment.text)),
                            )
                        is TextOrMarker.Marker ->
                            if (segment.n in citations) {
                                appendInlineContent(inlineContentId(index), "[${segment.n}]")
                            } else {
                                append("[${segment.n}]")
                            }
                    }
                }
            }
        }

    Column(modifier = modifier) {
        Text(
            text = annotated,
            style = style,
            inlineContent = inlineContent,
            modifier = Modifier.testTag(assistantTextTestTag(messageId)),
        )
        for (citation in citations.values.sortedBy { it.marker }) {
            if (isExcerptExpanded(citation.marker)) {
                Surface(
                    tonalElevation = 2.dp,
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text(
                        text = "[${citation.marker}] ${citation.excerpt}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier =
                            Modifier
                                .testTag(citationExcerptTestTag(messageId, citation.marker))
                                .padding(8.dp),
                    )
                }
            }
        }
    }
}

private fun inlineContentId(index: Int): String = "citation-$index"

/**
 * One finished (persisted) assistant turn's bubble. [message.interrupted]
 * shows the "stopped" badge; the turn's text is never a read-only image —
 * it is a plain [Text], so the platform's ordinary long-press-to-select
 * affordance is available (PP-64's "stays copyable"). Not wrapped in a
 * `SelectionContainer`: under Robolectric, `SelectionContainer`'s
 * long-press magnifier touches a `Surface` that Robolectric's shadow
 * leaves null, crashing `ChatScreenTest` with an unrelated
 * `Magnifier$InternalPopupWindow` NPE — see that test's own note.
 */
@Composable
public fun AssistantBubble(
    message: ChatMessageUi,
    isExcerptExpanded: (Int) -> Boolean,
    onCitationTap: (ChatCitation) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            MarkdownWithCitations(
                messageId = message.id,
                text = message.displayText,
                citations = message.citations,
                isExcerptExpanded = isExcerptExpanded,
                onCitationTap = onCitationTap,
            )
            if (message.interrupted) {
                Text(
                    text = "interrupted — stopped",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(INTERRUPTED_BADGE_TEST_TAG),
                )
            }
        }
    }
}

/**
 * The live, not-yet-persisted assistant bubble — driven directly by
 * [ChatViewModel.turnState] rather than a [ChatMessageUi] (there is no
 * `Message` row for it yet). Uses the sentinel id [STREAMING_MESSAGE_ID] for
 * tap bookkeeping since a streaming turn has no message id until persisted.
 */
@Composable
public fun StreamingAssistantBubble(
    text: String,
    citations: Map<Int, ChatCitation>,
    isExcerptExpanded: (Int) -> Boolean,
    onCitationTap: (ChatCitation) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            MarkdownWithCitations(
                messageId = STREAMING_MESSAGE_ID,
                text = text,
                citations = citations,
                isExcerptExpanded = isExcerptExpanded,
                onCitationTap = onCitationTap,
            )
        }
    }
}

/** Spec §8.4's placeholder for [ChatTurnState.Queued]/[ChatTurnState.Thinking] — a later bead (skein-cmoe) swaps this for the thinking-orbs animation. */
@Composable
public fun ThinkingPlaceholder(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.testTag(THINKING_PLACEHOLDER_TEST_TAG),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = "thinking…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp),
        )
    }
}

public const val STREAMING_MESSAGE_ID: String = "streaming"
public const val THINKING_PLACEHOLDER_TEST_TAG: String = "app.skein.feature.chat.ThinkingPlaceholder"
public const val INTERRUPTED_BADGE_TEST_TAG: String = "app.skein.feature.chat.InterruptedBadge"

private val INLINE_CHIP_WIDTH = 32.sp
private val INLINE_CHIP_HEIGHT = 18.sp
