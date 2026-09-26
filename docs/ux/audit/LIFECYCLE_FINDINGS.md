# Object lifecycle — storage findings

Bead `skein-xtov.10` (epic `skein-xtov`, the UX overhaul brief in
`docs/research/SKEIN_UI_UX_OVERHAUL_PROMPT.md`). This is input to
`docs/ux/OBJECT_LIFECYCLE_SPEC.md` (prompt §50). It records what create, rename,
update and delete **actually do underneath today**, read from the code at
`009cbb6`. It designs no UI and changes no storage.

Every claim carries `path:line` evidence. Paths are relative to the repo root.
"Today" means `009cbb6`. "Recommend" means a proposal for the spec author, not
something that exists.

---

## 0. Summary

| Object | Delete in backend? | Delete reachable from UI? | Rename in backend? | Rename reachable from UI? |
|---|---|---|---|---|
| Chat (`documents.kind='chat'`) | **Yes**: `VaultRepository.deleteDocument` (`core/model/src/main/kotlin/app/skein/core/model/Vault.kt:306`, impl `core/vault/src/main/kotlin/app/skein/core/vault/repository/VaultRepositoryImpl.kt:266-280`) | **No.** Zero production callers. Only the contract tests call it (`testing/src/main/kotlin/app/skein/testing/VaultRepositoryContractTest.kt:561,644`). | Only by accident, through `updateBody` (`Vault.kt:295-299`), which also rewrites the transcript. | Only by accident: a chat opened from the timeline renders in the **note editor** (see §3.6). |
| Message (`messages` row) | **No.** There is no delete or edit method. `feature/chat/src/main/kotlin/app/skein/feature/chat/SendPipeline.kt:94` says so explicitly. | No | No | No |
| Note (`documents.kind='note'`) | **Yes**, the same `deleteDocument` | **No** | Yes: `updateBody(id, title, body)` | Yes: inline header title edit, `feature/editor/src/main/kotlin/app/skein/feature/editor/notetab/NoteTabState.kt:122-132` |
| Attachment (`kind='attachment'` plus an encrypted file) | Yes, the same `deleteDocument`, which also deletes the blob | No | Via `updateBody` (unsafe for attachments, §5) | No attachment UI exists |
| Persona | Yes: `PersonaService.delete` (`core/vault/src/main/kotlin/app/skein/core/vault/persona/PersonaServiceImpl.kt:147-168`) | No. `feature/personas` is a `Placeholder.kt`. | Yes: `update` | No |
| Imported model | Yes: `ModelManager.delete` (`core/inference/src/main/kotlin/app/skein/core/inference/models/ModelManager.kt:164-176`) | **Yes**: `/models` → "Delete" with **no confirmation** (`feature/models/src/main/kotlin/app/skein/feature/models/ModelsScreen.kt:130`, wired at `app/src/main/kotlin/app/skein/MainActivity.kt:764-770`) | No | No |
| Collections, repositories, skills, saved workflows | These do not exist as stored objects (§8) | — | — | — |

What would happen if `deleteDocument` were wired to a button unchanged: it is
one hard-delete transaction. SQL foreign-key cascades and one trigger clean up
most row-level derived state (messages, chunks, the FTS and vector index rows,
revisions, the ingest queue row, export-stage rows), and the attachment blob is
deleted. **Graph edges are not cleaned up in either direction.** The UI is
not told to close tabs. Open chat views never learn the chat is gone. Copies of
the deleted text survive as citation excerpts in other chats and as FTS5 index
residue. There is no soft delete, no trash and no undo, and the vault is
excluded from every backup (§10). The delete is final and partial.

---

## 1. Ground truth: the storage graph

### 1.1 Everything is a `documents` row

Chats, notes, attachments and AI outputs all live in one table:
`documents(id, kind, title, body_md, created_at, updated_at, persona_id,
frontmatter, content_hash, mime_type, blob_size)`. See
`core/vault/src/main/resources/migrations/001_initial.sql:49-59` and `:154-156`.
`DocumentKind` is `note | chat | attachment | aiout`
(`core/model/src/main/kotlin/app/skein/core/model/Vault.kt:61-73`). The `id` is
a UUIDv7 and is mirrored into `frontmatter.id` on every write
(`VaultRepositoryImpl.kt:915-923`). The `title` is a plain column. It is never
derived after creation (§11).

### 1.2 What depends on a `documents` row, and what a delete does to it today

| Dependent | How it is linked | On `DELETE FROM documents` today | Evidence |
|---|---|---|---|
| `messages` | `chat_doc_id REFERENCES documents(id) ON DELETE CASCADE` | Deleted | `001_initial.sql:103-111` |
| `chunks` | `doc_id … ON DELETE CASCADE` | Deleted | `001_initial.sql:65-73` |
| `chunks_fts` (FTS5, external content) | Trigger `chunks_ad` issues the FTS `'delete'` command for each deleted chunk | Matches stop immediately. **Token residue stays in `chunks_fts_data`** until a segment merge (§10.3). | `001_initial.sql:77,166-169` |
| `chunks_vec` (sqlite-vec `vec0`) | The same `chunks_ad` trigger runs `DELETE FROM chunks_vec WHERE rowid = old.id` | Deleted. sqlite-vec 0.1.9 zeroes the vector bytes on delete. | `001_initial.sql:82,168`; `native/sqlite/third_party/sqlite-vec-0.1.9/sqlite-vec.c:8673-8695,9011-9012` |
| `ingest_queue` | `doc_id PRIMARY KEY … ON DELETE CASCADE` | Deleted | `001_initial.sql:134-138` |
| `document_revisions` | `document_id … ON DELETE CASCADE` | Deleted, **including revisions that chat citations still reference** | `003_document_revisions.sql:137-146`; contract test `VaultRepositoryContractTest.kt:638-656` |
| `export_stages` | `document_id … ON DELETE CASCADE` | The row is deleted. **The staged plaintext file is not** (§10.4). | `005_export_stages.sql:98-107` |
| `edges` | **No FK.** `src_id`/`dst_id` are free text. | **Untouched in both directions** | `001_initial.sql:84-93`; `003_document_revisions.sql:94` ("an unenforced pointer, exactly as `edges.src_id`/`dst_id` already are") |
| `entities` | No link. Rows are write-once and name-addressed. | Untouched. No production writer exists yet: `IngestPipelines.kt:50` passes `entities = null`. | `001_initial.sql:95-101`; `app/src/main/kotlin/app/skein/ingest/IngestPipelines.kt:50` |
| Attachment blob `<filesDir>/attachments/<id>` | Filesystem, keyed by id | `File(dir,id).delete()`, run **inside the write transaction, before COMMIT** | `VaultRepositoryImpl.kt:277`; `core/vault/src/main/kotlin/app/skein/core/vault/blob/FileAttachmentStore.kt:149-152` |
| Other chats' `messages.retrieved_chunks` JSON | `documentId` inside a JSON payload (SQLite cannot express this as an FK) | Untouched. Each excerpt is up to 1024 chars, and each assistant turn stores up to 8 of them. | `core/rag/src/main/kotlin/app/skein/core/rag/chat/CitationRecords.kt:63-90`; `core/model/src/main/kotlin/app/skein/core/model/CitationRecordJson.kt:62`; `docs/VAULT_FORMAT.md:124` |
| `documents.persona_id → personas(id)` | FK with no `ON DELETE` clause | Not applicable to a document delete. It matters for persona delete (§6). | `001_initial.sql:56` |

Foreign keys are enforced on every connection:
`PRAGMA foreign_keys = ON` at
`core/vault/src/main/kotlin/app/skein/core/vault/db/SkeinSQLiteDriver.kt:180-183`.
The cascade plus trigger path was confirmed empirically. With host
`sqlite3 3.51.0`, the same DDL and `DELETE FROM documents` left 0 `chunks`,
0 vector rows and 0 FTS matches, which shows an FK cascade fires `chunks_ad`.
Appendix A has the transcript. **No instrumented test on the device build
asserts this for chunks, FTS or vec.** The emulator lane only proves the cascade
for revisions and export stages
(`core/vault/src/androidTest/kotlin/app/skein/core/vault/db/migrations/MigratorInstrumentedTest.kt:232-233,263-264`).

