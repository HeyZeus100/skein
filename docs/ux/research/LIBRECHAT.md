# LibreChat: reference study for Skein

**Bead:** `skein-xtov.5` (epic `skein-xtov`)
**Authority:** `docs/research/SKEIN_UI_UX_OVERHAUL_PROMPT.md` §§1–3, 16, 21, 35, 41–42, 52–53 (lifecycle context from §§28–29)
**Companions:**
- [`CONTINUE.md`](CONTINUE.md), which holds **Proposed Skein command palette & shortcuts**. LibreChat's shortcut registry feeds its §2, §6 and §7.
- [`ZED.md`](ZED.md).

**Status:** research record, advisory only. No code changes.

---

## Project

<https://github.com/danny-avila/LibreChat> is a self-hosted, multi-user, multi-provider chat web app. The repository has since moved to **<https://github.com/LibreChat-AI/LibreChat>**. The GitHub API resolves the old path there, and permalinks below use the canonical path.

- **Stack:** React, Vite, Recoil/Jotai, Tailwind and Ariakit on the client; Node/Express, MongoDB and Meilisearch on the server.
- **Why it's here:** the prompt uses it (§16, §53) as the reference for "retaining a familiar chat shell around complex functionality". That means multi-model, presets, parameters, tools and agents, attachments, and conversation management.

| Field | Value |
| --- | --- |
| Commit inspected | `7b2362d7a7c6148b84850924dc7fa5fc43307923`, the default-branch head (committed 2026-09-25 14:08 UTC: `🧲 feat: Attach or Leave a Workspace on a Saved Chat (#16124)`) |
| Nearest release | `v0.8.7` (2026-06-24); current release candidate `v0.8.8-rc4` (2026-09-23) |
| Inspection date | 2026-09-26 |
| Local clone | `research/clones/librechat/` (shallow, blobless; git-ignored via `.gitignore:121`; never committed) |
| Areas read | `client/src/components/{Conversations,Nav,UnifiedSidebar,SidePanel/Parameters,Chat/{Header.tsx,AddMultiConvo.tsx,Landing.tsx,Menus,Input,Messages}}`, `client/src/hooks/{useKeyboardShortcuts.ts,Input/useHandleKeyUp.ts,Chat/useMultiConvo.ts,Nav/useSideNavLinks.ts}`, `client/src/utils/{shortcuts.ts,convos.ts}`, `packages/data-provider/src/{parameterSettings.ts,generate.ts}`, `api/server/services/Endpoints/agents/title.js`, `librechat.example.yaml`, `client/src/locales/en/translation.json` |

Reproduce:

```bash
git clone --depth 1 --filter=blob:none https://github.com/danny-avila/LibreChat.git research/clones/librechat
git -C research/clones/librechat log -1 --format='%H %cI'   # 7b2362d7a7c6148b84850924dc7fa5fc43307923
```

**Citation format:** permalinks of the form `https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/<path>#L<n>`.

## License

**SPDX: `MIT`.** The file is [`LICENSE`](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/LICENSE) and reads "MIT License / Copyright (c) 2026 LibreChat". MIT is on Skein's permissive `foss` allowlist, so any snippet adapted into Skein would need that copyright and permission notice carried in `NOTICE`/`third_party`.

**No LibreChat code is copied in this bead.** The only plausible port candidate, the composer key decision table, is cheaper to reimplement (see Potential Reusable Code).

## Maintenance

**Very active.**
- **Commit cadence:** 181, 140, 102 and 107 commits in the last four weeks (GitHub `stats/commit_activity`).
- **Releases:** four `v0.8.8` release candidates between 2026-08-14 and 2026-09-23. The head commit is 2026-09-25.
- **Size:** about 45k stars and 794 open issues.
- **Organisation:** the repository moved to the `LibreChat-AI` organisation. Links to `danny-avila/LibreChat` still redirect.

**What churn means for Skein:** agent, subagent and "steer" UI is under heavy change (`Chat/Steering/`, `Chat/Subagents/`, `ActivityPhaseGroup.tsx`). Skein should take the settled parts, which are conversation lifecycle, the header/drawer shell, the model-spec picker, parameters, attachments and the shortcut registry, and ignore the moving agent surfaces.

## Relevant Skein Problem

1. **Conversation lifecycle and navigation** (prompt §28–29, §51 "Chats: create/rename/delete works; history is useful; repeated anonymous 'Chat' entries are eliminated").
   - Every Skein chat is created with `title = "Chat"` ([`BuiltinCommands.kt:33`](../../../feature/shell/src/main/kotlin/app/skein/feature/shell/nav/BuiltinCommands.kt)).
   - The drawer mixes Timeline / Notes / Graph / Personas / Settings ([`NavDrawer.kt:27-32`](../../../feature/shell/src/main/kotlin/app/skein/feature/shell/nav/NavDrawer.kt)) with no conversation list of its own.
   - No rename/delete/archive affordance is visible in the shell code read for this study; the lifecycle audit belongs to prompt §§28–31.
