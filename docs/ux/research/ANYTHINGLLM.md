# AnythingLLM: reference study for Skein

**Bead:** `skein-xtov.6` (epic `skein-xtov`) · **Authority:** `docs/research/SKEIN_UI_UX_OVERHAUL_PROMPT.md` §§1–3, 13, 20, 27, 34–35, 52–53 · **Spec context:** `docs/superpowers/specs/2026-09-19-skein-design.md` §5, §7, §8
**Companions:** [`OPEN_WEBUI.md`](OPEN_WEBUI.md), [`LOBECHAT.md`](LOBECHAT.md). All three feed the design in [Proposed Skein context presentation](#proposed-skein-context-presentation) below.
**Status:** Research record. Advisory. No code was copied into Skein. Skein references are to repository paths at `009cbb6`.

---

## Project

| Field | Value |
| --- | --- |
| Repo URL | <https://github.com/Mintplex-Labs/anything-llm> |
| Commit inspected | `128a01575a50f0284aeca75a93399b6fb1db0328` (default branch `master`, committed 2026-09-25T17:28:46-07:00, *"Read Anthropic replies from text blocks, not the first block (#6496)"*) |
| Latest release tag | `v1.16.2` → `ad97bc8dfcb6919f34f7d6d0c722efdda64d66d9`, published 2026-09-22. The inspected HEAD is newer than the tag. |
| Inspection date | 2026-09-26 |
| Local clone | `research/clones/anything-llm/` (`git clone --depth 1`), git-ignored by `.gitignore:121` (`research/clones/`). It is never committed. |
| Stack | `frontend/` React + Vite + Tailwind; `server/` Node/Express + Prisma (SQLite by default); `collector/` document parsing. It ships as Docker, bare metal, or an Electron desktop app for Mac, Windows and Linux. |

Everything that matters for Skein comes from one idea. A **workspace** contains an LLM choice, a system prompt, a chat mode, its own set of embedded documents (its own vector namespace) and a list of **threads** (conversations). A conversation's knowledge is decided at three levels, and each level has its own lifetime. That is the question Skein is trying to answer.

Files read in full or in the relevant part (all permalinks are pinned to the inspected SHA):

- Chat-side context UI: [`ChatContainer/index.jsx`](https://github.com/Mintplex-Labs/anything-llm/blob/128a01575a50f0284aeca75a93399b6fb1db0328/frontend/src/components/WorkspaceChat/ChatContainer/index.jsx#L534-L580), [`ChatSidebar/index.jsx`](https://github.com/Mintplex-Labs/anything-llm/blob/128a01575a50f0284aeca75a93399b6fb1db0328/frontend/src/components/WorkspaceChat/ChatContainer/ChatSidebar/index.jsx), [`SourcesSidebar/`](https://github.com/Mintplex-Labs/anything-llm/blob/128a01575a50f0284aeca75a93399b6fb1db0328/frontend/src/components/WorkspaceChat/ChatContainer/SourcesSidebar/index.jsx), [`ChatHistory/Citation/index.jsx`](https://github.com/Mintplex-Labs/anything-llm/blob/128a01575a50f0284aeca75a93399b6fb1db0328/frontend/src/components/WorkspaceChat/ChatContainer/ChatHistory/Citation/index.jsx), [`ChatHistory/StatusResponse/index.jsx`](https://github.com/Mintplex-Labs/anything-llm/blob/128a01575a50f0284aeca75a93399b6fb1db0328/frontend/src/components/WorkspaceChat/ChatContainer/ChatHistory/StatusResponse/index.jsx), [`DnDWrapper/`](https://github.com/Mintplex-Labs/anything-llm/blob/128a01575a50f0284aeca75a93399b6fb1db0328/frontend/src/components/WorkspaceChat/ChatContainer/DnDWrapper/index.jsx), [`PromptInput/`](https://github.com/Mintplex-Labs/anything-llm/blob/128a01575a50f0284aeca75a93399b6fb1db0328/frontend/src/components/WorkspaceChat/ChatContainer/PromptInput/index.jsx) (Attachments, AttachItem, ParsedFilesMenu, ToolsMenu).
- Document management: [`Modals/ManageWorkspace/`](https://github.com/Mintplex-Labs/anything-llm/blob/128a01575a50f0284aeca75a93399b6fb1db0328/frontend/src/components/Modals/ManageWorkspace/index.jsx) (WorkspaceDirectory, WorkspaceFileRow).
- Server context assembly: [`server/utils/chats/stream.js`](https://github.com/Mintplex-Labs/anything-llm/blob/128a01575a50f0284aeca75a93399b6fb1db0328/server/utils/chats/stream.js), [`server/utils/helpers/chat/index.js`](https://github.com/Mintplex-Labs/anything-llm/blob/128a01575a50f0284aeca75a93399b6fb1db0328/server/utils/helpers/chat/index.js#L382-L442), [`server/utils/DocumentManager/index.js`](https://github.com/Mintplex-Labs/anything-llm/blob/128a01575a50f0284aeca75a93399b6fb1db0328/server/utils/DocumentManager/index.js#L30-L75), [`server/models/workspaceParsedFiles.js`](https://github.com/Mintplex-Labs/anything-llm/blob/128a01575a50f0284aeca75a93399b6fb1db0328/server/models/workspaceParsedFiles.js#L223-L253), [`server/prisma/schema.prisma`](https://github.com/Mintplex-Labs/anything-llm/blob/128a01575a50f0284aeca75a93399b6fb1db0328/server/prisma/schema.prisma#L27-L35).
- Copy: [`frontend/src/locales/en/common.js`](https://github.com/Mintplex-Labs/anything-llm/blob/128a01575a50f0284aeca75a93399b6fb1db0328/frontend/src/locales/en/common.js#L202-L219).

## License

**SPDX: `MIT`.** GitHub's licence API also reports `MIT`. The [`LICENSE`](https://github.com/Mintplex-Labs/anything-llm/blob/128a01575a50f0284aeca75a93399b6fb1db0328/LICENSE) file is the standard MIT text. Its header and condition, quoted exactly:

> The MIT License
>
> Copyright (c) Mintplex Labs Inc.

> The above copyright notice and this permission notice shall be included in
> all copies or substantial portions of the Software.

[`TERMS_SELF_HOSTED.md`](https://github.com/Mintplex-Labs/anything-llm/blob/128a01575a50f0284aeca75a93399b6fb1db0328/TERMS_SELF_HOSTED.md) repeats that *"The AnythingLLM core is provided under the **MIT License**"*. It adds no restriction. It does document two behaviours Skein must not copy: optional anonymous telemetry, which is opt-out, and a Mintplex CDN fallback for the default embedder and reranker models.

**Meaning for Skein.** MIT is on the `foss`-flavour allowlist (`docs/Handoffs/skein-v1-autonomous-completion.md` §3 item 6), so reuse would be legal if the notice were kept. In practice nothing transfers: the UI is JSX + Tailwind and the server is Node. **No AnythingLLM code is copied.** What Skein takes is behaviour, re-derived in Kotlin in Skein's own words.

## Maintenance

- **Active.** Last push 2026-09-26. At least 100 commits in the 30 days before inspection (the API page cap). Release `v1.16.2` on 2026-09-22. About 66.5k stars and 315 open issues. Not archived. Created 2023-06-04.
- **Fast-moving chat surface.** The chat container has recently gained a Memories sidebar, a model router, "parsed file" attachments and an agent tool menu. The component layout changes too often to be a stable reference. **Take the behaviours, not the file structure.**

## Relevant Skein Problem

**Primary question (brief §13):** *How should Skein communicate what knowledge is currently active in a conversation?*

What Skein does today (all paths in `feature/chat/src/main/kotlin/app/skein/feature/chat/`):

1. **The context panel reports after the fact, in implementation language.** `ChatScreen.kt:79-91` puts a literal `"chat"` title next to a `⚹ context` text button. The button toggles an inline `ContextPanel` that pushes the message list down. `ContextPanel.kt:33-89` lists only the *last* turn's `Retrieved` rows as `score 0.83` and `recalled by: vector, lexical`. Nothing tells the user *before* sending what Skein will look at. The `⚹` glyph also doubles as the Settings nav glyph (`SkeinTokens.kt:61`).
2. **📎 attach promises context it does not guarantee.** `ChatViewModel.attach` (`ChatViewModel.kt:286-298`) imports through `ImportService` and then only inserts the text `[[displayName]]` into the composer. `SendPipeline` still calls `retrieveContext(text, RETRIEVAL_K = 8, persona?.id)` over the whole vault (`SendPipeline.kt:71`, `:214`). Nothing makes the attached file part of this chat's context. It can only be retrieved after ingest finishes (`app/.../ingest/IngestWorkPort.kt`: unconstrained work that runs while unlocked in the foreground, so there is still a window).
3. **There are no knowledge scopes and no context meter, although the numbers exist.** There is no per-chat "use my notes on/off", no "answer only from my notes", and no way to include a whole note. Yet `TokenBudget` (`contextLength`, `reserveForAnswer`, `maxRetrievedTokens`) and `AssembledPrompt` (`citations`, `droppedHistoryTurns`, `estimatedTokens`) in `core/model/.../Retrieval.kt` already carry what a meter needs.

The user cannot answer three questions: *What is Skein reading for this chat? Did it read the file I just attached? Why did it cite that note?* AnythingLLM is the right reference because its RAG model has **three explicit context scopes**, and each one shows its status to the user.

## Patterns Worth Adopting

**A1 · Three context scopes, each with a visible lifetime.**
- **Pinned** documents: full text goes into *every* prompt in the workspace ([`stream.js` L149-165](https://github.com/Mintplex-Labs/anything-llm/blob/128a01575a50f0284aeca75a93399b6fb1db0328/server/utils/chats/stream.js#L149-L165)).
- **Parsed files** attached in chat: full text goes into every prompt *of this thread* until removed ([`stream.js` L167-183](https://github.com/Mintplex-Labs/anything-llm/blob/128a01575a50f0284aeca75a93399b6fb1db0328/server/utils/chats/stream.js#L167-L183); scoped by `threadId` in [`workspaceParsedFiles.js` L223-229](https://github.com/Mintplex-Labs/anything-llm/blob/128a01575a50f0284aeca75a93399b6fb1db0328/server/models/workspaceParsedFiles.js#L223-L229)).
- **Similarity search** over the workspace: `topN` 4, threshold 0.25 ([`stream.js` L185-200](https://github.com/Mintplex-Labs/anything-llm/blob/128a01575a50f0284aeca75a93399b6fb1db0328/server/utils/chats/stream.js#L185-L200); defaults in [`schema.prisma` L128-141](https://github.com/Mintplex-Labs/anything-llm/blob/128a01575a50f0284aeca75a93399b6fb1db0328/server/prisma/schema.prisma#L128-L141)).

→ *Skein:* **Always** (from the persona), **This chat** (attached), and **Search my notes** (Knowledge on). Skein has one vault, so the third scope is vault-wide (or persona-filtered through `retrieveContext(personaId)`), never a copied index.

**A2 · An attachment chip that states its status and scope in words.** The chip reads *Uploading…*, *Added as context!*, *File embedded!* or *Image attached!*, or shows a failure with the error ([`Attachments/index.jsx` L60-213](https://github.com/Mintplex-Labs/anything-llm/blob/128a01575a50f0284aeca75a93399b6fb1db0328/frontend/src/components/WorkspaceChat/ChatContainer/PromptInput/Attachments/index.jsx#L60-L213)). Its explanation says *"will be used as context for this chat only"* versus *"embedded into this workspace… available for RAG"* (L185-189). An image *"will not be embedded into the workspace permanently"* (L122).

→ *Skein:* chip states *Reading…*, *Whole note*, *Relevant parts*, *Indexing…*, *Couldn't read*. The one-line scope explanation lives in the inspector because touch has no tooltips.

**A3 · Attachments are checked against the budget before they enter context.** Full-text attachments are allowed up to 80% of the model's window (`maxContextWindowLimit: 0.8`, [`models/workspace.js` L11](https://github.com/Mintplex-Labs/anything-llm/blob/128a01575a50f0284aeca75a93399b6fb1db0328/frontend/src/models/workspace.js#L11)). Past that, the flow stops and asks ([`DnDWrapper/index.jsx` L217-307](https://github.com/Mintplex-Labs/anything-llm/blob/128a01575a50f0284aeca75a93399b6fb1db0328/frontend/src/components/WorkspaceChat/ChatContainer/DnDWrapper/index.jsx#L217-L307)). The attach menu shows **"Current Context (N files)"** and **"12k / 32k tokens"**, which turns orange and offers "Embed Files into Workspace" when over ([`ParsedFilesMenu` L106-161](https://github.com/Mintplex-Labs/anything-llm/blob/128a01575a50f0284aeca75a93399b6fb1db0328/frontend/src/components/WorkspaceChat/ChatContainer/PromptInput/AttachItem/ParsedFilesMenu/index.jsx#L106-L161)).

→ *Skein:* same check, driven by `TokenBudget` (16K context; `maxRetrievedTokens` = `min(3072, 40 %)`). Skein **falls back automatically** to "Relevant parts" and says so, instead of asking (see N6).

**A4 · Send waits until attachments are ready.** `useIsDisabled` disables Send while attachments process, *"or else the query may not have relevant context since RAG is not yet ready"* ([`PromptInput/index.jsx` L532-558](https://github.com/Mintplex-Labs/anything-llm/blob/128a01575a50f0284aeca75a93399b6fb1db0328/frontend/src/components/WorkspaceChat/ChatContainer/PromptInput/index.jsx#L532-L558)).

→ *Skein:* keep the guard but make it visible. AnythingLLM explains it only in a hover tooltip on Send. Skein shows a "Waiting for spec.pdf…" state on the Send button, with Cancel. Whole-note attachments only need text extraction; "Relevant parts" needs that one document indexed.

**A5 · A sources pill under each answer that opens a side panel.** It shows "Sources" plus up to 3 stacked source icons and "+N". Chunks are grouped per document with "Referenced N times". Tapping it again closes the panel ([`Citation/index.jsx` L111-191, L198-260](https://github.com/Mintplex-Labs/anything-llm/blob/128a01575a50f0284aeca75a93399b6fb1db0328/frontend/src/components/WorkspaceChat/ChatContainer/ChatHistory/Citation/index.jsx#L111-L260)).

→ *Skein:* a `3 sources` chip under each assistant message that opens the inspector at *"Used in this answer"* for **that** message. Group by `docId`, not by title (see "Potential Reusable Code").

**A6 · One right-hand slot, several inspectors.** `ChatSidebarProvider` holds a single `activeSidebar` (`"sources" | "memories"`), and opening one closes the other ([`ChatSidebar/index.jsx` L1-62](https://github.com/Mintplex-Labs/anything-llm/blob/128a01575a50f0284aeca75a93399b6fb1db0328/frontend/src/components/WorkspaceChat/ChatContainer/ChatSidebar/index.jsx#L1-L62)). The 366 px panel slides in with `translateX` so the chat history (500+ nodes) never re-lays-out (L64-93).

→ *Skein:* **one** supporting-pane slot whose content is the Context inspector. Per-message sources deep-link into it and never open a second panel. For Compose, animate the pane with offset or `graphicsLayer`, not by re-measuring the `LazyColumn`.

**A7 · On phones the panel becomes a modal with list → detail and a back button.** [`SourcesSidebar` L23-36](https://github.com/Mintplex-Labs/anything-llm/blob/128a01575a50f0284aeca75a93399b6fb1db0328/frontend/src/components/WorkspaceChat/ChatContainer/SourcesSidebar/index.jsx#L23-L36) swaps in [`MobileCitationModal` L8-56](https://github.com/Mintplex-Labs/anything-llm/blob/128a01575a50f0284aeca75a93399b6fb1db0328/frontend/src/components/WorkspaceChat/ChatContainer/SourcesSidebar/MobileCitationModal/index.jsx#L8-L56).

→ *Skein:* on the outer screen the same content sits in a `ModalBottomSheet` with drill-in inside the sheet.

**A8 · Activity rolls up into one collapsible block.** *"Every agent status update and model thought that happens between visible chat messages collapses into a single expandable block."* While running, the header shows the latest status. Afterwards it shows a duration label. Steps are timed on the client ([`StatusResponse/index.jsx` L22-94](https://github.com/Mintplex-Labs/anything-llm/blob/128a01575a50f0284aeca75a93399b6fb1db0328/frontend/src/components/WorkspaceChat/ChatContainer/ChatHistory/StatusResponse/index.jsx#L22-L94)).

→ *Skein:* this is brief §27's `▸ Worked for 8.1s · 7 sources · 2 notes`. The event source is `SendPipeline`'s existing stages (retrieve → budget → assemble → stream → parse → persist). No reasoning is fabricated.

**A9 · A mode that answers only from documents, and says so when it can't.** In `query` mode, if nothing is found the reply is *"There is no relevant information in this workspace to answer your query."* ([`stream.js` L100-129, L233-262](https://github.com/Mintplex-Labs/anything-llm/blob/128a01575a50f0284aeca75a93399b6fb1db0328/server/utils/chats/stream.js#L233-L262)), with a *"Why am I seeing this?"* link under it ([`HistoricalMessage` L165-177](https://github.com/Mintplex-Labs/anything-llm/blob/128a01575a50f0284aeca75a93399b6fb1db0328/frontend/src/components/WorkspaceChat/ChatContainer/ChatHistory/HistoricalMessage/index.jsx#L165-L177)). The copy defines the modes plainly: Chat = *"general knowledge **and** document context"*; Query = *"**only** if document context is found"* ([`common.js` L202-219](https://github.com/Mintplex-Labs/anything-llm/blob/128a01575a50f0284aeca75a93399b6fb1db0328/frontend/src/locales/en/common.js#L202-L219)).

→ *Skein:* a per-chat **Notes only** mode. When nothing is found, say so and offer one tap: **Answer without notes**. Show the mode in the indicator. AnythingLLM needs its "Why am I seeing this?" rescue only because the mode is hidden in workspace settings.

**A10 · A one-time explainer when a power concept is first used.** The pin explainer appears only on the first pin: *"inject the entire content of the document into your prompt window"* ([`WorkspaceDirectory` L311-367](https://github.com/Mintplex-Labs/anything-llm/blob/128a01575a50f0284aeca75a93399b6fb1db0328/frontend/src/components/Modals/ManageWorkspace/Documents/WorkspaceDirectory/index.jsx#L311-L367); [`common.js` L1508-1513](https://github.com/Mintplex-Labs/anything-llm/blob/128a01575a50f0284aeca75a93399b6fb1db0328/frontend/src/locales/en/common.js#L1508-L1513)).

→ *Skein:* the first time a user switches an attachment to "Whole note", show one inline sentence in the sheet, not a modal.

## Patterns Not Worth Adopting

- **N1 · Workspaces as hard silos with one index each.** A document has to be embedded again for each workspace ("Save and Embed" in the two-panel document manager). Skein has one vault joined by wikilinks and a graph. Scope belongs in a filter (persona or tag), not in a copied index. The word "workspace" is also reserved for the possible future Skein "Workspace / Projects" concept (brief §20).
- **N2 · Context the user can't see.** `fillSourceWindow` backfills up to `topN` chunks from *earlier answers' sources* into the prompt, and deliberately leaves them out of the citations ([`stream.js` L215-231](https://github.com/Mintplex-Labs/anything-llm/blob/128a01575a50f0284aeca75a93399b6fb1db0328/server/utils/chats/stream.js#L215-L231); [`helpers/chat` L382-442](https://github.com/Mintplex-Labs/anything-llm/blob/128a01575a50f0284aeca75a93399b6fb1db0328/server/utils/helpers/chat/index.js#L382-L442)). Pinned documents over the token cap are dropped with only a console log (`DocumentManager` [L60-62](https://github.com/Mintplex-Labs/anything-llm/blob/128a01575a50f0284aeca75a93399b6fb1db0328/server/utils/DocumentManager/index.js#L60-L62)). **Skein rule: everything in the prompt appears in the inspector, and everything left out that the user asked for is labelled as left out.**
- **N3 · Controls that need hover.** The ✕ on attachment chips appears only on `group-hover`. "Current Context" opens from a pointer-enter tooltip ([`AttachItem` L100, L116-145](https://github.com/Mintplex-Labs/anything-llm/blob/128a01575a50f0284aeca75a93399b6fb1db0328/frontend/src/components/WorkspaceChat/ChatContainer/PromptInput/AttachItem/index.jsx#L100-L145)). Score explanations are tooltips. None of this works on touch.
- **N4 · "83% match" similarity numbers** ([`Citation` L235-248](https://github.com/Mintplex-Labs/anything-llm/blob/128a01575a50f0284aeca75a93399b6fb1db0328/frontend/src/components/WorkspaceChat/ChatContainer/ChatHistory/Citation/index.jsx#L235-L248)). Skein fuses vector, BM25 and PageRank scores (spec §7.2), so the number is not a calibrated similarity. Showing it as a percentage would mislead. Keep it in developer details.
- **N5 · Third-party favicon fetches.** `SourceTypeCircle` loads `google.com/s2/favicons` ([L70-78](https://github.com/Mintplex-Labs/anything-llm/blob/128a01575a50f0284aeca75a93399b6fb1db0328/frontend/src/components/WorkspaceChat/ChatContainer/ChatHistory/Citation/index.jsx#L70-L78)). This leaks hostnames and is impossible without `INTERNET`.
- **N6 · The three-button "Context Window Warning" modal** (Cancel / Continue Anyway / Embed Files, [`FileUploadWarningModal` L51-93](https://github.com/Mintplex-Labs/anything-llm/blob/128a01575a50f0284aeca75a93399b6fb1db0328/frontend/src/components/WorkspaceChat/ChatContainer/DnDWrapper/FileUploadWarningModal/index.jsx#L51-L93)). It asks a Level-1 user to make a Level-2 decision. On a 16K local model, "Continue anyway" means silent truncation. Skein should pick the safe default and explain it.
- **N7 · Mode and model hidden in workspace settings pages** (`pages/WorkspaceSettings/ChatSettings/*`) plus a floating header model picker. In Skein these belong in the inspector's Session section. Global Settings holds only defaults.
- **N8 · Choosing the phone layout from the user agent.** `react-device-detect` `isMobile` picks modal vs sidebar once ([`SourcesSidebar` L2, L23](https://github.com/Mintplex-Labs/anything-llm/blob/128a01575a50f0284aeca75a93399b6fb1db0328/frontend/src/components/WorkspaceChat/ChatContainer/SourcesSidebar/index.jsx#L2-L23)). On `isMobileOnly` the whole document manager, where pinning lives, becomes *"Editing these settings are only available on a desktop device"* ([`ManageWorkspace/index.jsx` L47-65](https://github.com/Mintplex-Labs/anything-llm/blob/128a01575a50f0284aeca75a93399b6fb1db0328/frontend/src/components/Modals/ManageWorkspace/index.jsx#L47-L65); `common.js` L1467-1468). So on a phone, pinning does not exist. Skein must reflow on window-size changes (brief §24), and every context action must be touch-native.
- **N9 · The `@agent` text prefix to turn on tools** ([`PromptInput` L415-456](https://github.com/Mintplex-Labs/anything-llm/blob/128a01575a50f0284aeca75a93399b6fb1db0328/frontend/src/components/WorkspaceChat/ChatContainer/PromptInput/index.jsx#L415-L456)). A mode typed as magic text can't be discovered.
- **N10 · Multi-user roles** that stop "default" users from embedding (`canEmbed`). Skein has a single user.

## Potential Reusable Code

**None copied, none recommended.** MIT would allow it, but every relevant file is React/JSX, Tailwind or Node.

Three small behaviours are worth re-implementing in Kotlin (no attribution needed because no code is taken):

1. **Group sources per document for display.** `combineLikeSources` keys on `title` ([`Citation` L111-127](https://github.com/Mintplex-Labs/anything-llm/blob/128a01575a50f0284aeca75a93399b6fb1db0328/frontend/src/components/WorkspaceChat/ChatContainer/ChatHistory/Citation/index.jsx#L111-L127)). That merges two *different* documents that share a title, which is a real risk in Skein, where many chats are titled "Chat". **Key on `Retrieved.docId`** (UUIDv7) and count passages.
2. **Check the attachment budget before inclusion** (A3), against `TokenBudget`.
3. **Stamp activity steps on the client** (A8) when `SendPipeline` emits no timestamps.

Estimated lines copied: 0. Estimated re-implementation: tens of lines each.

## Android/Fold Relevance

The size classes are estimates: outer screen ≈ 411 dp wide (Compact); inner screen ≈ 790–820 dp (Medium width in both orientations). **Confirm on device with `currentWindowAdaptiveInfo()` before encoding thresholds.**

| AnythingLLM pattern | Outer screen (bottom / context sheet) | Inner screen (supporting pane) | Desktop-only (do not copy) |
| --- | --- | --- | --- |
| Sources pill → right panel (A5, A6) | Chip opens a `ModalBottomSheet` at half height; list → detail inside the sheet (A7) | The same content in a supporting pane of about 320–360 dp; the conversation keeps at least ~420 dp (see `LOBECHAT.md` L10) | 366 px fixed-width slide-in panel |
| Attachment chips with status (A2) | One summary chip above the composer ("2 notes · 1 file"), never a row of six chips | The summary chip, plus a chip strip when composing | Hover-revealed ✕ (N3) |
| "Current Context · 12k/32k" (A3) | Inspector **Context** section | Same section, always visible in the pane | Hover tooltip menu on the + button |
| Context Window Warning modal (N6) | Replaced by an inline notice in the attach sheet | Same | 3-button modal |
| Activity chain (A8) | Collapsible row in the transcript | Same, plus an Activity section in the pane | — |
| Drag-and-drop "Add anything" overlay | — | Useful when a file is dragged from another app in split-screen (Compose `dragAndDropTarget`); P2 | Full-window drop target |
| Document manager with pin and watch toggles | — | — | Two-panel 12-column transfer UI; already desktop-only upstream (N8) |
| Workspace settings pages | — | — | Reject; see Session in the proposal below |

**Fold transitions** (brief §§24–25). The inspector's open state, its focus (latest answer or a specific message) and its expanded sections must survive posture changes. Going open → closed hides the pane and does **not** pop a sheet over the conversation (Test A). Going closed → open restores the pane if it was open before (Test B). A sheet that is open when the device unfolds turns into the pane at the same scroll position (Test G). Details are under [Containers and fold behaviour](#p5--containers-and-fold-behaviour).

## Mac UX-Lab Relevance

- **Live behavioural reference.** AnythingLLM Desktop (Mac) or the Docker image with a local Ollama lets a designer walk through attach-to-chat → budget warning → pin → sources panel → activity chain in minutes. Its phone layout is chosen from the user agent (N8), so **resizing the window will not show it**. Use browser devtools device emulation with a phone UA against the Docker web UI. Keep the resulting screenshots out of the repository; they are for comparison only.
- **Layer A fixtures.** The three-scope model gives the preview states for the Compose Preview and Roborazzi baselines (brief §§37, 39): (1) Knowledge on, nothing attached; (2) 2 notes and 1 file attached, one indexing; (3) attachment too long, fell back to relevant parts; (4) Notes only with no sources (refusal plus "Answer without notes"); (5) sources opened from a message; (6) activity collapsed and expanded; (7) Knowledge off. Each one is rendered at outer-screen and inner-screen sizes.
- **Layer C** (optional `ux-lab/` React/Vite). AnythingLLM's MIT components *could* legally be pulled in. **Don't.** The lab exists to try Skein's design, and borrowed components pull the prototype toward someone else's product.

## Recommended Action

**PROTOTYPE.** Patterns only; no code. Prototype the three-scope context model, the context chip, the attach sheet and the Context inspector as Compose previews with fixture data (Layer A), at both Fold sizes, before production work in Wave 7 (brief §48). The concrete target is [Proposed Skein context presentation](#proposed-skein-context-presentation).

## Expected Benefit

- Answers the primary question with a model users can predict: *what's in, for how long, and why*.
- Turns 📎 from inserting text into a real context contract that the pipeline honours and the UI can verify.
- Closes an honesty gap that AnythingLLM itself has (N2): Skein shows every prompt input and every omission.
- One data model feeds brief §27 (activity), §35 (inspector) and the per-message sources chip. One composable serves both Fold containers.
- Removes today's implementation-language panel (`score`, `recalled by: vector`).

## Expected Cost

- **UI (inside this workstream):** `ContextChip`, `AttachSheet`, `ContextInspector` (five sections), `SourcesChip` per message, `ActivityRow`, plus previews and Roborazzi baselines. Roughly 4–6 beads of Compose work. It replaces `ContextPanel.kt` and the `⚹ context` button rather than adding to them.
- **Narrow integration, which needs its own beads because the brief's §1 refactor ban applies:**
  1. Persist per-chat attachments with a mode: for example the chat document's `frontmatter` JSON (`documents.frontmatter`, spec §5). No new table.
  2. `PromptAssembler` / `SendPipeline` honour "Whole" and "Relevant parts" inside `TokenBudget`.
  3. A per-chat Knowledge on/off switch and a Notes-only mode in `SendPipeline`.
  4. A `Flow` of indexing status from `ingest_queue`.
  5. Stage events (`ActivityEvent`) emitted by `SendPipeline`.
  6. Per-segment token counts: persona, history, knowledge.
- **Risk:** including whole notes competes with history on a 16K window. The budget split needs Fold measurement (see open questions).

## Answers to the prompt's specific questions

**Workspace concepts.** A workspace bundles model, system prompt, chat mode, temperature, history length, its own vector namespace and its threads ([`schema.prisma` L128-141](https://github.com/Mintplex-Labs/anything-llm/blob/128a01575a50f0284aeca75a93399b6fb1db0328/server/prisma/schema.prisma#L128-L141)). *For Skein:* the closest analogue is a **persona** (system prompt + default model, spec §5), optionally carrying default knowledge. Skein should not add a workspace container over the vault. Personas are already the "who am I talking to" unit (brief §20).

**RAG UX (upload, embedding, pinning, query vs chat).**
- **Two ingestion paths.** (a) The document manager: upload to "My Documents", move into a workspace, then **Save and Embed** ([`Documents/index.jsx` L57-122](https://github.com/Mintplex-Labs/anything-llm/blob/128a01575a50f0284aeca75a93399b6fb1db0328/frontend/src/components/Modals/ManageWorkspace/Documents/index.jsx#L57-L122)). It is desktop-only (N8). (b) In-chat attach: the file is parsed to text and "Added as context!" for this thread. When it doesn't fit, it can be promoted to "embedded" (A2, A3).
- **Pinning** injects the whole document and is explained once (A10).
- **Query vs chat** is a mode with an explicit refusal (A9).
- *For Skein:* ingestion is already automatic: every note, chat and import is embedded by the ingest worker. Skein therefore needs **only path (b)**, re-expressed as attach-to-chat with a *Whole* or *Relevant parts* mode. The "embed" step is invisible except for an honest *Indexing…* state.

**Document context.** Whole-document context (pinned or parsed) and chunk context (search) are mixed in one prompt, and the budget is checked only for parsed files (A3). *For Skein:* both kinds come from the same `TokenBudget` allowance, and the inspector shows which kind each item is.

**Source selection.** Users select sources in two ways: by *pinning* (always) or by *attaching* (this thread). Everything else comes from automatic similarity search, with threshold and topN in settings. *For Skein:* select by attaching (a sheet, or `[[` inline) and by the per-chat Knowledge switch. Leave thresholds and topN out of the UI entirely; `k = 8` is a spec literal (`SendPipeline.kt:66-71`).

**Knowledge organization.** A flat "My Documents" folder tree, a per-workspace embedded set, and "watch for changes" (live sync) on connector documents ([`WorkspaceFileRow` L189-257](https://github.com/Mintplex-Labs/anything-llm/blob/128a01575a50f0284aeca75a93399b6fb1db0328/frontend/src/components/Modals/ManageWorkspace/Documents/WorkspaceDirectory/WorkspaceFileRow/index.jsx#L189-L257)). *For Skein:* organization is already richer (wikilinks, tags, entities, backlinks). The attach sheet should use it: a **Suggested** section ranked by graph proximity to the current chat is something AnythingLLM cannot offer.

**Relationship between conversation and documents.** Threads own parsed files; workspaces own pinned and embedded documents. Answers store their `sources` in the chat row, which is also why backfill (N2) is possible. *For Skein:* chats are documents in the same vault (spec §5). Assistant messages already persist `retrieved_chunks`, so an answer's sources can be reopened later from that message. Attachments should persist on the chat document, so reopening a chat restores its context. That is brief §24's "active knowledge / attached context" preservation.

**Workspace boundaries.** Hard boundaries: separate namespaces, and the same file embedded twice. *For Skein:* boundaries are soft filters (persona scope today; tag scope possibly later), never copies (N1).

**Active context presentation.** AnythingLLM spreads this over four places: the + button badge and its hover menu, the chips above the input, the sources pill after the answer, and the workspace settings for mode. The mode is invisible until a refusal happens. *For Skein:* one indicator, one inspector, and one per-answer chip, all backed by the same model. Details follow.

**Primary question: how should Skein communicate what knowledge is active?** Before sending, show **one compact chip** above the composer that summarizes all three scopes in words (`2 notes · 1 file · Knowledge on`). After answering, show **one sources chip per answer** that reflects what was *actually* used (`3 sources`). Put **one inspector** behind both: a bottom sheet on the outer screen and a supporting pane on the inner screen. It lists every prompt input with its scope and lifetime, and every omission. Scores, recall stages and token numbers stay hidden unless asked for.

---

## Proposed Skein context presentation

This design draws on AnythingLLM (scopes, attachment status, the sources pill, the activity chain), Open WebUI (one content in two containers, "only what's on", per-attachment whole vs retrieval, inherit-by-default settings; see [`OPEN_WEBUI.md`](OPEN_WEBUI.md)) and LobeChat (a meter that stays quiet until needed, a conversation that keeps its width, product-language labels; see [`LOBECHAT.md`](LOBECHAT.md)). It is written for Skein's actual data types. Where a backend change is needed, it is marked **[integration]** and belongs in a separate, narrowly scoped bead.

### P0 · Principles

1. **Show what actually went into the prompt, not what the user intended.** Anything the user asked for that was left out is labelled as left out (fixes N2).
2. **Three scopes, named in product language, each with a visible lifetime:** *This message* (inline `[[link]]`, a selection), *This chat* (attached), and *Always* (from the persona). The automatic scope is *Search my notes*, which is **Knowledge on/off**.
3. **Quiet by default, visible when it matters.** Non-default states and pressure get a signal; the pristine state is one quiet chip (LobeChat L1, Open WebUI O3).
4. **One content, two containers.** The same inspector is a sheet on the outer screen and a pane on the inner screen (Open WebUI O2, AnythingLLM A7).
5. **Level 1 never sees** scores, recall-stage names, chunk IDs, token counts, embedder names or model filenames. Each section has a **Details** disclosure for Level 2.
6. **Local is the identity.** The model reads `Qwen 2.5 3B · Local`. There is no provider picker while only one kind of provider exists. Showing an empty one would be a dead control (brief §2). Skein Core has no `INTERNET` permission (spec §2 principle 1; non-negotiable #1), so any external provider needs its own architecture decision. The UI reserves only the attribute slot where "Local" appears.

### P1 · Vocabulary

| Internal concept | UI term (Level 1) | Level-2 detail line |
| --- | --- | --- |
| `retrieveContext` over the vault | **Knowledge** (on/off); explained as "Search my notes" | "Searches all notes" / "Searches Researcher's notes" (persona scope) |
| `query`-style grounding | **Notes only** | "Won't answer from general knowledge" |
| `Retrieved` chunk | **Passage** | `[1]`, the same marker as the inline citation |
| Retrieved set of an answer | **Sources** | grouped per document |
| Attached document, full text | **Whole note** / **Whole file** | "Included in full every turn" |
| Attached document, retrieval-limited | **Relevant parts** | "Only matching passages are used" |
| `RecallSource.VECTOR / LEXICAL / GRAPH` | *(hidden)* | "Similar meaning" / "Same words" / "Linked note" |
| `AssembledPrompt.droppedHistoryTurns` | **Earlier messages left out** | "3 earlier messages didn't fit" |
| `TokenBudget` usage | **Context** (a bar) | tokens used / 16,384 |
| `ingest_queue` membership | **Indexing…** | — |
| `persona.systemPrompt` | **Persona instructions** | — |

### P2 · The active-context indicator (chip)

**Placement.** A single row directly above the composer, inside the composer surface's top edge. It is one chip that summarizes, never a row of per-item chips on the outer screen. The visual height is 32 dp and the touch target is 48 dp. It is the **only** entry point to the inspector in the chat UI; the ⋮ overflow menu repeats it as "Context" so TalkBack and keyboard users can find it. It replaces the `⚹ context` header button (`ChatScreen.kt:80`), so there are not two ways to do the same thing.

| State | Chip text (en) | Treatment |
| --- | --- | --- |
| Pristine: Knowledge on, nothing attached | `Knowledge on` | quiet tonal chip, knowledge icon |
| Attachments present | `2 notes · 1 file · Knowledge on` | tonal |
| Knowledge off | `Knowledge off` (or `1 file · Knowledge off`) | outlined, crossed icon (never colour alone) |
| Notes only | `2 notes · Notes only` | tonal, accent outline on the composer (Open WebUI O8) |
| Attachment indexing | `1 file indexing…` | small progress ring replaces the leading icon |
| Attachment didn't fit whole | `2 notes · 1 file · ⚠` | warning icon plus text in the inspector: "spec.pdf: using relevant parts" |
| Context ≥ 75 % or history trimmed | the leading icon becomes a usage ring; the inner screen adds `· Context 80%` | only when pressure is real (LobeChat L1) |
| While generating | unchanged; tapping opens the inspector read-only | edits are disabled with the reason "Available when the answer finishes" |

**Truncation order** at 411 dp and font scale 1.3: drop `· Knowledge on`, then merge counts (`3 items`), and never drop a warning. **Content description:** "Context: 2 notes, 1 file, knowledge on. Double-tap to inspect."

**After each answer.** Under the assistant message goes `3 sources` (stacked kind icons: note, file, chat). If Knowledge was on and nothing was used, it reads `No sources used`, quietly. In Notes-only mode with no sources, the answer is the refusal plus an **Answer without notes** button (A9). Tapping `3 sources` opens the inspector **focused on that answer**.

**Activity** (brief §27). While running: `Working · Searching notes…`. When done: `▸ Worked for 8.1s · 3 sources · 2 notes`. Tapping expands the steps (A8).

```text
Outer screen (≈411 dp)                     Inner screen (≈800 dp), inspector open
┌─────────────────────────────┐            ┌───────────────────────────────┬────────────────────┐
│ ☰  Architecture chat     ⋮  │            │ ☰  Architecture chat       ⋮  │ Context          ✕ │
│    Qwen 2.5 3B · Local      │            │    Qwen 2.5 3B · Local        │ Latest answer ▾    │
├─────────────────────────────┤            ├───────────────────────────────┤ KNOWLEDGE          │
│ You                         │            │ You                           │ Search my notes ●  │
│ How does retrieval rank?    │            │ How does retrieval rank?      │ Attached · chat    │
│                             │            │                               │  RAG notes   Whole │
│ Skein                       │            │ Skein                         │  spec.pdf  Relev.  │
│ ▸ Worked 8.1s · 3 sources   │            │ ▸ Worked 8.1s · 3 sources     │ Used in answer     │
│ Retrieval fuses vector…[1]  │            │ Retrieval fuses vector…[1]    │  RAG notes · 2   › │
│ 3 sources                   │            │ 3 sources                     │ ACTIVITY  8.1s   › │
├─────────────────────────────┤            ├───────────────────────────────┤ CONTEXT ▰▰▰▰▱ 62% │
│ ◉ 2 notes · 1 file · Know…  │            │ ◉ 2 notes · 1 file · Knowl. on│ SESSION          › │
│ ＋  Ask Skein…           ↑  │            │ ＋  Ask Skein…             ↑  │ GRAPH  4 related › │
└─────────────────────────────┘            └───────────────────────────────┴────────────────────┘
```

### P3 · Attach-knowledge flow

**Entry points** (each is a different gesture; none duplicates another):

1. **Composer `＋`** replaces 📎 and opens the **Attach sheet**.
2. **`[[` in the composer** uses the existing wikilink autocomplete (spec §8.4). Choosing a note inserts the link **and** attaches it for *this message* only (Open WebUI `#` idea, O5). Skein does **not** add `#`, because `#tag` is Obsidian tag syntax in Skein's Markdown.
3. **From a note**: overflow → *Ask about this note* opens a chat with the note attached for *this chat*. **Editor selection → Ask** (spec §3.1) puts a quoted-selection chip in the composer for *this message* (LobeChat L12).
4. **Share to Skein** (existing share target) → *Add to a chat*.

**Attach sheet.** On the outer screen it is a `ModalBottomSheet` that opens half-height and drags to full. On the inner screen it is the same sheet, centred, with M3's maximum sheet width.

```text
┌─────────────────────────────┐
│             ───             │
│ Add to this chat            │
│ 🔍 Search notes, files, chats│
│ [Notes] [Files] [Chats]     │  filter chips; no chip selected = all
│ SUGGESTED                   │  graph-near the current chat (PageRank over its sources)
│ ☐ RAG architecture    note  │
│ ☐ llama.cpp build     note  │
│ RECENT                      │
│ ☐ spec.pdf            file  │
│ ＋ Import file…             │  SAF; the imported file arrives selected
├─────────────────────────────┤
│ Whole notes are used when   │  one line, shown the first time (A10)
│ they fit.          [Add 2]  │
└─────────────────────────────┘
```

**After Add:** the sheet closes and the chip updates. Skein picks the mode for each item: **Whole** if its estimated tokens fit the remaining attachment allowance, otherwise **Relevant parts** (A3 without the modal, N6). Imported files show *Indexing…* until they leave `ingest_queue`. Whole-mode needs only extracted text. If the user sends while a Relevant-parts item is still indexing, Send shows "Waiting for spec.pdf…" with Cancel (A4).

**[integration] Budget rule** (to be measured on the Fold). Whole attachments and retrieved passages share `TokenBudget.maxRetrievedTokens`, which defaults to 3,072 = `min(3072, 40 %)` of 16,384. Retrieval keeps a floor of 2 passages while Knowledge is on. AnythingLLM's 80 %-of-window rule (A3) is the upper bound for reference, not the target: Skein's history also has to fit.

**Removing:** in the inspector's Attached rows (48 dp ✕, or swipe). Changing mode: tap the row, then *Whole note* / *Relevant parts* (Open WebUI O6).

### P4 · The Context inspector

**Header:** `Context`, plus a focus selector: `Latest answer ▾`, or `Answer from 10:42 · Back to latest` when opened from a message's sources chip (A5).

**Sections.** One scrolling column. Each section can collapse, and its expanded state persists (Open WebUI O2). They are ordered by how often users need them. This differs from brief §35's list order: the header already shows model and persona, so Session is less urgent.

1. **Knowledge** (always expanded)
   - `Search my notes` switch. Detail line: scope ("All notes").
   - `Notes only` switch (Level 2; also appears in Session).
   - **Attached to this chat:** icon · title · mode (`Whole` / `Relevant parts` / `Indexing…` / `Too long · relevant parts`) · ✕. Tapping opens the document. A separate **From persona** group appears only once personas can carry knowledge (Open WebUI O10, LobeChat L7).
   - **Used in this answer:** grouped by `docId`, for example `RAG architecture · 2 passages ›`. Expanding shows quoted passages with the same `[N]` markers as the inline citations. Tapping a passage opens the note at `Retrieved.locator` when present (spec note: after Migration 003).
   - **Also in context** appears only when non-empty: for example "Persona instructions", or "3 earlier messages left out".
   - *Details:* score and recall stage per passage, labelled in words.
2. **Activity:** the latest turn's steps with durations, for example *Loaded model 2.1s · Searched notes: 8 found, 3 used · Read 2 attached notes · Wrote 212 words in 6.0s*. Future skill and tool calls appear here with approval state (LobeChat L14). Nothing is invented: rows are only real `SendPipeline` stage events **[integration]**.
3. **Context:** one segmented bar with Persona · Conversation · Knowledge · Reserved for answer, and the label "About 62% of this model's context". A warning line appears when `droppedHistoryTurns > 0`. *Details:* token numbers and `contextLength`. **[integration]** adds per-segment counts; the total already exists (`estimatedTokens`).
4. **Session:** Model (`Qwen 2.5 3B · Local`, `Loaded`) › opens the model sheet. Persona (`Researcher`) › change. Mode (Chat / Notes only). **Advanced** is collapsed: Creativity (temperature) and Response length, each shown as *Default (from Researcher)* until changed. The override is scoped to *this chat* (Open WebUI O1, LobeChat L6).
5. **Graph:** "Related in your vault", with notes and entities one hop from this answer's sources (wikilink, entity and tag edges; spec §5). They show as chips. *Open graph* goes to the Graph destination centred on the top source. On the inner screen a mini 2-hop graph is P2.

### P5 · Containers and fold behaviour

- **Compact (outer screen).** A `ModalBottomSheet` that opens partially and drags to full. Drill-in (document → passages) happens inside the sheet with a back arrow. Predictive back closes a drill-in first, then the sheet. Opening a note navigates to the note route and dismisses the sheet; it never stacks a sheet on a sheet. With the keyboard up, tapping the chip hides the keyboard first.
- **Medium/Expanded (inner screen).** `SupportingPaneScaffold` from `adaptive-layout` (already pinned at `1.3.0`, `gradle/libs.versions.toml:7`). The default scaffold directive allows one pane at Medium width, so Skein supplies its own `PaneScaffoldDirective` allowing two panes. **Rule: show the pane only if (window width − pane width) ≥ the conversation minimum** (≈ 420 dp, following LobeChat's `CONVERSATION_KEEP_WIDTH`; confirm on device). Otherwise use the sheet. Three panes (conversations | chat | context) don't fit at ≈ 800 dp, so opening the inspector moves the conversation list into the drawer (LobeChat L10). The pane width is fixed (320–360 dp) with no drag-resize (touch).
- **State.** `inspectorOpen`, `inspectorFocus` (latest answer or a message ID) and `expandedSections` live in the chat's ViewModel/`SavedStateHandle`, so they survive posture and configuration changes (brief §24).
- **Transitions.** Open → closed: the pane disappears and no sheet appears (Test A). Closed → open: the pane restores if it was open (Test B). An open sheet at unfold becomes the pane with the same focus and scroll position (Test G). While generating, the sections update in place (Test C).

### P6 · Where each element's data comes from

| UI element | Available today | Gap |
| --- | --- | --- |
| Sources chip per answer | `messages.retrieved_chunks` (spec §5); `AssembledPrompt.citations` | — |
| "Used in this answer", grouped | `Retrieved.docId/docTitle/text/sourceKind` | group by `docId` in UI |
| Passage → open at location | `Retrieved.locator` (nullable before Migration 003) | degrades to opening the whole note |
| Recall labels (Details) | `Retrieved.recalledBy` | — |
| Attached list and mode | — | **[integration]** per-chat set in chat `frontmatter` plus assembler support |
| Knowledge on/off, Notes only | — | **[integration]** `SendPipeline` flags plus refusal path |
| Indexing… | `ingest_queue(doc_id)` | a `Flow` over it |
| Context bar | `TokenBudget`, `AssembledPrompt.estimatedTokens`, `droppedHistoryTurns` | **[integration]** per-segment counts |
| Graph section | `edges`, `entities`; `VaultTools.list_backlinks` (design) | neighbour query for a set of docs |
| Activity | `SendPipeline` stage boundaries | **[integration]** emit `ActivityEvent`s |
| Model and persona | existing `Model` / `Persona` | display name vs filename (brief §34) |

### P7 · Acceptance checks for the implementing beads

- Every prompt input appears in the inspector, and nothing appears there that was not in the prompt. A fixture test compares `AssembledPrompt` with the inspector state.
- The chip fits at 411 dp with font scale 1.3 using the defined truncation order. It has a content description, and every target is at least 48 dp with no hover dependency.
- The sources chip opens the inspector focused on its own message, not on the latest answer.
- Fold Tests A, B, C and G (brief §25) pass with the inspector open.
- Roborazzi baselines exist for the seven fixture states in [Mac UX-Lab Relevance](#mac-ux-lab-relevance) at both sizes.
- No score, recall-stage name, chunk ID or token number is visible without opening Details.

### P8 · Open questions for the orchestrator

1. Should new chats start with Knowledge on (today's behaviour), or follow a persona default?
2. Attachments default to *this chat* (AnythingLLM thread scope) and `[[links]]` to *this message*. Should links also stick to the chat?
3. How to split the attachment and history budget on a 16K window: measure on the Fold before fixing numbers.
4. Should `Notes only` appear in the Level-1 chip at all, or stay Level 2?

---

**What specific problem in Skein can this project help us solve?** AnythingLLM shows how to make a conversation's knowledge explicit through three scopes with visible lifetimes (always, this chat, search), status-bearing attachment chips, a context-budget check and a per-answer sources panel. That lets Skein replace today's after-the-fact `⚹ context` panel and its text-only `📎 [[link]]` with one chip, one inspector and one sources chip that tell the user exactly what Skein is reading for this chat and why.
