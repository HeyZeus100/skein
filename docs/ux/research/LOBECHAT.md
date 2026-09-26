# LobeChat (LobeHub): reference study for Skein

**Bead:** `skein-xtov.6` (epic `skein-xtov`) · **Authority:** `docs/research/SKEIN_UI_UX_OVERHAUL_PROMPT.md` §§1–3, 15, 20, 27, 34–35, 52–53 · **Spec context:** `docs/superpowers/specs/2026-09-19-skein-design.md` §5, §8
**Companions:** [`ANYTHINGLLM.md`](ANYTHINGLLM.md), which holds the shared design in [Proposed Skein context presentation](ANYTHINGLLM.md#proposed-skein-context-presentation), and [`OPEN_WEBUI.md`](OPEN_WEBUI.md).
**Status:** Research record. Advisory. Brief §15 asks for this project to be used "primarily as a product/design reference". Its licence (below) rules out code reuse, so **no LobeChat code, copy, token values or assets are taken**. Skein references are to repository paths at `009cbb6`.

---

## Project

| Field | Value |
| --- | --- |
| Repo URL (as given) | <https://github.com/lobehub/lobe-chat>. It now **redirects to <https://github.com/lobehub/lobehub>**: the repository was renamed when the product became the "LobeHub" suite. The default branch is `canary`. |
| Commit inspected | `769c006afcc6cbe0dbf03f5f6e82950f1469dd44` (`canary` HEAD, committed 2026-09-26T14:44:53+08:00, *"🔧 chore: stop prettier from rewriting shell blocks in Markdown (#20016)"*) |
| Latest release tag | `v2.2.18` → `14dfc07b14eee1984e52c195df9319636d6b167b`, published 2026-09-20. `package.json` at HEAD: `@lobehub/lobehub` `2.2.17`. |
| Inspection date | 2026-09-26 |
| Local clone | `research/clones/lobe-chat/` (`git clone --depth 1`), git-ignored by `.gitignore:121`. It is never committed. |
| Stack | React + `antd-style` + `@lobehub/ui` (a separate MIT repository), Zustand stores, Next.js plus a Vite SPA with **separate desktop and mobile SPA entries** (`index.html` / `index.mobile.html`), and an Electron desktop app. `DESIGN.md` / `DESIGN.dark.md` describe the design system as YAML tokens followed by prose rules. |

Areas read (all permalinks are pinned to the inspected SHA):

- **Design rules:** [`DESIGN.md`](https://github.com/lobehub/lobehub/blob/769c006afcc6cbe0dbf03f5f6e82950f1469dd44/DESIGN.md)
- **Composer:** [`src/features/ChatInput/ActionBar/`](https://github.com/lobehub/lobehub/blob/769c006afcc6cbe0dbf03f5f6e82950f1469dd44/src/features/ChatInput/ActionBar/index.tsx) (Plus, Token, Knowledge, Model, Params, Upload), [`src/features/ChatInput/Mobile/index.tsx`](https://github.com/lobehub/lobehub/blob/769c006afcc6cbe0dbf03f5f6e82950f1469dd44/src/features/ChatInput/Mobile/index.tsx), [`src/features/ChatInput/Desktop/ContextContainer/`](https://github.com/lobehub/lobehub/blob/769c006afcc6cbe0dbf03f5f6e82950f1469dd44/src/features/ChatInput/Desktop/ContextContainer/ContextList.tsx), [`MainChatInput`](https://github.com/lobehub/lobehub/blob/769c006afcc6cbe0dbf03f5f6e82950f1469dd44/src/routes/%28main%29/agent/features/Conversation/MainChatInput/index.tsx)
- **Models:** [`src/features/ModelSwitchPanel/`](https://github.com/lobehub/lobehub/blob/769c006afcc6cbe0dbf03f5f6e82950f1469dd44/src/features/ModelSwitchPanel/types.ts)
- **Side panels:** [`src/features/Portal/`](https://github.com/lobehub/lobehub/blob/769c006afcc6cbe0dbf03f5f6e82950f1469dd44/src/features/Portal/router.tsx), [`src/features/Conversation/WorkingSidebar/`](https://github.com/lobehub/lobehub/blob/769c006afcc6cbe0dbf03f5f6e82950f1469dd44/src/features/Conversation/WorkingSidebar/fitsBesidePortal.ts)
- **Mobile chat route:** [`src/routes/(mobile)/chat/`](https://github.com/lobehub/lobehub/blob/769c006afcc6cbe0dbf03f5f6e82950f1469dd44/src/routes/%28mobile%29/chat/index.tsx)
- **Mobile/desktop routing:** [`src/libs/next/proxy/define-config.ts`](https://github.com/lobehub/lobehub/blob/769c006afcc6cbe0dbf03f5f6e82950f1469dd44/src/libs/next/proxy/define-config.ts#L86-L101)
- **Strings:** `locales/en-US/{chat,setting,portal}.json`

## License

**No SPDX identifier.** GitHub's licence API reports **`NOASSERTION`**. The governing file is [`LICENSE`](https://github.com/lobehub/lobehub/blob/769c006afcc6cbe0dbf03f5f6e82950f1469dd44/LICENSE), the **"LobeHub Community License"**. Quoted in full:

> LobeHub Community License
>
> Copyright (c) 2024/06/17 - current LobeHub LLC. All rights reserved.
>
> ----------
>
> From 1.0, LobeChat is licensed under the LobeHub Community License, based on Apache License 2.0 with the following additional conditions:
>
> 1. The commercial usage of LobeChat:
>
>   a. LobeChat may be utilized commercially, including as a frontend and backend service without modifying the source code.
>
>   b. a commercial license must be obtained from the producer if you want to develop and distribute a derivative work based on LobeChat.
>
> Please contact hello@lobehub.com by email to inquire about licensing matters.
>
> 2. As a contributor, you should agree that:
>
>   a. The producer can adjust the open-source agreement to be more strict or relaxed as deemed necessary.
>
>   b. Your contributed code may be used for commercial purposes, including but not limited to its cloud edition.
>
> Apart from the specific conditions mentioned above, all other rights and restrictions follow the Apache License 2.0. Detailed information about the Apache License 2.0 can be found at http://www.apache.org/licenses/LICENSE-2.0.

**Conflicting metadata to be aware of.** The root [`package.json` L25](https://github.com/lobehub/lobehub/blob/769c006afcc6cbe0dbf03f5f6e82950f1469dd44/package.json#L25) declares `"license": "MIT"`. The README badge says "apache 2.0" ([README L541](https://github.com/lobehub/lobehub/blob/769c006afcc6cbe0dbf03f5f6e82950f1469dd44/README.md#L541)), while the README text says *"This project is [LobeHub Community License](./LICENSE) licensed"* (L487). Only `packages/sdk/package.json` carries its own licence field (MIT); the other ~100 workspace packages have none. **The `LICENSE` file governs.** Package-manager metadata must not be read as an MIT grant.

**What this means for Skein:**

- **Clause 1(b) applies.** Any code, component, token table or text *derived from* LobeChat and **distributed** in Skein (an APK distributed publicly, with F-Droid as the target) would be a derivative work and would need a commercial licence from LobeHub LLC. That is incompatible with Skein's Apache-2.0 licence and with the `foss` allowlist (Apache-2.0, MIT, BSD-\*, ISC, CC0, OFL-1.1, Unlicense; `docs/Handoffs/skein-v1-autonomous-completion.md` §3 item 6).
- **Clause 2(a)** lets the owner change the terms further, which is another reason not to depend on anything here.
- **Branding.** There is no explicit trademark clause, but the Apache-2.0 base applies, and Apache-2.0 §6 grants no trademark rights. **Do not use the LobeHub/LobeChat names, logos or visual identity.**
- **Ideas are fine.** The design principles below are re-stated in Skein's own words; ideas are not covered by copyright. **Do not copy `DESIGN.md` prose, token values, locale strings or components.**
- **`@lobehub/ui`** (the component library, separate repo [`lobehub/lobe-ui`](https://github.com/lobehub/lobe-ui)) is **MIT** per GitHub. It is React, so it has no Compose value, and nothing here depends on it.

## Maintenance

- **Very active.** Last push 2026-09-26. At least 100 commits in the 30 days before inspection. Release `v2.2.18` on 2026-09-20. About 82.8k stars and 948 open issues. Not archived. Created 2023-05-21.
- **Scope is growing well beyond chat:** agents, groups, goals, tasks, pages, workspaces, billing, credits, a marketplace. The `SettingsTabs` enum has about 40 entries, including several `@deprecated` aliases ([`store/global/initialState.ts` L60-100](https://github.com/lobehub/lobehub/blob/769c006afcc6cbe0dbf03f5f6e82950f1469dd44/src/store/global/initialState.ts#L60-L100)). The chat interaction layer is still where the craft is, and the composer was recently consolidated (L2). **Study the chat surface; ignore the suite.**

## Relevant Skein Problem

Brief §15 asks for LobeChat as the reference for these Skein problems:

- **Visual hierarchy and polish of a dense AI chat on a narrow screen.** Skein's chat header is a literal `"chat"` title and a `⚹ context` text button (`feature/chat/.../ChatScreen.kt:79-82`). The composer is `$ prompt · [[ · slash · 📎 · ⏎` (spec §8.4). The brief's bar is "the interaction clarity of a polished chat application" (§1).
- **Model/provider UX** where *local stays the default identity* (brief §34) and there is room for optional external providers later.
- **Agent configuration.** Skein's **personas** (system prompt + default model, spec §5) are LobeChat's "agents".
- **Tool presentation.** Skein's future skills and vault tools (`docs/design/VAULT_TOOL_PRIMITIVES.md`) need this.
- **Multimodal composer:** images go to Gemma 4 vision when that model is active (spec §3.1).
- **Side panels, advanced settings and progressive disclosure.** These feed the context inspector ([P4](ANYTHINGLLM.md#p4--the-context-inspector)) and brief Wave 9.

## Patterns Worth Adopting

**L1 · The context meter stays hidden until it matters.** The context-window tag returns `null` for regular users unless usage is **> 50%**. The code comment: *"Keep the composer quiet for regular users until context pressure is real"* ([`Token/TokenTag.tsx` L34-36](https://github.com/lobehub/lobehub/blob/769c006afcc6cbe0dbf03f5f6e82950f1469dd44/src/features/ChatInput/ActionBar/Token/TokenTag.tsx#L34-L36)).
- The popover's breakdown (role settings / skill settings / history summary / chat messages; used vs remaining) is shown **only in dev mode** ([`TokenDetails.tsx` L50-98](https://github.com/lobehub/lobehub/blob/769c006afcc6cbe0dbf03f5f6e82950f1469dd44/src/features/ChatInput/ActionBar/Token/TokenDetails.tsx#L50-L98)).
- Counting runs **off the typing path**: a 300 ms debounce and `startTransition` ([`useTokenBreakdown.ts` L46-72](https://github.com/lobehub/lobehub/blob/769c006afcc6cbe0dbf03f5f6e82950f1469dd44/src/features/ChatInput/ActionBar/Token/useTokenBreakdown.ts#L46-L72)).
- Once shown, the meter is never pushed into overflow (`alwaysDisplay: actionKey === 'contextWindow'`, [`ActionBar/index.tsx` L18-26](https://github.com/lobehub/lobehub/blob/769c006afcc6cbe0dbf03f5f6e82950f1469dd44/src/features/ChatInput/ActionBar/index.tsx#L18-L26)).

→ *Skein:* the chip turns into a usage ring only past a threshold (≥ 75 %) or when `droppedHistoryTurns > 0`. The inspector's Context bar is always visible there, with a **Knowledge** segment that LobeChat's breakdown lacks. RAG is added at send time and doesn't appear in their count ([L183-189](https://github.com/lobehub/lobehub/blob/769c006afcc6cbe0dbf03f5f6e82950f1469dd44/src/features/ChatInput/ActionBar/Token/useTokenBreakdown.ts#L183-L189)). In Compose, count via `snapshotFlow { draft }.debounce(300)` on a background dispatcher.

**L2 · A consolidated composer: one "+" menu that shows state inside it.** The main agent composer's left side is just `['plus', 'voiceDictation']`. The right side is `['model', 'voiceMessage', 'contextWindow']`, and the comment notes *"The model chip lives on the right, next to Send"* ([`MainChatInput/index.tsx` L18-49](https://github.com/lobehub/lobehub/blob/769c006afcc6cbe0dbf03f5f6e82950f1469dd44/src/routes/%28main%29/agent/features/Conversation/MainChatInput/index.tsx#L18-L49)). Inside "+":
- toggles render a ✓ when active, and tools and attachments render **count chips** ([`Plus/index.tsx` L397-432](https://github.com/lobehub/lobehub/blob/769c006afcc6cbe0dbf03f5f6e82950f1469dd44/src/features/ChatInput/ActionBar/Plus/index.tsx#L397-L432));
- *"'Add Attachments…' merges file upload with the knowledge base"* into one entry (L651-680);
- **Params opens a section of the right-hand working panel**, not a popover (L390-395, L640-648).

The generic action bar still auto-collapses by width (`collapseOffset={mobile ? 48 : 80}`, [`ActionBar/index.tsx` L88-104](https://github.com/lobehub/lobehub/blob/769c006afcc6cbe0dbf03f5f6e82950f1469dd44/src/features/ChatInput/ActionBar/index.tsx#L88-L104)). But the product has moved to **fewer, stateful entry points**.

→ *Skein:* the composer is `＋ · text · ↑`. `＋` opens the Attach sheet, which merges *Import file…* with notes, files and chats, as in [P3](ANYTHINGLLM.md#p3--attach-knowledge-flow). Parameters live in the inspector's Session section, not in the composer.

**L3 · The model chip shows the effective model and its scope.** *"Topic-scoped model: a topic pins its own model… Display the topic's pinned model when present, else the agent default; a switch pins to the active topic"* ([`Model/index.tsx` L34-72](https://github.com/lobehub/lobehub/blob/769c006afcc6cbe0dbf03f5f6e82950f1469dd44/src/features/ChatInput/ActionBar/Model/index.tsx#L34-L72)).
- The chip carries **both current values** (model + reasoning effort), and the secondary part is never ellipsized away (L78-86).
- When selection is locked: *"say which model is pinned AND why it can't be changed here"* (L117-119).

→ *Skein:* the header subtitle `Qwen 2.5 3B · Local` shows the **effective** model: the chat's override, else the persona default. Session shows "This chat" vs "Persona default". Lock reasons are concrete, for example "Model is loading", or "Switching takes a few seconds" during the 2–5 s warm-swap (spec §6).

**L4 · Models first, providers second.** `GroupMode = 'byModel' | 'byProvider'` ([`ModelSwitchPanel/types.ts` L5](https://github.com/lobehub/lobehub/blob/769c006afcc6cbe0dbf03f5f6e82950f1469dd44/src/features/ModelSwitchPanel/types.ts#L5)). In *byModel*, one model available from several providers is **one row** with alternate providers (`model-item-multiple`, L22-45).

→ *Skein:* model identity is primary ("Gemma 4 E4B"). *Where it runs* is an attribute ("Local"). If external providers ever exist (which needs its own architecture decision; Skein Core has no `INTERNET`, spec §2), they appear as an alternate source of a model, not as a parallel top-level tree.

**L5 · List rows show only what tells items apart.** *"Right side of a model row: only the facts that tell models apart… Abilities every model shares… live in the hover detail panel instead. Values carry no labels to keep rows quiet"* ([`ModelRowMeta.tsx` L56-62](https://github.com/lobehub/lobehub/blob/769c006afcc6cbe0dbf03f5f6e82950f1469dd44/src/features/ModelSwitchPanel/components/List/ModelRowMeta.tsx#L56-L62)).

→ *Skein:* model rows show name, `3B · 2.0 GB` and `Loaded`. Capabilities (vision), context length, quant, GGUF filename, SHA-256 and backend go in a details sheet opened by an ⓘ button, because there is no hover (brief §34).

**L6 · Parameters named in product language, with the technical key as a secondary tag.** `temperature` → **Creativity**, `top_p` → **Openness**, `presence_penalty` → **Topic Divergence**, `frequency_penalty` → **Vocabulary Richness** ([`Params/Controls.tsx` L385-419](https://github.com/lobehub/lobehub/blob/769c006afcc6cbe0dbf03f5f6e82950f1469dd44/src/features/ChatInput/ActionBar/Params/Controls.tsx#L385-L419); strings in [`locales/en-US/setting.json` L1126-1137](https://github.com/lobehub/lobehub/blob/769c006afcc6cbe0dbf03f5f6e82950f1469dd44/locales/en-US/setting.json#L1126-L1137)).
- Each row shows the product label with the key (`temperature`) as a small tag.
- Each parameter is **off until switched on**, and its slider appears only then. The whole *Advanced Settings* section is collapsed (L1015-1056).
- The panel title changes with scope: *"Agent Advanced Settings"* vs *"Chat Parameter Settings"*.

→ *Skein:* Session → Advanced shows **Creativity** (`temperature`) and **Response length** (`max_tokens`), each "Default · from Researcher" until changed. The title states the scope ("This chat" / "Persona"). This combines with Open WebUI's inherit semantics ([`OPEN_WEBUI.md` O1](OPEN_WEBUI.md#patterns-worth-adopting)).

**L7 · Per-agent knowledge you can toggle, with a count and a way in when empty.** The agent's files and libraries appear with checkboxes and an `enabledCount`. The footer (*"Choose Files / Libraries"* / *"View More"*) *"stays rendered when nothing is attached yet — otherwise the submenu shows 'no related files or libraries' with no way to attach the first one"* ([`Knowledge/useControls.tsx` L85-147](https://github.com/lobehub/lobehub/blob/769c006afcc6cbe0dbf03f5f6e82950f1469dd44/src/features/ChatInput/ActionBar/Knowledge/useControls.tsx#L85-L147)).

→ *Skein:* persona default knowledge (later) appears as *From persona* rows that can be switched off for this chat. The Attach sheet always has a path forward in its empty state (*Import file…*, *Browse notes*).

**L8 · A written rule for disabled vs hidden.** A permission-gated upload renders disabled with a reason *"per disabled-not-hidden UX rule"* ([`Upload/index.tsx` L62-81](https://github.com/lobehub/lobehub/blob/769c006afcc6cbe0dbf03f5f6e82950f1469dd44/src/features/ChatInput/ActionBar/Upload/index.tsx#L62-L81)). Capability gaps (no vision model) disable the image item with a tooltip (L83-108).

→ *Skein* adapts this to brief §2 ("no visible control may be nonfunctional"): **hide** what this build or device can never do. **Disable with a written reason** only for *transient* states: model loading, vault locked, indexing, generating.

**L9 · A side panel with its own view stack, the same on mobile.** The Portal renders a view stack (`VIEW_COMPONENTS` over ~20 view types, [`Portal/router.tsx` L35-62](https://github.com/lobehub/lobehub/blob/769c006afcc6cbe0dbf03f5f6e82950f1469dd44/src/features/Portal/router.tsx#L35-L62)).
- On mobile, **the same `PortalContent`** sits in a 95 %-height modal whose close clears the stack ([`Portal/Mobile.tsx` L20-60](https://github.com/lobehub/lobehub/blob/769c006afcc6cbe0dbf03f5f6e82950f1469dd44/src/features/Portal/Mobile.tsx#L20-L60)).
- On desktop each view remembers its width and has a minimum ([`portalWidth.ts` L11-60](https://github.com/lobehub/lobehub/blob/769c006afcc6cbe0dbf03f5f6e82950f1469dd44/src/features/Portal/portalWidth.ts#L11-L60)).

→ *Skein:* the inspector keeps an internal back stack (Context → document → passages) in both the sheet and the pane (P5). Opening a full note leaves the inspector for the note route rather than stacking.

**L10 · The conversation keeps its width; side panels give way first.** `sidebarWidthBudget = availableWidth − portalWidth − CONVERSATION_KEEP_WIDTH`. The comment: *"A wide portal plus the sidebar can consume the whole row and leave the conversation at zero width, so the sidebar yields first"* ([`WorkingSidebar/fitsBesidePortal.ts` L10-27](https://github.com/lobehub/lobehub/blob/769c006afcc6cbe0dbf03f5f6e82950f1469dd44/src/features/Conversation/WorkingSidebar/fitsBesidePortal.ts#L10-L27)). The constants are `CONVERSATION_KEEP_WIDTH = 420`, `CHAT_PORTAL_WIDTH = 400`, `CHAT_PORTAL_TOOL_UI_WIDTH = 600` ([`packages/const/src/layoutTokens.ts` L12-29](https://github.com/lobehub/lobehub/blob/769c006afcc6cbe0dbf03f5f6e82950f1469dd44/packages/const/src/layoutTokens.ts#L12-L29)).

→ *Skein:* this is the rule for the ≈800 dp inner Fold screen ([P5](ANYTHINGLLM.md#p5--containers-and-fold-behaviour)). Show the inspector pane only if the conversation keeps ≈ 420 dp. Otherwise use the sheet. The conversation list pane gives way (to the drawer) before the conversation does. Brief §21: "Never squeeze three columns onto the outer Fold screen", and at ≈800 dp three columns don't fit either.

**L11 · A two-line mobile header that doubles as a switcher.** The title is the agent name with its topic count; the subtitle is the current topic title with a ▾. Tapping either toggles the topic (conversation) list ([`(mobile)/chat/features/ChatHeader/ChatHeaderTitle.tsx` L32-72](https://github.com/lobehub/lobehub/blob/769c006afcc6cbe0dbf03f5f6e82950f1469dd44/src/routes/%28mobile%29/chat/features/ChatHeader/ChatHeaderTitle.tsx#L32-L72)).

→ *Skein* outer screen: brief §22's `Architecture chat` over `Qwen 2.5 3B · Local`. **Tap the subtitle for the model/session sheet**; the ☰ opens conversations. A tap target that does two things is not copied.

**L12 · Selected text becomes a context chip.** The composer's `ContextList` renders *selections* from documents or pages as chips alongside uploaded files ([`Desktop/ContextContainer/ContextList.tsx` L19-57](https://github.com/lobehub/lobehub/blob/769c006afcc6cbe0dbf03f5f6e82950f1469dd44/src/features/ChatInput/Desktop/ContextContainer/ContextList.tsx#L19-L57)).

→ *Skein:* the editor's selection menu → **Ask** (spec §3.1, §8.5) opens the chat with a quoted-selection chip scoped to *this message*. It is the same chip family and the same inspector entry as attachments.

**L13 · Content and voice rules written down** ([`DESIGN.md` L168-189](https://github.com/lobehub/lobehub/blob/769c006afcc6cbe0dbf03f5f6e82950f1469dd44/DESIGN.md#L168-L189)). The ideas, restated for Skein's `DESIGN_SYSTEM.md` in our own words:
- one canonical term per concept, with no synonym drift;
- **"layered, not split"**: one simple main line plus an optional precise second line, and no "simple vs pro" variants of a screen;
- in-progress states use "-ing…";
- sensitive messages go: acknowledge → restore control (including "view Context") → next action;
- never signal state with colour alone.

→ *Skein canonical terms:* **Chat, Note, File, Knowledge, Persona, Model, Context, Source, Passage.**

**L14 · Tool approval modes in plain words.** *Manual*, *Allow List* and *Auto Approve*, with *Approve* / *Approve all N* ([`locales/en-US/chat.json` L2041-2050](https://github.com/lobehub/lobehub/blob/769c006afcc6cbe0dbf03f5f6e82950f1469dd44/locales/en-US/chat.json#L2041-L2050)).

→ *Skein:* future vault-writing skills (`write_note`, `patch_note`) default to asking each time. Approval state appears in the inspector's Activity section and inline in the transcript.

**L15 · Retrieved passages shown per message as a collapsed block.** A *"Reference Source"* row expands into chunk chips ([`Messages/components/FileChunks/index.tsx` L50-80](https://github.com/lobehub/lobehub/blob/769c006afcc6cbe0dbf03f5f6e82950f1469dd44/src/features/Conversation/Messages/components/FileChunks/index.tsx#L50-L80)). This confirms AnythingLLM's A5.

→ *Skein:* the `3 sources` chip under each answer ([P2](ANYTHINGLLM.md#p2--the-active-context-indicator-chip)).

## Patterns Not Worth Adopting

- **Separate mobile and desktop apps chosen by user agent at load time.** The proxy parses the UA (`isMobile: device.type === 'mobile'`, [`define-config.ts` L86-101](https://github.com/lobehub/lobehub/blob/769c006afcc6cbe0dbf03f5f6e82950f1469dd44/src/libs/next/proxy/define-config.ts#L86-L101)). Vite builds `dist/mobile` and `dist/desktop` from `index.mobile.html` and `index.html` ([`vite.config.ts` L113-150](https://github.com/lobehub/lobehub/blob/769c006afcc6cbe0dbf03f5f6e82950f1469dd44/vite.config.ts#L113-L150)). A foldable that changes size mid-session **cannot** switch between them. Skein must be one adaptive tree that reflows on `WindowSizeClass` changes (brief §§22–24). LobeChat's repeated split of `.mobile` and `.desktop` components is the opposite of "one content, two containers".
- **Settings sprawl.** About 40 settings tabs, including deprecated aliases, billing, credits, referral and plans ([`initialState.ts` L60-100](https://github.com/lobehub/lobehub/blob/769c006afcc6cbe0dbf03f5f6e82950f1469dd44/src/store/global/initialState.ts#L60-L100)). Skein keeps settings to defaults only; everything per-chat goes in the inspector.
- **Anything that depends on hover:** hover model detail panels, hover submenus, drag-resizable popovers and portals (`ENABLE_RESIZING`, [`ModelSwitchPanel/const.ts`](https://github.com/lobehub/lobehub/blob/769c006afcc6cbe0dbf03f5f6e82950f1469dd44/src/features/ModelSwitchPanel/const.ts)).
- **Cloud economics in the model picker:** rating radars, benchmark modals, pricing columns (`ModelRowMeta`, `BenchmarkModal`). Local models have no per-token price.
- **Marketplace, community agents, plugin store, MCP market, Composio/OAuth connectors, "Smart Search" / "Provider Search" web search.** All of these need a network connection, and Skein Core has none.
- **Knowledge that belongs only to the agent.** LobeChat's files and libraries hang off the *agent* (L7), and there is no per-conversation scope. Skein needs **per-chat** first (AnythingLLM's thread scope) and persona defaults second.
- **The mobile chat page mounts a `TelemetryNotification`** ([`(mobile)/chat/index.tsx` L8-19](https://github.com/lobehub/lobehub/blob/769c006afcc6cbe0dbf03f5f6e82950f1469dd44/src/routes/%28mobile%29/chat/index.tsx#L8-L19)). Skein bans telemetry outright (spec §2 principle 3).
- **Visual language:** gradients (the mobile portal's `linear-gradient` background, `Portal/Mobile.tsx` L14-18), emoji avatars and a playful brand. These conflict with Skein's terminal-inspired, no-gradient style (spec §8.1), and brief §2.14 says to preserve Skein's own identity.
- **The full action catalogue** (`agentMode`, `clear`, `history`, `memory`, `mention`, `params`, `promptTransform`, `search`, `tools`, `typo`, …; [`ActionBar/config.ts` L18-36](https://github.com/lobehub/lobehub/blob/769c006afcc6cbe0dbf03f5f6e82950f1469dd44/src/features/ChatInput/ActionBar/config.ts#L18-L36)) as composer buttons. Even LobeChat has pulled back from this (L2).

## Potential Reusable Code

**None. The licence forbids it** (clause 1(b): distributing a derivative work needs a commercial licence). `@lobehub/ui` is MIT but React-only.

Two small ideas are re-derived in Skein's own code, taking the ideas and not the code:
1. **Width budget:** `paneAllowed = windowWidth − paneWidth ≥ conversationMin` (L10).
2. **Debounced token counting off the input path** (L1).

Do not copy `DESIGN.md` token values, locale strings (for example the parameter labels in L6) or component structure. Skein writes its own labels. The parameter-naming *idea* is fine; the English words used should be chosen and reviewed separately for Skein.

## Android/Fold Relevance

Size classes are estimates: outer screen ≈ 411 dp (Compact); inner screen ≈ 790–820 dp (Medium). Confirm on device.

| LobeChat pattern | Outer screen (bottom / context sheet) | Inner screen (supporting pane) | Desktop-only (do not copy) |
| --- | --- | --- | --- |
| Quiet context meter (L1) | Ring on the context chip past the threshold; bar in the inspector sheet | Same, and the bar is visible in the pane | Hover popover breakdown |
| Consolidated `＋` with state inside (L2) | `＋` → Attach sheet; counts shown on rows | Same sheet, centred | Nested hover submenus |
| Effective-model chip with scope (L3) | Header subtitle → model/session sheet | Same, or Session section in the pane | Model + effort chip in a desktop action bar |
| Models first, providers second (L4, L5) | Model sheet rows: name · size · Loaded; ⓘ → details sheet | List–detail in the sheet or pane | Hover detail panel, rating/price columns |
| Product-named parameters (L6) | Session → Advanced (collapsed) in the sheet | Same in the pane | 384 px popover |
| Portal view stack; the same content on mobile (L9) | Inspector sheet with in-sheet back stack | Pane with the same stack | Per-view remembered widths, drag-resize |
| Conversation keeps its width (L10) | n/a (one pane) | **Core rule:** the pane appears only if the conversation keeps ≈ 420 dp; the list pane gives way first | — |
| Two-line header (L11) | Title and subtitle as separate tap targets | Same; the list pane replaces the ▾ switcher | — |
| Selection → context chip (L12) | Editor *Ask* → chat with a quote chip | Same, or a side-by-side note and chat | Browser-element picking |
| Separate mobile/desktop SPAs by UA | **Reject** | **Reject** | Rejected everywhere |

**Fold transition note.** Because LobeChat decides mobile vs desktop from the UA at load, it gives no guidance for brief §24. Skein takes its *panel-yielding* rule (L10) and applies it **continuously**: on every `WindowSizeClass` change, recompute `paneAllowed` and move the inspector between sheet and pane while keeping its back stack and focus. That covers Tests A, B and G.

## Mac UX-Lab Relevance

- **A live reference only for the desktop build.** `pnpm dev` ([README L378](https://github.com/lobehub/lobehub/blob/769c006afcc6cbe0dbf03f5f6e82950f1469dd44/README.md#L378)), the Docker image, or the Electron desktop app. The **mobile variant appears only with a mobile user agent** (devtools device emulation), not by resizing the window. It is a good test of how *not* to handle a fold. Keep screenshots out of the repository.
- **The format of `DESIGN.md` as an idea for Layers A and C.** One document holds machine-readable tokens (YAML front matter) plus prose rules. Skein's `DESIGN_SYSTEM.md` (brief §36) could use the same *shape* with Skein's own values: tokens in a header, generated into both the Compose theme (Layer A previews, Roborazzi) and, if Layer C is built, the `ux-lab/` CSS variables, so the two can't drift. Only the shape is borrowed; no LobeChat values or text.
- **Layer A fixtures** this study adds:
  - context meter hidden (< threshold) vs ring shown vs trimmed-history warning;
  - Session → Advanced collapsed vs one Custom parameter;
  - model sheet with a Loaded row and a details sheet;
  - an Ask-from-selection quote chip.

## Recommended Action

**STUDY ONLY.** The licence (clause 1(b)) rules out any code or asset reuse. Use it as a design reference for L1 (quiet meter), L2 (consolidated `＋`), L3–L5 (model presentation), L6 (product-named parameters), L10 (conversation keeps its width) and L13 (content rules). The patterns flow into the shared context design ([`ANYTHINGLLM.md`](ANYTHINGLLM.md#proposed-skein-context-presentation)) and into brief Waves 2 (design system), 4 (chat), 7 (context inspector) and 9 (models and settings).

## Expected Benefit

- A tested answer to "how many buttons does the composer need?": **three** (`＋`, text, send). State lives inside `＋` and in the context chip.
- Model presentation that keeps local as the identity and treats provider as an attribute (brief §34), ready for later providers without redesign.
- A concrete Fold pane rule (conversation keeps ≈ 420 dp) that makes Test B's "optional supporting panes appear" decidable.
- Written content rules that make "product language over implementation language" (brief §2.5) checkable in review.

## Expected Cost

- **Study: done.** No dependency, no code, no licence obligations. The licence is recorded here so nobody later reaches for `@lobehub/*` code by mistake.
- **Implementation** falls in existing Skein waves:
  - chip ring threshold and debounced counting (small);
  - `＋` Attach sheet (covered in the AnythingLLM prototype);
  - model sheet and details sheet (medium; Wave 9);
  - product-named parameters with inherit (small; needs copy review);
  - width-budget pane rule (small; Wave 3 shell);
  - content rules in `DESIGN_SYSTEM.md` (small; Wave 2).

## Answers to the prompt's specific questions

**Polished AI interaction design.** The polish comes from restraint, not ornament: a monochrome primary by default (colour only when the user picks one), text ranked by an opacity ramp, surfaces separated by tone and borders before shadows, motion of 100–200 ms that respects reduced motion, and one primary action per view ([`DESIGN.md` L93-166](https://github.com/lobehub/lobehub/blob/769c006afcc6cbe0dbf03f5f6e82950f1469dd44/DESIGN.md#L93-L166)). *For Skein:* these match its own terminal-restrained style (spec §8.1). Adopt the *discipline* (one primary, colour for state only, never colour alone) with Skein's own tokens.

**Model/provider UX.** The effective-model chip is scoped to the topic, then the agent (L3). There is a *byModel* grouping with providers as alternates (L4). Rows show only differentiating facts (L5). Lock reasons are explicit. *For Skein:* `Qwen 2.5 3B · Local` in the header, model-first lists, filename and hash in details, and no provider picker until a provider exists.

**Agent configuration.** Agents carry a system role, model, parameters, knowledge, tools and opening questions. Parameters use product names with an off-by-default state and a scope-aware title (L6). *For Skein:* **personas** carry instructions, default model, optional default knowledge (L7) and Creativity/Response length (L6). Per-chat overrides appear in the inspector's Session section.

**Tool presentation.** Tools and skills sit inside `＋` with count chips (L2) and plain approval modes (L14). *For Skein:* hidden until used, shown in Activity with approval state. Vault-writing tools ask each time.

**Multimodal composer.** Uploads merge with knowledge into one "Attachments" entry (L2). Image, video and audio are gated by model ability (L8). Selections become context chips (L12). Mobile has an expand-to-fullscreen composer ([`ChatInput/Mobile/index.tsx` L47-85](https://github.com/lobehub/lobehub/blob/769c006afcc6cbe0dbf03f5f6e82950f1469dd44/src/features/ChatInput/Mobile/index.tsx#L47-L85)). *For Skein:* the Attach sheet; *Photo* only with a vision model (hidden otherwise); quote chips from the editor; an expandable composer on the outer screen for long prompts.

**Side panels.** There are two: a view-stack Portal (L9) and a WorkingSidebar with Overview, Params and Resources sections. The sidebar gives way to keep the conversation readable (L10). *For Skein:* one inspector pane with sections and a back stack, following the width budget.

**Advanced settings.** Advanced is collapsed, each parameter is off until enabled, product label plus technical tag (L6). There are also ~40 global settings tabs (not adopted). *For Skein:* three levels (global / persona / chat) and inherit by default ([`OPEN_WEBUI.md` O1](OPEN_WEBUI.md#patterns-worth-adopting)), with no more than 2–4 parameters at Level 2.

**Visual hierarchy.** Text ranked by opacity, one primary, meta shown without labels, and the context meter hidden until needed (L1, L5, L13). *For Skein:* the chat shows title, model, messages, composer and one context chip. Nothing else is permanent (brief §26).

**Progressive disclosure.** Quiet by default (L1), state held inside `＋` (L2), details on demand (L5), advanced collapsed (L6), dev-mode-only breakdowns (L1). *For Skein:* the same layering, with a **Details** disclosure per inspector section replacing "dev mode".

**Licensing** (brief §15: "Review licensing carefully before any reuse"). The LobeHub Community License is Apache-2.0 plus clause 1(b): a commercial licence is needed to distribute derivative works. The root `package.json` says MIT, but that is **not** a grant; `LICENSE` governs. **Result: no reuse of code, tokens, strings or assets. Ideas only, restated in Skein's words.**

---

**What specific problem in Skein can this project help us solve?** LobeChat shows how to keep a capability-dense AI composer calm (one stateful `＋`, a context meter that appears only under pressure, an effective-model chip that states its scope, models first with providers as attributes, product-named parameters, side panels that give way before the conversation does). That lets Skein give its chat a three-control composer and a local-first model presentation on the 411 dp outer screen and the ≈800 dp inner screen. Skein uses the design ideas only, because the LobeHub Community License requires a commercial licence for distributed derivative works.
