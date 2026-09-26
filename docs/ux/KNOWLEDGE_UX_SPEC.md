# Skein — Knowledge UX Spec

**Bead:** `skein-xtov.17` · **Epic:** `skein-xtov` (UX overhaul) · **Authority:** `docs/research/SKEIN_UI_UX_OVERHAUL_PROMPT.md` §1–3, §20–23, §30–33, §35, §40, §43 (flows 12–18, 21), §45–47, §51
**Keystone:** `docs/ux/INFORMATION_ARCHITECTURE.md` ("the IA"). This spec implements the IA's Knowledge and Graph destinations; §19 lists the places where it clarifies or questions the IA.
**Status:** Proposed. Nothing here is implemented yet. §20 lists the decisions that amend the canonical spec (`docs/superpowers/specs/2026-09-19-skein-design.md` §8.5–8.6) and need the owner's sign-off.
**Evidence:** `docs/ux/audit/AUDIT_KNOWLEDGE.md` (finding ids `K-P0-n`, `K-P1-n`, `K-P2-n`), `MATRIX_KNOWLEDGE.md`, `LIFECYCLE_FINDINGS.md` (§4, §5, §11, §13–§15; gap ids `Gn`), `DEVICE_BEFORE_PASS.md`, and `ux-baselines/before/*/{note,note-backlinks,graph,timeline,timeline-empty}*.png`. The code was read at `5538b4f`.
**Sibling specs** (referenced, not specified here): `DESIGN_SYSTEM.md` (tokens, type scale, icons, dialog, snackbar and sheet components, copy rules), `ADAPTIVE_LAYOUT_SPEC.md` (window classes, pane widths, scaffolds), `CHAT_UX_SPEC.md` (composer ＋, context chip, inspector, citation chips), `OBJECT_LIFECYCLE_SPEC.md` (what rename and delete do to data), `UX_TEST_PLAN.md`, `MAC_UX_LAB_PLAN.md`.

**What this spec owns:** everything a user sees and does with notes, files, their connections and the graph; the UI contract for creating, renaming and deleting them; the Knowledge row and search components that the attach picker and the command palette reuse.

Path aliases follow the audit: `ED/` = `feature/editor/src/main/kotlin/app/skein/feature/editor/`, `GR/` = `feature/graph/…/graph/`, `TL/` = `feature/timeline/…/timeline/`, `CV/` = `core/vault/src/main/kotlin/app/skein/core/vault/`.

---

## 0. Decisions in one screen

| Topic | Decision |
|---|---|
| **Home** | One Knowledge list–detail for notes, files and AI outputs. Chats are not in it; they live in Chat. Every item opens by kind. |
| **List** | Sorted by *last edited* and grouped by day. Filters: **Notes · Files · AI outputs** (single-select; AI outputs appears only when some exist). **Tags** and **Persona** are Level-2 chips. There is no sort menu and no multi-select in v1. |
| **Search** | One field searches titles (contains, ranked) and text (full-text, with a snippet and the match highlighted). Title matches are immediate; text matches wait until an item is prepared for search, and the results say so. There is no fuzzy subsequence matching. |
| **A note's name** | The `documents.title` column is the **only** source of truth. It is edited in place as an inline title at the top of the note, or through ⋮ → Rename…. Neither the body's `# heading` nor frontmatter `title:` ever drives it. |
| **Rename** | A rename is saved on Enter, on focus loss or on leaving the note, never per keystroke. In the same step, `[[Old]]` links in other notes are rewritten to `[[New]]`, and a snackbar offers Undo. If the rewrite bead slips, the fallback is LIFECYCLE §11's disclosure ("N notes link to this title…"). |
| **New note** | A visible **New note** action (FAB, palette, Ctrl+Shift+N) opens a title-first editor. The note is created on the first character typed. A note left completely blank is discarded. A note left untitled is named from its first line. |
| **First-keystroke safety** | The editor's text buffer holds the **body only**. Properties (frontmatter) are shown read-only in Properties. No input can land before the body (the fix for K-P0-2). |
| **Delete** | ⋮ → Delete… → a confirmation with consequence lines → an immediate, permanent delete. **There is no Undo:** the delete is hard, the vault is excluded from backups, and a deferred delete would be silently undone by process death. A snackbar reads "Deleted “X”". |
| **Files** | Import from Knowledge: PDF, Markdown, plain text, code. Images are left out of the picker and refused politely until image import exists. A PDF is **one** row, a *File*, that opens a viewer with **Pages** and **Text**. Text, Markdown and code imports become notes, because the original isn't kept. |
| **Connections** | Linked from · Links to · Properties · a local-graph thumbnail with **Open in Graph** · (Unlinked mentions, P2). It is an extra pane on Expanded and a bottom sheet on Compact. The Backlinks strip at the bottom of every note is removed. |
| **Graph** | A destination centred on a note (the last one opened, else the last one edited). **A tap selects**: a detail sheet or pane offers Open and Centre here, and a single tap never navigates. Kind is shown by colour **and** icon. The key starts collapsed. Layout runs off the main thread, and the simulation settles and stops. A **List** view is the accessible equivalent. |
| **Citations** | A citation opens its source by kind, scrolled to the passage with the passage highlighted. The exact locator is used when the revision matches; otherwise Skein finds the quote; if the quote is gone, it says so. Back returns to the chat at the same place. |

---

## 1. Vocabulary

The IA glossary (§3.1) applies. These are the terms this spec adds or pins down. `DESIGN_SYSTEM.md` owns the final copy rules.

| User-facing term | Means | Never shown instead |
|---|---|---|
| **Knowledge** | The destination: your notes and files | vault, documents, timeline |
| **Note** | A `documents` row of kind `note` that isn't the text of a file | document, tab, preview |
| **File** | An imported original (today: PDF) together with its extracted text | attachment, blob |
| **AI output** | kind `aiout` | aiout |
| **Title** | A note's name (`documents.title`) | filename, frontmatter title |
| **Properties** | A read-only view of the frontmatter that means something to a user: tags, source file, persona, created, edited | frontmatter, YAML, `id`, `— id 01a0dceb… ▸` |
| **Connections** | Linked from + Links to + local graph + properties | backlinks drawer, neighbourhood, edges |
| **Linked from** | Notes and chats whose text contains `[[this title]]` | backlinks (Level 1), in-edges |
| **Links to** | Notes this note links to | out-edges |
| **Missing note** | A `[[Title]]` with no note behind it | unresolved sentinel, `title:` |
| **Preparing for search…** | The item is waiting in `ingest_queue` | indexing, ingest, chunking, embedding |
| **No readable text** | A PDF with no text layer | extraction failed |
| **Source deleted** | A citation whose document is gone | Note not found |
| **Centre / Centre here** | The note the graph is built around | focus doc id, seed |
| **Key** | The graph legend | legend |

"Backlinks" may appear in Level-2 copy such as palette command descriptions, per the IA.

---

## 2. Layout per window size

`ADAPTIVE_LAYOUT_SPEC.md` owns the window classes, the pane widths and the scaffold implementation. This section only says which Knowledge surfaces appear where.

| Window | Knowledge | A note or file | Connections | Graph | Node detail |
|---|---|---|---|---|---|
| **Compact** (Fold outer ≈ 411–524 dp, phones, split halves) | Full-screen list | Full-screen route pushed over the list, with real Back | Modal bottom sheet (opens half height, drags to full) | Full-screen canvas | Bottom sheet |
| **Medium** | List → item (one pane). Two panes if `ADAPTIVE_LAYOUT_SPEC.md`'s Medium prototype says so (IA §8 Q1) | Full width, capped at a readable width | Sheet | Canvas | Side sheet |
| **Expanded** (Fold inner ≈ 852–1043 dp) | **List (≈ 360 dp) \| Item** | Detail pane | **Extra pane (≈ 360 dp) that takes the list's place**; Back or ✕ restores the list | **Canvas \| Node detail (≈ 360 dp)** | Supporting pane |
| **Large** (≥ 1200 dp) | List \| Item \| Connections | Detail pane | Third pane | Canvas \| Node \| Related | Pane |