2. **Model and persona presentation.**
   - Personas are "system prompt + optional default model" (spec §3.1).
   - The model shows as `qwen · ●` text in the command bar ([`CommandBar.kt:125-127`](../../../feature/shell/src/main/kotlin/app/skein/feature/shell/nav/CommandBar.kt)).
   - `ChatScreen` gets `currentPersonaId = { null }` ([`MainActivity.kt:599`](../../../app/src/main/kotlin/app/skein/MainActivity.kt)).
   - Users need one understandable "who/what answers me" control (prompt §20 "Personas could be a session/chat parameter", §34).
3. **Advanced model settings.** `InferenceEngine.stream(prompt, params: SamplingParams)` (spec §6) implies user-tunable sampling. It needs a place that doesn't pollute Level 1.
4. **Attachments.** 📎 goes to SAF, then `ImportService`, then an inserted `[[attachment title]]` (`ChatBottomBar.kt` header comment). Images are analysed only when the active model has vision (spec §3.1). The user can't see what an attachment will *do* before sending.
5. **Tool and activity presentation.** Retrieval is shown after the fact as `score`/`recalled by` ([`ContextPanel.kt:78,89`](../../../feature/chat/src/main/kotlin/app/skein/feature/chat/ContextPanel.kt)). Prompt §27 wants understandable activity.
6. **Keeping a familiar chat shell** on a 400 dp outer screen while the inner screen carries workspace depth (prompt §22–23).

## Patterns Worth Adopting

Tags L1–L15 are referenced from the palette design in `CONTINUE.md`.

**L1. The conversation row menu is complete, conditional, and touch-visible.**

The row's "⋯" menu offers these items ([ConvoOptions.tsx#L303-L380](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/components/Conversations/ConvoOptions/ConvoOptions.tsx#L303-L380)):
- Share (only if shared links are enabled and permitted)
- Pin/Unpin
- Rename
- Duplicate
- Change project…
- Remove from project (only if the chat is in one)
- Archive/Unarchive
- Delete…

