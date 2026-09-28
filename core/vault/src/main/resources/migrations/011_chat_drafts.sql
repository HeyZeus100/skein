-- AL-10 / D7 M9: vault-only unsent text. No Documents trigger or derived data.
-- Existing drafts cascade with their chat. New drafts are scoped by Space + draft key.
CREATE TABLE chat_drafts (
  kind TEXT NOT NULL CHECK (kind IN ('chat', 'new')),
  space_id TEXT NOT NULL,
  draft_id TEXT NOT NULL,
  chat_id TEXT REFERENCES documents(id) ON DELETE CASCADE,
  text TEXT NOT NULL,
  selection_start INTEGER NOT NULL CHECK (selection_start >= 0),
  selection_end INTEGER NOT NULL CHECK (selection_end >= 0),
  PRIMARY KEY (kind, space_id, draft_id),
  CHECK ((kind = 'chat' AND chat_id IS NOT NULL AND chat_id = draft_id AND space_id = '')
      OR (kind = 'new' AND chat_id IS NULL AND space_id <> ''))
);--;

CREATE INDEX idx_chat_drafts_chat ON chat_drafts(chat_id);--;
