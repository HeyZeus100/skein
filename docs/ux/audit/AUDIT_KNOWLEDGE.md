# Knowledge audit: notes, files, backlinks, graph

**Bead:** skein-xtov.3 (epic skein-xtov, brief `docs/research/SKEIN_UI_UX_OVERHAUL_PROMPT.md`)
**Base:** `main` at `009cbb6`
**Method:** code read only. I did not run Gradle or a device. Every claim cites `file:line`. Where a behaviour depends on runtime timing or platform internals, the text says so and lists it under §14 (needs device verification).
**Companion:** `docs/ux/audit/MATRIX_KNOWLEDGE.md` (one row per interactive element).

## Path aliases used below

| Alias | Path |
|---|---|
| `APP/` | `app/src/main/kotlin/app/skein/` |
| `ED/` | `feature/editor/src/main/kotlin/app/skein/feature/editor/` |
| `GR/` | `feature/graph/src/main/kotlin/app/skein/feature/graph/` |
| `SH/` | `feature/shell/src/main/kotlin/app/skein/feature/shell/` |
| `TL/` | `feature/timeline/src/main/kotlin/app/skein/feature/timeline/` |
| `CH/` | `feature/chat/src/main/kotlin/app/skein/feature/chat/` |
| `CV/` | `core/vault/src/main/kotlin/app/skein/core/vault/` |
| `CM/` | `core/model/src/main/kotlin/app/skein/core/model/` |

Width tiers come from `SH/layout/PaneLayout.kt:13-21`: COMPACT < 600 dp ≤ MEDIUM < 840 dp ≤ EXPANDED. The Fold's outer display is COMPACT. Per the brief, the inner display is about MEDIUM. **Both tiers are single-pane** (`SH/layout/PaneLayout.kt:55-66`), so everything this audit says about "single-pane" applies to both Fold displays. The dual-pane layout, and with it the only full timeline list, appears only at ≥ 840 dp.

---

## 0. Executive summary

The Knowledge side has solid parts: a live-preview editor, autosave, wikilink autocomplete, a backlinks drawer, share/export, and a local force graph. All of them sit behind almost no product-level navigation. On the primary device today:

- **No list of notes shows titles on either Fold display.** With no tab open, the single-pane landing is `TimelineRail`: a centered column of up to 20 emoji glyphs with no titles (`SH/SkeinApp.kt:325-326`, `APP/MainActivity.kt:712-717`, `TL/TimelineRail.kt:40-65`). The titled `TimelineScreen` is composed only at ≥ 840 dp.
- **You can create a note only by typing `/new note …` in the command bar** (`SH/nav/BuiltinCommands.kt:53-68`). The timeline's New-note FAB and header button exist in code but are never wired (`APP/MainActivity.kt:705-711`).
- **You cannot delete a note or file anywhere.** `VaultRepository.deleteDocument` (`CM/Vault.kt:306`) has no caller in `feature/` or `app/`.
- **Every document opens in the note editor, whatever its kind.** The timeline, search, graph, backlinks, chat citations and context rows all mint `TabKind.NOTE` (`SH/SkeinApp.kt:227,230,238,241,411`; `CH/ChatViewModel.kt:269`; `CH/ContextPanel.kt:64`). A past chat therefore opens as an editable transcript with a "Backlinks 0" strip where the composer should be. That is the most likely cause of the filed "Backlinks overlap" bug (skein-gg11.23, §5.4). An imported PDF's attachment row opens as an empty editor.
- **Typing into a new note very likely corrupts its frontmatter.** The live-preview offset mapping returns raw offset 0 for transformed offset 0 (`ED/LivePreviewTransformer.kt:135-136`). The editor also starts with the caret at 0 (`ED/notetab/NoteTabState.kt:205`). So the first keystroke in an empty-body note, or at the start of the first visible line, lands *before* the hidden `---` block (§3.3, P0).
- **On the outer display, once a note is open there is no way back to the list.** The "Recent ▾" dropdown has no Close (`SH/tabs/RecentDropdown.kt:62-79`). No `BackHandler` exists anywhere in the codebase. The drawer's "Timeline" entry does nothing while a tab is active (`SH/SkeinApp.kt:323-333`).
- **The drawer's "Notes" and "Graph" entries are dead.** They render a bare `DestinationPlaceholder` showing the enum name (`APP/MainActivity.kt:687`).
- **The graph works as a canvas, with limits.** It is a full-window overlay at every width. Tapping a node opens that document and dismisses the graph, so there is no selection or detail view. It has zero accessibility semantics, and it is dismissed by any fold/unfold because its state is a plain `remember` and the Activity is recreated.

The only file-import UI is the chat composer's 📎. It crashes on images: `importImage` throws inside an unguarded `rememberCoroutineScope` launch (`CV/transfer/ImportServiceImpl.kt:176-184`, `CH/ChatBottomBar.kt:121-133`). It also runs PDF extraction on the main thread and inserts a `[[displayName]]` that does not match the title it just created.

---

## 1. What is actually composed in the running app

| Composable / holder | Composed from (real app) | When the user sees it | Notes |
|---|---|---|---|
| `NoteTab` + `NoteTabState` | `APP/MainActivity.kt:691-702` through `SH/SkeinApp.kt:406-414` | Any tab of `TabKind.NOTE` is active | REAL |
| `SkeinEditor` + `EditorState` | `ED/notetab/NoteTab.kt:221-232` | Inside a note tab once loaded | REAL (the `wikilinkSuggest != null` branch, `ED/SkeinEditor.kt:348-394`) |
| `FrontmatterChip` | `ED/SkeinEditor.kt:138-151` | **Every** vault note: every document gets an `id:` frontmatter key (`CV/repository/VaultRepositoryImpl.kt:141-143, 916-923`) | REAL |
| `WikilinkAutocompletePopup` | `ED/SkeinEditor.kt:390-392` | After typing `[[` in a note | REAL |
| `BacklinksDrawer` + `BacklinksState` | `ED/notetab/NoteTab.kt:235`; state built in `ED/notetab/NoteTabState.kt:108-115` | Bottom of every note tab | REAL |
| Share menu (`ShareMenuButton`) | `ED/notetab/NoteTab.kt:262-267` | Note header, "↗" | REAL. `unlockState` is never passed, so the button is always enabled (`APP/MainActivity.kt:692-701`) |
| `GraphScreen` / `GraphView` / `GraphLegend` | `APP/MainActivity.kt:729-750` through `SH/SkeinApp.kt:368` (`overlay` slot) | After tapping ✦ in a note header | REAL. Full-window overlay |
| `TimelineScreen` (titled list) | `APP/MainActivity.kt:704-711` (left pane) | **Only at ≥ 840 dp** (dual pane, `SH/layout/AdaptivePaneHost.kt:58-69`) | REAL, but not on the Fold at MEDIUM or COMPACT |
| `TimelineRail` (glyph column) | `APP/MainActivity.kt:712-717` through `SH/SkeinApp.kt:325-326` | Single-pane landing (both Fold displays) while no tab is open | REAL |
| `TimelineDestination` (a second titled `TimelineScreen`, `onEntryClick = {}`) | `APP/MainActivity.kt:647, 853-857` | Only through `tabContent`'s `else` branch for `TabKind.ATTACHMENT` (`SH/SkeinApp.kt:419`), which no production code opens | Effectively UNREACHABLE. Its rows would be dead if reached |
| Command bar search / palette | `SH/nav/CommandBarHost.kt:37-75` | Always (top of shell) | REAL |
| `RecentDropdown` | `SH/tabs/TabHost.kt:61-67` | COMPACT only (`SH/tabs/TabHost.kt:52`) | REAL |
| `TabStrip` + `TabContextMenu` | `SH/tabs/TabHost.kt:68-80` | MEDIUM and EXPANDED | REAL |
| `NavDrawer` Notes / Graph | `SH/nav/NavDrawer.kt:28-29` leading to `APP/MainActivity.kt:687` | Drawer | REAL but the destinations are placeholders |
| `IconRail` ✦ Graph | `SH/layout/AdaptivePaneHost.kt:71-73` | Only in EXPANDED split view (split forces RAIL, `SH/layout/AdaptiveLayoutState.kt:52-61`) | REAL; `onGraph` defaults to `{}` (`SH/layout/IconRail.kt:45,54`) |
| `SkeinEditorPreview.kt` (3 `@Preview`s), `SkeinEditorReadOnlyPreview` | none | Android Studio only | PREVIEW |
| `TimelineScreenPreview.kt` (debug source set) | none | Android Studio only | PREVIEW. This is the only place `onNewNote`/`onNewChat` are ever passed |
| `MockTabContent`, `TabStripPreviews`, `AdaptivePaneHostPreviews` | none in the app (replaced by `:app` slots) | never | PREVIEW/MOCK |

**APIs that exist but are not wired in the app:**

- `EditorState.autosaveStatus` / `autosaveError`: no UI renders them (`ED/EditorState.kt:126-132`). The only reader is `watchFirstEdit`, for tab pinning (`ED/notetab/NoteTabState.kt:245-252`).
- `EditorState.knownWikilinkTitles`: never set (`ED/notetab/NoteTabState.kt:203-210`), so broken links are never dimmed.
- `EditorState.Saver`: unused, so caret and scroll are lost on Activity recreation.
- `NoteTab(unlockState = …)`: not passed.
- `TimelineScreen(onNewNote/onNewChat)`: not passed.
- `CommandScope.EDITOR`: nothing registers it, and `CommandRegistry.filter/match` only ever read GLOBAL (`SH/nav/CommandRegistry.kt:68-96`).
- `FlushRegistry.flushAll()`: never called (`APP/MainActivity.kt:359-366`).
- `rememberBacklinksState`: unused (`ED/backlinks/BacklinksDrawer.kt:161-181`).
- `VaultRepository.deleteDocument`, `openAttachment`, `attachmentMimeType`: no UI caller.