On small screens the trigger is **always visible**. The code comment reads "*Touch has no hover, so a reveal-on-hover trigger is simply invisible there*" ([#L403-L412](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/components/Conversations/ConvoOptions/ConvoOptions.tsx#L403-L412)).

**Skein translation.** Conversation and note rows get a visible trailing "⋯" on the outer screen, plus long-press opening the same menu:
- Rename…
- Pin
- Archive
- Delete…

Share/Export belongs to the chat's own overflow menu, not the list. Continue's hover-only row actions are the counter-example (see `CONTINUE.md`).

**L2. Delete is deliberate, names the object, and defines where you land.**
- **Confirmation:** "*Delete chat?*" / "*This will delete **{{title}}***" with Cancel and Delete ([DeleteButton.tsx#L94-L110](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/components/Conversations/ConvoOptions/DeleteButton.tsx#L94-L110); strings at [translation.json#L1362-L1363](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/locales/en/translation.json#L1362-L1363)).
- **Where you land:** deleting or archiving the *open* chat starts a new chat and replaces the route ([ConvoOptions.tsx#L118-L138](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/components/Conversations/ConvoOptions/ConvoOptions.tsx#L118-L138), [#L222-L275](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/components/Conversations/ConvoOptions/ConvoOptions.tsx#L222-L275)).
- **Late results:** the "open chat" is re-read when the mutation *resolves*, not when it was clicked. Archive is announced through a live region that outlives the removed row.
- **Keyboard delete** goes through the same dialog (`KeyboardDeleteDialog.tsx`, which reuses `DeleteButton`). It never deletes instantly.

**Skein translation:** this is prompt §29 almost verbatim. Delete confirmation names the chat and states what else is affected ("Notes that cite it keep their text; citations become unlinked", pending §50's lifecycle spec). After delete, the user lands on a new empty chat (outer screen) or the next item in the list (inner screen). A TalkBack announcement fires. The palette's `Delete chat…` confirm stage (CONTINUE.md §3) uses the same copy.

**L3. Archive is the reversible sibling of delete.** Archive/Unarchive is a first-class, reversible action. The list has an **Archived** view whose sort field set includes `archivedAt` ([chatFilters.ts#L16-L29](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/components/Conversations/chatFilters.ts#L16-L29)).

**Skein translation:** worth evaluating in the lifecycle spec (§50) as the non-destructive default for "get this out of my list". It reduces the number of irreversible deletes in a vault the user owns.

**L4. Date buckets are finer than "This week".** Buckets are Today · Yesterday · Previous 7 days · Previous 30 days · month names for the current year · year numbers ([convos.ts#L19-L57](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/utils/convos.ts#L19-L57)).

**Skein translation:** use these buckets for the chat list and the timeline's section headers.

**L5. Filters don't persist; sort does.**
- The list filter is status (Active/Archived) plus bookmark tags, OR-matched.
- It is **deliberately not persisted**: "*a filter that hides chats should not greet the user on the next visit*" ([chatFilters.ts#L52-L57](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/components/Conversations/chatFilters.ts#L52-L57)).
- Sort order is a preference and is persisted (#L59-L60).
- A badge counts how many choices differ from default ([#L72-L83](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/components/Conversations/chatFilters.ts#L72-L83)), and "reset" exists.

**Skein translation:** Knowledge and chat list filters (persona, tag, kind) follow the same rules. Session-scoped filters, persisted sort, and a filter chip showing "2 filters · Clear".

**L6. Search escalates to a results route.** Typing in the sidebar search is debounced at 500 ms and navigates to a full `/search` results view (message-level hits). Clearing returns to a new chat ([SearchBar.tsx#L73-L74](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/components/Nav/SearchBar.tsx#L73-L74), [#L125-L128](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/components/Nav/SearchBar.tsx#L125-L128)). On mobile, search is offered only while the conversation list is the active panel ([mobile/BottomBar.tsx#L60-L61](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/components/UnifiedSidebar/mobile/BottomBar.tsx#L60-L61)).

**Skein translation:** the palette's "Search Knowledge for "q"" row (and Ctrl+Shift+F) opens a full results route with title hits, then body hits with snippets. The palette itself stays a quick jumper. Skein's FTS5 search already exists in `VaultRepository`.

**L7. Model specs are curated "who answers" presets, and they are Skein's personas.**
- **Trigger:** a pill showing an icon and a *human* name, with a tooltip hint that includes its shortcut and `aria-keyshortcuts` ([ModelSelector.tsx#L68-L88](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/components/Chat/Menus/Endpoints/ModelSelector.tsx#L68-L88)).
- **Menu order:** a search combobox, then curated **model specs**, then providers, then custom groups ([#L106-L120](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/components/Chat/Menus/Endpoints/ModelSelector.tsx#L106-L120)).
- **What a spec is:** `label` + one-line `description` + icon + `preset` (model, parameters, skills) + optional `conversation_starters`. There is a hard `default` versus a first-run-only `softDefault` ([librechat.example.yaml#L1066-L1106](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/librechat.example.yaml#L1066-L1106)). Rows show label, description, a pin toggle and a check on the selected one ([ModelSpecItem.tsx#L63-L84](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/components/Chat/Menus/Endpoints/components/ModelSpecItem.tsx#L63-L84)).
- **Admin control:** an admin can hide raw models entirely (`interface.modelSelect === false`, [#L136-L139](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/components/Chat/Menus/Endpoints/ModelSelector.tsx#L136-L139)).

**Skein translation (the most transferable finding).** A Skein persona is already "system prompt + optional default model", which is a model spec. The chat's single chip should be an **"Ask as ‹Persona›"** picker:
- **Rows:** persona name + one-line description, with the resolved model shown secondary ("Research assistant · Gemma 4").
- **Advanced:** a "Model…" submenu for choosing the raw model, and "Manage personas" in the footer.
- **Defaults:** a first-run "soft default" persona.

This collapses two concepts (persona, model) into one Level-1 control and keeps raw models one level down (prompt §3, §20).

**L8. The empty chat speaks for the chosen persona.** The landing shows the selected spec or agent's description (falling back to a greeting; [Landing.tsx#L84-L85](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/components/Chat/Landing.tsx#L84-L85)) and up to N conversation starters taken from the agent or spec ([ConversationStarters.tsx#L48-L77](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/components/Chat/Input/ConversationStarters.tsx#L48-L77)).

**Skein translation:** the empty chat shows the persona's one-line description and 2–4 starter prompts defined on the persona. That gives a PocketPal-like first screen with one clear action and no feature tour (prompt §33 empty states).

**L9. Parameters are schema-driven, resettable, and savable as presets.**
- **Schema:** each setting is declared with `key`, localised `label`/`description`, `type`, `component` (slider/switch/dropdown/…), `range`, `default` and `optionType: model | conversation` ([generate.ts#L52-L82](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/packages/data-provider/src/generate.ts#L52-L82); e.g. temperature at [parameterSettings.ts#L39-L49](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/packages/data-provider/src/parameterSettings.ts#L39-L49)).
- **Panel:** one generic panel renders any schema, offers "Reset model parameters" (with a polite live-region announcement) and "Save as preset" ([Panel.tsx#L138-L165](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/components/SidePanel/Parameters/Panel.tsx#L138-L165), [#L233](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/components/SidePanel/Parameters/Panel.tsx#L233)).
- **Visibility:** the panel appears only when the admin enabled parameters *and* the endpoint supports them ([useSideNavLinks.ts#L208](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/hooks/Nav/useSideNavLinks.ts#L208)).

**Skein translation:**
- Declare `SamplingParams` UI as a small Kotlin table (key, label, one-sentence description, range, default, scope `persona | chat`) rendered by one Compose form.
- Place it in the **persona editor** ("How this persona answers") and in Models › Advanced, never in the chat.
- Keep "Reset to defaults" and "Save as persona".

**L10. Attachments say what they will do, and one-tap mode exists.** The attach menu names the *destination* before the file picker opens ([AttachFileMenu.tsx#L213-L313](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/components/Chat/Input/Files/AttachFileMenu.tsx#L213-L313); labels at [translation.json#L2850-L2863](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/locales/en/translation.json#L2850-L2863)):
- "Upload Image", shown only if the provider accepts images
- "Upload as Text"
- "Upload for File Search"
- "Upload to Code Environment"

Each entry is gated by capability. LibreChat then added a **"unified" mode** that removes the destination chooser and uploads on a single tap ([#L185-L211](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/components/Chat/Input/Files/AttachFileMenu.tsx#L185-L211)). That is evidence that the chooser cost too much for everyday use.

Errors are specific (size, type, count, total size; [translation.json#L1115-L1122](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/locales/en/translation.json#L1115-L1122)). Long pastes collapse into an editable chip ("Edit pasted text", "Move back into message", [#L1928-L1932](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/locales/en/translation.json#L1928-L1932)).

**Skein translation:**
- 📎 is **one tap** straight into SAF. Skein decides the handling: images go to vision if the model supports it, otherwise they are stored as attachments. Documents are imported into Knowledge and attached.
- The resulting chip states the outcome ("PDF · added to Knowledge", "Image · model can't see images").
- Long pastes (say, more than 1,500 characters) become a chip on the outer screen so the composer stays usable above the IME.

**L11. Retrieval activity is phrased in tenses, with human relevance.** File retrieval reads "*Searching your files*" and then "*Searched your files*" (strings at [translation.json#L2098](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/locales/en/translation.json#L2098) and [#L2216](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/locales/en/translation.json#L2216)). Cancelled or failed states have their own labels ([RetrievalCall.tsx#L466-L490](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/components/Chat/Messages/Content/RetrievalCall.tsx#L466-L490)). Each source shows its file name, a **relevance bar** labelled "Relevance: 83%", and **page numbers** ([#L304-L328](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/components/Chat/Messages/Content/RetrievalCall.tsx#L304-L328)).

**Skein translation:**
- Activity line: "Searching your notes…" becomes "Searched your notes · 3 used ▸".
- The Inspector shows each source's title, excerpt and locator (heading or page for PDFs).
- If relevance is shown at all, it is a bar, not `score 0.83`. The `recalledBy` breakdown goes under a "How these were found" disclosure.

**L12. Sibling navigation makes regenerate non-destructive.** "‹ 2 / 3 ›" under a message switches between regenerations or edits ([SiblingSwitch.tsx#L40-L75](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/components/Chat/Messages/SiblingSwitch.tsx#L40-L75)).

**Skein translation:** if "Regenerate answer" ships (palette catalogue #10), keep the previous answer as a sibling rather than overwriting it. Lifecycle-wise, `messages` gains a parent/sibling link; this is flagged for §50.

**L13. The shortcut registry is central, grouped, typing-aware, and never eats unhandled keys.**
- **Definitions** ([useKeyboardShortcuts.ts#L36-L285](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/hooks/useKeyboardShortcuts.ts#L36-L285)): one object per action with id, label key, **group** (General / Navigation / Chat / Panels), and per-platform display and ARIA strings.
- **Typing-aware:** only six actions fire while typing (`EDITING_ALLOWED_SHORTCUTS`: focus chat, focus search, show shortcuts, submit, escalate steer, upload; [#L289-L302](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/hooks/useKeyboardShortcuts.ts#L289-L302)).
- **Dispatcher** ([#L1046-L1104](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/hooks/useKeyboardShortcuts.ts#L1046-L1104)): ignores auto-repeat and already-claimed events, stays silent while any dialog or menu is open, and does not `preventDefault` when an action returns `false` (a no-op).
- **Guards:** actions carry their own preconditions. Archive and delete need a real, currently-routed chat ([#L720-L768](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/hooks/useKeyboardShortcuts.ts#L720-L768)). Temporary chat can be toggled only before the first message ([#L691-L710](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/hooks/useKeyboardShortcuts.ts#L691-L710)).
- **Chord ownership:** exactly one owner per chord, and user choices win over newly added defaults ([#L380-L442](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/hooks/useKeyboardShortcuts.ts#L380-L442)).
- **Composer Enter** is a pure, terminal decision table covering IME `isComposing`, submitting state, "Enter to send", a custom submit chord, and interrupt/pre-empt chords during a run ([shortcuts.ts#L256-L313](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/utils/shortcuts.ts#L256-L313)).
- **Validity:** custom bindings must include a modifier ([#L194-L212](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/utils/shortcuts.ts#L194-L212)).
- **Layout independence:** punctuation matches by *physical* key so non-US layouts work ([#L76-L110](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/utils/shortcuts.ts#L76-L110)).
- **Settings:** a global "Keyboard shortcuts" switch and a grouped dialog ([KeyboardShortcutsDialog.tsx#L292-L385](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/components/Nav/KeyboardShortcutsDialog.tsx#L292-L385)).

**Skein translation:** the ✎ ("fires while typing") flag, the not-consumed rule, modal suppression, destructive chords that always go through confirmation, and the pure composer-key function all come from here (CONTINUE.md §2, §6–7, §11). For layout independence on Android, match `Key.Slash`/`Key.Comma` key codes rather than produced characters.

**L14. The header collapses into zones as width shrinks.** The header has three zones in one DOM order that collapse with CSS only ([Header.tsx#L26-L31](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/components/Chat/Header.tsx#L26-L31), [#L81-L130](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/components/Chat/Header.tsx#L81-L130)):
- **Narrow:** `≡` · model pill · (New chat, **only when there is a chat to leave**, #L43-L48) · `⋯`.
- **Moved into ⋯:** Bookmarks, Compare and Temporary live in the ⋯ `HeaderMenu` ([HeaderMenu.tsx#L75-L110](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/components/Chat/Menus/HeaderMenu.tsx#L75-L110)).
- **Wide:** the same items appear inline.
- **Focus:** when the drawer covers the header on mobile, header controls leave the tab order (#L78-L79).

**Skein translation:** the outer-screen chat top bar is `≡ · [Persona ▾] · ⊕ (only if the chat isn't empty) · ⋯`. On the inner screen, the ⋯ items spill inline where room allows. The command bar's model text becomes the persona pill (L7).

**L15. The mobile drawer is labelled, thumb-reachable, and one layer.**
- **Labelled switcher:** the drawer title doubles as a panel switcher that "*replaces the icon rail's ten unlabelled glyphs with labelled rows, and costs no standing width*" ([mobile/Switcher.tsx#L11-L16](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/components/UnifiedSidebar/mobile/Switcher.tsx#L11-L16)).
- **Thumb reach:** Search and New chat move to a **bottom bar**, because "*both sat in the top corner — the hardest place to reach one-handed*" ([mobile/BottomBar.tsx#L13-L18](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/components/UnifiedSidebar/mobile/BottomBar.tsx#L13-L18)).
- **Width:** full width by default, with an optional 80 % "strip" that keeps a slice of the conversation visible ([UnifiedSidebar/constants.ts#L1-L21](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/components/UnifiedSidebar/constants.ts#L1-L21)).
- **Gated panels:** sidebar panels appear only when the feature is enabled and permitted (`useSideNavLinks.ts`).

**Skein translation:** on the outer screen, the drawer lists **recent chats** (the thing people switch between most) under a labelled section switcher (Chats · Knowledge · Graph · Models · Settings). Search and New chat sit at the drawer's bottom for one-handed use. The drawer is full width. This directly addresses prompt §20's "avoid duplicate navigation systems": one labelled switcher replaces rail plus hamburger plus tabs on compact widths.

## Patterns Not Worth Adopting

- **Side-by-side multi-model compare.** The "+" next to the model pill clones the chat into a second pane (`AddMultiConvo.tsx`, [useMultiConvo.ts](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/hooks/Chat/useMultiConvo.ts)), and `+` typed as a sigil adds a model. Skein holds **one resident model**, and a swap costs 2–5 s plus RAM headroom on a 16 GB device (spec §6). Two live models is impractical, and sequential compare would double generation time and thermal load. Reject for v1.
- **Fork with three modes and eight explanatory strings** ([translation.json#L1528-L1547](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/locales/en/translation.json#L1528-L1547)): visible-only, related-branches and all-to-target, plus a "remember" option. This is a textbook case of exposing internal data-model distinctions to users. If Skein ever needs it, offer one action, "Duplicate chat from here".
- **The sigil zoo.** `@` switches model/agent/preset, `+` adds a model, `/` opens prompts and `$` opens skills, each with its own popover ([useHandleKeyUp.ts#L94-L144](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/hooks/Input/useHandleKeyUp.ts#L94-L144)). Each needs a **user setting to disable it** ([store/settings.ts#L120-L123](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/store/settings.ts#L120-L123)), which is evidence they collide with ordinary typing. Skein keeps two: `/` (palette) and `[[` (Knowledge). The *trigger rule* is worth keeping: only as the first character, and only while the input is at most 5 characters ([#L34-L49](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/hooks/Input/useHandleKeyUp.ts#L34-L49)). Skein's composer already uses the first-character rule (`ChatBottomBar.kt:168`).
- **Shift-held instant archive and delete.** Holding Shift on the active row swaps the ⋯ for one-click Archive and Delete with **no confirmation** ([ConvoOptions.tsx#L414-L439](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/components/Conversations/ConvoOptions/ConvoOptions.tsx#L414-L439)). This conflicts with prompt §29 unless Skein has undo, and invisible modifier-dependent UI doesn't exist on touch.
- **31 remappable shortcuts with a recorder**, including 8 panel-open actions with no default chord ([useKeyboardShortcuts.ts#L221-L284](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/hooks/useKeyboardShortcuts.ts#L221-L284)). Skein v1 ships a fixed set of about 20 chords with no remapping.
- **Ctrl+Shift+letter for almost everything** (for example, Stop is Ctrl+Shift+X). That is a browser workaround: web apps can't take Ctrl+N or Ctrl+W from the browser. A native Android app can, so Skein uses the conventional Ctrl+N, Ctrl+W and Esc.
- **Dispatch by DOM query.** Shortcuts `querySelector` and `.click()` the visible buttons (for example `[data-testid="stop-generation-button"]`, [#L606-L609](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/hooks/useKeyboardShortcuts.ts#L606-L609)). The intent (a shortcut equals its button) is right. The mechanism is fragile, and Skein gets the same guarantee structurally by having one `SkeinCommand` behind both.
- **Hover cards for parameter descriptions** (`OptionHover.tsx`). They are invisible on touch. Use inline supporting text.
- **A parameters panel in the main sidebar.** Level-1 users should never meet temperature. Skein places it in the persona editor and Models › Advanced (L9).
- **Cloud and multi-user surfaces**: shared links, SharePoint, projects with sharing permissions, marketplace, agent and MCP builders, schedules, memories, API-key dialogs, endpoints/providers. None apply to an offline, single-user vault.
- **The activity-phase folding engine** (`ActivityPhaseGroup.tsx`, 742 lines; `utils/activityLabels.ts`, 813 lines) for parallel agents and subagents. Skein needs one activity line per answer (L11).
- **Server-side LLM titling as-is.** Titles come from a separate, usually cheaper `titleModel` after the first turn, skip temporary chats, and are aborted on Stop (`api/server/services/Endpoints/agents/title.js#L55-L92`; `titleModel` examples at `librechat.example.yaml#L810-L992`). Skein has one expensive on-device model, so an extra title pass costs latency, battery and heat. The on-device alternative is in the Answers section below.

## Potential Reusable Code

MIT permits reuse with notice, but nothing is worth porting from React/TS to Compose:

| Candidate | Size | Verdict |
| --- | --- | --- |
| `resolveComposerKeyDown` ([shortcuts.ts#L256-L313](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/utils/shortcuts.ts#L256-L313)) | ~60 lines, pure | **Reimplement.** Skein's version needs Android's IME composition signal and `KeyEvent` types, and a clean-room Kotlin function with its own test is under an hour and avoids NOTICE bookkeeping. If it is transliterated, carry the MIT notice. |
| `groupConversations` / `getGroupName` ([convos.ts#L38-L57](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/utils/convos.ts#L38-L57)) | ~20 lines | Reimplement with `java.time`. The bucket *list* is the value. |
| `chatFilters.ts` state model | ~110 lines of Jotai atoms | Reimplement as a ViewModel state class. Keep the persist/don't-persist rule. |
| `SettingDefinition` schema ([generate.ts#L52-L82](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/packages/data-provider/src/generate.ts#L52-L82)) | type only | Borrow the *shape*; write a ~6-field Kotlin data class. |

Migration cost of any port is dominated by rewriting React state into Compose/ViewModel state. It is never "copy and adapt".

## Android/Fold Relevance

| LibreChat pattern | Fold outer screen (compact, touch, IME) | Unfolded Fold (with or without keyboard) | Do not copy |
| --- | --- | --- | --- |
| L1 row ⋯ always visible | Trailing ⋯ plus long-press opening a **menu sheet** | ⋯ on the focused or hovered row; list-local keys (see ZED Z15) | Hover-only reveal |
| L2 delete semantics | Confirm dialog naming the chat; land on a new chat | Confirm; land on the next list item; TalkBack announcement | Instant delete |
| L3/L4/L5 archive, buckets, filters | Buckets as sticky headers; filter chip row | Same in the Navigate pane | Persisted filters |
| L6 search route | Palette field, then a full-screen results route | Results in the Work pane; the list stays | — |
| L7 persona pill | `[Persona ▾]` in the top bar → **modal bottom sheet** (spec rows, "Model…" submenu) | Popover; Ctrl+Shift+M | Raw model list by default |
| L8 landing | Persona description plus 2–3 starters above the composer | Same, centered | Greeting animations |
| L9 parameters | Full-screen settings route under the persona editor | Same, or a dialog | Chat-surface panel |
| L10 attachments | 📎 one tap to SAF; outcome chip; long-paste chip | Same; Ctrl+Shift+A | Destination chooser by default |
| L11 activity | "Searched your notes · 3 used ▸" → Inspector **sheet** | Inspector **pane** | Raw scores |
| L12 siblings | ‹ 2/3 › under the answer | Same | Fork modes |
| L13 shortcuts | n/a | The registry drives chords and the system Shortcuts Helper | Remapping, Ctrl+Shift-everything |
| L14 header zones | `≡ · Persona · ⊕ · ⋯` | Inline actions spill out of ⋯ | Gradient overlay header |
| L15 drawer | Full-width drawer: labelled switcher, recent chats, bottom Search/New | The drawer becomes the persistent Navigate pane | Unlabelled icon rail on compact |

**Fold continuity** (prompt §24): the persona and model choice, list filter state (session-scoped), an open ⋯ menu target and a pending delete confirmation must survive fold/unfold. LibreChat's lesson from late-resolving mutations applies: resolve "the open chat" at commit time. After a posture change the "open chat" may be shown in a different pane.

## Mac UX-Lab Relevance

- **Live reference.** LibreChat runs locally with `docker compose` (MongoDB, Meilisearch, RAG API) and can point at a local model server. In a desktop browser's device toolbar at about 412 px width (close to the outer screen), it is a useful *live* reference for the header collapse (L14), the mobile drawer (L15) and the row menu (L1). It is heavy (several containers), so use it for reference sessions only, not as part of the lab. Skein must not become a web app (prompt §37 Layer C).
- **Layer A previews this study implies:**
  - conversation list with date buckets and long titles
  - archived view with an active filter badge
  - row ⋯ menu sheet
  - delete-chat confirmation naming the chat
  - persona sheet with descriptions and the model submenu
  - empty chat with persona starters
  - parameters form (sliders with supporting text)
  - attachment chips (added to Knowledge / model can't see images / long paste)
  - activity line collapsed and expanded
- **Layer B:** the outer drawer at 400 dp, full width versus an 80 % strip. This is a real decision the IA owner can make from two previews.
- **Unit-testable on the Mac (JVM):** the date-bucket function, the filter/sort state rules, and the composer key decision table.

## Recommended Action

**STUDY ONLY.** The licence is MIT, but the stack is web/React and nothing merits porting. The patterns that matter go to the owners in the table below.

| Pattern | Destination |
| --- | --- |
| Lifecycle (L1–L5, L12) | The chat/note lifecycle work (prompt §§28–31, §50) |
| Shell (L7, L8, L14, L15) | Information architecture and adaptive shell (Waves 1 and 3) |
| Model and settings (L9, L10, L11) | Models/settings and chat waves (Waves 4 and 9) |
| Shortcut registry (L13) | The palette and keyboard design in [`CONTINUE.md`](CONTINUE.md#proposed-skein-command-palette--shortcuts) |

## Expected Benefit

- **A complete, touch-correct conversation lifecycle:** rename, pin, archive, delete-with-named-confirmation, defined post-delete navigation, and useful date grouping. This closes prompt §51's "Chats" acceptance items.
- **One "who answers" control:** personas presented as curated specs with the raw model one level down. Two concepts become one Level-1 control, and model identity becomes human-readable (prompt §34, §51).
- **An empty chat that says what to do** (persona description plus starters), meeting the prompt's "five seconds" goal (§1).
- **Advanced model settings** that exist without touching Level 1.
- **Attachments and retrieval** that explain themselves before and after sending.
- **Evidence-backed shortcut rules** (typing-aware, modal-safe, non-consuming, confirm-destructive) for the palette design.

## Expected Cost

- **Lifecycle UI (L1–L5):** about 3–5 days of UI, *gated on* the lifecycle semantics spec (§50). Archive in particular needs a schema decision: is it a frontmatter flag or a column on `documents`? That decision is not UI-only.
- **Persona pill and sheet (L7, L8):** about 2–3 days, plus a small persona field for description and starters. The persona schema has `name`, `system_prompt` and `default_model` today (spec §5), so description and starters are additive columns or frontmatter.
- **Parameters form (L9):** about 1–2 days once `SamplingParams` fields are known.
- **Attachment chips and one-tap attach (L10):** about 1–2 days; `ImportService` already does the work.
- **Activity line (L11):** about 1 day of presentation mapping; no RAG changes.
- **Risks:**
  - Schema creep (archive, siblings, persona description). Mitigation: route each through §50 before any UI.
  - Reintroducing LibreChat's breadth. Mitigation: the "Not worth adopting" list.

---

## Answers to the prompt's specific questions

**Multi-model interaction.** LibreChat offers four routes:
1. The model pill: curated specs, then providers, with search, pins and favourites (L7).
2. `@` mentions to switch endpoint, model or preset mid-chat.
3. `+` or the header "+" to add a **second** model for side-by-side answers (`useMultiConvo`).
4. Regenerations as siblings, "‹ 2/3 ›" (L12).

For Skein, only (1), reframed as personas, and (4) survive. Side-by-side compare and mid-chat model sigils conflict with the single-resident on-device model and its swap cost. Switching must stay an explicit pick (see CONTINUE.md on why there is no model-cycle chord).

**Conversation navigation.**
- **Grouping:** Today / Yesterday / Previous 7 / Previous 30 days / months / years (L4), plus a Pinned section and a Projects section (`PinnedSection.tsx`, `ProjectsSection.tsx`).
- **Search:** debounced, escalating to a full results route (L6).
- **Rename:** inline field; Enter commits, Esc cancels, maximum 100 characters ([RenameForm.tsx#L33-L59](https://github.com/LibreChat-AI/LibreChat/blob/7b2362d7a7c6148b84850924dc7fa5fc43307923/client/src/components/Conversations/RenameForm.tsx#L33-L59)).
- **Delete:** confirmation naming the title, and landing on a new chat if the open one was deleted (L2).
- **Archive:** reversible, with its own view and sort (L3).
- **Filter and sort:** filters are session-scoped, sort is persisted, and a badge counts deviations (L5).
- **Titles:** automatic and LLM-generated. Skein's equivalent must be cheaper:
  1. Title each chat immediately from the first user message (trimmed to about 6 words), which removes the anonymous "Chat" entries on day one.
  2. Optionally refine it with the on-device model only when idle and charging, reusing the ingest worker's constraints (spec §7.1).
  3. Offer "Regenerate title" in the chat's ⋯ menu, as Zed does.

**Tool and agent presentation.**
- Retrieval shows as a tensed activity line ("Searching your files" → "Searched your files") with per-source relevance bars and page numbers (L11).
- Tool calls fold into activity-phase cards with a live ticker, then a summary. This machinery is built for parallel agents and is too heavy for Skein.
- Approvals appear as a pending panel above the composer (`PendingToolApprovalPanel` in `ChatForm.tsx`). This matches Continue's single status strip (CONTINUE.md C9), which is the pattern to adopt.

For Skein: one activity line per answer that expands into the Inspector, and a status-strip state for any future approval.

**Attachments.**
- The destination is chosen before picking, and each option is gated by capability.
- A unified one-tap mode was added because the chooser was too costly.
- Errors are precise, and long pastes become editable chips (L10).

For Skein: one tap, Skein decides the handling, and the chip explains the outcome.

**Advanced model settings: presets and parameters panel.**
- Parameters are declared as schema data and rendered by one generic panel with Reset and Save-as-preset. The panel appears only where supported and enabled (L9).
- Presets and model specs bundle model, parameters and prompt (L7). The presets menu shows only when the admin enables both presets and model select (`Header.tsx#L99-L101`).

For Skein, the persona *is* the preset: sampling parameters live in the persona editor ("How this persona answers") and in Models › Advanced, with Reset and "Save as persona".

**How LibreChat keeps a familiar chat shell around complex functionality.** It keeps the ChatGPT-shaped shell (left list, centre conversation, bottom composer, top model pill) constant and makes everything else **conditional, collapsible or displaced**:
- Header items collapse into ⋯ by width, and New chat appears only when there's a chat to leave (L14).
- Sidebar panels exist only when the feature is enabled and permitted.
- On mobile, a labelled switcher replaces the icon rail and the frequent actions move to the thumb zone (L15).
- Row actions live in an always-reachable ⋯ (L1).
- Parameters live in a side panel, not the chat (L9).
- Model complexity sits behind curated specs, and raw models can be hidden entirely (L7).

The failure cases are also instructive. Four composer sigils needed disable-switches, fork has three modes, and there are 31 shortcuts. Each extra concept LibreChat surfaced by default later needed a setting to turn it off. Skein should start at the conditional end and add surface area only with evidence.

**Command palette connection.** LibreChat has no command palette. Its shortcut registry (L13) is the most careful "keyboard layer over a chat app" in the three references. It supplies the palette design's typing-aware (✎) flag, not-consumed rule, modal suppression, destructive-confirm rule and pure composer-key function. See [`CONTINUE.md` → Proposed Skein command palette & shortcuts](CONTINUE.md#proposed-skein-command-palette--shortcuts).

---

**What specific problem in Skein can this project help us solve?** LibreChat shows how to wrap Skein's growing capability in a familiar, touch-correct chat shell: a conversation list with a real lifecycle (rename, archive, and a delete that names the chat and defines where you land, with date buckets and no anonymous "Chat" titles), personas presented as one curated "who answers" picker with raw models one level down, attachments and retrieval that explain themselves, and a header and drawer that collapse cleanly onto the Fold's 400 dp outer screen.