### 1.3 Connection and notification topology (why cross-table cleanup is not atomic)

- Each service has its own connection from the pool: the repository writer
  (plus 2 readers), `IndexStoreImpl`, `PersonaServiceImpl` and
  `ModelRegistryImpl`
  (`app/src/main/kotlin/app/skein/vault/DeviceVaultOpener.kt:105-118`). Every
  service runs its own `BEGIN IMMEDIATE` under its own mutex. A document delete
  (repository connection) and an edge rewrite (index connection) therefore
  **cannot share a transaction** unless the edge DML runs on the repository's
  writer connection.
- Change buses are per service. The repository's `ChangeBus` carries only
  `Documents(docId)`, `Messages(chatDocId)` and `IngestQueue`
  (`core/vault/src/main/kotlin/app/skein/core/vault/repository/TableChange.kt:21-35`).
  The index has its own `IndexStore.observeChanges()` stream
  (`Vault.kt:618-643`). `deleteDocument` publishes **only**
  `TableChange.Documents(id)` (`VaultRepositoryImpl.kt:278`). It publishes no
  `Messages(id)` and no `IngestQueue`, even though both cascade. Personas have a
  third private bus (`PersonaServiceImpl.kt` header, lines 33-41).
- The JVM fake `InMemoryVaultRepository` ticks every flow on every change
  (`testing/src/main/kotlin/app/skein/testing/InMemoryVaultRepository.kt:538`),
  so a JVM test of "an open chat refreshes after delete" passes on the fake and
  fails on device. The fake's `deleteDocument`
  (`InMemoryVaultRepository.kt:225-236`) also does no edge cleanup, so it
  mirrors the real gap.

### 1.4 Ingest (how derived state is produced)

Any insert, or any update of `body_md`/`title`, on a non-attachment document
enqueues ingest through a trigger (`001_initial.sql:179-186`). `IngestScheduler`
drains the queue while the vault is unlocked
(`app/src/main/kotlin/app/skein/ingest/IngestScheduler.kt:1-60`). Per document,
`IngestPipeline.ingest` loads the document, chunks it, runs `replaceChunks`
(FTS rows follow by trigger), runs the link step, then vectors, then
`completeIngest`
(`core/rag/src/main/kotlin/app/skein/core/rag/ingest/IngestPipeline.kt:228-298`).
The link step is `EdgeUpserter.upsert` followed by `DanglingResolver.resolveFor`
(`IngestPipelines.kt:45-49`). No embedder is wired in production
(`IngestPipelines.kt:37`, and `ModelServices` builds
`RetrievalServiceImpl(… embedder = null)` at
`app/src/main/kotlin/app/skein/models/ModelServices.kt:258`), so `chunks_vec` is
empty on device today. The vector path is dormant but live in the schema.

---

## 2. Chats

### 2.1 Storage

- A chat is **a `documents` row with `kind='chat'`**. `/chat` creates it with
  `title = "Chat"`, `bodyMd = ""` and the shell's persona (null by default)
  (`feature/shell/src/main/kotlin/app/skein/feature/shell/nav/BuiltinCommands.kt:22-36`,
  title at line 33), then opens it as a pinned `TabKind.CHAT` tab.
- **Messages are rows in `messages`**, which is the source of truth. The chat's
  `body_md` is a **derived Markdown transcript**: `**role:** content` joined by
  blank lines (`VaultRepositoryImpl.kt:902-903`). It is re-materialized inside
  the same transaction on every `appendMessage` (`VaultRepositoryImpl.kt:369-391`).
  The transcript exists so that chats "index like notes": they are chunked,
  FTS-indexed and graph-linked, and **retrievable into other chats' RAG
  context**.
- `content_hash` is the revision hash of the transcript. A revision row is
  captured on every append, with an empty-string snapshot for chats to bound
  storage (`VaultRepositoryImpl.kt:386-388,535`; `Vault.kt:362-376`).
- `appendMessage` bumps `updated_at` (`VaultRepositoryImpl.kt:377`), so
  "recent chats" is just `observeTimeline(TimelineFilter(kinds = {CHAT}))`
  ordered by `updated_at DESC` (`VaultSql.kt:123-133`).
- Title: a column fixed at creation. **Nothing generates titles.** A repo-wide
  search finds no title generator. `ChatScreen` hard-codes the header text
  `"chat"` (`feature/chat/src/main/kotlin/app/skein/feature/chat/ChatScreen.kt:79`).
- Persona: `documents.persona_id` is recorded but **never read at send time**.
  `SendPipeline` asks `personaProvider()`, which the app wires to
  `personaService.default()`
  (`app/src/main/kotlin/app/skein/vault/DeviceVaultOpener.kt:137`;
  `SendPipeline.kt:213`).

### 2.2 Create / open / rename / update today

| Operation | Path | Evidence |
|---|---|---|
| Create | `/chat` → `createDocument(NewDocument(CHAT, "Chat", ""))` → `openPinned` | `BuiltinCommands.kt:22-36` |
| Open | Chat tab → `chatContent` → `ChatScreen(docId)` | `MainActivity.kt:554-603` |
| Open from timeline or search | **Opens as `TabKind.NOTE`**, which is the note editor, not `ChatScreen` | `feature/shell/src/main/kotlin/app/skein/feature/shell/SkeinApp.kt:226-231,270` |
| Update (append turn) | `SendPipeline.send` persists the USER turn, then the ASSISTANT turn, through `appendMessage` | `SendPipeline.kt:205-206,271-286` |
| Rename | **No API.** `updateBody(id, title, body)` needs the caller to supply the body. For a chat that means echoing back the transcript. It races `appendMessage`, although the next append re-materializes and self-heals. It also re-enqueues a full re-ingest, because the title is in `documents_au_ingest`'s column list. | `Vault.kt:294-299`; `001_initial.sql:183-186` |
| Search | `searchTitles` (prefix on `title`) and `searchBodies` (FTS over chunks) include chats. There is no kind filter. | `VaultSql.kt:90-117` |

### 2.3 Delete today

`deleteDocument(chatId)` would remove the documents row, cascade `messages`,
`chunks` (with FTS and vec rows), `document_revisions`, `ingest_queue` and
`export_stages`, and call `attachments.delete(chatId)`, a no-op for chats
(`VaultRepositoryImpl.kt:266-280`). **Nothing calls it.**

### 2.4 Dependents of a chat, and what should happen