---

## 2. Inventory

### 2.1 Composables and state holders (Knowledge side)

**Editor (`:feature:editor`)**

- `NoteTab` (`ED/notetab/NoteTab.kt:98-237`) is a Column of:
  - `NoteTabHeader`
  - `HorizontalDivider`
  - a loading, error or editor Surface
  - `BacklinksDrawer`
- `NoteTabHeader` (`:239-281`): the title field, `ShareMenuButton` and the ✦ `IconButton`.
- `ShareMenuButton` (`:292-348`): "↗" `IconButton` plus a `DropdownMenu` with 4 items.
- `NoteTabState` (`ED/notetab/NoteTabState.kt:68-310`): the title, `loading`, `loadError`, `editorState`, `backlinksState`, share/export intents, wikilink suggest/create/open, and the first-edit pin.
- `SkeinEditor` (`ED/SkeinEditor.kt:113-395`):
  - `SecureBasicTextField` with `LivePreviewTransformation`
  - frontmatter chip
  - wikilink tap routing (`:158-179`)
  - hardware Up/Down (`:198-230`) and Left/Right (`:276-316`) re-implementations
  - `[[` popup
- `EditorState` (`ED/EditorState.kt:92-253`): raw `TextFieldValue`, a 500 ms debounced autosave collector (`:140-146`), `flush`, the frontmatter expand flag, and the protected-`id` guard.
- `LivePreviewTransformer` / `transform()` (`ED/LivePreviewTransformer.kt:63-142`): line-scoped live preview and offset table.
- `FrontmatterChip` / `FrontmatterChipLabel` (`ED/frontmatter/FrontmatterChip.kt:41-114`), `FrontmatterBlock` and `ProtectedIdGuard`.
- `WikilinkAutocompleteState`, `WikilinkAutocompletePopup`, `EditorAutocompleteHost` and `TitleMatcher` (autocomplete package).
- `BacklinksDrawer` and `BacklinksState` (backlinks package).
- `ShareIntents` and `SaveAsIntents` (share package): pure intent builders.

**Graph (`:feature:graph`)**

- `GraphScreen` (`GR/GraphScreen.kt:33-78`): `EdgeToEdgeSurface` holding the `GraphView`, `GraphLegend` (bottom-start) and a ✕ `IconButton` (top-end).
- `GraphView` (`GR/GraphView.kt:95-226`): a `Canvas` with node drag, `transformable` pan/zoom, and `detectTapGestures` tap/long-press.
- `GraphState` (`GR/GraphState.kt:72-206`): 2-hop `neighborhood`, BFS hops, title hydration, `ForceLayout.compute`, and a reload on every `EdgesReplaced`.
- `GraphSimulation`: live damped simulation. `GraphHitTest`, `GraphTransform`, `GraphLegend`.

**Entry points outside the two modules**

- Command bar (`SH/nav/CommandBar.kt:56-140`), `CommandBarHost` (`SH/nav/CommandBarHost.kt:27-76`), `CommandBarState` (`SH/nav/CommandBarState.kt:47-118`), `CommandPalette` (`SH/nav/CommandPalette.kt:25-59`), `SearchResults` (`SH/nav/SearchResults.kt:45-78`).
- `BuiltinCommands.newNoteCommand` / `chatCommand` (`SH/nav/BuiltinCommands.kt:22-68`).
- `TabsState` (`SH/tabs/TabsState.kt`), `TabHost`, `TabStrip`, `RecentDropdown`, `TabContextMenu`.
- `TimelineScreen`, `TimelineRail`, `TimelineRow`, `FilterBar` (`TL/`).
- Chat composer 📎 and `[[` (`CH/ChatBottomBar.kt:77-219`), `ChatViewModel.attach` (`CH/ChatViewModel.kt:286-298`).

### 2.2 Controls, menus, dialogs, text fields

| Kind | Element | Where |
|---|---|---|
| Text field | Note title: Material `TextField` (filled), singleLine, no label, no placeholder | `ED/notetab/NoteTab.kt:255-261` → `SH/input/SecureTextField.kt:103-150` |
| Text field | Note body: `BasicTextField`, multi-line, no placeholder, no label | `ED/SkeinEditor.kt:371-389` → `SH/input/SecureBasicTextField.kt` |
| Text field | Command bar ("search or /command"), `ImeAction.Search` | `SH/nav/CommandBar.kt:91-112` |
| Icon button | ↗ share (glyph text, no `contentDescription`) | `ED/notetab/NoteTab.kt:301-312` |
| Menu | Share as text · Save as Markdown (.md) · Save as Word (.docx) · Export as PDF | `ED/notetab/NoteTab.kt:313-346` |
| Icon button | ✦ graph (glyph text, no `contentDescription`) | `ED/notetab/NoteTab.kt:273-278` |
| Toggle row | Frontmatter chip "— id 0192abcd… ▸" / "▾ frontmatter" | `ED/frontmatter/FrontmatterChip.kt:49-70` |
| Popup | `[[` suggestions plus "Create \"x\"" row, 240 dp wide | `ED/autocomplete/WikilinkAutocomplete.kt:106-144` |
| Toggle row | "Backlinks [n]" header | `ED/backlinks/BacklinksDrawer.kt:63-75` |
| List rows | Backlink rows (title, ×count, 2-line excerpt) | `ED/backlinks/BacklinksDrawer.kt:111-152` |
| Icon button | ✕ close graph (glyph, no `contentDescription`) | `GR/GraphScreen.kt:62-74` |
| Canvas gestures | Node tap / long-press / drag; pinch / pan | `GR/GraphView.kt:165-206, 338-398` |
| Legend | Note · Chat · Attachment · AI output · Entity / tag | `GR/GraphLegend.kt:27-47` |
| System dialog | SAF `ACTION_CREATE_DOCUMENT` (Save as) | `ED/notetab/NoteTab.kt:165-177, 190-193` |
| System dialog | Android share chooser | `ED/notetab/NoteTab.kt:187-189` |
| System dialog | PrintManager (Export as PDF) | `ED/notetab/NoteTab.kt:194-201` |
| System dialog | SAF `OpenDocument` (chat 📎) | `CH/ChatBottomBar.kt:119-135` |
| In-app dialogs | **None.** No confirm, rename or delete dialog exists on the Knowledge side | — |

### 2.3 Empty, loading and error states

| State | What renders | Where | Assessment |
|---|---|---|---|
| Note loading | Centered `CircularProgressIndicator` | `ED/notetab/NoteTab.kt:205-208` | OK |
| Note missing | Red text "Note not found", no action. The header stays live, and typing a title there throws from `updateBody` (`NoSuchElementException`, `CV/repository/VaultRepositoryImpl.kt:825-826`) inside an unguarded `scope.launch` (`ED/notetab/NoteTabState.kt:124-131`) | `ED/notetab/NoteTab.kt:209-214` | Dead end plus a crash path |
| Empty note | Blank editor: no placeholder, a one-line tap target, the id chip above | `ED/SkeinEditor.kt:371-389`; `ED/notetab/NoteTab.kt:223` (`fillMaxWidth` only) | No next action |
| No backlinks | Header shows a "0" badge. Expanded: "No backlinks yet" | `ED/backlinks/BacklinksDrawer.kt:74-86` | Noisy when collapsed |
| Autosave error | **Not rendered** (`AutosaveStatus.ERROR` is set at `ED/EditorState.kt:227-231` but nothing reads it) | — | Silent failure |
| Save as / export result | **Nothing.** No success or failure feedback. Exceptions are uncaught (`ED/notetab/NoteTab.kt:171-175`) | — | Silent / crash |
| Graph loading | `CircularProgressIndicator` (first load only) | `GR/GraphView.kt:160-163` | OK |
| Graph with no connections | A single center node, no message | `GR/GraphState.kt:166-170` | No explanation |
| Timeline empty (EXPANDED only) | "Nothing here yet / New notes, chats, and AI outputs show up here." No action | `TL/TimelineScreen.kt:244-273` | Violates §33 |
| Rail empty (single-pane landing) | **Blank screen** under the command bar. `TimelineRail` has no empty branch | `TL/TimelineRail.kt:40-65` | Violates §33: first launch on the Fold shows nothing |
| Dual-pane right pane, no tab | "No tabs open — back to timeline" | `SH/tabs/TabHost.kt:98-109` | The exact string §33 calls out |
| Search with no hits | **Nothing renders** (list hidden when empty, `SH/nav/CommandBarHost.kt:65`) | — | You can't tell "no results" from "still searching" |
| Palette, no match | "No matching commands" | `SH/nav/CommandPalette.kt:35-41` | OK |
| Drawer Notes / Graph | Enum name "NOTES" / "GRAPH" in `headlineMedium`, top-left | `SH/SkeinApp.kt:429-441`; `APP/MainActivity.kt:687` | Placeholder shipped as product |

### 2.4 Keyboard and IME behaviour in the editor

- **IME hardening:**
  - Both fields go through `SecureImeInterceptor`: no suggestions, no personalized learning, `autoCorrectEnabled = false` (`SH/input/SecureBasicTextField.kt`, `SH/input/SecureTextField.kt:124-141`).
  - No `KeyboardCapitalization`, so no auto-capitals.
  - This is a deliberate threat-model choice (spec §9), but it makes long-form writing on the soft keyboard noticeably worse. Surface it as a product decision, not a bug.
