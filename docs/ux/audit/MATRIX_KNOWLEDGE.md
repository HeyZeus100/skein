# Knowledge interaction matrix (skein-xtov.3)

One row per interactive element on the Knowledge side: notes, files, backlinks, wikilinks, graph, and the shell/timeline/chat entry points into them. Base `009cbb6`. Rationale and flows are in `docs/ux/audit/AUDIT_KNOWLEDGE.md`, with finding IDs in the Recommendation column.

**Path aliases:**

| Alias | Path |
|---|---|
| `APP/` | `app/src/main/kotlin/app/skein/` |
| `ED/` | `feature/editor/src/main/kotlin/app/skein/feature/editor/` |
| `GR/` | `feature/graph/src/main/kotlin/app/skein/feature/graph/` |
| `SH/` | `feature/shell/src/main/kotlin/app/skein/feature/shell/` |
| `TL/` | `feature/timeline/src/main/kotlin/app/skein/feature/timeline/` |
| `CH/` | `feature/chat/src/main/kotlin/app/skein/feature/chat/` |
| `CV/` | `core/vault/src/main/kotlin/app/skein/core/vault/` |

**Width:**

- "Single-pane" is both Fold displays: COMPACT outer, and MEDIUM inner per the brief.
- "≥ 840 dp" is dual pane only.

**Rows for absent or unwired features.** A few rows are labelled *(absent)* or *(not rendered)* to record that a required control is missing. They are marked FUTURE, not DEAD, because nothing is visible to tap. Where the gap itself is a §47 P0, the Severity column says so.

