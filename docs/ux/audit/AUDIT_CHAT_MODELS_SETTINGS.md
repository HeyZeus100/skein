# UX audit: Chat, Models, Settings, Personas, Onboarding, Vault gate

Bead `skein-xtov.2` of epic `skein-xtov`. Brief: `docs/research/SKEIN_UI_UX_OVERHAUL_PROMPT.md` §1–3, §18–19, §26–29, §33–35, §40–45, §55. The canonical UI spec is `docs/superpowers/specs/2026-09-19-skein-design.md` §8.

- **Base:** `main` at `009cbb6`, audited 2026-09-26. This is a read-only code audit. No build, no device run.
- **Companion:** `docs/ux/audit/MATRIX_CHAT_MODELS_SETTINGS.md` has one row per interactive element.
- **Out of scope** (other agents): shell/navigation/Fold layout, editor/notes/graph, and storage-level deletion semantics. Where one of my flows crosses into their code, I cite it and stop there.

**How to read the citations.** Every claim cites `file:line`. Paths are shortened as follows:

| Short form | Full path |
|---|---|
| `chat/` | `feature/chat/src/main/kotlin/app/skein/feature/chat/` |
| `settings/` | `feature/settings/src/main/kotlin/app/skein/feature/settings/` |
| `auth/` | `feature/shell/src/main/kotlin/app/skein/feature/shell/auth/` |
| `nav/` | `feature/shell/src/main/kotlin/app/skein/feature/shell/nav/` |
| `shell/` | `feature/shell/src/main/kotlin/app/skein/feature/shell/` |
| `MainActivity.kt` | `app/src/main/kotlin/app/skein/MainActivity.kt` |
| `models/` (app) | `app/src/main/kotlin/app/skein/models/` |
| `ModelsScreen.kt` | `feature/models/src/main/kotlin/app/skein/feature/models/ModelsScreen.kt` |
| `ModelManager.kt` | `core/inference/src/main/kotlin/app/skein/core/inference/models/ModelManager.kt` |

**Running vs preview/test.** "Running" means composed on a device from `MainActivity`. "Preview-only" and "test-only" code is called out explicitly, because several behaviours that look correct in previews or Robolectric tests do not hold in the running app (§14).

---

## 0. Executive summary

At the code level the ask path works: you can import a model, start a chat, stream an answer, stop it, retry after an error, and tap a citation. The problems are in the layers around that path:

1. **The conversation is not a persistent object.**
   - Every chat is titled `"Chat"`.
   - A previous chat can never be reopened as a chat. The timeline and search open it in the note editor, as an editable transcript.
   - A chat can be neither renamed nor deleted from any chat surface.
   - Any lock (default: 5 minutes idle) discards the open tabs. After that, the only route back to a chat is the note-editor path.
2. **Chat state lives in composition.** Anything that removes `ChatScreen` from composition silently loses the in-flight answer and the composer draft: tapping a citation, switching tabs, folding or unfolding (the activity is recreated), or deleting the default model.
3. **Model identity is the raw id or filename.** Before load the command-bar chip shows a 20-character filename prefix. After load it shows the full slug-plus-hash id. That id is unbounded in width, and on the Fold's outer display it can collapse the command field. Friendly metadata that the import already computes (architecture, quantization, parameter count) is thrown away.
4. **Dead or unreachable surfaces.**
   - Settings › "View NOTICE", "Export vault" and "Erase vault" do nothing.
   - The drawer's Personas entry shows the literal word `PERSONAS`.
   - The `[[` popup's `Create "x"` row in chat does not create anything.
   - A biometric-key invalidation, such as enrolling a new fingerprint, ends on a text-only screen with no way forward.
5. **No activity presentation (§27).** A single static `thinking…` covers retrieval, model load and prefill. Prefill took minutes on the Fold (handoff `docs/Handoffs/skein-v1-autonomous-completion.md:96`). The elapsed time is computed and never shown.

The P0 list is in §15.1.

---

## 1. Reachability map (what the running app actually composes)

| Surface | How a user reaches it | Composed by | Status |
|---|---|---|---|
| Vault setup (first run) | Launch with no key envelope | `MainActivity.kt:922-935` → `auth/VaultSetupScreen.kt:85` | Running |
| Restore from passphrase export | Setup screen › "Restore from a passphrase export" | `auth/VaultSetupScreen.kt:163-184, 209-217` | Running (first run only) |
| Biometric unlock | Launch or return while locked | `MainActivity.kt:936-963` → `auth/BiometricUnlockScreen.kt:148` | Running |
| Vault reset | Unlock screen, **only** in the unreadable-envelope state › "Reset vault…" | `MainActivity.kt:939-952`, `auth/BiometricUnlockScreen.kt:308-316` | Running (rare path) |
| "Opening vault…" / open failure | After unlock | `MainActivity.kt:974-1015` | Running |
| Recovery required | After `KeyPermanentlyInvalidated` | `MainActivity.kt:1017-1028` | Running, dead end (P0) |
| Chat | Command bar `/chat` + Enter (the only path) | `nav/BuiltinCommands.kt:22-36` → `shell/SkeinApp.kt:415-418` → `MainActivity.kt:554-603` → `chat/ChatScreen.kt:47` | Running |
| "No model yet." chat state | `/chat` with no default model | `MainActivity.kt:567-586` | Running |
| "Chat is unavailable in this build." | Test fixtures with no `ModelServices` | `MainActivity.kt:560-566` | Test-only in practice |
| Models list | Command bar `/models` + Enter (the only path) | `MainActivity.kt:619-625, 755-773` → `ModelsScreen.kt:54` | Running, overlay |
| Model import | Command bar `/import model` + Enter → SAF picker | `MainActivity.kt:497-550, 605-618` | Running |
| Import status row | During or after an import | `MainActivity.kt:783-814` | Running, overlay |
| Model status chip | Always, in the command bar | `MainActivity.kt:403-435` → `shell/SkeinApp.kt:303-308` → `nav/CommandBar.kt:125-137` | Running |
| Settings | Drawer › "⚹ Settings" | `MainActivity.kt:648-686` → `settings/SettingsScreen.kt:151` (the view-model overload, **not** `SettingsRoute`) | Running |
| About / licenses | Nothing reaches it. Only `SettingsRoute` wires it (`settings/SettingsScreen.kt:195-220`), and `SettingsRoute` has no caller | `settings/AboutScreen.kt:60` | **Unreachable** |
| Personas | Drawer › "◈ Personas" | `MainActivity.kt:687` → `shell/SkeinApp.kt:429-441` shows the text `PERSONAS` | Running placeholder (P0) |
| `:feature:personas` | Nothing. The module is `object Placeholder` (`feature/personas/.../Placeholder.kt:7`) and is not an `:app` dependency (`app/build.gradle.kts:142-177`) | none | Not built into the app |
| `:feature:onboarding` | Nothing. Same situation (`feature/onboarding/.../Placeholder.kt:7`) | none | Not built into the app |

---

## 2. Chat: inventory

### 2.1 Composables and state holders

| Element | File:line | Running? | Notes |
|---|---|---|---|
| `ChatScreen` | `chat/ChatScreen.kt:47-141` | Yes | Column: header row → optional `ContextPanel` → optional error banner → `MessageList` (`weight(1f)`) → `ChatBottomBar` |
| `ChatViewModel` | `chat/ChatViewModel.kt:73-307` | Yes | A plain `@Stable` class, **remembered in composition** (`chat/ChatScreen.kt:60-71`) with a scope from `rememberCoroutineScope()` (`:59`). Not an AAC ViewModel, not retained, not saveable |
| `ChatTurnState` | `chat/ChatTurnState.kt:24-50` | Yes | `Queued` → `Thinking(elapsedMs)` → `Streaming(text)` → `Done` / `Interrupted` / `Failed` |
| `SentMessageState` | `chat/ChatTurnState.kt:53-70` | Yes | Drives the user bubble's colour only (`chat/MessageList.kt:153-158`) |
| `MessageList` | `chat/MessageList.kt:38-138` | Yes | `LazyColumn` keyed by message id (`:70`), plus one trailing live item, either `"streaming"` or `"thinking"` (`:114-136`) |
| `UserBubble` | `chat/MessageList.kt:147-171` | Yes | No actions |
| `AssistantBubble` | `chat/AssistantBubble.kt:177-207` | Yes | Markdown plus inline citation chips. Shows the `interrupted — stopped` badge (`:197-204`) |
| `StreamingAssistantBubble` | `chat/AssistantBubble.kt:215-238` | Yes | Same renderer, not yet persisted |
| `ThinkingPlaceholder` | `chat/AssistantBubble.kt:241-255` | Yes | Static `thinking…`. Takes no elapsed-time argument |
| `MarkdownWithCitations` | `chat/AssistantBubble.kt:80-163` | Yes | Splits on `[N]`, then parses **each fragment** as its own Markdown document (`:116-134`) |
| `CitationChip` | `chat/CitationChip.kt:24-39` | Yes | A `[N]` text badge. Inline placeholder is 32 sp × 18 sp (`chat/AssistantBubble.kt:261-262`) |
| `ContextPanel` | `chat/ContextPanel.kt:34-86` | Yes | A non-lazy, non-scrolling `Column` of the last turn's `Retrieved` list |
| `ChatBottomBar` | `chat/ChatBottomBar.kt:77-219` | Yes | `$` glyph · `[[` popup · `SecureBasicTextField` · 📎 · ⏎ or ■ |
| `SendPipeline` | `chat/SendPipeline.kt:156-303` | Yes | One per session (`models/ModelServices.kt:261-272`), shared by every chat tab |
| `TabController` | `chat/TabController.kt:29-42` | Yes | Adapted by `app/.../vault/TabsStateTabController.kt:15-30`. Every open is a **preview** tab |
| `ChatScreenPreviews` | `chat/ChatScreenPreviews.kt:81-128` | Preview-only | Static bottom bar with a `reply…` hint that the real composer does not have (`:106-111`) |

### 2.2 Controls, text fields, dialogs