- **Insets:** the shell pads the whole column by `WindowInsets.safeDrawing`, which includes the IME (`SH/SkeinApp.kt:296-301`). The note tab therefore shrinks above the keyboard. The backlinks header (and the drawer, if expanded) stays pinned between editor and keyboard (`ED/notetab/NoteTab.kt:235`).
- **Hardware arrows:** Up/Down (`ED/SkeinEditor.kt:198-230`) and Left/Right (`:276-316`) are fully re-implemented and **always consume the key**. They ignore modifiers.
  - Shift+Arrow no longer extends a selection; it collapses it.
  - Ctrl/Alt+Arrow word and line jumps become single steps.
  - Home/End are left to the framework.
- **`[[` popup keys:** Up/Down/Enter/Escape are handled only while the popup is visible (`ED/autocomplete/WikilinkAutocomplete.kt:68-93`).
- **Wikilinks cannot be opened from the keyboard.** Activation is a pointer handler only (`ED/SkeinEditor.kt:158-179`).
- **Title field:** Enter does nothing useful (`ImeAction.Default`). Focus does not advance to the body.
- **No shortcuts** for new note, search, close tab, open graph, or backlinks.
- **Initial caret is 0** (`ED/notetab/NoteTabState.kt:205`) combined with the offset-mapping bug (§3.3).
- **Undo:** no visible undo/redo affordance.

---

## 3. Notes today

### 3.1 Create

The only discoverable path is the command bar: tap it, type `/`, tap "/new note [title] — create a note and open it pinned", type a title, press Enter.

- Tapping a palette row only **fills** the field (`SH/nav/CommandBarHost.kt:59-63`). Enter is still required (`SH/nav/CommandBarState.kt:105-110`).
- `newNoteCommand` creates `NOTE` with body `""` and title `arg.trim().ifBlank { "Untitled" }`, then `openPinned`s it (`SH/nav/BuiltinCommands.kt:61-67`).
- Afterwards:
  - Focus stays in the command bar with the keyboard up.
  - The new note's editor is not focused.
  - The body's tap target is one line tall, because the text field wraps its content (`ED/notetab/NoteTab.kt:223` passes `fillMaxWidth()`).
  - The id chip "— id 0192abcd… ▸" is the first line of content.
- A bare `/new note` produces "Untitled" every time, so duplicate titles accumulate. That matters because wikilinks resolve by title (`CM/Vault.kt:317-318`).
- **Hidden create paths:**
  - Tapping an unresolved `[[wikilink]]` silently creates an empty note (`ED/notetab/NoteTabState.kt:299-309`).
  - The `[[` popup's "Create" row creates one (`ED/notetab/NoteTabState.kt:280-282`).
  - Importing a file from chat 📎 creates notes (§4.1).
- **Not wired:** the timeline's "📄 New note" FAB and header button (`TL/TimelineScreen.kt:279-336`). `APP/MainActivity.kt:705-711, 856` never passes `onNewNote`.

### 3.2 Open

| Surface | Width | Gesture | Result |
|---|---|---|---|
| `TimelineRail` glyph cell | Single-pane landing (both Fold displays) | Tap | `openPreview(TabKind.NOTE)` (`SH/SkeinApp.kt:226-228`). **No titles shown** |
| `TimelineScreen` row | ≥ 840 dp only | Tap: preview. Long-press: pinned (hidden gesture) | `SH/SkeinApp.kt:226-231` |
| Search result row / Enter on query | All | Tap / Enter | Preview, NOTE kind (`SH/nav/CommandBarState.kt:111-117` → `SH/SkeinApp.kt:270`) |
| Rendered wikilink | In a note | Tap | Opens the existing note or **creates** one, as a preview (`ED/notetab/NoteTabState.kt:299-309`; `SH/SkeinApp.kt:410-412`) |
| Backlink row | In a note | Tap | Preview (`ED/notetab/NoteTabState.kt:114, 285-290`) |
| Graph node | Overlay | Tap: preview. Long-press: pinned. **Graph dismissed** | `APP/MainActivity.kt:734-747` |
| Chat citation / context row | Chat | Tap | Preview, NOTE kind (`CH/ChatViewModel.kt:269`, `CH/ContextPanel.kt:61-65`) |

Every path mints `TabKind.NOTE`, whatever `DocumentKind` the document has. Consequences:

- **Chats** open in `NoteTab` as their materialized transcript (`CM/Vault.kt:333-335`). The body is editable, and the next `appendMessage` re-materializes it, so edits are silently overwritten. The tab title is "Chat" with the 📄 glyph. The composer is absent and "Backlinks 0" sits at the bottom. **There is no way to resume a past chat from Knowledge.** The only chat route is `/chat`, which always creates a new one (`SH/nav/BuiltinCommands.kt:22-36`).
- **Attachments** (the PDF originals `ImportServiceImpl` stores, `CV/transfer/ImportServiceImpl.kt:144-147`) have `bodyMd = null` and frontmatter `{id, mime, size}` (`CV/repository/VaultRepositoryImpl.kt:558-601`). They open as an empty editor plus id chip. Typing writes `body_md` onto the attachment row, and `updateBody` rewrites its `content_hash` to the hash of the typed text (`CV/repository/VaultRepositoryImpl.kt:215-216`).

**Preview replacement destroys the origin.** `TabsState.openPreview` replaces the single preview tab in place (`SH/tabs/TabsState.kt:54-70`). Suppose you open note A from the timeline (a preview), then follow a wikilink, a backlink or a graph node to B. B replaces A, and there is no back stack. A comes back only by finding it again.

### 3.3 Edit (autosave, frontmatter, the caret-at-zero bug)

- **Autosave:**
  - The editor buffer is `Frontmatter.render(frontmatter, body)` (`ED/notetab/NoteTabState.kt:203-210`).
  - Edits debounce for 500 ms (`ED/EditorState.kt:140-146`). The save then splits the buffer back and calls `updateFrontmatter` (when non-empty) plus `updateBody` (`ED/notetab/NoteTabState.kt:226-237`).
  - The first real edit pins the preview tab (`ED/notetab/NoteTabState.kt:245-252`).
  - **Nothing shows save state**, and an autosave error is silent (§2.3).
- **P0, high confidence: frontmatter corruption on the first keystroke.**
  - Every vault document carries frontmatter with at least `id` (`CV/repository/VaultRepositoryImpl.kt:141-143`).
  - With the chip collapsed, `transform()` hides the whole `---…---` block (`ED/LivePreviewTransformer.kt:94-97`).
  - Its `transformedToOriginal` short-circuits `if (offset <= 0) return 0` (`ED/LivePreviewTransformer.kt:135-136`). Transformed offset 0, the first visible character, therefore maps to raw 0, *before* `---`, instead of `offsets[0]` (the body start).
  - The editor is seeded with selection 0 (`ED/notetab/NoteTabState.kt:205`).
  - Four cases put the caret at raw 0:
    - For an **empty-body note** (every `/new note`, every wikilink-created note), the transformed text is empty. Any tap therefore resolves to transformed 0, then raw 0.
    - Tapping the very start of the first visible line in any note.
    - Up-arrow on the first line (`ED/SkeinEditor.kt:217`, `targetLine < 0 -> 0`).
    - Left-arrow at the first character (`ED/SkeinEditor.kt:301-311`).
  - Typing then produces `"X---\nid: …\n---\n…"`.
  - `ProtectedIdGuard` only fires when a well-formed block exists in both texts, so it does not catch this (`ED/frontmatter/ProtectedIdGuard.kt:47-48, 55-59`).
  - `FrontmatterBlock.endLineIndex` returns -1, so the chip disappears and the raw `id:` lines show up in the body.
  - On save, `Frontmatter.parse` finds no header, so the whole buffer, including the old `id:` block, is written as `body_md` (`ED/notetab/NoteTabState.kt:227-236`).
  - On reopen, `render` prepends the real frontmatter again, which leaves two `id` blocks.
  - `OffsetMappingTest` only asserts `originalToTransformed(transformedToOriginal(t)) == t`, which holds even with this bug (`feature/editor/src/test/kotlin/app/skein/feature/editor/OffsetMappingTest.kt:120-156`).
  - `NoteTabInstrumentedTest` types "hi" into a note but asserts only the pin count (`feature/editor/src/androidTest/kotlin/app/skein/feature/editor/notetab/NoteTabInstrumentedTest.kt:81-84`).
  - **Device check (1 minute):** `/new note Test`, tap the editor, type a letter.
- **Pending edits are not flushed on dispose.**
  - Autosave runs in `NoteTab`'s `rememberCoroutineScope` (`ED/notetab/NoteTab.kt:112-123`).
  - `DisposableEffect` only unregisters the flush handle (`:125-128`).
  - `FlushRegistry.flushAll` is never called (`APP/MainActivity.kt:359-366`).
  - Leaving composition cancels the debounce collector and any in-flight save. That happens when you:
    - close the tab
    - switch from a note tab to a chat tab (a different `when` branch in `SH/SkeinApp.kt:405-420`)
    - hit a vault lock (`VaultGate` swaps `UnlockedShell` out, `APP/MainActivity.kt:914-915`)
    - trigger Activity recreation: fold/unfold or rotate, since the manifest declares no `configChanges` (`app/src/main/AndroidManifest.xml:81-88`).
  - The loss window is up to the 500 ms debounce plus the write in flight.