**Pane-or-sheet rule for Connections** (same shape as LobeChat's `CONVERSATION_KEEP_WIDTH`, `LOBECHAT.md` L10): the pane is shown only if the note keeps at least **400 dp** after the rail and the pane. Otherwise Connections uses the sheet. At 852 dp (stock density), 852 − 80 − 360 = 412 dp, so the pane is shown. At the owner's 1006 dp the note keeps 566 dp. The final threshold is `ADAPTIVE_LAYOUT_SPEC.md`'s call.

### 2.1 Wireframes

Glyphs here are sketches. Real icons are Material Symbols chosen in `DESIGN_SYSTEM.md`.

**Compact — Knowledge list**
```text
┌─────────────────────────────┐
│ ☰  Search knowledge      ⤒  │  top bar is a search bar; ⤒ = Import file
├─────────────────────────────┤
│ (Notes) (Files) (Tags ▾)    │  AI outputs chip only if any exist
│ Today                       │
│ ▯ Meeting notes 26 Sep    ⋮ │
│   Decided: Roborazzi base…  │
│   12 min                    │
│ ▯ Fold launch plan        ⋮ │
│   Targets M2 for the ask…   │
│   3 h · ◌ Preparing for se… │
│ Yesterday                   │
│ ▤ Quarterly report 2025   ⋮ │
│   PDF · report.pdf          │
│               ┌───────────┐ │
│               │✎ New note │ │
└───────────────┴───────────┴─┘
```

**Compact — a note, keyboard up**
```text
┌─────────────────────────────┐
│ ←                  Saved ✦ ⋮│  ✦ = Connections
├─────────────────────────────┤
│ Fold launch plan            │  inline title, document-title style
│ #fold #launch · Properties ›│  only if the note has properties to show
│                             │
│ Targets **M2** for the ask| │  the caret line shows raw Markdown
│ Checklist                   │
│ • Import the model …        │
├─────────────────────────────┤
│         (keyboard)          │
└─────────────────────────────┘
```
Today the chrome above the keyboard takes about 200 dp: command bar, "Recent ▾", a filled title field, the id chip and the Backlinks bar (K-P1-14). This layout takes one top bar.

**Expanded — Knowledge (Fold inner, portrait)**
```text
┌──┬────────────────────────┬──────────────────────────────────────────┐
│✎ │ Knowledge  Import file │                            Saved  ✦  ⋮   │
│  │ ⌕ Search knowledge     │ Fold launch plan                         │
│💬│ (Notes)(Files)(Tags ▾) │ #fold #launch · Properties ›             │
│📚│ Today                  │                                          │
│✦ │▌Fold launch plan     ⋮ │ Targets M2 for the ask path. Owner smoke │
│▦ │ Meeting notes 26 Sep ⋮ │ test on the Pixel 9 Pro Fold …           │
│  │ Yesterday              │ Checklist                                │
│⚙ │ Quarterly report 2…  ⋮ │ • Import …                               │
│  │         ┌───────────┐  │                                          │
│  │         │✎ New note │  │                                          │
└──┴─────────┴───────────┴──┴──────────────────────────────────────────┘
 rail  list ≈ 360 dp          item ≈ 566 dp at 1006 dp
```

**Expanded — Connections open** (the list yields its place; Back or ✕ restores it)
```text
┌──┬───────────────────────────────────┬─────────────────────────────┐
│  │                    Saved  ✦  ⋮    │ Connections               ✕ │
│  │ Fold launch plan                  │ LINKED FROM · 3             │
│  │ …                                 │ ▯ Weekly review             │
│  │                                   │   Shipped: citations… [[Fo… │
│  │                                   │ 💬 Chat about quantisation  │
│  │                                   │ LINKS TO · 2                │
│  │                                   │ ▯ Sync design               │
│  │                                   │ ○ Model picker   [Create]   │
│  │                                   │ LOCAL GRAPH  ╱◉╲  (thumb)   │
│  │                                   │ [Open in Graph]             │
│  │                                   │ PROPERTIES                  │
│  │                                   │ Tags  #fold #launch         │
│  │                                   │ Created Sep 17 · Edited 3 h │
└──┴───────────────────────────────────┴─────────────────────────────┘
```

**Compact — Graph with a node selected**
```text
┌─────────────────────────────┐
│ ☰  Graph                 ≣  │  ≣ = Show as list
│    Fold launch plan ▾       │  tap to centre on another note
├─────────────────────────────┤
│      ○ Meeting notes 2…     │
│           ╲                 │
│ ○ Sync de…─◉ Fold launch pl…│
│           ╱   ╲             │
│   ◆ #fold      ● Weekly rev…│  ● selected: thicker ring, label kept
│ (Key)                 (Fit) │
├─────────────────────────────┤  appears after a tap
│ ▯ Weekly review             │
│ Note · edited yesterday     │
│ Links to Fold launch plan   │
│ [ Open ]    [ Centre here ] │
└─────────────────────────────┘
```

**Expanded — Graph**
```text
┌──┬───────────────────────────────────────┬─────────────────────────┐
│  │ Graph · Fold launch plan ▾         ≣  │ ▯ Fold launch plan      │
│  │                                       │ Note · edited 3 h ago   │
│  │   ○ Meeting notes 26 Sep              │ Targets M2 for the ask… │
│  │          ╲                            │ Linked from 3 · Links 2 │
│  │ ○ Sync design ── ◉ Fold launch plan   │ [ Open ]                │
│  │          ╱          ╲                 │ CONNECTED HERE          │
│  │     ◆ #fold     ○ Weekly review       │ ▯ Weekly review   links │
│  │ (Key)                           (Fit) │ ◆ #fold           tag   │
└──┴───────────────────────────────────────┴─────────────────────────┘
```

---

## 3. The Knowledge list

### 3.1 Top of the list

- **Compact:** the top bar is a search bar. It has ☰ (drawer) at the leading edge, the placeholder **"Search knowledge"**, and a trailing **Import file** icon button. Tapping the field opens the full-screen search view (§4). The drawer and the placeholder both name the destination, so there is no separate "Knowledge" title row.
- **Medium/Expanded:** the list pane has a header row, "Knowledge" (titleLarge) plus an **Import file** text button, and below it a docked search field, "Search knowledge".
- **Primary action:** an extended FAB, **✎ New note**. On Compact it sits at the bottom end and shrinks to an icon while the list scrolls down. On Expanded it is anchored in the list pane. There is no ⋮ in the list's top bar, because there is nothing to put in it (a menu with nothing useful in it is a dead control).

### 3.2 Filters

| Chip | Shows | When visible |
|---|---|---|
| **Notes** | kind `note`, excluding a file's text (§3.5) | always |
| **Files** | kind `attachment`, as File rows (§3.5) | always |
| **AI outputs** | kind `aiout` | only when at least one exists. Nothing writes `aiout` in production today (§19 I-3) |
| **Tags ▾** (Level 2) | opens a sheet of tags; picking one filters by it | when any tag exists |
| **Persona ▾** (Level 2) | as `TL/FilterBar.kt`'s persona dropdown | when two or more personas exist |

- The kind chips are **single-select**. Tapping the selected chip clears it, and "nothing selected" means all kinds. This replaces the timeline's multi-toggle, where turning the last kind off silently reset to all (`TL/TimelineState.kt` `toggleKind`).
- The tag and persona chips combine with a kind chip.
- The state of every chip is held in the destination's saved state, so it survives fold, unfold and a trip into a note and back.
- The tag list comes from the tags on loaded items, as `TimelineState.tags` does today. That list is incomplete for large vaults; see ask **KB-10**.

### 3.3 Row anatomy

One component, `KnowledgeRow`, is used by the list, search results, the attach picker (§13), Connections (§10) and the palette (§14). It has a `density` parameter: `Standard` or `Compact`.

| Slot | Standard (list, search) | Compact (picker, palette, Connections) | Rules |
|---|---|---|---|
| Leading | 40 dp tonal square holding a kind icon: note, file (PDF), AI output, chat (Connections only) | 24 dp icon | The icon comes from `DESIGN_SYSTEM.md`, not an emoji (the audit found ✧, 🤖 and red for the same kind, K-P2-1). Content description = the kind name |
| Title | `titleMedium`, 1 line, end ellipsis. A blank title reads **"Untitled"** in the muted style | same | In search, the matched characters are emphasised (§4.3) |
| Snippet | 2 lines of `bodyMedium`, muted, Markdown stripped with `TL/TimelineFormatting.kt` `previewOf`. For a File: the first line of its text | 1 line or none | In search, the FTS snippet with the terms highlighted replaces it |
| Meta line | `labelMedium`, muted: relative time (`relativeTime`: "12 min", "3 h", "Sep 17"), then state, then persona chip | time only | File: "PDF · report.pdf" (the original file name, middle-ellipsised to keep the extension). State: **"◌ Preparing for search…"** or **"No readable text"** |
| Trailing | ⋮ icon button, 48 dp, "More options for *title*" | checkbox (picker) or none | |
| Selected (Expanded) | `secondaryContainer`-style background and `selected = true` semantics on the row whose item is open | — | |

- Rows have **no fixed heights**. At 200 % font the meta line wraps under the title and the ⋮ stays 48 dp (§16).
- **Accessibility:** each row merges its semantics into one sentence, such as "Note, Fold launch plan, edited 3 hours ago, preparing for search". It exposes **custom actions** (Rename, Delete, Ask about this note when available, Open in Graph), so a TalkBack user never has to find the ⋮ (§17).

### 3.4 Order and grouping

- **Order:** last edited first (`updated_at` desc), as `observeTimeline` already returns. Opening a note doesn't move it; editing it does.
- **Groups:** day headers from `TL/TimelineFormatting.kt` `groupByDay` and `dayLabel` ("Today", "Yesterday", "Wednesday, Sep 23"). Headers are sticky, marked `heading()` for TalkBack, and never take focus.
- **No sort menu in v1.** A "Title A–Z" sort needs a backend ordering (`observeTimeline` sorts by `updated_at` only); sorting a 50-row window client-side would be wrong. It is added when **KB-11** lands. A sort menu with one option would be a dead control.
- **Paging:** the `TimelineState` model: one live query whose limit widens by 50 when the user is within 10 rows of the end.

### 3.5 Files in the list: one row per PDF

`importPdf` stores **two** documents: an `attachment` (the encrypted original) and a `note` whose frontmatter `source` names the attachment (`CV/transfer/ImportServiceImpl.kt:138-171`). The user sees **one** thing, so:

1. A `note` whose `frontmatter.source` names an existing attachment is that file's **text**. It is never listed on its own.
2. The `attachment` is listed as a **File** row:
   - **name** = its text's title (the name links, citations and search use), falling back to the attachment title;
   - **meta** = "PDF · <original file name>";
   - **snippet** = the first line of the text;
   - **time** = the later `updatedAt` of the pair;
   - **state** = the text's state (preparing, or no readable text).
3. The pairing is done client-side on the loaded window. If one half is missing from the window, it is fetched with `getDocument` (at most one lookup per row, cached by id). No backend change is needed.
4. **Everywhere else the pair is also one object.** A search hit on the text shows the File row. A citation into the text opens the File viewer's Text tab (§12). The graph shows one File node and hides the attachment node and its CITE edge (§11.5). Connections of a File are the connections of its text.
5. If the original was deleted but its text was kept (§8.5), the text is listed as a Note with the property "Original file deleted".

Text, Markdown and code imports are plain notes. Their original file is not kept (`LIFECYCLE_FINDINGS.md` §5), so they appear under **Notes**, and the import snackbar says so (§9.2).

### 3.6 Opening an item (by kind)

Every open, from the list, search, Connections, a wikilink, a graph node, a citation or the palette, goes through one dispatcher: `open(docId)` → `getDocument(docId).kind` →

| Kind | Destination |
|---|---|
| `note` (not a file's text) | Note editor (§5) |
| `note` that is a file's text | File viewer, Text tab (§9.3) |
| `attachment` | File viewer, Pages tab |
| `aiout` | Note editor |
| `chat` | The Chat destination's conversation (`CHAT_UX_SPEC.md`). **Never** the editor (K-P0-3, the likely cause of skein-gg11.23) |
| missing | "This item isn't available. It may have been deleted." with **Back** (§5.11). No title field, so no crash path (K-P1-17) |

- **The back stack holds ids** (IA §6.3). Opening from the list replaces the detail on Expanded and pushes on Compact.
- Following a link from inside a note **pushes**. A ← appears in the detail's top bar and system Back returns to the previous note with its scroll position. This fixes "a link replaces the origin preview" (K-P1-5).

### 3.7 Row menu (⋮ or long-press)

The row menu offers **Open**, **Rename…**, **Ask about this note** (only once `CHAT_UX_SPEC.md` supports attached notes; hidden until then), **Open in Graph**, a divider, and **Delete…** in the destructive style.

On Files the items read *Ask about this file* and *Delete file…*. Long-press opens the same menu as an accelerator, never a different action. Today long-press means "pin", which is removed along with tabs.

### 3.8 States

| State | What shows |
|---|---|
| First load | Nothing for the first 300 ms, then a centred progress indicator. Reading from the local DB is normally faster than that |
| **Empty vault** | Illustration-free empty state: **"Your knowledge lives here"** / "Write notes, import PDFs, and connect ideas with [[links]]. Everything stays on this device, encrypted." / **[New note]** **[Import file]** (prompt §33) |
| Empty with a filter | "No files yet. Import a PDF to search it and ask about it." **[Import file]** · "No notes tagged #x." **[Clear filters]** · "No AI outputs yet." (only reachable if the chip is shown) |
| Importing | A pinned row at the top of the list: "Importing report.pdf…" with an indeterminate progress bar. For several files: "Importing 2 of 3…". The list stays usable (§9.2) |
| Couldn't load | "Couldn't load your knowledge." **[Try again]**. Exceptions are caught; nothing crashes |
| **Expanded, nothing selected** | The detail pane shows "Pick a note or file, or start something new." **[New note]** **[Import file]**. It never says "No tabs open — back to timeline". Skein does not auto-open the most recent item |

### 3.9 Multi-select

**Not in v1.** Delete and rename are single-item and confirmed. Lazy creation (§6) stops new junk notes from appearing.

The one real need is cleaning up the empty "Untitled" notes and the silently created link notes that today's build has already left in vaults (`AUDIT_KNOWLEDGE.md` §3.1, §5.2). That is served by a P2 follow-up, KN-6.14: an "Empty notes" filter chip plus multi-select delete. It ships if the owner's vault turns out to need it.

---

## 4. Search

### 4.1 Entry points

- **Compact:** tap the top search bar. A full-screen search view opens with the keyboard up and the filter chips under the field. Back closes it and clears the query.
- **Expanded:** the docked field at the top of the list pane. Results replace the list in the same pane, and Esc clears the query.
- **Palette:** "Search Knowledge for “q”" opens the Knowledge destination with the query already filled in (§14). There is no second results screen: **the Knowledge list in search mode is the results route.**
- **Keyboard:** Ctrl+Shift+F focuses the field (`CONTINUE.md` §7).
- Knowledge search never returns chats. Chat history search belongs to `CHAT_UX_SPEC.md`, and the palette merges both.

### 4.2 Matching and ranking

- **Titles:** match anywhere in the title, case-insensitively ("plan" finds "Project plan"). Today's search is prefix-only `LIKE 'q%'` (`CV/repository/VaultSql.kt:90`), so this needs **KB-3**. Title search starts at **1 character**.
- **Text:** the existing `searchBodies` FTS5 query (best chunk per document, bm25). It starts at **2 characters**, because a 1-letter prefix query over the whole vault is expensive and useless. It only covers items that have been prepared for search (§4.5).
- **Merge:** one list, one row per document. The tiers, highest first:
  1. exact title
  2. title starts with the query
  3. a word in the title starts with the query
  4. title contains the query
  5. text-only match (bm25 order)

  Within tiers 1–4 the most recently edited comes first. A document that matches on both title and text takes its title tier and shows the text snippet. This is the same order the palette uses (`CONTINUE.md` §5 step 5), so the two never disagree.
- **Filters** apply to results. Chats and a file's text as a separate row are removed (§3.5).
- **Debounce** is 150 ms. A response for an older query is discarded, so the latest query always wins.
- **Not in v1:** fuzzy subsequence matching ("frlnch" → "Fold launch"), quoted phrases, operators. Word-start matching and full text cover the realistic cases. Fuzzy matching belongs in the palette's *command* ranking, not in document search.

### 4.3 Result row

This is `KnowledgeRow` (Standard).

- The **title** has its matched range in the emphasis style: bold plus the `searchHighlight` token. Colour is never the only cue.
- The **snippet** is the FTS5 snippet with each matched term highlighted. Today's snippet uses `[` and `]` as markers (`VaultSql.kt:108`), which can't be told apart from `[[wikilinks]]`. **KB-4** switches them to private-use characters. Until then the UI shows the snippet without highlights rather than guess.
- The **meta line** shows the kind and relative time.
- A quiet header above the results reads "12 results" and is announced once, politely, to TalkBack.

### 4.4 States

| State | What shows |
|---|---|
| Empty query | The normal list (filters apply). Recent *queries* are not stored anywhere (`ZED.md` Z3 privacy adaptation) |
| Waiting | Previous results stay. After 300 ms with no response, a thin linear progress bar appears under the field |
| Results | As §4.3 |
| **No results** | "Nothing matches “q”." Then the actions: **[New note “q”]** (creates a note with that title, §6); **[Search all kinds]** if a filter is on; **[Search chats for “q”]** if Chat history search exists |
| Some items not ready | See §4.5; shown above results *and* above "No results" |
| Error | "Search didn't work. [Try again]". Exceptions are caught |
| Item deleted while its row is showing | The active query re-runs on any delete, so the row never lingers (LIFECYCLE §9) |

### 4.5 "Preparing for search…": being honest about indexing

- Title search reads `documents` directly and is always current. Text search only sees items that ingest has chunked, and ingest runs only while Skein is unlocked and in the foreground (`app/…/ingest/IngestWorkPort.kt`). It can also be paused when the device is warm.
- Rows in the `ingest_queue` show **"◌ Preparing for search…"** in their meta line. **KB-2** exposes this per document; today only a global `IngestProgress(running, processed)` exists (`app/…/ingest/IngestScheduler.kt:87`).
- When any item is pending, search results begin with the line: **"Still preparing 3 items for search. Their text may not appear yet."** (Titles are already searchable.)
- A just-written note that is still pending **is** found by its title. The line tells the user why its text isn't.

### 4.6 Known limits

- `FtsQuerySanitizer` splits on `[^A-Za-z0-9]` (`CV/index/FtsQuerySanitizer.kt:26`), so accented and non-Latin text is dropped from text search: "café" searches for "caf", and "日本" searches for nothing. **KB-5** fixes this.
- Text search ORs the words and treats the last word as a prefix, so bm25 ranking decides relevance. That is acceptable for v1.

---

## 5. The note

### 5.1 Chrome

| Element | Compact | Expanded | Notes |
|---|---|---|---|
| ← Back | Always | Only when the detail pane has history (a link was followed) | "Back" / "Back to *previous title*" |
| Collapsed title | Appears in the top bar once the inline title scrolls off | Same | 1 line, ellipsised |
| Save status | Top bar, before ✦ | Same | §5.5 |
| ✦ **Connections** | Icon button, "Connections" | Same. Toggles the pane | No count badge (the "0" badge was noise, K-P1-6) |
| ⋮ | Note menu (§5.8) | Same | |
| Inline **title** | First line of the scrolling content | Same | §5.3 |
| **Properties row** | Under the title, only when the note has something to show | Same | §5.6 |
| Body | Fills the remaining height; tapping anywhere below the last line puts the caret at the end | Same | Fixes the one-line tap target (`ED/notetab/NoteTab.kt:223`) |

Removed from the note surface: the frontmatter id chip (K-P1-12), the Backlinks strip (K-P1-6, it moves into Connections), the command bar, "Recent ▾" and the tab strip (IA §4).

### 5.2 The note's name: one source of truth

- **The title is `documents.title`, and only that.** Lists, search, links, citations, the graph, the palette, dialogs and export filenames all read it.
- **The inline title is how you see and change it.** It is a borderless multi-line field in the design system's *document title* style. That style must be at least as large as a rendered body H1: today the title is a 16 sp filled form field under a 28.8 sp body H1 (K-P1-14).
- **A `# Heading` in the body is content, not the name.** It is never read back into the title, so renaming the note never edits the body and editing the body never renames the note.
  - The one exception: when an **untitled** note is left, its first line names it once (§6.3). After that the two are independent.
- **Frontmatter `title:`** (carried by imported notes, `ImportServiceImpl.kt` `reconcileFrontmatter`) is **not shown**. It is not a second name. Rename leaves it untouched, because updating it would change the revision hash and mark every citation into the note "source changed" (LIFECYCLE §11, option (c)). The drift that remains, where an export writes the old title inside the file, is fixed at export: **KB-9** makes export write the current title.

### 5.3 Inline title behaviour

- **Placeholder:** "Title". **Label (for accessibility):** "Note title". It wraps (no horizontal scrolling). Enter does not insert a newline.
- **IME:** `ImeAction.Next`. Enter/Next saves the title and moves focus to the body, with the caret at the **start of the body** (see §6.4). `KeyboardCapitalization.Words`.
- **Saving:** on Enter/Next, on focus loss, on leaving the note (Back, a link, a destination switch), on `ON_STOP` and on vault lock. **Never per keystroke.** Today every keystroke writes, re-ingests and captures a revision, and the write is not guarded against errors (`ED/notetab/NoteTabState.kt:122-132`, K-P1-2).
- **Blank on save** (the user cleared an existing title): the previous title is restored with the hint "A note needs a title." Blank is only allowed on a brand-new note, which is then named on leaving (§6.3).
- **Collision hint** (G25): while typing, a debounced (300 ms) `findByTitle` shows a supporting line under the field: *"Another note is already called this. Links to it will open the one edited most recently."* It does not block.
- **Characters that break links** (`[`, `]`, `|`, `#`): a supporting line reads *"Links can't point to titles with [ ] | or #."* It does not block, because imported titles may contain them.
- **Live title:** the editor observes its document (`observeDocument`). A rename made elsewhere, such as ⋮ Rename on the list row while the note is open on Expanded, updates the field, and **autosave writes the live title, never a snapshot**. Today `saveEditorValue` writes the `title` captured at load, so it would silently undo such a rename (`NoteTabState.kt:236`).

### 5.4 Body

- **Placeholder** (empty body): *"Start writing… Type [[ to link a note."* This teaches wikilinks without a toolbar.
- The live preview is unchanged in principle (spec §8.5): the caret line shows raw Markdown and every other line is rendered.
- The buffer is **the body only** (§6.4). Nothing hidden sits in front of it.
- **IME:** multi-line, `KeyboardCapitalization.Sentences`. Autocorrect and suggestions stay **off**, as today (`SecureImeInterceptor`, threat model §9). That is a deliberate privacy trade-off and an open question for the owner (§25 Q2). Capitalisation needs no learning, so it can be turned on without weakening that.

### 5.5 Save status ("Saved")

`EditorState.autosaveStatus` already exists and nothing reads it (`ED/EditorState.kt:126-132`). It drives one small text slot in the top bar:

| `AutosaveStatus` | Shown | Why |
|---|---|---|
| `IDLE` (no edits since opening) | nothing | Nothing to reassure about |
| `UNSAVED` / `SAVING` for under 1 s | stays as it was | The 500 ms debounce would otherwise flicker on every pause |
| `SAVING` for over 1 s | "Saving…" | A slow write is worth showing |
| `SAVED` | **"Saved"** in the muted style. It **stays** (it doesn't fade out and back in on every pause) | Calm and true |
| `ERROR` | **"Couldn't save"** with the error colour **and** an icon. It stays, and is announced once through a polite live region. Tapping it opens: "Skein couldn't save your latest changes." **[Try again]** **[Copy text]** | Silent failure today |

- **Leaving with an error:** Back while the status is `ERROR` asks: "Your latest changes aren't saved." **[Try again]** **[Stay]** **[Leave without saving]** (destructive style). This is the only case where leaving a note asks anything.
- **Flush guarantees** (K-P0-9). Pending edits are handed to `flush()` in each of these cases:
  - **before** the note's back-stack entry is popped or replaced (a link, Back, a destination switch, delete);
  - on `Lifecycle.ON_STOP`;
  - on vault lock, through `FlushRegistry.flushAll()` with its 2 s deadline. It exists and is never called today (`app/…/MainActivity.kt:359-366`).
  - The flush runs in a scope that outlives the composable: the entry's ViewModel scope for pops, and an app-scoped `NonCancellable` job for stop and lock.
  - Editor state lives in the entry's ViewModel, so fold/unfold recreation disposes nothing and loses nothing.
- **Ctrl+S** flushes immediately and the status shows "Saved". It costs nothing and it catches a reflex.

### 5.6 Properties

- **The properties row** under the title appears **only when there is something a person would care about**: tags (chips, one line, "+2" overflow), the source file ("From report.pdf"), and the persona if two or more exist. A plain note has **no** row, so the first content line of every note is no longer `— id 0192abcd… ▸`.
- Tapping the row opens **Connections scrolled to Properties** (§10).
- **Properties section** (read-only in v1):
  - Tags as chips; a chip opens Knowledge filtered by that tag.
  - "Created Sep 17 · Edited 3 h ago".
  - Source file (opens it) or "Original file deleted".
  - Persona.
  - Other keys from imported files, as a plain key–value list.
  - **Details** (Level 2, collapsed): Note ID with **Copy**.
  - The system keys `id`, `kind`, `title`, `created` and `updated` are never shown as raw YAML.
- **Editing properties is removed from v1.** Today the only way to edit them is to expand the raw frontmatter block inside the editor buffer, which is the mechanism behind K-P0-2.
  - Tags are added the Obsidian way, by typing `#tag` in the body (`TagExtractor` reads body tags).
  - A structured property editor is a P2 follow-up (KN-6.15). It needs **KB-12**, because a frontmatter-only update doesn't enqueue ingest (`001_initial.sql:183-186` watches `body_md`, `title` only), so edited tags would not reach the graph.

### 5.7 Links in a note

| Interaction | Behaviour | Replaces |
|---|---|---|
| Tap a rendered `[[link]]` to an existing note | Opens it by kind (§3.6), **pushed**; Back returns | A silent replace of the origin preview |
| Tap a link to a **missing note** | A small sheet: *"No note called “X” yet."* **[Create “X”]** **[Cancel]**. Create opens the new note, titled, with the caret in the body | Silent creation of an empty note that can't be deleted (K-P1-4). This is an amendment to spec §8.5 (K-D6) |
| How missing links look | Muted colour **and** a dotted underline. Computed by resolving this note's link targets (debounced 1 s after edits; only changed targets are looked up) and passing the result as `knownWikilinkTitles` | `knownWikilinkTitles` is never set today |
| Long-press a rendered link | Puts the caret inside the link, which reveals its raw text for editing | The caret can't be placed in a link by touch today (K-P2-7) |
| Keyboard | With the caret inside `[[…]]`, **Ctrl+Enter** follows the link | Links are pointer-only today |
| TalkBack | The editor exposes one custom action per link in view: "Open Sync design", or "Create note Model picker" | No semantics today |
| `[[` autocomplete | Full-width rows at least 48 dp tall, at most 5 visible on Compact. Anchored above the caret when the keyboard leaves no room below. **Chats are excluded**. The **Create “x”** row is **hidden on an exact title match**. Up/Down/Enter/Esc work as today | Chats listed as "Chat, Chat, …", duplicate creates, 36 dp text-width rows |

### 5.8 Note menu (⋮)

In this order. Items that aren't wired are **hidden**, not disabled (prompt §2):

1. **Ask about this note**: a new chat with this note attached (`CHAT_UX_SPEC.md` owns the chat side). Hidden until attached-note context exists.
2. **Connections**: the same as ✦, repeated here for TalkBack and keyboard users (IA §3.3).
3. **Rename…**: the rename dialog (§7.2), which is useful when the title is off screen.
4. **Share or export…**: a sheet with **Share as text**, **Save as Markdown (.md)**, **Save as Word (.docx)** and **Save as PDF**. These are the existing `ED/share/` flows, moved out of the unlabelled "↗" button. Each result gets feedback: "Saved “Fold launch plan.md”", or "Couldn't save the file. [Try again]". The writes are wrapped so that I/O and lock errors never crash (the Save-as crash in K-P0-7).
5. **Copy link** (Level 2): copies `[[Title]]` with `ClipDescription.EXTRA_IS_SENSITIVE`, so the system's clipboard preview hides it.
6. —
7. **Delete note…** (destructive style, §8).

### 5.9 Outer screen and Back

- A note on Compact is a **full-screen route** with real Back: the top-bar ← and system/predictive Back do the same thing.
- **Back order:**
  1. close the autocomplete popup or a link sheet;
  2. close the Connections or Properties sheet;
  3. save the title and flush the body (§5.5);
  4. pop to the previous entry: the linking note, the chat (from a citation) or the list, with its scroll position and filters.

  The IME is dismissed by the system before step 1, as usual. This fixes K-P0-5: today no `BackHandler` exists anywhere and "Recent ▾" has no Close.
- **Focus on open:** an existing note opens **without** focusing the body and without raising the keyboard. The user is usually reading. A new note focuses its title (§6).

### 5.10 Keyboard and IME (hardware keyboard on the unfolded Fold)

| Keys | Action | Today |
|---|---|---|
| ←/→/↑/↓ | Move the caret through the offset mapping (keep the skein-hacu/ex7d fixes) | Works |
| **Shift**+arrows | Extend the selection | **Broken**: the custom handlers consume every arrow and ignore modifiers (`ED/SkeinEditor.kt:198-316`). Only **unmodified** arrows may be intercepted; the rest pass through |
| **Ctrl**+←/→ | Move by word | Broken (same cause) |
| Home/End, Ctrl+Home/End | Line / document start and end | Framework behaviour |
| Ctrl+S | Flush now, show "Saved" | — |
| Ctrl+Enter | Follow the link under the caret | — |
| Esc | Close the popup or sheet. Otherwise nothing: Esc never leaves a note | — |
| Ctrl+Shift+N / Ctrl+P / Ctrl+Shift+F | New note / Go to… / Search Knowledge (palette catalogue, `CONTINUE.md` §6) | — |
| Ctrl+Z / Ctrl+Shift+Z | Undo/redo: **not in v1**. The editor uses the `TextFieldValue` API, which has no undo. Moving to `TextFieldState` is a separate editor project (§25 Q6) | — |
| **List focused:** Enter · Delete · Shift+R | Open · Delete… (with confirmation) · Rename… (`ZED.md` Z15) | — |

### 5.11 Error and edge states

| Situation | Behaviour |
|---|---|
| The note no longer exists (deleted elsewhere, a stale restore) | Full-pane state: "This note isn't available. It may have been deleted." **[Back to Knowledge]**. No title field, so no `NoSuchElementException` crash path (K-P1-17) |
| The vault locks while editing | Flush (§5.5); the shell gates; after unlock the id-only back stack reopens the note with the caret offset restored (an offset is not content, IA §6.3) |
| The same note open twice (only possible with "open beside" at 1200 dp or more) or changed underneath (a link rewrite, §7) | The editor observes its document. No unsaved local edits: reload silently, keeping the caret. Unsaved local edits: keep them, and show a banner "This note changed elsewhere. **[Reload]**". This fixes last-writer-wins (K-P1-16) |
| Existing notes already corrupted by K-P0-2 (a body starting with a leaked `---`/`id:` block) | If the device check confirms the bug happened, the note shows a one-line banner: "This note contains a copy of its properties. **[Clean up]**". Clean up strips a leading block whose `id` equals this note's id. Only if verified (KN-6.1) |

---

## 6. Creating a note

### 6.1 Entry points (none of them typed)

- **Knowledge FAB** "New note".
- **Chat's empty state** "New note" (IA §3.7).
- **Palette** "New note" / Ctrl+Shift+N; `/new note Title` still works as a power path.
- **Search "No results"** → **New note “q”**.
- **Missing-link sheet** → **Create “X”** (§5.7).
- **`[[` popup** → **Create “x”** (only when no exact match exists).

Every entry lands in **Knowledge › the new note**:

- Compact: the note route is pushed over the Knowledge list, so Back returns to the list.
- Expanded: the list plus the note in the detail pane. The row appears and is selected as soon as the note exists.
- When the entry is outside Knowledge (Chat, palette), the destination switches to Knowledge.

### 6.2 Title-first, created on the first character

1. The editor opens at once with **the title field focused** and the keyboard up. No document exists yet: the back-stack entry is `NewNote(draftKey)`.
2. The **first character typed** anywhere creates the document. That is `createDocument(NOTE, title = typed title or "", body = "")`, run once and serialized with autosave. The entry is then replaced by `Note(id)`.
3. **Enter/Next** in the title moves to the body (caret at the body start).
4. Arriving with a title (`New note “q”`, `Create “X”`, `/new note Title`) creates the note immediately with that title, and focuses the **body**.

Why create on the first character: today a bare `/new note` makes an "Untitled" note immediately, and every accidental tap left an empty note behind that couldn't be deleted (`AUDIT_KNOWLEDGE.md` §3.1). Titles also drive link resolution, so duplicates of "Untitled" are harmful.

### 6.3 Leaving a new note

| Title | Body | On leave |
|---|---|---|
| empty | empty | **Nothing is kept.** No document was created; or, if everything typed was erased, the document is deleted silently. It never held more than what the user erased |
| empty | text | **Named from its first line:** the first non-empty line of the body, Markdown stripped (`previewOf`), cut at a word boundary to at most 60 characters. If that title is taken, " (2)", " (3)"… is appended. A snackbar reads "Named “Checklist for launch”. **[Rename]**" |
| text | any | Kept as typed |

### 6.4 First-keystroke safety (the fix for K-P0-2)

**The bug:**
- The editor buffer is `Frontmatter.render(frontmatter, body)`, and the collapsed block is hidden by the visual transformation.
- `transformedToOriginal(0)` returns raw offset 0, *before* the hidden `---` (`ED/LivePreviewTransformer.kt:135-136`).
- The editor starts with the caret at 0 (`TextFieldValue(...)` at `ED/notetab/NoteTabState.kt:205`).
- So the first keystroke in an empty note, a tap at the start of the first visible line, ↑ on the first line or ← at the first character inserts text in front of the frontmatter. That corrupts the note.

**UI requirements** (testable; these hold whatever the implementation):

| # | Requirement |
|---|---|
| R1 | No tap, key press (including ↑ on the first line and ← at the first character), IME commit, paste, autocomplete insertion or programmatic edit can insert or delete text before the first character of the body |
| R2 | An existing note opens with no caret (§5.9). The first tap puts the caret at the tapped visible position. In an empty body, any tap puts it at the body start |
| R3 | Title → Next puts the caret at the body start |
| R4 | Saving a note writes exactly the body the user sees. Its properties are unchanged unless the user changed them |

**Implementation (decided): the buffer holds the body only.**
- `NoteTabState` seeds the editor with `document.bodyMd` and saves with one `updateBody(id, liveTitle, body)`.
- Properties are read from `document.frontmatter` for display (§5.6). They are no longer part of the editable text.
- This removes the whole class of bug instead of one offset. It also removes the per-keystroke frontmatter scans (§18) and the unused `updateFrontmatter` write on every save (`NoteTabState.kt:228-235`).
- The frontmatter hide/show code and `ProtectedIdGuard` become unused by the note editor. They are deleted in the same bead unless something else still uses them.

**Hotfix, if the body-only change can't ship first:** `transformedToOriginal(0)` returns `offsets[0]` when the block is hidden; the initial selection is the body start; and `EditorState.onValueChange` clamps both selection ends to `≥ bodyStart` while the block is collapsed. This hotfix ships ahead of Wave 6 as P0 (KN-6.1).

**Tests** (`OffsetMappingTest` only checked a round trip, which holds even with the bug):
1. A Compose test: new note, tap the body, type "X", flush, then assert `bodyMd == "X"` and the frontmatter is byte-identical.
2. The same with ↑ on the first line and ← at the first character of an existing note.
3. Paste at the start.
4. For the hotfix only: `transformedToOriginal(0) == blockEnd`.

The 1-minute device check in `AUDIT_KNOWLEDGE.md` §3.3 is run and recorded in the bead.

---

## 7. Renaming

### 7.1 Surfaces

- **The inline title** is the main way to rename (§5.3).
- **⋮ → Rename…** on the note, on a list row, or from the palette ("Rename note…", Text stage, `CONTINUE.md` §3). The dialog is titled "Rename note" and has a filled text field pre-selected, **[Cancel]** and **[Rename]**. It carries the same collision and character hints as §5.3.
- **A File is renamed through the same flow.** It renames the File's name, which is its text's title (§3.5). The original file name shown in the meta line is kept as it was imported.

### 7.2 What a rename does

Data semantics belong to `OBJECT_LIFECYCLE_SPEC.md`. The UI contract is:

1. It saves the new title once. It uses `renameDocument(id, title)` (**KB-1**, G1) when available, else `updateBody` with the live body. It bumps `updated_at`, so the note moves to the top of Recent. It doesn't touch the revision, so citations into the note stay "live" (LIFECYCLE §11).
2. **It rewrites links in other notes.** Every **note** that links to the old title gets its link text rewritten:
   - Which notes: sources from `edgesTo(id, WIKILINK)` ∪ `edgesTo("title:" + old.lowercase(), WIKILINK)`.
   - What is rewritten: `[[Old]]` → `[[New]]`, `[[Old|alias]]` → `[[New|alias]]`, `[[Old#Heading]]` → `[[New#Heading]]`, matched case-insensitively on the trimmed target.
   - What is left alone: links inside fenced code or inline code.
   - It runs in one `VaultRepository.transaction { }` together with step 1.
   - A **case-only** rename ("fold plan" → "Fold plan") skips the rewrite, because links resolve case-insensitively.
3. **Chats are not rewritten.** Messages are immutable (LIFECYCLE §3). A `[[Old]]` inside a chat renders as a missing note in that chat.
4. **Open editors** of the rewritten notes reload (§5.11). A note with unsaved local edits is skipped and reported in the snackbar.
5. **The snackbar** reads: "Renamed. Updated links in 3 notes. **[Undo]**" (8 s). If a note was skipped, it adds "1 open note wasn't updated". Undo renames back and rewrites back in one transaction. With no inbound links there is no snackbar, because the change is visible on screen.
6. Every surface showing the title updates from the live document: the list row, the collapsed top-bar title, Connections, graph labels and the palette. There are no snapshot titles; `Tab.title` goes away with tabs.

**Why rewrite instead of disclose** (LIFECYCLE §11 recommended deferring it):
- The owner calls Skein "a workspace knowledge graph".
- Title-first creation and naming from the first line (§6.3) make renames routine.
- Today every rename quietly cuts the graph: backlinks vanish after the linking notes re-ingest, and tapping `[[Old]]` creates an empty note.
- The rewrite needs no storage change. It uses `edgesTo`, `updateBody` and `transaction`. **KB-7** only stops link updates from flooding Recent.
- **Cost:** the rewritten notes get new revisions, so citations into *those* notes show "source changed". The user asked for the change, and the snackbar names it.

**Fallback** (if KN-6.6's rewrite part slips): the rename saves only the title, and when inbound links exist it shows "3 notes link to “Old”. Those links will show as missing." **[OK]**. Those links render as missing notes (§5.7), never silently recreated.

---

## 8. Deleting

### 8.1 Flow

⋮ → **Delete note…** (or **Delete file…**), from the note's menu, a list row, the palette's Confirm stage, a TalkBack custom action, or the list-focused Delete key. It opens a confirmation dialog (`DESIGN_SYSTEM.md` destructive dialog):

**Note:**
> **Delete “Fold launch plan”?**
> This permanently removes the note from Skein, including its search index. You can't undo this.
> *3 notes link to it. Those links will show as missing.* ← only if N > 0 (from `edgesTo(id, WIKILINK)`)
> *If Skein quoted this note in a chat, the quote stays in that chat.* ← always shown, until **KB-11b** can count
> **[Cancel]**  **[Delete note]**

**File:**
> **Delete “Quarterly report 2025”?**
> This permanently removes the PDF and its text from Skein, including its search index. You can't undo this.
> ☐ Keep the text as a note
> *(same conditional lines)*
> **[Cancel]**  **[Delete file]**

- Titles longer than about 60 characters are shown with an end ellipsis inside the quotes; the dialog wraps to at most 2 lines for the title.
- The destructive button names the object ("Delete note", not "Delete"). **Initial focus is on Cancel.**
- There is no swipe-to-delete (too easy to trigger by accident on a phone).

### 8.2 Undo: no

This is decided on the lifecycle findings:

- `deleteDocument` is **one hard-delete transaction**. There is no soft delete and no trash (G26 would be L-size storage work with a migration).
- The vault is **excluded from every backup** (`LIFECYCLE_FINDINGS.md` §10.2, `docs/BACKUP_EXCLUSIONS.md`), so nothing can restore it later either.
- The only undo that needs no storage change is a **deferred delete** held in memory. But backgrounding locks Skein, and the process can then die. A "deleted" note would then quietly come back, which is the wrong surprise for a privacy-first app (LIFECYCLE §14 recommendation).
- A person who deletes something in Skein expects it to be gone. The dialog says what stays: quotes in chats. Search-index residue (until G11 lands) is too technical for Level-1 copy; it belongs in `OBJECT_LIFECYCLE_SPEC.md`'s persistence section.

**Mitigations instead of Undo:** always confirm; state consequences; name the object on the button; focus Cancel first; no swipe; no bulk delete in v1.

After the delete a snackbar reads **"Deleted “Fold launch plan”"** (4 s, no action).

### 8.3 Order of operations (UI side)

1. Cancel the note's pending autosave, so a debounced save can't hit a missing row.
2. Remove **every back-stack entry** for the id (and for the paired text or original of a File) in all panes. This includes a citation-landing entry above a chat and a graph centred on it.
3. Call the delete (`OBJECT_LIFECYCLE_SPEC.md` semantics; LIFECYCLE §15.3 for notes, §15.4 for files). For a File, delete the text then the original in one transaction, unless "Keep the text as a note" is checked.
4. Navigate (§8.4), show the snackbar, and re-run any active search.

**Gate:** delete UI does not ship until the minimum backend work in `LIFECYCLE_FINDINGS.md` §13 items 1–5 has landed: edge cleanup (G3), correct events and blob-after-commit (G5), export-stage purge (G6), the ingest race guard and orphan sweep (G4), and the emulator cascade test (G21). Without them a deleted note keeps steering retrieval and appears as a UUID ghost in the graph. These are **KB-8**, owned by the lifecycle work.

### 8.4 Where the user lands

| Deleted from | Compact | Expanded |
|---|---|---|
| The note's own ⋮ | The entry below: the list at its previous scroll position, or the chat if the note was opened from a citation | The list stays; the detail pane shows its placeholder (§3.8); keyboard and TalkBack focus goes to the **neighbouring row** (next, else previous), which is not opened |
| A list row's ⋮ | Stays in the list; focus on the neighbouring row | Same; if that item was open, the placeholder shows |
| The palette | Returns to wherever the palette was opened from, unless that was the deleted note (then as the first row) | Same |

Opening the neighbour automatically is avoided on purpose: it invites deleting two things in a row by accident.

### 8.5 Open references after a delete

| Reference | What the user sees | Owner |
|---|---|---|
| Knowledge list, search results, palette results | Gone at once (live list; active queries re-run) | this spec |
| Connections of other notes | The row disappears (`BacklinksState` already filters missing sources) | this spec |
| `[[Title]]` in other notes | Rendered as a **missing note** (§5.7). A tap asks before creating | this spec |
| Graph | If it was the centre: re-centre on the most recently edited note, with a snackbar "“X” was deleted. Showing “Y”." If it was a node: gone. Document nodes that can't be resolved are **never drawn** (no UUID labels, `GR/GraphModels.kt:65-70`) | this spec |
| **Chat citations** | The chip becomes **"Source deleted"**. Tapping it shows the kept quote in a sheet ("This source was deleted. Skein kept the quoted passage in this chat.") and **never navigates** | `CHAT_UX_SPEC.md` renders; this spec defines the contract (G9) |
| **Attached context** (a note attached to a chat) | The chip shows "Deleted", is excluded from the next send, and can be removed | `CHAT_UX_SPEC.md` |
| A File's text kept on delete | Listed as a Note with "Original file deleted" in Properties | this spec |
| After a restart | Still gone. The id-only back stack drops entries that no longer resolve (LIFECYCLE G7) | shell |

---

## 9. Files

### 9.1 Import: what can be imported

| Type | Offered by the picker | Becomes |
|---|---|---|
| PDF | `application/pdf` | A **File**: the encrypted original plus its text (§3.5). A PDF with no text layer is still imported, marked "No readable text" |
| Markdown | `text/markdown`, `text/x-markdown` | A **Note**, named from frontmatter `title`, then the first `# heading`, then the file name |
| Plain text | `text/plain` | A Note |
| Code | `text/*`, `application/json`, `application/xml`, `application/javascript`, `application/octet-stream` (some providers report code this way) | A Note with the code fenced, named with the file name |
| **Images** | **Not offered** | Image import is not implemented: `importImage` throws `UnsupportedOperationException` (`CV/transfer/ImportServiceImpl.kt:176`). If one arrives anyway: *"Skein can't add images yet. It can import PDFs, Markdown, text and code files."* |
| Anything else (docx, zip…) | Not offered | *"Skein can't import this kind of file yet."* |

- **One import helper for everyone.** Knowledge's Import file and Chat's ＋ → Import file… (`CHAT_UX_SPEC.md`) call the same helper. It returns `Imported(docId, kind) | Unsupported(reason) | Failed(reason)` and **never throws**.
- The helper checks the type after the pick: `application/octet-stream` is accepted only when `MimeSniffer` recognises the extension.
- It runs on a background dispatcher in an app-scoped coroutine, so leaving Knowledge doesn't cancel it. Today `readBytes()` and PDF extraction run on the main thread (`AUDIT_KNOWLEDGE.md` §4.1).
- This fixes the 📎 image crash (K-P0-7) for both callers, not only the one the ticket names.
- **Several files** may be picked at once (`OpenMultipleDocuments`); they import one after another.

### 9.2 Import flow and feedback

1. **Import file** → the system picker, filtered as above.
2. The list shows the pinned progress row, "Importing report.pdf…" (§3.8). The rest of the app stays usable. There is no blocking dialog and no status bar floating over a composer (IA §4).
3. When it's done, a snackbar:
   - "Imported “Quarterly report 2025”. **[Open]**"
   - "Imported “notes.md” as a note. **[Open]**" (text imports; this is where users learn the original isn't kept)
   - "Imported 3 files."
4. The new row appears at the top with **"◌ Preparing for search…"** (§4.5). A PDF without a text layer shows **"No readable text"** instead.
5. **Failure:** "Couldn't import “x”. The file couldn't be read. **[Try again]**"
6. **Same name** (P2): if `findByTitle` finds an existing item with the same name: "You already have “report.pdf”. **[Import a copy]** **[Open existing]**"
7. **Frontmatter id collision** (`ImportResult.conflictWith`): "Imported as a copy: a note with the same ID already exists."

**Where it lands:** the user stays in the Knowledge list. Nothing opens by itself, including on Expanded; Open is in the snackbar. From Chat's ＋ the file arrives selected in the attach picker (`CHAT_UX_SPEC.md`).

**Not in scope here:** the share-target intent filter (spec §3.1 lists it; the manifest has none). It would reuse the same helper. It is tracked as a follow-up because it touches the app manifest and the threat model, not Knowledge UI.

### 9.3 File viewer

| Part | Exists today? | v1 |
|---|---|---|
| A viewer at all | **No.** `TabKind.ATTACHMENT` has no content, and attachments open as an empty note editor where typing rewrites the attachment's hash (K-P0-3, G19) | Required (KN-6.9) |
| **Pages** (PDF) | No | Rendered with the platform `android.graphics.pdf.PdfRenderer`: no new dependency, one page bitmap at a time in a `LazyColumn`, pinch to zoom, "3 / 12" page indicator. **The plaintext never touches disk:** `openAttachment` is decrypted into a memory file (`Os.memfd_create`, API 30 = `minSdk`) wrapped as a `ParcelFileDescriptor`, and closed on leave and on lock. Bitmaps live in memory only |
| **Text** | The text exists as a note | A read-only rendered view of the text note (`core:markdown` renderer), selectable. Code blocks scroll sideways here (unlike the editor). This is where citations land (§12). ⋮ → **Edit text** (Level 2) opens the text in the note editor, for fixing extraction errors |
| Default tab | — | **Pages** when opened from the list; **Text** when opened from a citation, a search text hit or a link |
| Top bar | — | ← · the File's name · ✦ Connections · ⋮ |
| ⋮ | — | Ask about this file · Connections · Rename… · **Save a copy…** (SAF create-document, streaming the decrypted original; user-initiated, like note export) · Edit text (Level 2) · — · **Delete file…** |
| No readable text | — | Text tab: "This PDF has no text Skein can read. It may be a scan. You can view it, but it won't show up in search or answers." |
| Unreadable PDF (encrypted or damaged) | — | Pages tab: "Skein can't display this PDF. Its text is in the Text tab." (or the no-text message) |
| Preparing for search | — | A banner in the Text tab: "Preparing for search…" |
| Images | — | **Hidden until image import exists** (skein-rni, not this epic). When it lands: one zoomable image, plus a Text tab for its description |

### 9.4 Rename, delete, preparing

- **Rename:** §7.1 (the File's name is its text's title).
- **Delete:** §8. The default removes the original and its text; "Keep the text as a note" keeps the text. LIFECYCLE §15.4 proposed keeping derived notes by default. This spec reverses the default because the user sees one object, and flags it for alignment in `OBJECT_LIFECYCLE_SPEC.md` (§25 Q3).
- **Preparing:** §4.5. Attachments themselves are never ingested (the trigger excludes them); the File's state comes from its text.

---

## 10. Connections

### 10.1 Content (in this order)

| Section | Source | Row | Empty |
|---|---|---|---|
| **Linked from · N** | `BacklinksState` as today: `edgesTo(id)` ∪ `edgesTo(title sentinel)`, with each source's excerpt | `KnowledgeRow` (Compact) with a 2-line excerpt in which `[[this title]]` is emphasised, and "2 links" when there is more than one. Chats appear with the chat icon and open in Chat | "No other notes link here yet." |
| **Links to · N** | `edgesFrom(id)` WIKILINK: resolved rows open; unresolved targets show as **missing notes** with a **[Create]** button | Same row. A missing note is a hollow icon with "No note yet" | "This note doesn't link to anything yet. Type [[ to add a link." |
| **Local graph** | A 1-step neighbourhood, at most 24 nodes, laid out **once** off the main thread, drawn statically: no simulation, no labels except the centre, 160 dp tall | Tapping it, or **[Open in Graph]**, opens the Graph destination centred on this note | Hidden when there are no connections |
| **Properties** | §5.6 | — | "No properties." (Details still shows the id) |
| **Unlinked mentions** (P2, Level 2, collapsed) | `searchBodies(title)` results whose body contains the exact title, case-insensitively, and no `[[title` | Row plus excerpt; tap opens | Hidden when empty, or when the title is shorter than 3 characters |

Unlinked mentions are included only because they're cheap: one FTS query when the section is expanded, and an exact filter over the ≤ 20 bodies it returns. Because the sanitizer ORs the title's words, the exact-match filter is required. Without it the section would list every note sharing one word.

### 10.2 Containers

- **Compact:** a modal bottom sheet that opens at half height and drags to full. Its content scrolls. Tapping a row **dismisses the sheet and pushes** the target; it never stacks a sheet on a sheet (`ANYTHINGLLM.md` P5).
- **Expanded:** the extra pane (≈ 360 dp) takes the list's place (IA §3.4). ✕, system Back and ✦ toggle it closed. Opening a row replaces the detail and **keeps the pane open**, showing the new note's connections, so users can walk the graph as a list.
- **State:** `connectionsOpen` and its scroll position live in the note's back-stack entry. On fold the pane becomes a sheet, and on unfold the sheet becomes a pane, at the same scroll position (prompt §25 Test G).
- **Refresh:** re-query on a WIKILINK `EdgesReplaced` event, debounced 500 ms, **only while visible** (collect with the lifecycle). Today every open drawer re-queries on every write anywhere (`AUDIT_KNOWLEDGE.md` §11).
- **Removed:** `BacklinksDrawer` from `NoteTab` (`ED/notetab/NoteTab.kt:235`). It is unbounded and can't scroll, so it can crush the editor on the outer screen with the keyboard up (K-P1-6). It was also composed under chat transcripts (skein-gg11.23).

---

## 11. The Graph destination

The IA makes Graph a primary destination (D6). This section makes it a real one: centred, legible, select-then-open, accessible, and cheap on battery.

### 11.1 Entering

- **From the drawer or rail:**
  - The graph centres on the **note opened most recently**, held as an id in navigation state (IA §6.3).
  - If there is none (a cold start, or the note is gone), it centres on the **most recently edited** note: `observeTimeline(kinds = {NOTE}, limit = 1)`.
  - If the vault has no notes, see §11.8.
- **From a note:** Connections → **Open in Graph**, or the palette's "Open graph around this note". This pushes Graph centred on that note, and Back returns to the note.
- **Top bar:**
  - title **"Graph"**;
  - subtitle **the centre's name ▾**: tapping it opens the Knowledge picker (§13) in single-select "Centre on…" mode, with **Recent centres** at the top;
  - trailing **≣ Show as list** (§11.9).

  There is no ✕: the graph is a destination, and system Back follows IA §3.8.
- **Theme:** the graph uses the app theme, so it follows Settings › Appearance. Today `GR/GraphScreen.kt:45` wraps a fresh `SkeinTheme()` in system mode.

### 11.2 The canvas

| Aspect | Specification | Today |
|---|---|---|
| **Fit** | The world transform fits the nodes' bounding box to the canvas on **both** axes with 48 dp padding. The first view is fitted | Scales to the *shorter* side (`GR/GraphTransform.kt:19`), wasting most of the inner screen's width, and labels are cut off at the edges (`ux-baselines/before/fold-inner/graph-dark.png`) |
| **Pan and zoom** | Pinch 0.4×–4× as today. Pan is clamped so part of the graph always stays on screen. Double-tap on empty space fits the view. A **Fit** button (48 dp, bottom end) appears only after the user has panned or zoomed | No fit or reset |
| **Centre** | Drawn larger (18 dp radius), with the primary ring and its label always shown | Same |
| **Node drag** | Kept: dragging a node pins it and the simulation reheats (skein-67ak) | Works |
| **Edges** | Thickness by weight as today. Neutral colour; tag edges dotted, file edges dashed. The selected node's edges take the accent colour | Colour only |
| **Kinds** | Colour **and** an icon inside the node (below) | Colour only; AI output uses the error colour (K-P2-4) |

**Node vocabulary** (colour roles are `DESIGN_SYSTEM.md` tokens):

| Node | Shape and icon | Colour role | Tap |
|---|---|---|---|
| Note | circle + note icon | `graph.note` | select |
| File (one node per PDF, §3.5) | circle + file icon | `graph.file` | select |
| Chat | circle + chat icon | `graph.chat` | select; Open goes to Chat |
| AI output | circle + sparkle icon | `graph.aiOutput`, **never** `error` | select |
| Tag | small diamond + "#" | `graph.tag` | select → "Tag #fold · 5 notes" **[Show in Knowledge]** (Knowledge filtered by that tag) |
| Missing note (`title:` target) | hollow circle, dashed outline | `graph.missing` | select → "No note called “X” yet · linked from 2 notes" **[Create note]** |
| Entity | **hidden** until entity names can be resolved | — | Today "Entity #123" is implementation language, and nothing in production writes entities (`IngestPipelines.kt:50`) |
| A document that can't be resolved (deleted) | **never drawn** | — | Today a UUID label opens "Note not found" |

### 11.3 Labels

- **Always shown for** the centre, the selected node and 1-step neighbours, subject to collisions (below).
- **2-step labels** are shown when zoom ≥ 1.25× or when the graph has 20 nodes or fewer. Today labels vanish entirely above 40 nodes (`GR/GraphView.kt` `MAX_LABELED_NODES`).
- **Size:** `labelMedium`, 1 line, **end ellipsis at 120 dp** (Compact) or **160 dp** (Expanded). The full title is in the node detail. Today labels are measured unconstrained and overlap each other (K-P1-7).
- **Legibility:** each label sits on a halo (`surface` at 85 %) so edges don't strike through it, and it is clamped inside the canvas bounds.
- **Collisions:** place labels greedily in priority order (selected, centre, 1-step, 2-step) and skip any label that would overlap one already placed.
- **Font scale:** labels follow font scale up to 1.3×, then stop growing. People who need larger text get it in the List view (§11.9), which has no such cap.

### 11.4 The key

A **"Key"** chip (48 dp) at the bottom start, **collapsed by default**. It expands into the node vocabulary above (icon + colour + name) and collapses on the next canvas tap. Its expanded state persists for the session. It never covers the centre: if it would, the fitted view leaves room for it.

Today the legend is always open and can hide nodes (`ux-baselines/before/fold-inner/graph-dark.png`: it covers "…design").

### 11.5 Select, then open (D6)

- **Tap a node → select it:**
  - it gets a thicker ring and its label is always shown;
  - its edges are highlighted;
  - nodes not connected to it dim to 40 %;
  - a polite live-region announcement reads "Selected Weekly review, note, links to Fold launch plan".
- **Tap empty space, or Esc → deselect.**
- **Nothing navigates on a single tap.** Long-press is **the same as tap**: no hidden "pin", and tabs are gone anyway.
- **Node detail**: a bottom sheet on Compact (it appears on selection and dragging it down deselects); the supporting pane on Expanded. It shows:
  - the kind icon, the name (2 lines) and "Note · edited yesterday";
  - a 3-line snippet;
  - the relation to the centre: "Links to Fold launch plan", "Linked from Fold launch plan", "Tagged #fold", or "2 steps away, via Weekly review";
  - "Linked from 3 · Links to 2";
  - **[Open]** (primary; opens by kind and pushes, so Back returns to the graph with the selection kept);
  - **[Centre here]** (secondary; hidden on the centre itself). Re-centring **replaces** the view rather than pushing, and the centre is added to Recent centres.
  - **Expanded only:** a "Connected here" list of this node's neighbours in the current graph. Tapping a row selects that node.
- **Default selection:** on Expanded, the centre starts selected so the pane is never empty. On Compact nothing starts selected, so the sheet doesn't steal canvas. An *explicit* selection survives fold and unfold; the implicit default doesn't open a sheet on fold.

### 11.6 Back in Graph

1. Collapse the key.
2. Deselect, which closes the Compact sheet.
3. If Graph was pushed from a note, pop to the note. At the destination root, go to Chat (IA §3.8).

Today there is no `BackHandler`, and Back leaves the app.

### 11.7 Performance and battery

| Rule | Why |
|---|---|
| `neighborhood` (2 steps, ≤ 80 nodes), title lookup and `ForceLayout.compute` run on `Dispatchers.Default`, and the result is posted to the UI | Today about 1.9 M pair evaluations run on the main thread on every open (`AUDIT_KNOWLEDGE.md` §11) |
| Reloads triggered by `EdgesReplaced` are **debounced by 1 s**, **only collected while Graph is visible** (lifecycle-aware), and **skip the re-layout** when the node and edge sets haven't changed | Today every ingest write re-runs the whole layout |
| When the sets do change, surviving nodes keep their positions and only new nodes are placed | No jumping |
| The simulation stops when the fastest node moves less than 0.5 dp per frame, or after **4 s**, whichever comes first. After that there are **no frame callbacks**. It pauses on `ON_STOP` | The idle stop exists (skein-67ak); the time cap and the lifecycle pause are new |
| **Reduced motion** (animator duration scale 0): the simulation runs to rest off screen and the final layout is drawn directly | Motion sensitivity (prompt §40) |
| Per frame: no allocation of screen-position maps (reuse arrays); label layouts cached per (label, style) with a cache at least as large as the node count (`rememberTextMeasurer(cacheSize = 96)`) | Today every frame allocates and may re-measure 40 labels |
| **Budgets on the Fold:** first graph visible ≤ 500 ms for 80 nodes; ≤ 8 ms per frame while settling; 0 frames while idle | Checked by the Macrobenchmark in `UX_TEST_PLAN.md` |

### 11.8 Empty states

| Situation | Canvas |
|---|---|
| No notes in the vault | "Your graph grows as you connect notes." / "Type [[ in a note to link it to another. Each link becomes a line here." **[New note]** |
| The centre has no connections (includes a vault with no links anywhere) | The single node, plus a card: "“Fold launch plan” isn't connected yet." / "Type [[ in the note to link it." **[Open note]** **[Centre on another note]** |
| The remembered centre was deleted | Re-centre on the most recently edited note, with a snackbar "“X” was deleted. Showing “Y”." |
| Loading | Nothing for 300 ms, then a centred progress indicator (first load only; reloads keep the current graph on screen) |

### 11.9 Accessible alternative: List view

- **≣ Show as list** swaps the canvas for a list of the same graph. It is the same data, so it never disagrees with the canvas:

```text
Graph · Fold launch plan ▾                       [Canvas]
CENTRE
  ▯ Fold launch plan                     Note
DIRECTLY CONNECTED · 5
  ▯ Weekly review                        links here
  ▯ Sync design                          linked from here
  ◆ #fold                                tag
  ○ Model picker                         missing note
TWO STEPS AWAY · 8
  ▯ Reading list — local-first software  via Sync design
```

- The rows are `KnowledgeRow` (Compact), 56 dp. Section headers are headings. Tapping a row **selects** it and opens the node detail, exactly as on the canvas. TalkBack custom actions **Open** and **Centre here** are on every row.
- **With TalkBack on** (`AccessibilityManager.isTouchExplorationEnabled`), Graph **opens in List view by default**. The toggle is always available, and the choice is remembered for the session.
- **The canvas's own semantics:** content description "Graph centred on Fold launch plan: 13 items, 5 directly connected." Custom actions: *Show as list*, *Fit to screen*. Today the canvas has no semantics at all (K-P1-7).
- **Keyboard on the canvas** (Level 2, Tab to focus it):

  | Keys | Action |
  |---|---|
  | Arrows | Select the nearest node in that direction |
  | Enter | Open |
  | Ctrl+Enter | Centre here |
  | Esc | Deselect |
  | `+` / `−` | Zoom |
  | `0` | Fit |

  A focus ring is drawn around the selected node.

---

## 12. Citation landing

`CHAT_UX_SPEC.md` owns the citation chips and markers and what a tap on them means in the chat. This section owns what happens once a source opens.

**Input:** `OpenSource(docId, locator: Locator?, excerpt, revisionHash)`. These are all fields of `Citation` (`core/model/…/Vault.kt`), and all are ids or offsets, so they can go on the saved back stack.

**Steps:**

1. **Dispatch by kind** (§3.6). A note opens the editor. A File's text opens the File viewer's **Text** tab. A chat opens the chat (scrolling to the quoting message is `CHAT_UX_SPEC.md`'s). A missing document shows "Source deleted" (§8.5) and doesn't navigate.
2. **Find the passage:**
   - If `revisionMatches(citation)` (a live source): map `locator.byteStart..byteEnd` (UTF-8 bytes of `body_md`) to a character range in the body. With the body-only buffer (§6.4) that is also the editor offset.
   - Otherwise (the source has changed): search the current body for `excerpt`, first exactly, then with whitespace normalised, then its longest sentence. If found, show the banner **"This note has changed since Skein quoted it."** **[Show quoted text]**.
   - If it isn't found: open at the top with the banner **"The quoted passage isn't in this note any more."** **[Show quoted text]**. That opens a sheet with the kept excerpt.
3. **Show it:**
   - Scroll so the passage starts in the upper third of the viewport.
   - Paint it with the `passageHighlight` token as a **decoration**, not a selection. The transformer takes a highlight range, so live preview keeps rendering the lines and nothing is put into edit mode.
   - The **keyboard stays down** and the caret isn't placed.
   - The highlight stays until the user's first tap, keystroke or scroll beyond two screens. It never flashes (prompt §40, motion).
4. **Back** returns to the chat at the same scroll position (IA §3.5). On Compact, the source is a pushed route.

On Expanded, the IA pushes the source into the chat's detail pane. §19 I-1 proposes showing it *beside* the chat instead. The landing behaviour above works in either container.

---

## 13. Attach-knowledge picker: the list content

`CHAT_UX_SPEC.md` owns the ＋ entry, the container (sheet or full-screen), the chips, the Whole / Relevant-parts modes and the Add button. This spec owns **what is listed and how it is searched**. The same component also serves Graph's "Centre on…" (single-select).

- **Component:** `KnowledgePicker(mode = Multi | Single, kinds = {NOTE, FILE[, CHAT]}, exclude = alreadyAttached, onDone(ids))`. Chats are included only if `CHAT_UX_SPEC.md` makes chats attachable.
- **Search field:** "Search notes and files", with the same matching and ranking as §4 (the shared search state holder) and the kind chips.
- **Empty query:**
  - **Suggested** (≤ 4, optional): passed in by the caller. For a chat, notes near its latest sources in the graph (`ANYTHINGLLM.md` P3). Shown only if the caller supplies any.
  - **Recent** (≤ 20): `observeTimeline`, Knowledge kinds, last edited first.
- **Rows:** `KnowledgeRow` (Compact) with a checkbox in Multi mode, or a plain tap in Single mode.

| Row state | Shown |
|---|---|
| Already attached | Checked and disabled; "Attached" |
| Preparing for search | Selectable; the meta line says "Preparing for search…" (`CHAT_UX_SPEC.md` decides what that means for sending) |
| No readable text | Disabled; "No readable text" |

- **Always last:** a **"＋ Import file…"** row (§9), so the picker's empty state always has a way forward (`LOBECHAT.md` L7). An imported file comes back selected.
- **`[[` in the chat composer** uses the same ranking and excludes the same kinds. Its dead **Create “x”** row in the composer (K-P0-8) is `CHAT_UX_SPEC.md`'s to wire or hide; this spec recommends **hiding** it, because creating notes is Knowledge's job.
- **Result:** `List<DocId>` (for a File, the File's text id, since that is what retrieval reads).

---

## 14. Command palette: the Knowledge section

The palette is `CONTINUE.md`'s proposal, built in Wave 10. Knowledge contributes the following.

**Document results** (a "Notes & files" section):
- Candidates and ranking come from §4.2, so the palette and Knowledge search agree. When the query is empty: the 4 most recently opened notes (the "Jump to" section, `CONTINUE.md` §4).
- At most 5 rows; 3 on Compact with the keyboard up.
- Each row: kind icon · title with the match emphasised · for a text hit, the highlighted snippet, otherwise "Note · edited 3 h ago" or "PDF · edited…".
- Enter or tap opens by kind (§3.6).
- The fallback row, always last: **"Search Knowledge for “q”"** opens Knowledge in search mode (§4.1).

**Commands** (availability-filtered, `ZED.md` Z1):

| Command | Available when | Stage |
|---|---|---|
| New note (Ctrl+Shift+N; `/new note [title]`) | unlocked | runs (with a title, creates it titled) |
| Import file… | unlocked | runs the picker |
| Search Knowledge (Ctrl+Shift+F) | always | Knowledge in search mode |
| Go to… (Ctrl+P) | always | Pick |
| Open Graph / **Open graph around “‹note›”** | a note exists / a note is focused | runs |
| Ask about “‹note›” | a note is focused **and** CHAT supports it | runs |
| Show connections | a note or file is focused | runs |
| Rename note… | a note is focused | Text stage (§7.1) |
| Delete note… | a note is focused | **Confirm** stage, using §8.1's copy; never instant |

A command acts on the note that was focused **before** the palette opened (`ZED.md` Z5).

---

## 15. Posture changes (prompt §24–25, Tests E, F, G)

State is held in the back-stack entry, its ViewModel or `rememberSaveable`. It is never a plain `remember`.

| State | Survives fold ↔ unfold, rotation and process death | Across a vault lock |
|---|---|---|
| Destination, selected item id, list scroll position, filters, search query | yes | the ids yes; the query is dropped (it is content) |
| Editor text | yes (ViewModel; flushed on stop) | flushed, reloaded from the vault |
| Caret and scroll offset | yes (`EditorState.Saver` exists and is unused today) | yes (offsets only) |
| Title draft (not yet saved) | yes | saved on lock |
| Connections open and its scroll; the sheet ↔ pane swap | yes | yes |
| Graph centre, explicit selection, zoom, pan, List/Canvas mode, key open | yes | yes (ids and numbers) |
| Citation highlight range | yes | yes |
| An open dialog (rename text, delete confirm) | yes | closed |

- **Test E (Knowledge):** open → close with a note selected on Expanded. The outer screen shows **that note** full-screen, and Back goes to the list. Close → open: list + that note, with the same caret and scroll.
- **Test F (Graph):** fold with a node selected. The sheet shows the same node. Unfold: the pane shows it.
- **Test G:** an open Connections sheet becomes the pane, and the reverse.

---

## 16. Responsive content (prompt §45)

| Content | Surface | Rule |
|---|---|---|
| Long note title | List row, palette, Connections, picker | 1 line, end ellipsis; the full title is in semantics |
| | Inline title | Wraps; no horizontal scroll (today it is single-line and scrolls sideways) |
| | Top bar (collapsed) | 1 line, end ellipsis |
| | Dialogs | Quoted, at most 60 characters then "…"; the dialog title wraps to 2 lines |
| | Graph label | 120/160 dp, end ellipsis (§11.3) |
| | Snackbar | The title is cut to 40 characters with "…" |
| Long file name | Row meta, viewer top bar | **Middle ellipsis** (`TextOverflow.MiddleEllipsis`, Compose BOM 2026.09), so the extension stays visible: "quarterly-rep…final.pdf" |
| Deeply nested lists | Editor | Recognise list markers at **any** indentation (spaces or tabs); today only up to 3 spaces are (`ED/LivePreviewTransformer.kt:390-419`). A hanging indent for wrapped lines, if `ParagraphStyle.textIndent` in the transformed text is honoured by the field; if not, markers stay flush (verify in KN-6.4) |
| Markdown tables | Editor | Table lines (starting with `\|`) get the code style, so the columns line up in monospace. Long rows soft-wrap; they aren't scrollable inside a text field (known limitation) |
| | Text tab, citations | The read-only renderer; a wide table scrolls sideways |
| Code blocks | Editor | Monospace plus the code background; long lines wrap (known limitation of one text field) |
| | Text tab | Scrolls sideways per block |
| 200 % font | List | Rows grow (no fixed heights); the meta line wraps; ⋮ stays 48 dp; the FAB shrinks to an icon at scales above 1.5× |
| | Note | The top bar keeps ←, status, ✦ and ⋮; the status text becomes an icon with a content description when it doesn't fit |
| | Graph | Labels capped (§11.3); the List view carries full-size text |
| | Dialogs, sheets | Scroll; buttons stack vertically when they don't fit side by side |
| Very long note (10k+ lines, a 5 MB PDF text) | Editor | §18 budgets; the Text tab uses a lazy, virtualised renderer |

The fixtures for all of these are listed in §22.

---

## 17. Accessibility (prompt §40)

| Area | Requirement |
|---|---|
| Touch targets | At least 48 dp: row ⋮, chips, the properties row, autocomplete rows, the missing-link sheet buttons, Key, Fit, graph nodes (keep the 24 dp hit radius), dialog buttons. Today the id chip is about 20 dp and autocomplete rows about 36 dp |
| Labels | Every icon button has a label: "Import file", "Search knowledge", "Connections", "More options", "Back", "Show as list", "Fit to screen", "Key". Today ↗, ✦ and ✕ are glyphs with no description |
| Titles and fields | Inline title: "Note title". Body: "Note text". The search field has a label |
| Rows | One merged description plus **custom actions** (§3.3). Selected state on Expanded |
| Headings | Day headers, Connections sections, Graph list sections, Properties |
| Links | Editor custom actions per link (§5.7). Missing links say "Create note X" |
| State and announcements | "Couldn't save" (polite, once); import finished (the snackbar is announced); result count; graph selection. **Not** "Saved" on every pause |
| Expanded/collapsed | The Key chip and Unlinked mentions expose their state. (The old Backlinks header didn't, K-P1-6) |
| Destructive actions | Confirmation dialog; initial focus on **Cancel**; the button names the object |
| Colour | Kind is never shown by colour alone (icon plus colour everywhere). Missing links: muted colour **and** a dotted underline. No kind uses the error colour |
| Graph | A List view equal to the canvas, the default under TalkBack; canvas semantics and custom actions; keyboard selection (§11.9) |
| Motion | The graph simulation and the citation highlight honour reduced motion; nothing flashes |
| Keyboard | §5.10; list keys; Tab order: list → detail → Connections; Esc closes the topmost popup or sheet |
| Font scale | Tested at 1.0, 1.3 and 2.0 (§22) |

---

## 18. Performance (prompt §46)

| Area | Today | Requirement |
|---|---|---|
| **Editor, per keystroke** | 2–3 full-document `transform()` passes, plus 2 `buildLines` for the chip, plus one per hardware arrow (`AUDIT_KNOWLEDGE.md` §11) | **At most one `transform()` per value change.** The `TransformedText` is cached per (text, cursor line, style), and the key handlers reuse its offset mapping. The body-only buffer (§6.4) removes the frontmatter scans. **Budget:** p95 ≤ 16 ms per keystroke frame on the Fold for a 2,000-line note. A test counter asserts one transform per edit (KN-6.4). An incremental per-line transform (re-render only the changed lines and the old and new caret lines) is the next step, if 10k-line notes miss the budget |
| **Title** | A write, a revision and an ingest per keystroke | Saved on commit only (§5.3) |
| **Autosave** | `updateFrontmatter` + `updateBody` every 500 ms pause | One `updateBody` per save. The 500 ms debounce stays. Ingest fan-out is storage's concern |
| **Connections, backlinks** | Re-query on any write anywhere | Only while visible, debounced 500 ms (§10.2) |
| **List** | `observeTimeline` returns full `bodyMd` for 50 rows. One PDF text can be megabytes | **KB-6:** a summary projection (a body prefix of about 400 characters). `previewOf` runs off the main thread and is cached by (id, updatedAt). `LazyColumn` keys = doc id, with a `contentType` per row kind. **Budget:** scrolling p95 ≤ 16 ms with 500 items including a 5 MB PDF text |
| **Graph** | Main-thread layout, re-run on every edge write; per-frame allocation | §11.7 |
| **File viewer** | — | One page bitmap in flight; pages are recycled; the memory file is closed on leave and on lock |
| **Leaks** | Old `NoteTabState` collectors keep running across note switches in one slot (K-P2-6) | One ViewModel per back-stack entry, cleared when the entry is popped |

---

## 19. Issues with the IA

These are clarifications or proposals. None of them changes the IA's structure of five destinations, one navigation system per window size, and opening by kind.

| # | Issue | Proposal |
|---|---|---|
| **I-1** | IA §3.5/§3.8: a citation *pushes* the source over the chat. On Expanded that hides the answer the user is checking | On Expanded, open the source in the **extra pane beside the chat** (the inspector's slot), read-only first, with "Open in Knowledge" to edit. Compact still pushes. `CHAT_UX_SPEC.md` and `ADAPTIVE_LAYOUT_SPEC.md` decide; §12 works either way |
| **I-2** | IA §3.1 lists *File* as "PDF, image, text you imported". In practice text imports keep no original and are notes, and images can't be imported yet | *Files* = PDFs today (§3.5, §9.1). The import snackbar says "imported as a note" for text files |
| **I-3** | IA §3.2 lists an **AI outputs** filter. Nothing in production creates `aiout` documents | Show the chip only when some exist (§3.2). Whether "Save answer as note" creates `aiout` or `note` is a CHAT/owner decision (§25 Q5) |
| **I-4** | IA §3.2: Graph "centred on the most recently opened note". The id-only back stack doesn't survive a cold start | Fall back to the most recently edited note (§11.1). No new storage |
| **I-5** | IA §3.3 names a Dialog as the Rename surface | For notes the main rename is the inline title (§5.3). The dialog serves rows, the palette and TalkBack |
| **I-6** | IA §3.3: Connections reached from "a file's info" | Files get the same ✦ Connections, over their text (§3.5) |
| **I-7** | IA §8 Q1 (Knowledge on Medium: one pane or two) | This spec works either way; the pane-or-sheet rule in §2 decides Connections |

---

## 20. Decisions needing the owner's sign-off (amending spec §8.5–8.6)

| # | Spec today | Proposed | Why |
|---|---|---|---|
| **K-D1** | §8.5 "Backlinks drawer at bottom of every note (collapsible)" | Backlinks move into **Connections** (pane or sheet) with Links to, local graph and Properties | The drawer crushes the editor on the outer screen with the keyboard up, can't scroll, and shows a "0" badge (K-P1-6). It was also composed under chats (skein-gg11.23) |
| **K-D2** | §8.6 "Tap node → preview; long-press → pin as tab" | **Tap selects** (detail with Open / Centre here). Long-press = tap. No pin | Navigation on tap made exploration impossible and dismissed the graph (flow 21) |
| **K-D3** | §8.5 (and E7.I3) frontmatter shown as an editable block inside the editor | The editor holds the body only. Properties are read-only in v1; tags come from `#tag`; a property editor comes later (KN-6.15) | Removes the corruption bug class (K-P0-2) and implementation detail from the first line of every note |
| **K-D4** | (LIFECYCLE §11 recommends deferring link rewrite) | Rename rewrites `[[Old]]` → `[[New]]` in notes, with Undo | "A workspace knowledge graph" should not break its own links on rename (§7.2) |
| **K-D5** | (new) | Delete is confirmed, permanent and has **no Undo** | Hard delete plus no backups plus lock-driven process death (§8.2) |
| **K-D6** | §8.5 "open-or-create" on a wikilink tap | A missing link **asks** before creating | Accidental empty notes that can't be deleted (K-P1-4) |
| **K-D7** | (new) | New notes are created on the first character, discarded if left blank, and named from their first line if left untitled | No more "Untitled" duplicates (§6) |
| **K-D8** | spec §9 IME hardening (implicit) | Keep suggestions and autocorrect **off** in notes; turn on sentence and word capitalisation | Confirms the privacy trade-off explicitly (K-P2-2); see §25 Q2 |

---

## 21. Acceptance criteria

Each criterion names the test that proves it: a JVM unit or Robolectric Compose test (**U**), an instrumented or emulator test (**I**), a Roborazzi screenshot (**S**), a device check on the Fold (**D**), a Macrobenchmark (**B**). `UX_TEST_PLAN.md` schedules them.

### Notes (prompt §51: create, edit, rename, delete, search)

| # | Criterion | Test |
|---|---|---|
| AC-N1 **Create** | On Compact and Expanded, **New note** is visible in Knowledge without scrolling or typing. One tap opens an editor with the title focused and the keyboard up. Typing a title, Next, typing a body and pressing Back leaves the note at the top of the list with that title and a preview | U, S, D |
| AC-N2 **First keystroke** | For a new note and for an existing one: typing after (a) a tap at the start of the first visible line, (b) ↑ on the first line, (c) ← at the first character, (d) a paste at the start stores a `bodyMd` that begins with the typed text, with the frontmatter byte-identical. No `---` or `id:` text ever appears in a body | U, I, D (the audit's 1-minute check) |
| AC-N3 Blank and untitled | New note then Back with nothing typed: the document count is unchanged. Body only: the title comes from the first line, a " (2)" suffix is added on collision, and the snackbar offers Rename | U |
| AC-N4 **Edit / autosave** | After typing, the status shows **Saved**. A failing repository fake shows **Couldn't save**, which persists, is announced once, and whose Try again recovers. Back while failing asks before leaving | U, S |
| AC-N5 No lost edits | Type, then within 100 ms (a) press Back, (b) lock the vault, (c) recreate the Activity (fold): in all three cases the text is persisted | I, D |
| AC-N6 **Rename**, inline | Editing the title makes **no** repository write until it is saved (Enter, blur or Back), and then exactly one | U |
| AC-N7 Rename, links | Notes A and B contain `[[Old]]`, `[[Old\|a]]`, `[[Old#h]]` and a fenced `[[Old]]`. Renaming Old → New rewrites the first three and not the fenced one, in one transaction. After re-ingest, New's Linked from still lists A and B. Undo within the snackbar restores the title and the link text. A chat's `[[Old]]` is untouched and renders as missing | U, I |
| AC-N8 Rename, from the list | ⋮ → Rename on a row while that note is open in the detail pane: the inline title updates, and the next autosave does not revert it | U |
| AC-N9 **Delete** | ⋮ → Delete… shows the dialog with the quoted title; Cancel changes nothing; Delete removes the note from the list, the active search (re-run), the graph and other notes' Connections, and removes every back-stack entry for it. The user lands per §8.4. A pending autosave doesn't crash or recreate it. After restarting the app it is still gone. The Delete UI is gated on LIFECYCLE §13 items 1–5 | U, I, S |
| AC-N10 Delete copy | "N notes link to it" appears exactly when N > 0. The File dialog's "Keep the text as a note" keeps the text | U, S |
| AC-N11 After delete | A chat citation to the deleted note reads "Source deleted" and doesn't navigate. `[[Title]]` in other notes renders as missing; a tap asks before creating | U, S |
| AC-N12 **Search** | "plan" finds "Project plan" by title. A text match shows a snippet with the term highlighted. "café" finds text containing it (after KB-5). No-results offers **New note “q”**. With pending items, the "Still preparing…" line appears. Results update after a delete | U, S |
| AC-N13 Open by kind | From the list, search, Connections, the graph, citations and the palette, a chat never opens in the editor and a PDF never opens as an empty editor | U |
| AC-N14 Back on the outer screen | From a note, system Back and ← both return to the list with its scroll position and filters. Back from the list goes to Chat | U, D |

### Files

| # | Criterion | Test |
|---|---|---|
| AC-F1 | The picker doesn't offer images. A shared or forced image produces the message, no crash and no document. The same helper serves Chat's ＋ | U, I |
| AC-F2 | Importing a 20 MB PDF shows a progress row, never blocks the main thread (no ANR; frames < 50 ms during import), ends with "Imported … [Open]", and the row shows "Preparing for search…" until ingested | I, B |
| AC-F3 | The viewer shows Pages and Text. During and after viewing, no new file exists under `cacheDir` or `filesDir` other than the encrypted attachment | I |
| AC-F4 | A PDF appears as one row, one search result and one graph node. Its text never appears as a separate Note | U |

### Connections

| # | Criterion | Test |
|---|---|---|
| AC-C1 | No note shows a Backlinks strip. Connections is a sheet on Compact and a pane on Expanded. Linked from lists the same sources as today's `BacklinksState`. Rows open by kind. On fold the sheet ↔ pane swap keeps the scroll position | U, S, D |
| AC-C2 | A missing target in Links to offers Create, which creates and opens the note | U |

### Graph

| # | Criterion | Test |
|---|---|---|
| AC-G1 | From the rail or drawer, Graph opens centred on the last opened note (else the last edited one). An empty vault shows the empty state with New note | U, S |
| AC-G2 | Tapping a node selects it and never navigates. Open navigates by kind. Centre here re-centres. Back deselects before leaving | U |
| AC-G3 | On the fixture graphs: labels are ≤ 120/160 dp, never drawn outside the canvas, and never overlapping (asserted on computed label rectangles) | U, S |
| AC-G4 | No node uses the `error` colour. Every kind has an icon or shape cue. Entity and unresolvable document nodes are not drawn | U, S |
| AC-G5 | Layout runs on the injected background dispatcher. The simulation stops within 4 s. There are zero frame callbacks while idle. ≤ 8 ms per settling frame and first draw ≤ 500 ms at 80 nodes | U, B |
| AC-G6 | The List view is one tap away and is the default under TalkBack. Every drawn node appears in it with its relation | U, S |
| AC-G7 | Test F: folding with a node selected keeps the centre, the selection and the zoom | D |
| AC-G8 | The graph follows Settings › Appearance (forced light or dark) | S |

### Citations

| # | Criterion | Test |
|---|---|---|
| AC-X1 | A live citation opens the note with the passage highlighted in the upper third, keyboard down. Back returns to the chat at the same scroll position | U, D |
| AC-X2 | A changed source with the quote present: highlight plus the "changed" banner. The quote gone: the banner plus Show quoted text. A deleted source: "Source deleted", no navigation | U, S |

### Accessibility and keyboard

| # | Criterion | Test |
|---|---|---|
| AC-A1 | Every Knowledge and Graph control is ≥ 48 dp and labelled. With TalkBack alone, a user can create, open, rename and delete a note and explore the graph (List view) without drag gestures | U (semantics), D |
| AC-A2 | At 200 % font there is no clipped text in list rows, the note top bar, the inline title, dialogs or the node sheet | S |
| AC-A3 | Hardware keyboard: Shift+arrows extend the selection, Ctrl+arrows jump words, Ctrl+S saves, Ctrl+Enter follows a link, and Enter/Delete/Shift+R work on a focused list | U, D |

### Performance

| # | Criterion | Test |
|---|---|---|
| AC-P1 | One `transform()` per edit (counter). p95 typing frame ≤ 16 ms on a 2,000-line note on the Fold | U, B |
| AC-P2 | List scroll p95 ≤ 16 ms with 500 items including a 5 MB PDF text | B |

### Visual

| # | Criterion | Test |
|---|---|---|
| AC-V1 | Every state in §22 has a baseline on each listed device and theme, and `verifyRoborazzi` passes | S |

---

## 22. Screenshot states (Roborazzi)

**Devices:** `phone`, `fold-outer`, `fold-inner`, `fold-landscape` (`docs/ux/research/ROBORAZZI_SPIKE.md` §5), in light and dark. Rows marked **F** are also captured at font scale 2.0 on `fold-outer`. Rows marked **X** are Expanded only (`fold-inner`, `fold-landscape`), and **C** Compact only (`phone`, `fold-outer`). The fixtures reuse the before-set's vault ("Fold launch plan", "Weekly review", …), plus a long-content fixture: an 80-character title, `quarterly-report-2025-final-final-v3.pdf`, 6-deep nested lists, a 6-column table and a 200-character code line.

| # | State id | Notes |
|---|---|---|
| 1 | `knowledge-list` | Day groups, a File row, a preparing row. **F** |
| 2 | `knowledge-list-empty` | Empty vault |
| 3 | `knowledge-list-filter-files-empty` | Files filter with no files |
| 4 | `knowledge-list-importing` | Progress row |
| 5 | `knowledge-detail-placeholder` | **X** |
| 6 | `knowledge-row-menu` | ⋮ open |
| 7 | `knowledge-search-results` | Title and text hits, highlighted snippet. **F** |
| 8 | `knowledge-search-no-results` | With New note “q” |
| 9 | `knowledge-search-preparing` | "Still preparing 3 items…" |
| 10 | `note` | Title, properties row, rendered body. **F** |
| 11 | `note-plain` | No properties row |
| 12 | `note-new` | Empty title focused, placeholders, keyboard (**C**) |
| 13 | `note-ime` | Existing note, caret line raw, keyboard up. **C** |
| 14 | `note-save-error` | "Couldn't save" |
| 15 | `note-not-found` | |
| 16 | `note-menu` | ⋮ open |
| 17 | `note-share-sheet` | |
| 18 | `note-rename-dialog` | With the collision hint |
| 19 | `note-rename-snackbar` | "Updated links in 3 notes · Undo" |
| 20 | `note-delete-dialog` | With the links line. **F** |
| 21 | `note-deleted-snackbar` | List with focus on the neighbour |
| 22 | `note-missing-link-sheet` | |
| 23 | `note-autocomplete` | `[[` popup, Create hidden on an exact match |
| 24 | `note-long-content` | Long-content fixture. **F** |
| 25 | `connections` | Sheet (**C**) / pane (**X**) |
| 26 | `connections-empty` | |
| 27 | `file-viewer-pages` | |
| 28 | `file-viewer-text` | |
| 29 | `file-no-readable-text` | |
| 30 | `file-delete-dialog` | With "Keep the text as a note" |
| 31 | `citation-landing` | Highlighted passage |
| 32 | `citation-changed-banner` | |
| 33 | `citation-missing-banner` | |
| 34 | `attach-picker-content` | Hosted in a neutral test container; CHAT owns the real one |
| 35 | `palette-knowledge-section` | Wave 10 |
| 36 | `graph` | Fitted, key collapsed, labels ellipsised |
| 37 | `graph-selected` | Sheet (**C**) / pane (**X**). **F** |
| 38 | `graph-key-open` | |
| 39 | `graph-list-view` | **F** |
| 40 | `graph-empty-vault` | |
| 41 | `graph-unconnected-note` | |
| 42 | `graph-tag-selected` | |
| 43 | `graph-missing-note-selected` | |
| 44 | `graph-dense-80` | 80 nodes: label LOD and collision behaviour |

Captures 36–44 draw a **settled, deterministic** layout (fixed seed, simulation run to rest) so baselines are stable.

---

## 23. Backend asks

These are narrow API changes with contract tests. None of them refactors storage, the graph or RAG (prompt preamble). "API" means a `VaultRepository`/`IndexStore` contract addition without a schema change. The lifecycle work refers to `LIFECYCLE_FINDINGS.md` and `OBJECT_LIFECYCLE_SPEC.md`.

| # | Ask | UI need | Size | Class | Owner | Blocks | Fallback |
|---|---|---|---|---|---|---|---|
| **KB-1** | `renameDocument(id, title)` (G1; shared with chat rename) | Rename without echoing the body; no race with autosave | S | API | skein-xtov lifecycle follow-up (with CHAT) | KN-6.6 (soft) | `updateBody` with the live body |
| **KB-2** | `observePendingIngest(): Flow<Set<DocId>>` over `ingest_queue`, ticking on `IngestQueue` changes (and G5 so a delete ticks it) | "Preparing for search…" per row; the search honesty line | S | API | skein-xtov (storage-adjacent) | KN-6.2, KN-6.3 | Hide the state (never guess) |
| **KB-3** | `searchTitles(query, limit, match = CONTAINS)`: `LIKE '%q%'` ordered with prefix matches first, then `updated_at` | Title contains search ("plan" → "Project plan") | S | API | skein-xtov | KN-6.3 | Prefix-only titles (known gap) |
| **KB-4** | FTS `snippet()` markers changed from `[`/`]` to private-use characters (U+E000/U+E001). No consumer uses snippets today | Highlight matches without confusing them with `[[links]]` | S | API | skein-xtov | KN-6.3 | Snippet without highlights |
| **KB-5** | `FtsQuerySanitizer` tokenises on `\p{L}\p{N}`, not `[A-Za-z0-9]` | Non-ASCII text search | S | Search (core/vault) | skein-xtov | — | Documented limitation |
| **KB-6** | A summary projection for `observeTimeline` (a `body_md` prefix of about 400 characters) | The list doesn't load megabyte bodies | S | API | skein-xtov | KN-6.2 (perf) | Full bodies (slow with big PDFs) |
| **KB-7** | Link-rewrite writes that keep `updated_at` (for example `updateBody(…, touch = false)`) | Renaming a hub note doesn't flood Recent | S | API | skein-xtov | KN-6.6 (soft) | Accept the reshuffle |
| **KB-8** | LIFECYCLE §13 items 1–5: edge cleanup (G3), events and blob-after-commit (G5), export-stage purge (G6), ingest race and orphan sweep (G4), cascade test (G21) | A correct delete, no UUID ghosts | 5 × S | API/Storage | the lifecycle work (`OBJECT_LIFECYCLE_SPEC.md`) | **KN-6.7 (hard gate)** | None: delete doesn't ship |
| **KB-9** | Export writes the current `documents.title` into the exported frontmatter `title` (G13 choice (c) + fix at export) | No drift after rename, no resurrected old titles on re-import | S | Export | skein-xtov | — | Documented drift |
| **KB-10** (optional) | `listTags(): List<TagCount>` from TAG edges | A complete Tags filter | S | API | skein-xtov | — | Tags from loaded rows |
| **KB-11** (optional) | `observeTimeline` ordered by title | A "Title A–Z" sort | S | API | skein-xtov | — | Recent only |
| **KB-11b** (optional) | `countCitingChats(id)` (a JSON scan of `messages.retrieved_chunks`) | "Quoted in 2 chats" in the delete dialog | S | API | lifecycle work | — | The generic sentence |
| **KB-12** (optional, P2) | `updateFrontmatter` enqueues ingest when the frontmatter changes | A property editor whose tag edits reach the graph | S | API | skein-xtov | KN-6.15 | Properties read-only |

Not asked, because the UI can do it: the link rewrite (`edgesTo` + `updateBody` + `transaction`), File pairing (§3.5), missing-link detection (`findByTitle`), passage location (`Locator` + `revisionMatches`), and the local-graph thumbnail (`neighborhood`).

---

## 24. Implementation beads

Waves follow prompt §48: **Wave 6** is Knowledge & Notes and **Wave 8** is Graph. "Shell" means the Wave 3 adaptive shell and its back stack of ids (IA D8). Sizes: S ≤ 1 day, M ≤ 3 days, L > 3 days.

| # | Title | Scope | Size | Wave | Depends on |
|---|---|---|---|---|---|
| **KN-6.1** | Editor P0 safety | The first-keystroke **hotfix** (§6.4: mapping, initial selection, clamp) with its tests. Wire `FlushRegistry.flushAll` on lock and flush on stop. Guard the title and Save-as writes. The device check, plus the "Clean up" banner if corruption is confirmed | S | **Ahead of Wave 6 (P0)** | — |
| **KN-6.2** | Knowledge list pane | `KnowledgeRow`; single-select kind chips; Tags and Persona chips (Level 2); day groups (reuse `TL/TimelineFormatting.kt`); File pairing (§3.5); every §3.8 state; FAB; Import entry; row menu; the open-by-kind dispatcher; retire the `TimelineRail`/`TimelineScreen` wiring | M | 6 | Shell; `DESIGN_SYSTEM.md` tokens; KB-6 (perf) |
| **KN-6.3** | Knowledge search | The search view and docked field; tiered merge; highlighting; states; the preparing line; a shared search state holder for the picker and palette | M | 6 | KN-6.2; KB-2, KB-3, KB-4 (KB-5 separately) |
| **KN-6.4** | Note editor chrome and body-only buffer | Top bar (←, status, ✦, ⋮); inline title; properties row; placeholders; fill-height body; **body-only buffer** (§6.4) and removal of the frontmatter hide/show path; one transform per edit; modified arrows pass through; Ctrl+S / Ctrl+Enter; autocomplete fixes; missing-link dimming and sheet; link a11y actions; ViewModel-owned state; flush on pop; the not-found state; observing the document | M–L | 6 | Shell; KN-6.1 |
| **KN-6.5** | New note flow | Entry points; `NewNote(draftKey)`; create on the first character; naming on leave; discarding blank notes | S | 6 | KN-6.4 |
| **KN-6.6** | Rename | Inline save semantics; ⋮ Rename dialog; collision and character hints; the live title everywhere; **link rewrite** with Undo; skipped-open-note reporting; fallback disclosure behind a flag | M | 6 | KN-6.4; KB-1, KB-7 (soft) |
| **KN-6.7** | Delete note and file | Dialogs (§8.1); the operation order (§8.3); landing (§8.4); references (§8.5, incl. the "Source deleted" contract with CHAT); search re-query | S | 6 | **KB-8**; KN-6.2; `OBJECT_LIFECYCLE_SPEC.md`; shell |
| **KN-6.8** | Import from Knowledge | The shared never-throw import helper (also used by CHAT's ＋); MIME filter and post-pick guard; background, app-scoped import; multiple files; progress row; snackbars; no-readable-text state | S | 6 | KN-6.2 |
| **KN-6.9** | File viewer | Pages (`PdfRenderer` over a memory file), Text (read-only renderer), tabs and their defaults, ⋮ (incl. Save a copy…), unreadable and no-text states | M | 6 | KN-6.2, KN-6.8 |
| **KN-6.10** | Connections | The panel content (§10.1, without the thumbnail and mentions); sheet and pane; visible-only refresh; remove `BacklinksDrawer` from the note | M | 6 | KN-6.4; `ADAPTIVE_LAYOUT_SPEC.md` panes |
| **KN-6.11** | Citation landing | `OpenSource` handling; passage resolution; highlight decoration; banners; Show quoted text | S–M | 6 (with Wave 7) | KN-6.4, KN-6.9; CHAT citation tap contract |
| **KN-6.12** | Picker and palette providers | `KnowledgePicker` (Multi and Single); the palette's "Notes & files" provider and Knowledge commands | S | 6 (picker) / 10 (palette) | KN-6.3; CHAT Wave 4/7; palette Wave 10 |
| **KN-6.13** | Knowledge baselines and a11y pass | §22 states 1–35; semantics tests; the font-scale set | S | 6 | KN-6.2 → KN-6.11 |
| **KN-6.14** (P2, optional) | Empty-note cleanup | An "Empty notes" filter plus multi-select delete, only if the owner's vault needs it (§3.9) | S | 6+ | KN-6.7 |
| **KN-6.15** (P2) | Property editor | Structured tags and key–value editing | M | later | KB-12 |
| **KN-8.1** | Graph destination shell | The destination entry; default centre and fallback; subtitle picker re-centre; Recent centres; app theme; Back; posture state; empty states | M | 8 | Shell; KN-6.12 (picker) |
| **KN-8.2** | Select-then-open | Selection visuals; detail sheet and pane; Open / Centre here; tag and missing-note actions; hiding entity and ghost nodes; merging File nodes | M | 8 | KN-8.1 |
| **KN-8.3** | Canvas legibility and cost | Two-axis fit, pan clamp, Fit button; kind icons and colour roles; label LOD, ellipsis, halo and collisions; collapsible key; layout off the main thread; debounced visible-only reload; time cap and lifecycle pause; reduced motion; allocation-free frames | M | 8 | KN-8.1 |
| **KN-8.4** | Accessible graph | The List view; the TalkBack default; canvas semantics and actions; keyboard selection | S–M | 8 | KN-8.2 |
| **KN-8.5** | Connections extras | The local-graph thumbnail; Unlinked mentions (P2) | S | 8 | KN-6.10, KN-8.3 |
| **KN-8.6** | Graph baselines and benchmark | §22 states 36–44; the Macrobenchmark budgets in §11.7 | S | 8 | KN-8.1 → KN-8.4 |

Order: **KN-6.1 first** (P0, now). Then KN-6.2/6.4 in parallel, then 6.3, 6.5, 6.8, 6.10, then 6.6, 6.9, 6.11, then 6.7 (when KB-8 lands), then 6.12, 6.13. Wave 8 follows Wave 7 (context inspector) as prompt §48 orders.

---

## 25. Open questions for the owner

1. **Link rewrite on rename (K-D4):** automatic with Undo (proposed), ask every time, or never (disclose only)?
2. **Keyboard suggestions in notes (K-D8):** keep them off, as today's threat-model default, or add an opt-in "Keyboard suggestions in notes" under Settings › Privacy? Suggestions let the keyboard learn from your notes.
3. **Deleting a PDF:** should the default remove its text too (proposed, with "Keep the text as a note"), or keep the text as LIFECYCLE §15.4 suggested?
4. **Chats in the graph and in `[[`:** chats appear as graph nodes (proposed) but are excluded from `[[` suggestions in notes (proposed). Agree?
5. **"Save answer as note"** (a CHAT feature): should it create an `aiout` document (so the AI outputs filter means something) or a plain `note`?
6. **Undo/redo in the editor:** worth a separate editor project (moving to `TextFieldState`), or not needed for v1?
7. **The device check for K-P0-2:** if the owner's vault already contains corrupted notes, do you want the "Clean up" banner (KN-6.1), or will you fix those few by hand?

---

## Appendix A — audit findings and where this spec answers them

| Finding | Section |
|---|---|
| K-P0-1 no delete | §8 |
| K-P0-2 frontmatter corruption on the first keystroke | §6.4 |
| K-P0-3 everything opens as a note | §3.6, §11.5, §12 |
| K-P0-4 no titled list on either Fold display | §3 |
| K-P0-5 no way back from a note on Compact | §5.9, §11.6 |
| K-P0-6 dead Notes and Graph navigation | §2, §11 (with the IA) |
| K-P0-7 image import and Save-as crashes | §9.1, §5.8 |
| K-P0-8 dead Create row in the chat composer | §13 (the recommendation; CHAT owns it) |
| K-P0-9 last ≤ 500 ms of edits lost | §5.5 |
| K-P1-1 no visible New note | §6 |
| K-P1-2 per-keystroke rename, stale labels, broken links | §5.3, §7 |
| K-P1-3 search gaps | §4 |
| K-P1-4 silent note creation from links | §5.7 |
| K-P1-5 no back stack | §3.6 |
| K-P1-6 backlinks drawer | §10 |
| K-P1-7 graph overlay problems | §11 |
| K-P1-8 invisible autosave and export status | §5.5, §5.8 |
| K-P1-9 hardware keyboard | §5.10 |
| K-P1-10 attach model | §13 (with CHAT) |
| K-P1-11 no viewer, no Files list | §3.5, §9 |
| K-P1-12 id chip | §5.6 |
| K-P1-13 empty states | §3.8, §4.4, §11.8 |
| K-P1-14 editor chrome | §5.1 |
| K-P1-15 performance | §18 |
| K-P1-16 two editors, one document | §5.11 |
| K-P1-17 "Note not found" dead end | §5.11 |
| K-P2-1 glyph and term drift | §1, §3.3, §11.2 |
| K-P2-2 IME trade-off | §5.4, §25 Q2 |
| K-P2-3 lists, tables, code | §16 |
| K-P2-4 legend, fit, error colour | §11.2–11.4 |
| K-P2-5 long titles in tabs | Removed with tabs (IA) |
| K-P2-6 collector leaks | §18 |
| K-P2-7 caret inside links | §5.7 |
| K-P2-8 dead `TimelineDestination` | Retired with the timeline (KN-6.2) |
