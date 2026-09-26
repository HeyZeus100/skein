# Skein — Object Lifecycle Spec

**Bead:** `skein-xtov.18` · **Epic:** `skein-xtov` (UX overhaul) · **Authority:** `docs/research/SKEIN_UI_UX_OVERHAUL_PROMPT.md` §28–32, §47, §50–51
**Status:** Proposed, for owner review. Nothing here is implemented. §14 lists the decisions that need sign-off.
**Primary input:** `docs/ux/audit/LIFECYCLE_FINDINGS.md` (the storage graph at `009cbb6`). Production code under `core/`, `feature/` and `app/` has not changed between `009cbb6` and `5538b4f`; only screenshot tests were added. The findings' line references therefore still hold. Every claim this spec relies on was re-checked against the cited code. §2 lists what this pass added or corrected.
**Other inputs:** `docs/ux/INFORMATION_ARCHITECTURE.md` (IA), `docs/ux/audit/AUDIT_CHAT_MODELS_SETTINGS.md` §3–4, `AUDIT_KNOWLEDGE.md` §3–5, `AUDIT_SHELL.md` §2.4 and §5, `docs/VAULT_FORMAT.md`, `docs/PRIVACY.md`, `docs/BACKUP_EXCLUSIONS.md`, `docs/design/ATTACHMENT_ENCRYPTION.md`, `docs/design/MODEL_STORE.md`, `docs/design/export-plaintext-lifetime.md`, `docs/design/POST_REVIEW_RESOLUTIONS.md` §1.

**Division of labour with the sibling specs.** `CHAT_UX_SPEC.md` and `KNOWLEDGE_UX_SPEC.md` own the UI flows: which menus hold Rename and Delete, and where dialogs appear. `DESIGN_SYSTEM.md` owns destructive styling and dialog anatomy. `ADAPTIVE_LAYOUT_SPEC.md` owns navigation mechanics: back stack, panes and sheets. This spec owns what each operation does underneath, and the observable results those flows must produce. Where this spec gives copy, it gives the meaning and a recommended wording. The owning UI spec may adjust the wording but may not change the meaning.

**Normative words.** MUST, MUST NOT, SHOULD and MAY are used in the RFC 2119 sense. Every MUST is written so that it maps to a test in §11.

---

## 0. Decisions in one screen

| # | Decision | Why |
|---|---|---|
| **L1** | **Delete means confirm, then an immediate hard delete.** There is no undo snackbar and no trash in v1. | The vault is excluded from every backup, so a delete is final anyway. A delayed delete keeps the content alive, and a process death brings it back. A trash keeps everything by design. See §3.6. |
| **L2** | **One transaction removes the object and all of its row-level derived state**, graph edges in both directions included. Files outside the database are removed after the commit. | A half-deleted object is never visible (§3.3). |
| **L3** | **References held by other objects are never rewritten.** Quotes in other chats and `[[Title]]` text in other notes are kept and shown as missing. | Deleting one object must not rewrite another object's history or its revision hash. The confirmation copy says so. |
| **L4** | **The name is `documents.title`.** Nothing derives it after creation. Rename is a title-only write. It does not move the revision, does not reorder Recent, and does not rewrite links. | §3.1, §4.3, §6.3 |
| **L5** | **Chats, and notes created without a name, are created lazily.** The row is written with the first message or the first content. | This ends the pile of empty `Chat` and `Untitled` rows (`AUDIT_CHAT_MODELS_SETTINGS.md` §3.1, `AUDIT_KNOWLEDGE.md` §3.1). |
| **L6** | **Messages are immutable in v1.** They are removed only with their chat. Retry never duplicates the user's message. | §5 |
| **L7** | **Deleting a file removes the file and its extracted text.** | Otherwise a "deleted" PDF stays searchable and quotable (§7). |
| **L8** | **A missing id is a state, never an error.** A local delete prunes the back stack. A restored back stack drops dead ids. Anything else shows "This note was deleted" or "Source deleted". | IA principle 7. It also fixes the crash when sending into a deleted chat (`LIFECYCLE_FINDINGS.md` §12.1). |
| **L9** | **FTS5 secure-delete (migration 010, emulator lane) is a release gate** for the first delete UI (Wave 5). | Without it the deleted words stay in the search index's pages (§3.7). |
| **L10** | **A chat that is generating cannot be deleted until the answer stops.** | Cancel is not reliable before the first token (`AUDIT_CHAT_MODELS_SETTINGS.md` §4.5). |

---

## 1. Scope and vocabulary

**Objects covered.** Chat, Message and Note are specified in full depth. File (an attachment plus its extracted text), Persona and Imported model are specified at contract depth. §10 records the objects that do not exist yet: collections, repositories, skills and saved workflows.

**What happens to a dependent** when its object is deleted or renamed is always one of five outcomes:

| Outcome | Meaning |
|---|---|
| **Removed** | Deleted in the same committed transaction as the object, unless the row says "after commit". |
| **Detached** | The other object survives. Its pointer is rewritten or dropped. Example: a resolved link edge becomes an unresolved-title edge. |
| **Rebuilt** | Derived state is recomputed from the sources that survive, by re-query or re-ingest. |
| **Marked missing** | The reference is kept verbatim and rendered as a missing target. It cannot be navigated. |
| **Kept** | Unaffected. |

**Work classes** (used in §12 and §13):

| Class | Meaning | Who owns it |
|---|---|---|
| **UI** | Narrowly scoped UI integration | This workstream (`skein-xtov`) |
| **API** | A contract addition to `VaultRepository`, `IndexStore`, `PersonaService` or `SendPipeline` with no schema change. It needs contract tests on the fake and on the real driver. | It can be implemented in this workstream, but only with review from the owning epic (E2 vault, E5 RAG) |
| **Storage** | A schema change, a migration, or a privacy mechanism | The E2 vault or E5 RAG owners |

Sizes are S, M and L as in `docs/BD_TAXONOMY.md`.

**Emulator lane.** Any bead that adds a migration MUST run the emulator lane. The JVM fake driver executes no DDL or DML (`core/vault/src/main/resources/migrations/009_model_origin.sql:36-40`).

---

## 2. Ground truth, and what this pass added

The storage graph is in `LIFECYCLE_FINDINGS.md` §1. In short:

- Chats, notes, attachments and AI outputs are all `documents` rows (`001_initial.sql:49-59`).
- `VaultRepository.deleteDocument` exists as one hard-delete transaction (`core/vault/src/main/kotlin/app/skein/core/vault/repository/VaultRepositoryImpl.kt:266-280`). Nothing in production calls it.
- Foreign-key cascades plus the `chunks_ad` trigger remove the dependent rows: messages, chunks, the FTS and vector rows, revisions, the ingest queue entry and export stages.
- `edges` has no foreign keys. Edges survive a delete in both directions (`001_initial.sql:84-93`).
- The attachment blob is deleted **before** COMMIT (`VaultRepositoryImpl.kt:277`).
- Only `TableChange.Documents(id)` is published (`VaultRepositoryImpl.kt:278`).
- FTS5 keeps token residue until a segment merge (`LIFECYCLE_FINDINGS.md` §10.3).

The cascade and residue experiment was re-run for this spec (Appendix A). It confirmed all of the above and added N6 below.

**New or corrected findings.** All were code-verified in this pass, and each is referenced later.

| # | Finding | Evidence | Consequence for this spec |
|---|---|---|---|
| **N1** | A write to a missing document throws **different exceptions** on the fake and on the real repository. The fake throws `IllegalArgumentException` from `requireNotNull`; the real one throws `NoSuchElementException`. | Fake: `testing/src/main/kotlin/app/skein/testing/InMemoryVaultRepository.kt:161,196,292`. Real: `VaultRepositoryImpl.kt:208,242,351,825-826` | A guard written against the real exception is never exercised by JVM tests on the fake, and the reverse. The contract MUST pin one exception (§3.2). |
| **N2** | The fake's `transaction {}` is not re-entrant: `writeLock.withLock { block() }`, around methods that take the same `Mutex`. Any nested write deadlocks, and it cannot roll back. Nothing calls it today. | `InMemoryVaultRepository.kt:525`, `:100` | A multi-document delete, such as a File (§7), cannot be tested on the JVM until this is fixed (LC-02). |
| **N3** | Ingest stamps chunks with the revision that is current **when it stamps**, not the one it chunked. It reads the document at `:229` and `currentRevision` at `:247`. An edit that lands between the two reads labels chunks of body v1 as revision v2. Citations copy `chunks.revision_hash` verbatim, so they would claim "live" for text that is no longer current. It heals on the next re-ingest. | `core/rag/src/main/kotlin/app/skein/core/rag/ingest/IngestPipeline.kt:229,247`; `VaultSql.kt` `SELECT_CURRENT_REVISION`; `core/rag/.../rank/RetrievedAssembler.kt:51-53,143` | Stamp with `document.contentHash` from the same read (LC-06). |
| **N4** | **A frontmatter-only edit re-addresses the note but never re-queues ingest.** The trigger watches only `body_md` and `title`. The editor writes the frontmatter and then an unchanged body. The result: tag edits never re-link, and every later citation into that note carries a superseded revision, so it shows "source changed" as soon as that badge exists. | `001_initial.sql:183-186`; `VaultSql.kt` `UPDATE_DOCUMENT_FRONTMATTER` comment; `feature/editor/.../notetab/NoteTabState.kt:227-236`; `EdgeUpserter.kt` (tags come from frontmatter) | `updateFrontmatter` MUST re-queue ingest when the hash moves (LC-06). |
| **N5** | The unresolved-link sentinel is lowercased in Kotlin, which folds Unicode (`EdgeUpserter.unresolvedTarget`). SQLite's `lower()` folds ASCII only. The SQL sketch in `LIFECYCLE_FINDINGS.md` §13, `'title:' \|\| lower(<title>)`, would therefore produce a sentinel that the backlinks query never matches for a title such as `Émile`. | `core/vault/.../extract/EdgeUpserter.kt` (`unresolvedTarget`); `feature/editor/.../backlinks/BacklinksState.kt:169-185` | Compute the sentinel in Kotlin and bind it (§3.4). |
| **N6** | The first FTS5 delete after `secure-delete` is set **permanently bumps `chunks_fts` to on-disk format version 5**. The bump survives switching the option off. Readers need SQLite 3.42 or later; the device build is 3.53.3 (`native/sqlite/amalgamation/sqlite3.h:149`). The option persists across reopen. | Appendix A (host `sqlite3` 3.51.0) | This is a one-way format change. §3.7 and LC-07 record it. |
| **N7** | The note header writes the title **on every keystroke** (`NoteTabState.kt:122-132`), and every body autosave re-sends the in-memory title (`NoteTabState.kt:236`). A rename made elsewhere, such as the row ⋮ on Expanded while the note is open beside the list, is **reverted by the next autosave** of that editor. | as cited | Body saves MUST NOT carry a title (`replaceBody`, LC-05). |
| **N8** | **A SAF round trip reverts a rename for every imported note.** The provider serves the stored frontmatter, which still holds the import-time `title:` (import writes it, `ImportServiceImpl.kt:302-320`). A write back through SAF sets the name from that key (`core/vault/.../provider/VaultDocumentsBackend.kt:184-187`, read path `:152-161` → `ExportServiceImpl.renderMarkdown`). | as cited | The frontmatter `title` rendered at every serialization boundary MUST come from `documents.title` (§3.1, LC-05). |
| **N9** | If a rename does not bump `updated_at`, the trigger enqueues with `queued_at = new.updated_at` (`001_initial.sql:183-186`). That can equal the `queued_at` of an ingest already in flight. `completeIngest` then deletes the fresh entry, and the post-rename re-ingest is lost. | `001_initial.sql:183-186`; `Vault.kt` `completeIngest` KDoc | `renameDocument` MUST write `queued_at = now` explicitly (§4.3). |
| **N10** | No notification carries a document id today. Both notifiers use a bare `ACTION_MAIN`. | `app/src/main/kotlin/app/skein/notify/ModelNotifier.kt:40`, `IndexingNotifier.kt:71` | Stale ids come only from the back stack, citations, links and in-memory holders. §3.5 still sets the rule for future notifications. |

---

## 3. Cross-cutting contracts

These apply to every object in §4–§9 unless the object's section says otherwise.

### 3.1 Identity, names, and what is stable across a rename

| Reference | Keyed by | Survives a rename? | On delete of the target |
|---|---|---|---|
| `documents.id` (UUIDv7, mirrored into frontmatter `id`, `VaultRepositoryImpl.kt:915-923`) | id | Yes. It never changes. | Gone. It is reused only if the user re-imports an exported file that carries it (`ImportServiceImpl.kt:268-289`). |
| `messages.id` | id | Yes | Removed with its chat |
| Back-stack keys (IA §6.3: ids only) | id | Yes. The label is resolved live. | Pruned (§3.5) |
| Citation records (`documentId`, `revisionHash`, `excerpt`, `sourceKind`) | id | Yes. The title is not in the revision hash (`VaultRepositoryImpl.kt:210-214`). | Marked missing |
| CITE edges (`source:` frontmatter → attachment) | id | Yes | Removed |
| Resolved WIKILINK edges | id, but **derived from title text** | No. Detached (§3.4). | Detached to the `title:` sentinel |
| `[[Title]]` text, `findByTitle`, the `title:` sentinel, the backlinks excerpt search | title | No | Text kept; renders unresolved |
| `documents.persona_id` | persona id | n/a | Persona delete: §8 |
| `messages.model_id` | model id | n/a | Model delete: §9 |