| Dependent | Today on delete | Recommend | Why | Mechanism | Migration? |
|---|---|---|---|---|---|
| `documents` row, `messages`, frontmatter metadata | Removed | **Delete** | This is the object itself | Existing cascade | No |
| Transcript chunks, FTS rows, vec rows ("embeddings created solely for the chat") | Removed (FTS residue, §10.3) | **Delete** | They are derived from the chat only | Existing cascade plus trigger | No (FTS secure-delete, §10.3, is separate) |
| `document_revisions` of the chat | Removed | **Delete** | Chat snapshots are empty strings anyway (`VaultRepositoryImpl.kt:535`) | Existing cascade | No |
| `ingest_queue` row | Removed | **Delete** | — | Existing cascade | No |
| **Out-edges** (`src_id = chatId`). A chat body containing `[[file]]` from 📎 attach (`ChatViewModel.kt:286-298`) produces WIKILINK edges chat → note, plus TAG edges. | **Orphaned** | **Delete** | These edges are derived from the chat's own text | New DML in `deleteDocument`'s transaction (`DELETE FROM edges WHERE src_id = ?`), or a new `IndexStore` method (§13) | No if done in code; yes if done as a trigger |
| **In-edges** (`dst_id = chatId`). Rare, but `[[Chat]]` in a note resolves to the most recently updated "Chat" (`VaultSql.kt:82-84`). | **Orphaned**. The graph shows a ghost node labelled with the raw UUID (§4.4). | **Detach**: rewrite each WIKILINK in-edge to the sentinel `title:<lowercased title>` at weight 0.5 | Keeps the source note's link honest ("unresolved"). A future same-titled document picks it back up through `DanglingResolver.resolveFor` (`core/vault/src/main/kotlin/app/skein/core/vault/extract/DanglingResolver.kt:68-86`). | New code (the resolver's inverse, explicitly left undone at `DanglingResolver.kt:22-30`) | No if done in code |
| Notes and PDFs imported through 📎 in this chat | Separate documents, untouched | **Keep (detach)** | They are Knowledge objects in their own right and may be linked elsewhere | Nothing to do | — |
| Citations **in other chats** that cite this chat (transcripts are retrievable) | Excerpt kept in JSON; the chip label falls back to the excerpt; a tap opens a "Note not found" tab | **Mark missing** (keep the excerpt; render "source deleted"; no preview tab) | Deleting one chat must not rewrite another chat's history. Privacy caveat in §10.2. | UI: `ChatViewModel.toUi` already calls `getDocument` per citation (`ChatViewModel.kt:159-168`); add a `missing` flag | No |
| Excerpts **inside this chat's** messages (copies of cited notes) | Removed with `messages` | Delete | — | Existing cascade | No |
| Open chat tabs (primary pane, secondary pane, preview) | **Stay open.** `observeMessages` never ticks because no `Messages(id)` is published (§1.3), so the transcript stays on screen. The **next send crashes** (§12.1). | **Close every tab** for the id in both panes | "Do not leave an orphaned tab" (prompt §29) | UI: a new `TabsState.closeDocument(docId)` (`feature/shell/src/main/kotlin/app/skein/feature/shell/tabs/TabsState.kt:100-115` only closes by `TabId`) | No |
| In-flight generation for this chat | Would `appendMessage` the assistant turn to a missing document → `NoSuchElementException` | **Refuse delete while that chat is generating**, or cancel and wait for the flow to finish before deleting | — | UI gate on `ChatViewModel.isGenerating` (`ChatViewModel.kt:94-100`) | No |
| `SendPipeline.lastOutcome` (session-wide, in memory): the last turn's retrieved texts | Kept. It is also shown in **other** chats' context panels, because the collector does not filter by `chatDocId` (`ChatViewModel.kt:142-147`). | **Clear** when `lastOutcome.chatDocId` is the deleted id | Stale in-memory retrieved text | UI/state, S | No |
| Generated title | None exists | — | — | — | — |
| Context bindings / attached knowledge | None exist. The only "binding" is `[[title]]` text in a message. | — | — | — | — |
| Retrieval caches | None persistent. `ContextBudget` caches token counts by digest only (`core/inference/src/main/kotlin/app/skein/core/inference/ContextBudget.kt:44-71`). | Nothing | — | — | — |
| Inference KV cache | Cleared at the **start** of the next request (`inference-service/src/main/kotlin/app/skein/inference/service/InferenceService.kt:484-494`) and zeroed on free | Nothing new, or accept that the last turn's tokens live in the isolated process until the next request, unload or lock | Low severity | — | — |
| WorkManager | No per-document jobs. Ingest is one unique pass; the queue row cascades. Race in §12.2. | Guard the race | — | S code | No |
| Export staging | A chat can be PDF-exported, because it opens in the note editor (§3.6). The stage row cascades; the file does not. | **Delete** the staged file | §10.4 | S code | No |
| SAF `DocumentsProvider` (chats are listed as `Notes/<title>.md`) | No `notifyChange` on delete | Notify | Stale DocumentsUI listing | S (`core/vault/src/main/kotlin/app/skein/core/vault/provider/VaultDocumentsProvider.kt:246-250`) | No |

---

## 3. Messages

### 3.1 Storage

`messages(id UUIDv7, chat_doc_id FK CASCADE, role 'system'|'user'|'assistant',
content_md, model_id, retrieved_chunks JSON, created_at)`
(`001_initial.sql:103-111`; roles at
`core/model/src/main/kotlin/app/skein/core/model/Inference.kt:89-95`).
`retrieved_chunks` holds citation-record-v1. That is **every** retrieved item
offered to the model for the turn (up to `RETRIEVAL_K = 8`,
`SendPipeline.kt:71`), not only the cited ones, each with `documentId`,
`revisionHash`, `locator` and an `excerpt` of up to 1024 chars
(`CitationRecords.kt:63-90`). An interrupted turn is stored with a suffix
marker, `<!-- skein:interrupted … -->` (`SendPipeline.kt:82`).

### 3.2 Create / update / delete today

- Create: only `appendMessage` (`Vault.kt:336-339`). The USER turn is persisted
  **before** generation. A failed generation leaves the USER row with no reply,
  and **retry re-sends, which appends a duplicate USER row**
  (`SendPipeline.kt:196-198,205-206`; `ChatViewModel.kt:251-254`). This is the
  one place a user would reasonably want to delete a message, and it has no
  support.
- Update / edit / regenerate: **none**.
- Delete: **none**. `SendPipeline.kt:94` records that "the locked
  `VaultRepository` contract has no delete-message method to un-persist one
  after the fact." Messages leave only through the chat's cascade.

### 3.3 What a single-message delete would have to do (if ever wanted)

1. `DELETE FROM messages WHERE id = ?`.
2. Re-materialize the chat transcript in the same transaction. Reuse the tail
   of `appendMessage` (`VaultRepositoryImpl.kt:373-391`).
3. That changes the chat's `content_hash`, which captures a new revision, and
   the trigger re-ingests. Any citation **into this chat** from another chat
   flips to "source changed". That is correct behaviour.
4. Publish `Messages(chatId)` and `Documents(chatId)`.

This is M-size: a new `VaultRepository` method, the fake, contract tests, and a
UI affordance. No migration. **Recommend: messages are immutable in v1 and are
deleted only with their chat.** Revisit per-message delete together with
"regenerate" and "edit and resend".

---

## 4. Notes

### 4.1 Storage

A `documents` row with `kind='note'`. `body_md` is Markdown. `frontmatter` is
JSON, and the editor shows it as a `---` block
(`NoteTabState.kt:193-213`). The title is a column: created through
`/new note [title]` (`BuiltinCommands.kt:53-68`, where a blank title becomes
`"Untitled"`), by a wikilink open-or-create (`NoteTabState.kt:299-309`), by the
wikilink autocomplete "Create" row (`NoteTabState.kt:280-282`), or by import,
which derives the title from frontmatter `title`, then the first `# heading`,
then the filename (`core/vault/src/main/kotlin/app/skein/core/vault/transfer/ImportServiceImpl.kt:254-333`).

### 4.2 Create / edit / rename today

| Operation | Path | Evidence |
|---|---|---|
| Create | `/new note`, a wikilink click on an unresolved title, autocomplete "Create", import | See above |
| Edit | Debounced autosave (500 ms) → `updateFrontmatter` (only if a frontmatter block exists) plus `updateBody` | `NoteTabState.kt:226-237` |
| Rename | Header inline edit → `updateBody(docId, newTitle, currentBody)` | `NoteTabState.kt:122-132` |
| Search | `searchTitles` / `searchBodies` from the command bar. Results are fetched once per query, so a result list stays stale if a note is deleted while it is showing. | `feature/shell/src/main/kotlin/app/skein/feature/shell/nav/CommandBarState.kt:57-90` |

### 4.3 Delete today

The same unreached `deleteDocument`. There is no UI entry point. The note
tab's share menu (`feature/editor/src/main/kotlin/app/skein/feature/editor/notetab/NoteTab.kt:187-198`)
and the tab context menu, which has only Pin, Close, Close others and Split
(`feature/shell/src/main/kotlin/app/skein/feature/shell/tabs/TabContextMenu.kt:31-57`),
are the natural hosts for one.

### 4.4 Dependents of a note, and what should happen

