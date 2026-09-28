# Encrypted chat draft storage

AL-10a (`skein-xtov.24.10.1`) adds migration 011 and three repository operations:
`readDraft`, `writeDraft`, and `deleteDraft`. Drafts live only in `chat_drafts` in
the encrypted vault database. They are not Documents, emit no Documents events,
and do not update a chat's timestamp, transcript, ingest queue, chunks or edges.
The export service and DocumentsProvider continue to expose sent documents only.

An existing draft is keyed by chat ID with a foreign key to `documents(id)` and
`ON DELETE CASCADE`. Writes also require the owner to be a CHAT. A late write after
chat deletion fails with a fixed message and cannot resurrect the row. Legacy
noncanonical chat IDs may remain internal; this does not make them saveable
navigation IDs.

An unsaved new chat is keyed by both Space ID and draft ID. The session owner must
resolve a null navigation Space to the default Persona ID first. New draft keys
have no Documents row and do not create empty chats. Space deletion cleanup is a
separate lifecycle integration; this initial table does not foreign-key new drafts
to the separately managed Persona table. Navigation validates draft UUIDs before
putting them in T2. The repository treats these IDs as bound data.

Text includes unfinished wikilinks; selection offsets use Kotlin/Compose UTF-16
indices, may be reversed, and must be inside the text on write. Reads clamp stored
selection values to the text's current length. IME composition is not persisted.
Draft/key string representations are redacted; errors never include text or IDs.

Every mutation joins the same repository transaction and quiesce admission gate
as other writes. The send/controller integration must use one transaction for
new-chat creation (when needed), USER append, and draft deletion. A batch flush
wraps all draft writes in one transaction. Failure/cancellation rolls the whole
transaction back. Shared native/fake contract tests cover these boundaries,
including no document/search/ingest side effects, per-Space keys and late writes.
A native upgrade test covers version 10 to 11 and the actual SQL foreign key;
JVM compilation alone is not evidence that this native test executed.

This storage seam does not yet restore drafts in the running composer. AL-10b
owns session/epoch admission, debounce, ON_STOP and the bounded LOW-tier lock
flush. The session controller owns atomic first send and visible partial-answer
persistence. Until those are connected and verified, do not claim AL-10 complete
or imply that the existing composition-owned send job survives navigation.