- **Header** (`chat/ChatScreen.kt:74-83`):
  - a static `Text("chat")` (`:79`);
  - a `TextButton` reading `⚹ context` that toggles the panel (`:80-82`).
  - No title, no model name, no overflow menu. The chat has no dialog of any kind: no rename, no delete, no confirm.
- **Error banner** (`:93-119`): the text `generation failed, retry` or `model process restarted, retry` (`:36-37`) and a `Button("retry")` (`:114-116`).
- **Bottom bar:**
  - `$` glyph, decorative (`chat/ChatBottomBar.kt:142-146`);
  - `[[` autocomplete popup (`:147`), which includes a synthesized `Create "x"` row (`feature/editor/.../autocomplete/WikilinkAutocompleteState.kt:132-139`, rendered at `feature/editor/.../autocomplete/WikilinkAutocomplete.kt:124`);
  - composer text field (`chat/ChatBottomBar.kt:148-198`);
  - 📎 `IconButton` (`:199-204`);
  - ■ `IconButton` while generating (`:205-208`), otherwise ⏎ `IconButton` (`:210-216`).
- **Citation chip** (`chat/AssistantBubble.kt:106-109`) and **context-panel row** (`chat/ContextPanel.kt:55-66`): both are tap targets.
- **Assistant text:** there is no copy or select affordance. The comment at `chat/AssistantBubble.kt:168-176` says the text stays copyable by long-press. In Compose, a plain `Text` outside a `SelectionContainer` is **not** selectable, so long-press does nothing and there is no way to copy an answer.

### 2.3 States

| State | What renders | File:line |
|---|---|---|
| Empty (new chat) | Header, then a blank area, then the composer. No welcome, no suggestions. The composer has no placeholder | `chat/MessageList.kt:64-137` (zero items), `chat/ChatBottomBar.kt:148-198` |
| No default model | "No model yet." / "Use /import model to add one, then come back to this chat." No button. The chat document has **already** been created | `MainActivity.kt:567-586`, `nav/BuiltinCommands.kt:31-35` |
| Queued / thinking | A `thinking…` bubble. The last user bubble turns `surfaceVariant` | `chat/MessageList.kt:128-135`, `chat/AssistantBubble.kt:241-255`, `chat/MessageList.kt:155` |
| Streaming | A live bubble that re-renders the whole text every ~30 ms (coalescing at `chat/SendPipeline.kt:301`). The last user bubble turns `primaryContainer` | `chat/MessageList.kt:115-127`, `chat/MessageList.kt:156` |
| Done | The persisted `AssistantBubble`. No timestamp, no model name, no actions | `chat/MessageList.kt:86-94` |
| Interrupted with partial text | The persisted bubble with `interrupted — stopped` | `chat/SendPipeline.kt:276`, `chat/AssistantBubble.kt:197-204` |
| Interrupted with no text (stopped during prefill) | **Nothing.** No assistant row, no badge, no message | `chat/SendPipeline.kt:271-274`, `chat/MessageList.kt:114` |
| Failed | Error banner at the top. Any partial streamed text is discarded (never persisted) | `chat/ChatViewModel.kt:231-238`, `chat/SendPipeline.kt:196-199` |
| Loading an existing chat | No loading state. The list fills when `observeMessages` emits | `chat/ChatViewModel.kt:129-141` |

### 2.4 Composer keyboard behaviour

- **Enter sends.**
  - Soft keyboard: a trailing `\n` typed at the end is treated as send (`chat/ChatBottomBar.kt:157-163`). The IME action is `Send` (`:172-173`).
  - Hardware keyboard: Enter sends and Shift+Enter inserts a newline (`:179-197`).
  - So on a soft keyboard there is **no way to type a multi-line prompt**.
- **`[[`** opens the title popup. The popup owns Enter and arrow keys while it is visible (`:158, 178, 181`).
- **`/` as the first character** calls `onSlashCommand` (`:168`). That callback is `{}` by default (`:86`, `chat/ChatScreen.kt:57`), and `MainActivity` never passes one (`MainActivity.kt:589-600`). **Slash commands in the composer do nothing**; the `/…` text is sent to the model as prompt text.
- **Growth.** The field is multi-line with `maxLines = Int.MAX_VALUE` (the default in `shell/input/SecureBasicTextField.kt`). A long paste grows the bottom bar without bound and squeezes the `weight(1f)` message list to zero height.
- **Focus.** The field has no label, placeholder or hint, and gets no focus on open. After `/chat` the focus stays in the command bar (a known follow-up, handoff `:94`).
- **Draft lifetime.** The draft is `remember { mutableStateOf(TextFieldValue("")) }` (`chat/ChatBottomBar.kt:88`). It is lost on tab switch, fold/unfold, lock, and after tapping a citation. A Bundle-based `rememberSaveable` would conflict with the threat model, so a fix has to retain the draft in memory, scoped to the session.
- **IME hardening.** No personalised learning, no suggestions (the `SecureBasicTextField` wrapper).

---

## 3. Chat today: lifecycle

### 3.1 Create

The only entry point is the command-bar slash command `/chat`. Its palette hint reads `— start a new chat and open it pinned` (`nav/BuiltinCommands.kt:29`). It creates a `DocumentKind.CHAT` document with **`title = "Chat"`** and `personaId = null`, then opens it as a **pinned** `TabKind.CHAT` tab (`nav/BuiltinCommands.kt:31-35`).

- `personaId` is null because `SkeinApp` passes its `personaId` parameter, whose default is null (`shell/SkeinApp.kt:162, 257`), and `MainActivity` never sets it.
- There is no other creation path: no "New chat" button, drawer entry or keyboard shortcut. A grep of `TabKind.CHAT` in `feature/` and `app/` finds only `nav/BuiltinCommands.kt:35` and the dispatch at `shell/SkeinApp.kt:415`.
- Every `/chat` creates a new, empty, persistent document, even with no model (`MainActivity.kt:567` renders guidance only after creation). Abandoned chats pile up in the timeline, and the user cannot delete them (§3.6).

### 3.2 Title: always "Chat"

- **Creation:** `"Chat"` (`nav/BuiltinCommands.kt:33`).
- **Chat code:** nothing in `feature/chat` or `SendPipeline` ever calls `updateBody` or sets a title. `SendPipeline` only calls `appendMessage` (`chat/SendPipeline.kt:206, 277-285`).
- **Tabs:** the tab label is the document title captured when the tab opens (`nav/BuiltinCommands.kt:35`). Every chat tab reads `💬 Chat` in the tab strip and in the `Recent ▾` dropdown (`shell/tabs/RecentDropdown.kt:52, 67`).
- **Header:** the chat header is the literal `chat`, not the title (`chat/ChatScreen.kt:79`).
- **Previews** show a chat tab called `"Research thread"` (`shell/tabs/TabStripPreviews.kt:26`), which the running app cannot produce.

### 3.3 List

Chats appear in the timeline with the 💬 glyph and the title `Chat` (`feature/timeline/.../TimelineFormatting.kt:175`, `feature/timeline/.../TimelineRow.kt:59`). The row's preview line is the first 80 characters of the materialised transcript, with Markdown stripped (`feature/timeline/.../TimelineFormatting.kt:34-56`). Each `appendMessage` rewrites the transcript as `**user:** … **assistant:** …` (`core/vault/.../repository/VaultRepositoryImpl.kt:369-380, 902-903`), so rows read like `user: hi assistant: Hello! How…`. The preview line is the only thing that tells one chat from another. The timeline's "Chats" filter chip exists (`feature/timeline/.../TimelineFormatting.kt:193`).

### 3.4 Reopen: broken

| Route | What it does | File:line |
|---|---|---|
| Timeline tap | `openPreview(... TabKind.NOTE)` | `shell/SkeinApp.kt:226-228` |
| Timeline long-press | `openPinned(... TabKind.NOTE)` | `shell/SkeinApp.kt:229-231` |
| Command-bar search result (tap or Enter) | Routes through the same `onTimelineEntryOpen`, so also NOTE | `shell/SkeinApp.kt:270`, `nav/CommandBarState.kt:105-117` |

So a previous chat opens in `NoteTab`, the note editor (`MainActivity.kt:691-702`, `shell/SkeinApp.kt:406-414`). There it is an **editable** Markdown transcript with an editable title. It cannot be continued, because `ChatScreen` is never shown for it.

Open chat tabs survive a configuration change (`rememberSaveable`, `shell/tabs/TabsState.kt:173-180`). They are discarded when `SkeinApp` leaves composition, which happens on every lock: the vault gate swaps it for the unlock screen (`MainActivity.kt:914-965`). The default idle lock is 5 minutes (`settings/SettingsViewModel.kt:157`). **After any lock, every chat is effectively read-only via the note editor.**

### 3.5 Rename

There is no rename in any chat surface. The only accidental path runs through the note editor:

1. Open the chat from the timeline, which opens it as a note.
2. Edit the title in the header. `NoteTabState.onTitleChange` then calls `updateBody(id, newTitle, body)` (`feature/editor/.../notetab/NoteTabState.kt:121-131`).

This also exposes the transcript body to edits. Any later `appendMessage` would silently re-materialise the body over those edits (`core/vault/.../repository/VaultRepositoryImpl.kt:369-380`). An already-open CHAT tab keeps its old `Chat` label.

### 3.6 Delete: impossible

`VaultRepository.deleteDocument` exists (`core/model/.../Vault.kt:306`), but no code in `feature/` or `app/src/main` calls it (grep, 0 hits). The tab context menu's `Close` only closes the tab (`shell/tabs/TabContextMenu.kt:39-45`); the document stays in the timeline. What a deletion would have to remove is the storage agent's topic. From the UI side, the relevant fact is that nothing can trigger any of it.

### 3.7 Search

Command-bar plain-text search covers titles, then bodies (`nav/CommandBarState.kt:81-96`). Chat transcripts are searchable by content, but every chat title hit is just `Chat`. Results open as notes (§3.4).

---

## 4. Chat today: header, model identity, streaming, stop, retry, citations, context, bottom bar, errors