| Dependent | Today on delete | Recommend | Why | Mechanism | Migration? |
|---|---|---|---|---|---|
| Row, chunks, FTS, vec, revisions, ingest queue | Removed by cascade (FTS residue, §10.3) | **Delete** | — | Existing | No |
| Revisions **cited by chats** | Removed with the note, so `getRevision` returns null and the diff view's left side is gone | **Delete** (keep today's behaviour) | Privacy: the user asked for the note to go. The design already anticipates `current: RevisionRef? // null when document deleted` (`docs/design/POST_REVIEW_RESOLUTIONS.md:246`). | Existing cascade | No |
| **Out-edges** `src_id = noteId` (WIKILINK, TAG, CITE, and ENTITY once wired) | **Orphaned.** They keep steering retrieval: `GraphRecall` expands 2 hops through any node, including a dead one (`core/rag/src/main/kotlin/app/skein/core/rag/recall/GraphRecall.kt:17,77`), and PPR uses the same graph (`core/rag/src/main/kotlin/app/skein/core/rag/rank/PprRanker.kt:213-215`). So a deleted note's link structure keeps connecting its neighbours. | **Delete** | These edges are derived from the note's own text | New DML (§13) | No if done in code |
| **In-edges** `dst_id = noteId` (other notes' resolved `[[Title]]`) | **Orphaned, pointing at a dead UUID.** The graph renders a ghost node labelled with the raw UUID (`feature/graph/src/main/kotlin/app/skein/feature/graph/GraphState.kt:118-138`; label fallback at `GraphModels.kt:65-71`). A tap opens a "Note not found" tab (`MainActivity.kt:734-740` → `NoteTabState.kt:196-200`). If a new note later takes the same title, these links **are not** re-attached, because `DanglingResolver` only rewrites `title:` sentinels (`DanglingResolver.kt:68-86`). | **Detach**: rewrite WIKILINK in-edges to `title:<lowercased old title>`, weight 0.5 | Restores "unresolved link" semantics and lets a recreated note regain its backlinks | New code, the inverse `DanglingResolver.kt:22-30` explicitly defers | No if done in code |
| `[[Title]]` **text** in other notes | Unchanged | **Mark missing**: leave the text and render it as an unresolved link. Do not rewrite other notes' bodies. | Rewriting would change their revision hashes and their citations | Nothing (the renderer decides) | No |
| Clicking such a link later | `findByTitle` misses, so it **silently creates a new empty note** with that title (`NoteTabState.kt:299-309`) | Spec decision: keep the Obsidian-style open-or-create, but the confirmation copy should mention the linking notes | This is a resurrection-by-title path | UI copy | No |
| Backlinks drawer of *another* note that this note linked to | The deleted source is filtered out (`getDocument` → `mapNotNull`, `feature/editor/src/main/kotlin/app/skein/feature/editor/backlinks/BacklinksState.kt:180-184`) and it re-queries on the timeline tick (`BacklinksState.kt:160-167`) | Keep | Already correct | — | — |
| Citations in chats (`messages.retrieved_chunks`) | Excerpt kept. The chip label falls back to the excerpt; a tap opens a "Note not found" preview (`ChatViewModel.kt:159-168,265-270`). `revisionMatches` returns false (`VaultRepositoryContractTest.kt:555-565`) but is not wired to any UI. | **Mark missing**: a "source deleted" chip that shows the excerpt inline and opens no tab | "Avoid broken citation targets" (prompt §31) without rewriting chat history | UI, S | No |
| Excerpt text of the note inside chats | Kept (`skein-koda`, `docs/VAULT_FORMAT.md:124`; `docs/PRIVACY.md:55`) | Keep in v1, **disclose in the confirmation copy**, and track "purge quotes of this note" as a follow-up | A user-visible purge is its own feature | M, privacy/storage epic | No |
| PDF-derived note (`frontmatter.source = <attachmentId>`) → attachment | The attachment stays; its CITE edge from this note is orphaned | Delete the CITE out-edge (covered above). **Keep the attachment**, or let the spec offer "also delete the original PDF". | The attachment is separate user data | — | No |
| Open tabs, including the secondary pane and a preview | Stay open. After a restore they show "Note not found". A pending autosave then fails with an `ERROR` status (caught, `EditorState.kt:216-230`). An **inline title edit crashes**: `onTitleChange` launches `updateBody` with no catch (`NoteTabState.kt:122-132`), and `updateBody` throws `NoSuchElementException` (`VaultRepositoryImpl.kt:825-826`). | **Close every tab** for the id before deleting | No orphaned tab, no dead route | UI, S | No |
| Graph overlay centred on the note | Stays; it re-centres on a ghost | Close the overlay | — | UI, S (`MainActivity.kt:729-750`) | No |
| Timeline, history, search | The timeline refreshes (Documents tick). Title and body search stop matching immediately because chunks cascade. Command-bar results already on screen stay stale. | Re-run the active query on delete | — | UI, S | No |
| Collections, repositories, active context | None exist (§8) | — | — | — | — |
| Export staging | See §10.4 | Delete the file | — | S | No |

---

## 5. Attachments and imported files

**Storage.** An attachment is a `documents` row with `kind='attachment'`,
`body_md = NULL`, `mime_type`, `blob_size`, and `content_hash` =
SHA-256 of the plaintext (`VaultSql.kt:74-76`; `VaultRepositoryImpl.kt:558-601`).
Its bytes live in `<filesDir>/attachments/<id>` as a SKAT v2 container. The
per-write key is HKDF(master, id, random salt), and the salt is stored in the
file header. No key is persisted anywhere
(`FileAttachmentStore.kt:1-60`; `007_drop_attachment_master_key.sql:20-34`).

**Import paths.** `importPdf` creates an attachment **plus** a NOTE with
`source: <attachmentId>` frontmatter; ingest turns that into a CITE edge
(`ImportServiceImpl.kt:138-171`; `core/vault/src/main/kotlin/app/skein/core/vault/extract/EdgeUpserter.kt:103-124`).
`importText` (Markdown, plain text, code) creates a plain NOTE, and **the
source file is not retained** (`ImportServiceImpl.kt:123-136,190-215,254-300`).
`importImage` throws `UnsupportedOperationException` (`ImportServiceImpl.kt:176-185`).
So "imported files" are notes, plus attachments for PDFs only.

**Create / rename / delete today.**
- Create: `createAttachment` writes the blob **outside** the transaction, then
  inserts the row (`VaultRepositoryImpl.kt:562-569`). A failed insert leaves an
  orphan blob, and the startup orphan sweep that
  `docs/design/ATTACHMENT_ENCRYPTION.md` §2.7 designs is **not implemented**.
  No sweep code exists under `core/vault/.../blob` or `app/`.
- Rename: through `updateBody`, which is unsafe. It **writes `body_md` onto an
  attachment row and replaces the blob's SHA-256 with a hash of that text**
  (`VaultRepositoryImpl.kt:215-224`). The timeline opens attachments in the
  note editor (§3.6), so an edit can reach this path.
- Delete: `deleteDocument` removes the row, then calls `attachments.delete(id)`
  **before COMMIT** (`VaultRepositoryImpl.kt:267-279`). If COMMIT then fails, the
  row survives and its bytes are gone: `openAttachment` → `NotFound`.
  **Recommend moving the blob delete after the commit** (S, code only). A crash
  between COMMIT and the file delete then leaves an orphan blob instead, which
  the §2.7 orphan sweep would collect.

**Dependents on delete.**

| Dependent | Recommend |
|---|---|
| Encrypted blob file | Delete after COMMIT |
| Derived NOTE with `source: <id>` | **Detach and mark missing**: delete the CITE in-edge (`dst_id = attachmentId`). Leave the note and its `source:` frontmatter; rewriting frontmatter would change its revision hash. The UI shows "original file deleted". |
| Wikilinks `[[file name]]` (📎 inserts `[[displayName]]`, `ChatViewModel.kt:297`) | These resolve by title to the derived note, not the attachment. Nothing to do. |

---

## 6. Personas

**Storage.** `personas(id, name, system_prompt, default_model, created_at)`
(`001_initial.sql:115-121`). `documents.persona_id` is an FK with no action
(`001_initial.sql:56`).

**Today.** `create` / `update` / `delete` / `default` all exist
(`core/model/src/main/kotlin/app/skein/core/model/Personas.kt:31-50`). `delete`
nulls every `documents.persona_id` that references the persona, then deletes it,
all in one transaction, and **refuses to delete the last persona**
(`PersonaServiceImpl.kt:147-168`;
`core/vault/src/main/kotlin/app/skein/core/vault/persona/PersonaSql.kt:49-54`).
There is no UI (`feature/personas/src/main/kotlin/app/skein/feature/personas/Placeholder.kt`).

**Consequences the spec must state.**

1. **Scope promotion.** Retrieval admits documents whose `persona_id` is null to
   every persona (`PprRanker.kt:97-101,166-170`). Deleting a persona therefore
   makes its documents visible to all personas' retrieval. That is a
   privacy-relevant semantic, not a cosmetic one. The alternative is to
   reassign them to the default persona: an S code change in
   `PersonaSql.NULL_DOCUMENTS_PERSONA`, but the contract KDoc
   (`Personas.kt:45`) says "set to NULL", so this is a contract change.
2. **"Default" can move.** `default()` is the first-created surviving persona
   (`PersonaSql.kt:33-35`), and `SendPipeline` uses it for every chat
   (`DeviceVaultOpener.kt:137`). Deleting the first persona silently changes the
   system prompt and retrieval scope for every chat.
3. **No change signal to documents.** The nulling runs on the persona connection
   and never ticks the repository's `ChangeBus`, so the timeline and its
   persona filter stay stale until the next document write. The filter bar
   already tolerates a missing selected id
   (`feature/timeline/src/main/kotlin/app/skein/feature/timeline/FilterBar.kt:82`).
4. `personas.default_model` can name a model that has been deleted. Nothing
   reconciles it.
5. Messages record no persona, so history does not depend on personas.

---

## 7. Imported (custom) models

**Storage.** One `models` row (`001_initial.sql:123-132` plus columns from
`009_model_origin.sql:60-68`), files under `<filesDir>/models/<id>/`, and the
default-model pointer in the SharedPreferences file `ui_prefs`
(`core/model/src/main/kotlin/app/skein/core/model/ModelRegistry.kt:20-26`).

**Delete today** is `ModelManager.delete`
(`ModelManager.kt:164-176`):
1. Refuses if the model is loaded.
2. `ImmutableModelStore.delete` refuses while a handle is open, then deletes the
   directory tree
   (`core/inference/src/main/kotlin/app/skein/core/inference/models/ImmutableModelStore.kt:385-392`).
3. `registry.delete` (`core/vault/src/main/kotlin/app/skein/core/vault/models/ModelRegistryImpl.kt:93-101`).
4. Clears the default if it pointed at this model.

The files go before the row, so a crash in between leaves a row whose files are
missing. `reconcilePaths` reports that case rather than deregistering
(`ModelManager.kt:185-200`).

**UI.** The `/models` overlay has a filled-style "Delete" `Button` with **no
confirmation** (`ModelsScreen.kt:130`). The `DeleteOutcome` is **ignored** at
`MainActivity.kt:764-770`, so a refused delete does nothing, visibly or
otherwise. After the default is deleted, chat tabs fall back to "No model yet"
(`MainActivity.kt:567-586`).

**Dependents.**

| Dependent | Recommend |
|---|---|
| `messages.model_id` | Keep. It is historical provenance. |
| `personas.default_model` | Mark missing and fall back to the global default |
| Default pointer | Already cleared |

Model files are plaintext GGUF, excluded from backup (`docs/BACKUP_EXCLUSIONS.md:40-47`).
Secure wipe does not matter for public weights.

---

## 8. Collections, repositories, skills, saved workflows

None of these exist as stored objects. No table, no repository method, no file
format. Evidence: the full table list in migrations 001-009 (§1.2), and a
repo-wide search of `app/src/main`, `core/*/src/main` and `feature/*/src/main`
for `collection|workflow|archive|skill`, which finds only comments. The only
related surface is the interface-only `core/agent/src/main/kotlin/app/skein/core/agent/tools/VaultTools.kt`,
which already assumes **hard** deletes ("does not exist (or was
hard-deleted)", `VaultTools.kt:229`). Skills are a design document
(`docs/design/SKILL_GUARDRAILS.md`). Any lifecycle UI for these objects is
**L-size** new storage work that belongs to its own epic.

---

## 9. Timeline, tabs, routes: where a deleted id can linger

| Holder | Persistence | After delete today | Needed |
|---|---|---|---|
| `TabsState` (primary and secondary panes) | `rememberSaveable`: survives configuration change and process death, not a cold start (`TabsState.kt:151-180`). Each `Tab` snapshots `title`. | Tab stays, and is restored after process death | `closeDocument(docId)` across both panes. When restoring saved tabs, drop ids that `getDocument` reports missing. |
| Graph overlay `graphDocId` | In-memory in `MainActivity` (`MainActivity.kt:386`) | Stays | Clear it if it names the deleted id |
| `SendPipeline.lastOutcome` | Session memory | Stays, and leaks across chats | Clear, and filter by `chatDocId` |
| `CommandBarState.results` | Memory | Stale | Re-query |
| Timeline (`observeTimeline`) | Live | Refreshes | — |
| SAF provider listing | Live query on demand | No change notification | `notifyChange` |

Routing hazard found along the way: **timeline taps, graph taps and search
results always open `TabKind.NOTE`, whatever the document's kind**
(`SkeinApp.kt:226-241,270`; `MainActivity.kt:734-745`). A chat opened that way
lands in the note editor. Editing it writes to the chat's `body_md` (the
derived transcript) and leaves `messages` alone, so the edit is **silently
discarded on the next turn** when `appendMessage` re-materializes. Renaming it
works, by accident. Routing by `Document.kind` is a prerequisite for any chat
lifecycle UI (S, UI-only).

