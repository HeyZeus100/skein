# PocketPal AI: UX reference study for Skein

**Bead:** `skein-xtov.4` (epic `skein-xtov`). **Authority:** `docs/research/SKEIN_UI_UX_OVERHAUL_PROMPT.md` §§1–5, 26–27, 34, 37, 52–53.
**Scope:** this is the UX-layer study only. The engineering and licensing reconnaissance is `docs/research/POCKETPAL_RECON.md` (bead `skein-e8ly`, pinned at `v1.17.3` / `fa46438e`), and this study does not repeat it. Where a behaviour below has an engineering twin (stop responsiveness, interrupted turns, edit and regenerate, context-full), the recon's finding ID (`PP-nn`) is cited and its analysis stands.
**Where to start:** the centrepiece is **A1: the Skein activity block**, under *Answers to the prompt's specific questions*. The first four §52 sections set it up.

**Evidence used, in priority order:**
1. **Owner-supplied screenshots.** These outrank everything else.
   - [`pocketpal-screens/pocketpal-drawer.png`](pocketpal-screens/pocketpal-drawer.png) shows the drawer over a chat: destinations, a divider, and history grouped *Today* / *This week*.
   - [`pocketpal-screens/pocketpal-reasoning.png`](pocketpal-screens/pocketpal-reasoning.png) shows a chat mid-generation with the live *Reasoning* card.

   Both are 1320×2868 captures with an iOS status bar. PocketPal renders the same React Native UI on Android. The coordinator commits them at `docs/ux/research/pocketpal-screens/`, and this study does not copy them.
2. **Source at the pinned SHA.** Every UI claim below comes from here.
3. **The upstream README composite and the Play-store phone screenshots.** They are older than the source. For example, `screenshot-models.png` still lists the `Q4_0_8_8` quantizations that the recon's PP-44 marks as removed. Treat them as historical, not current.

---

## Project

- **Repository:** <https://github.com/a-ghorbani/pocketpal-ai>
- **Inspected:** release tag `v1.18.0`, commit `ebe1425a569c56496eda15ae3a471c84773fdb05`. The tag commit is dated 2026-09-25 10:47:37 +0000 (`chore(release): bump version to 1.18.0`). Inspected 2026-09-26.
- **Clone:** a shallow clone at `research/clones/pocketpal-ai/`. The path is git-ignored by `.gitignore:121` and was never committed.
- **Continuity with the recon pin:** I fetched `v1.17.3` and diffed the UX layer against `v1.18.0`. The diff covered ThinkingBubble, ReasoningBlock, Message, PendingIndicator, ChatHeaderTitle, SidebarContent, ChatInput, HeaderRight, ChatView, ChatSessionStore, theme, AssistantTurnFooter, ChatEmptyPlaceholder and ModelsScreen. Only two things changed:
  - `ChatInput.tsx` gained camera-error alerts (13 lines).
  - `ChatView/BannerRow.tsx` shows a context-fullness percentage (7 lines).

  The recon's UX-adjacent citations therefore still hold.
- **Stack:** React Native 0.82, React Native Paper, MobX, WatermelonDB and `llama.rn`. The recon rejects all of these for Skein (PP-76), and nothing here reopens that.
- **Permalink base:** `https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/`. Line numbers are those of this SHA.

## License

- **SPDX identifier:** `MIT`. The file is [`LICENSE`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/LICENSE), and GitHub also detects `mit`. The copyright line is `Copyright (c) 2024 Asghar Ghorbani`.
- **No code is proposed for copying in this study.** Every recommendation is a behaviour to reimplement natively in Compose.
- **The recon's licensing analysis stands:** attribution is required if any file is ever adapted, and MIT is on Skein's `foss` allowlist. See `POCKETPAL_RECON.md` §0.1 and §6.
- **UX assets are not licence-cleared for reuse.** The `Fraunces` / `Inter` / `JetBrains Mono` font assets, the icons and the brand imagery ship under their own licences. This study proposes reusing none of them.

## Maintenance

**Actively maintained, with a fortnightly release cadence:**

| Release | Date |
| --- | --- |
| `v1.17.2` | 2026-08-25 |
| `v1.17.3` | 2026-09-10 |
| `v1.18.0` | 2026-09-25 |

Other signals:
- 39 commits to the default branch between 2026-08-26 and 2026-09-26.
- 8,415 stars and 202 open issues (GitHub API, 2026-09-26).
- The repository was created 2024-08-25 and is maintainer-led.