**The name contract (storage level; `KNOWLEDGE_UX_SPEC.md` and `CHAT_UX_SPEC.md` build on it):**

1. The name of a chat, note or file is **`documents.title`**. It is set at creation and changed only by `renameDocument`. Nothing derives it after creation (`LIFECYCLE_FINDINGS.md` §11).
2. A body `# Heading` is content, never the name.
3. Frontmatter `title` is a **serialization mirror**, not a name.
   - Inbound boundaries read it as an explicit name: import (`ImportServiceImpl.deriveTitle`) and a SAF write.
   - Outbound boundaries MUST render it from `documents.title`: export (`ExportServiceImpl.renderMarkdown`) and a SAF read.
   - The stored key may be stale after a rename. Rename MUST NOT rewrite it, because frontmatter is part of the revision hash (`docs/VAULT_FORMAT.md` "Revision hash").
   - Inside the app, the editor MUST NOT present `title` as an editable property. Whether it is hidden or read-only is `KNOWLEDGE_UX_SPEC.md`'s choice.
   - This closes N8 and gives exported files the `title` key that `VAULT_FORMAT.md`'s wire format marks as required.
4. **Title rules.** These are enforced by `renameDocument` and by the creation paths this spec introduces. Import keeps its own derivation.
   - Trimmed and non-blank.
   - Runs of `\r`, `\n` or `\t` become one space.
   - 1–200 characters.
   - Not unique. `findByTitle` returns the most recently updated case-insensitive match (`VaultSql.kt:82-84`; `COLLATE NOCASE` folds ASCII only).

### 3.2 Writes never resurrect

- `updateBody`, `replaceBody`, `updateFrontmatter`, `renameDocument` and `appendMessage` on a missing id MUST throw `NoSuchElementException` and write nothing. The real repository already does this; the fake MUST be aligned (N1, LC-01). This MUST be stated in each method's KDoc in `core/model/.../Vault.kt`.
- No write path upserts. A late autosave, a late title write, a generated-title write or a late assistant turn cannot bring a deleted object back.
- **The only paths that bring a deleted object back are explicit and intended:**
  - creating an object with the same title, which gets a new id and, by design, re-attaches old backlinks through `DanglingResolver.resolveFor`;
  - re-importing an exported file whose frontmatter `id` is free, which gets the same id; chat citations with that `documentId` then resolve again (`LIFECYCLE_FINDINGS.md` §10.1).
- Every UI call site that writes from long-lived state MUST catch `NoSuchElementException` and switch that surface to its gone state (§3.5). Those call sites are: the chat send, the editor autosave and title commit, a generated-title write, and the SAF provider (which already maps it to `FileNotFoundException`, `VaultDocumentsBackend.kt:196`).

### 3.3 The delete pipeline: ordering and transaction boundaries

The UI calls one coordinator, called `DeleteCoordinator` here. It is session-scoped and lives in the app or shell layer. The repository does the storage work.

```text
UI: DeleteCoordinator                  Repository (writer connection)                 After COMMIT
─────────────────────────────          ──────────────────────────────────             ─────────────────────────
1 resolve(id) → Document
  null ⇒ already gone: prune (step 7), stop
2 preconditions (chat: not generating, L10)
3 compute confirmation counts (§3.6)
4 dialog → user taps Delete
5 mark id "deleting" (open views keep their
  last frame); suspend autosave for id
6 deleteDocument(id) ────────────────► BEGIN IMMEDIATE
  (a File: transaction { notes…;        a  read kind, title          (sentinel input)
   attachment }, §7)                     b  read export_stages.stage_id for id
                                         c  edges: §3.4 (out removed; WIKILINK in → sentinel;
                                            other in removed)
                                         d  DELETE FROM documents WHERE id = ?
                                            cascade: messages, chunks (→ FTS 'delete', vec delete
                                            via chunks_ad), document_revisions, ingest_queue,
                                            export_stages (and chat context bindings / drafts
                                            if those tables exist: they MUST cascade)
                                         e  re-queue the same-title survivor (§3.4)
                                         f  register after-commit actions; queue change events
                                       COMMIT ──────────────────────────────────►  publish Documents(id),
                                                                                    Messages(id) if chat,
                                                                                    IngestQueue
                                                                                   attachments.delete(id)
                                                                                   delete <staging>/<stageId>*
                                                                                   (errors logged, never thrown)
7 success ⇒ prune every back-stack entry
  for id (§3.5); clear session holders;
  announce "Chat deleted" / "Note deleted"
  failure ⇒ un-mark; resume and flush
  autosave; show "Couldn't delete "<title>".
  Try again."
```

**Transaction rules.**

- **T1.** Steps 6a–6e run in **one** `BEGIN IMMEDIATE … COMMIT` on the repository's writer connection, including the `edges` DML. The index connection is a different connection and cannot share this transaction (`app/src/main/kotlin/app/skein/vault/DeviceVaultOpener.kt:105-118`), so it MUST NOT be used for the delete.
- **T2.** Files outside the database MUST be touched only **after** COMMIT: the attachment blob and the staged export plaintext. They go through a new `TxContext.afterCommit` list, flushed next to `pending` in `writeTx` (`VaultRepositoryImpl.kt:773-791`), so a nested `transaction {}` defers them to the outermost commit. A crash between COMMIT and the file delete leaves an **invisible** orphan. The orphan-blob sweep and the staging-directory sweeps collect it (LC-03, LC-09). A failed COMMIT leaves the object whole, bytes included. This fixes the "row alive, blob gone" hazard (`LIFECYCLE_FINDINGS.md` §5).
- **T3.** Navigation happens **after** the commit returns, never optimistically. A local transaction takes milliseconds.
- **T4.** Change events MUST include `TableChange.Messages(chatId)` for a chat and `TableChange.IngestQueue`, both of which cascade today but are never published (`VaultRepositoryImpl.kt:278`, `TableChange.kt:21-35`).

**Idempotency.** `deleteDocument` on a missing id is a successful no-op: its statements affect no rows, its after-commit file deletes find nothing, and it still publishes `Documents(id)`. A double tap on Delete, or two surfaces deleting the same id, is therefore harmless.

**Failure behaviour.** Every row-level effect is in one transaction, so any failure rolls back to the whole object, visible and usable. Failures include: the lock racing the delete, a disk-full error, and the writer being closed by `VaultLifecycle.close` (`core/vault/.../lifecycle/VaultLifecycle.kt:348-366`). After-commit failures leave only invisible residue, which the sweeps collect. **No sequence of failures leaves a half-deleted object visible.** §11 has the tests.

**Races.**

| Race | Rule |
|---|---|
| Ingest of the same document in flight (index connection, several transactions, `IngestPipeline.kt:229-296`) | (1) `IndexStore` writes for a document-shaped source run a `SELECT 1 FROM documents WHERE id = ?` **inside their own `BEGIN IMMEDIATE`**. If the row is gone they write nothing. SQLite serializes writers, so either the delete committed first and the write is skipped, or it commits later and its edge cleanup removes what was written. (2) Edges that another source's ingest points **at** the deleted id are healed by the orphan-edge sweep in the once-per-unlock maintenance pass. Such edges come from a `findByTitle` result, or a `DanglingResolver.resolveFor(deletedDoc)` pass, read before the delete. The sweep deletes the edge and re-queues its source, so `EdgeUpserter` recomputes the correct sentinel from the text (LC-06). (3) Orphan `chunks_vec` rows, possible once an embedder is wired (`LIFECYCLE_FINDINGS.md` §12.2), are removed by the same sweep. |
| A turn generating in **this** chat | Delete is refused (L10). As defence in depth, `SendPipeline` MUST treat `NoSuchElementException` from either `appendMessage` as "chat deleted": it ends the turn without a crash and persists nothing further. |
| A turn generating in **another** chat that retrieved this object | Allowed. The finished turn may persist a quote of the deleted object, which is then shown as missing (§4.5, §6.5). |
| Autosave of the note being deleted | Step 5 suspends the note's autosave and holds its text. After success the text is dropped. After failure autosave resumes and flushes. A save that still lands after the delete throws (§3.2) and is discarded silently, because the id is marked "deleting". |
| A rename, generated title or SAF write racing the delete | Each throws or returns `FileNotFoundException` after the commit (§3.2). Nothing is recreated. |
| A lock during the delete | Either the commit wins, or the transaction fails and rolls back (see failure behaviour). The coordinator shows the failure after the next unlock. |

### 3.4 The edge detach algorithm (shared by delete and rename)

Run on the writer connection, inside the delete or rename transaction. `:sentinel` MUST be computed in Kotlin as `EdgeUpserter.unresolvedTarget(oldTitle)` and bound as a parameter. It MUST NOT be computed with SQL `lower()` (N5).

```sql
-- Delete only: every out-edge of the deleted document (wikilink, tag, cite, entity).
DELETE FROM edges WHERE src_id = :id;

-- Delete and rename: resolved wikilinks into the document become unresolved-title links.
-- The PK is (src_id, dst_id, kind), so a source that already holds the same sentinel collapses into it.
INSERT OR REPLACE INTO edges(src_id, dst_id, kind, weight, created_at)
  SELECT src_id, :sentinel, 'wikilink', 0.5, created_at
  FROM edges WHERE dst_id = :id AND kind = 'wikilink';
DELETE FROM edges WHERE dst_id = :id AND kind = 'wikilink';

-- Delete only: any other in-edge (a CITE into a deleted attachment).
DELETE FROM edges WHERE dst_id = :id;

-- Delete and rename: the document that now answers [[Old title]], if any, is re-queued.
-- Its ingest runs DanglingResolver.resolveFor, which attaches the sentinels to it.
-- Reason 'updated': IngestReason.fromDb throws on an unknown string (Vault.kt:196-206).
INSERT OR REPLACE INTO ingest_queue(doc_id, reason, queued_at, attempts)
  SELECT id, 'updated', :now, 0 FROM documents
  WHERE title = :oldTitle COLLATE NOCASE AND kind != 'attachment' AND id != :id
  ORDER BY updated_at DESC LIMIT 1;
```

Weight 0.5 is `EdgeUpserter.UNRESOLVED_WIKILINK_WEIGHT`. A rename keeps CITE in-edges, because those are keyed by id.

**Why this is sufficient.**

- Out-edges were derived from the dead document's own text.
- A rewritten in-edge says exactly what the linking note's text now means: `[[Old title]]` no longer names this document.
- A later document with that title picks the links up again through the existing resolver (`core/vault/.../extract/DanglingResolver.kt:68-86`). That resolver deliberately left out the inverse operation (`:22-30`), and this algorithm is that inverse.
- The survivor re-queue handles duplicate titles. `[[Plan]]` in note C resolved to the newer "Plan" (A). Delete A, and C's link now means the older "Plan" (B). B's ingest attaches it.

**Who hears about it.** These edge writes do not go through `IndexStore`, so `IndexStore.observeChanges()` does not fire. Every consumer that renders edges MUST also re-query on the repository's `Documents` tick. `BacklinksState` already does this (`feature/editor/.../backlinks/BacklinksState.kt:160-167`). `GraphState` MUST be changed to do the same; today it listens only to `EdgesReplaced` (`feature/graph/.../GraphState.kt:102-107`) (LC-26).

### 3.5 Stale ids: every holder, one rule each

| Holder | Rule |
|---|---|
| **Back stack** (Nav3 keys, ids only, saved across process death and lock, IA §6.3) | On a local delete, the coordinator removes **every** entry whose key names the id, including duplicates and pane entries keyed by it: Connections, the context inspector, node detail. Then the new top entry is shown (§4.7, §6.7). When the stack is **restored** after process death or after unlock, entries whose id no longer resolves are **dropped silently**. The user already saw the delete. |
| An entry that still meets a missing id (defence in depth: `observeDocument(id)` emits `null`) | It renders a gone state and never crashes. Chat: **"This chat was deleted."** with the action "Go to Chats", and no composer, so nothing can be sent. Note or File: **"This note was deleted."** / **"This file was deleted."** with the action "Go to Knowledge". It MUST NOT render this state for an id the local coordinator marked "deleting" (§3.3 step 5). |
| Draft keys (`NewChat`, `NewNote(draftId)`, §4.2, §6.2) | A draft key with no row is **not** stale. It restores as an empty draft. |
| Citation chips and the context inspector's source rows | Marked missing (§4.5, §6.5). They never push an entry. |
| `[[Title]]` in note text | Renders unresolved. A tap offers an explicit create (§6.2). It never creates silently. |
| Knowledge list, chat history, Recent, the attach picker | Live queries (`observeTimeline`). A row disappears on the `Documents` tick. |
| Command-palette results already on screen | MUST re-run the query on a `Documents` tick. Today they are fetched once (`feature/shell/.../nav/CommandBarState.kt:57-90`). Tapping a result whose id no longer resolves removes the row and opens nothing. |
| Graph focus id and selected node | A deleted focus re-centres on the most recently opened document that still exists; if there is none, the Graph empty state. A deleted selected node closes its detail. |
| Session-scoped memory (`SendPipeline.lastOutcome`, streaming citation state, attach-picker caches) | Cleared or filtered at delete time: §4.5 for chats, §6.5 for notes. `ContextBudget` caches token counts by digest only (`LIFECYCLE_FINDINGS.md` §2.4), so there is nothing to clear. |
| Future notifications and deep links carrying an id (none today, N10) | MUST resolve through `getDocument`. If it is missing, open the destination root with a transient "That chat was deleted." |
| SAF grants held by other apps | The provider throws `FileNotFoundException` (`VaultDocumentsBackend.kt:196`). Listings refresh through `notifyChange` (LC-10). |