- **Same slot, different note.** Note→note tab switches reuse the same `NoteTab` composition slot (no `key(tab.id)`), so the old `NoteTabState`'s collectors keep running in the shared scope until the slot leaves composition (`ED/notetab/NoteTab.kt:112-123`). That leaks work, but it does *not* lose edits on note→note switches. The backlinks drawer's `rememberSaveable` expanded flag is also position-keyed, so it carries over to the next note (`ED/backlinks/BacklinksDrawer.kt:58`).
- **Stale document.** `NoteTabState` loads once with `getDocument` and never observes (`ED/notetab/NoteTabState.kt:193-213`). If the same note is open in two editors (EXPANDED split: primary plus secondary), the last writer overwrites the other.

### 3.4 Rename

- **What "title" means.** The title is the `documents.title` column. Nothing derives it at edit time:
  - Not from the first `# heading`: a body H1 is independent, and at 1.8× of 16 sp it renders far larger than the title field (titleMedium 16 sp, `SH/theme/SkeinTypography.kt:54`; `core/markdown/src/main/kotlin/app/skein/core/markdown/render/MarkdownStyle.kt:30-44`).
  - Not from frontmatter `title:` (imported notes carry one, `CV/transfer/ImportServiceImpl.kt:302-320`; it is never updated on rename).
  - Not from a filename. Filenames exist only at export (`SafeFileName.sanitize(document.title)`, `CV/export/ExportServiceImpl.kt:105`) and in the Save-as suggestion (`ED/notetab/NoteTabState.kt:160-161`).
  - Only import derives a title: frontmatter `title`, then first heading, then filename without extension (`CV/transfer/ImportServiceImpl.kt:322-330`). PDFs use metadata title, then filename (`:150`). Code files use the filename as-is (`:196`).
- **The rename UI** is the header `TextField` (`ED/notetab/NoteTab.kt:255-261`). Every keystroke launches an un-debounced `updateBody(docId, newTitle, body)` (`ED/notetab/NoteTabState.kt:122-132`), and each one bumps `updated_at`, captures a revision if the content hash moves, and enqueues ingest.
  - No label, no placeholder, no "rename" affordance: it reads as a generic form field.
  - The launch is not wrapped in try/catch, so a failed write crashes (see "Note missing" in §2.3).
- **Tab labels never update.** `Tab.title` is immutable and `TabsState` has no rename (`SH/tabs/Tab.kt:47-55`, `SH/tabs/TabsState.kt`). The strip or "Recent ▾" keeps the old name until the tab is reopened.
- **Links break on rename.** Links resolve by title (`ED/notetab/NoteTabState.kt:301`, `CV/repository/VaultSql.kt:82-84`) and nothing rewrites them. Once the linking note re-indexes, a renamed target loses its backlinks. Tapping the old `[[Old title]]` then silently creates a new empty "Old title" note.
- **Blank titles are allowed.** They display as "Untitled" in lists (`TL/TimelineRow.kt:59`, `ED/backlinks/BacklinksDrawer.kt:129`) but not in tabs.

### 3.5 Search (results side)

- **Input:** `CommandBarState` (`SH/nav/CommandBarState.kt:60-96`).
  - Search runs 150 ms after the last keystroke.
  - Title hits come first: a **prefix-only** `LIKE 'q%'`, newest first, max 20 (`CV/repository/VaultSql.kt:90-92`). Searching "plan" does not title-match "Project plan".
  - Body hits follow, from FTS5 over `chunks_fts` (`CV/repository/VaultSql.kt:19-27, 94…`), which only covers documents the ingest pipeline has indexed. A just-written note may not be body-searchable yet.
  - All kinds are included. Every `/chat` is titled "Chat" (`SH/nav/BuiltinCommands.kt:33`), so results fill with identical "💬 Chat" rows.
- **Results UI** (`SH/nav/SearchResults.kt:45-78`): a kind glyph plus a single-line title.
  - `DocumentHit.snippet` is available but dropped (`SH/nav/CommandBarState.kt:89-94`), so a body hit has no "why it matched".
  - No count, no kind filter, no "No results" state, no loading indicator.
  - The list is composed **inline** in the shell Column above the pane host with no max height (`SH/nav/CommandBarHost.kt:65-73`, `SH/SkeinApp.kt:303-311`). A long list pushes the whole workspace down.
  - Results persist until the query text is deleted. There is no clear (×) button and focus loss does not dismiss them.
  - Enter opens the top hit without showing which one (`SH/nav/CommandBarState.kt:111-113`).
  - Every open is a NOTE-kind preview (§3.2).
- **Missing:** no Knowledge-scoped search, no recent searches, no search inside a note (find-in-page).

### 3.6 Delete

**None.** No UI anywhere calls `deleteDocument` (`CM/Vault.kt:306`; implementation `CV/repository/VaultRepositoryImpl.kt:266-280`).

- No overflow menu or ⋮ on notes.
- No swipe or long-press action on timeline rows. Long-press is "pin" (`APP/MainActivity.kt:708`).
- The system-picker `DocumentsProvider` advertises no `FLAG_SUPPORTS_DELETE` (`CV/provider/ProviderCursors.kt:10-14`).

Junk notes created by accidental wikilink taps, duplicate "Untitled" notes and imported files cannot be removed. What deletion should mean for edges, chunks, revisions and citations belongs to skein-xtov.10.

### 3.7 Navigate between notes and return to the previous workspace

- **Between notes:** wikilinks, backlinks, the graph and search. All open as previews that replace the origin preview (§3.2). There is no Back.
- **Tabs:**
  - At MEDIUM and above: `TabStrip`, with ×, a long-press menu, and double-tap to pin (`SH/tabs/TabStrip.kt:121-182`).
  - At COMPACT: `RecentDropdown`, which can only **switch** tabs (`SH/tabs/RecentDropdown.kt:41-80`). No close, so the landing list is unreachable once any tab is open.
  - No `BackHandler` exists in the codebase (grep for `BackHandler`, `OnBackPressed` and `PredictiveBack` returns nothing). System Back leaves the app, which with lock-on-background can lock the vault.
  - Drawer "Timeline" only changes `navState.destination`, and the active tab keeps rendering (`SH/SkeinApp.kt:319-345`).
- **State loss:**
  - Tabs survive Activity recreation (`rememberSaveable`, `SH/tabs/TabsState.kt:173-180`).
  - Tabs do **not** survive a vault lock: `SkeinApp` leaves composition when `VaultGate` switches branch (`APP/MainActivity.kt:914-965`).
  - The graph overlay (`APP/MainActivity.kt:386`, plain `remember`), editor caret and scroll, and the frontmatter expanded flag are lost on any recreation, including fold/unfold.

---

## 4. Files and attachments today

### 4.1 Import

The **only** file-import UI is the chat composer's 📎 (`CH/ChatBottomBar.kt:196-201`) → SAF `OpenDocument("*/*")` → `ChatViewModel.attach` (`CH/ChatViewModel.kt:286-298`). It routes by MIME type:

| MIME type | What gets created |
|---|---|
| `application/pdf` | ATTACHMENT (bytes, titled with the display name) + extracted-text NOTE (titled PDF-metadata-title or filename-minus-extension, frontmatter `source: <attachmentId>`) (`CV/transfer/ImportServiceImpl.kt:138-171`) |
| `image/*` | **`UnsupportedOperationException`** (`CV/transfer/ImportServiceImpl.kt:176-184`). The launch in `CH/ChatBottomBar.kt:121-133` has no try/catch and `rememberCoroutineScope` has no handler, so this should crash the app (P0, verify on device) |
| everything else | Prose becomes a NOTE (title from frontmatter, heading or filename). Recognised code becomes a NOTE with a fenced body titled with the filename (`CV/transfer/ImportServiceImpl.kt:123-136, 190-300`) |

Problems:

- **Wrong link.** It returns and inserts `[[displayName]]` (`CH/ChatViewModel.kt:297`).
  - For PDFs, the display name matches the **ATTACHMENT** row's title, so the link opens the empty-editor attachment, not the readable note.
  - For Markdown/text, the note's title is derived differently, so the link is unresolved. Tapping it in a note creates an empty note.
- **No feedback.** There is no progress indicator, no success message, and no notice that the file became a permanent vault document.
- **Main-thread work.** `readBytes()` and PDF extraction run on the composition's main-thread scope. Neither `ImportServiceImpl` nor the chat path switches dispatcher (grep for `withContext`/`Dispatchers` in both is empty). Large PDFs risk jank or ANR.
- **Nowhere else.** No import entry point exists in Knowledge, the command bar (`/import model` is models-only, `APP/MainActivity.kt:605-626`) or the timeline, and there is no share-target intent filter (`app/src/main/AndroidManifest.xml:81-88`), although spec §3.1 lists "Share targets: text, image, file, PDF".

### 4.2 Open and preview

- **No attachment viewer exists.** `TabKind.ATTACHMENT` has no real content. `SH/SkeinApp.kt:419` falls back to `destinationContent`. No production code opens that kind anyway (`APP/vault/TabsStateTabController.kt:28` maps it, but `CH/` never emits it). `openAttachment` has no UI caller.
- Opening an attachment row from the timeline, search, graph or citations therefore shows an empty `NoteTab` (§3.2).
- The attachment bytes are reachable only from **other apps** through the system file picker's Skein root (`CV/provider/VaultDocumentsProvider.kt`, read-only for attachments, `CV/provider/ProviderCursors.kt:10-14`).

### 4.3 Delete