---

## 10. Persistence guarantees and secure delete

### 10.1 Durability

- `deleteDocument` is a single `BEGIN IMMEDIATE … COMMIT` on the writer
  connection (`VaultRepositoryImpl.kt:773-791`) in WAL mode
  (`SkeinSQLiteDriver.kt:182`). No `PRAGMA synchronous` override exists in
  `core/vault/src/main` or `native/sqlite/CMakeLists.txt`, so SQLite's default
  (FULL) applies and a committed delete survives a crash or power loss.
- Lock closes the pool after `PRAGMA wal_checkpoint(TRUNCATE)`
  (`core/vault/src/main/kotlin/app/skein/core/vault/lifecycle/VaultLifecycle.kt:348-366`).
  A delete persists across lock and unlock with nothing to replay.
- **Paths that bring a deleted object back:**
  1. **Wikilink open-or-create** makes a *new* document (new id) with the old
     title (`NoteTabState.kt:299-309`).
  2. **Re-importing an exported `.md`** whose frontmatter `id` no longer
     collides reuses the **same id** (`ImportServiceImpl.kt:268-289`). Chat
     citations with that `documentId` then resolve again. `revisionMatches` is
     true only if the content is byte-identical.
  3. **The ingest race** (§12.2) re-creates edges, never documents.
  4. **Saved tab state** (§9) restores a tab, never data.

### 10.2 Copies that survive a delete (privacy posture)