**If delete UI ships before the Wave 3 shell.** The same rules apply to today's tabs. `TabsState` gains `closeDocument(docId)` across both panes (`feature/shell/.../tabs/TabsState.kt:100` only closes by `TabId`), and restored tabs whose document is missing are dropped (`TabsState.kt:151-180`).

### 3.6 Confirmation and undo policy

**When to confirm.** Always, for deleting a chat, note, file, persona or imported model. There is no silent path: no swipe-to-delete, no Delete key without a dialog, no bulk delete in v1. With no undo (below), the confirmation is the only safety net, so it is not "when appropriate" but always. Rename never needs a confirmation.

**Placement** (the menus themselves are `CHAT_UX_SPEC.md` and `KNOWLEDGE_UX_SPEC.md`):

- Delete is the **last** item in a ⋮ or long-press menu, separated from the rest.
- It is never a top-level button, never in the drawer or rail, and never under a primary action.
- The palette command ("Delete chat", "Delete note") opens the same dialog.
- On a hardware keyboard, Esc and Back mean **Cancel**. Enter is **not** bound to Delete.
- Styling of the destructive action is `DESIGN_SYSTEM.md`'s destructive-action treatment. Delete is never the filled or default action.

**Dialog anatomy.** Every delete dialog follows prompt §29:

- Title: `Delete "<title>"?`. The title is truncated to about 60 characters with "…". If the title is blank, use `Delete this chat?` / `Delete this note?` / `Delete this file?`.
- One consequence line, tailored to the object, saying what is actually removed.
- Optional conditional lines. Each is shown only when its count is greater than 0.
- Actions: `Cancel` · `Delete`.

The copy for each object is in §4.6, §6.6, §7, §8 and §9. Counts are computed when the dialog opens:

- linking notes: `edgesTo(id, WIKILINK)`, distinct `srcId`;
- chats that quoted the object: `countChatsCiting(id)`, LC-04.

**After a delete:** a polite announcement ("Chat deleted.", "Note deleted.") through a snackbar with **no action**, which is also a live region for TalkBack.

**Undo: decision and trade-off.**

| Option | Privacy | What happens on process death or lock inside the window | Cost | Verdict |
|---|---|---|---|---|
| **A. None: confirm, then an immediate hard delete** | Content is gone at commit, apart from §3.7's residue | Nothing to lose; the delete is already durable | 0 | **Chosen for v1** |
| B. Snackbar undo as a **delayed** hard delete (the object is hidden for about 8 s, then deleted) | Content stays fully live, searchable and retrievable for the window | **The object reappears.** A privacy-first app must not un-delete on a crash. | M (in-memory) | Rejected |
| C. Snackbar undo as **re-insert**: delete at once, keep an in-memory snapshot, re-create with the same id on Undo | Plaintext snapshot only in app memory, only for the window | The delete **sticks**, which is the safe direction | M: needs a `restoreDocument(snapshot)` API that re-inserts the row, messages, timestamps and frontmatter; ingest rebuilds the rest; cited old revisions are lost; attachment blob deletion is deferred to the end of the window | **Preferred design if the owner wants undo later.** Not in v1. |
| D. Trash / soft delete | Keeps everything, including residue, until purged | Survives | L: migration, retention policy, every query filtered | Rejected: it contradicts "delete means gone" |

The reasons for A:

- `docs/BACKUP_EXCLUSIONS.md:34-47` excludes the vault from cloud and device-transfer backup, so no platform backup could ever undo a delete. A simulated undo would be the only one.
- Prompt §29 already prescribes a confirmation with a consequence line.
- Material guidance is to use a confirmation *or* an undo for a destructive action, not both.
- Every extra copy works against §3.7.

### 3.7 Persistence and secure-delete guarantees, and their limits

**Durability.**

- A delete is durable when `deleteDocument` returns. It is one `BEGIN IMMEDIATE … COMMIT` in WAL mode (`core/vault/.../db/SkeinSQLiteDriver.kt:181-182`). No `PRAGMA synchronous` override exists, so SQLite's default FULL applies (`native/sqlite/CMakeLists.txt:443-466` sets no `SQLITE_DEFAULT_*SYNCHRONOUS`).
- It survives a process death, a restart, a power loss, and a lock followed by an unlock. Lock runs `wal_checkpoint(TRUNCATE)` and then closes the pool, so there is nothing to replay (`VaultLifecycle.kt:348-366`).
- It survives a restored back stack, because restoration drops dead ids (§3.5).

**Backup and export interaction.**

- Every backup (cloud and D2D, `docs/BACKUP_EXCLUSIONS.md:34-47`) excludes the vault, the attachments and the export staging directory. No restore can resurrect a deleted object, and there is nothing to delete from a backup.
- Exports made **before** the delete are user-owned copies outside Skein: `.md`, vault zip, and PDF through SAF. A delete does not reach them. Re-importing one re-creates the object (§3.2).
- Exports made **after** the delete omit it. `exportVaultZip` enumerates live rows (`ExportServiceImpl.kt:156-162`).
- Staged PDF plaintext of the deleted document is removed after commit (§3.3). Today it can outlive its 10-minute bound until the next lock (`LIFECYCLE_FINDINGS.md` §10.4, `core/vault/.../export/stage/StagedPlaintextSweep.kt:54-61`).

**What can still be recovered after a delete, by adversary.** The realistic threat for residue is someone who gets the vault open **later**, through a compelled unlock or the recovery passphrase. Root and forensic extraction are out of scope in `docs/PRIVACY.md` §7, but the table still states what they would find.

| Residue | Recoverable from Skein's UI (vault unlocked, no root) | Recoverable with the vault key and a raw storage image | Until | Mitigation |
|---|---|---|---|---|
| **Quotes of the deleted object in other chats** (`messages.retrieved_chunks`: up to 8 × 1024 chars per turn that retrieved it, whether or not the answer cited it; `core/rag/.../chat/CitationRecords.kt:63-90`) | **Yes**, deliberately: the "Source deleted" sheet shows the kept quote (§4.5) | Yes | The quoting chat is deleted | Disclosed in the confirmation copy. A "purge quotes" action is LC-12 (G10, `skein-koda`). |
| **Answers in other chats that were written from it** | Yes | Yes | The answering chat is deleted | None. An answer is the other chat's content. |
| FTS5 token residue in `chunks_fts_data` (and for every *earlier version* of edited notes) | No (`MATCH` returns 0) | **Yes, until migration 010** | The next segment merge; with 010, never written | **LC-07, a release gate (L9).** |
| Freed B-tree pages (`chunks`, `documents`, `messages` cells) | No | No: `SQLITE_SECURE_DELETE` zeroes freed content (`CMakeLists.txt:450`) | — | — |
| Vector bytes | No | No: zeroed by sqlite-vec 0.1.9, and empty in production today (`LIFECYCLE_FINDINGS.md` §1.2) | — | — |
| Pre-delete page images in `vault.db-wal` (SQLCipher ciphertext) | No | Yes | The next lock (TRUNCATE checkpoint). A failed checkpoint is only logged (`VaultLifecycle.kt:340-347`). | Optional LC-14: a best-effort TRUNCATE checkpoint after a user delete. |
| Flash blocks holding old copies of database pages or of an unlinked attachment file | No | Yes. They are encrypted by SQLCipher or SKAT (and by Android file-based encryption). The per-file attachment key is HKDF(master, id, salt stored in the file) (`core/vault/.../blob/FileAttachmentStore.kt:40-60`), so no crypto-shredding happens. | Overwritten by the FTL, at an unknown time | None possible in an app. This limit is stated, not claimed away. |
| Plaintext in process memory (UI state, Kotlin strings) | No | No (it needs a memory image of the app process) | GC; the lock tears down the session | None in v1 |
| KV cache in the isolated inference process (the last prompt, which may include the deleted text) | No | No (it needs a memory image of `:inference`) | The next request, a model unload, or the lock (`inference-service/.../InferenceService.kt:484-494`) | Accepted |
| `entities` rows extracted from the deleted text | No | Yes | Never. They are write-once. **No production writer exists yet** (`app/src/main/kotlin/app/skein/ingest/IngestPipelines.kt:50`). | Garbage-collect unreferenced entities when entity extraction ships (§10) |

**The guarantee Skein can state honestly**, once LC-03, LC-04, LC-06 and LC-07 land:

> After a delete, nothing in Skein shows or searches the deleted object except quotes of it saved inside other chats. Its words are gone from the search index, the database keeps no readable copy of its rows, and it cannot return unless you create or re-import it.

Until LC-07 lands, the second sentence MUST NOT be claimed, in the UI or in `docs/PRIVACY.md`.

**Migration 010 specifics.**

- It is DML on the FTS5 config: `INSERT INTO chunks_fts(chunks_fts, rank) VALUES('secure-delete', 1)`, followed by one `INSERT INTO chunks_fts(chunks_fts) VALUES('optimize')` to purge residue left behind before the option was set.
- It creates no schema object, so the integrity catalogue that `VaultLifecycle.integrityCheck()` derives is unaffected (`docs/VAULT_FORMAT.md` "integrity check").
- Seeding: its version exceeds every shipped `user_version`, so ledger seeding (`core/vault/.../db/migrations/Migrator.kt:228-245`) always leaves it pending. It is idempotent anyway.
- Cost: `optimize` rewrites the whole FTS index once, at the unlock that migrates.
- It performs the one-way version bump in N6. Every reader of the vault file MUST therefore be SQLite 3.42 or later, and a downgrade to a build older than that could not read `chunks_fts`.
- Every FTS delete becomes somewhat slower, including the `replaceChunks` on each re-ingest. That is acceptable at personal-vault scale, and the emulator lane measures it (LC-08).

### 3.8 Change signals, summarised

| Operation | MUST publish (repository bus) | Consumers that MUST refresh |
|---|---|---|
| `deleteDocument` | `Documents(id)`, plus `Messages(id)` for a chat, plus `IngestQueue` | Lists, `observeDocument(id)` (emits `null`), `observeMessages(id)` (emits `[]`), backlinks, graph (§3.4), palette results, ingest progress |
| `renameDocument` | `Documents(id)`, plus `IngestQueue` | Header, list rows, Recent, backlinks, graph |
| Persona delete | A repository `Documents` tick for the reassigned rows (§8) | Timeline and Knowledge persona filters |

The JVM fake ticks every flow on every change (`InMemoryVaultRepository.kt:538`). A JVM test of "the open chat refreshes after delete" therefore passes on the fake and fails on the device. The change-signal tests in §11 MUST run on the real driver (emulator lane).

---

## 4. Chat

### 4.1 Identity and storage

- A chat is a `documents` row with `kind='chat'`. Its id is a UUIDv7, stable for its lifetime.
- `messages` rows are the source of truth.
- `body_md` is a **derived** Markdown transcript, re-materialized inside every `appendMessage` transaction (`VaultRepositoryImpl.kt:369-391`). It exists so that chats are chunked, searchable, linkable and retrievable like notes.
- `content_hash` is the transcript's revision hash. Revisions are captured with an empty snapshot (`VaultRepositoryImpl.kt:386-388,535`).
- `updated_at` is bumped by every append (`:377`), so "Recent" means last activity.
- `persona_id` is recorded but never read at send time (`LIFECYCLE_FINDINGS.md` §2.1).
- Title: see §4.2.

### 4.2 Create