### 4.1 Header

`chat` + `⚹ context` (`chat/ChatScreen.kt:79-82`). §26 asks for the conversation title, the active model, the response state and stop/retry. The header has none of these.

### 4.2 Model identity

**Nothing in the chat surface names the model.** `SendPipeline` never records which model answered: the assistant `NewMessage` has no `modelId` (`chat/SendPipeline.kt:277-285`), although `messages.model_id` exists. Per-message attribution is therefore impossible even later. The only model identity on screen is the command-bar chip (§5).

### 4.3 Streaming presentation

- **Coalescing.** `SendPipeline` batches segments every 30 ms (`chat/SendPipeline.kt:235-241, 301`).
- **Per update:** `ChatViewModel` builds a fresh `String` of the full answer (`chat/ChatViewModel.kt:203-204`). `MarkdownWithCitations` then re-splits and re-parses the entire answer (`chat/AssistantBubble.kt:90, 116-134`).
- **Auto-scroll** happens only when the **item count** changes (`chat/MessageList.kt:58-60`). While the live bubble grows, the list does not follow it, so a long answer streams off the bottom of the screen.
- **Screen readers.** There are no `liveRegion` semantics anywhere in the chat (grep, 0 hits), so TalkBack users hear neither the arrival nor the completion of an answer.

### 4.4 Activity and progress (§27): none