| Surface | Control | Where (file:line) | Expected Action | Actual Action | State | Severity | Recommendation |
|---|---|---|---|---|---|---|---|
| Command bar | Query field (plain text) | `SH/nav/CommandBar.kt:91-112`; `SH/nav/CommandBarState.kt:60-96` | Search all Knowledge by title and content | Debounced 150 ms. Title **prefix** match (`CV/repository/VaultSql.kt:90-92`), then body FTS over indexed chunks only. All kinds, chats included | PARTIAL | P1 | Contains-match on titles, snippets, kind filter, "No results" state (K-P1-3) |
| Command bar | Search result row | `SH/nav/SearchResults.kt:56-74`; `SH/nav/CommandBarHost.kt:66-72` | Open that document in its proper view | Opens a preview tab as `TabKind.NOTE` whatever the kind (`SH/SkeinApp.kt:226-228, 270`). No snippet shown | PARTIAL | P0 | Dispatch on `DocumentKind` (K-P0-3). Show snippet |
| Command bar | IME Search / Enter on a text query | `SH/nav/CommandBar.kt:101-102`; `SH/nav/CommandBarState.kt:111-113` | Run search / move focus to results | Silently opens the top hit | CONFUSING | P2 | Enter focuses the results list. Open only an explicitly highlighted row |
| Command bar | Results list (container) | `SH/nav/CommandBarHost.kt:65-73` | Dismissible overlay that closes on selection or outside tap | Inline in the shell column, unbounded height, pushes the workspace down, persists until the text is deleted. No clear (×) button | PARTIAL | P1 | Overlay or sheet with max height and a clear button (K-P1-3) |
| Command bar | Palette row "/new note" | `SH/nav/CommandPalette.kt:44-54`; `SH/nav/CommandBarHost.kt:59-63` | Start creating a note | Only fills `/new note ` into the field. Enter is still required | CONFUSING | P1 | Tap runs the command (prompting for a title in place) |
| Command bar | `/new note [title]` + Enter | `SH/nav/BuiltinCommands.kt:53-68` | Create a note and put me in it | Creates NOTE ("Untitled" if blank), opens it pinned. Focus stays in the command bar. The first keystroke hits the caret-at-0 bug | PARTIAL | P0 | Visible "New note" action, focus into body, fix K-P0-2 (K-P1-1) |
| Command bar | `/new`, or any partial or unknown command + Enter | `SH/nav/CommandBarState.kt:105-110` | Feedback | Nothing happens, text stays | CONFUSING | P2 | Inline "Unknown command" hint |
| Nav drawer | "▤ Notes" | `SH/nav/NavDrawer.kt:28`; `APP/MainActivity.kt:687` | Show the notes list | Renders placeholder text "NOTES" (`SH/SkeinApp.kt:429-441`), and only when no tab is active. Otherwise nothing visible changes | DEAD | P0 | Remove, then replace with the Knowledge destination (K-P0-6) |
| Nav drawer | "✦ Graph" | `SH/nav/NavDrawer.kt:29`; `APP/MainActivity.kt:687` | Open a graph | Placeholder "GRAPH" / no visible change. No global graph exists | DEAD | P0 | Remove. The graph lives per document (K-P0-6, audit §12) |
| Nav drawer | "◐ Timeline" | `SH/nav/NavDrawer.kt:27`; `SH/SkeinApp.kt:323-333` | Return to the list | With a tab active, nothing changes: the tab keeps rendering | PARTIAL | P0 | Navigating to a destination must show it (K-P0-5) |
| Icon rail (split view, ≥ 840 dp) | "✦" Graph | `SH/layout/IconRail.kt:45,54`; `SH/layout/AdaptivePaneHost.kt:71-73` | Open the graph | `onGraph = {}`: nothing | DEAD | P0 | Remove, or wire to the active note's Connections (K-P0-6) |
| Single-pane landing | Timeline rail glyph cell | `TL/TimelineRail.kt:47-63`; `APP/MainActivity.kt:712-717`; `SH/SkeinApp.kt:325-326` | Pick a note by name | Up to 20 emoji cells (📄 💬 📎 ✧) with **no titles**. Tap opens a NOTE-kind preview. TalkBack reads "Note"/"Chat" | CONFUSING | P0 | Titled Knowledge list as the landing (K-P0-4) |
| Single-pane landing | *(absent)* empty-vault call to action | `TL/TimelineRail.kt:40-65` | "Create your first note / Ask Skein" | Blank screen | FUTURE | P1 | §33 empty state (K-P1-13) |
| Timeline (≥ 840 dp) | Row tap | `TL/TimelineRow.kt:43-49`; `APP/MainActivity.kt:707` | Open the document | Preview tab, always NOTE kind. Chats open as editable transcripts, attachments as empty editors | PARTIAL | P0 | Kind dispatch (K-P0-3) |
| Timeline (≥ 840 dp) | Row long-press | `TL/TimelineRow.kt:47`; `APP/MainActivity.kt:708` | Context menu (open, rename, delete) | Opens pinned (hidden gesture, no feedback) | CONFUSING | P1 | Long-press or ⋮ menu: Open in new tab · Rename · Delete |
| Timeline (≥ 840 dp) | Kind filter chips (Notes · Chats · Files · AI) | `TL/FilterBar.kt:39-46`; `TL/TimelineFormatting.kt:190-196` | Filter by kind | Filters | WORKING | P2 | Keep in Knowledge. Align "Files" with the graph legend's "Attachment" (K-P2-1) |
| Timeline (≥ 840 dp) | Tag chips | `TL/FilterBar.kt:47-54` | Filter by tag | Filters | WORKING | — | Keep |
| Timeline (≥ 840 dp) | Persona dropdown chip + menu | `TL/FilterBar.kt:79-104` | Filter by persona | Filters. Shown only when personas exist | WORKING | P2 | Consider dropping from the Knowledge list (persona is a chat parameter) |
| Timeline (≥ 840 dp) | "Clear filters" | `TL/TimelineScreen.kt:264-271` | Reset filters | Resets | WORKING | — | Keep |
| Timeline | "📄 New note" FAB / header button *(not rendered)* | `TL/TimelineScreen.kt:289-334`; not passed at `APP/MainActivity.kt:705-711, 856` | Create a note | Hidden: `onNewNote` is null | FUTURE | P1 | Wire it (K-P1-1) |
| Tab chrome (COMPACT) | "Recent ▾" header + menu items | `SH/tabs/RecentDropdown.kt:41-80` | Switch, close, return to list | Switch only. No close, no "back to list". Long titles wrap inside a fixed 36 dp row | PARTIAL | P0 | Close per item and a Back/Up route (K-P0-5) |
| Tab chrome (≥ 600 dp) | Tab chip × | `SH/tabs/TabStrip.kt:161-168` | Close the tab | Closes (about 12 dp target). Pending autosave dropped if it was the last tab | PARTIAL | P1 | 48 dp target. Flush before dispose (K-P0-9) |
| Tab chrome (≥ 600 dp) | Tab chip title (after rename) | `SH/tabs/TabStrip.kt:151-160`; `SH/tabs/Tab.kt:47-55` | Reflect the current title | Keeps the title from when the tab opened. Unclipped width | PARTIAL | P1 | Observe the document title. Ellipsize (K-P1-2, K-P2-5) |
| System | Back (any Knowledge surface) | repo-wide: no `BackHandler` | Close overlay, then detail, then list | Leaves the app (can trigger lock-on-background, which discards tabs) | DEAD | P0 | `BackHandler` / predictive back chain (K-P0-5) |
| Note header | Title field | `ED/notetab/NoteTab.kt:255-261`; `ED/notetab/NoteTabState.kt:122-132` | Rename the note | Writes `updateBody` per keystroke with no debounce. Tab label stale. Links to the old title silently break. No label. Crashes if the document is missing | PARTIAL | P1 | Styled title with a "Title" label, debounced, rename propagation, guarded write (K-P1-2, K-P1-17) |
| Note header | *(absent)* ⋮ overflow → Rename / Delete / Move to… | — | Manage the note | No overflow menu exists. `deleteDocument` has no UI caller | FUTURE | P0 | Add ⋮ with Delete (confirm) and Rename (K-P0-1) |
| Note header | ↗ share button | `ED/notetab/NoteTab.kt:301-312` | Open the share/export menu | Opens the menu. No `contentDescription`. Never disabled (`unlockState` not passed, `APP/MainActivity.kt:692-701`) | WORKING | P2 | Label "Share or export". Move into ⋮ |
| Note header | Menu "Share as text" | `ED/notetab/NoteTab.kt:314-321, 187-189` | Android share sheet with the note text | Share chooser with the live buffer body | WORKING | — | Keep |
| Note header | Menu "Save as Markdown (.md)" | `ED/notetab/NoteTab.kt:322-329, 165-177, 190-193` | Save a file and confirm | SAF create, writes after flush. **No success/failure feedback.** An I/O or lock exception inside `scope.launch` is uncaught (crash) | PARTIAL | P0 | Catch errors and show a snackbar result (K-P0-7) |
| Note header | Menu "Save as Word (.docx)" | `ED/notetab/NoteTab.kt:330-337` | Same as above | Same as above | PARTIAL | P0 | Same as above |
| Note header | Menu "Export as PDF" | `ED/notetab/NoteTab.kt:338-345, 194-201` | Print / save as PDF | Flushes, then the system print dialog | WORKING | — | Keep |
| Note header | ✦ graph button | `ED/notetab/NoteTab.kt:273-278`; `APP/MainActivity.kt:698, 729-750` | Show this note's connections | Full-window graph overlay. No `contentDescription` | WORKING | P1 | Open Connections (supporting pane or route). Label it (K-P1-7) |
| Editor | Body text field (typing) | `ED/SkeinEditor.kt:371-389`; `ED/notetab/NoteTabState.kt:203-237` | Write; autosave | Autosaves after 500 ms. **Caret at transformed 0 inserts before the hidden frontmatter** (`ED/LivePreviewTransformer.kt:135-136`, `ED/notetab/NoteTabState.kt:205`), which corrupts new notes. No placeholder. One-line tap target (`ED/notetab/NoteTab.kt:223`) | PARTIAL | P0 | Fix the mapping and initial caret. Fill-height tap target. Placeholder (K-P0-2) |
| Editor | *(absent)* save-status indicator | `ED/EditorState.kt:126-132` (unused) | "Saved / Saving / Couldn't save" | Nothing rendered. Errors silent | FUTURE | P1 | Subtle status in the header (K-P1-8) |
| Editor | Frontmatter chip "— id 0192… ▸" / "▾ frontmatter" | `ED/frontmatter/FrontmatterChip.kt:49-70`; `ED/SkeinEditor.kt:138-151` | View note properties | Expands the raw YAML inline. The id line is protected. Shown on **every** note. About 20 dp tap target | CONFUSING | P1 | Move to Properties in the supporting pane or ⋮. Hide the id (K-P1-12) |
| Editor | Rendered `[[wikilink]]` tap | `ED/SkeinEditor.kt:158-179`; `ED/notetab/NoteTabState.kt:299-309` | Open the linked note (or ask before creating) | Opens the existing note or **silently creates** an empty one. Replaces the origin preview tab. Not activatable by keyboard or TalkBack. Can't place the caret inside a link | PARTIAL | P1 | Confirm-create, dim broken links, back stack, a11y action (K-P1-4, K-P1-5) |
| Editor | `[[` autocomplete row | `ED/autocomplete/WikilinkAutocomplete.kt:123-140` | Insert a link | Inserts `[[Title]]`. Includes chats ("Chat" × n). Text-width, about 36 dp rows | WORKING | P2 | Exclude chats. Full-width 48 dp rows |
| Editor | `[[` "Create \"x\"" row | `ED/autocomplete/WikilinkAutocompleteState.kt:132-138`; `ED/notetab/NoteTabState.kt:280-282` | Create a new linked note | Creates it even when a note with that exact title exists (duplicates) | PARTIAL | P1 | Hide Create on an exact match (K-P1-4) |
| Editor | Hardware ↑↓←→ | `ED/SkeinEditor.kt:198-230, 276-316` | Move caret; Shift extends; Ctrl jumps words | Moves the caret, but always consumes the key: Shift and Ctrl modifiers are ignored (selection collapses) | PARTIAL | P1 | Pass modified arrows through or implement them (K-P1-9) |
| Editor | "Note not found" state | `ED/notetab/NoteTab.kt:209-214` | Explain and offer Close / Back to list | Red text only. Header still live (title edit crashes) | DEAD | P1 | Error state with Close (K-P1-17) |
| Backlinks drawer | "Backlinks [n]" header | `ED/backlinks/BacklinksDrawer.kt:63-75` | Expand or collapse the list | Toggles. No chevron, no expanded-state semantics. Shows "0" badge always. About 40 dp tall | PARTIAL | P1 | State semantics, hide or quiet when 0, move to Connections (K-P1-6) |
| Backlinks drawer | Expanded list (container) | `ED/backlinks/BacklinksDrawer.kt:76-91`; `ED/notetab/NoteTab.kt:216-235` | Scrollable list, editor stays usable | Unbounded, unscrollable Column. Can squeeze the editor to 0 height (outer display with the IME up). Rows can be clipped out of reach. Also composed for chat transcripts (skein-gg11.23) | PARTIAL | P1 | Bounded `LazyColumn` / sheet. Only for notes (K-P1-6, K-P0-3) |
| Backlinks drawer | Backlink row | `ED/backlinks/BacklinksDrawer.kt:111-152`; `ED/notetab/NoteTabState.kt:114, 285-290` | Open the linking document | Opens a preview (NOTE kind even for chats). Replaces the origin preview | PARTIAL | P1 | Kind dispatch, back stack (K-P0-3, K-P1-5) |
| Graph overlay | ✕ close | `GR/GraphScreen.kt:62-74` | Close the graph | Closes. No `contentDescription` | WORKING | P2 | Label "Close graph" |
| Graph overlay | Document node tap | `GR/GraphView.kt:196-200`; `APP/MainActivity.kt:734-740` | Select the node and show its details | Opens a NOTE-kind preview **and dismisses the graph**. No selection | CONFUSING | P1 | Select, then a detail sheet with Open / Center here (K-P1-7) |
| Graph overlay | Document node long-press | `GR/GraphView.kt:201-204`; `APP/MainActivity.kt:741-747` | Context menu | Opens pinned and dismisses (hidden gesture) | CONFUSING | P2 | Long-press menu (Open, Open in new tab, Center here) |
| Graph overlay | Entity / tag / unresolved-title node tap | `GR/GraphView.kt:268-270` | Show what it is (filter by tag, create the missing note) | Nothing, no feedback | DEAD | P1 | Selection details. "Create note" for unresolved titles |
| Graph overlay | Deleted-document node (UUID label) tap | `GR/GraphModels.kt:65-70`; `GR/GraphState.kt:136` | Explain that it is missing | Opens "Note not found" | CONFUSING | P2 | Mark as missing. Don't navigate |
| Graph overlay | Node drag | `GR/GraphView.kt:338-398` | Rearrange nodes | Moves the node; the simulation reheats | WORKING | — | Keep |
| Graph overlay | Pinch / pan | `GR/GraphView.kt:110-114, 193` | Zoom and pan | Works (0.4×–4×). No reset / fit / double-tap | WORKING | P2 | Add "Fit" (K-P2-4) |
| Graph overlay | Legend | `GR/GraphScreen.kt:61`; `GR/GraphLegend.kt:27-47` | Explain the colours; collapsible | Always shown. Colour-only kinds. AI output uses the error colour | CONFUSING | P2 | Collapsible. Shape plus colour (K-P2-4) |
| Graph overlay | Canvas for TalkBack | `GR/GraphView.kt:165-223` | Navigable nodes or a list alternative | No semantics at all | DEAD | P1 | Semantic "Connections" list alongside the canvas (K-P1-7) |
| Chat composer | 📎 attach | `CH/ChatBottomBar.kt:196-201, 119-135`; `CH/ChatViewModel.kt:286-298` | Attach a file to this conversation | Imports permanently into the vault. **Images crash** (`CV/transfer/ImportServiceImpl.kt:176-184`). PDF/text inserts a `[[displayName]]` that doesn't match the created note. No progress. Main-thread I/O | PARTIAL | P0 | MIME filter or handle images, error handling, feedback, correct link (K-P0-7, K-P1-10) |
| Chat composer | `[[` "Create \"x\"" row | `APP/MainActivity.kt:589-600`; `CH/ChatScreen.kt:56` | Create the note | Inserts `[[x]]` only. `onCreateWikilink` not wired, so nothing is created | DEAD | P0 | Wire it or hide the row (K-P0-8) |
| Chat composer | `[[` suggestion row | `CH/ChatBottomBar.kt:108-109, 146`; `APP/MainActivity.kt:594-596` | Attach that note as context | Inserts link text only. Retrieval does not treat it as pinned context | CONFUSING | P1 | Explicit "attach note" chip with pinned-context semantics (K-P1-10) |
| Chat context / citations | Context row, citation chip | `CH/ContextPanel.kt:56-66`; `CH/ChatViewModel.kt:265-270` | Open the source document | NOTE-kind preview whatever the source kind | PARTIAL | P1 | Kind dispatch (K-P0-3) |
| Knowledge | *(absent)* Files list / attachment viewer / file delete | `SH/SkeinApp.kt:419`; no `openAttachment` UI caller | Browse, preview and remove imported files | None. Attachments open as an empty note editor | FUTURE | P1 | Files in the Knowledge list plus a read-only viewer (K-P1-11) |
| Knowledge | *(absent)* Delete note | `CV/repository/VaultRepositoryImpl.kt:266-280` (no caller) | ⋮ → Delete, with confirm | Impossible | FUTURE | P0 | K-P0-1 (semantics per skein-xtov.10) |
| Hidden route | `TimelineDestination` rows | `APP/MainActivity.kt:853-857` | Open a document | `onEntryClick = {}`. Reachable only through an ATTACHMENT tab, which nothing opens | REMOVE | P2 | Delete the dead code (K-P2-8) |
