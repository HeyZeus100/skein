package app.skein.core.vault.repository

internal object DraftSql {
    const val READ =
        "SELECT text, selection_start, selection_end FROM chat_drafts " +
            "WHERE kind = ? AND space_id = ? AND draft_id = ?"
    const val DELETE = "DELETE FROM chat_drafts WHERE kind = ? AND space_id = ? AND draft_id = ?"
    const val UPSERT =
        "INSERT INTO chat_drafts(kind, space_id, draft_id, chat_id, text, selection_start, selection_end) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT(kind, space_id, draft_id) DO UPDATE SET " +
            "text = excluded.text, selection_start = excluded.selection_start, selection_end = excluded.selection_end"
}