None, same as notes (§3.6). No Files list exists. The only file-shaped view is the timeline's "Files" kind chip (`TL/TimelineFormatting.kt:190-196`), which is visible only at ≥ 840 dp.

---

## 5. Backlinks and wikilinks

### 5.1 Discoverability

- Wikilinks are only discoverable by typing `[[` (popup, `ED/SkeinEditor.kt:348-392`).
- No toolbar, and no "Link to…" action.
- No hover or long-press preview of a link target.
- Links render with underline and `wikilinkColor` (`core/markdown/src/main/kotlin/app/skein/core/markdown/render/MarkdownStyle.kt:57`). Broken links are never dimmed, because `knownWikilinkTitles` is never set (§1).
- The backlinks drawer is a bar labelled "Backlinks" with a count badge at the bottom of every note (`ED/backlinks/BacklinksDrawer.kt:63-75`). It has no chevron or expand affordance.

### 5.2 Behaviour

- **Tap routing:**
  - A down on a rendered link is consumed on the Initial pass (`ED/SkeinEditor.kt:158-179`). The caret cannot be placed inside a rendered link by tapping.
  - Tap detection probably includes the offset just past a link's end, since `getStringAnnotations(start = end, end = end)`. If so, a line holding only `[[Link]]` cannot be edited by touch at all, only by arrowing into it.
  - The gesture waits for up on the Initial pass without a slop check. A drag that starts on a link may open it (verify on device).
- **Open or create:** `findByTitle` (case-insensitive exact, newest first) returns the existing document, otherwise `createDocument(NOTE, title, "")` runs **silently** (`ED/notetab/NoteTabState.kt:299-309`). A typo or an uncommitted idea becomes an undeletable empty note.
- **Autocomplete** (`ED/autocomplete/WikilinkAutocompleteState.kt:128-139`):
  - Always appends "Create \"query\"", even when a title matches exactly. Choosing it creates a duplicate-titled note (`ED/notetab/NoteTabState.kt:280-282` does not check `findByTitle`).
  - Suggestions include chats ("Chat", "Chat", …) and exclude only the current note's own title (`ED/notetab/NoteTabState.kt:264-269`).
  - Rows are text-width clickables about 36 dp tall (`ED/autocomplete/WikilinkAutocomplete.kt:131-139`).
  - The popup is anchored at the caret rect with no scroll compensation and does not avoid the IME.
- **Chat composer `[[`:** `MainActivity` does not pass `onCreateWikilink` to `ChatScreen` (`APP/MainActivity.kt:589-600`; default `{}` at `CH/ChatScreen.kt:56`). The composer's "Create \"x\"" row inserts `[[x]]` and **creates nothing**, so it is a dead control.
- **Backlinks:**
  - The query takes WIKILINK edges pointing at the doc id or at `title:<lowercased>` (`ED/backlinks/BacklinksState.kt:169-185`).
  - It re-queries on any document write anywhere (a timeline tick) and on any wikilink re-index (`:160-167`).
  - Excerpt: a window of about ±60 characters around the first `[[title` match.
  - Rows open as previews.
  - When expanded, the drawer is an **unbounded, unscrollable Column** (`ED/backlinks/BacklinksDrawer.kt:76-91`) under an editor weighted `1f` (`ED/notetab/NoteTab.kt:216-235`). With many backlinks, especially on the outer display with the IME up, it squeezes the editor to zero height and clips rows that can then never be reached.
  - The header row has no role or state semantics (no "collapsed/expanded").

### 5.3 Overlap with other surfaces

- The drawer is always composed, including when the "note" is a chat transcript or an attachment.
- In EXPANDED split view, a note in the primary pane puts its drawer beside a chat composer in the secondary pane (`SH/layout/AdaptivePaneHost.kt:114-121`).

### 5.4 The filed "Backlinks overlap" bug (skein-gg11.23)

Handoff text (`docs/Handoffs/skein-v1-autonomous-completion.md:95`): "the owner sees a note's Backlinks strip over the chat composer; in the runner's dumps (unfolded, split) the strip sits beside the composer, and `TabHost` composes only the active tab — needs the owner's posture/split/note-tab details."

**Most likely explanation from code:**

1. On the Fold (single-pane), the owner opens an existing chat from the timeline rail or search.
2. That opens the chat document as `TabKind.NOTE` (`SH/SkeinApp.kt:226-231, 270`).
3. So `NoteTab` renders the chat's materialized transcript (`CM/Vault.kt:333-335`) as editable text, with `BacklinksDrawer` at the bottom (`ED/notetab/NoteTab.kt:235`) exactly where the chat composer would be.
4. In "Recent ▾" there are then two tabs titled "Chat": the original 💬 CHAT tab from `/chat` and the new 📄 preview. It is easy to believe you are looking at "the chat" with a Backlinks strip over its composer.

**Secondary contributor:** the unbounded expanded drawer (§5.2).

**Fix direction:** route `DocumentKind.CHAT` to a CHAT tab and `ATTACHMENT` to a viewer at the three `onTimelineEntry*/onOverlay*` builders and `tabContent`'s NOTE branch in `SH/SkeinApp.kt`, instead of hard-coding NOTE. Also exclude the backlinks drawer for non-note kinds.

---

## 6. Graph today

- **Entry points:**
  - ✦ in the note header (`ED/notetab/NoteTab.kt:273-278`) sets `graphDocId` (`APP/MainActivity.kt:698`), and the overlay renders (`APP/MainActivity.kt:729-750`).
  - Drawer "Graph" is a placeholder (dead). IconRail ✦ is `{}` (dead). There is no `/graph` command; the `CommandRegistry` kdoc lists it as future (`SH/nav/CommandRegistry.kt:9-17`).
  - There is **no graph without a note**: the graph is always local to one document (spec §8.6 defers the global view to v2), which is why a top-level "Graph" destination has nothing to show.
- **Presentation:**
  - A full-window overlay above the command bar and panes, **at every width** (`GR/GraphScreen.kt:53-76`). There is no pane integration on EXPANDED, and no sheet or route on COMPACT.
  - The canvas scales the world to the **shorter** side (`GR/GraphTransform.kt:15-19`), so a wide inner display wastes most of its width.
  - It re-wraps `SkeinTheme()` with the default `SYSTEM` mode (`GR/GraphScreen.kt:45`, `SH/theme/SkeinTheme.kt:20-21`), so it **ignores Settings › Appearance** (a forced dark or light mode flips back to the system theme inside the graph).
- **Selection and gestures:**
  - Tap a document node: the `onOpenPreview` call resolves the title, opens a preview and **dismisses the graph** (`APP/MainActivity.kt:734-740`). Long-press does the same, pinned (`:741-747`).
  - No selected-node state, no detail sheet, no "re-center here", no multi-hop exploration without leaving.
  - Taps on entity, tag and unresolved-title nodes do nothing, with no feedback (`GR/GraphView.kt:268-270`).
  - Nodes for deleted documents are labelled with their raw UUID (`GR/GraphModels.kt:65-70`; `GR/GraphState.kt:136`). Tapping one opens "Note not found".
  - Dragging a node moves it (`GR/GraphView.kt:338-398`). Pinch and pan work through `transformable` (`:110-114`). There is no zoom reset, no fit-to-screen, no double-tap zoom.
  - All kinds open as NOTE tabs (§3.2).
- **Legend:** always visible, bottom-start, not collapsible (`GR/GraphScreen.kt:61`). It can cover nodes. "Attachment" here is "Files" in the timeline chips. AI output is coloured with `colorScheme.error` (`GR/GraphView.kt:425`), which reads as an error. Document kinds differ by **colour only**.
- **Labels** (`GR/GraphView.kt:219-221, 526-538`):
  - Drawn only when the graph has 40 nodes or fewer (the cap is 80, `GR/GraphState.kt:204`). Denser graphs have **no labels at all**.
  - Measured without width constraints, so long titles overlap each other and run off-canvas. They scale with font scale.
  - Unresolved-title labels show the *lowercased* title; entity labels read "Entity #123".
- **Narrow vs wide:** identical overlay. On COMPACT the ✕ and the legend take up corners of a small canvas. The top-end ✕ is inset through `safeDrawing` (`SH/layout/EdgeToEdge.kt:37-44`).
- **Back:** no `BackHandler`. System Back leaves the app instead of closing the graph.
- **Fold transition:** `graphDocId` is a plain `remember` (`APP/MainActivity.kt:386`) and the Activity recreates on fold (no `configChanges`). **The graph closes on fold or unfold**, failing brief Test F.
- **Accessibility:** the `Canvas` exposes **no semantics**. No node list, no `contentDescription`, no custom actions. The ✕ has no label. TalkBack users get nothing but the legend text. There is no semantic alternative (for example a "Connections" list).

---

## 7. User flows (brief §43)

Tap counts assume the single-pane Fold (COMPACT outer), starting from the landing with the vault unlocked.

### Flow 12: Create note

**Steps:**

1. Tap the command bar.
2. Type `/`.
3. Tap "/new note…".
4. Type a title.
5. Press Enter.
6. Tap the editor's single line.

That is **4 taps plus typing**, with nothing visible to suggest it.