| Copy | Where | Survives? | Evidence |
|---|---|---|---|
| Citation excerpts of the deleted document in other chats | `messages.retrieved_chunks` | **Yes**: up to 8 × 1024 chars per assistant turn that retrieved it, whether or not the answer cited it | `CitationRecords.kt:63-90`; `docs/VAULT_FORMAT.md:124` (`skein-koda`) |
| FTS5 token index | `chunks_fts_data` segments | **Yes, until segment merge.** This also covers every *earlier version* of every edited note, because `replaceChunks` deletes the old chunks the same way. | §10.3 |
| Deleted B-tree cells | `vault.db` pages | No. `SQLITE_SECURE_DELETE` is compiled in and zeroes freed content. | `native/sqlite/CMakeLists.txt:450` |
| WAL frames | `vault.db-wal` | Ciphertext until checkpoint. TRUNCATE on lock; a checkpoint that fails leaves residue, logged only (`VaultLifecycle.kt:340-347`). | — |
| Vector bytes | `chunks_vec` chunk blobs | No: zeroed by sqlite-vec 0.1.9 (and empty in production today) | `sqlite-vec.c:8673-8695` |
| Attachment ciphertext | Flash blocks of the unlinked file | Possibly. `File.delete()` unlinks and does not shred the key. The per-file key derives from the master plus a salt stored in the same file, so anyone with the master key *and* raw block access could recover it. Overwriting is meaningless on flash anyway. Real crypto-shredding would need random per-file keys stored in the DB, which is the design migration 007 deliberately dropped. | `FileAttachmentStore.kt:40-60,149-152`; `007_drop_attachment_master_key.sql:20-34` |
| Staged PDF plaintext | `cache/staging_export/` | **Beyond its 10-minute bound**, until the next lock or boot (§10.4) | — |
| OS or cloud backup | — | Not applicable. The whole vault, attachments, keys and staging are excluded from cloud **and** device-transfer backup. | `docs/BACKUP_EXCLUSIONS.md:34-47` |
| Earlier user exports (zip, `.md`, SAF copies) | Outside the vault | Yes, by definition; future `exportVaultZip` calls skip the deleted document (`ExportServiceImpl.kt:156-162` enumerates live rows only) | — |

Everything that survives *inside* `vault.db` is SQLCipher ciphertext at rest.
The residue matters only against an adversary who later obtains the vault key
(compelled unlock, or a passphrase export plus a disk image,
`feature/shell/src/main/kotlin/app/skein/feature/shell/auth/VaultRestoreState.kt:1-15`).
Skein's privacy posture treats that adversary as in scope, so the spec should
not promise that "delete" erases every trace unless the recommended fixes land.

### 10.3 FTS5 residue: verified, with a fix

Experiment (Appendix A): after `DELETE FROM documents` cascades a chunk
containing "walrus", `MATCH 'walrus'` returns 0 and `fts5vocab` shows 0 rows,
**but the literal token is still inside 2 `chunks_fts_data` blocks**. Setting
the FTS5 option `INSERT INTO chunks_fts(chunks_fts, rank) VALUES('secure-delete', 1)`
leaves 0 blocks containing it after a later delete. A one-time
`INSERT INTO chunks_fts(chunks_fts) VALUES('optimize')` purges residue left
behind before the option was set. The device build is SQLite 3.53.3
(`native/sqlite/amalgamation/sqlite3.h:149`), which supports both.
Recommendation: persist the option through migration 010 (DML on the virtual
table's config; it creates no schema object, so the integrity catalogue in
`docs/VAULT_FORMAT.md:499` is unaffected) and run one `optimize`. This is
**storage work with migration risk**: the JVM fake driver executes no DDL or
DML (`009_model_origin.sql:36-40`), so it has to be proven on the emulator
lane. The experiment above used the host's sqlite 3.51.0, not the SQLCipher
build.

### 10.4 Staged export plaintext outlives a deleted document

`005_export_stages.sql:66-70` says the file of a cascaded stage row "is still
removed, because `sweepAll` sweeps the staging DIRECTORY". That holds only for
the lock sweep and the boot sweep. The **timed** sweep keys on the row:
`sweepIfExpired` returns `UNKNOWN` when `getStage` finds nothing and deletes
nothing
(`core/vault/src/main/kotlin/app/skein/core/vault/export/stage/StagedPlaintextSweep.kt:54-61`).
Deleting a document within 10 minutes of a PDF export therefore leaves its
plaintext on disk until the next lock or reboot.

Fix, S and code only, choose one:
- `sweepIfExpired` purges `<stagingDir>/<stageId>*` when the row is missing.
  The file name is prefixed with the stage id (`005_export_stages.sql:53-55`).
- `deleteDocument` reads the document's stage paths before the delete and
  removes those files after COMMIT.

---

## 11. Rename semantics

- **The title is stored, not derived.** `documents.title` is set at creation
  and changed only by `updateBody` (`VaultSql.kt:135-136`). Import derives a
  title once (frontmatter `title`, then `# heading`, then filename) and never
  again (`ImportServiceImpl.kt:325-333`).
- **The title is not part of the revision hash.** A rename leaves every citation
  into the document "live" (`VaultRepositoryImpl.kt:210-214`).
- **A rename triggers a full re-ingest**, because `documents_au_ingest` watches
  `title` (`001_initial.sql:183-186`). The useful effect:
  `DanglingResolver.resolveFor(doc)` resolves sentinels pointing at the *new*
  title (`IngestPipelines.kt:46-49`).
- **Wikilinks are by title and break on rename.** Other notes' `[[Old Title]]`
  text is not rewritten. Their already-resolved edges keep pointing at the id,
  so backlinks keep working, until those source notes are next re-ingested.
  At that point `EdgeUpserter` calls `findByTitle("Old Title")`, which misses,
  and the edge becomes a `title:old title` sentinel
  (`EdgeUpserter.kt:139-151`). A click on `[[Old Title]]` **creates a new empty
  note** (`NoteTabState.kt:299-309`). The link is broken both in the UI and,
  eventually, in the graph.
- **What is id-based (stable across rename):** `documents.id`, frontmatter
  `id`, chat citations (`documentId`), CITE edges (`source:` frontmatter), tabs
  (`Tab.docId`), and resolved edge rows. **What is title-based:** wikilink text,
  `findByTitle`, the `title:` sentinel, and the backlinks excerpt search
  (`BacklinksState.kt:178`).
- **Titles are not unique.** `findByTitle` returns the most recently updated
  match (`VaultSql.kt:82-84`). Renaming onto an existing title makes every
  `[[Title]]` resolve ambiguously. Recommend a collision warning (S, UI).
- **Frontmatter `title` drift.** Imported notes carry `frontmatter.title`
  (`ImportServiceImpl.kt:312-316`), and a rename does not update it. Export then
  writes the old title inside the file under a new filename (`ExportServiceImpl.kt:105,153-154`),
  and re-import resurrects the old title. The spec author must choose:
  - (a) update `frontmatter.title` on rename. This changes the revision hash,
    so citations into the note show "source changed".
  - (b) drop the `title` key on the first rename.
  - (c) ignore it and document the drift.
- **Open tab titles are snapshots** (`Tab.title`, `feature/shell/src/main/kotlin/app/skein/feature/shell/tabs/Tab.kt:48-54`),
  with no retitle API. A rename leaves stale tab labels until the tab is
  reopened (S, UI).
- **Chats:** the minimum correct rename is a dedicated
  `renameDocument(id, title)` that runs
  `UPDATE documents SET title = ?, updated_at = ? WHERE id = ?`. It does not
  echo the body back, so it cannot race `appendMessage`. It is S-size (core/model
  contract, SQL impl, fake, contract test) with no migration. Whether a rename
  should bump `updated_at`, and so move the chat to the top of history, is a
  spec decision.
- **Rename-with-link-update** (rewriting `[[Old]]` → `[[New]]` in the linking
  notes, as Obsidian does) is M-size. It edits other documents and changes their
  revision hashes. Recommend deferring it, and disclosing in the rename UI:
  "N notes link to this title; those links will show as missing".

---

## 12. Races and hazards found

### 12.1 Sending into a deleted chat crashes

`ChatViewModel.send` catches only `InferenceException` (`ChatViewModel.kt:231`).
`appendMessage` on a missing document throws `NoSuchElementException`
(`VaultRepositoryImpl.kt:351,825-826`), inside a `rememberCoroutineScope`
launch (`ChatScreen.kt:59-71`), and that is uncaught. Two triggers:
- a chat tab left open in either pane after its chat is deleted;
- an in-flight generation whose chat is deleted before the assistant turn is
  persisted (`SendPipeline.kt:277-285`).

### 12.2 Ingest versus delete (separate connections)

