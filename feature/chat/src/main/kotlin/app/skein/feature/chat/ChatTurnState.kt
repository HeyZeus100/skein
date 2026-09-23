// Owner scope addition, 2026-09-23 (a seam only, per the dispatch note on
// `bd show skein-6as`): model the assistant turn's UI state explicitly so a
// later bead (skein-cmoe, "thinking-orbs") can swap the placeholder
// composable [ChatTurnState.Thinking] renders as, without touching
// [SendPipeline] or [ChatViewModel]'s send loop — the state machine is the
// seam, not a specific animation.
package app.skein.feature.chat

import app.skein.core.model.InferenceException

/**
 * One assistant turn's lifecycle, from [ChatViewModel.send] to a terminal
 * state.
 *
 * [Thinking] spans from `send` until the *first* answer token arrives:
 * retrieval, prompt assembly and the engine's own queueing all live here.
 * If a reasoning model ever emits a `<think>...</think>` span ahead of its
 * answer, that span folds into [Thinking] too — [Thinking.elapsedMs] is the
 * only thing such a span may advance; it is never rendered as prose. This
 * bead models the states; the actual `<think>` tag-folding is left to a
 * follow-up (no engine in this bead's fakes emits one, and the locked
 * `Token`/`Segment` contracts carry no "reasoning" case to fold from yet).
 */
public sealed interface ChatTurnState {
    /** Send just happened; the pipeline hasn't started retrieval yet. */
    public data object Queued : ChatTurnState

    /** Retrieval + prompt assembly + engine queueing (and any reasoning span) — no visible answer text yet. */
    public data class Thinking(
        val elapsedMs: Long,
    ) : ChatTurnState

    /** The engine has produced at least one piece of visible answer text. */
    public data class Streaming(
        val text: String,
    ) : ChatTurnState

    /** The turn finished normally (`StopReason.EOS`/`LENGTH`/`STOP_STRING`). */
    public data object Done : ChatTurnState

    /** The turn was cancelled mid-stream; [partial] is what had streamed so far (still copyable). */
    public data class Interrupted(
        val partial: String,
    ) : ChatTurnState

    /** The turn failed with an [InferenceException] before or during streaming. */
    public data class Failed(
        val exception: InferenceException,
    ) : ChatTurnState
}

/** The sent user bubble's own state (owner scope addition), derived from the answer turn's [ChatTurnState]. */
public enum class SentMessageState {
    /** [ChatTurnState.Queued] / [ChatTurnState.Thinking] — the model hasn't produced any answer content yet. */
    QUEUED,

    /** [ChatTurnState.Streaming] — the engine picked the prompt up and is answering. */
    PICKED_UP,

    /** [ChatTurnState.Done] / [ChatTurnState.Interrupted] / [ChatTurnState.Failed] — the turn concluded. */
    SETTLED,
}

/** Maps an answer turn's [ChatTurnState] to the sent user bubble's [SentMessageState]. */
public fun ChatTurnState.toSentMessageState(): SentMessageState =
    when (this) {
        is ChatTurnState.Queued, is ChatTurnState.Thinking -> SentMessageState.QUEUED
        is ChatTurnState.Streaming -> SentMessageState.PICKED_UP
        is ChatTurnState.Done, is ChatTurnState.Interrupted, is ChatTurnState.Failed -> SentMessageState.SETTLED
    }