**The UX layer shows deliberate iteration.** Style comments reference a numbered design pass. For example, `ThinkingBubble/styles.ts` cites *"Idea A"* for the text-only collapsed row and *"Idea G"* for tightened margins ([`styles.ts#L24-L28`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/components/ThinkingBubble/styles.ts#L24-L28), [`#L128-L143`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/components/ThinkingBubble/styles.ts#L128-L143)).

**Large-screen issues are open and unaddressed:**
- [#442](https://github.com/a-ghorbani/pocketpal-ai/issues/442) "Enhance Pal's screen on iPad".
- [#843](https://github.com/a-ghorbani/pocketpal-ai/issues/843), Enter-to-send on a hardware keyboard.

**Reasoning-extraction bugs are open:**
- [#484](https://github.com/a-ghorbani/pocketpal-ai/issues/484): Qwen3-VL's template embeds `<think>` in the prompt.
- [#735](https://github.com/a-ghorbani/pocketpal-ai/issues/735): GPT-OSS shows reasoning but not the answer.
- [#723](https://github.com/a-ghorbani/pocketpal-ai/issues/723): the thinking toggle.

## Relevant Skein Problem

PocketPal is the benchmark for Skein's **Level 1: Conversational** depth (prompt §3). It helps with five concrete problems in the current tree.

1. **Nothing tells the user what is happening during slow on-device work. This is the most acute of the five.**
   - Skein renders a static `thinking…` placeholder from `ChatTurnState.Queued` until the first answer token ([`feature/chat/.../AssistantBubble.kt:242-255`](../../../feature/chat/src/main/kotlin/app/skein/feature/chat/AssistantBubble.kt), [`ChatTurnState.kt`](../../../feature/chat/src/main/kotlin/app/skein/feature/chat/ChatTurnState.kt)).
   - On the Fold that window has exceeded ten minutes. Prompt processing runs in 512-token chunks on four CPU cores (commit `2d8d193`; [`inference-service/.../InferenceService.kt:516-539`](../../../inference-service/src/main/kotlin/app/skein/inference/service/InferenceService.kt)).
   - The pipeline knows a lot during that time, and none of it reaches the UI:
     - retrieval results (`SendPipeline.send`, [`SendPipeline.kt:200-217`](../../../feature/chat/src/main/kotlin/app/skein/feature/chat/SendPipeline.kt));
     - the engine state (`EngineState.LOADING`, [`core/model/.../Inference.kt:340`](../../../core/model/src/main/kotlin/app/skein/core/model/Inference.kt));
     - prefill chunk progress, which today is logcat only.

   PocketPal's reasoning card is the owner's stated reference: *"I like how it shows the reasoning as it's thinking of a response."*
2. **Chats are anonymous and undeletable.**
   - Every chat is created titled `Chat` ([`feature/shell/.../nav/BuiltinCommands.kt:33`](../../../feature/shell/src/main/kotlin/app/skein/feature/shell/nav/BuiltinCommands.kt)).
   - There is no rename or delete.
   - Chats live only in the mixed Timeline. The drawer lists destinations only: Timeline · Notes · Graph · Personas · Settings ([`NavDrawer.kt:27-32`](../../../feature/shell/src/main/kotlin/app/skein/feature/shell/nav/NavDrawer.kt)).

   PocketPal's drawer and header menu cover grouping, titling, pin, rename and delete end to end.
3. **The model is identified by a filename.**
   - The command bar prints `engineStatus.modelId` ([`MainActivity.kt:430-434`](../../../app/src/main/kotlin/app/skein/MainActivity.kt), [`CommandBar.kt:119-137`](../../../feature/shell/src/main/kotlin/app/skein/feature/shell/nav/CommandBar.kt)).
   - That ID is the slugified picked filename plus a 12-hex hash suffix ([`ModelManager.kt:340-368`](../../../core/inference/src/main/kotlin/app/skein/core/inference/models/ModelManager.kt), [`:623-631`](../../../core/inference/src/main/kotlin/app/skein/core/inference/models/ModelManager.kt), [`:666`](../../../core/inference/src/main/kotlin/app/skein/core/inference/models/ModelManager.kt)).

   PocketPal has exactly the same failure, visible in the owner's screenshot as `Qwen3.5-2B-Q4_K_M.` with a stray trailing period. It also has one correct answer, a curated `display_name`. The failure and the fix are both instructive. JAN.md §6.1 carries the full identity scheme.
4. **Empty states are written in implementation language.** Two examples:
   - `No tabs open — back to timeline` ([`TabHost.kt:103`](../../../feature/shell/src/main/kotlin/app/skein/feature/shell/tabs/TabHost.kt)).
   - `No models imported yet — use /import model` ([`feature/models/.../ModelsScreen.kt:81-82`](../../../feature/models/src/main/kotlin/app/skein/feature/models/ModelsScreen.kt)).

   PocketPal's empty chat is state-driven: no models, a model not loaded, or a model still downloading. Each state has exactly one next action.
5. **The composer is terminal glyphs.** Skein's bottom bar is `📎`, `■` and `⏎` as `Text` ([`ChatBottomBar.kt:203-215`](../../../feature/chat/src/main/kotlin/app/skein/feature/chat/ChatBottomBar.kt)). PocketPal's two-row composer shows how send↔stop, edit mode, attach and a capability-gated *Think* toggle can coexist on a 6.3″ screen.

## Patterns Worth Adopting

Each pattern is reimplemented in Compose. No code is copied.

| # | Pattern (PocketPal source) | What Skein takes | Skein target |
| --- | --- | --- | --- |
| W1 | **Reasoning is metadata, not a chat bubble.** Reasoning renders outside the bubble shell, with no author header ([`Message.tsx#L317-L340`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/components/Message/Message.tsx#L317-L340)). | The activity block sits on the chat surface above the answer. It is not a bubble. | `AssistantBubble.kt` |
| W2 | **Three-state reasoning card, PARTIAL while streaming.** The default is a 150 dp window that auto-scrolls to the newest line, with a 30 dp top fade mask ([`ThinkingBubble.tsx#L56-L58`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/components/ThinkingBubble/ThinkingBubble.tsx#L56-L58), [`#L211-L220`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/components/ThinkingBubble/ThinkingBubble.tsx#L211-L220), [`#L349-L376`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/components/ThinkingBubble/ThinkingBubble.tsx#L349-L376); [`styles.ts#L61-L63`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/components/ThinkingBubble/styles.ts#L61-L63), [`#L120-L127`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/components/ThinkingBubble/styles.ts#L120-L127)). This is what the owner liked. | A bounded, live, bottom-anchored reasoning window. The user sees thought happening without the page scrolling away. It is used only for real reasoning tokens (A1, lane B). | A1 |
| W3 | **Auto-collapse on the first answer token, unless the user touched it.** The card collapses on `hasContent \|\| step.partial === false` ([`Message.tsx#L352-L363`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/components/Message/Message.tsx#L352-L363)). A rising edge only. A `userToggledRef` makes a manual toggle win for that bubble's lifetime ([`ThinkingBubble.tsx#L63-L80`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/components/ThinkingBubble/ThinkingBubble.tsx#L63-L80)). | The exact rule, as `rememberSaveable` state keyed by message ID. An explicit user toggle is never overridden, including across a fold or unfold. | A1 |
| W4 | **The pending indicator covers every dead zone and hides while text streams.** It shows during `prefill`, `generating_tool_call` and `executing_tool`, and hides during `streaming_text` ([`ChatView.tsx#L812-L834`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/components/ChatView/ChatView.tsx#L812-L834)). | The "no dead air" invariant: from send to first token, something truthful is always on screen, and it gives way once answer text is visible. | A1 |
| W5 | **"Stopping…" is a state.** It appears the instant Stop is tapped and holds until the native loop actually exits ([`PendingIndicator.tsx#L83-L91`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/components/PendingIndicator/PendingIndicator.tsx#L83-L91), [`#L138-L140`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/components/PendingIndicator/PendingIndicator.tsx#L138-L140); [`ChatView.tsx#L825-L830`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/components/ChatView/ChatView.tsx#L825-L830)). | Skein needs this more than PocketPal does. `InferenceService` checks `request.cancelled` only between 512-token prefill chunks ([`InferenceService.kt:527-530`](../../../inference-service/src/main/kotlin/app/skein/inference/service/InferenceService.kt)), so on the Fold a Stop can take a whole chunk to land. Pairs with recon PP-61/PP-62. | A1, `ChatBottomBar.kt` |
| W6 | **Friendly step label, running count and elapsed seconds, shown only once they mean something.** For example: *Building page · 120 tokens · 4s*. Counts below 10 tokens and elapsed below 1 s are suppressed ([`PendingIndicator.tsx#L12-L30`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/components/PendingIndicator/PendingIndicator.tsx#L12-L30), [`#L115-L157`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/components/PendingIndicator/PendingIndicator.tsx#L115-L157)). The post-hoc form is *1,500 tokens · 35s* ([`ToolMetricsFooter.tsx#L17-L49`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/components/ToolMetricsFooter/ToolMetricsFooter.tsx#L17-L49)). | Product-language step names, a 1 Hz elapsed timer, and threshold-gated numbers. | A1 |
| W7 | **A typed event stream reduced by a pure reducer.** `AgentEvent` (`run_started`, `step_started`, `token`, `tool_call_*`, `run_finished`, `run_failed`) is reduced into `AgentUiState.status`. The reducer returns the same reference when nothing changed and has an exhaustive `never` default ([`AgentRunner.types.ts#L41-L80`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/services/agent/AgentRunner.types.ts#L41-L80), [`agentStateReducer.ts#L14-L103`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/services/agent/agentStateReducer.ts#L14-L103)). | A sealed `TurnActivityEvent` reduced into an immutable `TurnActivity`. Structural equality avoids recomposition on no-op events. Every step is traceable to a real event, which is the anti-fabrication guarantee. | A1 |
| W8 | **Reasoning text comes from the engine's reasoning field, never from the UI.** Deltas carry `reasoning_content` split out by llama.cpp's parser via `llama.rn` ([`AgentRunner.ts#L86-L87`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/services/agent/AgentRunner.ts#L86-L87)). The *Think* toggle appears only when a template probe says the model supports `enable_thinking` ([`utils/thinkingCapabilityDetection.ts`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/utils/thinkingCapabilityDetection.ts); [`ChatInput.tsx#L585-L640`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/components/ChatInput/ChatInput.tsx#L585-L640)). | Lane B renders only engine-classified reasoning. There is no Think control unless the loaded model can think, so no dead controls (§2). | A1, composer |
| W9 | **Two-line chat header: title over model.** `titleSmall` title over `bodySmall` model name, both single-line ([`ChatHeaderTitle.tsx#L18-L28`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/components/ChatHeaderTitle/ChatHeaderTitle.tsx#L18-L28)). There is a new-chat icon, and ⋮ opens *Generation settings · Model ▸ · Duplicate · Rename · Delete* ([`HeaderRight.tsx#L161-L236`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/components/HeaderRight/HeaderRight.tsx#L161-L236)). | This is exactly the prompt §22 sketch (`Architecture Chat` / `Qwen 2.5 3B · Local`). Skein keeps the structure and replaces the subtitle source with the friendly identity (JAN.md §6.1). | shell header |
| W10 | **Drawer = destinations, a divider, then history grouped by date, pinned first.** Items are *Chat, Pals, Models, Benchmark, Settings, App Info*, then *Pinned / Today / Yesterday / This week / …* ([`SidebarContent.tsx#L535-L608`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/components/SidebarContent/SidebarContent.tsx#L535-L608); [`ChatSessionStore.ts#L56-L67`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/store/ChatSessionStore.ts#L56-L67), [`#L1137-L1200`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/store/ChatSessionStore.ts#L1137-L1200)). The selected chat is a full-width pill (`pocketpal-drawer.png`). | Skein's compact-width drawer gains *Recent chats* under its destinations. On the unfolded Fold the same list becomes the persistent conversations pane (§21, §23). | `NavDrawer.kt` |
| W11 | **Per-chat actions from the list and from inside the chat.** Long-press a history row for *Pin/Unpin · Rename · Export · Delete · Select…*, which enters multi-select bulk delete/export ([`SidebarContent.tsx#L126-L191`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/components/SidebarContent/SidebarContent.tsx#L126-L191)). The same *Rename / Delete* also sits in the chat's ⋮ (W9). Delete is error-coloured and last, and a confirmation uses the destructive style ([`HeaderRight.tsx#L69-L92`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/components/HeaderRight/HeaderRight.tsx#L69-L92)). | Two entry points to the same actions, with destructive last and confirmed. This satisfies §28–29 ("`⋮ → Delete chat`"). | drawer row + chat header ⋮ |
| W12 | **Deterministic title from the conversation, set once, never overwriting a user title.** It is gated on the sentinel `New Session` and cut to 40 characters ([`ChatSessionStore.ts#L33-L34`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/store/ChatSessionStore.ts#L33-L34), [`#L463-L488`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/store/ChatSessionStore.ts#L463-L488)). | This fixes `Chat / Chat / Chat` with zero inference cost, which matters on a device where every prefill is expensive. See A3 for Skein's variant. |  |
| W13 | **Delete returns the user to a fresh chat, and derived UI state goes with the chat.** `deleteSession` resets the active session to a new chat, drops the per-chat draft and clears banner state ([`ChatSessionStore.ts#L335-L351`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/store/ChatSessionStore.ts#L335-L351)). Messages and per-chat settings are removed in one `database.write` ([`ChatSessionRepository.ts#L325-L359`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/repositories/ChatSessionRepository.ts#L325-L359)). | "Move user to a sensible next state… do not leave an orphaned tab" (§29). The transaction shape carries over. Skein's cascade is larger; see A3. |  |
| W14 | **Per-chat drafts.** A draft is saved when switching chats and restored on return ([`ChatSessionStore.ts#L1418-L1433`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/store/ChatSessionStore.ts#L1418-L1433); [`ChatView.tsx#L379`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/components/ChatView/ChatView.tsx#L379)). | Draft-per-chat keyed by document ID. It must also survive fold/unfold and process death (§24). PocketPal's map is in-memory only. |  |
| W15 | **Two-row composer.** The text area sits above a control row. Send and Stop share one slot. An edit banner reads *Editing message ✕*. Send is dimmed with a *Load a model first* helper when no model is active ([`ChatInput.tsx#L392-L411`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/components/ChatInput/ChatInput.tsx#L392-L411), [`#L359-L366`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/components/ChatInput/ChatInput.tsx#L359-L366), [`#L641-L690`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/components/ChatInput/ChatInput.tsx#L641-L690)). | This replaces `$ … 📎 ■ ⏎`. The edit semantics are the recon's PP-68/PP-69. | `ChatBottomBar.kt` |
| W16 | **One sheet for "who answers".** The composer's `^` opens a bottom sheet with *Models* / *Pals* tabs ([`ChatPalModelPickerSheet.tsx#L78-L98`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/components/ChatPalModelPickerSheet/ChatPalModelPickerSheet.tsx#L78-L98), [`#L225-L248`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/components/ChatPalModelPickerSheet/ChatPalModelPickerSheet.tsx#L225-L248)). | Skein's persona is a session parameter (§20). Model and persona are picked in one bottom sheet opened from the header subtitle, not from a primary destination. |  |
| W17 | **The message menu is filtered by author.** Every message offers *Copy*. The assistant's adds *Regenerate* and *Regenerate with ▸*. The user's adds *Edit* ([`ChatView.tsx#L664-L745`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/components/ChatView/ChatView.tsx#L664-L745)). | The same author filter. Semantics are PP-68 to PP-71, plus the in-flight guard the recon adds. |  |
| W18 | **Context budget in product language.** A banner at 80 % of `n_ctx` says *"This conversation is getting long and may soon run out of room."* When full: *"Start a new chat or increase the context size."* A dialog explains *"Room is the model's working memory"* with *"~N words · ≈X RAM"* and a *Fits* chip ([`bannerVariantResolver.ts#L3-L16`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/utils/bannerVariantResolver.ts#L3-L16), [`#L47-L110`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/utils/bannerVariantResolver.ts#L47-L110); [`en.json#L1196-L1210`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/locales/en.json#L1196-L1210)). | Skein's `ContextPanel` and `ContextBudget` show tokens. Adopt the *room* vocabulary and the 0.8 threshold for Level 1, and keep token counts in the inspector. Engineering twin: PP-66. | `ContextPanel.kt` |
| W19 | **State-driven empty chat.** It distinguishes *No Models Available* → *Download Model*, a model not loaded → *Select Model*, and a model downloading → *"{name} is getting ready"* ([`ChatEmptyPlaceholder.tsx#L43-L108`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/components/ChatEmptyPlaceholder/ChatEmptyPlaceholder.tsx#L43-L108)). | "Every empty state should answer: what should I do next?" (§33). Skein's variant is in A4. |  |
| W20 | **Middle ellipsis for model names.** `ellipsizeMode="middle"` keeps both the family prefix and the variant suffix visible ([`ModelCard.tsx#L702-L708`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/screens/ModelsScreen/ModelCard/ModelCard.tsx#L702-L708)). | Use it for the Details view's file-name row, the one place a long technical string must appear. Skein's Compose BOM `2026.09.00` (`gradle/libs.versions.toml:5`) already has single-line `TextOverflow.MiddleEllipsis`, so no custom code is needed. | Model Details |
| W21 | **Separate UI and code typefaces.** `Inter` for UI, `Fraunces` for display and `JetBrains Mono` for code, with absolute line heights ([`typography.ts#L28-L37`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/theme/tokens/typography.ts#L28-L37)). A 4/8/12/16/20/24/32/40 spacing scale ([`spacing.ts#L8-L19`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/theme/tokens/spacing.ts#L8-L19)). | Corroborates §36: use monospace only for code, identifiers and technical details, and a legible UI face everywhere else. Skein picks its own families. |  |

## Patterns Not Worth Adopting

| # | PocketPal pattern | Why Skein refuses it |
| --- | --- | --- |
| N1 | **Filename-derived model names.** | The owner's screenshot subtitle is `Qwen3.5-2B-Q4_K_M.`. The trailing period is a bug: `extractHFModelTitle` strips `[-_]?gguf$` but not the `.` before it ([`utils/index.ts#L401-L413`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/utils/index.ts#L401-L413), used at [`#L474`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/utils/index.ts#L474)). Locally imported models are named by the raw filename (`name: filename`, [`ModelStore.ts#L2947`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/store/ModelStore.ts#L2947)). Only curated presets get `display_name: "Gemma 3 1B"` ([`ModelStore.ts#L894-L896`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/store/ModelStore.ts#L894-L896), [`rules.android.json#L302-L303`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/store/bundledDeviceRules/rules.android.json#L302-L303)). PocketPal already reads `general.size_label`, but only to back-fill a parameter count ([`ModelStore.ts#L1921-L1930`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/store/ModelStore.ts#L1921-L1930)), never to name the model. Skein, like Skein Core, has no network catalog for picked files, so its "curated" path covers only the two bundled defaults. Skein needs the GGUF-metadata path Jan uses (JAN.md §6.1). |
| N2 | **The glowing, blue-tinted reasoning card.** | Dark theme: background `#142e4d`, text `#6abaff`, border `rgba(74,140,199,.6)`, and shadow `#4a9fff` at `shadowRadius 12` on iOS or `elevation 8` on Android ([`colors.ts#L266-L271`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/theme/tokens/colors.ts#L266-L271); [`styles.ts#L11-L43`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/components/ThinkingBubble/styles.ts#L11-L43)). Skein's identity is calm, restrained and research/terminal-flavoured: "no gradients or dropshadows" (spec §8.1), and "calm even when capability density is high" (§2.15). Keep the *behaviour* of W2. Render it on a flat `surfaceContainer` with a 1 dp `outlineVariant` border and muted `onSurfaceVariant` text. No glow and no accent fill. |
| N3 | **Spring-bounce toggle animations.** | These are a 450–600 ms spring with damping 0.7 and a chevron scale pop ([`ThinkingBubble.tsx#L87-L189`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/components/ThinkingBubble/ThinkingBubble.tsx#L87-L189), [`#L222-L262`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/components/ThinkingBubble/ThinkingBubble.tsx#L222-L262)). They are noisy on a surface the user taps often, and they ignore reduced-motion settings. Use a ≈200 ms standard-easing expand, no bounce, and respect the system's remove-animations setting. |
| N4 | **Collapsing to a bare "Reasoning" label.** | When done, the card becomes `› Reasoning` in 12 sp at 0.75 opacity ([`ThinkingBubble.tsx#L272-L301`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/components/ThinkingBubble/ThinkingBubble.tsx#L272-L301); `en.json` `thinkingBubble.reasoning`). It carries no duration, no source count and no outcome. The collapsed row is the one line users see on every past turn, and PocketPal wastes it. Skein's collapsed row is the prompt §27 summary (`▸ Worked for 8.1s · 7 sources · 2 notes`), and Jan's `Worked for …` header (JAN.md) shows the timing half of it. |
| N5 | **Throughput metrics in the default message footer.** | `31ms/token, … tokens per second, TTFT` appears under every assistant turn ([`AssistantTurnFooter.tsx#L51-L113`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/components/AssistantTurnFooter/AssistantTurnFooter.tsx#L51-L113); visible in `pocketpal-drawer.png`). This is implementation language on the Level 1 surface. In Skein these numbers live in the expanded activity block and the context inspector. |
| N6 | **Sub-minimum touch targets.** | The composer's `+`, pal and Think controls are 28×28 ([`ChatInput/styles.ts#L17-L40`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/components/ChatInput/styles.ts#L17-L40)). The collapsed reasoning row is 12 sp text with an 8 dp `hitSlop`. Skein's minimum is 48 dp (Material and §36). |
| N7 | **Rename and delete hidden behind long-press on the list.** | The history row has no visible affordance. PocketPal mitigates this with the header ⋮. On the unfolded Fold, Skein's persistent list should show a trailing ⋮ on the selected or focused row and keep long-press as an accelerator. |
| N8 | **Nine date buckets.** | *Today · Yesterday · This week · Last week · 2 / 3 / 4 weeks ago · Last month · Older* ([`ChatSessionStore.ts#L1143-L1170`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/store/ChatSessionStore.ts#L1143-L1170)). "This week" means "≤ 6 days ago", not the calendar week, so the label misleads. Skein uses *Pinned · Today · Yesterday · Previous 7 days · Previous 30 days · by month*. |
| N9 | **An ellipsis baked into the stored title.** | The title stored is `substring(0, 40) + "..."` (W12). The dots then enter rename fields, search and exports. Store the clean string and let `TextOverflow.Ellipsis` truncate at render time. |
| N10 | **Generic delete copy.** | *"Delete Chat" / "Are you sure you want to delete this chat?"* ([`en.json#L763-L764`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/locales/en.json#L763-L764)) does not say which chat or what happens. Skein uses §29: *Delete "Skein UX redesign"? This removes the conversation from Skein.* |
| N11 | **Screen width read once at module load.** | `const screenWidth = Dimensions.get('window').width` sizes the drawer ([`App.tsx#L56`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/App.tsx#L56), [`#L117-L121`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/App.tsx#L117-L121)). It never reacts to fold or unfold. The drawer is always modal: there is no permanent or list-detail layout, which is issue #442. Skein must derive everything from `WindowSizeClass` and posture at composition time (§24). |
| N12 | **Benchmark, App Info and Dev Tools as primary destinations.** | Six top-level drawer items, two of them developer-facing. Per §20, Skein's primary navigation stays small. Benchmarks belong in the developer surface; `tools/m0-benchmark` already covers the device lane (recon PP-51). |
| N13 | **Regenerate with ▸ ⟨every model⟩.** | This is cheap on PocketPal's single in-process context. In Skein a model swap is an isolated-process warm swap costing 2–5 s of TTFT plus a full re-prefill (spec §6). Offer it only from the model sheet, with the cost stated, not as a one-tap menu item. |
| N14 | **Engineering anti-patterns already rejected by the recon.** | Regex-matched context-full (PP-67), fail-open templating (PP-33), telemetry and Firebase (PP-54, PP-76). Listed here so the UX work does not reintroduce them through a banner or copy path. |

## Potential Reusable Code

**None.** The UI is React Native, React Native Paper and MobX. Compose cannot import any of it, and the recon rejects the stack (PP-76).

What transfers is **values and rules**. These are facts, not copyrightable expression, and MIT would permit adaptation with attribution anyway:

| Value or rule | Source | Skein use |
| --- | --- | --- |
| Reasoning window 150 dp tall, 30 dp top fade, auto-scroll to end | `ThinkingBubble/styles.ts#L61-L63`, `#L120-L127` | A1 lane B, starting values for the prototype |
| Auto-collapse predicate `hasContent \|\| !partial` plus the "user toggle wins" latch | `Message.tsx#L356`, `ThinkingBubble.tsx#L63-L80` | A1 |
| Streaming UI write coalescing at 30 ms (≈33 Hz) | `ChatSessionStore.ts#L36-L37` (recon PP-63) | already recon-owned |
| Pending-count threshold of 10 tokens; elapsed shown from 1 s at a 1 Hz tick | `PendingIndicator.tsx#L16`, `#L115-L129` | A1 |
| Context warning at 0.8 × `n_ctx`; auto-clear runway of 256 tokens | `bannerVariantResolver.ts#L3-L7` | `ContextPanel` Level 1 banner |
| Title limit of 40 characters, set once from the first message, sentinel-gated | `ChatSessionStore.ts#L33-L34`, `#L463-L488` | A3 (with N9 fixed) |
| Date-bucketing function shape (pinned first, then ordered buckets) | `ChatSessionStore.ts#L1137-L1200` | A3 (with N8 fixed) |

## Android/Fold Relevance

**Folded (outer screen, Compact width): high relevance.** PocketPal is a single-pane phone app, and the owner's screenshots are phone-width captures. These map straight onto the outer display's one-dominant-workspace rule (§22):
- the header (W9);
- the drawer with grouped history (W10);
- the two-row composer (W15);
- the bottom-sheet pickers (W16);
- the inline activity and reasoning (W1–W6).

**Unfolded (inner screen, Medium or Expanded width): low direct relevance.**
- PocketPal has no tablet or foldable layout. The drawer is always modal and its width is frozen at launch (N11).
- The message column is capped at `min(0.92 × width, 900)` ([`ChatView.tsx#L838-L843`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/components/ChatView/ChatView.tsx#L838-L843)). That is too wide for comfortable reading on the Fold's inner display.

Skein takes the *content* of PocketPal's drawer (W10) and presents it as the persistent left pane of `Conversations | Active Conversation | Context` (§21). Layout mechanics come from the Android adaptive samples, not from PocketPal.

**Live fold transitions:** PocketPal gives no guidance here. W3's "user toggle wins" state and W14's drafts are the two pieces of PocketPal state Skein must carry across a fold/unfold without a reset (§24).

**Android specifics:** PocketPal enables `LayoutAnimation` experimentally on Android ([`ThinkingBubble.tsx#L23-L29`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/components/ThinkingBubble/ThinkingBubble.tsx#L23-L29)) and uses elevation for the glow. Neither transfers. Compose's `animateContentSize` and `AnimatedVisibility` are the native equivalents.

## Mac UX-Lab Relevance

**Moderate. Useful as target states, not as tooling.**
- **The two owner screenshots become the first two §37 Layer A preview fixtures.** Build Compose `@Preview`s of Skein's own versions of both. Use fixture data only; the pipeline is not needed.
  - `pocketpal-drawer.png` becomes *drawer open over a chat*: destinations, a divider, *Today* with a selected pill, *This week*.
  - `pocketpal-reasoning.png` becomes *mid-generation*: the header with title over friendly model, a right-aligned user bubble, the live reasoning window, and the composer with Stop.
  - Render each at the Fold outer width and the inner width, and baseline them in Roborazzi (§39). This gives the owner a same-scene comparison between PocketPal and Skein.
- **PocketPal's e2e visual capture uses a real model.** `e2e/specs/visual-capture.spec.ts` takes screenshots for PR review, but it downloads and loads a real small model (`smollm2-135m` by default) on a real device or device pool (`e2e/DEVICE-POOLS.md`) ([`visual-capture.spec.ts#L1-L30`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/e2e/specs/visual-capture.spec.ts#L1-L30)). That is the opposite of what a MacBook loop needs. Skein should prefer fixture state (JAN.md §6.6).
- **One structural idea is worth copying: an automation bridge that cannot ship.** Its build-time contract is `__E2E__`-gated code, dead-code eliminated from production and enforced by a lint rule ([`src/__automation__/README.md`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/__automation__/README.md)). Skein already has a `src/debug/` source set for previews (`feature/timeline/src/debug/.../TimelineScreenPreview.kt`). Keep every UX-lab fixture and fake there, never in `main`.

## Recommended Action

**ADOPT: interaction patterns W1–W21, reimplemented natively in Compose. No code.**
- The **activity block (A1)** enters through a **PROTOTYPE** gate: Layer A previews plus a Fold device check. Only then does it replace `ThinkingPlaceholder`.
- **Everything in N1–N14 is REJECTED.**

## Expected Benefit

- **The slow-prefill problem becomes legible.** The user sees *Started model → Searched your knowledge (7 passages) → Reading your message · 1:08 → Writing*, with an honest elapsed time and a responsive *Stopping…*. Today they see a frozen `thinking…`. This is the single largest perceived-quality gain available without touching inference performance.
- **Reasoning models get the live window the owner asked for, without fabrication.** Lane B appears only when tokens are classified as reasoning.
- **§28, §29 and §33 get a proven, phone-sized template.** That covers named, grouped, renamable, deletable chats plus state-driven empty states.
- **The chat header matches §22 exactly**, and the model subtitle becomes human-readable once the JAN.md §6.1 identity lands.

## Expected Cost

| Item | Cost | Notes |
| --- | --- | --- |
| Activity block, phase 1: UI-only events from `SendPipeline`, `EngineState` and `ChatTurnState` | M (≈3–5 d) | Pure reducer, Compose component, previews, Roborazzi. No inference change. |
| Activity block, phase 2: prefill progress | S in UI, plus a **separate seam bead** for the inference owners | One `oneway` AIDL callback carrying counts only. `IInferenceCallback` has only `onTokens`, `onDone` and `onError` today ([`IInferenceCallback.aidl:12-23`](../../../core/ipc/src/main/aidl/app/skein/ipc/IInferenceCallback.aidl)). |
| Activity block, phase 3: reasoning lane | S in UI, plus a **separate seam bead** | The locked `Token`/`Segment` contracts have no reasoning case (`ChatTurnState.kt` header). Parsing belongs in one place, with PP #484 and #735 as test fixtures. |
| Summary persistence (duration, per-step times, counts) | S, plus a narrow storage decision | Jan's `useCoTDuration` shows why: without it, reloaded turns say "a while". |
| Drawer history, titles, rename/delete UI | M | The delete contract (§29) is a separate audit. |
| Composer rebuild | S–M | 48 dp targets, capability-gated Think. |

---

## Answers to the prompt's specific questions

### A1: Reasoning and activity presentation: the Skein activity block

#### What PocketPal actually does (from source; matches `pocketpal-reasoning.png`)

1. **While the model is reading the prompt:**
   - Three pulsing dots appear below the latest turn (`PendingIndicator`).
   - There is no label and no timer during `prefill`. The label and timer exist only for tool calls ([`PendingIndicator.tsx#L112-L157`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/components/PendingIndicator/PendingIndicator.tsx#L112-L157)).
   - **This is PocketPal's weakest state, and it is Skein's longest state.**
2. **When the first reasoning token arrives:**
   - The reducer flips to `streaming_text`, which hides the dots ([`agentStateReducer.ts#L55-L65`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/services/agent/agentStateReducer.ts#L55-L65)).
   - A `ReasoningBlock` mounts in the **PARTIAL** state. This is the card in the owner's screenshot: a *Reasoning* header, a chevron in a 28 dp circle, and a 150 dp window rendering the reasoning as Markdown. The window auto-scrolls to the newest line, and its top 30 dp fades out through a `MaskedView` gradient.
   - The text is not selectable ([`ReasoningBlock.tsx#L28-L77`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/components/ReasoningBlock/ReasoningBlock.tsx#L28-L77); [`ThinkingBubble.tsx#L303-L384`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/components/ThinkingBubble/ThinkingBubble.tsx#L303-L384)).
3. **User taps:** these cycle **COLLAPSED → PARTIAL → EXPANDED → COLLAPSED** (full height, no mask) and latch "user toggled" ([`ThinkingBubble.tsx#L150-L165`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/components/ThinkingBubble/ThinkingBubble.tsx#L150-L165), [`#L264-L270`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/components/ThinkingBubble/ThinkingBubble.tsx#L264-L270)).
4. **When the first answer token arrives or the step finalises:**
   - The card auto-collapses to a text-only inline row, `› Reasoning`, unless the user has toggled it.
   - The answer streams in a normal bubble below it.
5. **After completion:**
   - The collapsed row stays `› Reasoning` forever. **There is no timing, no summary and no step history.**
   - The footer shows `ms/token · tokens/sec · TTFT`, plus *interrupted* or *truncated* when relevant ([`AssistantTurnFooter.tsx#L34-L127`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/components/AssistantTurnFooter/AssistantTurnFooter.tsx#L34-L127)).
6. **After Stop:** the dots return with *Stopping…* until the native loop exits.
7. **Where the reasoning comes from:** the engine's `reasoning_content` field, never the UI (W8). With a non-thinking model, or with *Think* off, no card appears at all.

**Summary:** PocketPal does the *live reasoning window* well (owner-endorsed) and the *dead zone* and *afterwards* poorly. Skein's version keeps the first and fixes the other two. The fixes come from Jan: `PromptProgress` for the dead zone, and `ChainOfThought` with persisted *Worked for …* durations for the afterwards (JAN.md, *Patterns Worth Adopting* J5–J6).

#### The Skein design: two lanes in one block

The **activity block** is one metadata element above each assistant answer. It is not a bubble (W1). It has two lanes.

**Lane A: Activity. Always present, driven only by real pipeline events.** Each step exists only if its event fires, and a step's label changes only when its source event says so.

| Step (product label) | Real source today | Live detail | Completed detail |
| --- | --- | --- | --- |
| **Starting model** | `EngineState.LOADING` → `READY` ([`Inference.kt:340`](../../../core/model/src/main/kotlin/app/skein/core/model/Inference.kt)) | elapsed timer | `Started model · 3.4s` |
| **Searching your knowledge** | `RetrievalService.retrieveContext` in `SendPipeline.send` ([`SendPipeline.kt:214`](../../../feature/chat/src/main/kotlin/app/skein/feature/chat/SendPipeline.kt)) | elapsed | `Found 7 passages in 3 notes`, counted from `List<Retrieved>` (`chunkId`, `docId`, `sourceKind`; [`Retrieval.kt:96-103`](../../../core/model/src/main/kotlin/app/skein/core/model/Retrieval.kt)). The step is omitted when retrieval does not run. If nothing matches: *No matching notes*. |
| **Reading your message** (prefill) | Phase 1: `ChatTurnState.Thinking.elapsedMs` after the stream starts. Phase 2: a prefill-progress callback (seam). | Phase 1: `Reading your message · 1:08`, with an indeterminate bar and no percentage. Phase 2: `2.1k / 3.4k · about 40s left`, with ETA extrapolated from the measured chunk rate (Jan's `PromptProgress`). | `Read your message · 1m 12s · 3,412 tokens` |
| **Thinking** (only for reasoning models) | Phase 3: reasoning segments from the engine (seam) | opens lane B | `Thought for 12s` |
| **Writing answer** | first visible answer token, `ChatTurnState.Streaming` | The step line hides while the answer is visible, because the text itself is the progress indicator (W4). | `Wrote 212 tokens · 11.7 tok/s` (`GenStats`, [`Parcels.kt:583-589`](../../../core/ipc/src/main/kotlin/app/skein/ipc/Parcels.kt)) |
| **Stopping…** | Stop tapped, before the service acknowledges | `Stopping… finishing the current step`. Required because cancellation lands only between 512-token chunks (W5). | `Stopped after 1m 31s` |
| **Couldn't finish** | `ChatTurnState.Failed(InferenceException)` with a typed `ErrorCode`, never a message string (PP-67) | none | `Couldn't finish · Out of room` or similar, mapped from the `ErrorCode` |

Future steps plug in with the same event/step contract: *Reading note*, *Traversing graph*, *Running skill ⟨name⟩ (needs approval)*, and *Sending to ⟨provider⟩* for future external compute. They are **not shown until they exist** (§2: no dead controls).

**Lane B: the model's reasoning. Present only when the engine classifies tokens as reasoning.**
- **Source:** a reasoning segment from the inference side (phase 3 seam). This comes from llama.cpp's reasoning parsing or a single, tested `<think>…</think>` splitter. The UI never infers reasoning from answer text, never summarises it with another model call, and never shows it for a model that emitted none. The default Qwen 2.5 3B emits none, so for today's default model **lane B never appears**.
- **Presentation (W2 and W3, restyled per N2 and N3):**
  - A bounded, bottom-anchored, auto-following window of about 6 lines on the outer screen and 8 on the inner screen, with a top fade, labelled **"Model's reasoning"**. The label is honest: this is text the model generated, not verified steps.
  - The text is selectable, which PocketPal's is not, and uses muted colour.
  - The window auto-collapses on the first answer token unless the user touched it.
- **Storage:** stored with the message, separate from `content_md`. It is **excluded from RAG indexing, citations, backlinks and exports by default.** It is not the answer and may contain wrong claims. Whether prior-turn reasoning is stripped from the next prompt is an inference-owner decision, flagged, not decided here.
- **Failure mode to design for:** a model opens `<think>` and exhausts its budget before answering (PocketPal #735; Jan #8530/#8025). Lane B then shows the reasoning, and the answer slot says *"The model ran out of room before answering"* with *Try again with more room*. It must never be an empty bubble.

#### The collapsed summary (every completed turn)

```text
▸ Worked for 1m 31s · 3 sources
```

- **"Worked for"** is wall-clock time from send to `Done`. It includes model start, retrieval, reading and writing. That makes it true, and it is the number the user actually waited.
- **Sources** is the count of distinct documents that were retrieved or cited. Prefer *cited*, because citations are validated against the marker offer (`CitationParser`). Fall back to *retrieved* when no citation was emitted, and label it accordingly: `· 7 passages searched`.
- **Optional parts:** `· Thought 12s` when lane B ran, `· Stopped` or `· Couldn't finish` for those outcomes.
- **Calm rule for Level 1 (decide during PROTOTYPE):** omit the summary when the turn took under ~3 s, used no sources, had no reasoning, and finished normally. Every short chit-chat reply would otherwise carry a `Worked for 1.2s` line.
- **Persistence:** persist the summary fields with the message. Jan's `useCoTDuration` exists because an unpersisted live measurement degrades to "a while" on reload. Skein should not start there.

#### Expanded view (tap the summary; ≥ 48 dp row, `Role.Button`)

```text
▾ Worked for 1m 31s · 3 sources
  ✓ Started model                         3.4s
  ✓ Searched your knowledge                0.4s
     7 passages · 3 notes
  ✓ Read your message                   1m 12s
     3,412 tokens
  ✓ Wrote answer                          15s
     212 tokens · 11.7 tok/s
  Sources
     [1] Oyster mushroom log        › (opens preview tab)
     [2] Substrate notes            ›
     [3] 2026-08 grow journal       ›
  Model's reasoning                         ▸   (only if lane B ran)
```

**Formatting:**
- Numbers use tabular figures in the UI face.
- Monospace is reserved for the token counts only if the design system (§36) decides so.
- Technical rates (tok/s) appear only here, never in the default footer (N5).

#### Live states on the outer screen (Compact width, ≈ 411 dp)

```text
┌──────────────────────────────────┐        ┌──────────────────────────────────┐
│ ☰  Mycology research       ✎  ⋮ │        │ ☰  Mycology research       ✎  ⋮ │
│    Qwen 2.5 3B · Local           │        │    Qwen 3 4B · Local             │
├──────────────────────────────────┤        ├──────────────────────────────────┤
│        Summarise my notes on     │        │        Which substrate won?      │
│        oyster mushrooms.         │        │                                  │
│                                  │        │  Working · 0:22                  │
│  Working · 1:12                  │        │  ✓ Read your message       9.8s  │
│  ✓ Started model           3.4s  │        │  ● Thinking                 12s  │
│  ✓ Searched your knowledge 0.4s  │        │  ┌ Model's reasoning ───── ▾ ┐  │
│    7 passages · 3 notes          │        │  │ ░░ (fade) ░░░░░░░░░░░░░░░ │  │
│  ● Reading your message    1:08  │        │  │ Note [2] logs a 14-day    │  │
│    ▬▬▬▬▬▬▬▭▭▭  (phase 2 only:    │        │  │ colonisation on straw vs  │  │
│    2.1k / 3.4k · ~40s left)      │        │  │ 21 on hardwood, so…       │  │
│  ○ Writing answer                │        │  └───────────────────────────┘  │
├──────────────────────────────────┤        ├──────────────────────────────────┤
│  ＋   Ask Skein…             ■   │        │  ＋   Ask Skein…             ■   │
└──────────────────────────────────┘        └──────────────────────────────────┘
      Qwen 2.5 3B, no reasoning                  reasoning-capable model
```

**Unfolded:** the block is identical inline in the conversation. The context inspector's **Activity** section (§35) shows the same `TurnActivity` for the selected turn. It is a second *view* of the same state, not a second set of controls.

#### Implementation shape (for the UX workstream; no inference refactor)

```kotlin
// feature/chat — pure, previewable, Roborazzi-testable.
sealed interface TurnActivityEvent {
    data object ModelStarting : TurnActivityEvent
    data object ModelReady : TurnActivityEvent
    data object RetrievalStarted : TurnActivityEvent
    data class RetrievalFinished(val passages: Int, val documents: Int, val notes: Int) : TurnActivityEvent
    data object PrefillStarted : TurnActivityEvent
    data class PrefillProgress(val processed: Int, val total: Int, val elapsedMs: Long) : TurnActivityEvent // phase 2 seam
    data class ReasoningDelta(val text: String) : TurnActivityEvent                                   // phase 3 seam
    data object FirstAnswerToken : TurnActivityEvent
    data object StopRequested : TurnActivityEvent
    data class Finished(val outcome: Outcome, val stats: GenStatsUi?) : TurnActivityEvent
}
fun reduce(state: TurnActivity, event: TurnActivityEvent, nowMs: Long): TurnActivity // W7: no-op returns same instance
```

**Where events come from:**
- `SendPipeline` emits the retrieval and prefill events next to the calls it already makes.
- `ChatViewModel` already owns `ChatTurnState` and the `elapsedMs` ticker. It folds these events into one `StateFlow<TurnActivity>`.
- **`ChatTurnState` stays the seam.** The activity block replaces `ThinkingPlaceholder`, which is what `skein-cmoe` ("thinking-orbs") was reserved for (`ChatTurnState.kt` header). That bead should be re-scoped to this design rather than to an animation.

**Required states for §37 Layer A previews and Roborazzi baselines:**
1. queued
2. starting model
3. searching
4. reading, indeterminate, at 0:05 and 3:40
5. reading with progress (phase 2)
6. thinking plus the reasoning window
7. writing
8. stopping
9. stopped
10. failed: out of room
11. completed and collapsed
12. completed and expanded
13. reasoning-only exhaustion

Each state is rendered at outer, inner-portrait and inner-landscape sizes, with the font scale at 1.0 and 1.3.

**The fake engine needs three additions.** `FakeInferenceEngine` already supports `script` and `tokenDelay` ([`testing/.../FakeInferenceEngine.kt:67-69`](../../../testing/src/main/kotlin/app/skein/testing/FakeInferenceEngine.kt)). Add `prefillDelay`, a scripted reasoning span, and stop-during-prefill. JAN.md §6.6 has the full list.

**Accessibility:**
- The collapsed row's content description is *"Worked for 1 minute 31 seconds, 3 sources. Show activity."*
- Step changes are announced through a polite live region, **one announcement per step and never per token**.
- The pulsing active dot becomes a static dot when animations are off.

### A2: Chat layout, typography, spacing, touch targets, negative space

**Layout and alignment:**
- User messages are right-aligned in a compact rounded bubble.
- Assistant text is left-aligned and **unbubbled** in the current design. In `pocketpal-drawer.png` the answer runs full-width on the surface, and the metadata footer sits under it.
- Reasoning and footers are chrome, not content (W1).
- This asymmetry is what makes the screenshot calm: only the user's words are boxed. **Recommend it for Skein.** Skein's `AssistantBubble` currently wraps answers in a `Surface` with 8 dp corners, and dropping that shell for assistant turns removes a layer of visual noise.

**Measure:** the message width is `min(0.92 × W, 900)`, or `0.9 ×` with avatars ([`ChatView.tsx#L838-L843`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/components/ChatView/ChatView.tsx#L838-L843)).
- On the outer screen, keep about 92 % of the width.
- On the inner screen, cap the reading column in dp (≈ 640–720 dp) rather than as a percentage, because the inner display is nearly square.

**Scales:** spacing is 4/8/12/16/20/24/32/40 and radii 2/4/8/12/16/20/32/40 ([`radius.ts`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/theme/tokens/radius.ts)). Both are ordinary and fine as a sanity check for §36. Skein should derive its own.

**Touch targets:** PocketPal's 28 dp composer controls and 12 sp collapsed row are below minimum (N6). Skein's minimum is 48 dp.

**Negative space:** the empty-chat state vertically centres a logo plus one sentence plus one button ([`ChatEmptyPlaceholder.tsx#L84-L108`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/src/components/ChatEmptyPlaceholder/ChatEmptyPlaceholder.tsx#L84-L108)). Once a model is active, the empty chat is a blank surface above the composer (`hasActiveModel → <View />`). Skein's §33 empty state adds *Recent* and *New note*, but should keep the same single dominant action.

### A3: Navigation drawer, conversation grouping, titles, rename and delete

**Proposed compact-width drawer (outer screen):**

```text
┌──────────────────────────────┐
│  🔍 Search chats              │   ← PocketPal has none; §28 requires it
│  ＋ New chat                  │
│  Chat · Knowledge · Graph     │   ← destinations (final set is the IA bead's call, §20)
│  ──────────────────────────   │
│  Pinned                       │
│    RAG architecture           │
│  Today                        │
│  ▌ Skein UX redesign        ▌ │   ← selected = full-width pill (pocketpal-drawer.png)
│    Mycology research          │
│  Previous 7 days              │
│    Continue repo analysis     │
│  August                       │
│  ──────────────────────────   │
│  Models            Settings   │   ← secondary destinations, bottom
└──────────────────────────────┘
```

**Unfolded:** the same list is the persistent left pane, and the drawer disappears. This avoids duplicate navigation (§2.13).

**Grouping** uses PocketPal's shape (pinned first, then ordered buckets) with Skein's bucket set (N8).

**Titles.** Skein's variant of W12 has three steps:
1. On the first user send, derive a title deterministically:
   - strip Markdown, `[[wikilinks]]` brackets and slash-command prefixes;
   - take the first sentence;
   - cut at a word boundary at ≤ 40 characters;
   - store it without an ellipsis (N9).

   This costs nothing and is immediate.
2. **Optional, and a PROTOTYPE decision.** Later, while the device is idle, *upgrade* an auto-title with a model-generated one, as Jan's summarizer does (JAN.md). Only do this if the title is still auto-generated, and only when the engine is idle. On the Fold, every model call costs a full prefill, so this must never contend with a user turn, and it may reasonably stay off.
3. A user rename is final. The auto-title never overwrites it; both PocketPal and Jan gate on "still default".

**Rename:** from the drawer row (long-press, or ⋮ when unfolded) and from the chat's ⋮. Use a dialog with the current title preselected, and Save disabled when the field is blank.

**Delete:**
- **Where:** from the same two places, destructive-styled and last in the menu.
- **Dialog:** a §29 dialog naming the chat (N10).
- **After deleting:** navigate to a fresh chat, like PocketPal's `resetActiveSession` (W13). Close any tab showing the chat and remove it from search and the timeline.
- **Cascade: Skein's is strictly larger than PocketPal's.** Spec §5 has these tables:
  - `messages`, `chunks` and `ingest_queue` cascade from `documents` through `ON DELETE CASCADE`.
  - **`edges` has no foreign key.** That covers `cite` and `wikilink` edges from or to the chat.
  - `chunks_fts` (external-content FTS5) and `chunks_vec` (`vec0`) are virtual tables that do not cascade.

  These are exactly the rows the §29 deletion contract must decide explicitly. **This study does not decide them;** it flags the difference from PocketPal's two-table cascade.

**Drafts (W14):** keep drafts per chat ID in the ViewModel with `SavedStateHandle` backing, so they survive chat switches, fold/unfold and process death. `app/src/main/AndroidManifest.xml` declares no `configChanges`. The platform therefore recreates the activity on every fold/unfold (a screen-size change), so draft and expansion state held only in plain `remember` would be lost.

### A4: Model presentation in the header and picker

**Header:** the W9 layout, with the subtitle = friendly primary label, then ` · `, then `Local`. For example: `Qwen 2.5 3B · Local`.

**Where the label comes from:** the resolution chain is in JAN.md §6.1. The one PocketPal-specific addition is its curated `display_name` path (`"Gemma 3 1B"`, `"Qwen3 0.6B"`). Skein mirrors it for its two bundled defaults by shipping `name` in their manifests, so the first-run experience never depends on GGUF metadata.

**Status:** the header shows status only when it is not "ready":

| State | Subtitle |
| --- | --- |
| Ready | `Qwen 2.5 3B · Local` |
| Loading | `Qwen 2.5 3B · Starting…` |
| Not loaded | `Qwen 2.5 3B · Loads when you send` |
| No model | `No model · Add one` (tappable) |

Today's `qwen · ●` and `⏸` glyphs ([`CommandBar.kt:119-137`](../../../feature/shell/src/main/kotlin/app/skein/feature/shell/nav/CommandBar.kt)) are replaced.

**Picker (W16):** tapping the subtitle opens a bottom sheet.
- **Contents:** *On this device* models, each showing primary label, a small secondary (`Q4 · 1.9 GB`) and a status dot; then a *Persona* row; then *Manage models ›*.
- **Excluded:** no generation sliders. PocketPal puts *Generation settings* one tap from every chat, a sheet of N-predict, temperature, top-k and top-p sliders (README composite, panel 5: [`assets/images and logos/Chat.png`](https://github.com/a-ghorbani/pocketpal-ai/blob/ebe1425a569c56496eda15ae3a471c84773fdb05/assets/images%20and%20logos/Chat.png)). In Skein that is the Advanced tier, reached from Model Details, per the recon's Automatic/Advanced split (PP-43, recon §7.3).

### A5: Message composition and generation controls

**Composer** (W15), at Compact width:
- **Top row:** a multi-line field, placeholder *Ask Skein…*, max 6 lines before it scrolls internally. PocketPal caps at `maxHeight: 150`.
- **Bottom row:**
  - `＋` opens a sheet: *Attach file · Attach note · Link [[note]]*. This is where Skein's `[[` autocomplete and knowledge attach live.
  - A *Think* pill appears **only** when the loaded model's template supports thinking (W8).
  - The send/stop slot sits on the right.
- **Glyphs:** Material icons with 48 dp targets. No glyph text.

**Stop:** replaces Send in the same slot while the turn is `Queued`, `Thinking` or `Streaming`. On tap it switches immediately to *Stopping…* (W5). An interrupted turn keeps its text, per PP-64 and the existing `interrupted — stopped` badge ([`AssistantBubble.kt:199`](../../../feature/chat/src/main/kotlin/app/skein/feature/chat/AssistantBubble.kt)). That badge's copy becomes the activity summary `▸ Stopped after 42s`.

**Regenerate and edit:** use the long-press menu filtered by author (W17). The semantics are PP-68 to PP-71, plus the in-flight guard the recon adds (§4.10.2 there). Regenerating **with another model** is not offered in this menu (N13).

**Edit mode:** the banner above the field reads *Editing message ✕* (W15). Cancelling restores the previous draft.

### A6: Empty states and responsive behaviour

**Empty-chat variants (W19, restated for Skein):**

| State | Title | Action |
| --- | --- | --- |
| No model installed | *Add a model to start* | **Choose a file…** (SAF import) and, only when Skein Hub is installed, **Get models** |
| Model installed, not loaded | none (show the §33 home: *What are you working on?*) | The composer is live. The model starts on first send, and the activity block shows *Starting model*. |
| Import in progress | *Getting Qwen 2.5 3B ready · 42 %* | none |

These replace `No models imported yet — use /import model` and `No tabs open — back to timeline`.

**Responsive behaviour:** PocketPal has essentially none beyond a 400 px drawer-width breakpoint frozen at launch (N11). Skein's adaptive behaviour comes from the Android adaptive samples study, not from here.

### A7: Conversation history and what the owner's screenshots add

- **`pocketpal-drawer.png` confirms W10 and N5.**
  - Six destinations above a hairline divider.
  - Buckets `Today` (`Hi`, selected pill; `Yo`) and `This week` (`Help me plan and brainstorm th…`, truncated with the baked-in ellipsis of N9).
  - Behind the drawer, the per-message footer shows play and copy icons and `31ms/token`.
  - Takeaway: grouping and pills are good; one-word auto-titles (`Hi`, `Yo`) show that deterministic titles need Skein's first-sentence rule and do not replace rename.
- **`pocketpal-reasoning.png` confirms W2, W8, W9, W15, N1 and N2.**
  - Header: `Hi` over `Qwen3.5-2B-Q4_K_M.`.
  - A right-aligned user bubble.
  - The PARTIAL reasoning card: fading top, a live numbered plan, a blue tint and glow.
  - Composer: `＋`, `^` (pal/model sheet), the *Think* pill (enabled, inverted), TTS with a dropdown, and Stop (a square in a circle).
- **The owner's framing, and Skein's position on it:** *"I know pocketpal is super basic chat. And skein is much more complicated, workspace knowledge graph."*
  - This is why lane A exists. A knowledge-workspace turn does visible, nameable work (search, read notes, traverse graph) before any reasoning.
  - Showing that work truthfully is Skein's differentiator. Showing only a reasoning window would hide it.

---

**What specific problem in Skein can this project help us solve?** The one-screen conversation experience on the Fold's outer display. Specifically:
- the dead-air `thinking…` during minute-long on-device prefill, replaced by a truthful, collapsible activity block whose live reasoning window is PocketPal's and whose pipeline steps and `Worked for …` summary are Skein's own;
- anonymous, undeletable `Chat` sessions, replaced by date-grouped, titled, renamable, deletable history in the drawer;
- a title-over-model chat header that shows `Qwen 2.5 3B · Local` instead of a filename.