- **One static `thinking…`.** `ChatTurnState.Thinking` deliberately folds retrieval, prompt assembly, engine queueing, **model load** (the first send's `warmUp`, `chat/SendPipeline.kt:211`) and **prefill** into one state (`chat/ChatTurnState.kt:15-22`). It renders as the static `thinking…` (`chat/AssistantBubble.kt:248-253`).
- **Elapsed time computed, never shown.** `Thinking.elapsedMs` is updated every 200 ms (`chat/ChatViewModel.kt:187-194`), but `ThinkingPlaceholder` takes no parameters and never shows it.
- **"Loading model…" only in a system notification.** The notification says "Loading model…" / "Model ready" (`app/.../vault/VaultServices.kt:234-235`). The chat never does.
- **The chip glyph is ● for both READY and GENERATING** (`MainActivity.kt:435`), so it cannot tell idle from working.
- **Throughput is never shown.** `ModelStatus.tokensPerSec` is in the contract (`core/model/.../Inference.kt:345`), but `ManagedInferenceEngine` never fills it (`models/ManagedInferenceEngine.kt:84-90, 100-112`).
- **No post-completion summary** of the `Worked for 8.1s · 7 sources` kind.

**Why it matters.** On the Fold, prefill ran for 11 minutes under `thinking…` (handoff `docs/Handoffs/skein-v1-autonomous-completion.md:96`). From the user's side that was indistinguishable from a hang.

### 4.5 Stop

- **Placement.** ■ replaces ⏎ while `isGenerating`, which covers `Queued`, `Thinking` and `Streaming` (`chat/ChatViewModel.kt:94-100`, `chat/ChatBottomBar.kt:205-208`). It calls `sendPipeline.cancel()` → `engine.cancel()` (`chat/ChatViewModel.kt:246-248`, `chat/SendPipeline.kt:187-189`).
- **Early taps are dropped.** `LlamaCppEngine.cancel()` returns immediately when no stream is registered yet (`core/inference/.../engine/LlamaCppEngine.kt:474-478`). A tap on ■ during retrieval, model load or prompt assembly is **silently ignored** and the answer arrives anyway. Engine internals are out of scope here. The UX fact is a visible ■ that sometimes does nothing.
- **A stop during prefill leaves no trace** (§2.3).
- **No keyboard shortcut** (for example Esc).

### 4.6 Retry

- **When it appears.** Only for `InferenceException` (the banner, `chat/ChatScreen.kt:93-119`).
- **It duplicates the user message.** `retry()` re-sends `lastFailedText` (`chat/ChatViewModel.kt:251-254`). `SendPipeline.send` persists the USER row **before** anything can fail (`chat/SendPipeline.kt:205-206`), so a retry appends the same user message a second time. `ChatScreenTest.kt:318-325` asserts exactly two `"q"` user rows, so the duplication is pinned by a test. The retried prompt's history also contains the failed turn, which means the model sees the question twice.
- **No retry or regenerate** for a completed or interrupted answer, and no edit-and-resend.
- **The banner is not dismissible.** It stays until the next send.

### 4.7 Citations

- **Rendering.** An inline `[N]` becomes a chip only when N is in the turn's offer (`chat/AssistantBubble.kt:92-114, 125-131`).
- **First tap** calls `tabController.openPreview(docId, label, NOTE)` (`chat/ChatViewModel.kt:269`). On every width that makes the **preview tab active**, because `TabHost` composes only the active tab (`shell/tabs/TabHost.kt:82-88`). The chat leaves composition, which triggers §4.10.
- **Second tap** "expands the inline excerpt" (`chat/ChatViewModel.kt:270-277`). This is **unreachable in the running app**. After the first tap the chat is no longer on screen, and returning rebuilds `ChatViewModel`, which resets `tappedOnce`. `ChatScreenTest.kt:101` passes only because its `TabController` fake never switches content.
- **Wrong editor for some sources.** Citations always open as `NOTE`, even when the source is an attachment or a chat (`chat/ChatViewModel.kt:269`, `chat/ContextPanel.kt:61-65`).
- **Touch target.** About 32 × 18 (sp-sized placeholder, `chat/AssistantBubble.kt:261-262`). The chip has no role and no description beyond `[1]`.

### 4.8 Context panel

- **Contents.** Toggled by `⚹ context`. It lists the **last finished turn's** `Retrieved` items: doc title, `recalled by: vector, lexical`, `score 0.87` (`chat/ContextPanel.kt:69-81, 88-89`). When empty it shows `no retrieved context for this turn` (`:46-52`).
- **Stale across chats.** `ChatViewModel` fills the panel from the **session-wide** `sendPipeline.lastOutcome` without filtering on `outcome.chatDocId` (`chat/ChatViewModel.kt:142-147`). Opening chat B therefore shows chat A's retrieval. `lastOutcome` is a `StateFlow`, so it is replayed into every new `ChatViewModel`.
- **Empty during a turn.** `lastOutcome` is set only at turn end (`chat/SendPipeline.kt:288-297`). During a turn the panel shows the **previous** turn's context.
- **Missing from §35:** model, persona, token or context usage, and any activity.
- **Does not scroll.** The panel is a plain `Column` that is not `weight`ed (`chat/ContextPanel.kt:44-84`, `chat/ChatScreen.kt:85-91`). With 8 rows (`RETRIEVAL_K`, `chat/SendPipeline.kt:71`) plus the keyboard, on the outer display it takes the whole height and the message list collapses to zero.
- **Long titles squeeze the score.** The title column has no `weight`, so a long title pushes `score …` to zero width (`chat/ContextPanel.kt:67-82`).

### 4.9 Bottom bar controls

| Control | What it really does | File:line |
|---|---|---|
| `$` | Decorative `Text` in `primary` colour. Mimics the command bar's prompt and is read aloud as "dollar" | `chat/ChatBottomBar.kt:142-146` |
| `/` | Calls a no-op hook (§2.4) | `chat/ChatBottomBar.kt:168`, `MainActivity.kt:589-600` |
| `[[` → title row | Inserts `[[Title]]` as plain prompt text. Nothing pins that note into context: `SendPipeline` passes the raw text to the normal top-8 retrieval (`chat/SendPipeline.kt:214`) | `chat/ChatBottomBar.kt:108-109, 147` |
| `[[` → `Create "x"` row | Inserts `[[x]]`, then calls `onCreateWikilink`. That is `{}` by default (`chat/ChatScreen.kt:56`, `chat/ChatBottomBar.kt:85`) and `MainActivity` never passes one (`MainActivity.kt:589-600`), so **no note is created** (`WikilinkAutocompleteState.kt:126-128`). The editor wires the same popup to a real create (`NoteTabState.kt:280-282`) | as listed |
| 📎 | See below the table | `chat/ChatBottomBar.kt:120-136, 199-204` |
| ⏎ | Sends. Disabled while the field is blank | `chat/ChatBottomBar.kt:210-216` |
| ■ | Stop (§4.5) | `chat/ChatBottomBar.kt:205-208` |

What 📎 does, step by step:

1. Opens SAF `OpenDocument("*/*")`.
2. Calls `ImportService.importPdf`, `importImage` or `importText`, which puts the file **into the vault as a new document** (`chat/ChatViewModel.kt:292-296`).
3. Appends `[[displayName]]` to the draft (`:297`).

The gaps:

- It shows no progress for a large PDF.
- It discards `ImportResult`, including `conflictWith`, so the inserted link can name the wrong document.
- It has **no error handling**: the `scope.launch` at `chat/ChatBottomBar.kt:123-135` has no try/catch, so an import exception is uncaught in a composition scope.
- It does **not** attach the file to this message. The new document is retrievable only after background ingest runs.

### 4.10 In-flight answers are lost when the chat leaves composition (P0)

The mechanism, in the order it happens:

1. `ChatViewModel` is `remember`ed in `ChatScreen` (`chat/ChatScreen.kt:60-71`).
2. Its send coroutine runs in `rememberCoroutineScope()` (`chat/ChatScreen.kt:59`, `chat/ChatViewModel.kt:196`).
3. `SendPipeline` persists the assistant row only **after** the token stream completes (`chat/SendPipeline.kt:243-286`).
4. So when `ChatScreen` leaves composition mid-turn, the scope is cancelled, the stream collection is cancelled, and **the assistant row is never written**.
5. The user's message was already persisted (`chat/SendPipeline.kt:206`). The reopened chat shows it with no reply, no badge, no error and no retry. This is the §47 "data loss" and "reset during Fold transitions" case.

Triggers in the running app:

- **Citation or context-row tap** (§4.7).
- **Switching tabs:** `TabHost` composes only the active tab (`shell/tabs/TabHost.kt:82-88`).
- **Fold/unfold or rotation:** `MainActivity` declares no `android:configChanges` (`app/src/main/AndroidManifest.xml:81-88`), so the activity is recreated.
- **Deleting the default model** while a chat is open: `hasDefaultModel` flips and the `when` swaps `ChatScreen` out (`MainActivity.kt:567`).
- **Any lock**, including the idle timeout during a long prefill.

### 4.11 Errors

| Situation | What the user sees | File:line |
|---|---|---|
| Generic failure | `generation failed, retry` (all lower-case, no cause). The same text covers "no default model" (`InferenceException.ModelNotLoaded`, `models/ManagedInferenceEngine.kt:128`), a load failure and any engine error, so for the first of these, retry cannot help | `chat/ChatScreen.kt:37` |
| Service death | `model process restarted, retry` | `chat/ChatScreen.kt:36` |
| Failed load | The chip falls back to `<name> · not loaded` (`MainActivity.kt:433`), because `ManagedInferenceEngine` clears `modelId` on `ERROR` (`models/ManagedInferenceEngine.kt:89`). No error glyph; the notification is dismissed (`app/.../vault/VaultServices.kt:233`) | as listed |
| Import failure | `Import failed: <describe()>`, for example `Import failed: StructurallyInvalid(NOT_GGUF)`, `FromStore(…)`, `InspectionFailed(<code>)`, or a bare class name (`ModelManager.kt:770-776`). Implementation language | `MainActivity.kt:542` |
| Vault open failure | `The vault could not be opened: <reason>`, where the reason can contain an exception class name (`app/.../vault/GatePhase.kt:73-80`) | `MainActivity.kt:1004-1008` |

---

## 5. Model identity: the exact strings users see

**Where names and ids come from**

- **Name.** For a picked import, `Model.name` is the SAF display name of the picked file, extension included (`ModelManager.kt:340, 594`). For a model adopted after an interrupted import, the name **is the id** (`ModelManager.kt:239, 243, 258`). The Fold's current model was adopted that way (handoff `:92-94`).
- **Id.** `<slug(displayName)>-<first 12 hex of sha256>`, capped at 64 characters (`ModelManager.kt:623-631`). The slug lower-cases, drops the extension, and maps every character outside `[a-z0-9.-]` to `-` (`ModelManager.kt:677-688`).
- **Worked example.** The smoke model `Qwen2.5-3B-Instruct-abliterated-Q3_K_M.gguf` (handoff `:87`) gets the id `qwen2.5-3b-instruct-abliterated-q3-k-m-<12 hex>`, which is 51 characters. That is the §26 "Bad" example almost verbatim.
- **Metadata thrown away.** The import inspection reports `architecture`, `quantization`, `parameterCount` and `contextLength` (`core/ipc/.../Parcels.kt:488-498`). Only `contextLength` and capabilities are persisted (`ModelManager.kt:447-463`); `Model` has no field for the rest (`core/model/.../Inference.kt:70-82`). So the UI has nothing but the filename or id to show.

**Every user-visible model string, with its source**

| Surface | Template | Source | Example on the Fold (adopted smoke model) |
|---|---|---|---|
| Chip, nothing imported | `no model · ⏸` | `MainActivity.kt:434` + `nav/CommandBar.kt:125-127` | `no model · ⏸` |
| Chip, import running | `importing N% · ⏸` | `MainActivity.kt:431` | `importing 42% · ⏸` |
| Chip, default set but not loaded (also after a load **error**) | `${name.take(20)} · not loaded · ⏸` | `MainActivity.kt:433`, `CHIP_NAME_MAX = 20` at `:1034` | `qwen2.5-3b-instruct- · not loaded · ⏸`; a freshly picked file shows `Qwen2.5-3B-Instruct- · not loaded · ⏸` |
| Chip, LOADING | `<model id> · ⏸` (untruncated; the glyph reads as paused) | `MainActivity.kt:432, 435` | `qwen2.5-3b-instruct-abliterated-q3-k-m-<12hex> · ⏸` |
| Chip, READY or GENERATING | `<model id> · ●` (untruncated) | `MainActivity.kt:432, 435` | `qwen2.5-3b-instruct-abliterated-q3-k-m-<12hex> · ●` |
| Chip, TalkBack | `Model status: <same text>, active` or `…, paused` | `nav/CommandBar.kt:133-136` | as above |
| `/models` row, title | `<name>` + ` · default` | `ModelsScreen.kt:112-119`, `MainActivity.kt:446-450` | `qwen2.5-3b-instruct-abliterated-q3-k-m-<12hex> · default` (a picked file shows `…Q3_K_M.gguf · default`) |
| `/models` row, subtitle | `<size> · <SPDX>`; every picked or adopted import is `UNKNOWN` (`ModelManager.kt:597`, `MainActivity.kt:449`) | `ModelsScreen.kt:121-125` | `1.5 GB · UNKNOWN` |
| Import status row | `Importing model…`, `Importing model… N%`, `Imported "<name>" and set as default`, `Registered <id>[, <id>] from an earlier import and set as default`, `Import failed: <describe()>`, `An import is already running — wait for it to finish` | `MainActivity.kt:474, 502, 507, 519, 522, 531, 542, 613` | `Imported "Qwen2.5-3B-Instruct-abliterated-Q3_K_M.gguf" and set as default` |
| System notification | `Loading model…` / `Model ready` (counts only, never a name) | `app/.../vault/VaultServices.kt:234-235` | as shown |
| Settings › Models (inert rows) | `Qwen 2.5 3B Instruct`, `Gemma 4 E4B`, `Import your own GGUF`, each with the caption `Available once the first-run model picker lands (skein-bxk)` | `settings/SettingsScreen.kt:129-134` | the only friendly names in the app, attached to nothing |
| Chat surface | none | `chat/ChatScreen.kt:74-83` | none |
| Previews | `qwen · ●` | `nav/NavPreviews.kt:39, 105, 117, 133, 151`; `shell/SkeinApp.kt:195` default | preview only. It hides the width problem below |

**Chip layout (P0, computed; confirm with a screenshot).**

- The chip is an unconstrained `Text`: no `maxLines`, no `overflow`, no width cap (`nav/CommandBar.kt:126-137`). The search field is the only `weight(1f)` child in the same `Row` (`:91-95`). Compose measures the unweighted children first, so the chip takes all the width it needs and the field gets what is left.
- Width estimate. `labelMedium` is 12 sp bold IBM Plex Mono with 0.2 tracking (`shell/theme/SkeinTypography.kt:60`); with Plex Mono's 0.6 em advance that is about 7.4 dp per character.
  - The loaded string (≈55 characters) is about 405 dp plus 16 dp of padding.
  - The Fold's outer display is 1080 px wide, which is about 411 dp at the default density. **This is an assumption; confirm it with `adb shell wm size/density`.**
  - After the hamburger (48 dp) and row padding there is about 355 dp left. The chip wraps onto two lines and the search/command field collapses to roughly 0 dp.
  - Before load, `qwen2.5-3b-instruct- · not loaded · ⏸` (≈37 characters, ≈290 dp) leaves the field about 65 dp.
- Larger font scales make both cases worse. The command bar is the only way to start a chat or reach `/models`.

**Other chip problems**

- The chip is not clickable. There is no model picker anywhere near it.
- LOADING shows ⏸ (TalkBack says "paused").
- ERROR reads as "not loaded".
- READY and GENERATING look identical.

---

## 6. Models screen

**Hierarchy** (`ModelsScreen.kt:54-132`)

- A full-screen overlay with a `Models` title and a `Close` TextButton (`:76-77`).
- Then either the empty text `No models imported yet — use /import model` (`:79-86`) or a `LazyColumn` of rows (`:88-93`).
- Each row shows the name, ` · default`, `size · SPDX`, a `Set default` TextButton (hidden when the row is already the default, `:127-129`) and a **filled** `Delete` Button (`:130`).
- There are no sections and no "active/loaded" indicator. `isLoaded` is used only to disable Delete, which is also a snapshot taken when the list was built (`MainActivity.kt:451`), so it goes stale.

**Default vs advanced.** There is no advanced layer. The default view already shows implementation detail: a slug id with a hash suffix, a raw filename with `.gguf`, and `UNKNOWN` licence. It also hides useful facts: quantization, parameter count, context length, when it was imported, and whether the model is loaded. Compared with §34 and §6.1–6.2 (Jan): no friendly primary name, no `Local` secondary label, no On Device / Available split, no details inspector.

**Entry points**

- Only by typing `/models` into the command bar (`MainActivity.kt:619-625`).
- Not in the drawer.
- Settings › Models shows three inert placeholder rows that claim import is not yet available (`settings/SettingsScreen.kt:129-134`), even though `/import model` works.

**Import flow UX**

1. `/import model` (hint `— pick a GGUF file to import and set as default`, `MainActivity.kt:608-611`).
2. The SAF picker opens with `application/octet-stream` and `*/*` (`:615`).
3. A status row appears at the bottom, full width, over the chat composer (`:783-814`), with a progress bar and a `Dismiss` TextButton.
4. On success the imported model **silently becomes the default** (`:528`). The success row auto-clears after 6 s (`:483-493`). Failure rows stay until dismissed, covering the composer.

The import job lives in `rememberCoroutineScope` (`:465`), so a lock cancels it; the handoff tracks this as skein-gg11.19 at `:92`. There is no import from inside the Models screen.

**Active vs default**

- `Set default` only changes the registry default (`MainActivity.kt:758-763`).
- `ManagedInferenceEngine` loads the default **only when nothing is loaded** (`models/ManagedInferenceEngine.kt:125-135`). Switching the default while a model is resident therefore changes nothing until the next unload (lock). The chip keeps showing the old id, and no message says the switch is pending.

**Delete**

- One tap, no confirmation, and it deletes a multi-GB sealed file (`MainActivity.kt:764-770` → `ModelManager.kt:164-175`).
- The returned `DeleteOutcome` is ignored (`MainActivity.kt:766`), so a `Refused` delete looks exactly like a no-op.
- Delete is disabled for the loaded model with no explanation (`ModelsScreen.kt:130`).
- Deleting the default clears it (`ModelManager.kt:174`). Every open chat tab then flips to "No model yet." mid-conversation (`MainActivity.kt:567`), which kills any in-flight answer (§4.10).

**Back.** There is no `BackHandler` anywhere in `feature/` or `app/src/main` (grep, 0 hits). System Back while `/models` is open leaves the app instead of closing the overlay; the owner already hit this on the Fold (handoff `:94`).

---

## 7. Personas and onboarding

**Personas**

- The drawer entry `◈  Personas` (`nav/NavDrawer.kt:30`) navigates to `Destination.PERSONAS`. `MainActivity` renders `DestinationPlaceholder(label = destination.name)` for it (`MainActivity.kt:687`), which is a full-screen `headlineMedium` text reading **`PERSONAS`** with no other content (`shell/SkeinApp.kt:429-441`).
- `:feature:personas` contains only `object Placeholder` (`feature/personas/.../Placeholder.kt:7`) and is not an `:app` dependency.
- Behind the scenes, `PersonaService.default()` seeds a persona named `Default` with `systemPrompt = null` (`core/vault/.../persona/PersonaServiceImpl.kt:169-184, 287`). Every chat turn uses it, through `personaProvider = { personaService.default() }` (`app/.../vault/DeviceVaultOpener.kt:137`); the chat's own `currentPersonaId` is hard-wired to `{ null }` (`MainActivity.kt:599`).
- The user can neither see, edit, create nor choose a persona. The timeline's persona filter chip (`feature/timeline/.../FilterBar.kt:53-59`) is the only persona UI, and chats are created with `personaId = null`, so they drop out of any persona filter.

**Onboarding.** `:feature:onboarding` is `object Placeholder` (`feature/onboarding/.../Placeholder.kt:7`). Spec §8.7 onboarding does not exist. That spec calls for choosing a default model, downloading it, verifying its hash, unlocking with biometric, and creating a first persona. What a first-time user actually gets:

1. Vault setup (§9), then a third biometric prompt.
2. An empty timeline.
3. A command bar that says `search or /command`.

Nothing on screen says "Ask Skein" or "Start a chat". Nothing offers to import a model outside the `/import model` slash command. The chat's no-model state tells the user to type that command rather than offering a button (`MainActivity.kt:582`).

---

## 8. Settings

**Organisation in the running app** (`settings/SettingsScreen.kt:82-146`, a single scrolling column capped at 640 dp):

| Section | Row | Real? | File:line |
|---|---|---|---|
| Appearance | "Appearance" label + System / Light / Dark options | Real | `:91-93`, `settings/AppearanceControls.kt:40-61`; the label repeats the section title (`AppearanceControls.kt:46`) |
| Security | Block screenshots & screen recording (switch) | Real | `settings/FlagSecureToggle.kt:29-55` |
| Security | Lock after inactivity (dropdown: 1 / 5 / 15 / 30 / 60 minutes) | Real | `settings/LockPolicyControls.kt:36-70, 138` |
| Security | Lock when screen turns off (switch) | Real | `settings/LockPolicyControls.kt:73-85` |
| Security | Lock when app leaves foreground (switch) | Real | `settings/LockPolicyControls.kt:88-100` |
| Security | `Hardware` — `StrongBox` or `TEE (StrongBox unavailable)` | Info only. Implementation language | `settings/LockPolicyControls.kt:108-115` |
| Security | Export vault key (passphrase) → warning, passphrase ×2, re-auth, save | Real | `settings/RecoveryKeyExportControls.kt:245-292, 329-446` |
| Security | `Biometric unlock` — "Coming in v1.1" | **Inert, and contradicts the app**, which already unlocks only with biometrics (`auth/BiometricUnlockScreen.kt:181`) | `settings/SettingsScreen.kt:118` |
| Indexing | `Notifications` — "Indexing progress shown while documents are processed" | Info row styled like a setting | `settings/SettingsScreen.kt:121-127` |
| Models | `Qwen 2.5 3B Instruct`, `Gemma 4 E4B`, `Import your own GGUF` | **Inert.** The caption leaks a bead id (`skein-bxk`) | `settings/SettingsScreen.kt:129-134` |
| Vault | `Export vault` | **Dead**: clickable, but the callback is `{}` (`:56, 137`), never passed from `MainActivity.kt:682-685` | `settings/SettingsScreen.kt:137` |
| Vault | `Erase vault` (red, destructive) | **Dead**: same wiring | `settings/SettingsScreen.kt:138` |
| About | `Version` — `<versionName> (<versionCode>)` | Info | `settings/SettingsScreen.kt:142`, `MainActivity.kt:684` |
| About | `View NOTICE` | **Dead**: `onViewNoticeClick = {}`, because `MainActivity` uses `SettingsScreen`, not `SettingsRoute` (`MainActivity.kt:682-685`, `settings/SettingsScreen.kt:195-220`) | `settings/SettingsScreen.kt:143` |

**Implementation-language labels:** `StrongBox`, `TEE`, `GGUF`, `(skein-bxk)`, `View NOTICE`, `Export vault key (passphrase)`, and "Indexing".

**What should move to advanced/developer:** the Hardware row, the Indexing info row, and the vault-key export. Export is a strong security feature, but it is a power-user flow and belongs under an explicit "Recovery" or "Advanced security" heading.

**What is missing:** Models (a link to a real Models screen), Personas, Chats (storage, clear history), Privacy/About (reachable licences), and a "Lock now" action.

**Accessibility**

- The `SwitchRow` and `FlagSecureToggle` rows are not `toggleable`. The `Switch` is a separate node with no label (`settings/LockPolicyControls.kt:124-133`, `settings/FlagSecureToggle.kt:35-52`), so TalkBack reads "Switch, on" with no name, and tapping the label does nothing.
- Theme options are about 34 dp tall (12/8 dp padding around 13 sp text, `settings/AppearanceControls.kt:81`), below 48 dp. They carry `selected` semantics but no radio or tab role.
- The Export vault key flow is solid. It re-authenticates freshly, warns before the passphrase field, shows a strength meter and checks for a mismatch (`settings/RecoveryKeyExportControls.kt:370-423, 469-487`).

---

## 9. Unlock, setup, reset and restore: first impression (§1: "understand what to do in about five seconds")

**First run: `VaultSetupScreen`** (`auth/VaultSetupScreen.kt:187-218`)

- Title `Set up your vault`.
- A four-sentence explanation (`:375-378`): an encrypted vault, a key in secure hardware, "You will be asked to confirm twice", "Nothing leaves your device".
- Primary `Set up vault`.
- Small print about restoring (`:389-391`), then an outlined `Restore from a passphrase export`.

Verdict: **passes the five-second test** for "tap the big button". It does not say what Skein is (an AI workspace, chat), and "vault" / "passphrase export" is jargon for a first-time user.

Then:

1. `Step 1 of 2` (biometric) and `Step 2 of 2` (device credential).
2. The gate routes `provisioned = true` to `GatePhase.Unlock` (`app/.../vault/GatePhase.kt:45-57`, `MainActivity.kt:927-930`), and that screen **auto-presents a third prompt** (`auth/BiometricUnlockScreen.kt:220-227`). "Step 2 of 2" followed by a third prompt reads as a bug.

**Error and edge states**

- Cancelled or failed setup: `Try again` (`:229-242`).
- No biometric: `Open security settings` + `Try again` (`:244-263`). Clear.

**Return visit: `BiometricUnlockScreen`**

- The system prompt appears at once, with `Unlock Skein` / `Authenticate to open your vault` / `Cancel` (`auth/BiometricUnlockScreen.kt:155-157`).
- Behind it: a spinner and `Waiting for biometric authentication…` (`:234-246`). There is no app name, logo or context on the screen itself.
- After Cancel: `Authentication was cancelled.` + `Try again` (`:381, 267-285`). Clear enough.
- Unlock is `BIOMETRIC_STRONG` only, with no device-credential fallback (`:181`). A biometric lockout or a failing sensor leaves only `Authentication failed.` + `Try again` (`:471`), with no alternative path. The device-credential key already exists from setup and is never offered for unlock.

**Recovery required (P0 dead end).** `The biometric key was invalidated. Recovery is not available in this build yet.` shows with **no button at all** (`MainActivity.kt:1017-1028`). The biometric alias is created with `setInvalidatedByBiometricEnrollment(true)` (`core/vault/.../key/AndroidKeystoreFacade.kt:62`), so **enrolling a new fingerprint puts the user here**. Restore-from-export exists, but only on the first-run setup screen, which is unreachable while an envelope exists.

**Reset (unreadable envelope only)**

- Clear, two-step confirmation: type `RESET`, then a second `This cannot be undone` screen (`auth/VaultResetScreen.kt:88-176`). Cancel is composed first and nothing is auto-focused. This is the model the app's other destructive actions should follow.
- Minor: the final `Permanently delete vault` uses the default primary `Button` colours rather than error colours (`:168-174`).

**Opening vault.** `Opening vault…` + spinner. On failure, `The vault could not be opened: <reason>` + `Try again` (`MainActivity.kt:989-1013`); the reason can contain a class name.

---

## 10. User flows (§43)

Legend for each flow: **Path** · **Dead ends** · **Duplicates** · **Confusing** · **Missing feedback** · **Stale state** · **Layout** · **Terminology**.

### Flow 2: Start new chat

- **Path.** Either:
  - tap the command bar → type `/chat` → Enter (IME "Search"); or
  - type `/` → tap the `/chat` palette row, which only **fills** `/chat ` into the bar (`nav/CommandBarHost.kt:56-64`, `nav/CommandPalette.kt:17-23`) → press Enter.

  That is at least three interactions, and all of them are hidden behind a slash command.
- **Dead ends.** With no model, the chat document is created anyway and shows "No model yet." with no button (`MainActivity.kt:567-586`).
- **Confusing.** Nothing on screen offers "New chat". The palette hint says "open it pinned", which is jargon. The command bar keeps focus, so the keyboard stays attached to the wrong field and the composer is not focused.
- **Missing feedback.** None beyond the tab appearing.
- **Stale state.** Empty `Chat` documents accumulate in the timeline and cannot be deleted.
- **Layout.** On the outer display, once a model is loaded, the command field may be collapsed by the chip (§5), which blocks this flow entirely.
- **Terminology.** `/chat`, "pinned", `$`.

### Flow 3: Send prompt

- **Path.** Tap the composer (no hint shows where to type) → type → Enter or ⏎. Two actions.
- **Dead ends.** A soft keyboard cannot insert a newline.
- **Confusing.** The composer shows only `$`, the same glyph as the command bar. The owner has already confused the two (handoff `:92`: "the chat input is the message bar at the bottom of the /chat tab, not the command bar").
- **Missing feedback.**
  - The first send includes the model load, which shows only as `thinking…`.
  - The user bubble's colour change (`surfaceVariant` → `primaryContainer`) is the only "picked up" signal, and it is colour-only.
  - The draft is cleared immediately; on failure it is not restored to the field.
- **Stale state.** The context panel shows the previous turn, or another chat's (§4.8).
- **Layout.** A long paste grows the composer without bound.
- **Terminology.** `$`.

### Flow 4: View response

- **Path.** Watch the bubble.
- **Dead ends.** No copy (§2.2), no share, no regenerate, no "continue".
- **Confusing.**
  - A citation inside bold or list text breaks the Markdown, because each fragment is parsed separately (`chat/AssistantBubble.kt:116-124`).
  - Tables render as raw pipe text (`core/markdown/.../MarkdownAst.kt:137` → `render/MarkdownRenderer.kt:101`).
  - Code blocks wrap inside the bubble, with no horizontal scroll and no copy button (`render/MarkdownRenderer.kt:93`).
- **Missing feedback.** The list does not follow the growing bubble (`chat/MessageList.kt:58-60`). No "done" signal, no timestamp, no model name.
- **Layout.** Bubbles have no maximum width, so on the inner display (≈820 dp) lines run the full pane width. User and assistant bubbles are both `surface` on `background` (`chat/MessageList.kt:157`, `chat/AssistantBubble.kt:186`); alignment is the only thing that separates them.

### Flow 5: View activity (§27)

- **Path.** None exists.
- **Dead ends.** A single `thinking…` for everything before the first token, possibly for minutes. Nothing afterwards.
- **Missing feedback.** No stages, no elapsed time (it is computed but hidden), no sources count, no load or prefill indicator in-app. The only "Loading model…" text is in the notification shade (`app/.../vault/VaultServices.kt:234`).
- **Terminology.** `thinking…` suggests reasoning; the time is really retrieval, load and prefill.

### Flow 6: Stop response

- **Path.** One tap on ■.
- **Dead ends.** Before the stream registers (retrieval, load, assembly) the tap is dropped silently (§4.5).
- **Missing feedback.**
  - Stopped before any text: nothing is shown at all.
  - Stopped with partial text: the bubble is kept and marked `interrupted — stopped`, which is redundant wording.
  - The ■ glyph has no label.
- **Keyboard.** No Esc.

### Flow 7: Retry

- **Path.** Only after an error: tap the banner's `retry`.
- **Duplicates.** The user message is persisted a second time (§4.6).
- **Dead ends.** A completed or interrupted answer has no retry or regenerate. When there is no default model, the banner still says "retry", and retrying cannot succeed.
- **Confusing.** The banner sits at the top of the chat, far from the failed message, and never says what went wrong.
- **Stale state.** Partial text from a turn that failed mid-stream disappears.

### Flow 8: Open previous chat

- **Path.**
  1. The timeline shows `💬 Chat` + `user: … assistant: …`.
  2. Tap it: it opens as a **note-editor preview** (`shell/SkeinApp.kt:226-228`).
  3. The transcript is editable, and the conversation cannot be continued.

  Chats opened earlier in this session are reachable through `Recent ▾` or the tab strip. Every one is labelled `💬 Chat`, and all of them vanish at the next lock.
- **Dead ends.** Every chat, after any lock, can never be continued.
- **Duplicates.** The same chat document can be open at once as a CHAT tab (from `/chat`) and as a NOTE tab (from the timeline), because they are different tab kinds.
- **Confusing.** The chat renders as `**user:** …` Markdown in an editor with a title field and a ✦ graph button.
- **Stale state.** Editing the transcript as a note changes the document body but not the `messages` rows (§3.5).

### Flow 9: Rename chat

- **Path.** None in chat. Accidental path: open from the timeline (as a note), then edit the title in the note header (`NoteTabState.kt:121-131`).
- **Dead ends.** From a chat tab: no header title, no menu.
- **Stale state.** An open CHAT tab keeps `Chat`, and the chat header always says `chat`.

### Flow 10: Delete chat

- **Path.** None. There is no UI caller of `deleteDocument`.
- **Dead ends.** Total: the §47 P0 "inability to delete chats".
- **Confusing.** Tab `Close` looks like dismissal, but the chat stays in the timeline, where it can only be opened as a note.

### Flow 19: Open citation

- **Path.** Tap `[N]`. The source opens as a NOTE preview and becomes the active tab. Getting back to the chat means using `Recent ▾` or the tab strip.
- **Dead ends.**
  - The second-tap excerpt is unreachable (§4.7).
  - A citation tapped mid-stream **loses the answer** (§4.10).
- **Confusing.** Attachment and chat sources open in the note editor. Opening a second citation replaces the first preview tab (`shell/tabs/TabsState.kt:54-70`).
- **Stale state.** The chat's view model is rebuilt on return: draft gone, tap bookkeeping gone.
- **Layout.** The chip's target is about 32 × 18.

### Flow 20: Inspect context

- **Path.** Tap `⚹ context`. The panel appears inline under the header.
- **Stale state.**
  - It shows the last finished turn from any chat (§4.8).
  - During a turn it shows the previous one.
  - When the model answered without retrieval it says `no retrieved context for this turn`, even if the stale data is from another chat.
- **Layout.** Up to 8 rows with no scroll. On the outer display with the keyboard up, the message list collapses. Long titles squeeze the score.
- **Terminology.** `score 0.87`, `recalled by: vector, lexical, graph` — raw RAG internals as the default view.
- **Missing (§35).** Model, persona, context-window usage, attached notes, activity.
- **Rows.** Tapping a row opens a NOTE preview and leaves the chat, with the same loss as Flow 19.

### Flow 22: Switch model

- **Path.** Type `/models` + Enter → `Set default` on another row → `Close`. At least four interactions, discoverable only through a slash command.
- **Dead ends.** The loaded model keeps answering until the next lock or unload (§6). The chip still shows the old id. Nothing says "will switch on next load".
- **Confusing.** `/import model` also switches the default silently (`MainActivity.kt:528`). The chip is not tappable. Settings › Models lists friendly model names that do nothing.
- **Layout.** A long id wraps inside the row next to two buttons.
- **Terminology.** Slug ids, `.gguf`, `UNKNOWN`.
- **Back.** System Back leaves the app (§6).

### Flow 23: Change persona

- **Path.** Drawer → `◈ Personas` → a screen that says `PERSONAS`.
- **Dead ends.** Total. No persona UI exists; every turn uses the hidden `Default` persona.
- **Confusing.** The drawer advertises a feature that does not exist, and the timeline's persona filter hides chats because they have `personaId = null`.

---

## 11. Visual hierarchy (§44)

**Chat tab.** What draws the eye first:

1. The chip in the command bar, a long bold monospaced id. It is louder than anything in the chat.
2. **Two** `$` prompts: one leading the command bar, one leading the composer.
3. The error banner (a full-width `errorContainer`) when it is present.

The conversation title does not exist (the header says `chat`). Messages do not dominate: both bubbles are `surface` on `background`, with 8 dp corners and no border, so the bubble edges barely show. The `⚹ context` glyph button competes with the title slot. The context panel's metadata (`score`, `recalled by`) is as loud as the source titles. Technical information outweighs user content in the chip and the context panel.

**Models overlay.** `Delete` is a **filled** primary `Button`. `Set default` is a quiet `TextButton`. So the destructive action is the most prominent control on every row (`ModelsScreen.kt:127-130`). The model name, a hashed slug, is the title.

**Settings.** All sections are equal weight. Inert placeholder rows look exactly like live rows (`settings/SettingsScreen.kt:252-280`). The red `Erase vault` is the most eye-catching row, and it is dead.

**Setup and unlock.** Setup is centred and intentional; it is the best hierarchy in scope. The unlock screen behind the prompt is only a spinner.

---

## 12. Responsive text and content (§45)

| Case | Result | File:line |
|---|---|---|
| Long model names | Chip is unbounded and can collapse the command field (P0, §5). Models row wraps the name beside two buttons. Status row wraps | `nav/CommandBar.kt:126-137`, `ModelsScreen.kt:110-131` |
| Long chat titles | n/a: every title is `Chat`. The header never shows the title anyway | `nav/BuiltinCommands.kt:33`, `chat/ChatScreen.kt:79` |
| Long source names | Context-panel title has no `maxLines` and no weight, so it pushes `score` to zero width | `chat/ContextPanel.kt:67-82` |
| Large font scale | Chip grows (worse P0). Composer row keeps working. Citation placeholder is sp-sized and scales. Theme options stay under 48 dp tall. Nothing is tested at font scale > 1 in this scope | as noted |
| Large messages | Streaming re-parses the whole message on every update (§13). Long answers stream off-screen | `chat/AssistantBubble.kt:116-134`, `chat/MessageList.kt:58-60` |
| Code blocks | A span inside one wrapping `Text`: no horizontal scroll, no copy, and a `[N]` inside code splits the block | `render/MarkdownRenderer.kt:93`, `chat/AssistantBubble.kt:54-67` |
| Markdown tables | Rendered as raw source | `MarkdownAst.kt:137`, `render/MarkdownRenderer.kt:101` |
| Citations | Single digits only (`RETRIEVAL_K = 8`); a 32 sp placeholder would clip `[10]`+. Grouped `[1, 2]` stays plain text | `chat/SendPipeline.kt:71`, `chat/AssistantBubble.kt:56` |
| Deeply nested lists | Renderer tracks depth (`render/MarkdownRenderer.kt:95-99`). Breaks if a citation falls inside the list | as noted |

---

## 13. Accessibility (§40) and UI performance (§46)

### 13.1 Accessibility

- **Touch targets under 48 dp.**
  - Citation chip, about 32 × 18 (`chat/AssistantBubble.kt:101-109, 261-262`).
  - Theme options, about 34 dp tall (`settings/AppearanceControls.kt:81`).
  - Context-panel rows, about 46 dp (`chat/ContextPanel.kt:66`).
  - The `IconButton`s and Material buttons meet the minimum.
- **Missing labels.**
  - 📎, ■ and ⏎ are emoji or glyph `Text` inside `IconButton`s with no `contentDescription` (`chat/ChatBottomBar.kt:199-217`).
  - `$` is read aloud (`:142-146`).
  - The composer has no label or hint.
  - The citation chip is read as `[1]` with no role.
  - The `⚹ context` toggle has no expanded or collapsed state.
- **Status announcements.** Zero `liveRegion` in scope (grep). Streaming, `thinking…`, the error banner and the import status row are all silent to TalkBack.
- **Headings.** No `heading()` semantics on the chat header, the Models title, or Settings section titles.
- **Switches** are unlabelled in Settings (§8).
- **Destructive confirmation.**
  - Model Delete: none.
  - Erase vault: dead.
  - Tab Close (shell): none needed.
  - Vault reset: exemplary.
- **Disabled state without a reason:** model `Delete`.
- **Colour-only signal:** the user bubble's queued/picked-up state.
- **Keyboard.**
  - Enter and Shift+Enter work.
  - No Esc to stop, no new-chat shortcut, no focus hand-off to the composer after `/chat`.
  - System Back does not close the `/models` overlay.

### 13.2 UI performance risks

**Streaming is quadratic in answer length.**

- Every ~30 ms update copies the full text (`chat/ChatViewModel.kt:203-204`).
- It re-splits on markers and re-parses every fragment's Markdown (`chat/AssistantBubble.kt:90, 116-134`).
- It rebuilds the inline-content map (`:92-114`).

That is O(n²) characters per turn, on the main thread, on a phone whose CPU is already saturated by inference.

**Possible main-thread blocking during send (needs profiling).**

- `feature/chat` contains no `flowOn` or dispatcher switch (grep). So `SendPipeline.send`'s `channelFlow` body runs on the collector's dispatcher, which is `ChatScreen`'s `rememberCoroutineScope()`, which is Main.
- `PromptAssemblerImpl.assemble` calls the synchronous `countTokens` many times (`core/rag/.../prompt/PromptAssemblerImpl.kt:95-114`).
- The app wires `countTokens` to a `runBlocking { withTimeoutOrNull(3000) { … } }` Binder bridge (`models/ModelServices.kt:348-356`).
- Every uncached count can therefore block the UI thread for up to 3 s: composer frozen, ■ unresponsive.

**Thinking ticker.** It writes `turnState` every 200 ms for a value nothing displays (`chat/ChatViewModel.kt:187-194`), so `MessageList` recomposes five times a second through the whole prefill, which can last minutes.

**N+1 reads on every message append.** Each emission of `observeMessages` re-projects **every** message and does one `getDocument` per citation (`chat/ChatViewModel.kt:129-141, 156-168`). Cost grows with chat length and citation count.

**Fine as they are:**

- `MessageList`, `ModelsScreen`, `AboutScreen` and `CommandPalette` are lazy lists with keys.
- `ContextPanel` is a non-lazy `Column`, but it is capped at 8 rows (the layout problem is covered in §4.8).

---

## 14. Preview, mock and test vs reality

| Where | Shows | Reality |
|---|---|---|
| `chat/ChatScreenPreviews.kt:104-113` | Composer with a `reply…` hint | The real composer has no hint |
| `nav/NavPreviews.kt:39` and elsewhere; `shell/SkeinApp.kt:195` | Chip reads `qwen · ●` | Running chip shows a 51-character slug-plus-hash or a 20-character filename prefix |
| `shell/tabs/TabStripPreviews.kt:26` | A chat tab titled `Research thread` | Always `Chat` |
| `ChatScreenTest.kt:101` | "tap opens preview, second tap shows excerpt" passes | Unreachable in the app: the preview tab replaces the chat content |
| `ChatScreenTest.kt:318-325` | Asserts the retry leaves **two** identical user rows | Pins the duplicate as intended behaviour |
| `settings/SettingsScreen.kt:195-220` | `SettingsRoute` wires About/NOTICE | Never called; the running app uses the bare `SettingsScreen` |
| `shell/SkeinApp.kt:180-183` | `MockTabContent` for chat tabs by default | Replaced in the app by `MainActivity.kt:641` |
| No previews exist for `ModelsScreen`, `VaultSetupScreen`, `BiometricUnlockScreen`, `VaultResetScreen`, or the chat's error / no-model / interrupted states | — | The model chip and Models screen have never been rendered at outer-display width with real data |

---

## 15. Ranked findings (§47)

IDs are stable for follow-up beads. "CMS" stands for Chat / Models / Settings.

### 15.1 P0

| ID | Finding | Evidence |
|---|---|---|
| CMS-P0-01 | An in-flight answer is **lost** (never persisted, no error) whenever `ChatScreen` leaves composition. Triggers: citation or context-row tap, tab switch, fold/unfold or rotation (no `configChanges`), deleting the default model, lock. The composer draft is lost the same way | `chat/ChatScreen.kt:59-71`; `chat/ChatViewModel.kt:196-242`; `chat/SendPipeline.kt:206, 243-286`; `shell/tabs/TabHost.kt:82-88`; `app/src/main/AndroidManifest.xml:81-88`; `chat/ChatBottomBar.kt:88` |
| CMS-P0-02 | A previous chat **cannot be reopened as a chat**. Timeline, long-press and search open every document as a NOTE tab, so chats land in the note editor as editable transcripts. `/chat` is the only CHAT-tab path, and open tabs die on every lock | `shell/SkeinApp.kt:226-231, 270`; `nav/BuiltinCommands.kt:35`; `MainActivity.kt:914-965` |
| CMS-P0-03 | **Chats cannot be deleted.** No UI calls `deleteDocument`, and every `/chat` leaves a permanent `Chat` document, even with no model | `core/model/.../Vault.kt:306` (0 callers); `nav/BuiltinCommands.kt:31-35`; `MainActivity.kt:567` |
| CMS-P0-04 | **Dead Settings rows**: `View NOTICE` (About/licences unreachable), `Export vault`, `Erase vault` | `settings/SettingsScreen.kt:137-138, 143`; `MainActivity.kt:682-685`; `settings/SettingsScreen.kt:195-220` (unused) |
| CMS-P0-05 | **Personas drawer entry is dead**: it shows the literal `PERSONAS`. The persona module is an empty placeholder and not in the app | `nav/NavDrawer.kt:30`; `MainActivity.kt:687`; `shell/SkeinApp.kt:429-441`; `feature/personas/.../Placeholder.kt:7` |
| CMS-P0-06 | **Model chip can collapse the command field on the Fold outer display.** The untruncated `<slug>-<hash> · ●` is unconstrained and measured before the weighted field. Computed, not yet observed; confirm with a screenshot | `MainActivity.kt:430-434`; `nav/CommandBar.kt:91-95, 125-137`; `shell/theme/SkeinTypography.kt:60` |
| CMS-P0-07 | **Chat composer's `[[` → `Create "x"` row does not create** the note it names | `chat/ChatScreen.kt:56`; `chat/ChatBottomBar.kt:85, 108-109`; `MainActivity.kt:589-600`; `feature/editor/.../WikilinkAutocompleteState.kt:126-128` |
| CMS-P0-08 | **Recovery-required dead end.** A text-only screen with no action, reached by enrolling a new fingerprint, locks the user out of the vault from the UI | `MainActivity.kt:1017-1028`; `core/vault/.../key/AndroidKeystoreFacade.kt:62`; `app/.../vault/GatePhase.kt:54` |

### 15.2 P1

| ID | Finding | Evidence |
|---|---|---|
| CMS-P1-01 | Every chat is titled `Chat`. The header is the literal `chat`. No generated or user titles, no rename in chat | `nav/BuiltinCommands.kt:33`; `chat/ChatScreen.kt:79` |
| CMS-P1-02 | No model identity in chat. The chip shows a raw id or filename, and the friendly metadata computed at import is discarded. `messages.model_id` is never written | §5; `ModelManager.kt:447-463`; `core/ipc/.../Parcels.kt:488-498`; `chat/SendPipeline.kt:277-285` |
| CMS-P1-03 | No activity or progress UI (§27). A single static `thinking…` covers load and prefill (minutes on device). `elapsedMs` is computed and hidden. No completion summary | `chat/ChatTurnState.kt:15-31`; `chat/AssistantBubble.kt:241-255`; `chat/ChatViewModel.kt:187-194` |
| CMS-P1-04 | ■ Stop is silently ignored before the stream registers (retrieval, load, assembly). Stopping during prefill leaves no trace | `core/inference/.../LlamaCppEngine.kt:474-478`; `chat/SendPipeline.kt:271-274` |
| CMS-P1-05 | Retry duplicates the user message, and a test pins it. No regenerate for completed or interrupted turns. The banner cannot be dismissed and does not name the cause | `chat/SendPipeline.kt:205-206`; `chat/ChatViewModel.kt:251-254`; `ChatScreenTest.kt:318-325`; `chat/ChatScreen.kt:36-37, 93-119` |
| CMS-P1-06 | The context panel shows **another chat's** retrieval and the previous turn's during a turn. It does not scroll and can collapse the message list. It uses RAG-internal labels | `chat/ChatViewModel.kt:142-147`; `chat/SendPipeline.kt:288-297`; `chat/ContextPanel.kt:44-84` |
| CMS-P1-07 | Citation second-tap excerpt is unreachable. Citations open non-note sources in the note editor. Chip target is about 32 × 18 | `chat/ChatViewModel.kt:265-278`; `shell/tabs/TabHost.kt:82-88`; `chat/AssistantBubble.kt:261-262` |
| CMS-P1-08 | Assistant text is not selectable: no copy anywhere, despite the code comment claiming otherwise | `chat/AssistantBubble.kt:168-176, 136-142` |
| CMS-P1-09 | Streaming does not follow the growing bubble. No live-region announcements | `chat/MessageList.kt:58-60`; grep `liveRegion` = 0 |
| CMS-P1-10 | Markdown in answers: a `[N]` split breaks bold, list and code structure. Tables render raw. Code blocks have no scroll or copy | `chat/AssistantBubble.kt:116-134`; `MarkdownAst.kt:137`; `render/MarkdownRenderer.kt:93, 101` |
| CMS-P1-11 | Composer: no hint or label; grows without bound; draft is not retained; a soft keyboard cannot insert a newline; no focus after `/chat`; `/` hook is a no-op | `chat/ChatBottomBar.kt:88, 148-198, 168`; `shell/input/SecureBasicTextField.kt` |
| CMS-P1-12 | 📎 attach: no progress, no error handling (uncaught), `ImportResult` ignored, not a per-message attachment. `[[Title]]` does not pin context | `chat/ChatBottomBar.kt:120-136`; `chat/ChatViewModel.kt:286-298`; `chat/SendPipeline.kt:214` |
| CMS-P1-13 | `Set default` does not switch the loaded model, and nothing says so. `/import model` silently changes the default | `models/ManagedInferenceEngine.kt:125-135`; `MainActivity.kt:528, 758-763` |
| CMS-P1-14 | Model `Delete`: one tap, no confirmation, a filled primary button, outcome ignored, disabled without a reason. Deleting the default mid-chat kills the chat (CMS-P0-01) | `ModelsScreen.kt:130`; `MainActivity.kt:764-770`; `ModelManager.kt:164-175` |
| CMS-P1-15 | Models is reachable only through `/models`. No drawer or Settings entry; Settings › Models shows inert placeholders with a bead id. System Back leaves the app | `MainActivity.kt:619-625`; `settings/SettingsScreen.kt:129-134`; no `BackHandler` (grep) |
| CMS-P1-16 | Model-chip semantics: LOADING reads "paused", ERROR reads "not loaded", READY and GENERATING look the same, and the chip is not tappable | `MainActivity.kt:430-435`; `models/ManagedInferenceEngine.kt:89` |
| CMS-P1-17 | Import and model error copy is implementation language (`StructurallyInvalid(NOT_GGUF)`, `FromStore(…)`, `UNKNOWN` licence) | `ModelManager.kt:770-776, 597`; `MainActivity.kt:542, 449` |
| CMS-P1-18 | No persona selection or visibility. The hidden `Default` persona has a null system prompt; `currentPersonaId = { null }`. Chats have a null persona, so they vanish under persona filters | `MainActivity.kt:599`; `PersonaServiceImpl.kt:169-184`; `nav/BuiltinCommands.kt:33` |
| CMS-P1-19 | No onboarding (§8.7). A first-time user is never offered "start a chat" or "add a model" outside slash commands | `feature/onboarding/.../Placeholder.kt:7`; `MainActivity.kt:582` |
| CMS-P1-20 | Unlock is biometric-only with no device-credential fallback. First run asks for three prompts back to back ("Step 2 of 2", then another) | `auth/BiometricUnlockScreen.kt:181, 220-227`; `MainActivity.kt:927-930` |
| CMS-P1-21 | Settings: `Biometric unlock — Coming in v1.1` contradicts the app. The Indexing info row reads like a setting. Switch rows are unlabelled for TalkBack | `settings/SettingsScreen.kt:118, 121-127`; `settings/LockPolicyControls.kt:117-134`; `settings/FlagSecureToggle.kt:35-52` |
| CMS-P1-22 | Missing `contentDescription` on 📎 ■ ⏎; `$` is read aloud; no heading semantics; the `⚹ context` toggle has no state | `chat/ChatBottomBar.kt:142-146, 199-217`; `chat/ChatScreen.kt:80-82` |
| CMS-P1-23 | The import status row overlays the chat composer; failure rows persist until dismissed | `MainActivity.kt:478-493, 783-814` |
| CMS-P1-24 | UI performance: O(n²) streaming re-parse; possible main-thread `runBlocking` token counting during send (profile first); 200 ms idle ticker; N+1 `getDocument` per emission | §13.2 |

### 15.3 P2

| ID | Finding | Evidence |
|---|---|---|
| CMS-P2-01 | The `$` glyph in the composer is terminal cosplay that competes with the command bar's `$`. Remove it | `chat/ChatBottomBar.kt:142-146` |
| CMS-P2-02 | Lower-case, terse copy: `chat`, `retry`, `generation failed, retry`, `interrupted — stopped`, `thinking…`, `context` | `chat/ChatScreen.kt:36-37, 79, 115`; `chat/AssistantBubble.kt:199, 249`; `chat/ContextPanel.kt:45` |
| CMS-P2-03 | Bubbles: user and assistant share the `surface` colour; no max width on the inner display; the queued vs picked-up state is colour-only | `chat/MessageList.kt:153-158`; `chat/AssistantBubble.kt:186` |
| CMS-P2-04 | Settings: Appearance label repeats the section title; theme options are under 48 dp and have no role; the Hardware (StrongBox/TEE) row belongs under Advanced; `View NOTICE` → "Open-source licences" | `settings/AppearanceControls.kt:46, 81`; `settings/LockPolicyControls.kt:108-115`; `settings/SettingsScreen.kt:143` |
| CMS-P2-05 | Vault reset's final destructive button uses primary colours rather than error colours | `auth/VaultResetScreen.kt:168-174` |
| CMS-P2-06 | Open-failure text exposes exception class names | `MainActivity.kt:1004-1008`; `app/.../vault/GatePhase.kt:73-80` |
| CMS-P2-07 | The unlock backdrop has no app identity: only a spinner and "Waiting for biometric authentication…" | `auth/BiometricUnlockScreen.kt:234-246` |
| CMS-P2-08 | Models rows have no detail view (quantization, context, imported date, hash under an Advanced disclosure) | `ModelsScreen.kt:100-132` |
| CMS-P2-09 | Glyph-as-icon everywhere (📎 ■ ⏎ ⚹ ◈) with inconsistent emoji and text-glyph mixing | `chat/ChatBottomBar.kt:203-216`; `shell/theme/SkeinTokens.kt:53-67` |

---

## 16. Recommendations, sequenced (UI-layer only)

1. **Lift chat state out of composition.** Put a session-scoped `ChatSession` registry keyed by `chatDocId` in `:app`, holding the turn state, the draft (in memory, never in the Bundle) and the send job. Stop wiring `ChatViewModel` to `rememberCoroutineScope`. This fixes P0-01 and also makes the citation-excerpt and draft behaviours work.
2. **Route documents by kind.** Timeline, search and graph opens should map `DocumentKind.CHAT` → `TabKind.CHAT` (a one-line mapping at `shell/SkeinApp.kt:226-241`, which the shell agent owns). Add "New chat" as a visible action (drawer or empty state).
3. **Chat header.** Show the title (generated from the first user message, editable in place), then a model label, then a `⋮` menu with Rename and Delete. Delete gets a confirmation dialog in the §29 wording (the storage contract comes from the storage agent). Stop creating a document until the first send.
4. **Model display name.** Persist `architecture`, `quantization` and `parameterCount` at import (additive fields on `Model`/`ModelRecord`, a storage migration). Derive "Qwen 2.5 3B" with "Local · Q3_K_M" as the secondary line. Make the chip `maxLines = 1` with ellipsis and a width cap, show the display name, and open a model sheet on tap. Put the id, hash and path in Advanced.
5. **Activity row.** Split `Thinking` into Loading model → Searching knowledge (n sources) → Reading prompt (with elapsed seconds) → Generating. Collapse it after completion into "Worked for Xs · N sources". Add a `liveRegion` on stage changes and on completion.
6. **Kill dead controls now.** Wire `SettingsRoute` (fixes NOTICE). Hide Export vault, Erase vault, the Settings › Models placeholders and "Biometric unlock v1.1" until they work. Hide the Personas drawer entry. Wire `onCreateWikilink` in `MainActivity` (the note editor's `createWikilink` is the pattern) or drop the Create row in chat.
7. **Recovery-required screen.** Offer restore-from-export (the flow already exists on setup) and a reset path.
8. **Models screen.** Friendly name, a "Loaded" / "Default" badge, a pending-switch message (or unload-and-reload on Set default), a Delete confirmation using the error colour, a `BackHandler`, and a drawer or Settings entry.

---

## 17. Could not determine / needs device verification

- **Actual chip width on the outer display.** The 411 dp width and the 7.4 dp-per-character figure are computed, not measured. A single screenshot with a loaded model settles CMS-P0-06.
- **Main-thread blocking during send.** Code-path analysis says `SendPipeline` runs on Main, but I did not profile how long the Binder token counts take (CMS-P1-24).
- **Uncaught 📎 import exceptions.** Whether any `ImportService` implementation throws, rather than returning a refused `ImportResult`, for realistic failures. I did not trace `ImportServiceImpl` internals.
- **Idle-lock during generation.** Whether an in-flight generation counts as activity for the idle timeout (`UnlockManager` internals were not audited). If it does not, a long prefill will always be killed by the 5-minute lock.
- **Fold-transition specifics** (a split pane moving between hosts, `AdaptivePaneHost` re-parenting) are the shell agent's to confirm. The loss mechanism in CMS-P0-01 holds for any path that disposes `ChatScreen`.
- **Handoff skein-gg11.23** ("Backlinks strip over the chat composer", handoff `:96`). I could not reproduce it from code alone; it depends on posture and split.
