package app.skein.core.model

/** User-led history windows. Stored SYSTEM messages become USER data during assembly. */
public object PromptHistory {
    /** A leading assistant reply has no retained question and cannot form an exchange. */
    public fun withoutLeadingReplies(history: List<Message>): List<Message> =
        history.dropWhile {
            it.role ==
                Role.ASSISTANT
        }

    /** Drops the oldest question and all replies to it, stopping at the next user/system message. */
    public fun dropOldestExchange(history: List<Message>): List<Message> =
        history.drop(1).dropWhile {
            it.role ==
                Role.ASSISTANT
        }
}
