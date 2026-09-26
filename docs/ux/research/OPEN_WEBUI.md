# Open WebUI: reference study for Skein

**Bead:** `skein-xtov.6` (epic `skein-xtov`) · **Authority:** `docs/research/SKEIN_UI_UX_OVERHAUL_PROMPT.md` §§1–3, 14, 20, 27, 34–35, 52–53 · **Spec context:** `docs/superpowers/specs/2026-09-19-skein-design.md` §5, §8
**Companions:** [`ANYTHINGLLM.md`](ANYTHINGLLM.md), which holds the shared design in [Proposed Skein context presentation](ANYTHINGLLM.md#proposed-skein-context-presentation), and [`LOBECHAT.md`](LOBECHAT.md).
**Status:** Research record. Advisory. **No code, copy, icons or branding are taken from Open WebUI** (see License). Skein references are to repository paths at `009cbb6`.

---

## Project

| Field | Value |
| --- | --- |
| Repo URL | <https://github.com/open-webui/open-webui> |
| Commit inspected | `8bd8b4fac5e059578ac0c74b3c18d11139f88b7d`, which is exactly release tag **`v0.11.4`** (published 2026-09-21; *"Merge pull request #29960 from open-webui/dev"*, committed 2026-09-21T15:25:06-04:00) |
| Inspection date | 2026-09-26 |
| Local clone | `research/clones/open-webui/` (`git clone --depth 1`), git-ignored by `.gitignore:121`. It is never committed. |
| Stack | SvelteKit + Tailwind frontend (`src/`); Python FastAPI backend (`backend/open_webui/`). It runs self-hosted with `pip install open-webui` → `open-webui serve` ([README L118-125](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/README.md#L118-L125)) or Docker, usually alongside Ollama. |

Areas read (all permalinks are pinned to the inspected SHA):

- **Composer:** [`MessageInput.svelte`](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/src/lib/components/chat/MessageInput.svelte), [`InputMenu.svelte`](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/src/lib/components/chat/MessageInput/InputMenu.svelte), [`Commands/Knowledge.svelte`](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/src/lib/components/chat/MessageInput/Commands/Knowledge.svelte), [`Commands/SlashCommands.svelte`](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/src/lib/components/chat/MessageInput/Commands/SlashCommands.svelte)
- **Per-chat controls:** [`ChatControls.svelte`](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/src/lib/components/chat/ChatControls.svelte), [`Controls/Controls.svelte`](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/src/lib/components/chat/Controls/Controls.svelte), [`Settings/Advanced/AdvancedParams.svelte`](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/src/lib/components/chat/Settings/Advanced/AdvancedParams.svelte), [`common/FileItemModal.svelte`](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/src/lib/components/common/FileItemModal.svelte)
- **Models:** [`ModelSelector.svelte`](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/src/lib/components/chat/ModelSelector.svelte), [`ModelSelector/ModelItem.svelte`](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/src/lib/components/chat/ModelSelector/ModelItem.svelte), [`ModelSelector/Selector.svelte`](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/src/lib/components/chat/ModelSelector/Selector.svelte), [`workspace/Models/ModelEditor.svelte`](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/src/lib/components/workspace/Models/ModelEditor.svelte)
- **Conversations and settings:** [`layout/Sidebar/ChatMenu.svelte`](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/src/lib/components/layout/Sidebar/ChatMenu.svelte), [`layout/Sidebar/Folders/FolderModal.svelte`](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/src/lib/components/layout/Sidebar/Folders/FolderModal.svelte), [`chat/SettingsModal.svelte`](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/src/lib/components/chat/SettingsModal.svelte), [`lib/utils/settings-search.ts`](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/src/lib/utils/settings-search.ts)
- **Messages:** [`Messages/Citations.svelte`](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/src/lib/components/chat/Messages/Citations.svelte), [`ResponseMessage/StatusHistory/StatusItem.svelte`](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/src/lib/components/chat/Messages/ResponseMessage/StatusHistory/StatusItem.svelte)
- **Backend merge logic:** [`backend/open_webui/main.py` L1165-1177](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/backend/open_webui/main.py#L1165-L1177), [`utils/misc.py` L34-40](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/backend/open_webui/utils/misc.py#L34-L40), [`utils/middleware.py` L2586-2644](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/backend/open_webui/utils/middleware.py#L2586-L2644)

## License

**No SPDX identifier.** GitHub's licence API reports **`NOASSERTION`**, and `pyproject.toml` says only `license = { file = "LICENSE" }`. The repository uses **three licences, split by commit date**, as set out in [`LICENSE_NOTICE`](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/LICENSE_NOTICE):

| Code committed | Licence | Boundary commit (resolved via the GitHub API) |
| --- | --- | --- |
| before `a76068d69cd59568b920dfab85dc573dbbb8f131` | **MIT** (`Copyright (c) 2023 Timothy Jaeryang Baek`) | `a76068d` = 2025-01-10, *"Update LICENSE"* |
| from `a76068d` up to and including `60d84a3aae9802339705826e9095e272e3c83623` | **BSD 3-Clause** (`Copyright (c) 2023-2025 Timothy Jaeryang Baek`) | `60d84a3` = 2025-04-18, *"chore: license "branding" clause"* |
| after `60d84a3` | **"Open WebUI License"** (current [`LICENSE`](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/LICENSE)) | — |

The current `LICENSE` is BSD-3-Clause's three conditions (retain notice in source; reproduce notice in binaries; no endorsement using the names) **plus a fourth condition**, quoted exactly:

> 4. Notwithstanding any other provision of this License, and as a material
>    condition of the rights granted herein, licensees are strictly prohibited
>    from altering, removing, obscuring, or replacing any "Open WebUI"
>    branding, including but not limited to the name, logo, or any visual,
>    textual, or symbolic identifiers that distinguish the software and its
>    interfaces, in any deployment or distribution, except in the following
>    circumstances: (i) deployments or distributions where the total number
>    of end users (defined as individual natural persons with direct access
>    to the application) does not exceed fifty (50) within any rolling
>    thirty (30) day period; (ii) the licensee has obtained specific prior
>    written permission from the copyright holder; or (iii) where the
>    licensee has obtained a duly executed enterprise license expressly
>    permitting such modification. For all other cases, any removal or
>    alteration of the "Open WebUI" branding shall constitute a material
>    breach of license.

The file also says: *"Materials governed by prior licenses retain those original license terms, as specified in LICENSE_HISTORY"*. Contributors grant Open WebUI Inc. the right to *"sublicense, and commercialize my work under any terms they choose, both now and in the future"* ([`CONTRIBUTOR_LICENSE_AGREEMENT`](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/CONTRIBUTOR_LICENSE_AGREEMENT)), so the terms can change again.

**What this means for Skein's code reuse:**

- **Code from after 2025-04-18**, which includes every UI surface studied here (the v0.11 composer, the tabbed controls panel, the model selector), comes with a condition that requires shipping Open WebUI's branding in any distribution to more than 50 users. That condition goes beyond BSD-3, so the licence is **not** one of the `foss` allowlist licences (Apache-2.0, MIT, BSD-\*, ISC, CC0, OFL-1.1, Unlicense; `docs/Handoffs/skein-v1-autonomous-completion.md` §3 item 6). Skein is distributed publicly under its own name (Apache-2.0 `LICENSE`), so it **cannot take this code**.
- **Code from before 2025-01-10** is MIT and could legally be reused with its notice. None of the patterns below live in that code as inspected, and all of it is Svelte or Python.
- **Behaviour, layout ideas and interaction patterns are not code.** Skein can re-implement them in its own words and visuals. It must not reproduce Open WebUI's strings verbatim, its icon set, its visual identity or its name.

## Maintenance

- **Very active.** Last push 2026-09-26. At least 100 commits in the 30 days before inspection. `v0.11.4` released 2026-09-21. About 153k stars and 324 open issues. Not archived. Created 2023-10-06.
- **High churn in exactly the areas studied.** In v0.11.x the model selector moved into the composer's bottom-right ([`MessageInput.svelte` L2575-2585](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/src/lib/components/chat/MessageInput.svelte#L2575-L2585)), and the per-chat controls became a tabbed Controls / Files / Overview panel. Treat layouts as a snapshot. The underlying *principles* (inherit-by-default settings, one content in two containers, only showing what's on) are stable across versions.

## Relevant Skein Problem

**Primary lesson (brief §14):** *How can Skein expose extensive configuration without permanently cluttering chat?*

Skein's configuration surface is already wide and still growing:

- personas (system prompt + default model, spec §5)
- models (import, hash verification, capabilities, `Model.contextLength`)
- sampling (`SamplingParams`)
- retrieval (`k = 8` spec literal)
- ingest and lock policy
- export
- and, coming next, skills, commands and graph (brief §3 Level 2)

Today the chat header shows a literal `"chat"` title and a `⚹ context` text button (`feature/chat/.../ChatScreen.kt:79-82`). The spec's command bar carries model status as `qwen · ●` (spec §8.2). Nothing defines **which level a setting lives at** (global, persona, chat, model), **how an override is shown**, or **where the model/provider choice sits** so that local stays the default identity (brief §34) with room for optional external providers later. Brief §28–29 also needs conversation management (rename, delete, search, archive) that doesn't turn into a second navigation system (§20).

Open WebUI is the most configurable chat UI studied. It has admin, user, model-preset, folder and per-chat levels, plus ~34 generation parameters, plugins and tools. Most of that complexity stays out of the conversation. It is the right reference for *layering*.

## Patterns Worth Adopting

**O1 · A four-level settings cascade where "inherit" is a first-class value.** Parameters merge as follows: admin global defaults < per-model preset < user < per-chat.
- Backend: [`main.py` L1169-1177](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/backend/open_webui/main.py#L1169-L1177), `merge_model_params` in [`utils/misc.py` L34-40](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/backend/open_webui/utils/misc.py#L34-L40).
- Client: `{...$settings?.params, ...params}` in [`Chat.svelte` L3578-3582](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/src/lib/components/chat/Chat.svelte#L3578-L3582).

Every parameter starts as `null`, shown as **"Default"**. Tapping it turns it into **"Custom"** and only then reveals the slider ([`AdvancedParams.svelte` L18-52, L159-176](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/src/lib/components/chat/Settings/Advanced/AdvancedParams.svelte#L159-L176)). The **same component** is reused in user Settings, the model editor, and per-chat Controls ([`Controls.svelte` L131-146](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/src/lib/components/chat/Controls/Controls.svelte#L131-L146)).

→ *Skein:* three levels: **Settings (global defaults) < Persona < This chat**. One Compose `ParamRow(value: T?, inherited: T, from: Scope)` shows *"Default · from Researcher"*. That is an improvement on Open WebUI, which never says where an inherited value comes from. There is no model-level preset level: personas already carry the model (spec §5).

**O2 · Per-chat controls in a panel, with one content and two containers.** [`Controls.svelte`](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/src/lib/components/chat/Controls/Controls.svelte#L18-L146) groups Files, Valves, System Prompt and Advanced Params into collapsible sections. Each section's open/closed state is **remembered** (L18-30).
- [`ChatControls.svelte`](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/src/lib/components/chat/ChatControls.svelte#L147-L200) listens to `matchMedia('(min-width: 1024px)')` **with a change handler**. It renders the same content in a `Drawer` below the breakpoint (L195-200) and in a `ResizableSidePanel` above it (L309-316).
- The panel has Controls / Files / Overview tabs. If a tab disappears it falls back to another, and if none remain the panel closes itself (L86-99).

→ *Skein:* this is the inspector's container model: a `ModalBottomSheet` on the outer screen and a supporting pane on the inner screen, switching live on `WindowSizeClass` changes (brief §24). Skein's rule is based on the width budget, not on 1024 px, which would put the ≈800 dp inner Fold screen in drawer mode (see [P5](ANYTHINGLLM.md#p5--containers-and-fold-behaviour)).

**O3 · The composer shows only what's on.** Capabilities sit behind one "integrations" menu. Only the **enabled** ones appear next to it, as tinted, removable pills: tool count, skill count, active filters, web search, image generation, code interpreter ([`MessageInput.svelte` L2366-2500](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/src/lib/components/chat/MessageInput.svelte#L2366-L2500)).

→ *Skein:* the context chip carries only deviations and attachments (`Knowledge off`, `Notes only`, `2 notes`). Skills and tools, when they exist, appear only while active. The one exception is `Knowledge on` in the pristine state, because whether the vault is being read is Skein's core trust question.

**O4 · A "+" menu with drill-in lists in the same surface.** The menu offers Upload Files · Capture · Attach Webpage · **Attach Files · Attach Notes · Attach Knowledge · Reference Chats**. Each "Attach" opens a searchable sub-list inside the same popover, with back ([`InputMenu.svelte` L135-383, L594-668](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/src/lib/components/chat/MessageInput/InputMenu.svelte#L135-L383)). Items the model can't handle are disabled with a reason: *"Model(s) do not support file upload"* (L189-191).

→ *Skein:* the Attach sheet (Notes · Files · Chats filters, *Import file…*). *Photo* appears only when the loaded model has vision (`models.capabilities`, spec §5) and is otherwise **hidden**, because the brief bans dead controls.

**O5 · Inline knowledge attach from the keyboard.** Typing `#` searches Folders, Collections and Files, with type headers and a 200 ms debounce ([`Commands/Knowledge.svelte` L84-131, L142-203](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/src/lib/components/chat/MessageInput/Commands/Knowledge.svelte#L84-L203)).

→ *Skein:* reuse the existing **`[[`** wikilink autocomplete (spec §8.4) as the inline attach trigger. **Do not** introduce `#`: in Skein's Obsidian-compatible Markdown it means a tag.

**O6 · Each attachment chooses whole document or retrieval.** A switch in the file detail reads *"Using Entire Document"* or *"Using Focused Retrieval"*, with plain explanations: *inject the entire content… recommended for complex queries* versus *segmented retrieval… recommended for most cases* ([`FileItemModal.svelte` L347-373](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/src/lib/components/common/FileItemModal.svelte#L347-L373)).

→ *Skein:* **Whole note / Relevant parts** on each attached row, chosen automatically by budget and changeable by the user.

**O7 · Context usage in product terms, one step away.** `/status` opens a small panel above the composer: *Context usage 62% 10k/16k* with a thin bar, plus queued messages and tasks ([`MessageInput.svelte` L535-548, L1816-1891](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/src/lib/components/chat/MessageInput.svelte#L1816-L1891)). `/compact` shows a ring and *"{{percent}}% full"* with the explanation *"Shorten older messages so this chat can keep going."* ([`SlashCommands.svelte` L212-270](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/src/lib/components/chat/MessageInput/Commands/SlashCommands.svelte#L212-L270)).

→ *Skein:* the inspector's **Context** bar, and the chip's ring past a threshold. A future "shorten older messages" action would belong there too, but it is out of scope until `SendPipeline` supports summarization.

**O8 · Show a mode by changing the thing it affects.** A temporary chat gives the composer a **dashed border** ([`MessageInput.svelte` L1896-1897](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/src/lib/components/chat/MessageInput.svelte#L1896-L1897)) instead of a banner.

→ *Skein:* **Notes only** gives the composer an accent outline and appears in the chip. This is a cheap signal that doesn't cost a row.

**O9 · Model presentation that keeps technical detail secondary.**
- The model **name** is primary. A small chip shows parameter size, and its tooltip shows *quantization + GB*. A green dot means *"Loaded"* or *"Unloads in 4 minutes"*. External and Direct connections get an icon; **local models get no badge**. There is an *Eject* action ([`ModelItem.svelte` L123-240](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/src/lib/components/chat/ModelSelector/ModelItem.svelte#L123-L240)).
- Users can pin models and "Set as default" ([`ModelSelector.svelte` L26-46](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/src/lib/components/chat/ModelSelector.svelte#L26-L46)).
- Local/External **filter chips appear only when both kinds exist** ([`Selector.svelte` L356-366](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/src/lib/components/chat/ModelSelector/Selector.svelte#L356-L366)).
- The composer's model trigger is capped at 10–13 rem wide (L2575-2585).

→ *Skein:* the header subtitle reads `Qwen 2.5 3B · Local`. Model sheet rows show name, `3B · 2.0 GB` and `Loaded`. Filename, GGUF, quant, hash and backend go in model details (brief §34). A Local/External split appears **only if** an external provider ever exists (see License above and [P0.6](ANYTHINGLLM.md#p0--principles)).

**O10 · Lightweight containers that carry defaults.** Folders hold a **system prompt and knowledge** that the chats inside them inherit ([`FolderModal.svelte` L216-241](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/src/lib/components/layout/Sidebar/Folders/FolderModal.svelte#L216-L241); applied server-side in [`middleware.py` L2586-2603](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/backend/open_webui/utils/middleware.py#L2586-L2603)).

→ *Skein:* **personas** are already this container (system prompt + default model). Add optional default knowledge (notes or tags) to personas. That knowledge then shows in the inspector as *From persona*. Do **not** add folders as another concept.

**O11 · One ⋮ menu for conversation lifecycle.** Share, Download (JSON/TXT/PDF), Rename, Mark as unread, Pin/Unpin, Clone, Move (to folder), Archive/Unarchive, Delete ([`ChatMenu.svelte` L305-475](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/src/lib/components/layout/Sidebar/ChatMenu.svelte#L305-L475)). Archived chats have their own settings tab.

→ *Skein* (brief §§28–29): **Rename · Pin · Export (MD/PDF/DOCX; existing writers) · Archive** (if clean) **· Delete** (with confirmation). Share-link, Mark unread and Clone are skipped. The same menu serves the chat header ⋮ and a long-press on a chat in the list.

**O12 · Search across all settings.** An index is built from the i18n `label`/`title` keys of each settings tab, filtered by what the user may access ([`settings-search.ts`](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/src/lib/utils/settings-search.ts#L1-L60); [`SettingsModal.svelte` L186-262](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/src/lib/components/chat/SettingsModal.svelte#L186-L262)).

→ *Skein:* index settings from string resources by screen. The same index feeds the command palette (brief §42), so deep settings never need a top-level place.

**O13 · Activity in plain language, and relevance shown only when it means something.** Status rows read *Searching Knowledge for "…"*, *Querying* with query chips, and *Retrieved N sources*, with a shimmer while active ([`StatusItem.svelte` L39-120](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/src/lib/components/chat/Messages/ResponseMessage/StatusHistory/StatusItem.svelte#L39-L120)). Citations show relevance **only** when the distances are comparable, and percentages only when every distance falls in [-1, 1] ([`Citations.svelte` L79-107](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/src/lib/components/chat/Messages/Citations.svelte#L79-L107)).

→ *Skein:* the Activity rows in [P4](ANYTHINGLLM.md#p4--the-context-inspector). Hide fused-rank scores by default for the same reason.

## Patterns Not Worth Adopting

- **An admin/user/permission matrix.** There are 13 user settings tabs (general, interface, notifications, shortcuts, connections, tools, personalization, audio, data controls, usage, archived chats, account, about; [`SettingsModal.svelte` L186-262](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/src/lib/components/chat/SettingsModal.svelte#L186-L262)) plus about 20 admin tabs (`src/lib/components/admin/Settings/`), and `$user?.permissions.chat.*` gates throughout. Skein has one user on one device.
- **Raw backend parameters.** About 34 are exposed (`num_ctx`, `mirostat_eta`, `tfs_z`, `num_gpu`, `use_mlock`, `keep_alive`, `logit_bias`…; [`AdvancedParams.svelte` L18-52](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/src/lib/components/chat/Settings/Advanced/AdvancedParams.svelte#L18-L52)). Level 2 in Skein offers at most 2–4 product-named parameters per persona (see `LOBECHAT.md` L6). Runtime knobs such as context length, batch, mmap and threads belong to model details or a developer surface, where the inference agents own them.
- **Valves, Functions, Pipelines and Filters** run arbitrary Python server-side. Skein's threat model (isolated inference, no network, spec §§2, 9) rules them out, and Skein's skills have their own guardrails (`docs/design/SKILL_GUARDRAILS.md`).
- **Comparing several models in one chat** (`multipleEnabled`, [`ModelSelector.svelte` L83-87](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/src/lib/components/chat/ModelSelector.svelte#L83-L87)). Skein keeps one model resident on 16 GB (spec §6).
- **Web search, image generation, code interpreter, Google Drive and OneDrive** all need network access.
- **Several entry points for one action.** `/model` and `/settings` slash commands duplicate the model trigger and Settings; controls open from the header and from menus. Brief §20 forbids duplicate navigation. In Skein, every such action is reachable from one visible place plus the command palette.
- **Knowledge added without being shown.** A model preset's attached knowledge and a folder's files are merged into the request on the server ([`middleware.py` L2604-2644](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/backend/open_webui/utils/middleware.py#L2604-L2644)). The composer never shows them; only a per-message *"Searching Knowledge…"* status reveals them afterwards. Skein lists inherited context as *From persona* in the inspector.
- **Hover-only UI.** Hover-reveal ✕ on file chips (`hover-reveal`, [`MessageInput.svelte` L1974-1999](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/src/lib/components/chat/MessageInput.svelte#L1974-L1999)), hover chat previews (`ChatHoverPreview.svelte`), and tag lists hidden in tooltips on model rows.
- **Drag-resizable side panels** (`ResizableSidePanel`). They need pointer precision, which a touch device doesn't have.
- **Licence-trial banners and branding surfaces** ([`Navbar.svelte` L270-298](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/src/lib/components/chat/Navbar.svelte#L270-L298)). These have nothing to do with Skein.

## Potential Reusable Code

**None.** The UI code studied was all changed after 2025-04-18, so it falls under the Open WebUI License (branding condition, not on the allowlist). The small MIT-era remainder is Svelte or Python and doesn't contain these patterns.

What Skein re-implements from scratch is **behaviour**, not code:

- **Null-means-inherit merge (O1).** A few lines of Kotlin: `chat ?: persona ?: global`.
- **Settings search index (O12)** built from Android string resources.
- **Context usage label (O7)** from `TokenBudget` and `AssembledPrompt.estimatedTokens`.

Do not copy strings verbatim (for example *"Using Entire Document"*). Write Skein's own copy (see [P1 vocabulary](ANYTHINGLLM.md#p1--vocabulary)).

## Android/Fold Relevance

Size classes are estimates: outer screen ≈ 411 dp (Compact); inner screen ≈ 790–820 dp (Medium). Confirm on device.

| Open WebUI pattern | Outer screen (bottom / context sheet) | Inner screen (supporting pane) | Desktop-only (do not copy) |
| --- | --- | --- | --- |
| Controls: drawer ↔ side panel from one component (O2) | `ModalBottomSheet`, sections collapsible | Supporting pane, same sections | 1024 px breakpoint; drag-resize handle |
| "+" menu with drill-in (O4) | Attach sheet with an in-sheet sub-list and back | Same sheet, centred at M3 max width | Hover submenus |
| Active pills in composer (O3) | One summary chip, never a pill row | Summary chip; optional pill strip while composing | — |
| `/status`, `/compact` panels (O7) | Inspector **Context** section; slash commands stay available because Skein already has them | Same | — |
| Default/Custom parameters (O1) | Session → Advanced (collapsed) | Same | 34-parameter grid layout |
| Model selector in composer (O9) | Model sheet opened from the header subtitle (the composer is too narrow at 411 dp) | Header subtitle or model chip; sheet or menu | Hover detail, tag tooltips |
| Chat ⋮ menu (O11) | Header ⋮ and long-press in the drawer list | Same in the list pane | Hover chat preview |
| Settings modal with tab rail + search (O12) | Settings list → detail routes, with search at the top | `ListDetailPaneScaffold` | Modal-over-app settings |
| Folders with defaults (O10) | → personas | → personas | Folder tree with drag-to-move |

**Fold-specific note.** Open WebUI swaps drawer and panel **live** on a width change (the `matchMedia` change listener, [`ChatControls.svelte` L122-150](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/src/lib/components/chat/ChatControls.svelte#L122-L150)). Of the three references, it is the only one that decides layout from the viewport rather than the user agent. That is the behaviour brief §24 requires: close and reopen the Fold, and the controls reflow without losing state.

## Mac UX-Lab Relevance

- **The easiest live reference to run.** `pip install open-webui && open-webui serve` with Ollama on the MacBook gives a working local-model chat in minutes. Its layout follows the **viewport**, not the user agent, so resizing a desktop browser to ≈ 411 CSS px and ≈ 800 CSS px shows how the controls feel as a drawer versus a panel at the two Fold widths. That quickly tests the P5 "width budget" rule before writing Compose. Keep screenshots out of the repository.
- **Layer A fixtures** (brief §37) this study adds for the Session section:
  - all parameters Default (from persona);
  - one Custom override on this chat;
  - model Loaded vs Loading vs not loaded;
  - Notes-only outline;
  - Attach sheet with the Photo row hidden (no vision model) vs shown.
- **Layer C** (optional localhost prototype): do not embed or fork Open WebUI as the prototype shell, because of the branding condition.

## Recommended Action

**STUDY ONLY.** The licence rules out code reuse. The *patterns* (O1 inherit-by-default cascade, O2 one content in two containers, O3 only what's on, O4/O6 attach flow with a whole/relevant choice, O9 model presentation, O11 lifecycle menu) feed Skein's own designs: the context inspector in `ANYTHINGLLM.md`, and Wave 9 Models & Settings (brief §48).

## Expected Benefit

- A concrete answer to "configuration without clutter": three levels, inherit by default, overrides shown where they apply, plus one inspector and one settings search. Nothing is permanently in the chat except the context chip.
- A model-presentation rule that keeps local as the identity and leaves an attribute slot, not a UI, for other providers.
- A settled per-chat lifecycle menu for brief §§28–29.

## Expected Cost

- **Study cost: done.** No dependency, no code and no licence obligations.
- **Implementation cost** falls in Skein beads that already exist in the plan:
  - a `ParamRow` with inherit semantics (small);
  - persona-level default knowledge (**[integration]**: persona row or frontmatter);
  - a settings search index (small to medium);
  - the lifecycle menu (small; depends on the existing delete/rename work).

## Answers to the prompt's specific questions

**Local model presentation.** The name comes first. A size chip carries quant and GB behind it. A *Loaded* dot or *Unloads in 4 minutes* shows state, and *Eject* is available. Local gets no badge; only non-local connections are marked (O9). *For Skein:* `Qwen 2.5 3B · Local` in the header. Rows show name, size and *Loaded*. Filename, GGUF, quant, hash, backend and context length go in details (brief §34).

**Model switching.** A compact trigger in the composer's bottom-right opens a searchable list with pinned models and *Set as default* for new chats (O9). *For Skein:* switching models means a warm-swap of about 2–5 s (spec §6), so the model sheet must say so ("Switching takes a few seconds; this chat continues"). The trigger lives in the header subtitle rather than the composer, because the outer screen can't spare composer width.

**Knowledge.** Knowledge collections are managed under Workspace. They reach a chat through `#`, through a model preset's knowledge, or through a folder's knowledge. Only the first is visible in the composer (O5, O10, and the "Not worth adopting" list). *For Skein:* notes and files are already the knowledge. Attach through `＋` or `[[`. Persona defaults appear as *From persona*. Everything is visible in the inspector.

**File attachments.** Chips above the input show upload state. Tapping one opens a detail view with an **Entire Document / Focused Retrieval** switch (O6). The + menu offers upload, existing files, notes, knowledge and chats (O4). *For Skein:* the Attach sheet, plus automatic Whole / Relevant parts.

**Tools.** Tools sit behind an integrations menu. Once enabled they show as a count pill in the composer (O3). Tool servers have a *Tool Permissions* mode: *Full access* vs *Ask for approval* ([`InputMenu.svelte` L51-63](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/src/lib/components/chat/MessageInput/InputMenu.svelte#L51-L63)). *For Skein* (future skills): hidden until used; shown in the Activity section with approval state. Vault-writing skills (`docs/design/VAULT_TOOL_PRIMITIVES.md` `write_note` / `patch_note`) ask each time by default.

**Plugins.** Functions, Pipelines and Filters (Python) have per-user "Valves" for configuration. *For Skein:* rejected on security grounds (see "Not worth adopting").

**Settings.** There are user, admin, model-preset, folder and per-chat levels, with a settings search (O1, O12). *For Skein:* global, persona and chat, with inherit-by-default and settings search. There is no admin level.

**Conversation management.** The sidebar has folders, pinned chats, search, tags and an archive. Each chat's ⋮ menu covers rename, pin, clone, move, archive, delete and export (O11). *For Skein:* rename, pin, export, archive (if clean) and delete, plus search and tags (Skein already has tags). No folders.

**Advanced configuration.** Every parameter is *Default* until made *Custom* (O1). The model editor puts Advanced Params behind **Show/Hide** ([`ModelEditor.svelte` L1127-1140](https://github.com/open-webui/open-webui/blob/8bd8b4fac5e059578ac0c74b3c18d11139f88b7d/src/lib/components/workspace/Models/ModelEditor.svelte#L1127-L1140)). *For Skein:* the same approach, with fewer parameters and product names.

**Progressive disclosure.** Open WebUI uses four layers:
1. The composer shows only active pills.
2. The Controls panel.
3. Settings and the model editor.
4. Admin.

*For Skein:* three layers:
1. The chip and header.
2. The inspector.
3. Settings / Persona / Model details.

**Provider/model complexity.** Connection type (local / external / direct) is an attribute with an icon, and a filter appears only when there is more than one type (O9). *For Skein:* the same shape. Skein Core has **no `INTERNET` permission** (spec §2 principle 1), so today the attribute is always *Local*, and any external provider needs its own architecture decision (for example a companion-app bridge in the style of Skein Hub). Until then the UI shows no provider picker at all, since that would be a dead control.

**Primary lesson: how can Skein expose extensive configuration without permanently cluttering chat?**
1. **Inherit by default at every level**, so the default screen has nothing to show.
2. **Show only deviations** (overrides, non-default modes, attachments) in the one chip that is always there.
3. **Collect everything per-chat into one inspector** that is a sheet on the outer screen and a pane on the inner screen, and reflows live.
4. **Push defaults up** to Persona and Settings, and make them all findable through **settings search and the command palette**, never through extra buttons in the chat.

---

**What specific problem in Skein can this project help us solve?** Open WebUI shows how to layer a very large configuration surface (global → persona → chat, null meaning inherit, one controls panel that reflows between drawer and side pane, only active features shown, a searchable settings index). That lets Skein add personas, sampling, knowledge scope, model choice and conversation lifecycle without putting any permanent control in the chat beyond the single context chip. Skein takes the patterns only, because the post-2025 Open WebUI License's branding condition rules out code reuse.