| Category | Finding |
|---|---|
| Unnecessary taps | Tapping a palette row doesn't run the command (`SH/nav/CommandBarHost.kt:59-63`). Focus isn't moved to the editor |
| Dead ends | None, but a new user has no visible way in: no FAB (`APP/MainActivity.kt:705-711`), and the empty landing is blank (`TL/TimelineRail.kt`) |
| Duplicate navigation | Wikilink tap / Create row create notes too, silently |
| Confusing states | "— id 0192abcd… ▸" is the first content line. The empty body has no placeholder. The palette hint says "open it pinned" |
| Missing feedback | No "Note created". No save indicator |
| Stale state | None |
| Layout bugs | One-line body tap target (`ED/notetab/NoteTab.kt:223`) |
| Data risk | The first keystroke lands before `---` (§3.3, P0) |
| Terminology | "/command", "pinned", "frontmatter", "Untitled" default |

### Flow 13: Edit note

**Steps:** open the note (§3.2), then tap into the body and type.

| Category | Finding |
|---|---|
| Confusing states | The caret line shows raw markdown and other lines render (intended). Links can't be tapped to edit. Shift/Ctrl+arrows are broken on a hardware keyboard (`ED/SkeinEditor.kt:198-316`) |
| Missing feedback | No saved / saving / error indicator (`ED/EditorState.kt:126-132` unused) |
| Stale state | No `observeDocument`, so two editors on one doc give last-writer-wins |
| Data risk | The caret-at-zero corruption (§3.3). The ≤ 500 ms window lost on tab close, chat switch, lock or fold (§3.3) |
| Layout | With the IME up on the outer display, the chrome takes about 200 dp: command bar 56 + "Recent ▾" 36 + header ≥ 64 + divider + backlinks bar about 40 (`SH/theme/SkeinTokens.kt:18,46`; `ED/notetab/NoteTab.kt:250-281`; `ED/backlinks/BacklinksDrawer.kt:63-75`). An expanded backlinks drawer can crush the editor |
| Performance | Two or three full-document `transform()` passes per keystroke (§11) |

### Flow 14: Rename note

**Steps:** open the note, tap the title field, edit. **2 taps.**

| Category | Finding |
|---|---|
| Hidden controls | The title field doesn't look like a title: filled grey form field, no label, titleMedium 16 sp, smaller than a body H1 |
| Missing feedback | No confirmation. Each keystroke is saved immediately (`ED/notetab/NoteTabState.kt:122-132`) |
| Stale state | The tab label keeps the old title (`SH/tabs/Tab.kt:47-55`). Frontmatter `title:` of imported notes diverges |
| Data / link integrity | Inbound `[[Old]]` links are not rewritten. Backlinks vanish after re-index. Tapping an old link creates an empty "Old" |
| Crash | A title edit when the doc is missing or the vault is closing throws in an unguarded launch |

### Flow 15: Delete note

**No path exists** (§3.6), so every user who needs to delete a note dead-ends. P0 per §47 ("inability to delete … notes").

### Flow 16: Search Knowledge (results side)

**Steps:**

1. Tap the command bar.
2. Type.
3. Wait 150 ms.
4. Tap a row, or press Enter.

**2 taps.**

| Category | Finding |
|---|---|
| Confusing states | Title match is prefix-only. Body hits only exist after ingest. Duplicate "💬 Chat" rows. No snippet (`SH/nav/CommandBarState.kt:89-94`) |
| Missing feedback | No "No results", no spinner, no count |
| Layout | The inline, unbounded list pushes the workspace down (`SH/nav/CommandBarHost.kt:65-73`). The results never auto-dismiss |
| Wrong destination | Chats and attachments open in the note editor (§3.2) |
| Terminology | The placeholder "search or /command" mixes two modes in one field |

### Flow 17: Open document

**Steps (outer display):** tap an **unlabelled glyph** in the rail, or search. **1–2 taps, but identification is guesswork.**

| Category | Finding |
|---|---|
| Dead ends | After the first open, the landing is unreachable (no close on "Recent ▾", no Back, drawer Timeline inert) |
| Duplicate navigation | Timeline rail, search, drawer "Notes" (dead), drawer "Timeline" (inert), the "Recent ▾" dropdown: five ways in, none of them a titled list at < 840 dp |
| Confusing states | Preview (italic) vs pinned semantics. Following a link replaces the origin preview |
| Wrong content | Chats show as editable transcripts. Attachments show as empty editors |
| Stale state | Tabs are lost on lock. The graph closes on fold |

### Flow 18: Attach Knowledge to chat

- **Today's mechanisms:**
  - (a) Implicit: every send retrieves the top 8 from the whole vault (`CH/SendPipeline.kt:214`). The user can't choose, pin or exclude.
  - (b) `[[` autocomplete in the composer inserts link text. Nothing in `core/rag` treats query wikilinks as pinned context (`core/rag/src/main/kotlin/app/skein/core/rag/retrieval/RetrievalServiceImpl.kt:125-135` passes the raw query to lexical/vector/graph recall).
  - (c) 📎 imports a file permanently into the vault (§4.1).
- **No "Ask about this note"** from the note header, and no "attach to chat" from search or the timeline.

| Category | Finding |
|---|---|
| Hidden controls | There is no explicit "attach" concept. The ⚹ context panel only explains afterwards (chat-owned) |
| Dead controls | Composer "Create \"x\"" row creates nothing (`APP/MainActivity.kt:589-600`) |
| Crash | 📎 with an image (§4.1) |
| Missing feedback | No import progress or success. The inserted link is mismatched |
| Terminology | "Attach" means "import into the vault forever" |

### Flow 21: Open graph node

**Steps:**

1. Open a note.
2. Tap ✦.
3. Wait for the spinner.
4. Tap a node.

**3 taps.** Step 4 closes the graph.

| Category | Finding |
|---|---|
| Dead ends | Entity, tag and unresolved nodes: no feedback. Deleted-document nodes open "Note not found" |
| Unnecessary taps | Exploring a second hop means reopening ✦ from the new note (the graph re-centres) |
| Hidden controls | Long-press to pin. Drag. Pinch |
| Confusing states | No labels past 40 nodes. Colour-only kinds. AI output coloured as an error |
| Navigation | Back leaves the app. The node's document replaces the origin preview |
| Fold | The overlay is dismissed by fold/unfold (Activity recreation) |
| Accessibility | Nothing reachable by TalkBack |

---

## 8. Visual hierarchy (brief §44), note tab on the outer display

What attracts attention first, top to bottom:

1. The command bar: 56 dp, `$` prompt, model chip "gemma · not loaded ⏸". The model chip is irrelevant while writing.
2. The "Recent ▾" row.
3. **The title as a filled Material form field.** It is the heaviest block on screen but typographically weak: titleMedium 16 sp versus a body H1 at about 28.8 sp (`core/markdown/src/main/kotlin/app/skein/core/markdown/render/MarkdownStyle.kt:30-44`).
4. Two unlabeled glyph buttons (↗ ✦).
5. The **frontmatter id chip**: implementation metadata as the first content line on every note.
6. The body.
7. The "Backlinks [0]" bar with a `secondaryContainer` badge. The badge is loud even at zero.

Four chrome layers compete before any content appears. Technical detail (id, frontmatter, the model chip) outranks user content, against brief principles 5 and 7.

**Graph:**

- The legend and ✕ are the only non-canvas elements.
- The centre node's 18 dp radius versus 12 dp for others, plus a 3 dp primary ring, is a clear focal point. Labels in `labelSmall` 11 sp compete as soon as there are more than about 10 nodes.
- **Glyphs are inconsistent across surfaces.** AI output is `✧` in the timeline (`TL/TimelineFormatting.kt:177`), `🤖` in search (`SH/nav/SearchResults.kt:35`) and red in the graph. Attachments are "Files" in chips, "Attachment" in the legend, 📎 as the glyph.

---

## 9. Responsive text and content (brief §45)

| Content | Surface | Behaviour | Where |
|---|---|---|---|
| Long note title | Title field | Single line, scrolls horizontally. OK | `ED/notetab/NoteTab.kt:255-261` |
| | Tab strip chip | `BasicText` with no `maxLines`/ellipsis: one chip can exceed screen width | `SH/tabs/TabStrip.kt:151-160` |
| | "Recent ▾" header | `Text` with no `maxLines` inside a fixed 36 dp row: wraps and clips | `SH/tabs/RecentDropdown.kt:42-60` |
| | Timeline row / search row / backlink row | `maxLines = 1` + ellipsis. OK | `TL/TimelineRow.kt:58-63`; `SH/nav/SearchResults.kt:66-73`; `ED/backlinks/BacklinksDrawer.kt:128-133` |
| | Graph label | Unconstrained `measure`: overlaps and runs off-canvas | `GR/GraphView.kt:532` |
| | `[[` popup row | Fixed 240 dp, unlimited lines | `ED/autocomplete/WikilinkAutocomplete.kt:119, 131-139` |
| Long filename | Chat 📎 | Inserted verbatim as `[[very-long-name.pdf]]` into the composer. The attachment title is the full name | `CH/ChatViewModel.kt:297` |
| | Save as | Suggested filename = title | `ED/share/SaveAsIntents.kt:45` |
| Deeply nested lists | Editor | A bullet or ordered marker is recognised only with ≤ 3 leading spaces (`ED/LivePreviewTransformer.kt:390-419`). Deeper items fall to the plain-paragraph path. Markers stay raw, with no hanging indent, so wrapped lines start at column 0. Tab indentation is not recognised | |
| Tables | Editor | No table support. Raw pipes, readable only because all type is monospace (`SH/theme/SkeinTypography.kt:31-62`). Wide tables soft-wrap and break alignment | — |
| Code blocks | Editor | Monospace plus `codeBackground` inside fences (`ED/LivePreviewTransformer.kt:175-179`). Fence lines are hidden when inactive. Long lines **soft-wrap** (no horizontal scroll) | |
| Large font scale | Header | `TextField` grows and fixed 36 dp tab rows clip (`SH/theme/SkeinTokens.kt:18`). The backlinks header grows. Graph labels grow and overlap more | |