`IngestPipeline.ingest` reads the document once and then writes in several
independent transactions on the index connection. Three outcomes, depending on
where the delete lands:
- Before `replaceChunks`: the chunk insert fails its FK, the failure is counted,
  the row is gone, and no harm is done.
- Between `replaceChunks` and the link step: the chunks cascade away, but then
  `EdgeUpserter.upsert` **re-creates out-edges for the dead id**, and
  `DanglingResolver.resolveFor(deletedDoc)` **re-points other notes' `title:`
  sentinels at the dead id**.
- Before the vector step: `putEmbeddings` would insert `chunks_vec` rows for
  chunk ids that no longer exist. vec0 has no FK. The path is dormant because no
  embedder runs, but after a reopen `IndexStoreImpl` re-seeds `nextChunkId` from
  `MAX(id)` (`core/vault/src/main/kotlin/app/skein/core/vault/index/IndexSql.kt:57-61`),
  so those ids can be reused and inherit the orphan vector.

Evidence: `IngestPipeline.kt:229-290`; `DeviceVaultOpener.kt:105-118`.

Fix (S):
- re-check `getDocument` after the link and vector steps, and purge if the
  document has gone;
- run an idempotent **orphan-edge sweep** in the existing once-per-unlock
  maintenance pass beside `sweepUnreferencedRevisions` (`IngestScheduler.kt:221`).
  It would delete edges whose document-shaped `src_id` has no row, and
  re-sentinel document-shaped `dst_id`s that have no row. This also heals a
  crash between a two-step delete.

### 12.3 Other hazards

- **Blob deleted before COMMIT** (§5).
- **Wrong change events.** `deleteDocument` publishes no `Messages` or
  `IngestQueue` change (§1.3), so open chat views and ingest progress miss the
  delete.
- **No instrumented cascade test for chunks, FTS and vec** (§1.2). This must
  land on the emulator lane before any delete UI ships.
- **Timeline opens chats and attachments in the note editor** (§9, §5).
- **Model delete has no confirmation, and refusals are swallowed** (§7).

---

## 13. Minimum backend work for a correct delete

Ordered. The first four are required for both chats and notes. None needs a
schema migration if the edge cleanup is done in code.

1. **Edge cleanup in the same transaction.** Two options:
   - **A (recommended).** Extend `VaultRepositoryImpl.deleteDocument` to run, on
     the writer connection before or after `DELETE FROM documents`:
     - `DELETE FROM edges WHERE src_id = ?`
     - for each `edges` row with `dst_id = ?` and `kind = 'wikilink'`, rewrite it
       to `dst_id = 'title:' || lower(<title>)`, `weight = 0.5`, using
       `INSERT OR REPLACE` plus a delete, because the PK is
       `(src_id, dst_id, kind)`
     - `DELETE FROM edges WHERE dst_id = ? AND kind != 'wikilink'` (CITE
       in-edges when the document is an attachment)

     Read the title first. Mirror all of it in `InMemoryVaultRepository`, and
     add contract tests. Graph views subscribe only to `IndexChange`
     (`GraphState.kt:102-107`), so they also need to re-query on the
     repository's `Documents` tick, **or** the edge work goes through a new
     `IndexStore.purgeNode(id, title)` that publishes `EdgesReplaced`, in its
     own transaction, healed by the orphan sweep in (4).
   - **B.** An `AFTER DELETE ON documents` trigger doing the same thing. That is
     migration 010, emulator lane only. It is atomic by construction and covers
     every future delete path. It still does not notify the index stream.
2. **Correct notifications and ordering.** Publish `Messages(id)` and
   `IngestQueue` from `deleteDocument`; call `attachments.delete` after COMMIT
   (S).
3. **Export-stage plaintext purge** for the deleted document (§10.4, S).
4. **Ingest race guard plus orphan-edge sweep** (§12.2, S).
5. **Instrumented contract test (emulator lane).** After `deleteDocument`:
   `chunks`, `chunks_fts MATCH`, `chunks_vec`, `messages`, `document_revisions`,
   `ingest_queue`, `export_stages` and `edges` (both directions) are all clean,
   and the blob is gone (S).
6. **Chats only: `renameDocument(id, title)`** (S), needed for "rename chat"
   and later for generated titles.
7. **Optional privacy hardening:** FTS5 `secure-delete` plus a one-time
   `optimize` as migration 010 (S code, emulator lane, §10.3).

UI prerequisites the backend cannot fix, each S and a "narrowly scoped UI
integration change":
- `TabsState.closeDocument(docId)` in both panes, plus clearing the graph
  overlay;
- route tabs by `Document.kind`;
- a delete gate while generating, plus a `send` guard for a missing chat;
- `ChatCitation.missing`;
- clear `SendPipeline.lastOutcome` for the deleted chat;
- re-query open command-bar results;
- SAF `notifyChange`.

---

## 14. Gaps list

"UI" = narrowly scoped UI integration change (this epic). "API" = a
`VaultRepository`/`IndexStore` contract addition with no schema change (this
epic can own it, but it is storage-adjacent and needs contract tests).
"Storage" = real storage work, belonging to a storage or privacy epic.

| # | Gap (prompt §) | Size | Class | Migration? |
|---|---|---|---|---|
| G1 | Chat rename: no `renameDocument`; `updateBody` needs the transcript echoed back (§28) | S | API | No |
| G2 | Delete chat or note: `deleteDocument` exists and nothing calls it (§29, §31) | S | UI (after G3-G5) | No |
| G3 | Edge cleanup on delete: out-edges deleted, in-edges re-sentinelled (§29 "derived graph relationships", §31) | S in code / M as trigger | API (or Storage if trigger) | No / Yes (010) |
| G4 | Ingest-versus-delete race plus orphan-edge maintenance sweep (§29 "does not reappear") | S | API | No |
| G5 | `deleteDocument` publishes the wrong events; blob deleted before COMMIT | S | API | No |
| G6 | Staged export plaintext of a deleted document survives the timed sweep | S | Storage (privacy) | No |
| G7 | Close tabs by `docId` across panes; drop restored tabs whose document is missing (§29 "no orphaned tab / dead route") | S | UI | No |
| G8 | Send into a deleted chat crashes; no delete gate while generating; `lastOutcome` leaks across chats | S | UI | No |
| G9 | Citation chip "source deleted" state (§31 "avoid broken citation targets") | S | UI | No |
| G9b | Full `CitationResolver` (live / source changed / deleted) from `POST_REVIEW_RESOLUTIONS.md` §1.2 | M | UI + API | No |
| G10 | Purge a deleted note's quoted excerpts from chat history (`skein-koda`) | M | Storage (privacy) | No (JSON rewrite) |
| G11 | FTS5 residue: `secure-delete` plus one `optimize` | S | Storage (privacy) | Yes (010, emulator lane) |
| G12 | Rename-with-link-update for `[[Old]]` → `[[New]]` (§30) | M | API | No |
| G13 | Frontmatter `title` drift on rename: decision plus S change | S | API | No |
| G14 | Generated chat titles (§28) | M | UI + inference call, uses G1 | No |
| G15 | Chat history and search by kind (§28): `observeTimeline(kinds={CHAT})` exists; add a kind filter to search, or filter client-side | S | UI | No |
| G16 | Archive chats (§28 "optionally") | M | Storage: an `archived_at` column or a frontmatter flag, plus `observeTimeline` filtering | Yes (column) |
| G17 | Per-message delete, edit, regenerate; duplicate USER rows on retry | M | API | No |
| G18 | Tabs route by `Document.kind`: chats and attachments currently open in the note editor | S | UI | No |
| G19 | `updateBody` accepts attachments and corrupts `content_hash`; needs a kind guard | S | API | No |
| G20 | Orphan attachment-blob sweep (`ATTACHMENT_ENCRYPTION.md` §2.7) | S-M | Storage | No |
| G21 | Instrumented cascade test on the emulator lane for chunks, FTS, vec and edges | S | Test | No |
| G22 | SAF provider `notifyChange` on delete | S | API | No |
| G23 | Persona management UI plus delete-semantics decision (scope promotion, moving default, no change tick) (§32) | M | UI + API | No |
| G24 | Model delete confirmation plus refusal feedback (§32 "custom models") | S | UI | No |
| G25 | Rename-to-existing-title collision warning | S | UI | No |
| G26 | Undo / trash | M (deferred hard delete in memory) or L (soft delete) | UI or Storage | L: yes |
| G27 | Collections, repositories, skills, saved workflows lifecycle (§32) | L each | Storage (new objects) | Yes |

