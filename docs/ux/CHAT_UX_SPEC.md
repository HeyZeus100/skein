# Skein — Chat UX Spec

**Bead:** `skein-xtov.16` · **Epic:** `skein-xtov` (UX overhaul) · **Authority:** `docs/research/SKEIN_UI_UX_OVERHAUL_PROMPT.md` §1–3, §5, §26–29, §33–35, §41–42, §45–47, §51
**Keystone:** `docs/ux/INFORMATION_ARCHITECTURE.md` (the IA). This spec implements it. Where it disagrees, see §23.
**Status:** Proposed, for owner review. Nothing here is implemented.
**Evidence:** `docs/ux/audit/AUDIT_CHAT_MODELS_SETTINGS.md` (finding IDs `CMS-…`), `MATRIX_CHAT_MODELS_SETTINGS.md`, `LIFECYCLE_FINDINGS.md` (gap IDs `G…`), `DEVICE_BEFORE_PASS.md`; reference studies `docs/ux/research/POCKETPAL.md` (A1 is the activity block's source), `JAN.md`, `CONTINUE.md`, `LIBRECHAT.md`, `ANYTHINGLLM.md`, `OPEN_WEBUI.md`, `LOBECHAT.md`; the owner's screenshots `docs/ux/research/pocketpal-screens/`; code at `main` (`feature/chat/**`, `app/src/main/kotlin/app/skein/models/**`, `core/model/…/Inference.kt`, `Retrieval.kt`, `Vault.kt`).

**What this spec owns:** the content and behaviour of everything a user meets while chatting. That covers the chat screen, the activity and reasoning block, stop/retry/error states, chat empty states, the conversation list, titles, rename and delete as UI flows, the model sheet, the context inspector, the chat side of attaching knowledge, model naming, responsive content, chat accessibility and chat performance.

**What siblings own** (referenced, never re-specified here):

| Sibling | Owns |
|---|---|
| `DESIGN_SYSTEM.md` | Tokens, type scale, colour roles, component styling, destructive styling, dialog and sheet components, motion values |
| `ADAPTIVE_LAYOUT_SPEC.md` | Pane mechanics, breakpoints, which pane yields, rail/drawer geometry, sheet-vs-pane switching, fold transitions |
| `KNOWLEDGE_UX_SPEC.md` | The knowledge list, search and filters that the attach picker reuses; the note/file viewer that a source opens into; what counts as a "file" |
| `OBJECT_LIFECYCLE_SPEC.md` | What rename and delete do to data (cascades, edges, residue, persistence). This spec states only the UI contract |
| `UX_TEST_PLAN.md` | Test harness, device matrix, fold acceptance tests. §25–§26 here list what the chat needs from it |
| `MAC_UX_LAB_PLAN.md` | Preview/fixture tooling |

---

## 0. The chat in one screen

> **Say what Skein is doing, in words a person uses, and never say anything it isn't doing.**

| | Today (`main`) | Proposed |
|---|---|---|
| Header | Literal `chat` + `⚹ context` (`ChatScreen.kt:79-82`) | Title over `Qwen 2.5 3B · Local ▾`; ✎ and ⋮ |
| Model identity | `qwen2.5-3b-instruct-abliterated-q3-k-m-2c5f9a121ae6 · ●` in the command bar (`DEVICE_BEFORE_PASS.md` P01) | `Qwen 2.5 3B`; details only in Model details (§19) |
| Waiting for an answer | Static `thinking…` for up to 11 minutes (`CMS-P1-03`) | **Activity block:** real steps (`Starting Qwen 2.5 3B` → `Used 3 notes` → `Reading your message · 1:08` → writing), then `▸ Worked for 1m 31s · 3 sources` (§9) |
| Reasoning | None; `<think>` would fold into `Thinking` (`ChatTurnState.kt:15-22`) | A separate lane, only when the model really emits reasoning (§9.6) |
| Stop | `■`, silently ignored before the stream registers (`CMS-P1-04`) | Stop that always works, with a visible `Stopping…` (§9.8, §10) |
| Errors | `generation failed, retry` banner at the top; retry duplicates the message (`CMS-P1-05`) | An inline card on the failed turn in product language; Try again reuses the message (§10) |
| Chats | All titled `Chat`; can't reopen, rename or delete (`CMS-P0-02/03`) | Titled from the first message; grouped history; rename and delete with the prompt §29 dialog (§12–§15) |
| Composer | `$ · field · 📎 · ⏎`; soft Enter can't make a newline; 📎 only inserts `[[name]]` text | `＋ · Ask Skein… · Send/Stop`; context chip above; `/` and `[[` as power paths (§7–§8) |
| Context | `score 0.87 · recalled by: vector, lexical`, from any chat (`CMS-P1-06`) | Context chip → context inspector (sheet / extra pane) in product language (§17) |
| In-flight answer | Lost when the chat leaves composition (`CMS-P0-01`) | Owned by a session-scoped turn controller; survives fold, navigation and chat switching (§28 bead C1) |

---

## 1. Principles specific to chat

1. **Truthful activity.** Every step shown maps to an event that fired. No step is shown before its event, no pending `○` rows are shown for steps that may never happen, and no reasoning is fabricated (§9.1).
2. **No dead air.** From Send to the first answer word, something truthful is always on screen and a clock is running (POCKETPAL W4).
3. **One next action per state.** Every error, empty state and warning offers exactly one primary action (prompt §2.6).
4. **Product language.** No `score`, `recalled by`, `GGUF`, `prefill`, `token`, `context window`, `retrieval`, model id or file name on a Level 1 surface. Technical numbers live in expanded detail and the inspector (IA §3.1).
5. **Local is normal.** `Local` is a label, not an achievement. No badges, no engine logos (JAN N-J2).
6. **Hide what can never work; disable with a written reason what is temporarily unavailable** (LOBECHAT L8 adapted to prompt §2).
7. **The conversation owns the width.** Chrome yields before messages do (LOBECHAT L10).
8. **State survives the device.** Draft, in-flight turn, activity expansion, scroll position and open inspector survive fold, unfold, rotation, chat switching and process death (prompt §24). Mechanics: `ADAPTIVE_LAYOUT_SPEC.md`; ownership: bead C1.

---

## 2. Copy: vocabulary and every string that changes

### 2.1 Vocabulary (extends IA §3.1)

| Concept | Say | Never say (Level 1) |
|---|---|---|
| The assistant | **Skein**, or the persona's name once personas exist | assistant, model, bot |
| The user | **You** | user |
| A conversation | **chat** | thread, session, tab, conversation document |
| Documents given to the model for this answer | **used** ("Used 3 notes") | retrieved, recalled, context items |
| Documents the answer actually cites with `[N]` | **sources** ("3 sources") | citations, chunks |
| A cited span | **passage** | chunk, excerpt |
| Automatic search of the vault | **Knowledge on / off** | RAG, retrieval |
| Prompt processing | **Reading your message** | prefill, prompt eval, tokenizing |
| Model load | **Starting ‹model›** | loading weights, mmap, warm-up |
| Generation | **Writing** | generating, decoding, inference |
| Model's think-span | **Thinking**; the text is **Model's reasoning** | chain of thought, CoT, `<think>` |
| Context budget | **room** ("This chat is running out of room") | context window, n_ctx, tokens |
| Model runs here | **Local** | on-device inference, llama.cpp, CPU |

"Used" and "sources" are deliberately different words for different sets: *used* = what went into the prompt (`AssembledPrompt.citations`); *sources* = what the answer cites (markers in the answer ∩ that offer). The numbers can differ and the words tell the user why.

### 2.2 Current strings → new strings

| Where | Today | New |
|---|---|---|
| Header | `chat` | the chat title; `New chat` before the first send |
| Header toggle | `⚹ context` | removed; the context chip (§8) |
| Pre-answer | `thinking…` | activity block (§9) |
| Stop badge | `interrupted — stopped` | summary `▸ Stopped after 42s` |
| Error banner | `generation failed, retry` | inline error card, mapped per cause (§10.3) |
| Error banner | `model process restarted, retry` | `The model stopped unexpectedly.` · **Try again** |
| Retry button | `retry` | **Try again** |
| Context panel title | `context` | `Context` (inspector title) |
| Context empty | `no retrieved context for this turn` | `No notes were used for this answer.` |
| Context row | `recalled by: vector, lexical` / `score 0.87` | hidden; Details says `Found by meaning` / `Found by matching words` / `Linked note` |
| Composer glyph | `$` | removed |
| Composer | (no placeholder) | `Ask Skein…` |
| Attach | `📎` (no label) | `＋`, content description `Add to this chat` |
| Send / Stop | `⏎` / `■` (no labels) | icons with `Send` / `Stop answer` descriptions |
| No model | `No model yet.` / `Use /import model to add one…` | card `Add a model to start` · **Choose a model file** (§11) |
| Chat title | `Chat` | provisional title from the first message (§13) |

All chat strings go through string resources with plurals (`1 note` / `2 notes`) from day one (JAN N-J13).

---

## 3. Anatomy per window class

Window classes and measured sizes come from IA §3.4 and `DEVICE_BEFORE_PASS.md`: outer display **Compact** (~443 dp at stock density, ~524 dp at the owner's 330 dpi), inner display **Expanded** (~852–1043 dp), **Medium** only at larger Display size or on tablets. Layout mechanics (pane widths, what yields, sheet vs pane) are `ADAPTIVE_LAYOUT_SPEC.md`'s. The reading column cap (~720 dp) is from IA §3.4.

### 3.1 Compact (Fold outer, phones, split-screen halves ≥ 320 dp)

```text
┌──────────────────────────────────┐
│ ☰  Mycology research       ✎  ⋮ │  header: title (1 line) over subtitle (1 line)
│    Qwen 2.5 3B · Local ▾         │  subtitle = button → model sheet
├──────────────────────────────────┤
│                              You │  author label
│              ┌─────────────────┐ │
│              │Which substrate  │ │  user: right-aligned tonal bubble
│              │won?             │ │
│              └─────────────────┘ │
│ Skein                            │  assistant: unbubbled, full column
│ ▸ Worked for 1m 31s · 3 sources  │  activity summary (collapsed)
│ Straw colonised in 14 days [1],  │
│ hardwood in 21 [2]…              │
│ 3 sources            ⧉   ↻   ⋯   │  footer: sources · Copy · Try again · More
│                                  │
│              ┌─────────────────┐ │
│              │ Jump to latest ↓│ │  only when scrolled up and new content exists
│              └─────────────────┘ │
├──────────────────────────────────┤
│ ◉ 2 notes · Knowledge on         │  context chip (≥ 48 dp target)
│ ＋  Ask Skein…                 ↑ │  composer: ＋ · field (1–6 lines) · Send/Stop
└──────────────────────────────────┘
```

### 3.2 Medium

As Compact, inside a navigation rail, with the conversation column capped at ~720 dp and centred. The inspector is a levitated side sheet over the chat (`ANDROID_ADAPTIVE_SAMPLES.md` §4.4). The Conversations list is reached through the rail's Chat item or Back.

### 3.3 Expanded (Fold inner, any orientation)

```text
┌──┬───────────────────┬──────────────────────────────────────────┐
│✎ │ ⌕ Search chats    │ Mycology research                  ⌕  ⋮  │
│  │ Today             │ Qwen 2.5 3B · Local ▾                    │
│💬│▌Mycology research⋮│──────────────────────────────────────────│
│📚│  Straw beat hardw…│        (column capped at ~720 dp)        │
│✦ │  RAG architecture⋮│ You  Which substrate won?                │
│▦ │  Retrieval fuses… │ Skein                                    │
│  │ Yesterday         │ ▸ Worked for 1m 31s · 3 sources          │
│  │  Continue repo… ◌ │ Straw colonised in 14 days [1]…          │
│⚙ │  Reading your…    │ 3 sources                     ⧉  ↻  ⋯    │
│  │                   │──────────────────────────────────────────│
│  │                   │ ◉ 2 notes · Knowledge on                 │
│  │                   │ ＋  Ask Skein…                        ↑  │
└──┴───────────────────┴──────────────────────────────────────────┘
 rail  Conversations     Chat
```

`◌` = a chat that is still answering in the background (§12.3). Opening the inspector gives `rail | Chat | Context`; Back restores `rail | Conversations | Chat` (IA §3.4).

### 3.4 Compact height (outer landscape, any window < 600 dp tall, or the soft keyboard up on Compact)

- The header keeps one line: the title only; the subtitle folds into the ▾ next to the title (still a button).
- The composer caps at 3 lines.
- A running activity block shows only its active step line (§9.11).
- The context chip stays; the `Jump to latest` pill sits above the chip.

### 3.5 Region ownership

| Region | Content and behaviour (this spec) | Geometry (`ADAPTIVE_LAYOUT_SPEC.md`) | Styling (`DESIGN_SYSTEM.md`) |
|---|---|---|---|
| Header | §4 | top app bar height, inset handling | type roles, icon buttons |
| Message list | §5, §6 | column cap, gutters | bubble, code block, chip styles |
| Activity block | §9 | none (inline) | step row, window, summary row |
| Context chip | §8 | position above composer | chip style |
| Composer | §7 | IME insets, max height | field, buttons |
| Inspector, sheets, dialogs | §16, §17, §14, §15 | sheet vs pane, widths | sheet and dialog components |

---

## 4. Header

### 4.1 Layout

| Window | Leading | Title block | Trailing |
|---|---|---|---|
| Compact | `☰` (opens drawer) | title over subtitle | `✎` New chat (hidden on an unsaved new chat), `⋮` (hidden on an unsaved new chat) |
| Medium / Expanded | none (rail) | title over subtitle | `⌕` (Search or run a command), `⋮` |

`✎` is in the rail on Medium/Expanded, so the header doesn't repeat it. `⌕` is omitted on Compact (see §23 issue 1).

### 4.2 Title

- The chat's stored title (§13), or `New chat` before the first send (no document exists yet; §11.4).
- One line, end ellipsis. The full title is in the content description and the rename dialog.
- Semantics: `heading()`.
- **Not a tap target.** One target, one purpose: rename lives in ⋮, the list and the palette. The subtitle is the header's only in-block button (LOBECHAT L11 rejects double-duty targets).

### 4.3 Subtitle: the model label

Format: `‹friendly name› · ‹where› ▾`, where the name comes from §19 and `‹where›` is `Local` in v1. The subtitle names the model that will answer the **next** message (in v1, the default model; see §16.3). States:

| Engine state (`ManagedInferenceEngine.status`) | Subtitle | Notes |
|---|---|---|
| `READY` / `GENERATING` | `Qwen 2.5 3B · Local` | Generating is shown by the activity block, not the header |
| `LOADING` | `Qwen 2.5 3B · Starting…` | |
| `UNLOADED`, default set | `Qwen 2.5 3B · Local` | Starting on send is normal; no "not loaded" warning (JAN §6.1) |
| Switch pending during a generation | `Gemma 4 E4B · Switches after this answer` | §16.3 |
| `ERROR` (last load failed) | `Qwen 2.5 3B · Couldn't start` | Error colour on the second part only; the sheet explains |
| Default's file missing | `Qwen 2.5 3B · Not available` | |
| No model installed | `No model · Add one` | Button opens import directly |
| Import in progress, no other model | `Getting Qwen 2.5 3B ready · 42%` | Only while no model is usable |

**Truncation.** The name part truncates with an end ellipsis; the `· Local ▾` part never truncates (LOBECHAT L3). At font scale 2.0 on a 443 dp screen, `Qwen 2.5 3B · Local ▾` still fits; a 40-character name shows as `Qwen 2.5 Coder 32B Inst… · Local ▾`.

**Semantics.** `Role.Button`, content description `Model: Qwen 2.5 3B, local. Change model.` (plus the state when not ready).

### 4.4 ⋮ menu

On a saved chat, in order:

1. **Rename…** (§14)
2. **Context** — opens the inspector (§17); the chip does the same, this is the keyboard/TalkBack-findable twin
3. — divider —
4. **Delete chat…** — destructive style, last (§15)

Not in v1: Pin, Archive, Duplicate, Export, Share. Archive needs an `archived_at` column (G16); export of chats is not verified to work; they appear only once they work end to end (prompt §2).

On an unsaved new chat (landing), ⋮ is hidden: nothing in it applies.

---

## 5. Message list

### 5.1 Author distinction

| | User message | Assistant answer |
|---|---|---|
| Alignment | End (right in LTR) | Start, full reading column |
| Container | Tonal bubble (`DESIGN_SYSTEM.md` role), max width 80 % of the column or 560 dp | **No bubble.** Text on the surface (POCKETPAL A2: only the user's words are boxed) |
| Author label | `You`, small, above the bubble | `Skein` (or the persona name once personas exist), small, above the activity block |
| When the label shows | At the start of every turn (each turn alternates authors) | Same |
| Model tag | none | `· Gemma 4 E4B` after the label **only** when this answer's model differs from the chat's current model (from `messages.model_id`; §16.3) |

The bubble's colour no longer signals queued vs picked up (`SentMessageState`, colour-only, `CMS-P2-03`); the activity block carries state.

Labels stay visible at every size because (1) at font scale 2.0 the user bubble fills the width and alignment alone stops working, (2) the activity block needs an anchor, and (3) the persona name must be visible once personas exist. They are quiet (`labelMedium`, `onSurfaceVariant`).

### 5.2 Assistant turn anatomy (top to bottom)

1. Author label (`Skein`).
2. **Activity block** (§9): live steps while working; the collapsed summary once done (subject to the calm rule, §9.5).
3. **Answer body**: Markdown (§5.6).
4. **Footer row**, 48 dp tall, only on completed, stopped or failed turns:
   - leading: the **sources chip** — `3 sources` (cited) or `Used 2 notes` (used but not cited); absent when nothing was used;
   - trailing icon buttons: **Copy** (`⧉`), **Try again** (`↻`, latest turn only; §10.2), **More** (`⋯`);
   - the answer pager `‹ 2 of 2 ›` when this turn has more than one answer (§10.2).

The footer appears on every completed answer, not only the latest: touch has no hover (CONTINUE, LIBRECHAT L1). It shares a row with the sources chip, so answers with sources pay no extra height.

### 5.3 Message menus

Long-press on a message, or its `⋯`, opens the same menu (sheet on Compact, anchored menu on Expanded):

| Assistant answer | User message |
|---|---|
| Copy | Copy |
| Select text | Select text |
| Save as note | Edit as new message |
| Try again (latest turn only) | |
| Sources and activity (opens the inspector focused on this answer) | |

- **Copy** copies the Markdown. `[N]` markers stay, and a `Sources` list with titles is appended (no ids). The clip is marked sensitive (`ClipDescription.EXTRA_IS_SENSITIVE`) so the Android clipboard preview doesn't show vault content. Snackbar: `Copied`.
- **Select text** opens the message in a full-height sheet inside a `SelectionContainer`. This keeps long-press free for the menu and fixes `CMS-P1-08` without the Robolectric magnifier issue (`AssistantBubble.kt:168-176`).
- **Save as note** creates an `aiout` document. Title: the §13 title algorithm run on the answer (first heading, else first sentence). Body: the answer, with each cited `[N]` kept and a `Sources` section of `[[Title]]` wikilinks, so the saved note joins the graph. Frontmatter records `source_chat` and `source_message` ids. Snackbar: `Saved as note "‹title›"` · **Open**. The note survives the chat's deletion (§15.1).
- **Edit as new message** copies the text into the composer and focuses it. History is not modified: messages are immutable in v1 (`LIFECYCLE_FINDINGS.md` §15.2). The old message stays; sending creates a new turn.

### 5.4 Citations in the answer

- An inline `[N]` whose N is in the turn's offer renders as a small chip; out-of-offer markers stay plain text (unchanged rule, `AssistantBubble.kt:92-114`).
- **Tap → source peek**, not navigation: a bottom sheet (Compact) or popover (Expanded) with the source title, its kind (`Note` / `File` / `Chat`), the cited passage, and **Open**. **Open** pushes the note or file at the passage (§17.6). Today's first-tap-navigates / second-tap-expands rule (`ChatViewModel.kt:265-278`) is retired; its second half was unreachable (`CMS-P1-07`).
- Touch target: inline chips use WCAG 2.5.8's inline exception (their size is constrained by the line height). The visual is ≥ 24 dp and the hit area is extended to ≥ 32 × 40 dp without overlapping neighbours. The **48 dp route to every source** is the footer's sources chip.
- Multi-digit markers (`[10]`) size to content (today's fixed 32 sp placeholder clips them). Grouped `[1, 2]` becomes two chips.
- A source whose document was deleted renders `[N]` greyed; the peek says `This source was deleted.` with no **Open** (G9).
- Semantics: `Role.Button`, content description `Source 1: Oyster log`.

### 5.5 Links in answers

URLs are shown in full on tap, never opened directly: a peek with the full URL and **Copy link** / **Open in browser**. Model output can carry prompt-injected links that exfiltrate data through query strings, and Skein Core has no network, so opening a browser is an explicit user decision, never a single tap.

### 5.6 Markdown rendering (chat side)

| Element | Rule |
|---|---|
| Parsing | Parse the **whole** answer once, with citation markers as inline nodes. Today each fragment between markers is parsed separately, which breaks bold, lists and code around a citation (`AssistantBubble.kt:116-134`, `CMS-P1-10`) |
| Code blocks | Monospace; horizontally scrollable (never wrapped); language label if given; **Copy** button (48 dp) in the block header; max visible height 60 % of the window, then scroll inside |
| Inline code | Monospace, no wrap-breaking inside short spans |
| Tables | Horizontally scrollable grid; header row sticky within the block. `core/markdown` renders tables as raw pipes today (`MarkdownAst.kt:137`); rendering is ask **P2** |
| Lists | Nested indentation up to 4 levels (16 dp each), then flattened at level 4 with a bullet glyph; ordered-list numbers use tabular figures |
| Headings | Scaled down one step inside answers (an answer `#` is not a screen title) |
| Block quotes | Leading rule, muted text |
| Task lists | Read-only check glyphs; not tappable (they aren't the user's note) |
| Images | Not rendered in v1 (no network; attachments aren't images yet) |

### 5.7 Huge messages

- User messages over 20 lines or 1,500 characters collapse to 8 lines with **Show all**. Pasted text keeps its newlines.
- Assistant answers never collapse, but render as block-level lazy items (§22) so a 20,000-character answer scrolls smoothly.
- A single unbroken token (a hash, a URL) wraps anywhere rather than overflowing.

---

## 6. Scroll behaviour

1. **Bottom-anchored list.** `LazyColumn(reverseLayout = true)`, newest at the bottom. Opening a chat lands at the latest message. Opening the soft keyboard keeps the bottom anchored.
2. **Follow only when at the bottom.** "At the bottom" means the last item's bottom edge is within 48 dp of the viewport bottom. While at the bottom, growth of the live turn follows.
3. **Stop following once the answer's start would scroll off.** When the streaming answer's first line reaches the top of the viewport, following stops so the reader can read from the top; the `Jump to latest ↓` pill appears. On a 443 dp screen this is what keeps a long answer readable while it streams.
4. **Scrolling up stops following** immediately.
5. **`Jump to latest ↓`** appears when not at the bottom **and** there is content below (new or streaming). Tap scrolls to the bottom and resumes following. It is a 48 dp pill above the context chip, with content description `Jump to latest message`.
6. **Sending always jumps to the bottom** and resumes following.
7. **Returning via Back** (from a source, the inspector or another destination) restores the exact scroll position for that chat. Position is per-chat UI state (`ADAPTIVE_LAYOUT_SPEC.md` §5.6 row 3 equivalent).
8. **No jump when the live answer becomes the stored answer.** The live item and the persisted row must have the same height and position in the frame where one replaces the other (no fade, no reflow). Today's guard (`MessageList.kt:105-114`) prevents doubling; this adds "no visible jump" (AC-12).
9. **Keyboard.** With focus in the list, Page Up/Down scroll by a viewport, End jumps to latest, Home to the first message.

---

## 7. Composer

### 7.1 Anatomy

`＋ · text field · Send/Stop`, one row, with the context chip (§8) directly above. Minimum height 56 dp; every control has a 48 dp target.

- **Field:** multi-line. Grows to 6 lines (Compact), 8 lines (Medium/Expanded) or 3 lines (Compact height), then scrolls internally. It never pushes the message list below 40 % of the window height; at large font scales the line cap shrinks to honour that (`CMS-P1-11`: today it grows without bound).
- **Placeholder:** `Ask Skein…`, always, including when no model is installed (drafting is allowed; IA §3.7). No sigil hints in the placeholder: `[[` and `/` are Level 2 and are discovered through ＋ and the palette.
- **Removed:** the decorative `$` (`CMS-P2-01`) and the `[[` popup's `Create "x"` row in chat (`CMS-P0-07`). Creating notes belongs to Knowledge; the popup instead offers `Search Knowledge for "x"`.

### 7.2 ＋ opens "Add to this chat"

One tap on ＋ opens the attach picker directly. There is no intermediate menu, because a chooser before the picker costs a tap for nothing (LIBRECHAT L10's "unified" mode). Picker content is §18.

Hidden until it works end to end: **Photo** (image import throws `UnsupportedOperationException`, `LIFECYCLE_FINDINGS.md` §5; vision needs a vision model), **Skills**, **Tools**.

### 7.3 Send ↔ Stop

One slot, one control at a time.

| State | Slot shows | Enabled | Content description |
|---|---|---|---|
| Field blank | Send | no | `Send` |
| Field has text, a model is usable (any state but "none") | Send | yes | `Send` |
| No model installed / default unavailable | Send | no; the no-model card (§11) explains | `Send, unavailable: add a model first` |
| This chat's turn queued, starting, searching, reading, thinking or writing | **Stop** | yes | `Stop answer` |
| Stop tapped, not yet landed | Stop with a progress ring | no | `Stopping` |
| Another chat is answering | Send (sending queues, §9.2 step 0) | yes | `Send` |

Sending while the model is `Starting…` is allowed; the turn shows `Starting ‹model›`.

### 7.4 Keys

| Input | Soft keyboard | Hardware keyboard |
|---|---|---|
| Enter | Sends (owner's Fold smoke #2 preference, `ChatBottomBar.kt:151-156`), **unless** Settings › Chat › *Enter key sends* is off, in which case it inserts a newline and only the Send button sends | Sends |
| Shift+Enter | n/a | Newline |
| Ctrl+Enter | n/a | Always sends |
| Enter while an IME composition is active (CJK, voice) | Never sends; commits the composition | Same |
| Enter while the `[[` or `/` popup is open | Chooses the highlighted row | Same |
| Paste with newlines | Newlines kept (only a *typed* trailing newline sends) | Same |
| ↑ in an empty field | n/a | Recalls the last message you sent in this chat, editable (CONTINUE C14) |
| Esc | n/a | Closes a popup; else stops a generating turn in this chat; else leaves the field (CONTINUE §8 ladder) |

The *Enter key sends* setting fixes "a soft keyboard cannot type a multi-line prompt" (`CMS-P1-11`) without reversing the owner's preference. It lives in Settings; the chat spec only requires it.

### 7.5 `/` → the command palette, chat scope

- **Trigger:** `/` typed as the first character of an empty field (today's rule, `ChatBottomBar.kt:168`, now actually wired; `CONTINUE.md` §9).
- **Container:** the palette's chat-scope view, anchored above the composer: a sheet on Compact, a popup on Expanded. The palette itself is Wave 10's (`CONTINUE.md` "Proposed Skein command palette").
- **Chat-scope commands** (only the available ones are listed): New chat · Rename chat… · Delete chat… · Add to this chat… · Import file… · Show context · Switch model… · Stop answer (while generating) · Try again (when §10.2 allows) · Copy last answer.
- **Dismiss** (Back, Esc, or typing a space after `/`): the palette closes and the typed text stays as plain text (a user may be typing `/etc/hosts`). Once a space follows the slash, it is ordinary text.
- Choosing a row **runs** it and clears the `/`.
- Raw `/keyword arg` typing still works for power users through the same registry.

### 7.6 `[[` → attach inline

`[[` anywhere opens the Knowledge picker as the existing autocomplete popup (KNOWLEDGE_UX_SPEC owns list and ranking). Choosing an item:

1. inserts an inline token that renders as a chip `[[Oyster log]]` inside the field; and
2. **attaches that document to this message** (scope *This message*; §18.1).

Deleting the chip detaches it. Sent messages keep the `[[Title]]` text, so the transcript still reads naturally and the wikilink edge forms as today.

### 7.7 Drafts

Each chat keeps its own draft: text, caret, and pending `[[` tokens. The draft survives chat switching, fold/unfold, rotation, process death and a vault lock, using the IA's recommended encrypted draft row (IA §6.3, D7; ask **P4**). A draft is never written to the saved-state Bundle. Sending clears it. The landing's unsaved new chat has a draft too.

---

## 8. Context chip

A single chip directly above the composer that summarises what Skein will read with the next message. Visual height 32 dp, touch target 48 dp. **Tap → context inspector** (§17). It replaces `⚹ context` (`ChatScreen.kt:80`).

### 8.1 Copy grammar

`[attachments] · [knowledge] [· warning]`

- **Attachments** (items attached to this chat plus `[[` items in the draft): counts by kind, notes first, then files, then chats: `1 note`, `2 notes · 1 file`. What counts as a "file" follows `KNOWLEDGE_UX_SPEC.md`'s object model (a note imported from a PDF is a file there; IA §3.1).
- **Knowledge:** `Knowledge on` or `Knowledge off`. On is the default, from Settings › Knowledge & search.
- **Warning:** only when real (below).

| State | Chip text | Leading icon |
|---|---|---|
| Nothing attached, Knowledge on (pristine) | `Knowledge on` | knowledge icon, quiet tonal chip |
| Attachments, Knowledge on | `2 notes · 1 file · Knowledge on` | knowledge icon |
| Knowledge off | `Knowledge off`, or `1 file · Knowledge off` | crossed icon, outlined chip (never colour alone) |
| An attachment still preparing for search | `1 file preparing…` replaces its count | small progress ring |
| An attachment was too long to use whole | `2 notes · 1 file · ⚠` | warning icon; the inspector row says `Using the most relevant parts` |
| Chat running out of room (≥ 75 % of the budget, or earlier messages were left out) | unchanged text; Expanded appends `· Running out of room` | usage ring |
| While this chat is answering | unchanged | unchanged; the inspector opens read-only |

### 8.2 Truncation (Compact, font scale ≥ 1.5)

1. Drop `· Knowledge on` (the default).
2. Merge counts into `3 items`.
3. Never drop `Knowledge off`, a preparing state or a warning.

Content description always carries the full state: `Context: 2 notes, 1 file, knowledge on. Double-tap to inspect.`

### 8.3 "Knowledge on/off"

- Per chat, stored with the chat (ask **B9**; the chat's frontmatter needs no migration).
- Off means `SendPipeline` skips `retrieveContext` (a chat-side change, bead C2). Attached items are still used, because the user asked for them explicitly.
- Toggled in the inspector's Knowledge section (§17.3) and by the palette (`Knowledge on/off`), not by tapping the chip. The chip is an entry point, not a switch: a mis-tap on a switch changes what the model reads without the user noticing.

---

## 9. Activity and reasoning block

The owner's reference is PocketPal's live reasoning card (`pocketpal-reasoning.png`): *"I like how it shows the reasoning as it's thinking of a response."* The owner's framing is *"skein is much more complicated, workspace knowledge graph."* So the block has two lanes. **Lane A** shows the real work Skein does before and during an answer; this is where a knowledge workspace differs from a plain chat. **Lane B** shows the model's own reasoning, PocketPal-style, and appears only when the model really produces it. The design is `POCKETPAL.md` A1, adopted, with the timing half from `JAN.md` J5–J8.

### 9.1 Rules

1. **Event-driven.** A step row exists only after its event fired; its label changes only when its event says so. No predicted `○` rows.
2. **No fabrication.** Lane B renders only text the engine classified as reasoning. It is never inferred from answer text, never summarised by another model call, and never shown for a model that emits none. The default Qwen 2.5 3B emits none, so **today Lane B never appears**.
3. **Not a bubble.** The block is metadata on the chat surface above the answer (POCKETPAL W1).
4. **One clock.** Durations are measured client-side from the Send tap on a monotonic clock (`elapsedRealtime`), stamped at event receipt (ANYTHINGLLM A8).
5. **Numbers only when meaningful.** Elapsed time appears from 1 s; counts under 10 tokens are suppressed; rates appear only in expanded detail (POCKETPAL W6, N5).
6. **Calm.** No shimmer, glow or bounce. Expand/collapse is a ~200 ms standard ease; it and the active dot's pulse obey the system's remove-animations setting (POCKETPAL N2–N3).

### 9.2 Lane A step catalogue

The label is the live form; the done form replaces it when the step ends. Signal names refer to code on `main`.

| # | Step | Live label | Done label (+ detail line) | When omitted | Signal today | Status |
|---|---|---|---|---|---|---|
| 0 | Waiting | `Waiting for "‹other chat›" to finish` · action **Stop that answer** | `Waited 12s` | No other turn running | none (one engine, `InferenceException.Busy`) | Chat-side: session turn queue (bead C1) |
| 1 | Starting model | `Starting Qwen 2.5 3B` (indeterminate) | `Started Qwen 2.5 3B · 3.4s` | Model already `READY` at send | `SendPipeline.send` → `warmUp()` (`SendPipeline.kt:211`) → `ManagedInferenceEngine.status` `LOADING`→`READY` (`ManagedInferenceEngine.kt:83-92`) | Exists; needs a per-turn event (C2). Load % is optional ask **B14** |
| 2 | Knowledge | `Searching Knowledge` | `Used 3 notes` / `Used 2 notes and 1 file` / `Nothing relevant found` (detail: `7 passages from 3 notes`) | Knowledge off | `retrievalService.retrieveContext` (`SendPipeline.kt:214`); counts from `AssembledPrompt.citations` (what survived the budget), grouped by `docId` | Exists; needs events (C2) |
| 3 | Attachments | `Reading 2 attached notes` | `Read 2 attached notes` (detail: `1 used in part: too long`) | Nothing attached | none | After ask **B9** |
| 4 | Reading | `Reading your message` · `1:08` (detail: `with 3 notes and 4 earlier messages`). Indeterminate bar. After 15 s, a second detail line: `This can take a few minutes on this device.` | `Read your message · 1m 12s` (Level 2 detail: `3,412 tokens`) | never | Start: `engine.stream` subscribed (`SendPipeline.kt:243`); end: first `Token.Text` of any kind. Total tokens: `Token.Done.promptTokens` (`Inference.kt:121-133`), which `SendPipeline` discards today | Exists (indeterminate); determinate needs ask **B1** |
| 4b | Reading, determinate | `Reading your message · 2.1k of 3.4k · about 40s left` with a determinate bar | as 4 | ask **B1** absent | none (`InferenceService.kt` logs `prefill batch i/n`, `:516-539`) | Ask **B1** |
| 5 | Thinking | `Thinking · 12s` + Lane B window | `Thought for 12s` | Model emits no reasoning | none (`ChatTurnState.kt:15-22`: no reasoning case in `Token`/`Segment`) | Ask **B2** |
| 6 | Writing | Hidden in the step list while text streams (the text is the progress); the collapsed live summary reads `▸ Writing · 0:12` | `Wrote the answer · 15s` (Level 2: `212 tokens · 11.7 per second`) | never on success | first `Segment.Text` (`ChatViewModel.kt:202-205`); stats `Token.Done.generatedTokens`, `tokensPerSec` | Exists; stats need `SendPipeline` to keep `Token.Done` (C2) |
| 7 | Stopping | Header becomes `Stopping…`; active step detail `Finishing the current step` | — | Stop not tapped | local tap; ends at `StopReason.CANCELLED` (`SendPipeline.kt:253, 268`) | Chat-side: pre-stream cancel (C2); latency is §9.8 |

Terminal outcomes (replace the header):

| Outcome | Summary line (§9.5) | Answer slot |
|---|---|---|
| Completed | `▸ Worked for 1m 31s · 3 sources` | the answer |
| Stopped with text | `▸ Stopped after 42s` | partial answer, kept (`INTERRUPTED_MARKER`, `SendPipeline.kt:82`) |
| Stopped before any text | `▸ Stopped before answering` | nothing else; footer offers **Try again** |
| Hit length limit (`StopReason.LENGTH`) | `▸ Worked for 58s · 2 sources` | answer + note `The answer was cut short.` · **Ask to continue** (prefills `Continue` in the composer; the user sends it, so nothing is sent on the user's behalf) |
| Failed | `▸ Couldn't finish` | the error card (§10.3) |
| Reasoning only, no answer | `▸ Thought for 2m 10s · Couldn't finish` | `The model used all its room thinking and didn't answer.` · **Try again** |

Future steps plug into the same event contract and are **not shown until they exist**: *Reading ‹note›*, *Searching ‹repository›*, *Following links in your notes*, *Running ‹skill›* (with approval in the step), *Using ‹tool›*, *Sending to ‹provider›*.

### 9.3 Live state

```text
Compact, Qwen 2.5 3B (no reasoning)          Compact, reasoning-capable model
┌──────────────────────────────────┐         ┌──────────────────────────────────┐
│ Skein                            │         │ Skein                            │
│ Working · 1:12                ▾  │         │ Working · 0:22                ▾  │
│ ✓ Started Qwen 2.5 3B      3.4s  │         │ ✓ Read your message        9.8s  │
│ ✓ Used 3 notes             0.4s  │         │ ● Thinking                  12s  │
│ ● Reading your message     1:08  │         │ ┌ Model's reasoning ────────── ┐ │
│   with 3 notes and 4 earlier     │         │ │░░░░░░░░ (fade) ░░░░░░░░░░░░░ │ │
│   messages                       │         │ │Note [2] logs a 14-day        │ │
│   ▬▬▬▬▬▬▬▬▬▬▬▬ (indeterminate)   │         │ │colonisation on straw vs 21   │ │
│   This can take a few minutes    │         │ │on hardwood, so…              │ │
│   on this device.                │         │ └──────────────────────────────┘ │
├──────────────────────────────────┤         ├──────────────────────────────────┤
│ ◉ Knowledge on                   │         │ ◉ Knowledge on                   │
│ ＋  Ask Skein…                 ■ │         │ ＋  Ask Skein…                 ■ │
└──────────────────────────────────┘         └──────────────────────────────────┘
```

- **Header row:** `Working · m:ss`, ticking at 1 Hz, with a ▾/▸ toggle. It is ≥ 48 dp tall and is the collapse target.
- **Step rows:** `✓` done (with its duration, right-aligned in tabular figures), `●` active (with its live elapsed time). The active dot pulses unless animations are off.
- **The active step is always visible**, even when the block is collapsed.
- **The block is expanded while working, until the first answer word.** Then it **auto-collapses** to `▸ Writing · 0:12`, unless the user has touched the toggle for this turn (the "user toggle wins" latch, POCKETPAL W3). The latch is kept per message id and survives fold/unfold.

### 9.4 From live to done

| Moment | Block shows |
|---|---|
| Send tapped | `Working · 0:00`, then the first step's row |
| First answer word | collapses to `▸ Writing · 0:12` (unless latched open); the answer streams below |
| Done | `▸ Worked for 1m 31s · 3 sources` (or the calm rule hides it, §9.5) |
| Stop tapped | header `Stopping…`; Stop becomes a progress ring |
| Stop lands | `▸ Stopped after 1m 40s` |

### 9.5 The collapsed summary

**Grammar:** `▸ ‹verb› for ‹duration›` + `· N sources` (N > 0 cited documents) or `· used N notes` (used, none cited) + `· Thought ‹duration›` (Lane B ran) + outcome suffix for stopped/failed turns, per §9.2's table.

**Duration format:** under 10 s → `8.1s`; under 60 s → `42s`; under 1 h → `1m 31s`; else `1h 2m`. Spoken: `8.1 seconds`, `1 minute 31 seconds`. An unknown duration (a turn restored without stored timing) omits the duration and never shows a guess (JAN J6: "a few seconds", never a fake number).

**"Worked for"** is wall-clock from Send tap to the end of persistence. It includes waiting, model start, search, reading and writing: the number the user actually waited.

**Calm rule** (Level 1): the summary is hidden when the turn took under 3 s, used nothing, had no reasoning, finished normally and needed no model start. A short chit-chat reply then carries no `Worked for 1.2s` line. The threshold is a PROTOTYPE decision (owner question Q4).

**Why the sources count appears twice** (summary at the top, sources chip at the bottom): on long answers they are screens apart. The summary is the account ("what happened"), the chip is the way in ("show me"). The summary's count is not interactive; the chip is.

### 9.6 Lane B: the model's reasoning

- **Source:** reasoning segments classified by the engine side (ask **B2**). Never a UI heuristic.
- **Live window:** titled `Model's reasoning`. The label is honest: it is text the model generated, not verified steps. Bounded and bottom-anchored with a top fade: 6 lines on Compact, 8 on Expanded, 4 at Compact height. It auto-follows the newest line while Thinking. Text is muted, selectable, not Markdown-rendered (it can be half-formed), and wraps.
- **Tap the window** → it grows to full height inside the block (a 2-state toggle; POCKETPAL's 3-state cycle is simplified because the block's own ▾ already collapses).
- **First answer word** → Lane B collapses to `✓ Thought for 12s ▸` (unless latched), and the answer streams below.
- **Expanded detail after completion:** `Model's reasoning ▸` as a disclosure row; tapping it shows the full stored reasoning, selectable.
- **Never fed back.** Stored reasoning is excluded from the next turn's history, from RAG indexing, from citations, from backlinks and from Copy and Save as note (ask **B5**). It is not the answer and may contain wrong claims.
- **No "Think" toggle in v1.** It appears only when a template probe says the loaded model supports switching thinking on and off, and the switch is plumbed (POCKETPAL W8; ask **B2**'s optional part). No dead controls.

### 9.7 Expanded detail (tap the summary)

```text
▾ Worked for 1m 31s · 3 sources
  ✓ Started Qwen 2.5 3B                       3.4s
  ✓ Used 3 notes                              0.4s
     7 passages from 3 notes
  ✓ Read your message                       1m 12s
     with 3 notes and 4 earlier messages
  ✓ Wrote the answer                           15s
  Sources
     [1] Oyster mushroom log            Note  ›
     [2] Substrate notes                Note  ›
     [3] spec.pdf                       File  ›
  Model's reasoning                            ▸    (only if Lane B ran)
  Details                                      ▸    Level 2, collapsed:
     Qwen 2.5 3B · 3,412 tokens read · 212 written · 11.7 per second
```

- Rows are ≥ 48 dp; the summary row is `Role.Button` with `stateDescription` expanded/collapsed.
- A source row opens the source at its first cited passage (§17.6).
- On Expanded with the inspector open, the inspector's Activity section shows the same `TurnActivity` for the focused answer. It is a second view of one state, not a second set of controls.
- At Compact height the live block shows only `● Reading your message · 1:08 ▸ (2 done)`.

### 9.8 Stop mid-step

| Stop during | What happens | What the user sees | Latency |
|---|---|---|---|
| Waiting | Turn leaves the queue | `▸ Stopped before answering` | immediate |
| Starting model | The turn's job is cancelled; the model keeps loading in the background (a load can't be interrupted) | `▸ Stopped before answering`; header shows `Starting…` then `Local` | immediate |
| Searching Knowledge | Job cancelled | `▸ Stopped before answering` | immediate |
| Reading your message | `engine.cancel()`; the service checks between 512-token chunks (`InferenceService.kt:527-530`) | `Stopping…` with `Finishing the current step` until it lands | up to one chunk, several seconds on the Fold CPU |
| Thinking / Writing | `engine.cancel()` | partial text kept, `▸ Stopped after 42s` | next token |

Today a Stop tapped before the stream registers is silently dropped (`LlamaCppEngine.kt:474-478`, `CMS-P1-04`). The fix is chat-side: the turn controller cancels its own job in pre-stream phases and never starts the stream after a stop (bead C2). No inference change is needed. The *latency* during Reading is the engine's own chunk granularity; the UI's job is to be honest about it.

### 9.9 What persists

| Data | Stored with the message (encrypted in the vault) | Ephemeral |
|---|---|---|
| Outcome (completed / stopped / length / failed-with-text) | ✓ (ask **B5**; `INTERRUPTED_MARKER` already records "stopped") | |
| Total duration, per-step type + duration + counts | ✓ (**B5**) | |
| Model that answered | ✓ `messages.model_id` (column exists; `SendPipeline` never writes it, `CMS-P1-02` → C2) | |
| Tokens read / written, rate | ✓ (**B5**) | |
| Sources | ✓ citation-record-v1 (exists) | |
| Reasoning text + thought duration | ✓ separate from `content_md` (**B5**) | |
| Live elapsed, prefill fraction, ETA, active step | | ✓ |
| Expanded/collapsed, user-toggle latch | | ✓ per message id, session-scoped saveable UI state |
| Failed turn with no answer text (no assistant row) | | ✓. After a restart it is derived: the last message is yours and nothing is running → `No answer was saved.` · **Try again** |

**Until B5 lands:** turns from earlier sessions show no `Worked for` line (never a guessed duration). Stopped turns still say `▸ Stopped` (from the marker), and the sources chip still works (from citations).

### 9.10 Accessibility

- **One polite live region** on the block header. It announces:
  - each step **start**: `Starting Qwen 2.5 3B`, `Searching Knowledge`, `Reading your message`, `Thinking`, `Writing`;
  - the **outcome**: `Answer ready. Worked for 1 minute 31 seconds, 3 sources.` / `Stopped.` / `Couldn't finish: the model stopped unexpectedly.`
- **Never** announced: tokens, streaming text, the ticking clock, reasoning text, progress percentages. Steps shorter than 1 s are coalesced into the next announcement. At most one announcement per 2 s.
- The streaming answer is not a live region. When the answer completes, focus does not move; the outcome announcement tells TalkBack users it's ready.
- The collapsed summary: `Role.Button`, content description `Worked for 1 minute 31 seconds, 3 sources`, state `collapsed`, action label `Show activity`.
- Custom accessibility actions on every answer: `Copy`, `Try again` (when allowed), `Show sources`, `Show activity`, `Save as note`.
- With remove-animations on, the active dot is static and expand/collapse is instant.
- At font scale 2.0, step durations wrap below their labels instead of sharing a right-aligned column.

### 9.11 Outer screen and fold behaviour

- Compact shows the full step list while working (at most five rows in v1). At Compact height, or with the soft keyboard up, only the active step shows.
- Fold/unfold mid-step: the turn lives in the session turn controller (bead C1), so the block keeps ticking from the same start time; the expansion latch is restored by message id. Fold tests C and D (prompt §25) cover this.
- Lock mid-turn: the lock unloads the model and ends the turn. After unlock, the chat shows the partial answer if it was saved, or `No answer was saved.` · **Try again**. The idle lock must not fire during a generation (ask **B8**).

### 9.12 Implementation shape

A sealed `TurnActivityEvent`, emitted by `SendPipeline` next to the calls it already makes, reduced by a pure `reduce(state, event, nowMs)` into an immutable `TurnActivity`. The reducer returns the same instance for no-op events so nothing recomposes (POCKETPAL W7, whose sketch is in `POCKETPAL.md` A1). `ChatTurnState` stays the seam; the activity block replaces `ThinkingPlaceholder`. `skein-cmoe` ("thinking-orbs") is re-scoped to this block rather than an animation. The 200 ms `Thinking` ticker that recomposes the list five times a second for nothing (`ChatViewModel.kt:187-194`) is replaced by a 1 Hz clock that runs only while a block is on screen.

---

## 10. Stop, try again and errors

### 10.1 Stop

- The control is §7.3's slot. Esc also stops (focus in this chat; CONTINUE §8). Palette: `Stop answer`.
- The pressed state is immediate: `Stopping…`, with the slot disabled until the stream ends (POCKETPAL W5).
- After stopping, the partial answer is kept and copyable, and its footer offers **Try again**.

### 10.2 Try again (retry and regenerate)

v1 semantics, with **messages immutable** (`LIFECYCLE_FINDINGS.md` §15.2):

| Situation | Try again does | Visible result |
|---|---|---|
| Failed or stopped **before any text** (no assistant row) | Re-runs the turn **reusing the stored user message**: no duplicate row (fixes `CMS-P1-05`, whose duplicate is pinned by `ChatScreenTest.kt:318-325`) | A new answer under the same message |
| Latest answer **completed or stopped with text** | Runs a new answer for the same user message and **appends it after the old one** | The turn gets a pager `‹ 2 of 2 ›`; the new answer shows by default; the older one is one tap away |
| Any answer that isn't the latest turn | not offered | — |

**The adjacency rule.** An assistant row directly followed by another assistant row is *superseded*. `SendPipeline` drops superseded answers from the history it assembles, and the list groups consecutive assistant rows after one user row into one turn with a pager. This needs no schema change; the rule lives in `feature/chat` (bead C8). Disclosure: the chat's indexed transcript still contains both answers until a sibling link exists (optional ask **B11**).

Pager: `‹` `›` buttons of 48 dp with descriptions `Previous answer` / `Next answer` and label `Answer 2 of 2`. Copy, Save as note and Sources act on the answer shown.

Not in v1: *Try again with another model* (a swap costs 2–5 s plus a full re-read; POCKETPAL N13). Switch model in the model sheet, then Try again.

### 10.3 Error card

Errors render **inline, on the failed turn**, not as a banner at the top (`CMS-P1-05`: the banner sits far from the message and names no cause). Card: an error icon, one sentence, one primary action, and at most one secondary action. It stays until the user retries or sends a new message; it needs no dismiss because it scrolls with the conversation.

| Cause (`InferenceException` subclass, `Inference.kt:165+`) | Sentence | Primary | Secondary |
|---|---|---|---|
| `ModelNotLoaded` (no default model) | `No model is set up yet.` | **Choose a model** | — |
| `InvalidModel`, `HashMismatch`, `PostMmapHashMismatch`, `CompanionHashMismatch` | `Qwen 2.5 3B couldn't be started. Its file may be damaged or changed.` | **Choose another model** | Model details |
| `OutOfMemory` | `There wasn't enough memory to answer.` | **Try again** | Choose a smaller model |
| `ServiceDied` | `The model stopped unexpectedly.` | **Try again** | — |
| `ModelInUse`, `Busy` | should not reach the user (the turn queue handles it); if it does: `Skein was busy with another answer.` | **Try again** | — |
| `SessionLocked` | `Skein locked while answering.` | **Try again** | — |
| `TransactionTooLarge` | `This message and its notes were too large to send to the model.` | **Try again with fewer notes** (opens the inspector's Knowledge section) | — |
| `Internal` and unknown codes | `Something went wrong while answering.` | **Try again** | — |

Rules:
- Error copy never includes exception class names, codes or diagnostic strings (`sanitizeDiagnostic` output is logged, never shown; `CMS-P2-06`).
- A failure *after* some text keeps the partial text (today it is discarded, `SendPipeline.kt:196-199`). The card sits under it. This needs the pipeline to persist partial text on failure as it does on cancel (bead C2).
- `ModelNotLoaded` never offers Try again: it cannot help (`AUDIT` §4.11).

---

## 11. Empty states

Every empty state answers "what should I do next?" with one dominant action (prompt §33).

### 11.1 The landing: "What are you working on?" (IA §3.7)

The landing **is** an unsaved new chat. `✎ New chat`, app launch with no chats, and deleting the open chat all land here.

```text
Compact, model ready                     Compact, no model installed
┌──────────────────────────────────┐     ┌──────────────────────────────────┐
│ ☰  New chat                      │     │ ☰  New chat                      │
│    Qwen 2.5 3B · Local ▾         │     │    No model · Add one            │
├──────────────────────────────────┤     ├──────────────────────────────────┤
│                                  │     │                                  │
│  What are you working on?        │     │  What are you working on?        │
│  Ask Skein, search your          │     │                                  │
│  knowledge, or continue          │     │ ┌──────────────────────────────┐ │
│  something recent.               │     │ │ Add a model to start         │ │
│                                  │     │ │ Skein runs AI models on this │ │
│  ✎ New note    ⤓ Import file     │     │ │ device. Nothing leaves it.   │ │
│  ⌕ Search knowledge               │     │ │ [ Choose a model file ]      │ │
│                                  │     │ └──────────────────────────────┘ │
│  Recent                          │     │  ✎ New note    ⤓ Import file     │
│  Mycology research       9:41    │     │  ⌕ Search knowledge               │
│  Oyster mushroom log     Tue     │     │                                  │
├──────────────────────────────────┤     ├──────────────────────────────────┤
│ ◉ Knowledge on                   │     │ ◉ Knowledge on                   │
│ ＋  Ask Skein…                 ↑ │     │ ＋  Ask Skein…            (↑ off)│
└──────────────────────────────────┘     └──────────────────────────────────┘
```

- **Heading:** `What are you working on?`; supporting line `Ask Skein, search your knowledge, or continue something recent.`
- **Actions:** `New note` · `Import file` · `Search knowledge`, plus `Choose a model` when no model is ready (IA §3.7's four). They are text buttons, not a feature tour.
- **Recent:** up to 5 recent chats and notes (Compact: 3), opened by kind. Omitted when empty.
- **Composer focus:** with a hardware keyboard attached, the field is focused; with touch only, it is not auto-focused (auto-focus would cover the landing with the IME).
- **Model-ready variant:** the no-model card is absent; the composer is live.
- **Expanded with nothing selected:** the detail pane shows the landing; Recent shows up to 8 (IA §3.7).

### 11.2 The other states

| State | What shows | One next action |
|---|---|---|
| First run: no chats, no model | Landing with the no-model card | **Choose a model file** (SAF import). **Get models** appears only when Skein Hub is installed (JAN §6.2) |
| No chats, model ready | Landing, Recent shows notes only (or nothing) | Type in the composer |
| New chat (✎) | Landing | Type |
| Model starting | Landing or chat unchanged; subtitle `Starting…` | Type and send; the turn shows `Starting ‹model›` |
| Default model's file missing | Card: `Qwen 2.5 3B isn't available on this device anymore.` | **Choose a model** |
| Default model failed to start | Card: `Qwen 2.5 3B couldn't start.` | **Try again** · secondary **Choose another model** |
| Import in progress, no usable model | Card: `Getting Qwen 2.5 3B ready · 42%` with a determinate bar | none; the draft is kept and Send enables when ready |
| Conversations list empty (Expanded pane, Compact drawer section) | `No chats yet` + (keyboard attached) `Ctrl+N starts one` + `Chats are stored encrypted on this device.` | ✎ above |
| Chat search, no results | `No chats match "mycel"` | **Search Knowledge for "mycel"** |
| Inspector, nothing used or attached | `No notes were used for this answer.` in Knowledge | **Add to this chat** |

The no-model card replaces `No model yet. / Use /import model…` (`MainActivity.kt:567-586`) and the Models screen's `use /import model` text.

### 11.3 Where the no-model card lives

On the landing it sits under the heading (as drawn). In an existing chat whose model disappeared, it sits at the bottom of the conversation, directly above the context chip. Either way the chat history stays readable, the draft stays editable, and Send is disabled with the card as its written reason.

### 11.4 Create on first send

A chat document is created **when the first message is sent**, not on ✎ or `/chat` (today every `/chat` leaves an empty `Chat` row: `DEVICE_BEFORE_PASS.md` 06–08, `CMS` Flow 2). The landing is UI state plus a draft only. First send creates the document with its provisional title (§13) in the same step, then appends the message.

---

## 12. Conversation list and history

### 12.1 Where it lives

Compact: the modal drawer's **Chats** section under the destinations (IA §3.4). Medium: the list pane reached by Back or the rail. Expanded: the persistent **Conversations** pane.

### 12.2 Grouping and order

Sections, in order: **Today · Yesterday · Previous 7 days · Previous 30 days**, then one section per month (`August`; `July 2025` for other years). Sorted by the time of the last message, newest first (ask **B7**). Renaming never moves a chat (§14). Empty sections are omitted. Section headers are sticky on Expanded. No **Pinned** section in v1 (no storage for it).

### 12.3 Row anatomy

```text
┌──────────────────────────────────────────────┐
│ Mycology research                      9:41 ⋮│  title (titleSmall, 1 line) · time · ⋮
│ Straw colonised in 14 days, hardwood in 21…  │  preview (bodySmall, 1 line, muted)
└──────────────────────────────────────────────┘
```

- **Title:** 1 line, ellipsis.
- **Preview:** the latest message as plain text (Markdown, `[N]` markers and `[[ ]]` brackets stripped), 1 line. Prefixed `You: ` when the latest message is yours (an unanswered question). **Never** `user:` / `assistant:` (today's transcript preview, `TimelineFormatting.kt:34-56`). From the chat-summary projection (ask **B7**).
- **Time:** Today and Yesterday → `9:41`; Previous 7 days → `Tue`; older → `3 Sep`. Locale formats.
- **Answering:** a small progress ring replaces the time while a turn is running in that chat (JAN J12). Queued → `Waiting`. A turn that finished while you were elsewhere shows a dot until the chat is opened (session memory only).
- **Selected** (the open chat): a full-width pill in the secondary container colour (`pocketpal-drawer.png`), on both Compact and Expanded.
- **⋮:** visible on every row (touch has no hover; LIBRECHAT L1), 48 dp: **Rename…**, divider, **Delete chat…**. Long-press opens the same menu.
- Row height ≥ 64 dp; the whole row is the open target.

### 12.4 Search (title and content)

- **Expanded/Medium:** `⌕ Search chats` at the top of the list pane. Typing filters in place (150 ms debounce) into two sections: **Titles** (title matches) and **In messages** (content matches with a highlighted snippet). Tapping a message match opens the chat scrolled to that message, highlighted (ask **B7** returns message-level hits).
- **Compact:** the drawer's ⌕ opens the palette (§23 issue 6), whose result list has a **Chats** section with the same two kinds of hit.
- Clear (✕, Esc) restores the grouped list. The query is not persisted (LIBRECHAT L5).
- No results: §11.2.

### 12.5 Keyboard

As §21.4 (↑↓, Enter, F2, Delete, type-to-search).

---

## 13. Titles

### 13.1 Provisional title (immediate, deterministic, no model call)

Computed from the first user message at first send (POCKETPAL A3, LIBRECHAT, with PocketPal's N9 bug fixed):

1. Remove a leading `/command` token, if any.
2. Strip Markdown: emphasis markers, heading `#`s, list markers, block quotes. Drop fenced code blocks entirely. Keep inline code text without backticks.
3. `[[Title]]` → `Title`; a bare URL → its host (`example.com`).
4. Take the first sentence: end at `.`, `?` or `!` followed by whitespace, or at a line break.
5. If that sentence has fewer than 3 words and more text follows, continue into the next sentence (so `Hi. Can you…` doesn't become `Hi`, POCKETPAL A7).
6. Collapse whitespace (including any line breaks kept by step 5) to single spaces; trim.
7. Cut at **48 characters** at a word boundary. If the boundary would fall before 60 % of the limit, hard-cut at 48 instead.
8. Remove trailing punctuation except `?`. Capitalise the first letter; leave the rest alone.
9. **Store without an ellipsis.** Truncation is a rendering concern (`TextOverflow.Ellipsis`).
10. Fallbacks if the result is empty (the message was only code or only an attachment): the first attached document's title (`About Oyster mushroom log`); else `Chat from 26 Sep, 14:02` (locale format). Never `Chat`.

The chat records `title_source: auto` (in chat frontmatter; no migration).

### 13.2 Improved title (optional, later)

A model-generated title is **not in v1**. On the Fold every model call costs a full read, so any upgrade must never contend with a user turn. If enabled after prototyping (owner question Q5), it follows JAN J13: after the first completed answer; only when the engine is idle and the device is charging; aborted by any new send; thinking disabled; cleaned (strip reasoning tags, markup and quotes; ≤ 10 words; reject under 2 characters). It replaces the title only while `title_source` is still `auto`, and records `generated`.

### 13.3 Rename wins forever

A rename records `title_source: user`. Nothing automatic ever overwrites it.

---

## 14. Rename

**Entry points:** chat header ⋮ → **Rename…**; row ⋮ or long-press → **Rename…**; palette `Rename chat…`; F2 on a focused list row.

**Dialog** (`DESIGN_SYSTEM.md` dialog component):

```text
┌────────────────────────────────────┐
│ Rename chat                        │
│ ┌────────────────────────────────┐ │
│ │ Mycology research│             │ │  prefilled, all text selected
│ └────────────────────────────────┘ │
│                    Cancel   Save   │
└────────────────────────────────────┘
```

- Field label `Name`. Prefilled with the current title, all selected, IME up.
- **Save** is disabled when the trimmed text is blank or unchanged.
- Max 100 characters; a counter appears from 80.
- Enter saves; Esc, Back or **Cancel** cancel.
- Leading and trailing whitespace is trimmed; internal newlines are replaced with spaces.
- Duplicate titles between chats are allowed without warning (chats aren't wikilink targets in practice; the collision warning G25 is a Knowledge concern).

**After Save:**
- The header, list row, Recent and palette results update immediately.
- **Renaming never reorders history.** The list sorts by last message time, not `updated_at` (asks **B6**, **B7**).
- An open inspector keeps its focus.
- TalkBack announces `Chat renamed`.

Data semantics (`renameDocument(id, title)` that doesn't echo the transcript, G1): `OBJECT_LIFECYCLE_SPEC.md`.

---

## 15. Delete

### 15.1 Dialog (prompt §29, exact)

```text
┌────────────────────────────────────┐
│ Delete "Skein UX redesign"?        │
│                                    │
│ This removes the conversation from │
│ Skein.                             │
│ Notes and files you added stay in  │  ← only when the chat attached,
│ Knowledge.                         │    imported or saved-as-note anything
│                                    │
│                  Cancel   Delete   │
└────────────────────────────────────┘
```

- **Title:** `Delete "‹title›"?`, wrapping to at most 3 lines, then ellipsis inside the quotes.
- **Body:** `This removes the conversation from Skein.` (prompt §29). `LIFECYCLE_FINDINGS.md` §15.1 proposes `…the conversation and its messages from Skein.`; this spec follows the prompt's wording, and `OBJECT_LIFECYCLE_SPEC.md` may amend it.
- **Conditional line:** `Notes and files you added stay in Knowledge.` when the chat has attachments, imports or saved notes (out-edges, per the lifecycle contract).
- **While this chat is answering:** an extra line `Skein will stop the answer first.` (§15.3).
- **Actions:** **Cancel** (default focus; Enter activates it) and **Delete** (destructive style from `DESIGN_SYSTEM.md`: error-coloured text). Delete is never auto-focused and never Enter-activated (JAN N-J6: Enter also sends messages on the Fold keyboard).
- No undo in v1 (`LIFECYCLE_FINDINGS.md` §14's recommendation). A deferred-delete snackbar would be cancelled by process death, a surprising outcome for a privacy-first app.

### 15.2 Entry points

Chat header ⋮ → **Delete chat…**; row ⋮ or long-press → **Delete chat…**; palette `Delete chat…` (a confirm stage with the same copy); Ctrl+Shift+Backspace with a saved chat focused (never while typing). **Every path shows the dialog.**

### 15.3 Deleting a chat that is answering

**Stop first, then delete.** On **Delete**, the button shows a progress ring and `Stopping…`. The turn is cancelled and awaited until its pipeline has fully ended, then the chat is deleted. This avoids the crash where the answer is appended to a deleted chat (`LIFECYCLE_FINDINGS.md` §12.1). The wait is bounded by §9.8's latency. Cancel is still available while it stops. A queued turn for that chat is simply dropped.

Why not disable Delete while generating: the moment a user wants to delete a chat is often exactly when the answer is going wrong (the degenerate repetition loop in `DEVICE_BEFORE_PASS.md` 04). Making them find Stop first adds a step for no safety gain.

### 15.4 Where the user lands

| Window | Deleted chat | Lands on |
|---|---|---|
| Compact | the open chat (from header ⋮) | The landing (§11.1). The drawer stays closed |
| Compact | the open chat, from the drawer | The drawer stays open with the row removed; the content behind is the landing |
| Compact | another chat, from the drawer | The drawer stays open; the current chat is unaffected |
| Medium / Expanded | the selected chat | The detail pane shows the landing; nothing is selected. List focus (keyboard, TalkBack) moves to the next row, else the previous |
| Medium / Expanded | another chat | Selection unchanged; list focus moves to the neighbouring row |

On every width, deleting the open chat lands on a new chat, never on a *different* existing chat. Opening a neighbour would show content the user didn't ask for. LibreChat and PocketPal both land on a new chat (LIBRECHAT L2, POCKETPAL W13).

### 15.5 UI contract after delete

- The row disappears immediately (a ~150 ms exit animation, instant with remove-animations on).
- Snackbar `Chat deleted` (no action); TalkBack announces it.
- Every back-stack entry naming the chat id is removed: the chat, its inspector, and any source opened from it. There is never a dead route, and Back never returns to it (IA §3.8).
- Recent, palette results and search results drop it.
- The chat's draft is deleted with it.
- If `SendPipeline.lastOutcome` belonged to it, it is cleared (`LIFECYCLE_FINDINGS.md` §9).
- Citations to it in other chats render as deleted sources (§5.4).
- It does not reappear after a restart. Whatever the lifecycle contract removes, the UI never shows the id again.

Everything about rows, messages, chunks, edges and residue: `OBJECT_LIFECYCLE_SPEC.md`.

---

## 16. The model sheet ("who answers")

### 16.1 Trigger and container

The header subtitle; palette `Switch model…`; Ctrl+Shift+M. Compact: modal bottom sheet. Medium/Expanded: an anchored menu-style side sheet under the subtitle (JAN J11: one primitive, two presentations).

### 16.2 Contents

```text
┌──────────────────────────────────┐
│             ───                  │
│ Model                            │   title is "Who answers" once personas exist
│ Used for all chats               │   honest scope in v1 (§16.3)
│                                  │
│ ON THIS DEVICE                   │
│ ◉ Qwen 2.5 3B                    │
│   1.6 GB · Abliterated · Loaded  │
│ ○ Gemma 4 E4B                    │
│   3.1 GB · Vision                │
│                                  │
│ One model runs at a time.        │
│ Switching takes a few seconds.   │
│                                  │
│ Model details              ›     │   for the selected model
│ Manage models              ›     │   → Models destination
└──────────────────────────────────┘
```

- **Rows:** friendly name (§19) as primary; secondary `size · tags · state` (`Loaded`, `Starting…`, `Couldn't start`). A radio shows the model that answers next. 48 dp+ rows.
- **No search and no favourites** with fewer than ~8 models (JAN §6.5: desktop density for a list of two).
- **No generation settings.** Sampling lives behind Model details › Advanced (JAN N-J3, LIBRECHAT L9).
- **No models:** a single row `Add a model` → import.
- **Persona section:** **hidden** until the persona feature exists (`:feature:personas` is a placeholder and not in the app, `CMS-P0-05`). When it exists, a Persona section goes above Model: each row shows the name, a one-line description and the model it uses (LIBRECHAT L7). The author label (§5.1) then uses the persona's name; the default persona is shown as `Skein`.

### 16.3 Switching the model mid-chat

v1 has one default model for every chat. `ManagedInferenceEngine` loads the default only when nothing is loaded (`ManagedInferenceEngine.kt:125-135`), so today *Set default* silently changes nothing (`CMS-P1-13`). The sheet makes the switch real and says what it does:

| When the user picks another model | What happens | What the user sees |
|---|---|---|
| Idle | The new model becomes the default; the old one is unloaded and the new one starts in the background | Subtitle `Gemma 4 E4B · Starting…` → `· Local`. A send before it's ready shows `Starting Gemma 4 E4B` |
| This or another chat is answering | The switch is queued until that answer ends | Subtitle `Gemma 4 E4B · Switches after this answer` |
| The new model fails to start | Default reverts to the previous model | Snackbar `Gemma 4 E4B couldn't start. Still using Qwen 2.5 3B.` · **Details** |

**In the conversation:** the next answer's author label gains `· Gemma 4 E4B` (§5.1). Where answers switch models, a quiet divider reads `Now answering with Gemma 4 E4B`. It is derived from `messages.model_id` changes, stored nowhere else, and needs `SendPipeline` to write `model_id` (bead C2).

A **per-chat** model (the chat remembers its own) is later work (ask **B12**). The sheet's `Used for all chats` line exists so v1 never implies otherwise.

---

## 17. Context inspector (prompt §35)

### 17.1 Container

Compact: a bottom sheet (half height, drags to full). Medium: a levitated side sheet. Expanded: an extra pane that takes the Conversations list's place (IA §3.4; mechanics `ADAPTIVE_LAYOUT_SPEC.md`). It is a back-stack entry, so a fold turns the sheet into the pane with the same focus and scroll (fold test G).

**Entry points:** the context chip; an answer's sources chip or `Sources and activity` (focused on that answer); ⋮ → Context; Ctrl+Shift+I; palette `Show context`.

### 17.2 Header and focus

`Context` + a focus selector: `Latest answer ▾`, or `Answer from 10:42 · Back to latest` when opened from an older answer's sources chip (ANYTHINGLLM A5). Close `✕` (48 dp) on sheets.

### 17.3 Sections

Sections are ordered by how often they're needed. Each can collapse; its state persists for the session.

**1. Session** (one row, always first): `Qwen 2.5 3B · Local ›` (opens the model sheet). Later: `Persona: Researcher ›`.

**2. Knowledge** (always expanded)
- `Search Knowledge` switch (Knowledge on/off; §8.3). Detail: `Searches all your notes and files`.
- **Added to this chat:** icon · title · mode (`Whole` / `Relevant parts` / `Preparing…` / `Too long · using relevant parts`) · ✕ detach.
- **Added to this message** (draft `[[` tokens; only while composing).
- **Used in this answer:** grouped by document (`Oyster mushroom log · 2 passages ›`). Expanding shows quoted passages with their `[N]` markers; cited ones are marked `cited`.
- **Also in context** (only when non-empty): `3 earlier messages were left out to make room.`
- **Details** (Level 2, collapsed): per passage `Found by meaning` / `Found by matching words` / `Linked note` (from `Retrieved.recalledBy`). **No scores** anywhere (ANYTHINGLLM N4: fused scores are not calibrated).
- Empty: `No notes were used for this answer.` · **Add to this chat**.

**3. Activity:** the focused answer's `TurnActivity` (§9.7), expanded.

**4. Room** (context usage). **Hidden below 75 %** of the budget unless messages were left out (CONTINUE C8, LOBECHAT L1). Then: a bar and `This chat is using about 80% of the model's room.` · **Start a new chat**. Details: `3,412 of 15,360 tokens · persona 120 · conversation 2,100 · knowledge 1,192` (per-segment counts are ask **P1**; the total exists as `AssembledPrompt.estimatedTokens`).

**5. Graph** (only if cheap): `Related in your notes`, as up to 6 chips of documents one hop from this answer's sources (wikilink, tag and entity edges), plus **Open in Graph**. Hidden when the query isn't available or returns nothing (ask **P3**).

### 17.4 Truthfulness

Everything in the Knowledge and Room sections comes from the `AssembledPrompt` of the focused turn, per chat (fixing `lastOutcome` leaking across chats, `CMS-P1-06`). Nothing the user asked for is left out silently: anything dropped is labelled (ANYTHINGLLM P0.1). A fixture test compares `AssembledPrompt` to the inspector state (AC-30).

### 17.5 Detaching

✕ on an attached row (48 dp) detaches it immediately. Snackbar `Removed "spec.pdf" from this chat` · **Undo** (undo is safe here: no data is destroyed). While the chat is answering, ✕ is disabled with the reason `Available when the answer finishes`. The document itself stays in Knowledge.

### 17.6 Opening a source

Tapping a passage (or a source in the peek, activity detail or inspector) **pushes** the note or file onto the back stack, opened by kind, scrolled to the passage (`Retrieved.locator`; the whole document when the locator is null before Migration 003). The passage is highlighted until the user scrolls or edits.

- **Compact:** the note opens full-screen. Back returns to the inspector sheet if it was open, then to the chat at the same scroll position (IA §3.8).
- **Expanded:** the chat stays visible when the window allows two panes and the note takes the pane beside it; the inspector yields. Back restores chat + inspector exactly. Pane choice: `ADAPTIVE_LAYOUT_SPEC.md`.
- **During an answer:** opening a source never cancels the answer (the turn controller owns it, bead C1; today it is lost, `CMS-P0-01`).
- **Deleted sources** are not tappable (§5.4).

---

## 18. Attaching knowledge (chat side)

### 18.1 Today vs intended

**Today:** 📎 imports the file into the vault and inserts `[[name]]` text. Nothing makes that file part of this chat's context; it may be retrieved later if ingest has finished and its passages rank (`ChatViewModel.kt:286-298`, `SendPipeline.kt:214`, `CMS-P1-12`). `[[Title]]` from the popup is likewise only prompt text. Errors are uncaught; images throw.

**Intended** (ANYTHINGLLM P0–P3): three scopes the user can see.

| Scope | Created by | Lifetime | Included how |
|---|---|---|---|
| **This message** | `[[` in the composer | the one turn | Whole if it fits, else relevant parts |
| **This chat** | ＋ picker, `Ask about this note` from a note, share-to-Skein | until detached | Whole if it fits, else relevant parts, every turn |
| **Knowledge** (automatic search) | Knowledge on | per turn | Top passages, as today |

Skein picks Whole vs Relevant parts per item from the remaining budget and says which it picked. It never shows a Level 2 modal (ANYTHINGLLM N6). The user can change the mode in the inspector row.

### 18.2 The picker ("Add to this chat")

Reuses `KNOWLEDGE_UX_SPEC.md`'s list, search and filters. Chat-side wrapper:

- Title `Add to this chat`; search field `Search notes and files`; filter chips `Notes · Files · Chats`.
- Sections when the query is empty: **Suggested** (documents linked to this chat's sources, if cheap), **Recent**.
- Multi-select with checkboxes; primary button `Add 2`.
- Last row: `Import from device…` (SAF). The imported item arrives selected and appears in Knowledge.
- Compact: full-screen (IA §3.3). Expanded: anchored sheet.
- Empty vault: `Nothing in Knowledge yet.` · **Import from device…** (the path forward always exists, LOBECHAT L7).

After **Add**: the picker closes and the chip updates (`2 notes · Knowledge on`). New imports show `1 file preparing…` until searchable (ask **B10**).

### 18.3 Failures

- Unsupported type: inline in the picker, `Skein can't read .docx files yet.`
- Import failure: snackbar `Couldn't import "spec.pdf".` · **Details** (a product-language reason in the inspector row). Never uncaught.
- Too long to use whole: row `Too long · using relevant parts`; chip `⚠`.

### 18.4 v1 fallback if the backend asks aren't ready

If **B9** hasn't landed when Wave 4 ships, ＋ offers only `Import file…`, and the result is honest: snackbar `Added "spec.pdf" to Knowledge. Skein will use it when it's relevant.` The chip shows no attachment count. The `[[` token then carries no scope promise; the inspector doesn't list "Added to this message". **Nothing claims a file is in context unless the pipeline puts it there.**

---

## 19. Model identity

### 19.1 Display form (JAN §6.1)

| Part | Example | Where |
|---|---|---|
| **Name** (primary) | `Qwen 2.5 3B` | header subtitle, model sheet, Models list, activity (`Starting Qwen 2.5 3B`), author tag |
| **Where** (secondary) | `Local` | header subtitle; status appended when not ready (§4.3) |
| **Tags** | `Abliterated` · `Vision` · `Coder` | model sheet rows, Models list, Model details. **Not in the header** |
| **Technical** | `Q3_K_M (3-bit) · qwen2 · 16K of 32K · 1.6 GB · SHA-256 2c5f…` | Model details › Technical details only |

### 19.2 Name resolution order (first non-blank wins)

1. **User rename** (Model details › Rename; ask **B4**). Never overwritten by re-import or metadata.
2. **Bundled manifest name** for bundled defaults (curated, like PocketPal's `display_name`).
3. **GGUF metadata:** `pretty(general.basename) + " " + general.size_label`, plus `general.version` if not already in the basename. If `basename` is absent, use `normalise(general.name)` with generic finetune words removed. Read inside `:inference` (ask **B3**).
4. **Cleaned filename:** see §19.3.
5. `Local model`.

**`pretty()`:** insert a space between a family word and its glued version (`Qwen2.5` → `Qwen 2.5`, `Phi3` → `Phi 3`); hyphens and underscores between words become spaces; canonical casing for an allow-list (Qwen, Gemma, Llama, Mistral, Phi, SmolLM, Granite, DeepSeek); uppercase size labels (`3b` → `3B`, `e4b` → `E4B`, `8x7b` → `8x7B`); never add words.

**Tags:** from `general.finetune`, or from filename tokens removed in step 4. Generic tokens (`instruct`, `it`, `chat`, `base`, `gguf`) are dropped; others are title-cased (`abliterated` → `Abliterated`). `Vision` comes from `ModelInspection.hasVision`.

**Disambiguation:** when two installed models resolve to the same name, the colliding rows (and only they) append, in order until unique: the first tag in parentheses (`Qwen 2.5 3B (Abliterated)`), then the short quant (`(Q3)`), then the import date. Parentheses are used because ` · ` already separates the name from `Local`.

**Sanitisation:** GGUF strings are attacker-controlled. Strip C0/C1 controls, bidi overrides (U+202A–202E, U+2066–2069) and zero-width characters; store ≤ 128 characters; display ≤ 40 then ellipsis; render as plain text only. Trust is never inferred from a name: `Gemma 4 (Verified)` gets no trust from its name; verification is a separate Core-derived row in Model details (JAN §6.1).

### 19.3 Cleaned filename (step 4)

1. Take the stem without `.gguf`.
2. If the stored name *is* the model id (adopted models, `ModelManager.kt:239-258`), strip the trailing `-` + 12 hex hash.
3. Strip a shard suffix `-00001-of-0000N`.
4. Strip the quant token at the end, accepting `_`, `-` or `.` separators and the slugged form: `(IQ\d+|Q\d+)([_-][A-Z0-9]+)*|BF16|F16|F32`, case-insensitive (so both `Q3_K_M` and `q3-k-m` match).
5. Drop `GGUF` and generic finetune words; move other finetune words to tags.
6. `pretty()`.
7. If the result is a generic word (`model`, `ggml-model`, `unnamed`) or empty, go to step 5 of §19.2.

### 19.4 Worked examples

| Input | Resolves to | Tags | Why |
|---|---|---|---|
| Owner's model: id `qwen2.5-3b-instruct-abliterated-q3-k-m-2c5f9a121ae6`, name = id (adopted), no `general.*` keys read yet | **`Qwen 2.5 3B`** | Abliterated | §19.3: hash stripped → `qwen2.5-3b-instruct-abliterated-q3-k-m` → quant `q3-k-m` stripped → `instruct` dropped, `abliterated` → tag → `qwen2.5 3b` → `pretty` |
| Same file with GGUF keys `basename=Qwen2.5`, `size_label=3B`, `finetune=Instruct-abliterated` | **`Qwen 2.5 3B`** | Abliterated | §19.2 step 3 |
| Both a Q3 and a Q4 of that model installed | `Qwen 2.5 3B (Q3)` / `Qwen 2.5 3B (Q4)` | Abliterated | Tags don't differ, so the quant disambiguates |
| That model plus the plain (non-abliterated) Qwen 2.5 3B | `Qwen 2.5 3B (Abliterated)` / `Qwen 2.5 3B` | — | The first tag disambiguates; the untagged one keeps the bare name |
| `gemma-4-E4B-it-Q4_K_M.gguf` | `Gemma 4 E4B` | Vision (if `hasVision`) | `it` is generic |
| `mistral-7b-instruct-v0.3.Q5_K_M.gguf` | `Mistral 7B v0.3` | — | `.Q5_K_M` stripped with a dot separator |
| `model-00001-of-00003.gguf`, no metadata | `Local model` | — | Generic after cleaning |
| User renamed it `Research brain` | `Research brain` | as before | Step 1 wins |

**Decision on "Qwen 2.5 3B (abliterated)": the header shows `Qwen 2.5 3B`, and `Abliterated` is a tag.** Reasons:
1. The prompt prescribes exactly `Qwen 2.5 3B` / `Local` (prompt §26, §34).
2. The finetune is a property, like quantization, not the model's identity. The header's job is "which model is answering", and with one Qwen installed there is no ambiguity.
3. The outer screen's subtitle budget is ~30 characters at font scale 1.0.
4. It stays honest: the tag shows in the model sheet, the Models list and Model details, and it moves into the name the moment it is needed to tell two installed models apart.

### 19.5 What Model details shows (the chat's view)

The Models destination (Wave 9) owns the screen (`JAN.md` §6.2). The chat links to it from the model sheet and error cards, and expects at least: **Name** (Rename) · **Runs** (`On this device`) · **Status** · **Size** · **Tags** · **Quality** (`Q3_K_M (3-bit)`) · **Room** (`Skein uses 16K · trained for 32K`) · **Licence** (`Not stated in the file` instead of `UNKNOWN`) · **Technical details ▸** (file name in monospace with middle ellipsis, SHA-256, verification, architecture, chat format, technical id with Copy, imported on) · **Delete model…** (confirmed; JAN §6.2).

---

## 20. Responsive content (prompt §45)

| Case | Compact (443 dp) | Expanded | Rule |
|---|---|---|---|
| Long chat title (100 chars) | Header: 1 line, ellipsis. Row: 1 line, ellipsis. Delete dialog: up to 3 lines | Same | Full title in content descriptions and the rename field |
| Long model name (40 chars, the display cap) | `Qwen 2.5 Coder 32B Inst… · Local ▾` | Same | `· Local ▾` never truncates |
| Code block (120-char lines) | Horizontal scroll inside the block; the page never scrolls horizontally | Same | Max height 60 % of the window, then inner scroll; Copy always visible |
| Markdown table (8 columns) | Horizontal scroll inside the block | Same | Ask **P2** |
| Deeply nested list (6 levels) | Levels 1–4 indented 16 dp each; 5+ flattened at level 4 with a glyph | Same | No text narrower than 60 % of the column |
| Citations (`[10]`, `[1, 2]`, 8 per paragraph) | Chips size to content; line height unchanged | Same | §5.4 |
| Long source names | 1 line, ellipsis in chips and rows; 2 lines in the peek | Same | |
| Long file names | Middle ellipsis (keeps the extension) | Same | |
| Huge user message (5,000 chars) | Collapsed to 8 lines, `Show all` | Same | §5.7 |
| Huge answer (20,000 chars) | Block-level lazy items; smooth scroll | Same | §22 |
| Unbroken strings (hash, URL) | Break anywhere | Same | |
| RTL, bidi | User bubble aligns to end; bidi isolation around titles and model names | Same | Model names are sanitised (§19.2) |
| **Font scale 2.0, outer screen** | Header: title and subtitle each 1 line, no fixed height. Composer caps at 3 lines. Chip truncates per §8.2. Activity durations wrap under labels. Footer icons keep 48 dp; the sources chip truncates first. Dialog buttons stack vertically when they don't fit side by side | Same, more room | The message list keeps ≥ 40 % of the window height |

Today's fixtures capture only font 1.5 on a provisional 411 dp outer screen. The screenshot list (§26) adds 2.0 at the measured widths.

---

## 21. Accessibility and keyboard

### 21.1 Semantics

| Element | Semantics |
|---|---|
| Header title | `heading()` |
| Subtitle | `Role.Button`, `Model: Qwen 2.5 3B, local. Change model.` |
| User message | merged node, `You said: ‹text›`; long-press action `Message options` |
| Assistant answer | merged node, `Skein replied: ‹text›`; custom actions (§9.10) |
| Activity summary | `Role.Button`, state collapsed/expanded |
| Activity header (live) | polite live region (§9.10) |
| Citation chip | `Role.Button`, `Source 1: ‹title›` |
| Context chip | `Role.Button`, full state (§8.2) |
| ＋ / Send / Stop | `Add to this chat` / `Send` / `Stop answer` (today they have none, `CMS-P1-22`) |
| Jump to latest | `Jump to latest message` |
| Conversation row | `‹title›, ‹preview›, ‹time›[, answering]`; actions `Rename`, `Delete` |
| Answer pager | `Answer 2 of 2`; `Previous answer` / `Next answer` |

### 21.2 Focus order

Header (☰ → title → subtitle → ✎ → ⋮) → message list (oldest to newest within the viewport) → Jump to latest → context chip → ＋ → field → Send/Stop. On Expanded, the Conversations pane comes before the chat.

**Opening a chat:** with TalkBack, focus goes to the title heading. With a hardware keyboard, focus goes to the composer. With touch only, nothing is focused (no surprise IME).

**Closing** a sheet, dialog or palette returns focus to the control that opened it.

### 21.3 Other

- Every target ≥ 48 dp, except inline citations under the WCAG inline exception with a 48 dp alternative (§5.4).
- No colour-only signals (the user bubble's queued/picked-up colour is removed; Knowledge off uses an icon and text).
- Reduced motion is honoured everywhere (§9.1).
- The model-chip `⏸`-reads-as-"paused" defect disappears with the chip (`CMS-P1-16`).

### 21.4 Chat keyboard shortcuts

The global map, conflict rules and Android constraints (never Meta; Ctrl chords only; no text-field overrides) are `CONTINUE.md` "Proposed Skein command palette & shortcuts" §7–§8. Chat-scoped bindings:

| Keys | Action | Fires while typing |
|---|---|---|
| Enter / Shift+Enter / Ctrl+Enter | Send / newline / always send (§7.4) | yes |
| Esc | Popup → stop answer (focus in this chat) → leave field | yes |
| ↑ (empty field) | Recall last sent message | yes |
| Ctrl+N | New chat | yes |
| Ctrl+L | Focus composer | yes |
| Ctrl+K | Command palette | yes |
| Ctrl+Shift+A | Add to this chat | yes |
| Ctrl+Shift+I | Context inspector | no |
| Ctrl+Shift+M | Model sheet | no |
| Ctrl+Shift+Backspace | Delete chat… (always confirms) | no |
| F2 (list row focused) | Rename… | no |
| Delete (list row focused) | Delete chat… (confirms) | no |
| ↑ / ↓, Enter (list focused) | Move / open | no |
| Printable key (list focused) | Moves focus to the list's search field and types (Expanded) | — |
| Page Up / Page Down / Home / End (messages focused) | Scroll; End = jump to latest | no |

Chords appear in tooltips, menus and palette rows only when a hardware keyboard is attached (CONTINUE §10).

---

## 22. Performance

The Fold's CPU is saturated by inference while the UI streams. The UI thread's budget is the scarcest resource in the product.

1. **Isolate the live turn.** Only the live item recomposes during streaming. Persisted messages are an immutable list with stable keys; `isAtBottom` and pill visibility are `derivedStateOf`.
2. **UI update rate ≤ 20 Hz.** `SendPipeline` coalesces at 30 ms (fine). The UI applies text to the live item at most every 50 ms and batches in between.
3. **Incremental Markdown.** Completed blocks of the streaming answer are parsed once and frozen; only the last (open) block is re-parsed per update. Today the whole answer is copied and re-parsed per update, O(n²) per turn (`ChatViewModel.kt:203-204`, `AssistantBubble.kt:90-134`, `CMS-P1-24`).
4. **Parse persisted messages off the main thread**, cached by `(message id, content hash)` in a bounded LRU.
5. **Lazy keys and content types:** `msg:‹id›` for rows; block items `msg:‹id›#‹n›` for answers over ~4,000 characters; `live:‹turnId›` for the live turn; `contentType` per kind (user, answer block, activity, divider) for reuse.
6. **No idle tickers.** The 1 Hz activity clock runs only while a live block is composed and visible; it is replaced by nothing when done. (Today a 200 ms ticker recomposes the list for minutes, `CMS-P1-24`.)
7. **No main-thread pipeline work.** `SendPipeline` runs off Main; the `runBlocking` token-count bridge (`ModelServices.kt` `syncCountTokens`) must never execute on Main.
8. **No N+1 title lookups.** Citation titles resolve in one batched lookup per message set, cached by `docId` (`ChatViewModel.kt:156-168`).
9. **Budgets** (measured with JankStats / Macrobenchmark on the Fold, in `UX_TEST_PLAN.md`): streaming a 2,000-token answer with a 50-message history keeps p90 frame time ≤ 16 ms and no frame > 100 ms on the outer screen; opening a 200-message chat shows the latest message within 300 ms; scrolling a 20,000-character answer has no dropped-frame bursts > 5.

---

## 23. Issues with the IA

The IA (`skein-xtov.13`) is implemented as written except for these points.

1. **Compact chat header: ✎ instead of ⌕.** IA §3.3 puts ⌕ "in any top bar", and its Compact sketch shows `☰ title ⌕ ⋮`. On the outer screen this spec uses `☰ title ✎ ⋮`. New chat is Level 1 (IA §3.5) and PocketPal's header has exactly this. The palette stays one gesture away through `/` in the composer and the drawer's ⌕ field. Four icons plus a title don't fit well at 443 dp and font 2.0. Medium/Expanded keep ⌕ (the rail carries ✎).
2. **Date buckets.** IA §3.4 lists *Today / Yesterday / This week / Older*. This spec uses **Today · Yesterday · Previous 7 days · Previous 30 days · then months** (`August`; `July 2025` for other years). "This week" is ambiguous at week boundaries (POCKETPAL N8), and "Older" becomes a wall once a user has months of chats (LIBRECHAT L4).
3. **"Retry on a failed/any last answer"** (IA §3.5): v1 can regenerate only the latest turn, through the adjacency rule (§10.2). Messages are immutable, and branching older turns needs sibling links (ask **B11**).
4. **The model subtitle means "answers next", not "this chat's model"**, until per-chat models exist (ask **B12**). The sheet says `Used for all chats` so the IA's per-chat reading is never implied.
5. **"Model & persona sheet"** is a **Model** sheet in v1: personas don't exist, so no persona control is shown (IA principle 5).
6. **Chat search on Compact** goes through the palette (the drawer's ⌕ field). The IA's drawer has no separate "Search chats" field, and adding one would be a second search box. The palette's result list gets a `Chats` section with title and message matches (§12.4).

No disagreement on: Chat as landing; drawer history vs Conversations pane; open by kind; the context chip and inspector containers; composer `＋ · text · Send/Stop`; `/` and `[[` as power paths; the product glossary.

---

## 24. Open questions for the owner

| # | Question | Default if unanswered |
|---|---|---|
| Q1 | Compact header: ✎ New chat instead of ⌕ (§23 issue 1)? | ✎ |
| Q2 | Keep soft-keyboard Enter = send, with a Settings switch for newline? | Yes, switch default "sends" |
| Q3 | Header shows `Qwen 2.5 3B` with `Abliterated` as a tag, not in the name? | Yes |
| Q4 | Hide the `Worked for` summary on quick, sourceless turns (under 3 s)? | Yes, tune in prototype |
| Q5 | Allow an idle, charging-only model-generated title upgrade? | Off (not built in v1) |
| Q6 | Deleting a chat that's answering: stop first then delete, rather than blocking? | Stop first |
| Q7 | After deleting the open chat on the inner screen, land on a new chat rather than the next chat? | New chat |
| Q8 | Attached notes: default scope "this chat" for ＋ and "this message" for `[[`? | Yes |

---

## 25. Acceptance criteria

Each is testable in JVM (reducers, pure functions), Robolectric Compose tests, Roborazzi, or on the device lane (marked **device**).

**Header and identity**
- **AC-01** No model id, file name, hash, `.gguf` or quant string appears on any chat surface outside Model details › Technical details (grep of rendered semantics in all §26 fixtures).
- **AC-02** `ModelNameResolver` returns the §19.4 results for every row, including the owner's id `qwen2.5-3b-instruct-abliterated-q3-k-m-2c5f9a121ae6` → `Qwen 2.5 3B`.
- **AC-03** At 443 dp and font 2.0 the subtitle shows `… · Local ▾` in full for a 40-character name.
- **AC-04** Each §4.3 engine state renders its subtitle.

**Activity**
- **AC-05** Given a scripted fake (model unloaded, 3 notes retrieved, 30 s read, 10 s write), the block shows exactly `Starting …` → `Used 3 notes` → `Reading your message` → (collapse on first word) → `▸ Worked for ‹d› · N sources`, where ‹d› matches the scripted total within 1 s, and no step appears before its event.
- **AC-06** With a model that emits no reasoning, Lane B never renders (no node with `Model's reasoning`).
- **AC-07** With a scripted reasoning span, Lane B renders the span's text verbatim and nothing else; stored reasoning never appears in the next turn's assembled history, in Copy or in Save as note.
- **AC-08** TalkBack announcements for a full turn number at most (steps + 1), none per token or per second.
- **AC-09** A user toggle on the block survives a 443 → 1006 dp width change mid-turn and is not overridden at the first answer word.
- **AC-10** The clock reads the same elapsed time (± 1 s) before and after a fold mid-turn (**device**: fold test C).
- **AC-11** The calm rule hides the summary for a 2 s, no-source, normal turn, and shows it for a 2 s turn with sources.

**List and composer**
- **AC-12** When the live answer becomes the stored answer, the item's top and height don't change in that frame.
- **AC-13** Scrolled up, new tokens don't move the viewport and `Jump to latest` appears; tapping it resumes following.
- **AC-14** Following stops when the streaming answer's first line reaches the top.
- **AC-15** A soft-keyboard user can type a two-line message with *Enter key sends* off; hardware Shift+Enter inserts a newline; Enter never sends during IME composition.
- **AC-16** `/` as the first character opens the chat-scope palette; dismissing leaves `/` as text.
- **AC-17** The draft survives chat switch, 443 ↔ 1006 dp, and process death (Robolectric recreate).
- **AC-18** Every control in §21.1 has its content description; every target except inline citations is ≥ 48 dp.

**Stop, retry, errors**
- **AC-19** Stop during Searching or Starting ends the turn within 500 ms with `Stopped before answering`, and no stream starts afterwards.
- **AC-20** Try again after a failure leaves exactly one copy of the user message (replaces `ChatScreenTest.kt:318-325`'s assertion).
- **AC-21** Try again on a completed answer shows `Answer 2 of 2`; the next turn's assembled history contains only answer 2.
- **AC-22** Each `InferenceException` subclass renders its §10.3 sentence and action; none shows a class name or code.

**Chats**
- **AC-23** Sending the first message creates exactly one chat, titled per §13.1. Unit corpus includes: `Hi. Can you summarise my oyster notes?` → `Hi. Can you summarise my oyster notes?` (a 1-word first sentence extends into the next); `**Plan** for [[Fold launch plan]]` → `Plan for Fold launch plan`; a 300-character sentence → ≤ 48 characters cut at a word boundary, no ellipsis stored; a code-only message → the attached-document or date fallback. No chat is created by ✎ alone.
- **AC-24** No title equals `Chat` after any §13 path.
- **AC-25** A rename persists, updates header/list/recents immediately, never moves the row, and is never overwritten.
- **AC-26** The delete dialog text equals §15.1; Cancel has initial focus; Enter doesn't delete.
- **AC-27** Deleting the open chat lands on the landing on every window class; Back never returns to it; it's absent after an app restart (**device**).
- **AC-28** Deleting a chat that is answering stops it, then deletes it, without an exception (lifecycle §12.1 regression test).
- **AC-29** Rows never show `user:` or `assistant:`; a row answering in the background shows the ring.

**Context**
- **AC-30** For any fixture turn, the inspector's Knowledge section lists exactly `AssembledPrompt.citations`' documents (none added, none missing) plus labelled omissions.
- **AC-31** The inspector for chat B never shows chat A's turn.
- **AC-32** The chip fits 443 dp at font 2.0 using §8.2's truncation; `Knowledge off` and warnings are never truncated away.
- **AC-33** With B9 absent, no surface says an imported file is "in this chat" (§18.4).
- **AC-34** Opening a source from the inspector mid-answer doesn't cancel the answer; Back returns to the same scroll position with the inspector open.

**Performance**
- **AC-35** §22's frame budgets hold on the Fold outer screen (**device**).
- **AC-36** With no live turn on screen, no recomposition happens in the chat over 10 s (composition counter test).

---

## 26. Screenshot states (Roborazzi fixtures)

**Devices:** the existing `UxDevice` set (phone 360, fold-outer, fold-inner, fold-landscape), with **fold-outer corrected to the measured widths**: add `w443dp` (stock) and `w524dp` (owner's 330 dpi), and a `w320dp` split-screen minimum for the header and chip. **Themes:** light and dark. **Font:** 1.0 everywhere, plus **2.0 on fold-outer 443** for the states marked ★ (today only 1.5 exists). Fixtures are plain UI state built in `src/debug/`, never the pipeline (JAN §6.6). Names follow the existing `‹screen›-‹state›` convention.

| # | Name | State |
|---|---|---|
| 1 | `chat-landing-ready` ★ | Landing, model ready, Recent with 3 items |
| 2 | `chat-landing-nomodel` ★ | Landing, no-model card |
| 3 | `chat-model-starting` | Header `Starting…`, empty chat |
| 4 | `chat-model-failed` | `Couldn't start` card |
| 5 | `chat-activity-starting` | Live: Starting model |
| 6 | `chat-activity-searching` | Live: Searching Knowledge |
| 7 | `chat-activity-reading-5s` | Live: Reading at 0:05, indeterminate |
| 8 | `chat-activity-reading-3m` ★ | Live: Reading at 3:40, with the "few minutes" line |
| 9 | `chat-activity-reading-progress` | Live: determinate with ETA (B1) |
| 10 | `chat-activity-writing` | Collapsed live `▸ Writing · 0:12` + streaming answer |
| 11 | `chat-activity-collapsed` ★ | Completed, `▸ Worked for 1m 31s · 3 sources` |
| 12 | `chat-activity-expanded` ★ | Completed, expanded with steps, sources, Details |
| 13 | `chat-reasoning-live` ★ | Thinking + Lane B window mid-stream |
| 14 | `chat-reasoning-done` | `Thought for 12s ▸` collapsed + answer |
| 15 | `chat-reasoning-exhausted` | Reasoning only, no answer |
| 16 | `chat-stopping` | `Stopping…`, Stop ring |
| 17 | `chat-stopped-partial` | `▸ Stopped after 42s` + partial answer + Try again |
| 18 | `chat-stopped-empty` | `Stopped before answering` |
| 19 | `chat-error-servicedied` ★ | Inline error card |
| 20 | `chat-error-nomodel` | `No model is set up yet.` card |
| 21 | `chat-regenerated-pager` | `Answer 2 of 2` |
| 22 | `chat-long-model-name` ★ | 40-character name in the subtitle |
| 23 | `chat-long-title` ★ | 100-character title |
| 24 | `chat-content-stress` ★ | Code block, 8-column table, 6-level list, `[10]` and `[1, 2]` citations |
| 25 | `chat-huge-user-message` | Collapsed 5,000-character message |
| 26 | `chat-jump-to-latest` | Scrolled up with the pill |
| 27 | `chat-source-peek` | Citation peek sheet/popover |
| 28 | `chat-message-menu` | Answer long-press menu |
| 29 | `chat-chip-states` | Chip: pristine / attachments / off / preparing / ⚠ / room ring (one image per state) |
| 30 | `chat-inspector` ★ | Inspector: Session, Knowledge used, Activity (sheet on outer, pane on inner) |
| 31 | `chat-inspector-room` | Room section visible at 80 % |
| 32 | `chat-attach-picker` | Add to this chat, 2 selected |
| 33 | `chat-model-sheet` ★ | Two models, one loaded |
| 34 | `chat-model-switch-pending` | `Switches after this answer` |
| 35 | `chat-delete-dialog` ★ | §15.1 with the conditional line |
| 36 | `chat-delete-dialog-generating` | With `Skein will stop the answer first.` |
| 37 | `chat-rename-dialog` | Prefilled, selected |
| 38 | `chats-list` ★ | Drawer (outer) / pane (inner): 4 groups, selected pill, one answering ring, one finished dot |
| 39 | `chats-list-empty` | `No chats yet` |
| 40 | `chats-search` | Titles + In messages sections |
| 41 | `chat-composer-multiline` ★ | 6 lines, then inner scroll |
| 42 | `chat-slash-palette` | Chat-scope palette over the composer |
| 43 | `chat-keyboard-up` | Compact height rules with the IME up (outer) |

---

## 27. Backend asks

None of these refactors inference, RAG internals, storage or model loading. Each is additive and narrowly scoped, and each has a UI fallback until it lands. "Chat-side" changes in `feature/chat` (C1, C2, C8) are this epic's own work and are not listed here.

| ID | Ask | Size | Owning epic | Needed by | Fallback without it |
|---|---|---|---|---|---|
| **B1** | **Counts-only prefill progress.** One `oneway` callback on `IInferenceCallback`: `onPrefillProgress(requestId, processedTokens, totalTokens)`, sent once per 512-token chunk from the loop that already logs `prefill batch i/n` (`InferenceService.kt:516-539`). Numbers only, never text. Surfaced as a `Token`-adjacent value or a side flow on `InferenceEngine`'s implementation, without changing the locked `stream()` signature | S | Inference | C12 | Indeterminate Reading with elapsed time |
| **B2** | **Reasoning classification.** Tag generated text as reasoning vs answer (llama.cpp's reasoning parser, or one tested `<think>…</think>` splitter at the stream boundary), exposed additively so `Segment` gains a reasoning case. Guarantee the delimiters survive detokenisation. Optional: a template-probe capability flag ("supports thinking on/off") for a future Think toggle. Fixtures: PocketPal #484, #735 | S–M | Inference | C13 | Lane B never shows; a thinking model's `<think>` text must at least not render as answer text (the splitter is required before any thinking model is recommended) |
| **B3** | **GGUF naming keys.** Read `general.name`, `general.basename`, `general.size_label`, `general.finetune`, `general.version` inside `:inference` as bounded, sanitised strings; add them to `ModelInspection` (additive parcel fields); keep `GgufPreCheck` unchanged | S | Inference / IPC | C4 (step 3) | Filename path (§19.3), which already yields `Qwen 2.5 3B` for the owner's model |
| **B4** | **Persist model presentation fields.** Store `display_name` (user rename), naming keys, `quantization`, `architecture`, `parameterCount` on the model row (today discarded, `ModelManager.kt:447-463`) | S (migration) | Models / storage | C4, Model details | Resolver runs on filename every time; no rename |
| **B5** | **Turn metadata on messages.** A nullable, versioned JSON column (`turn_meta`, "turn-activity-v1"): outcome, per-step durations and counts, token counts, rate, reasoning text, thought duration. Excluded from transcript materialisation, indexing and export by default | S (migration) | Storage | C11, C13 | Summaries only for this session's turns; no Lane B persistence (Lane B stays off) |
| **B6** | **`renameDocument(id, title)`** that doesn't echo the transcript or bump sort order (G1) | S | Vault API | L3, L6 | none; rename can't ship |
| **B7** | **Chat summaries projection.** `observeChats()` → id, title, last-message time, last message role + first 200 plain characters, answering flag slot; plus chat search: titles + message-level hits (message id + snippet) filtered to `kind = chat` (G15) | S | Vault API | L1, L5 | Client-side parsing of the transcript tail (fragile; strip `**role:**`) and document-level hits only |
| **B8** | **Idle lock doesn't fire mid-generation.** Generation activity pokes the idle timer; screen-off and explicit locks still win. Needs security review | S | Vault / lock | C1, §9.11 | Long reads can be killed by the 5-minute lock (`AUDIT_SHELL.md` P0-11) |
| **B9** | **Per-chat context set.** Store attached document ids + mode and Knowledge on/off in the chat's frontmatter (no migration); `PromptAssembler`/`SendPipeline` include attached items as Whole or Relevant parts within `TokenBudget` and report what was included or left out | M | RAG / chat pipeline | I2, I3 | §18.4 honest fallback |
| **B10** | **Preparing-for-search status.** `Flow` of `ingest_queue` membership per document id | S | Vault | I2 | No "preparing" state; Relevant-parts items may be missing on the first turn (disclosed in the inspector) |
| **B11** | **Sibling link for regenerated answers** (optional, later). `messages.supersedes_id` so superseded answers leave the transcript/index and older turns can branch | M (migration) | Storage | post-v1 | Adjacency rule (§10.2) |
| **B12** | **Per-chat model** (later). Chat frontmatter model id + swap-on-send policy in the composition root | M | Models | post-v1 | Global default, labelled `Used for all chats` |
| **B13** | **Correct delete** (lifecycle G3–G5, G7, G8, G22): edge cleanup, notifications, ordering, orphan sweep, instrumented cascade test | S each | Vault / storage | L4 | Delete can't ship |
| **B14** | **Model load progress** (optional, low priority). Forward llama.cpp's load progress callback as a counts-only value | XS–S | Inference | C3 | Indeterminate `Starting ‹model›` (loads take seconds) |

**Platform asks** (other UX-epic owners, not backend):

| ID | Ask | Owner |
|---|---|---|
| **P1** | Per-segment token counts (persona / history / knowledge) in `AssembledPrompt` | RAG (only for the Room section's Details) |
| **P2** | Markdown tables, and code blocks as scrollable blocks, in `core/markdown` | Editor / markdown |
| **P3** | One-hop neighbour query for a set of document ids | Graph (Wave 8) |
| **P4** | Encrypted per-chat draft row (IA D7) | Storage (security review) |

---

## 28. Implementation beads

Wave numbers follow prompt §48: **4** Chat, **5** Conversation lifecycle, **7** Context inspector. Every bead ships its Compose previews (fixture state, `src/debug/`) and Roborazzi baselines for its states in §26. Sizes: S ≤ 2 d, M 3–5 d, L > 5 d.

| ID | Title | Scope | Size | Wave | Depends on |
|---|---|---|---|---|---|
| **C1** | Chat turn controller: lift turn, draft and send job out of composition | Vault-session-scoped controller keyed by chat id (`ANDROID_ADAPTIVE_SAMPLES.md` §5.6 row 4); `StateFlow<ChatUiState>`; turn queue across chats (step 0); cancel on lock; fixes `CMS-P0-01` | M | 4 (first) | Wave 3 shell (Nav3 entries) |
| **C2** | Turn activity events and pipeline fixes | `TurnActivityEvent` + pure reducer + tests; `SendPipeline` emits events; keeps `Token.Done` stats; writes `model_id`; pre-stream cancel; Knowledge-off skips retrieval; keeps partial text on failure; runs off Main | M | 4 | C1 |
| **C3** | Activity block, Lane A | Live/collapsed/expanded UI, calm rule, latch, 1 Hz clock, live-region announcements, reduced motion; replaces `ThinkingPlaceholder`; re-scope `skein-cmoe` to this | M | 4 | C2 |
| **C4** | Chat header and friendly model name | Title/subtitle/states/⋮; pure `ModelNameResolver` (steps 1, 4, 5 now; 2–3 when B3/B4 land) with a unit-test corpus including the §19.4 rows | M | 4 | Wave 3 shell |
| **C5** | Message list redesign | Author labels, unbubbled answers, footer row, message menus (Copy/Select/Save as note/Edit as new), source peek, link peek, scroll/follow/jump rules, no-jump persist swap | M | 4 | C1 |
| **C6** | Answer Markdown in chat | Single-pass parse with citation nodes, code blocks (scroll, copy), multi-digit/grouped citations, huge-message blocks, list flattening | M | 4 | P2 for tables |
| **C7** | Composer rebuild | ＋ · field · Send/Stop/Stopping; placeholder; line caps; Enter setting; IME-composition guard; `/` → palette seam; `[[` token chips; remove `$` and chat `Create "x"`; per-chat draft | M | 4 | C1; P4 for durable drafts |
| **C8** | Stop, try again, errors | Inline error card with §10.3 mapping; Try again reusing the user row; adjacency regenerate + pager; `No answer was saved` derivation; update `ChatScreenTest`'s duplicate-row assertion | M | 4 | C2 |
| **C9** | Landing and empty states | "What are you working on?", no-model card, missing/failed model cards, create-on-first-send | S | 4 | C4 |
| **C10** | Model sheet (v1: model only) | Sheet/menu, rows, switch = real default change + swap + pending state, failure revert, model divider in chat | S | 4 | C4, C2 |
| **C11** | Activity persistence | Write and read turn meta; restored summaries | S | 4 | B5 |
| **C12** | Determinate reading progress | Step 4b with ETA and no-jitter layout | S | 4 | B1 |
| **C13** | Reasoning lane (Lane B) | Window, collapse, stored reasoning disclosure, exhaustion state; excluded from history, copy and save | S | 4 (gated) | B2, B5 |
| **L1** | Conversation list | Grouping, row anatomy, answering/waiting/finished indicators, selection pill, row ⋮/long-press, keyboard | M | 5 | Wave 3 shell, B7 |
| **L2** | Provisional titles | §13.1 algorithm (unit-tested); title and `title_source: auto` passed to `createDocument` at first send, so no rename call is needed | S | 5 | C9 |
| **L3** | Rename | Dialog and entry points; live updates; no reorder | S | 5 | B6, B7 |
| **L4** | Delete | Dialog (prompt §29 copy), entry points, stop-first, landing rules, back-stack purge, snackbar and announcement | M | 5 | B13, `OBJECT_LIFECYCLE_SPEC.md`, C1 |
| **L5** | Chat search | Title + content search, message-level open, palette Chats section | S | 5 | B7; Wave 10 palette for Compact |
| **L6** | Idle title upgrade (PROTOTYPE, off by default) | §13.2, only if the owner approves Q5 | S | 5 | L2, B6 |
| **I1** | Context chip and inspector | Chip grammar/truncation; container per window; Session, Knowledge (used in answer), Activity, Room, Graph (if cheap); focus selector; per-chat `lastOutcome`; fixture test prompt ≡ inspector | M | 7 | C2, C3; P1, P3 optional |
| **I2** | Attach knowledge from chat | ＋ picker wrapper, `[[` message scope, detach with undo, preparing/too-long states, §18.4 fallback | M | 7 | B9, B10, Wave 6 picker |
| **I3** | Knowledge on/off per chat | Switch in inspector, chip state, palette command, stored per chat | S | 7 | C2, B9 (storage key only) |
| **I4** | Open source at passage | Push by kind, locator scroll + highlight, Back restores chat + inspector, deleted-source state | S | 7 | Wave 6 viewer, G9 |