---

## 10. Accessibility (brief §40)

| Area | Finding | Where |
|---|---|---|
| Screen-reader labels | ↗ share, ✦ graph and graph ✕ are glyph `Text` in `IconButton`s with no `contentDescription` (read as symbol names) | `ED/notetab/NoteTab.kt:273-278, 301-312`; `GR/GraphScreen.kt:62-74` |
| | The title field has no label ("Title") | `ED/notetab/NoteTab.kt:255-261` |
| | Timeline rail cells read "Note" or "Chat", with no title. The list is unusable with TalkBack | `TL/TimelineRail.kt:48-55` |
| | The frontmatter chip has a description. OK | `ED/frontmatter/FrontmatterChip.kt:55` |
| State | The backlinks header has no role or expanded state | `ED/backlinks/BacklinksDrawer.kt:63-72` |
| Canvas | The graph has no semantics and no alternative list | `GR/GraphView.kt:165-223` |
| Links | Wikilinks can't be activated by TalkBack or the keyboard (pointer-only handler, no `LinkAnnotation` or custom action) | `ED/SkeinEditor.kt:158-179` |
| Touch targets | Frontmatter chip about 20 dp tall (2 dp vertical padding) | `ED/frontmatter/FrontmatterChip.kt:52-53` |
| | Backlinks header about 40 dp | `ED/backlinks/BacklinksDrawer.kt:68` |
| | `[[` rows about 36 dp, text-width | `ED/autocomplete/WikilinkAutocomplete.kt:136-138` |
| | Tab × about 12 dp (shell) | `SH/tabs/TabStrip.kt:161-168` |
| | ✦ and ↗ are `size(40.dp)`; M3 `IconButton` still enforces a 48 dp interactive size. OK | |
| Keyboard | Shift and Ctrl arrows broken. No shortcuts. Title Enter doesn't advance | §2.4 |
| Colour | Graph kinds by colour only. AI output uses the error colour | `GR/GraphView.kt:417-446` |
| Destructive confirmation | N/A: there are no destructive actions (none exist) | |
| Status | Autosave and import status never announced | §2.3 |
| Motion | The graph simulation animates on open and after drags, with no reduced-motion check. It stops when idle (`GR/GraphView.kt:142-154`) | |

---

## 11. UI performance (brief §46)

- **Editor, per keystroke:**
  - `transform()` over the whole document runs in the `VisualTransformation` (`ED/SkeinEditor.kt:123-131`, via the `BasicTextField` filter).
  - It runs **again** for `transformedCursorOffset` (`:352-357`).
  - `buildLines` runs twice more for the frontmatter end and chip label (`:134-143`).
  - Every hardware arrow press runs another full `transform()` (`:208-210, 290-297`).
  - The whole note is one `BasicTextField` laid out in full, with no virtualisation.
  - Fine for short notes. Linear per keystroke for long ones: expect lag on multi-thousand-line notes.
- **Autosave fan-out every 500 ms pause:**
  - `updateFrontmatter` + `updateBody` (`ED/notetab/NoteTabState.kt:226-237`).
  - Each can `captureRevision` (`CV/repository/VaultRepositoryImpl.kt:226-229, 259-261`).
  - The DB trigger enqueues ingest (`:230-232`).
  - Downstream: re-chunk, re-embed, edge rewrite, then `EdgesReplaced`. That makes every open `BacklinksState` re-query (`ED/backlinks/BacklinksState.kt:160-167`), every open `GraphState` reload (`GR/GraphState.kt:97-107`), and the timeline re-emit.
  - Title edits do all of this **per keystroke**. Battery and CPU cost while typing is the main risk. Revision and ingest policy is storage-owned (skein-xtov.10) but drives UI cost.
- **Graph:**
  - `GraphState.load` runs in a main-dispatcher `rememberCoroutineScope`. `ForceLayout.compute` is 300 iterations of O(N²) with N ≤ 80, about 1.9 M pair evaluations over `HashMap<String, Vec2>`, **on the main thread** (`GR/GraphState.kt:150-156`; `GR/ForceLayout.kt:60, 230-247`). That means jank on open.
  - It is **repeated on every vault `EdgesReplaced`**, for example while ingest runs.
  - The live simulation steps O(N²) per frame at ≤ 60 Hz until idle (`GR/GraphView.kt:142-154`), then stops. That part is good.
  - Each frame re-allocates `screenPositions` and measures up to 40 labels. `rememberTextMeasurer()` has a small default cache (`GR/GraphView.kt:115, 532`), so it likely re-lays out text every frame.
- **Import:** `readBytes()` + PDFBox extraction on the main thread (§4.1).
- **Leak:** old `NoteTabState` instances keep collectors alive across note→note tab switches in one slot (§3.3).

---

## 12. Where Notes, Files and Graph should live (brief §20, §21, §23)

**The evidence:**

1. **Notes and files are the same object.** Both are `documents` rows (`DocumentKind`), searched by the same command bar, mixed in the same timeline, and an imported PDF is literally a NOTE plus an ATTACHMENT pair (`CV/transfer/ImportServiceImpl.kt:138-171`). Splitting "Notes" and "Files" into separate primary destinations would duplicate list, search and delete machinery.
2. **The graph is always local to a document** (`GR/GraphState.kt:118`; spec §8.6 defers a global view to v2). A primary "Graph" destination with no document has nothing to render, which is exactly why the drawer entry is a placeholder today.
3. **The timeline mixes chats with knowledge**, and chats open wrongly from it (§3.2). As a destination it overlaps both "Chat history" and "Knowledge".

**Recommendation:**

- **Primary destinations:** Chat · Knowledge · Models · Settings. Graph is *not* primary until a global graph ships.
- **Knowledge** is a list-detail destination:
  - **List pane:** notes and files, with kind filter chips (Notes · Files · AI outputs; chats excluded), a search field with snippets, "Recent" at top, "New note" and "Import file" actions, row ⋮ with Rename and Delete.
  - **Detail:** the note editor, or an attachment viewer for files.
  - **Supporting pane** on EXPANDED (and MEDIUM landscape if it fits): *Connections*, i.e. backlinks, the local graph and metadata (frontmatter).
  - **COMPACT:** list, then a full-screen detail route with real Back. Connections open as a bottom sheet or full-screen route with a semantic list plus the graph canvas.
- **Graph = the "Connections" view of a document:** the ✦ button opens it in the supporting pane (wide) or as a route (narrow). Tapping a node **selects** it (details plus "Open" and "Center here") instead of navigating away.
- **Timeline:** retire it as a destination. Keep a "Recent" section in Knowledge and in the command palette. Chats get their own history in the Chat destination.
- **Personas:** a chat parameter, not a drawer destination. Out of my scope, but it removes another placeholder.

**In three lines:**

1. Knowledge = one list-detail home for notes and files (kind chips, search with snippets, New and Import, ⋮ Rename/Delete) replacing the Notes drawer item, the glyph rail and the timeline.
2. The graph is each document's "Connections" view: supporting pane on wide, sheet or route on narrow, select-then-open. It is not a primary destination until a global graph exists.
3. Chats leave the knowledge list and route to the Chat destination. Opening any document dispatches on its `DocumentKind`.

---

## 13. Ranked findings (brief §47)