Recommendation for v1: a confirmation dialog followed by an immediate hard
delete, as prompt §29 describes, with no undo. A deferred-delete snackbar is
the only undo that needs no storage change, but a process death would cancel
the delete, and that is a surprising outcome for a privacy-first app.

---

## 15. Proposed deletion contracts

Ready to lift into `OBJECT_LIFECYCLE_SPEC.md`. "Removed" means gone in the same
committed transaction unless stated. "Detached" means the other object survives
and its pointer is rewritten or dropped. "Marked missing" means a reference is
kept and rendered as a missing target.

### 15.1 Chat

| Aspect | Contract |
|---|---|
| Removed | `documents` row; all `messages` (with this chat's own citation excerpts); transcript chunks plus FTS entries plus vectors; `document_revisions`; `ingest_queue` row; `export_stages` rows **and** their staged files; out-edges (`src_id = chat`) |
| Detached | WIKILINK in-edges → `title:<lowercased title>` sentinel. Notes and PDFs imported through 📎 stay in Knowledge untouched. Persona and model untouched. |
| Marked missing | Citations in *other* chats that quoted this chat: excerpt kept, chip reads "source deleted", no navigation. `[[Chat title]]` text in notes renders unresolved. |
| Not scrubbed (disclosed) | Quotes of this chat inside other chats; FTS index residue until G11 |
| Preconditions | Not generating in this chat (disable the menu item, or cancel and wait) |
| Confirmation | Title: `Delete "<title>"?` Body: `This removes the conversation and its messages from Skein.` Add `Notes and files you attached stay in Knowledge.` when the chat has out-edges to notes. Actions: `Cancel` / `Delete` (destructive style). |
| Navigation after | Close every tab for the chat in both panes. If it was active, activate the neighbour (`TabsState.close` semantics); if no tabs remain, go to the chat history list or the empty state. Clear `SendPipeline.lastOutcome` if it belongs to this chat. Never route to the dead id. |
| Persistence | Durable at COMMIT (WAL, synchronous FULL). Survives restart and lock. Excluded from every backup, so it is **not recoverable**. Future vault exports omit it. |

### 15.2 Message (v1)

| Aspect | Contract |
|---|---|
| Create | `appendMessage` only, which re-materializes the transcript and re-ingests |
| Rename | Not applicable |
| Update / delete | **Not supported in v1.** Messages are immutable and removed only with their chat. The duplicate USER row on retry is a known issue (G17). |
| If G17 lands later | Removed: the row. Rebuilt: the chat transcript, revision and index. Other chats citing this chat: "source changed". No confirmation for a single failed or duplicate turn; confirm otherwise. |

### 15.3 Note

| Aspect | Contract |
|---|---|
| Removed | `documents` row; chunks plus FTS plus vectors; all `document_revisions`, including chat-cited ones; `ingest_queue` row; `export_stages` rows and staged files; all out-edges (wikilink, tag, cite, entity) |
| Detached | WIKILINK in-edges from other notes and chats → `title:<lowercased title>` sentinel (weight 0.5), so a future note with this title regains its backlinks. A source PDF attachment (`source:`) is kept. |
| Marked missing | `[[Title]]` text in other notes (unchanged; unresolved; a tap offers to create a new note). Chat citations (excerpt kept, "source deleted" chip, no preview tab). |
| Not scrubbed (disclosed) | Quoted excerpts of this note in chats (up to 1 KB per retrieval); FTS residue until G11 |
| Preconditions | Close its tabs first, so a pending autosave or title edit cannot reach a missing row |
| Confirmation | Title: `Delete "<title>"?` Body: `This removes the note from Skein, including its search index.` Conditional lines: `<N> notes link to it — those links will show as missing.` and `It was quoted in <M> chats — those quotes stay.` Counts come from `edgesTo(id, WIKILINK)` and a citation scan. Actions: `Cancel` / `Delete`. |
| Navigation after | Close its tabs in both panes and the graph overlay if it is centred there. Return to the Knowledge list with selection on the neighbouring row. Re-query open search results. Other notes' backlinks drawers refresh on their own. |
| Persistence | As for chats |

### 15.4 Attachment / imported file

| Aspect | Contract |
|---|---|
| Removed | `documents` row; the encrypted blob, deleted after COMMIT; CITE in-edges |
| Detached | Derived notes stay; their `source:` frontmatter is left as-is |
| Marked missing | The derived note shows "original file deleted" |
| Confirmation | `Delete "<file name>"?` / `The original file is removed from Skein. Notes made from it stay.` |
| Caveat | The unlinked ciphertext is not shredded (§10.2) |

### 15.5 Persona

| Aspect | Contract |
|---|---|
| Removed | `personas` row. The last persona cannot be deleted. |
| Detached | Documents: `persona_id` set to NULL, which **makes them visible to every persona's retrieval**. The spec must either accept this and say so in the copy, or change it to reassign to the default persona (contract change). |
| Marked missing | Nothing (messages store no persona) |
| Confirmation | `Delete persona "<name>"?` / `Its notes and chats become shared with all personas.` If it is the current default: `Chats will use "<next persona>" from now on.` |
| Also needed | A repository change tick, so the timeline and its filter refresh |

### 15.6 Imported model

| Aspect | Contract |
|---|---|
| Removed | Model directory, then the registry row; the default pointer is cleared |
| Refused | While loaded, or while a handle is open. **Show the refusal.** |
| Kept | `messages.model_id` (history) |
| Marked missing | `personas.default_model` |
| Confirmation (new, required) | `Delete "<model>"?` / `Frees <size>. You'll need to import it again to use it.` |
| Navigation | Stay in the model list. If it was the default, chats show "No model yet" guidance. |

---

## Appendix A — cascade and FTS residue experiment

This ran in the scratchpad with the host `sqlite3 3.51.0`. It was not run
against the SQLCipher 4.17.0 / SQLite 3.53.3 device build. The chunk, FTS and
trigger DDL is copied from `001_initial.sql:65-77,162-169`. `chunks_vec` is
stood in for by an ordinary table, because the host has no vec0.

```
-- cascade fires chunks_ad
before fts|1
after chunks|0
after vec|0
after fts match|0
vocab rows|0
fts_data rows|4|96

-- residue: literal token bytes still present in chunks_fts_data after the cascaded delete
residue walrus in fts_data|2
-- with 'secure-delete' set before the delete (and 'optimize' for pre-existing residue)
residue with secure-delete only|0
residue after optimize|0
config|secure-delete|1
```

## Appendix B — files read

- `docs/research/SKEIN_UI_UX_OVERHAUL_PROMPT.md` §20, §28-32, §48 (Waves 5-6), §50
- `docs/VAULT_FORMAT.md`, `docs/PRIVACY.md`, `docs/BACKUP_EXCLUSIONS.md`
- `docs/design/POST_REVIEW_RESOLUTIONS.md` §1
- Migration headers `001`, `003`, `005`, `007`, `008`, `009`
- `core/model` `Vault.kt`, `Personas.kt`, `ModelRegistry.kt`
- `core/vault` repository, index, extract, persona, blob, transfer, export, provider and lifecycle packages
- `core/rag` ingest, chat, recall and rank packages
- `feature/chat`, `feature/editor/notetab` and `feature/editor/backlinks`, `feature/graph`, `feature/shell` (`nav`, `tabs`, `SkeinApp`), `feature/models`
- `app/` `MainActivity`, `vault/DeviceVaultOpener`, `ingest/*`
- `native/sqlite/CMakeLists.txt`, vendored `sqlite-vec.c` and `sqlite3.h`