| Trigger (IA §3.5, §5.1) | What opens | When the row is written |
|---|---|---|
| The landing composer ("What are you working on?"), ✎ New chat (drawer top on Compact, rail top on Expanded), Ctrl+N, palette "New chat", `/chat` (a power path that still works) | A **draft chat** (`NewChat` key; no row, no id in storage) | On the **first send**. `SendPipeline` runs `createDocument` and the first USER `appendMessage` in **one** `transaction {}`. The real repository already nests these (ambient `TxContext`, `VaultRepositoryImpl.kt:773-791`); LC-02 makes the fake support it. A failed first send therefore leaves either nothing or a chat with its first message, never an empty chat. |
| "Ask about this" on a note or file (IA §3.8) | A draft chat with that item attached (the binding is `CHAT_UX_SPEC.md`'s) | On the first send, as above |

**Rules.**

- **C1.** An unsent draft MUST leave no `documents` row. Leaving the draft, locking, a process death or a fold never creates one. The draft's composer text is not a chat object. If IA D7 ("encrypted draft rows") is adopted, a draft of an *existing* chat MUST be keyed by `chat_doc_id` with `ON DELETE CASCADE` (migration; emulator lane). A draft of a not-yet-created chat is keyed by the draft key, never by a `documents` row.
- **C2.** Defaults at creation:
  - `kind = CHAT`;
  - `title = provisionalTitle(firstUserMessage)` (C3);
  - `body_md = ""`, immediately re-materialized by the first append;
  - `persona_id` = the persona explicitly selected in the chat's model & persona sheet, otherwise `null` (shared);
  - frontmatter `{id}`.
- **C3. `provisionalTitle(text)`** is a pure, deterministic function, unit-tested with fixed examples:
  1. Strip Markdown syntax: headings, emphasis, code fences and inline code, `[text](url)` becomes `text`, `[[x|alias]]` becomes `alias`, and `[[x]]` becomes `x`.
  2. Collapse all whitespace to single spaces and trim.
  3. If the result is longer than 48 characters, cut at the last space at or before 48 (as long as that keeps at least 24 characters, otherwise cut at 48) and append `…`.
  4. If the result is empty (for example the message was only an attachment link), use `Chat · <local short date and time>`, for example `Chat · Sep 26, 14:05`.

  The title is written **once**, at creation. This replaces the constant `"Chat"` (`feature/shell/.../nav/BuiltinCommands.kt:33`) and satisfies prompt §28 and §51 ("repeated anonymous 'Chat' entries are eliminated").
- **C4. Legacy chats** are rows titled exactly `Chat` that have at least one USER message. They are re-titled **once** with `provisionalTitle(first USER message)` through `renameDocument(id, t, ifTitleIs = "Chat")`. This runs in the once-per-unlock maintenance pass beside `sweepUnreferencedRevisions` (`app/src/main/kotlin/app/skein/ingest/IngestScheduler.kt:215-222`). It is idempotent. Each re-title re-queues that chat's ingest. Legacy chats with no messages are left alone; the user can delete them.
- **C5. Generated titles** are optional (G14, M). A generated title MAY replace the title **only if it is still automatic**, where `isAutoTitle := title == "Chat" || title == provisionalTitle(first USER message)`. The replacement MUST be written with `renameDocument(id, generated, ifTitleIs = <the auto title read before generation>)`. That makes it a compare-and-set: a user rename, or a delete, in the meantime wins, returning `null` or throwing `NoSuchElementException`, and the generated title is dropped. The rest of the rule:
  - Generation MUST start only after the first ASSISTANT turn is persisted.
  - It MUST run only while the engine is idle.
  - It MUST be cancellable, and a new send takes priority over it.
  - It MUST be bounded to 8 words.
  - It MUST NOT run more than once per chat.
- **Side effects of creation:**
  - the ingest trigger enqueues `created` (`001_initial.sql:179-181`);
  - a revision is captured;
  - the chat appears at the top of the history (`observeTimeline(kinds = {CHAT})`, ordered by `updated_at`).

### 4.3 Rename

- **Triggers:** ⋮ Rename on the chat header, ⋮ or long-press Rename on a history row, and palette "Rename chat". The dialog opens prefilled with the text selected. Rename is disabled while the field is blank (after trim) or unchanged. Enter confirms, since rename is not destructive.
- **Storage.** `renameDocument(id, title)` (new, LC-05) runs one transaction:
  1. `UPDATE documents SET title = ? WHERE id = ?`.
  2. The §3.4 in-edge detach and survivor re-queue for the **old** title.
  3. `INSERT OR REPLACE INTO ingest_queue(doc_id, 'updated', :now, 0)` (N9).
  4. Publish `Documents(id)` and `IngestQueue`.

  It **does not** change `updated_at`, so the history order is unchanged. It also does not change `body_md`, `content_hash`, revisions, messages or citations.
- **No race with appends.** `appendMessage` writes only `body_md`, `updated_at` and `content_hash` (`UPDATE_CHAT_BODY`), and `renameDocument` writes only `title`. This removes today's accidental path, where `updateBody` echoes the transcript back and races the append (`LIFECYCLE_FINDINGS.md` §2.2).
- **Live update.** The header resolves the title through `observeDocument(id)`, and history rows through `observeTimeline`. Back-stack keys hold ids only, so no label goes stale. That fixes the frozen `Tab.title` snapshots (`feature/shell/.../tabs/Tab.kt:48-54`).
- **Links and citations.** Citations are keyed by id and so are unaffected; their chip labels re-resolve. `[[Old chat title]]` in a note becomes unresolved (§3.4). The dialog adds the note-rename disclosure line (§6.3) when the chat has WIKILINK in-edges, which is rare.
- **A user rename is final.** From then on `isAutoTitle` is false, so no generated title overwrites it (C5).
- **Errors.** Renaming a deleted chat throws `NoSuchElementException`, and the surface shows its gone state (§3.5).

### 4.4 Update

- **The only update is `appendMessage`** (§5). The transcript `body_md` is derived and MUST NOT be written by anything else:
  - `updateBody` on a CHAT MUST throw `IllegalArgumentException` (LC-05). Any such edit is silently overwritten by the next append (`LIFECYCLE_FINDINGS.md` §9).
  - Open-by-kind (IA §2 principle 2, LC-20) removes today's path where the note editor edits chats.
- Switching persona or model mid-chat is `CHAT_UX_SPEC.md`'s. If persona switching writes `documents.persona_id`, it needs a new title-free, body-free API. None exists today; add it with that spec's bead. A persona change leaves the revision untouched (`docs/VAULT_FORMAT.md` "Revision hash").
- **Concurrency with ingest.** Each append re-queues the chat (`queued_at` advances), and an ingest already in flight ends with a no-op `completeIngest`. So chat search, and retrieval of this chat by other chats, reflect the latest transcript eventually, never mid-turn. The N3 stamping fix applies to chats too.

### 4.5 Delete: the contract

**Preconditions.**

- The chat exists; otherwise the delete is idempotent and the coordinator only prunes.
- **It is not generating (L10).** `SendPipeline` exposes a session-scoped `activeChatId: StateFlow<DocId?>`, set when a send starts and cleared in `finally` (LC-21). While it names this chat, Delete is **disabled** on every surface: the header ⋮, history rows and the palette. The supporting line reads `Stop the answer to delete this chat.`

| Dependent | Outcome | Mechanism and when | Today | Item |
|---|---|---|---|---|
| `documents` row (title, generated title, `persona_id`, frontmatter) | **Removed** | `DELETE FROM documents`, in the transaction | Code exists, unreached | LC-22 |
| All `messages`, including this chat's own citation excerpts | **Removed** | FK cascade (`001_initial.sql:103-111`) | Cascades | — |
| Transcript `chunks` | **Removed** | FK cascade | Cascades | — |
| `chunks_fts` rows | **Removed**: no match from then on. Token residue remains until a merge; **none with 010.** | The `chunks_ad` trigger's FTS `'delete'` (`001_initial.sql:166-169`) | Residue | LC-07 |
| `chunks_vec` rows | **Removed** (bytes zeroed) | `chunks_ad` | Cascades | — |
| `document_revisions` of the chat | **Removed** | FK cascade (`003_document_revisions.sql`) | Cascades | — |
| `ingest_queue` entry | **Removed** | FK cascade | Cascades | — |
| An ingest of this chat in flight | Its writes are **skipped**; anything left over is swept | The §3.3 race rule | Re-creates edges for the dead id (`LIFECYCLE_FINDINGS.md` §12.2) | LC-06 |
| WorkManager | Nothing to cancel: ingest is one unique pass, not per-document | — | — | — |
| **Out-edges** (`src_id = chat`: WIKILINK and TAG from the transcript, e.g. `[[file]]` inserted by 📎) | **Removed** | §3.4, in the transaction | Orphaned | LC-04 |
| **In-edges** (`[[Chat title]]` in notes) | **Detached** to `title:<title>`, weight 0.5; the same-title survivor is re-queued | §3.4 | Orphaned: a ghost node labelled with a UUID | LC-04 |
| `[[Chat title]]` text in notes | **Kept**; renders unresolved | Renderer | — | — |
| Notes and files attached, linked or imported through this chat | **Kept** (they are Knowledge objects) | — | Kept | — |
| **Chat context bindings** (attached notes and files; `CHAT_UX_SPEC.md`) | **Removed** | If persisted: a join table whose `chat_doc_id` cascades (never chat frontmatter, which would move the revision). If in memory: dropped at step 7. | Do not exist | LC-13 |
| **Quotes of this chat inside other chats** (transcripts are retrievable) | **Marked missing.** The excerpt is kept. The chip reads **Deleted chat** (from `sourceKind`). A tap opens a sheet: "Source deleted. This quote was saved with the answer." plus the excerpt. It never navigates. | UI: `ChatCitation.missing`, resolved per render (`feature/chat/.../ChatViewModel.kt:159-168` already calls `getDocument`) | The label falls back to the excerpt; a tap opens "Note not found" | LC-23 |
| `export_stages` rows | **Removed** | FK cascade | Cascades | — |
| Staged export plaintext files | **Removed after commit** | After-commit delete of `<staging>/<stageId>*`; also `sweepIfExpired` purges by prefix when the row is missing | Survives until the next lock | LC-03 |
| Attachment blob | n/a (chats have none; `attachments.delete` is a no-op) | — | — | — |
| `SendPipeline.lastOutcome` (the retrieved texts of the last turn, session memory) | **Removed** if `chatDocId == id` | Coordinator step 7. Also, every chat view MUST filter `lastOutcome` by its own `chatDocId`; today it does not (`ChatViewModel.kt:142-147`). | Leaks across chats | LC-21 |
| Composer draft and streaming state of this chat | **Removed** | Its view model is disposed with the pruned entry | — | LC-22 |
| Open views and back-stack entries (chat, context inspector, model sheet keyed by this chat) | **Removed** (pruned) | §3.5 | Tab stays; the next send crashes | LC-20 |
| Inference KV cache | **Kept** until the next request, unload or lock | Accepted residue (§3.7) | — | — |
| `persona_id` target (persona) | **Kept** | — | — | — |
| SAF provider listing (chats list as `Notes/<title>.md`) | **Rebuilt** | `notifyChange` after commit | No notification | LC-10 |
| History, search and Recent | **Rebuilt** (live) | `Documents` tick | Refreshes, except palette results | LC-26 |

**Transaction boundary.** The rows above marked Removed or Detached are one transaction. File deletions happen after commit. UI clean-up happens after the call returns (§3.3).

### 4.6 Confirmation copy (chat)

```
Delete "Skein UX redesign"?

This permanently removes the conversation from Skein.
Notes and files it used stay in Knowledge.          ← only if the chat has WIKILINK out-edges
                                                       to documents, or context bindings

                                   Cancel    Delete
```

`permanently` stands in for the missing undo. The prompt §29 wording is kept otherwise.

### 4.7 Navigation after deleting a chat

| Where the delete started | Compact (Fold outer, phones) | Expanded (Fold inner) |
|---|---|---|
| **The open chat's header ⋮**, or the palette while in the chat | Every entry for the id is pruned. If an entry remains above the Chat root (for example the chat was reached from another object), it is shown. Otherwise the **Chat destination root** is shown: the "What are you working on?" landing with an empty composer. The drawer stays closed. No other chat opens. | The Conversations list stays, and the row disappears. The detail pane shows the **landing** ("What are you working on?" plus Recent). **No other chat is opened.** List focus (keyboard and TalkBack) moves to the neighbouring row: the next one, else the previous one, else the list's empty state. |
| **A history row's ⋮ or long-press**, for a chat that is **not** open | The drawer stays open on the history. The row disappears, and focus moves to the neighbouring row. The open chat is untouched. | The row disappears and focus moves to the neighbour. The open chat is untouched. |
| **A history row, for the chat that is open** | As for the header | As for the header |
| A fold or unfold right after the delete | No effect: the back stack was already pruned | Same |
| A process death or lock right after the delete | The restored stack drops the id (§3.5) and lands where the prune would have | Same |

It never routes to the dead id and never shows an orphaned entry. Sending is impossible, because the composer does not exist for a gone chat, which fixes `LIFECYCLE_FINDINGS.md` §12.1. Also, as a separate fix, `ChatViewModel.send` MUST catch `NoSuchElementException` alongside `InferenceException` (`ChatViewModel.kt:231`) and switch to the gone state.

### 4.8 Persistence (chat)

- Creation is durable at the commit of the first send (C1).
- Rename is durable at its commit.
- Delete: §3.7. It survives restart, lock and unlock, process death and a fold. It is excluded from every backup and is **not recoverable**.
- A legacy "Chat" title changes at most once (C4).

---

## 5. Message

### 5.1 Identity and storage

`messages(id UUIDv7, chat_doc_id FK CASCADE, role, content_md, model_id, retrieved_chunks JSON, created_at)` (`001_initial.sql:103-111`).

- `retrieved_chunks` holds citation-record-v1. That is **every** item offered to the model for the turn, up to 8, each with `documentId`, `revisionHash`, `locator`, an `excerpt` of at most 1024 chars, and `sourceKind` (`CitationRecords.kt:63-90`, `core/model/.../CitationRecordJson.kt:195`).
- The id never changes.
- Messages are ordered by `created_at`.

### 5.2 Create

- **The only path is `appendMessage`.** It inserts the row and then, in the same transaction:
  - re-materializes the chat transcript;
  - captures a revision if the hash moved;
  - bumps the chat's `updated_at`;
  - re-queues ingest through the trigger;
  - publishes `Messages(chatId)` and `Documents(chatId)` (`VaultRepositoryImpl.kt:346-403`).
- **USER** is persisted **before** generation starts (`feature/chat/.../SendPipeline.kt:205-206`). For the first message of a new chat it is created together with the chat (C1).
- **ASSISTANT** is persisted **once**, at the end of the turn:
  - an interrupted turn carries the suffix marker `<!-- skein:interrupted … -->` (`SendPipeline.kt:82`);
  - an empty turn is never persisted (`SendPipeline.kt:94`, PP-64).
- **M1.** The ASSISTANT message MUST record `model_id` (today it does not, `AUDIT_CHAT_MODELS_SETTINGS.md` §4.2). That makes provenance survive a model delete (§9). S, `feature/chat`, LC-21.
- **M2.** The ASSISTANT message MUST store `citations` (citation-record-v1), never legacy `retrievedChunks` (already true, `SendPipeline.kt:281-283`).

### 5.3 Rename

Not applicable.

### 5.4 Update

- **Messages are immutable in v1** (L6). There is no edit, no regenerate and no per-message delete.
- **M3. Retry MUST NOT append a second USER message.** Today it does (`ChatViewModel.kt:251-254`, pinned by `ChatScreenTest.kt:318-325`). Retrying a failed turn reuses the persisted USER row: history is every message before that row, the prompt is its `content_md`, and no USER append happens. S, `feature/chat`, LC-21. `CHAT_UX_SPEC.md` owns how Retry looks.
- **M4.** A turn lost to process death or composition exit leaves a USER row with no reply. It MUST render as "No answer" with Retry (applying M3), never as silence. Making generation survive composition exit is `CHAT_UX_SPEC.md`'s P0 (`AUDIT_CHAT_MODELS_SETTINGS.md` §4.10). This spec only fixes what is persisted.

### 5.5 Delete

- **In v1, messages leave only with their chat** (the §4.5 cascade).
- If per-message delete is ever added (G17, M, API, no migration), this is the required contract, from `LIFECYCLE_FINDINGS.md` §3.3:
  - Removed: the row.
  - Rebuilt, in the same transaction: the transcript, a new revision, and a re-queue.
  - Other chats' citations into this chat then read "source changed".
  - Publish `Messages` and `Documents`.
  - No confirmation for deleting a failed or duplicate USER turn; confirmation otherwise.

### 5.6 Dependencies, derived state, navigation and persistence

- **Depends on** its chat (FK, cascade); the documents it cites (by id, with the excerpt copied in, so they are marked missing on delete); and the model (`model_id`, provenance that may dangle).
- **Derived from it:**
  - the chat transcript, and through it the chat's chunks, FTS rows, edges and retrievability into other chats;
  - the chat's `updated_at`, which drives recency;
  - the provisional title (C3), for the first USER message.
- **Confirmation and navigation after deletion:** not applicable in v1.
- **Persistence:**
  - A USER row is durable once the send starts.
  - An ASSISTANT row is durable at the end or interruption of its turn.
  - Both survive restart, lock and process death.
  - Neither is in any backup.

---

## 6. Note

### 6.1 Identity and storage

- A note is a `documents` row with `kind='note'`. AI outputs (`kind='aiout'`) follow the same contract.
- Its id is a UUIDv7, stable across rename and edits. Its name is `documents.title` (§3.1).
- `body_md` is Markdown. `frontmatter` is JSON, shown as a `---` block in the editor.
- A note whose frontmatter has `source: <attachmentId>` is the **extracted text of a File** (§7). The UI never presents it as a stand-alone note to delete; its Delete is the File's Delete.

### 6.2 Create

| Trigger (IA §3.2, §3.5) | Name | When the row is written |
|---|---|---|
| Knowledge "New note", palette "New note" | The title field. If it is blank at first commit, `Untitled`. `KNOWLEDGE_UX_SPEC.md` MAY instead choose a first-line rule, but it MUST still write a non-blank title, once. | **Lazily.** The editor opens on a `NewNote(draftId)` key with a pre-minted UUIDv7. The row is created with `createDocument(id = draftId, …)` at the first commit of a non-blank title or body. An **untouched new note leaves no row.** After a process death, a `NewNote` key with no row restores as an empty draft. |
| `[[` autocomplete "Create "X"" in the editor | `X` | Immediately (an explicit, named create). It MUST first check `findByTitle(X)` and link to the existing note instead of creating a duplicate. Today it does not (`NoteTabState.kt:280-282`, `AUDIT_KNOWLEDGE.md` §5.2). |
| Tapping an unresolved `[[X]]` | `X` | **Only after an explicit "Create note "X"" affordance.** It MUST NOT create silently (today: `NoteTabState.kt:299-309`). `KNOWLEDGE_UX_SPEC.md` owns the affordance. |
| Import a Markdown, text or code file (Knowledge "Import file", chat ＋) | frontmatter `title`, then the first `# heading`, then the filename (`ImportServiceImpl.kt:322-330`) | Immediately |
| "Save answer as note" (AIOUT; future, `CHAT_UX_SPEC.md`) | Given by that spec | Immediately |

**Side effects of creation.**

- The trigger enqueues `created`.
- Ingest then chunks the note, indexes it for FTS, extracts WIKILINK, TAG and CITE edges, and runs `DanglingResolver.resolveFor(note)`. A note that takes a deleted note's title therefore **regains its backlinks**. That is the designed resurrection-by-title (§3.2).
- A revision is captured.

### 6.3 Rename

- **Triggers:** ⋮ Rename on the note header, ⋮ or long-press on a Knowledge row, and palette "Rename note". Inline editing of the header title MAY remain. If it does, it MUST commit through `renameDocument` **once**, on IME Done, on focus loss or on leaving the note, and **never per keystroke**. Today every keystroke writes, re-captures and re-queues (`NoteTabState.kt:122-132`, `AUDIT_KNOWLEDGE.md` §3.4).
- **Storage.** `renameDocument` as in §4.3: the title only, `updated_at` unchanged, `content_hash` and revisions unchanged, the §3.4 detach for the old title, and ingest re-queued with `queued_at = now` so that `resolveFor(note)` attaches `title:<new>` sentinels.
- **Body saves never carry a title** (N7). The editor saves through `replaceBody(id, bodyMd)` (new, LC-05) plus `updateFrontmatter` when the frontmatter changed. `updateBody(id, title, body)` remains for callers that deliberately set both. The SAF write path (`VaultDocumentsBackend.kt:184-187`) MUST split into two calls: `renameDocument`, only when the inbound `title` differs from the stored name, then `replaceBody`. An external rename then gets the same §3.4 edge treatment as an in-app rename (LC-05).
- **Frontmatter `title`** is left untouched, so the revision does not move (§3.1).
- **Effect on links. This is a v1 decision.**
  - `[[Old title]]` text in other notes is **not rewritten** (G12 deferred). Those links become unresolved, or resolve to another note with that title.
  - The resolved in-edges are detached at rename time, so backlinks, the graph and the text agree **at commit**. Today they disagree until each linking note is next re-indexed (`LIFECYCLE_FINDINGS.md` §11).
  - Citations, CITE edges and back-stack entries are keyed by id and are unaffected. Their labels re-resolve.
- **Disclosure and collision lines in the Rename dialog** (`KNOWLEDGE_UX_SPEC.md` places them):
  - When `edgesTo(id, WIKILINK)` has N > 0 distinct sources: `2 notes link to "Old title". Those links won't follow the new name.`
  - When `findByTitle(new)` returns another document: `Another note is already called "Plan". [[Plan]] links will open the one edited most recently.` This is a warning, not a block (G25).
- **Live update:** the header, rows, Recent, backlinks drawers of other notes (they already re-query on the timeline tick) and the graph (LC-26).
- **Export and SAF:** the filename and the rendered `title:` follow the new name (§3.1).
- **Errors:** renaming a deleted note throws `NoSuchElementException`, which leads to the gone state.

### 6.4 Update (editing)

- **Edit semantics.** The editor buffer is frontmatter plus body. A save splits it:
  - `updateFrontmatter` only when the frontmatter changed, with `id` force-pinned (`NoteTabState.kt:227-236`);
  - then `replaceBody`.
  - Each write that moves the hash captures a revision. A body write re-queues ingest through the trigger. **A frontmatter write MUST re-queue ingest too** (N4, LC-06).
- **Autosave cadence.** Debounce at most 1 s (500 ms today, `feature/editor/.../EditorState.kt:140-146`). Saves are serialized (`saveMutex`).
- **Flush requirements (MUST).** The pending buffer is flushed through `EditorState.flush(deadline = 2 s)` (`EditorState.kt:203-215`) when:
  - (a) the editor leaves composition for any reason: navigation, pruning, a fold or unfold, a configuration change, a pane re-parent;
  - (b) the app goes to the background (`ON_STOP`);
  - (c) **before the vault locks.** The pre-lock hook calls `FlushRegistry.flushAll()`, which is held but never called today (`app/src/main/kotlin/app/skein/MainActivity.kt:359-366`). The lock sequence belongs to E3.I3b; this spec makes it a requirement.

  If a flush fails or times out, the text stays in memory and the note shows an unsaved or error state. It is never dropped silently. Today, pending edits are cancelled on dispose (`AUDIT_KNOWLEDGE.md` §3.3).
- **Discard on delete.** A note being deleted suspends its autosave (§3.3 step 5). Disposal after the delete MUST NOT flush.
- **One writer per note.** At most one live `EditorState` exists per note id per process. A second surface showing the same note (for example "Open beside" at ≥ 1200 dp) attaches to it. Today two editors overwrite each other, last writer wins (`AUDIT_KNOWLEDGE.md` §3.3).
- **External writes** (a SAF write, `VaultDocumentsBackend.kt:172-188`) are a second writer. The open editor observes `observeDocument(id)`. When the stored body changes and there are no local pending edits, it reloads. When there are, local edits win at the next save and `KNOWLEDGE_UX_SPEC.md` surfaces the conflict.
- **Concurrency with ingest.** Each save advances `queued_at`, so an ingest already in flight ends with a no-op `completeIngest` and the note is re-ingested. Search and retrieval lag an edit by at most one ingest pass. `KNOWLEDGE_UX_SPEC.md` may show "Preparing for search…". Chunks MUST be stamped with the revision of the body they were cut from (N3, LC-06).

### 6.5 Delete: the contract

**Preconditions.** The note exists. None beyond that: a note can be deleted while a chat is generating, and a turn in flight that retrieved it later shows its quote as missing. If the note is the extracted text of a File (`source:`), the delete targets the File (§7).

| Dependent | Outcome | Mechanism and when | Today | Item |
|---|---|---|---|---|
| `documents` row (title, body, frontmatter, `persona_id`) | **Removed** | In the transaction | Unreached | LC-25 |
| `chunks`, `chunks_fts`, `chunks_vec` | **Removed**; FTS residue none with 010 | Cascade plus `chunks_ad` | Residue | LC-07 |
| `document_revisions`, **including revisions cited by chats** | **Removed** | Cascade. Privacy: the user asked for the note to go. Citation resolution reads as deleted (`POST_REVIEW_RESOLUTIONS.md` §1.3: `current: RevisionRef? // null when document deleted`). | Cascades | — |
| `ingest_queue` entry; an ingest of it in flight | **Removed**; writes skipped or swept | Cascade; the §3.3 race rule | Edges re-created | LC-06 |
| **Out-edges** (WIKILINK, TAG, CITE, ENTITY) | **Removed** | §3.4. Today they keep steering retrieval: `GraphRecall` expands through dead nodes (`core/rag/.../recall/GraphRecall.kt:17,77`, `PprRanker.kt:213-215`). | Orphaned | LC-04 |
| **WIKILINK in-edges** from other notes and chats | **Detached** to `title:<title>`; the same-title survivor is re-queued | §3.4 | Orphaned, pointing at a dead UUID | LC-04 |
| `[[Title]]` **text** in other notes | **Kept**; renders unresolved; a tap offers an explicit create (§6.2) | Renderer | A tap silently creates an empty note | LC-25 |
| Backlinks drawers of other notes | **Rebuilt** | They already filter missing sources and re-query on the tick (`BacklinksState.kt:160-184`) | Correct | — |
| Graph views containing the node | **Rebuilt**; a deleted centre re-centres (§3.5) | `GraphState` MUST merge the `Documents` tick | Ghost UUID node | LC-26 |
| **Quotes of this note in chats** (`messages.retrieved_chunks`) | **Marked missing.** The excerpt is kept. The chip reads **Deleted note**. A tap opens the "Source deleted" sheet with the quote. No navigation. | UI, per render | Label falls back to the excerpt; a tap opens "Note not found" | LC-23 |
| Answers written from the note | **Kept** (the other chat's content) | — | — | — |
| **Chat context bindings** naming the note (active context) | **Removed** from every chat. The chat's context chip count drops, and the chat's own history is unchanged. | A join table with `doc_id … ON DELETE CASCADE` if persisted (LC-13); otherwise the in-memory holder drops the id at step 7 | Do not exist | LC-13 |
| Context inspector rows and `SendPipeline.lastOutcome` items for this note | **Marked missing**: "1 source was deleted", with no text | Filter at render; drop the text from memory at step 7 | Stale text shown | LC-21 |
| Source attachment (`source:` on this note) | See §7. The UI routes this delete to the File. | — | — | LC-09 |
| `export_stages` rows; staged plaintext files | **Removed**; files after commit | Cascade; after-commit purge | The file outlives the bound | LC-03 |
| Open editor and every back-stack entry for the id (editor, Connections pane, node detail) | **Removed** (pruned); pending edits **discarded** | §3.3 steps 5 and 7; §3.5 | Stays open; a title edit crashes (`NoteTabState.kt:122-132` launches `updateBody` without a catch) | LC-20, LC-24 |
| Knowledge list, Recent, search, the attach picker | **Rebuilt** (live); palette results re-queried | `Documents` tick | Palette stale | LC-26 |
| SAF listing | **Rebuilt** | `notifyChange` | None | LC-10 |
| `entities` extracted from it (future) | **Removed** when unreferenced | §10 | No writer yet | — |
| Collections and repository references | Not applicable (§10) | — | — | — |

Transaction boundary, ordering, idempotency, races and failure behaviour are as in §3.3, identical for notes. Rows marked Removed or Detached commit together. Files are deleted after commit. UI clean-up happens after the call returns.

### 6.6 Confirmation copy (note)

```
Delete "Mycology research"?

This permanently removes the note from Skein.
2 notes link to it. Those links will show as missing.   ← WIKILINK in-edges, distinct sources > 0
It was quoted in 3 chats. Those quotes stay.            ← countChatsCiting(id) > 0

                                   Cancel    Delete
```

The two conditional lines are the privacy disclosure (§3.7). They are not optional when their counts are greater than 0.

### 6.7 Navigation after deleting a note

| Where the delete started | Compact | Expanded |
|---|---|---|
| **The open note's header ⋮**, or the palette in the note | Every entry for the id is pruned. **If the note was pushed from somewhere, return there.** For example, opened from a chat citation returns to the same chat at the same scroll position, where its chip now reads "Deleted note". Otherwise show the Knowledge list, scrolled to where the row was, with focus on the neighbouring row. | The list stays and the row disappears. The detail pane shows Knowledge's empty-selection state. **No other note is opened.** Focus moves to the neighbouring row. A Connections extra pane for this note closes (its entry is pruned) and the list takes its place back (IA §3.4). |
| **A Knowledge row's ⋮ or long-press**, for a note that is not open | Stay on the list. The row disappears, and focus moves to the neighbour. | Same. The open note is untouched. |
| A Graph destination centred on the note, or its node detail open | The detail closes and the graph re-centres (§3.5) | Same |
| A fold, lock or process death right after | Restoration drops the id (§3.5) | Same |

### 6.8 Persistence (note)

- Creation is durable at the first commit (a draft until then).
- Edits are durable at each autosave commit, and the flush rules (§6.4) bound what is at risk to the debounce window, never a lock or a fold.
- Rename and delete: §3.7.
- A note returns only through an explicit create with its title (which re-attaches its backlinks) or a re-import (§3.2).

---

## 7. File (attachment plus extracted text), and other imported files

**Identity.** A **File** (IA §3.1) is an attachment row plus its extracted-text notes:

- The attachment row: `kind='attachment'`, `body_md = NULL`, `mime_type`, `blob_size`, `content_hash` = SHA-256 of the plaintext.
- The encrypted SKAT blob at `<filesDir>/attachments/<id>`.
- The extracted-text notes: `kind='note'` with `frontmatter.source = <attachmentId>`.

File operations take the attachment id. A derived note id resolves to its File through `source`. Today only PDFs produce this pair (`ImportServiceImpl.kt:138-171`), and image import throws (`:176-185`). Imported Markdown, text and code files are **plain notes** (the source file is not retained, `:123-136`), so their lifecycle is §6.

| Aspect | Contract |
|---|---|
| **Create** | Import (Knowledge "Import file", chat ＋). The blob is written **outside** the transaction, then the row is inserted (`VaultRepositoryImpl.kt:558-569`), then the extracted-text note is created. A failure after the blob is written leaves an orphan blob, which the unlock sweep removes (LC-09; `ATTACHMENT_ENCRYPTION.md` §2.7 designs it, and it is not implemented). A failure after the attachment row leaves a File without text: visible, and deletable. |
| **Name** | The File's name is the **attachment's** `documents.title` (the display name, such as `report.pdf`). The extracted-text note keeps its own title (PDF metadata title or filename), and `[[links]]` resolve to that text note. `KNOWLEDGE_UX_SPEC.md` decides which name the list shows. |
| **Rename** | `renameDocument(attachmentId, title)`: the title only, with `content_hash` (the SHA-256 of the bytes) untouched. It does **not** rename the extracted-text note, so links to its text keep working. `updateBody` on an attachment MUST throw `IllegalArgumentException`: today it writes `body_md` onto the attachment row and replaces the byte hash (`VaultRepositoryImpl.kt:215-224`) (LC-05). |
| **Update** | The bytes are write-once and never updated. The extracted text is editable like any note (§6.4). |
| **Delete: removed** | In **one** `transaction {}`: every extracted-text note (`source = id`), with the full §6.5 contract each, then the attachment row, with its CITE in-edges (§3.4). The blob is deleted **after commit** (§3.3 T2), and so are staged files. |
| **Delete: detached** | Nothing. A future AIOUT made from the file (it does not exist today) is detached and shows "Original file deleted". Its own spec may choose otherwise. |
| **Delete: marked missing** | Chat quotes of the extracted text: "Deleted file" chips (§6.5). |
| **Why the text goes too (L7)** | If the extracted text survived, the "deleted" PDF would stay searchable, retrievable into answers and quotable. That is exactly the surprise a privacy-first app must not have. The finding's alternative, keeping the note (`LIFECYCLE_FINDINGS.md` §15.4), is rejected. |
| **Confirmation** | `Delete "report.pdf"?` / `This permanently removes the file and its text from Skein.` plus the note's two conditional lines (§6.6), counted over the text notes. `Cancel` · `Delete`. |
| **Navigation** | As for a note (§6.7). |
| **Persistence and limits** | As §3.7. The unlinked ciphertext is not shredded: the per-file key is derivable from the master and the salt in the file (`FileAttachmentStore.kt:40-60`; migration 007 dropped per-file stored keys). |

---

## 8. Persona (contract depth)

**Identity and storage.**

- Table: `personas(id, name, system_prompt, default_model, created_at)` (`001_initial.sql:115-121`).
- `documents.persona_id` is an FK with no `ON DELETE` clause, so the database refuses to delete a persona while documents still reference it.
- `default()` returns the first-created surviving persona (`core/vault/.../persona/PersonaSql.kt:33-35`). The chat send path uses it (`DeviceVaultOpener.kt:137`).
- There is no UI. Management lives in Settings › Personas and stays hidden until `skein-3iw` lands (IA §4).

| Aspect | Contract |
|---|---|
| Create, update | These exist (`core/model/.../Personas.kt:31-50`). Renaming a persona changes nothing else: documents reference it by id. |
| **Delete: preconditions** | Refused for the last persona (as today, `core/vault/.../persona/PersonaServiceImpl.kt:147-168`). Refused while any chat is generating, because the persona's system prompt may be in the running prompt. |
| **Delete: detached (decision)** | The persona's notes and chats are **reassigned, by an explicit choice in the dialog**. The default choice is **the persona that will be default after the delete**; the alternative is "Shared with all personas" (`persona_id = NULL`). Why: `persona_id = NULL` makes documents visible to **every** persona's retrieval (`core/rag/.../rank/PprRanker.kt:94-104`). Silently nulling, as the current KDoc says (`Personas.kt:45`), is a scope promotion the user never asked for. This is a contract change: `PersonaService.delete(id, reassignTo: PersonaId?)` (LC-11). |
| **Delete: ordering** | (1) Reassign through the **repository** writer in one transaction, which publishes `Documents` ticks so lists and persona filters refresh. Today the nulling runs on the persona connection and ticks nothing (`LIFECYCLE_FINDINGS.md` §6). (2) Delete the persona row. A crash between the two leaves a persona with no documents, and retrying is safe. The FK makes the reverse order impossible. |
| Marked missing | `personas.default_model` naming a deleted model falls back to the global default (§9). |
| Kept | Messages do not record a persona, so history does not change. |
| Moving default | If the deleted persona is the default, the next first-created persona becomes the default and every chat uses its system prompt from then on. The dialog MUST say so. |
| Confirmation | `Delete persona "Therapist"?` / `Its 12 notes and chats will move to: [Default ▾]` / (if it is the default) `Chats will use "Default" from now on.` / `Cancel` · `Delete` |
| Navigation | Stay in Settings › Personas; focus moves to the neighbouring row. A chat's model & persona sheet showing the deleted persona re-resolves to the new default. |

---

## 9. Imported model (contract depth)

**Identity and storage.**

- One `models` row (`001_initial.sql:123-132` plus `009_model_origin.sql:60-68`).
- Files under `<filesDir>/models/<id>/`, plaintext GGUF, excluded from backup.
- The default pointer lives in the `ui_prefs` SharedPreferences (`core/model/.../ModelRegistry.kt:20-26`).
- No rename in v1. The friendly name is derived (`AUDIT_CHAT_MODELS_SETTINGS.md` §5).

| Aspect | Contract |
|---|---|
| **Delete: refused** | While the model is loaded, or while a store handle is open (`core/inference/.../models/ModelManager.kt:164-176`, `ImmutableModelStore.kt:385-392`). **The refusal MUST be shown.** Today the `DeleteOutcome` is discarded (`MainActivity.kt:764-770`). The recommended copy is `Unload "Qwen 2.5 3B" to delete it.` The dialog MAY offer "Unload and delete" when no chat is generating. |
| **Delete: removed** | The model directory **first**, then the registry row, then the default pointer if it named this model (the current order). A crash between the files and the row leaves a visible row with missing files, which `reconcilePaths` reports (`ModelManager.kt:185-200`) and the Models list shows as "Files missing — Remove". That is preferred over an invisible multi-GB orphan directory. |
| Kept | `messages.model_id` (provenance). Once M1 (§5.2) records it, past answers show "Removed model" when the id no longer resolves. |
| Marked missing | `personas.default_model` falls back to the global default. |
| **Confirmation (new, required)** | Today there is **none** (`feature/models/.../ModelsScreen.kt:130`). `Delete "Qwen 2.5 3B"?` / `This frees 1.9 GB. To use it again, you'll need to import it again.` / (if it is the default) `Chats will need another model.` / `Cancel` · `Delete` |
| Navigation | Stay in Models, with focus on the neighbouring row. If it was the default, the chat header and composer show "Choose a model to start" (IA §3.7). |
| Persistence | Durable once `delete` returns. Weights are public, so secure wiping is irrelevant (`LIFECYCLE_FINDINGS.md` §7). |

---

## 10. Objects that don't exist yet, and derived objects

**Collections, repositories, skills and saved workflows do not exist as stored objects.** None has a table, a repository method or a file format. The evidence is the table list in migrations 001–009, and a repository-wide search that finds these names only in comments (`LIFECYCLE_FINDINGS.md` §8). The only related surface is the interface-only `core/agent/.../tools/VaultTools.kt`, which already assumes hard deletes (`:229`). Any lifecycle UI for these objects is new storage work (L) in its own epic.

The table also records objects that are pending or only derived. This spec sets only the lifecycle rules each must follow when it is built.

| Object | Exists? | Where the IA puts it | Lifecycle rules it MUST follow when built | Size / migration |
|---|---|---|---|---|
| **Collections** | No | Knowledge › Collections (IA §7) | Membership is a join table with FKs that cascade on **both** sides. Deleting a collection never deletes its members. Deleting a member removes it from every collection. | L, yes |
| **Repositories / projects** | No | A future "Projects" destination | Deleting a repository removes Skein's derived documents, index and edges, and **never touches the external source**. It follows the §3.3 pipeline. | L, yes |
| **Skills** | No (design doc only, `docs/design/SKILL_GUARDRAILS.md`) | Palette; Settings › Skills | Deleting a skill that a workflow references marks that step missing and never deletes the workflow. | L, yes |
| **Saved workflows** | No | Palette | As skills. They are keyed by id, never by name. | L, yes |
| **Chat context bindings** | No | Chat composer ＋, context chip (`CHAT_UX_SPEC.md`) | If persisted: a join table `(chat_doc_id FK CASCADE, doc_id FK CASCADE)`, **never** chat frontmatter (which would move the chat's revision on every attach). §4.5 and §6.5 rely on this. | S–M, yes (LC-13) |
| **Drafts** (IA D7) | No (owner decision pending; `006_recovery_drafts` is a reserved number, `docs/VAULT_FORMAT.md`) | Composer, editor | Keyed by `chat_doc_id` or the note id with `ON DELETE CASCADE`. Drafts of not-yet-created objects are keyed by the draft key (C1). | S–M, yes |
| **Archive** (prompt §28, "optionally") | No | Chat history | It needs an `archived_at` column (or equivalent) plus filtering in `observeTimeline`. **Not in v1** (G16). | M, yes |
| **Trash / undo** | No | — | Rejected for v1 (§3.6). Option C is the preferred future undo. | M–L |
| **Entities** | Table exists; no production writer (`IngestPipelines.kt:50`) | Graph | When extraction ships, unreferenced entities MUST be garbage-collected with the document delete, or in the maintenance pass. Entity names are extracted text (§3.7). | S, no |
| **Tags, connections** | Derived only (`edges`) | Knowledge, Graph | They have no lifecycle of their own. They change only when text changes. Renaming a tag across notes is out of scope for v1. | — |

---

## 11. Invariants and test obligations

Each line is one test. The **name is the invariant** (snake_case, matching `VaultRepositoryContractTest`). Where each test runs:

- **JVM-C**: the shared contract suite in `:testing`, run on the linked in-memory fakes (LC-02) by `InMemoryVaultRepositoryTest`.
- **EMU**: the same suite, or an instrumented test, on the real SQLCipher driver (`core/vault/src/androidTest/.../VaultRepositoryImplContractTest.kt`), on the emulator lane.
- **JVM-F**: a feature or RAG unit test with fakes.
- **UI**: a Compose UI test (Robolectric on the JVM; instrumented where fold posture matters) and, where noted, a Roborazzi baseline.

A test marked JVM-C + EMU MUST pass on both. Tests that exist today are marked *(exists)*.

### 11.1 Repository contract: delete (JVM-C + EMU unless marked)

- [ ] `deleteDocument_removes_the_row_and_getDocument_returns_null`
- [ ] `deleteDocument_is_idempotent_for_a_missing_id`
- [ ] `deleteDocument_removes_messages_revisions_and_the_ingest_queue_entry`
- [ ] `deleteDocument_removes_the_documents_chunks` (via `chunksForDocs`)
- [ ] `deleteDocument_removes_out_edges_of_every_kind`
- [ ] `deleteDocument_rewrites_wikilink_in_edges_to_the_title_sentinel_at_weight_half`
- [ ] `deleteDocument_sentinel_matches_EdgeUpserter_for_a_non_ascii_title` (title `Émile`, sentinel `title:émile`)
- [ ] `deleteDocument_removes_cite_in_edges`
- [ ] `deleteDocument_requeues_the_surviving_document_with_the_same_title`
- [ ] `a_note_created_with_a_deleted_notes_title_regains_its_backlinks` (through `DanglingResolver.resolveFor`)
- [ ] `observeDocument_emits_null_after_delete`
- [ ] `observeMessages_emits_empty_after_the_chat_is_deleted` (**EMU is the meaningful run**: it fails on the real driver until `Messages(id)` is published)
- [ ] `observeTimeline_drops_the_deleted_document`
- [ ] `searchTitles_and_searchBodies_never_return_a_deleted_document`
- [ ] `writes_to_a_deleted_document_throw_NoSuchElementException_and_write_nothing` (covers `updateBody`, `replaceBody`, `updateFrontmatter`, `renameDocument`, `appendMessage`)
- [ ] `a_late_autosave_never_resurrects_a_deleted_note`
- [ ] `deleteDocument_keeps_other_chats_citation_excerpts` (pins the disclosed retention)
- [ ] `countChatsCiting_counts_distinct_chats_by_decoded_citation_records`
- [ ] `a_citation_into_a_deleted_document_does_not_match` *(exists)*
- [ ] `deleting_a_document_cascades_its_revisions_ahead_of_any_sweep` *(exists)*
- [ ] `transaction_nests_writes_without_deadlock` (the fake fix, N2)
- [ ] `a_file_delete_removes_the_attachment_and_its_text_notes_together`
- [ ] EMU only: `a_failed_transaction_rolls_back_every_delete_in_it`
- [ ] EMU only: `attachment_blob_is_deleted_only_after_commit` (inject a failing COMMIT; the blob stays readable)
- [ ] EMU only: `staged_export_file_of_a_deleted_document_is_purged_after_commit`
- [ ] EMU only: `a_committed_delete_survives_pool_close_and_reopen`

### 11.2 Repository contract: rename and update (JVM-C + EMU)

- [ ] `renameDocument_changes_only_the_title`
- [ ] `renameDocument_keeps_content_hash_and_captures_no_revision`
- [ ] `renameDocument_does_not_change_updated_at`
- [ ] `renameDocument_requeues_ingest_with_a_fresh_queued_at` (N9)
- [ ] `renameDocument_detaches_wikilink_in_edges_to_the_old_title_sentinel`
- [ ] `renameDocument_keeps_cite_in_edges`
- [ ] `renameDocument_trims_collapses_line_breaks_and_rejects_blank_or_over_200`
- [ ] `renameDocument_with_ifTitleIs_writes_nothing_when_the_title_changed`
- [ ] `renameDocument_on_an_attachment_keeps_its_byte_hash`
- [ ] `updateBody_rejects_attachments_and_chats`
- [ ] `replaceBody_never_writes_the_title`
- [ ] `a_frontmatter_only_edit_requeues_ingest` (N4)
- [ ] `export_and_saf_render_the_current_title_into_frontmatter` (N8)
- [ ] `a_saf_round_trip_does_not_revert_a_rename` (N8)
- [ ] `retitling_alone_does_not_change_the_revision_hash` *(exists)*

### 11.3 Emulator lane only (real SQLCipher, FTS5, vec0, migrations)

- [ ] `cascade_leaves_no_chunks_fts_matches_or_vec_rows` (G21; there is no device test of this today)
- [ ] `pragma_secure_delete_is_on_for_every_pool_connection` (pins `SQLITE_SECURE_DELETE`)
- [ ] `migration_010_enables_fts5_secure_delete`
- [ ] `after_migration_010_a_deleted_token_leaves_no_bytes_in_chunks_fts_data`
- [ ] `migration_010_optimize_purges_residue_written_before_it`
- [ ] `migration_010_is_idempotent_on_rerun`
- [ ] `migration_010_keeps_the_integrity_catalogue_green`
- [ ] `fts_delete_cost_with_secure_delete_stays_within_budget` (measured; the budget is set by the E2 owner)

### 11.4 Ingest and RAG (JVM-F with linked fakes; a race variant on EMU)

- [ ] `ingest_racing_a_delete_leaves_no_edges_for_the_deleted_id`
- [ ] `index_writes_for_a_missing_source_document_are_skipped`
- [ ] `orphan_edge_sweep_deletes_edges_from_missing_sources`
- [ ] `orphan_edge_sweep_deletes_edges_to_missing_documents_and_requeues_their_sources`
- [ ] `orphan_edge_sweep_is_idempotent`
- [ ] `ingest_stamps_chunks_with_the_revision_of_the_body_it_chunked` (N3)
- [ ] `retrieval_never_returns_a_chunk_of_a_deleted_document` (`PprRanker` already drops unreadable documents, `PprRanker.kt:101-104`)

### 11.5 Chat and editor behaviour (JVM-F)

- [ ] `an_unsent_new_chat_leaves_no_document`
- [ ] `the_first_send_creates_the_chat_and_its_first_message_together`
- [ ] `provisional_title_examples` (a fixed table: Markdown, wikilinks, a 48-char cut, the empty fallback)
- [ ] `legacy_chat_titles_are_backfilled_once_and_only_while_still_Chat`
- [ ] `a_generated_title_never_overwrites_a_user_rename`
- [ ] `sending_into_a_deleted_chat_shows_the_gone_state_without_crashing`
- [ ] `an_assistant_turn_whose_chat_was_deleted_is_dropped_without_crashing`
- [ ] `delete_is_disabled_while_the_chat_is_generating`
- [ ] `retry_does_not_append_a_second_user_message` (replaces the assertion at `ChatScreenTest.kt:318-325`)
- [ ] `assistant_messages_record_the_model_id`
- [ ] `lastOutcome_of_a_deleted_chat_is_cleared`
- [ ] `the_context_panel_never_shows_another_chats_retrieval`
- [ ] `a_citation_to_a_deleted_document_renders_source_deleted_and_opens_nothing`
- [ ] `an_untouched_new_note_leaves_no_document`
- [ ] `autocomplete_create_links_to_an_existing_note_instead_of_duplicating`
- [ ] `tapping_an_unresolved_link_never_creates_a_note_silently`
- [ ] `a_title_edit_writes_once_per_commit_not_per_keystroke`
- [ ] `leaving_the_editor_flushes_pending_edits`
- [ ] `locking_flushes_pending_edits_first`
- [ ] `deleting_the_open_note_discards_pending_edits_without_error`
- [ ] `two_surfaces_on_one_note_share_one_editor_state`

### 11.6 Navigation after delete (UI; Roborazzi baselines for dialogs and gone states)

- [ ] `deleting_the_open_chat_on_compact_lands_on_the_new_chat_landing`
- [ ] `deleting_the_open_chat_on_expanded_keeps_the_list_and_opens_no_other_chat`
- [ ] `deleting_a_history_row_moves_focus_to_the_neighbouring_row`
- [ ] `deleting_a_note_opened_from_a_citation_returns_to_the_same_chat_position`
- [ ] `deleting_the_open_note_on_expanded_closes_its_connections_pane`
- [ ] `every_back_stack_entry_for_a_deleted_id_is_pruned`
- [ ] `a_back_stack_restored_after_process_death_drops_deleted_ids`
- [ ] `a_back_stack_restored_after_unlock_drops_deleted_ids`
- [ ] `a_stale_entry_renders_the_was_deleted_state_not_a_crash`
- [ ] `a_new_note_draft_restores_as_a_draft_not_as_deleted`
- [ ] `palette_results_drop_a_deleted_document_live`
- [ ] `graph_recentres_when_its_centre_is_deleted`
- [ ] `rename_updates_header_row_and_recents_without_reopening`
- [ ] `delete_dialog_copy_matches_spec_for_chat_note_file_persona_and_model`
- [ ] `delete_dialog_conditional_lines_appear_only_when_counts_are_positive`
- [ ] `back_and_escape_cancel_a_delete_dialog_and_enter_does_not_delete`
- [ ] `model_delete_asks_for_confirmation_and_shows_refusals`
- [ ] `fold_and_unfold_right_after_a_delete_keep_the_pruned_state` (instrumented, Fold)

---

## 12. Backend and integration work, ordered by dependency

In the table, the "Class" column separates **storage and API work that belongs to the vault and RAG owners** (E2, E5) from **narrowly scoped UI integration** that this workstream may do (UI). An LC number is the implementation bead in §13. G-numbers refer to `LIFECYCLE_FINDINGS.md` §14.

| # | Item | Gaps | Size | Migration → emulator lane | Owner | Class | Needed by | Depends on |
|---|---|---|---|---|---|---|---|---|
| LC-01 | Pin missing-id write semantics (`NoSuchElementException`) in KDoc; align the fake | N1 | S | No | E2 | API | Wave 4 | — |
| LC-02 | Linked in-memory vault: the repository and index fakes share chunks and edges, and a delete cascades; `transaction {}` becomes re-entrant | N2 | S–M | No | E2 (`:testing`) | API (test harness) | Wave 4 | LC-01 |
| LC-03 | Delete ordering and signals: `TxContext.afterCommit`; blob delete after commit; staged-export purge after commit, plus `sweepIfExpired` purging by prefix when the row is missing; publish `Messages` and `IngestQueue` | G5, G6 | S | No | E2 | API + Storage (privacy) | Wave 5 | LC-02 |
| LC-04 | Edge detach in the delete transaction (§3.4), survivor re-queue, `countChatsCiting` | G3 | S–M | **No** (done in code; the trigger variant would need 010 and is rejected) | E2 | API | Wave 5 | LC-02, LC-03 |
| LC-05 | `renameDocument(id, title, ifTitleIs)`, `replaceBody`, `updateBody` kind guard, frontmatter `title` rendered from the column at export and SAF read, SAF write renames through `renameDocument` | G1, G13, G19, N7–N9 | S–M | No | E2 | API | Wave 5 | LC-01, LC-04 |
| LC-06 | Ingest integrity: existence check inside index writes; orphan edge and vec sweep in the maintenance pass; stamp chunks from the read snapshot; `updateFrontmatter` re-queues ingest | G4, N3, N4 | S | No | E5 (+E2 for `updateFrontmatter`) | API | Wave 5 | LC-02 |
| LC-07 | **Migration 010**: FTS5 `secure-delete` plus one `optimize` | G11 | S | **Yes, emulator lane** | E2 | **Storage (privacy)** | **Wave 5 gate** | — |
| LC-08 | Emulator-lane lifecycle suite (§11.1 EMU, §11.3) | G21 | S | Runs 010 | E2 | Test | **Wave 5 gate** | LC-03, LC-04, LC-05, LC-07 |
| LC-09 | File delete (one transaction over the text notes plus the attachment) and the orphan-blob unlock sweep | G20 | S–M | No | E2 | Storage | Wave 6 | LC-02, LC-03 |
| LC-10 | SAF `notifyChange` on delete and rename | G22 | S | No | E2 | API | Wave 5 | LC-03 |
| LC-11 | `PersonaService.delete(id, reassignTo)`; reassignment through the repository writer with a tick | G23 | S | No | E2 | API (contract change) | Wave 9 | LC-01 |
| LC-12 | Purge a deleted document's quotes from chat history | G10 | M | No (JSON rewrite) | E2 | Storage (privacy) | Post-v1 (owner priority) | LC-04 |
| LC-13 | Chat context bindings table with cascades on both sides (only if `CHAT_UX_SPEC.md` persists bindings) | — | S–M | **Yes, emulator lane** | E2 | Storage | Wave 7 | — |
| LC-14 | Optional: a best-effort `wal_checkpoint(TRUNCATE)` after a user delete | — | S | No | E2 | Storage (privacy) | Wave 5 (optional) | LC-03 |
| LC-20 | Open by kind; id-only back-stack prune, restore filter and gone states | G7, G18 | S (in the Wave 3 shell) | No | E6 | **UI** | Wave 3 | — |
| LC-21 | Chat send integrity: `SendPipeline.activeChatId`; missing-chat handling in `ChatViewModel` and `SendPipeline`; `lastOutcome` filtered and cleared; retry without a duplicate USER row; record `model_id` | G8, G17 (part) | S | No | E6 (`feature/chat`) | **UI** (plus a small `SendPipeline` API) | Wave 4 | LC-01 |
| LC-22 | Chat lifecycle UI: lazy creation, provisional title, legacy backfill, rename and delete dialogs, delete coordinator, navigation after delete | G2, G14 (hook), G15 | M | No | E6 | **UI** | Wave 5 | LC-03, LC-04, LC-05, LC-07, LC-08, LC-20, LC-21 |
| LC-23 | "Source deleted" citation state (chips, sheet) and missing sources in the context inspector | G9 (G9b later) | S | No | E6 | **UI** | Wave 5 (inspector: Wave 7) | LC-20 |
| LC-24 | Editor lifecycle: flush on dispose and before lock, discard on delete, title through `renameDocument` on commit, saves through `replaceBody`, one `EditorState` per id, observe external writes | — | S–M | No | E7 | **UI** | Wave 6 | LC-05 |
| LC-25 | Note and File lifecycle UI: lazy new note, rename dialog with disclosure and collision lines, delete dialogs, explicit create from an unresolved link, autocomplete dedupe | G2, G25 | M | No | E6/E7 | **UI** | Wave 6 | LC-04, LC-05, LC-09, LC-24 |
| LC-26 | Live consumers: the graph re-queries on the `Documents` tick and re-centres; palette and search results re-query | — | S | No | E6 | **UI** | Waves 6, 8, 10 | LC-04 |
| LC-27 | Model delete confirmation and refusal feedback | G24 | S | No | E6 | **UI** | Wave 2.5 (it can land at any time) | — |
| LC-28 | Persona delete UI (Settings › Personas) | G23 | S | No | E6 | **UI** | Wave 9 | LC-11, `skein-3iw` |

**Only two items need a migration, and so the emulator lane:** LC-07 (always) and LC-13 (only if bindings are persisted). The drafts table (IA D7) and archive are also migrations if the owner adopts them, but they are outside this spec's v1 scope (§10).

**Release gates for the first delete UI (Wave 5):** LC-03, LC-04, LC-05, LC-07 and LC-08 green on the emulator lane, plus LC-20 and LC-21. Wave 6 (notes and files) adds LC-09 and LC-24.

---

## 13. Implementation beads

These are proposed for `skein-xtov` and its owners' epics. They are not filed yet. The ids are placeholders. Each bead's acceptance criteria are the §11 tests named in its scope.

| Bead | Title | Scope (acceptance = the named §11 tests) | Size | Wave | Depends on |
|---|---|---|---|---|---|
| LC-01 | vault: pin NoSuchElementException for writes to a missing document | KDoc in `Vault.kt`; fake alignment; `writes_to_a_deleted_document_throw_…` | S | 4 | — |
| LC-02 | testing: linked in-memory vault and re-entrant fake transaction | `InMemoryVaultRepository` plus `InMemoryIndexStore` share state; delete cascades chunks and edges; `transaction_nests_writes_without_deadlock` | S–M | 4 | LC-01 |
| LC-03 | vault: after-commit file deletion and complete delete signals | `TxContext.afterCommit`; blob and staged-file purge after commit; `Messages` and `IngestQueue` events; §11.1 EMU blob and staging tests | S | 5 | LC-02 |
| LC-04 | vault: detach graph edges on delete | §3.4 DML; survivor re-queue; `countChatsCiting`; §11.1 edge tests | S–M | 5 | LC-02, LC-03 |
| LC-05 | vault: renameDocument, replaceBody, kind guards, title at serialization boundaries | §11.2 | S–M | 5 | LC-01, LC-04 |
| LC-06 | rag: ingest integrity under delete and edit | Existence checks in index writes; orphan sweep; N3 and N4 fixes; §11.4 | S | 5 | LC-02 |
| LC-07 | vault: migration 010 FTS5 secure-delete (emulator lane) | Migration, `INDEX.txt`, `VAULT_FORMAT.md` changelog, `PRIVACY.md` claim; §11.3 | S | 5 (gate) | — |
| LC-08 | vault: emulator-lane lifecycle suite | Run §11.1 EMU and §11.3 on the lane; record timings | S | 5 (gate) | LC-03, LC-04, LC-05, LC-07 |
| LC-09 | vault: file delete and orphan-blob sweep | One transaction over the text notes plus the attachment; unlock sweep per `ATTACHMENT_ENCRYPTION.md` §2.7 | S–M | 6 | LC-02, LC-03 |
| LC-10 | vault: SAF notifyChange on delete and rename | `VaultDocumentsProvider` | S | 5 | LC-03 |
| LC-11 | vault: persona delete with explicit reassignment | Contract change plus repository tick | S | 9 | LC-01 |
| LC-12 | vault: purge quotes of a deleted document (`skein-koda`) | A user-visible action, plus a citation-record rewrite | M | post-v1 | LC-04 |
| LC-13 | vault: chat context bindings table (conditional) | Migration with cascades on both sides; emulator lane | S–M | 7 | `CHAT_UX_SPEC.md` decision |
| LC-14 | vault: checkpoint after a user delete (optional) | Best-effort TRUNCATE | S | 5 | LC-03 |
| LC-20 | ux: open by kind and id-only back-stack hygiene | Prune API, restore filter, gone states; §11.6 back-stack tests | S | 3 | Wave 3 shell |
| LC-21 | ux: chat send integrity | §11.5 chat send tests | S | 4 | LC-01 |
| LC-22 | ux: chat lifecycle (create, title, rename, delete) | §4; §11.5 creation and title tests; §11.6 chat navigation tests | M | 5 | LC-03, LC-04, LC-05, LC-07, LC-08, LC-20, LC-21 |
| LC-23 | ux: source-deleted citation state | §4.5 and §6.5 citation rows | S | 5 | LC-20 |
| LC-24 | editor: lifecycle-safe editing | §6.4; §11.5 editor tests | S–M | 6 | LC-05 |
| LC-25 | ux: note and file lifecycle | §6, §7; §11.6 note tests | M | 6 | LC-04, LC-05, LC-09, LC-24 |
| LC-26 | ux: live graph and palette after delete and rename | §3.4 consumers, §3.5 | S | 6, 8, 10 | LC-04 |
| LC-27 | ux: confirm model delete and show refusals | §9 | S | 2.5 | — |
| LC-28 | ux: persona delete in Settings › Personas | §8 | S | 9 | LC-11, `skein-3iw` |

---

## 14. Open questions for the owner

1. **Undo (L1).** Confirm-only in v1, with no undo and no trash. If you want an undo later, the design is option C: re-insert from an in-memory snapshot, so a crash makes the delete stick rather than bring the object back. Sign-off requested.
2. **Deleting a file removes its extracted text (L7).** Is that acceptable, or should the dialog offer "Keep the text as a note"? That would be an extra choice on a destructive dialog, and this spec argues against it.
3. **Persona delete reassigns explicitly (§8).** The default target is the next default persona, not "shared". This changes `Personas.kt:45`'s "set to NULL".
4. **Rename leaves Recent order alone (L4).** Renaming an old chat does not move it to Today. The alternative, bumping `updated_at`, also works if N9's `queued_at` rule is kept.
5. **Links break on rename (§6.3).** v1 discloses the count and does not rewrite `[[Old]]`. Should rename-with-link-update (G12, M: it edits other notes and moves their revisions) move into Wave 6?
6. **Migration 010 as a Wave 5 release gate (L9).** It is a one-way FTS5 format change (N6). Confirm that no tool you use reads `vault.db` with SQLite older than 3.42.
7. **Quotes of deleted notes stay in chats (L3).** They are disclosed, and the purge (LC-12) is post-v1. Should it be earlier? It is the one residue visible in the UI (§3.7).
8. **Legacy chats titled "Chat" (C4)** are re-titled once, from their first message, at the next unlock. Any objection to a one-time automatic rename?

---

## Appendix A — cascade and FTS5 residue, re-run for this spec

This was run on the host with `sqlite3` 3.51.0 (2025-06-12), not on the SQLCipher 4.17.0 / SQLite 3.53.3 device build. The DDL follows `001_initial.sql:65-93,162-169`. `chunks_vec` is stood in for by an ordinary table, because the host has no vec0. Script: `scratchpad/lc/t.sql`, outside the repo.

```
-- delete document A (chunk "the walrus sleeps zanzibarx"; edges A→B and B→A)
chunks|1                          ← only B's chunk remains
vec|0
match|0                           ← FTS no longer matches
residue|2                         ← the literal token is still in 2 chunks_fts_data blocks
edges_left|A|B                    ← out-edge of the deleted doc survives
edges_left|B|A                    ← in-edge to the deleted doc survives
residue_after_optimize|0          ← one 'optimize' purges earlier residue
-- with INSERT INTO chunks_fts(chunks_fts, rank) VALUES('secure-delete', 1)
residue_securedelete|0            ← a cascaded delete leaves no token bytes
config|secure-delete|1
config|version|5                  ← format bumped by the first secure delete (N6)
residue_rewrite_securedelete|0    ← re-chunking (delete + insert) leaves none either
-- reopen: SELECT k, v FROM chunks_fts_config → secure-delete|1, version|5 (persists)
-- fresh table: version|4; after setting the option: still 4; after the first delete: 5;
-- after switching the option back to 0: still 5 (irreversible)
```

## Appendix B — files read for this pass

In addition to `LIFECYCLE_FINDINGS.md` Appendix B:

- `docs/research/SKEIN_UI_UX_OVERHAUL_PROMPT.md` §28–33, §47–51
- `docs/ux/INFORMATION_ARCHITECTURE.md`
- `docs/ux/audit/AUDIT_CHAT_MODELS_SETTINGS.md` §3–4
- `docs/ux/audit/AUDIT_KNOWLEDGE.md` §3–5
- `docs/ux/audit/AUDIT_SHELL.md` §2.4, §5
- `docs/VAULT_FORMAT.md` §2–3 and §7
- `docs/PRIVACY.md` §1, §6, §7
- `docs/BACKUP_EXCLUSIONS.md`
- `docs/design/export-plaintext-lifetime.md`
- `docs/design/ATTACHMENT_ENCRYPTION.md` §2.7
- `docs/design/POST_REVIEW_RESOLUTIONS.md` §1.2–1.3
- `core/model/.../Vault.kt` (`VaultRepository`, `IndexStore`, `IngestReason`, `EdgeKind`), `Personas.kt`
- `core/vault/...`: `repository/VaultRepositoryImpl.kt`, `repository/VaultSql.kt`, `extract/EdgeUpserter.kt`, `extract/DanglingResolver.kt`, `transfer/ImportServiceImpl.kt`, `export/ExportServiceImpl.kt`, `export/stage/StagedPlaintextSweep.kt`, `provider/VaultDocumentsProvider.kt`, `provider/VaultDocumentsBackend.kt`, `provider/ProviderCursors.kt`, `persona/PersonaServiceImpl.kt`, `persona/PersonaSql.kt`, `blob/FileAttachmentStore.kt`, `db/SkeinSQLiteDriver.kt`, `db/migrations/Migrator.kt`, `lifecycle/VaultLifecycle.kt`; migrations 001, 003, 005, 008, 009 and `INDEX.txt`
- `core/rag/...`: `ingest/IngestPipeline.kt`, `rank/RetrievedAssembler.kt`, `rank/PprRanker.kt`, `chat/CitationRecords.kt`
- `core/inference/.../models/ModelManager.kt`
- `feature/chat/...`: `SendPipeline.kt`, `ChatViewModel.kt`, `ChatScreen.kt`
- `feature/editor/...`: `notetab/NoteTabState.kt`, `EditorState.kt`, `backlinks/BacklinksState.kt`
- `feature/graph/...`: `GraphState.kt`, `GraphModels.kt`
- `feature/shell/...`: `tabs/TabsState.kt`, `nav/BuiltinCommands.kt`
- `testing/...`: `InMemoryVaultRepository.kt`, `InMemoryIndexStore.kt`, `VaultRepositoryContractTest.kt`
- `core/vault/src/androidTest/.../VaultRepositoryImplContractTest.kt`
- `app/...`: `MainActivity.kt`, `vault/DeviceVaultOpener.kt`, `ingest/IngestPipelines.kt`, `ingest/IngestScheduler.kt`, `notify/*`
- `native/sqlite/CMakeLists.txt`, `native/sqlite/amalgamation/sqlite3.h`