IDs are `K-P{n}-{i}`. "Owner" names the agent or bead best placed to fix it: **K** = Knowledge (this bead's wave 6/8), **SH** = shell/nav/Fold, **CH** = chat, **ST** = storage lifecycle (skein-xtov.10).

### P0

| ID | Finding | Evidence | Fix direction | Owner |
|---|---|---|---|---|
| K-P0-1 | **No delete for notes or files** anywhere in the UI | `CM/Vault.kt:306` has no `feature/`/`app/` caller; `CV/provider/ProviderCursors.kt:10-14` | ⋮ → Delete with confirm in note header and list rows; semantics per xtov.10 | K + ST |
| K-P0-2 | **Typing at transformed offset 0 inserts before the hidden frontmatter.** Corrupts the note on the first keystroke of every new note | `ED/LivePreviewTransformer.kt:135-136`; `ED/notetab/NoteTabState.kt:205`; `ED/SkeinEditor.kt:217, 301-311`; save path `ED/notetab/NoteTabState.kt:227-236` | `transformedToOriginal(0)` must return `offsets[0]`. Seed the selection at body start. Add a test asserting `transformedToOriginal(0) == blockEnd` and a NoteTab typing test that checks the saved body | K |
| K-P0-3 | **Every document opens as a NOTE tab.** Chats become editable transcripts (edits overwritten, no composer, can't resume a chat). Attachments become empty editors (typing rewrites the attachment `content_hash`). Likely cause of skein-gg11.23 | `SH/SkeinApp.kt:227,230,238,241,411`; `CH/ChatViewModel.kt:269`; `CH/ContextPanel.kt:64`; `CM/Vault.kt:333-335`; `CV/repository/VaultRepositoryImpl.kt:215-216` | Carry `DocumentKind` through `onEntryOpen`/`openPreview`/`overlay` and map to `TabKind`. Read-only viewer for attachments | SH + K + CH |
| K-P0-4 | **No titled notes list on either Fold display.** The single-pane landing is a glyph-only rail (≤ 20 emoji, no titles, no filters, no empty state, TalkBack reads "Note") | `SH/SkeinApp.kt:323-327`; `APP/MainActivity.kt:712-717`; `TL/TimelineRail.kt:40-65`; `SH/layout/PaneLayout.kt:55-66` | Knowledge list (titled, filterable) as the single-pane landing | K + SH |
| K-P0-5 | **No way back from a note on COMPACT.** No close on "Recent ▾", no `BackHandler` anywhere, drawer Timeline inert while a tab is active. The graph overlay also ignores Back | `SH/tabs/RecentDropdown.kt:62-79`; `SH/SkeinApp.kt:319-345`; repo-wide grep | Back closes the overlay, then the detail, then returns to the list. Close in "Recent ▾" | SH |
| K-P0-6 | **Dead Knowledge navigation:** drawer "Notes" and "Graph" render "NOTES"/"GRAPH" placeholders. IconRail ✦ is `{}` | `SH/nav/NavDrawer.kt:28-29`; `APP/MainActivity.kt:687`; `SH/layout/IconRail.kt:45,54`; `SH/layout/AdaptivePaneHost.kt:71-73` | Remove now. Replace with the Knowledge destination | SH + K |
| K-P0-7 | **The only file-import UI crashes on images**, and Save-as I/O errors crash | `CV/transfer/ImportServiceImpl.kt:176-184`; `CH/ChatBottomBar.kt:121-133`; `ED/notetab/NoteTab.kt:171-175` | Filter the picker MIME types or catch and report. Wrap the Save-as write | CH + K |
| K-P0-8 | **Composer "Create \"x\"" wikilink row is dead** (inserts text, creates nothing) | `APP/MainActivity.kt:589-600`; `CH/ChatScreen.kt:56` | Pass `onCreateWikilink`, or hide the Create row in chat | CH |
| K-P0-9 | **Pending edits dropped** on tab close, note→chat switch, lock or fold (≤ 500 ms plus in-flight). `flushAll` is never called | `ED/notetab/NoteTab.kt:112-128`; `ED/EditorState.kt:140-146`; `APP/MainActivity.kt:359-366`; `app/src/main/AndroidManifest.xml:81-88` | Flush on dispose, `ON_STOP` and before lock, in a scope that outlives the composable | K + SH |

### P1

| ID | Finding | Evidence |
|---|---|---|
| K-P1-1 | No visible "New note". Only `/new note` in the command bar. The FAB and header button are unwired. No focus hand-off after create | `APP/MainActivity.kt:705-711, 856`; `SH/nav/BuiltinCommands.kt:53-68`; `SH/nav/CommandBarHost.kt:59-63` |
| K-P1-2 | Rename is an un-debounced per-keystroke write. Tab label stale. Frontmatter `title:` diverges. Inbound links not rewritten (backlinks vanish, and old links create empty notes). No label | `ED/notetab/NoteTabState.kt:122-132`; `SH/tabs/Tab.kt:47-55`; `ED/notetab/NoteTabState.kt:299-309` |
| K-P1-3 | Search: prefix-only titles, body hits only after ingest, snippet dropped, no empty state, inline unbounded list, no clear, duplicate "Chat" rows | `CV/repository/VaultSql.kt:90-92`; `SH/nav/CommandBarState.kt:87-95`; `SH/nav/CommandBarHost.kt:65-73` |
| K-P1-4 | Unresolved wikilink tap silently creates a note. No broken-link dimming. Create row duplicates existing titles | `ED/notetab/NoteTabState.kt:280-282, 299-309`; `ED/autocomplete/WikilinkAutocompleteState.kt:132-138`; `ED/notetab/NoteTabState.kt:203-210` |
| K-P1-5 | Following a link, backlink or graph node replaces the origin preview tab. No back stack | `SH/tabs/TabsState.kt:54-70`; `SH/SkeinApp.kt:410-412` |
| K-P1-6 | Expanded backlinks drawer is unbounded and unscrollable and can crush the editor. No state semantics. "0" badge noise | `ED/backlinks/BacklinksDrawer.kt:58-109`; `ED/notetab/NoteTab.kt:216-235` |
| K-P1-7 | Graph is a full-window overlay at all widths. Tap navigates away (no selection). No labels past 40 nodes, unbounded label width. Zero semantics. Closes on fold. Ignores the theme override | `GR/GraphScreen.kt:45-76`; `GR/GraphView.kt:196-221, 526-538`; `APP/MainActivity.kt:386, 729-750` |
| K-P1-8 | Autosave and export status invisible. Errors silent | `ED/EditorState.kt:126-132`; `ED/notetab/NoteTab.kt:165-201` |
| K-P1-9 | Hardware keyboard: Shift and Ctrl arrows broken. Links not keyboard-activatable. No shortcuts | `ED/SkeinEditor.kt:198-316, 158-179` |
| K-P1-10 | "Attach Knowledge" has no explicit model. 📎 imports permanently, with no feedback, a mismatched `[[link]]`, and main-thread I/O | `CH/SendPipeline.kt:214`; `CH/ChatViewModel.kt:286-298`; `CV/transfer/ImportServiceImpl.kt:138-171, 322-330` |
| K-P1-11 | No attachment viewer, no Files list, no file lifecycle. No Knowledge-level import. No share target | `SH/SkeinApp.kt:419`; `app/src/main/AndroidManifest.xml:81-88` |
| K-P1-12 | Frontmatter id chip is the first line of every note (implementation detail up front). The chip's tap target is about 20 dp | `ED/SkeinEditor.kt:138-151`; `ED/frontmatter/FrontmatterChip.kt:49-104` |
| K-P1-13 | Empty states: blank single-pane landing, "Nothing here yet" with no action, "No tabs open — back to timeline", empty-note body with no placeholder, graph with no connections unexplained | §2.3 |
| K-P1-14 | Editor chrome takes about 200 dp above the IME on the outer display. Title typographically weaker than a body H1 | §7 flow 13; §8 |
| K-P1-15 | Performance: 2–3 full-document transforms per keystroke. Autosave fans out to revisions, ingest, backlinks and graph reloads. Graph layout on the main thread, re-run on every `EdgesReplaced` | §11 |
| K-P1-16 | Tabs (the open notes) are discarded on vault lock. Two editors on one doc give last-writer-wins | `APP/MainActivity.kt:914-965`; `ED/notetab/NoteTabState.kt:193-213` |
| K-P1-17 | "Note not found" is a dead end whose live title field crashes on edit | `ED/notetab/NoteTab.kt:209-214`; `ED/notetab/NoteTabState.kt:122-132`; `CV/repository/VaultRepositoryImpl.kt:825-826` |

### P2

| ID | Finding | Evidence |
|---|---|---|
| K-P2-1 | Glyph and terminology drift: ✧ / 🤖 / red for AI output; Files / Attachment / 📎; "pinned", "preview", "frontmatter", "Entity #123", "/command" | `TL/TimelineFormatting.kt:172-196`; `SH/nav/SearchResults.kt:29-36`; `GR/GraphLegend.kt:40-44`; `GR/GraphModels.kt:65-70` |
| K-P2-2 | IME autocorrect, suggestions and capitalisation are off for the note body (threat-model trade-off). Decide explicitly, perhaps as an opt-in | `SH/input/SecureBasicTextField.kt` |
| K-P2-3 | Nested lists with > 3 spaces, tables and long code lines get no special rendering. No hanging indent | `ED/LivePreviewTransformer.kt:390-419` |
| K-P2-4 | Graph legend always on. No zoom reset or fit. AI output coloured as an error. Kinds distinguished by colour only | `GR/GraphScreen.kt:61`; `GR/GraphView.kt:417-446` |
| K-P2-5 | Long titles unclipped in the tab strip and "Recent ▾" | `SH/tabs/TabStrip.kt:151-160`; `SH/tabs/RecentDropdown.kt:51-58` |
| K-P2-6 | Old `NoteTabState`s leak collectors across note→note switches. The backlinks expanded flag carries over | `ED/notetab/NoteTab.kt:112-123`; `ED/backlinks/BacklinksDrawer.kt:58` |
| K-P2-7 | Wikilink tap consumes the down, so the caret can't be placed in a rendered link. A line that is only a link may be uneditable by touch | `ED/SkeinEditor.kt:158-179` |
| K-P2-8 | `TimelineDestination` is dead code with dead row taps (`onEntryClick = {}`) | `APP/MainActivity.kt:853-857` |

---

## 14. Could not determine, or needs device verification

1. **K-P0-2 (caret at 0).** High confidence from the code and the `BasicTextField` tap-mapping contract, but not reproduced on a device. The 1-minute repro is in §3.3.
2. **K-P0-7 image-attach crash.** Depends on the `rememberCoroutineScope` context having no `CoroutineExceptionHandler`, which is the Compose default. Verify by attaching a JPEG in a chat.
3. **The Fold inner display's exact width class** in each orientation and display-size setting. The brief says MEDIUM, which makes it single-pane. If a posture reports ≥ 840 dp, the titled timeline appears there. Measure with `currentWindowAdaptiveInfoV2()` on the device.
4. **skein-gg11.23.** §5.4 is a code-level hypothesis. Confirm by opening an existing chat from the rail or search on the Fold.
5. **Whether a drag starting on a rendered wikilink opens it**, and whether a tap just after a link at line end opens it. Both depend on pointer-slop and annotation-range edge behaviour (§5.2).
6. **Real frame cost** of `ForceLayout.compute` at N = 80 and of per-keystroke `transform()` on long notes. Worth a Macrobenchmark or systrace pass before optimising.
7. **What deletion must cascade to** (revisions, citations, `title:` sentinels, context attachments). Deferred to skein-xtov.10 by design.
