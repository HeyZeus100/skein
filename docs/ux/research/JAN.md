# Jan: UX and architecture reference study for Skein

**Bead:** `skein-xtov.4` (epic `skein-xtov`). **Authority:** `docs/research/SKEIN_UI_UX_OVERHAUL_PROMPT.md` §§1–4, 6 (6.1–6.7, *What Skein Should Extract*, *What Skein Should NOT Copy Blindly*), 26–27, 34, 37, 52–53.
**Companion:** `docs/ux/research/POCKETPAL.md`. Its activity block (A1) takes two of its three halves from this study: J5 (`PromptProgress`) and J6 (`ChainOfThought`, *Worked for …*).
**Reading guide:**
- The §52 sections come first.
- The seven §6 questions are answered in order under *Answers to the prompt's specific questions*.
- **Skein's proposed model identity scheme is in §6.1**, and the proposed Models hierarchy is in §6.2.

---

## Project

- **Repository:** <https://github.com/janhq/jan>.
- **Inspected:** default branch `main` at commit `1cd96da93443c89c9f069f13a647dd9b6f8181e8`. The commit is dated 2026-09-25 18:35:44 +0530 (`fix(agent): Robot Studio RPC gaps, the ADK rename, and the Windows runtime install (#9066)`). Inspected 2026-09-26.
- **Latest release:** `v0.8.4` (tag commit `5f30aee467f08941964a83f946e2663e7ae0e01f`, 2026-07-23). I fetched it to separate *shipped* from *unreleased* behaviour:

| Area | At `v0.8.4` | Changed on `main` since |
| --- | --- | --- |
| `PromptProgress.tsx` | shipped | +55 lines |
| `chain-of-thought.tsx` | shipped | +199 lines |
| `reasoning-timeline.tsx` | shipped | |
| `ModelSupportStatus.tsx` | shipped | |
| `thread-title-summarizer.ts` | shipped | |
| `ChainOfThoughtGroup.tsx` (the grouped timeline) | not present | new |
| `useCoTDuration.ts` (persisted durations) | not present | new |
| *Cowork* / agent surfaces | not present | new |

  Findings marked **(unreleased)** exist only on `main`.
- **Clone:** a shallow clone at `research/clones/jan/`. The path is git-ignored by `.gitignore:121` and was never committed.
- **Stack:**
  - Tauri 2 (Rust) desktop shell, with generated Android and iOS targets under `src-tauri/gen/`.
  - React, Vite, TanStack Router, Zustand, and shadcn/ui on Tailwind (`web-app/`).
  - Engine and extension packages in TypeScript (`core/`, `extensions/`).
  - The Vercel AI SDK for provider calls.

  None of this is adoptable by Skein (Kotlin, Compose, an isolated-process llama.cpp). This is a product and architecture reference only (§6, *Do not assume… Skein should adopt Jan's implementation stack*).
- **Permalink base:** `https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/`.

## License

- **At the pinned SHA: `Apache-2.0`.** The root [`LICENSE`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/LICENSE) is an 18-line Apache 2.0 notice. It reads `Copyright 2025 Menlo Research`, points to the Apache URL, and closes with *"Attribution is requested in user-facing documentation and materials, where appropriate."*
  - Because the file is an abbreviated notice rather than the full licence text, GitHub's detector reports the licence as `Other`.
  - Apache-2.0 is on Skein's `foss` permissive allowlist (spec §2.7).

**⚠ Licensing flags.** None affects this study, because **no Jan code is proposed for copying**. Each would matter if that ever changed.

1. **Licence history.**
   - The root licence was **AGPL-3.0** from 2023-10-25 (`ec612fc583`, "Change license to AGPL").
   - It became **Apache-2.0** on 2025-05-20 (`e8ca7f3c1b`, "chore: Jan's code is now under the Apache license (#5042)").
   - Code taken from any commit before `e8ca7f3c1b` is AGPL-3.0, which is not permitted in Skein.
2. **Stale per-package metadata at the pinned SHA.**
   - Eight `package.json` files still declare `"license": "AGPL-3.0"`: `core/`, `packages/adk/`, and `extensions/{download,assistant,rag,vector-db,mlx,llamacpp,conversational}-extension`.
   - Nine `Cargo.toml` files (`src-tauri/` and its plugins) declare `MIT`.
   - The root file governs, but the inconsistency means **any future adaptation needs written confirmation from Menlo Research** of the licence of the specific files. It should come only from post-`e8ca7f3c1b` commits.
3. **Attribution request.** Apache §4 already requires the NOTICE and licence for redistributed code. The extra *"attribution is requested in user-facing documentation"* is a non-binding request. Honour it only if Skein ever ships Jan-derived code, which it does not.

## Maintenance

**Very active, with a large community:**
- 44,656 stars and roughly 184 contributors (contributors API pagination).
- 543 open issues.
- More than 100 commits to `main` between 2026-08-26 and 2026-09-26. The API page size capped the count.
- The repository was created 2023-08-17.

**Release cadence:** roughly monthly through `v0.8.4`, which is 2026-07-23: `v0.8.0` 05-22, `v0.8.1` 05-29, `v0.8.2` 06-01, `v0.8.3` 06-24, `v0.8.4` 07-23.

**Since then `main` has accumulated two months of unreleased work.** That includes Cowork, the agent ADK, the grouped reasoning timeline and persisted reasoning durations. Expect churn: the surfaces this study cites for reasoning were substantially rewritten in the last release window.

**Open issues show reasoning display is hard to get right:**
- [#8530](https://github.com/janhq/jan/issues/8530): the reasoning block appears inconsistently.
- [#8025](https://github.com/janhq/jan/issues/8025): chain-of-thought is not separated for a custom Qwen 3.5 endpoint.
- [#8802](https://github.com/janhq/jan/issues/8802): users want to monitor thinking.
- [#8591](https://github.com/janhq/jan/issues/8591), closed: thinking text did not update during streaming, only a percentage did.

## Relevant Skein Problem

Jan sits where Skein is weakest today: making a local model feel like an ordinary assistant, and managing models without exposing infrastructure. It helps with six concrete problems.

1. **Model identity is a filename, so the header chip leaks it.** Today's chain:
   - Import records the SAF display name, which is the filename, as the manifest `name` ([`ModelManager.kt:340-368`](../../../core/inference/src/main/kotlin/app/skein/core/inference/models/ModelManager.kt), [`:587-596`](../../../core/inference/src/main/kotlin/app/skein/core/inference/models/ModelManager.kt)).
   - The model ID becomes `slugify(filename)-<12 hex of sha256>` ([`:623-631`](../../../core/inference/src/main/kotlin/app/skein/core/inference/models/ModelManager.kt), [`:666-668`](../../../core/inference/src/main/kotlin/app/skein/core/inference/models/ModelManager.kt)).
   - When a model is loaded, the command bar prints `engineStatus.modelId`, the full ID with its hash. When it is not loaded, it prints the first 20 characters of the name plus `· not loaded` ([`MainActivity.kt:430-434`](../../../app/src/main/kotlin/app/skein/MainActivity.kt), [`:1034`](../../../app/src/main/kotlin/app/skein/MainActivity.kt)).
   - The GGUF naming keys (`general.name`, `general.basename`, `general.size_label`, `general.finetune`) are read nowhere in the Skein tree. `ModelInspection` already carries `architecture`, `quantization` (from `general.file_type`), `contextLength`, `hasVision` and `chatTemplateOk` ([`core/ipc/.../Parcels.kt:488`](../../../core/ipc/src/main/kotlin/app/skein/ipc/Parcels.kt)).

   Jan's import path reads `general.name` first (J1), which is exactly the missing link.
2. **The Models experience exposes implementation and hides product** ([`feature/models/.../ModelsScreen.kt:76-131`](../../../feature/models/src/main/kotlin/app/skein/feature/models/ModelsScreen.kt)).
   - Each row is the filename-name, then `size · licenceSpdx`, which is literally `UNKNOWN` for every picked file.
   - Actions are *Set default* and a one-tap *Delete*, which has no confirmation and deletes gigabytes ([`MainActivity.kt:764-770`](../../../app/src/main/kotlin/app/skein/MainActivity.kt)).
   - The empty state instructs `use /import model`.
   - There is no active/loaded distinction beyond disabling Delete, no details view and no fit estimate.

   Jan's split into Hub, provider page, header pill, model details and advanced settings is the reference (§6.2).
3. **There is no progress during minute-long prefill.** Jan's `PromptProgress` shows *Loading model: 42%* and then *Reading: 63% · 1.2k / 1.9k tokens · ~14s left* (J5). Skein has the chunk data in `InferenceService` ([`:516-539`](../../../inference-service/src/main/kotlin/app/skein/inference/service/InferenceService.kt)) but only logs it.
4. **No after-the-fact account of a turn.** Jan's chain-of-thought folds to *Worked for 1m 12s* or *Thought for 12s*, with the duration persisted per message (J6). This is the timing half of prompt §27's `▸ Worked for 8.1s · 7 sources · 2 notes`.
5. **Future external compute and local tooling must not force an IA redesign.** Jan shows one information architecture carrying local engines and remote providers side by side (§6.3), plus a local OpenAI-compatible server (§6.4). Skein must document the seams without building either.
6. **The MacBook UX loop has no model-independent path for streaming UI.** Jan's e2e runs the real chat loop against a deterministic mock OpenAI server, and its unit tests run against a mocked `ServiceHub` (§6.6). Skein's `FakeInferenceEngine` exists but cannot simulate slow prefill or reasoning yet.

## Patterns Worth Adopting

All are reimplemented natively. No code.

| # | Pattern (Jan source) | What Skein takes |
| --- | --- | --- |
| J1 | **Name resolution on import: GGUF `general.name`, then the filename without `.gguf`, then the model ID.** Import reads `general.name` and normalises whitespace ([`llamacpp-extension/src/index.ts#L2053-L2057`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/extensions/llamacpp-extension/src/index.ts#L2053-L2057)). It falls back to the file basename without its extension ([`#L2112-L2115`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/extensions/llamacpp-extension/src/index.ts#L2112-L2115)) and persists the result as `name` ([`#L2121`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/extensions/llamacpp-extension/src/index.ts#L2121)). | The core of Skein's identity chain (§6.1). Skein reads **more** keys (`basename`, `size_label`, `finetune`) and keeps spaces; Jan turns them into hyphens (N-J1). |
| J2 | **Display name first, ID as fallback, user rename wins.** `getModelDisplayName = displayName \|\| id` ([`lib/utils.ts#L126-L128`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/lib/utils.ts#L126-L128)). An *Edit model* dialog sets the display name and capability flags ([`EditModel.tsx#L86-L90`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/containers/dialogs/EditModel.tsx#L86-L90)). Imported rows carry an *Imported* badge, and the technical ID is only a tooltip ([`$providerName.tsx#L1093-L1110`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/routes/settings/providers/$providerName.tsx#L1093-L1110)). | Rename in Model Details. The user's name is final. Technical ID lives in *Technical details*, never in the header. |
| J3 | **Fit expressed as a human sentence, marked as an estimate.** *"Should run comfortably on your device"* / *"Will run but leaves little memory headroom"* / *"Likely exceeds your available memory"* / *"Fit unknown"*, always suffixed *(estimated)* ([`ModelSupportStatus.tsx#L24-L31`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/containers/ModelSupportStatus.tsx#L24-L31), [`#L78`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/containers/ModelSupportStatus.tsx#L78)). On Hub cards: *Fits / May be slow / Won't fit*, plus *"Estimated from file size and your hardware…"* ([`ModelInfoHoverCard.tsx#L40-L66`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/containers/ModelInfoHoverCard.tsx#L40-L66), [`#L155-L156`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/containers/ModelInfoHoverCard.tsx#L155-L156)). | The copy pattern for the *Fits this device* row in Model Details. It depends on the recon's RAM model (F-1, PP-45/PP-46). Without that estimate the row is **hidden**, not shown as unknown. |
| J4 | **Quantization variants behind one family card.** The Hub shows one card per model with a default quant chosen by preference ([`lib/models.ts#L94-L105`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/lib/models.ts#L94-L105)), and *Show variants* expands the rest ([`routes/hub/index.tsx#L688-L700`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/routes/hub/index.tsx#L688-L700)). `extractQuantLabel` pulls `Q4_K_M` / `IQ3_XS` / `BF16` from an ID ([`lib/models.ts#L107-L112`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/lib/models.ts#L107-L112)). | Quantization is secondary information. It appears only as a disambiguator or in Details (§6.1). The quant regex shape is reused for the filename fallback. |
| J5 | **`PromptProgress`: a truthful dead-zone indicator.** During load it shows *Loading model: 42%*, naming the stage only when there is more than one (text model / encoder / draft). During prompt processing it shows *Reading: 63%* with a thin bar and *1.2k / 1.9k tokens · ~14s left*, where the ETA is extrapolated from the observed rate. Otherwise it shows *Working…*. The box keeps a minimum width so it does not jitter ([`PromptProgress.tsx#L49-L115`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/components/PromptProgress.tsx#L49-L115), [`#L117-L163`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/components/PromptProgress.tsx#L117-L163)). | The **Reading your message** step of POCKETPAL.md A1, phase 2. The copy (*Reading*), the ETA rule and the no-jitter rule transfer directly. The data needs one counts-only callback (§6.3 seams). |
| J6 | **`ChainOfThought`: live label, then *Worked for / Thought for {duration}*.** **(Partly unreleased.)** While streaming, the header shimmers *Thinking* or *Working…*, the latter when the current step is a tool. When settled, the collapsed pill reads *Thought for 12s* or, if tools ran, *Worked for 1m 12s*. Details: <ul><li>Duration **accumulates** across reasoning windows in one turn and is rounded up to at least 1 s ([`chain-of-thought.tsx#L121-L157`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/components/ai-elements/chain-of-thought.tsx#L121-L157)).</li><li>An unknown duration says *"a few seconds"*, never a fake number ([`#L206-L235`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/components/ai-elements/chain-of-thought.tsx#L206-L235); [`locales/en/chat.json#L27-L37`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/locales/en/chat.json#L27-L37)).</li><li>Expanded, it is a full-width card. Collapsed, it is a compact pill that hugs its label ([`#L164-L181`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/components/ai-elements/chain-of-thought.tsx#L164-L181)).</li><li>Steps carry `complete` ✓ / `active` ● / `pending` ○ status icons ([`#L357-L397`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/components/ai-elements/chain-of-thought.tsx#L357-L397)).</li><li>`forceOpen` pins the trace open while a tool awaits approval. `shouldCollapse` folds it when the answer begins. A manual toggle is preserved between input changes ([`#L105-L115`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/components/ai-elements/chain-of-thought.tsx#L105-L115)).</li></ul> | The collapsed summary and the step-status vocabulary of POCKETPAL.md A1. Jan's *worked* vs *thought* split maps directly: *Worked for* covers whole-turn pipeline time, and *Thought* covers the reasoning lane. |
| J7 | **Persist the measured duration per message.** **(Unreleased.)** `useCoTDuration` stores `messageId → ms` (bounded at 500 entries) and seeds it back on reload. Without it, a restored trace can only say "a while" ([`hooks/useCoTDuration.ts#L6-L48`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/hooks/useCoTDuration.ts#L6-L48); [`ChainOfThoughtGroup.tsx#L60-L66`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/containers/message/ChainOfThoughtGroup.tsx#L60-L66)). | Store the activity summary *with the message* in the vault (encrypted, exportable), not in a side store capped at 500 entries. The lesson is "measure live, persist once". |
| J8 | **While streaming, show only the current step; show the full rail when done.** **(Unreleased.)** The condensed view shows only the *frontier*: the current reasoning paragraph, or the current batch of tool calls. It never hides a tool awaiting approval. A *Show full timeline* toggle is offered while streaming. After completion, every step renders on one dotted rail ending in *Done* ([`ChainOfThoughtGroup.tsx#L79-L116`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/containers/message/ChainOfThoughtGroup.tsx#L79-L116), [`#L147-L205`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/containers/message/ChainOfThoughtGroup.tsx#L147-L205), [`#L268-L303`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/containers/message/ChainOfThoughtGroup.tsx#L268-L303)). Reasoning is cut into budget-bounded steps that "always advance" even without paragraph breaks ([`lib/reasoning.ts#L51-L65`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/lib/reasoning.ts#L51-L65); [`reasoning-timeline.tsx#L51-L83`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/components/ai-elements/reasoning-timeline.tsx#L51-L83)). | On the Fold's **outer** screen, the live activity block shows the completed steps compactly plus the one active step. The full rail is for the expanded or completed view and for the inner-screen inspector. Budget-bounded segmentation keeps lane B's window from becoming one ever-growing paragraph. |
| J9 | **Settings grouped by how essential they are.** First *core* (General, Appearance, Assistants, Attachments, Local API Server, HTTPS Proxy, Web Search, Shortcuts, Hardware, Privacy). Then *Integrations* with an **Experimental** badge (MCP Servers, Claude Code). Then *Model Providers*, split *Local* / *Remote* / *N hidden providers* ▸ ([`SettingsMenu.tsx#L170-L238`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/containers/SettingsMenu.tsx#L170-L238), [`#L240-L330`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/containers/SettingsMenu.tsx#L240-L330)). | Progressive disclosure in Settings. Future skills and tools sit in a labelled *Experimental* group under the essentials, and providers are split by location, with local first. |
| J10 | **Capability-gated features in one table.** `PlatformFeatures` lists which surfaces exist per platform. On Android and iOS it turns off local inference, the local API server, the Hub, MCP settings, projects and attachments. `PlatformGuard` renders children, a fallback or nothing ([`lib/platform/const.ts#L13-L75`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/lib/platform/const.ts#L13-L75), [`PlatformGuard.tsx#L13-L43`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/lib/platform/PlatformGuard.tsx#L13-L43)). | A single `SkeinFeatures` table: Hub installed? reasoning-capable model? external providers present? It gates every capability-dependent control. That is the mechanical form of "no visible production control may be nonfunctional" (§2). |
| J11 | **One menu primitive, two presentations.** `DropDrawer` is a dropdown on wide screens and a bottom drawer below 768 px ([`components/ui/dropdrawer.tsx#L28-L48`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/components/ui/dropdrawer.tsx#L28-L48); [`hooks/use-mobile.ts#L3`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/hooks/use-mobile.ts#L3)). The shadcn sidebar becomes a `Sheet` on narrow screens ([`components/ui/sidebar.tsx#L27-L34`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/components/ui/sidebar.tsx#L27-L34), [`#L254-L256`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/components/ui/sidebar.tsx#L254-L256)). | The Compose equivalent: `DropdownMenu` at Medium/Expanded width, `ModalBottomSheet` at Compact width, chosen per call from `WindowSizeClass`. It gives the model picker, per-chat ⋮ and tools menu one fold-aware implementation. |
| J12 | **Thread-list mechanics.** The list is sorted by recency. A spinner shows on a thread that is streaming in the background. The ⋯ menu offers *Rename · Add to project · Delete*. Delete confirms, toasts *"This thread has been permanently deleted."* and navigates home ([`ThreadList.tsx#L142-L266`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/containers/ThreadList.tsx#L142-L266); [`DeleteThreadDialog.tsx#L61-L74`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/containers/dialogs/DeleteThreadDialog.tsx#L61-L74)). | The **background-streaming spinner on a list row**. Skein generations can take minutes, and the user will switch chats, so the conversations list must show which chat is still working. Delete-then-navigate matches POCKETPAL W13. |
| J13 | **Model-generated title, cleaned and never blocking.** The title is generated after the first reply, with thinking disabled, only once the engine slots are idle, and aborted if the user sends again. `cleanTitle` strips `<think>`-style blocks, tags, quotes and symbols and caps the result at 10 words ([`thread-title-summarizer.ts#L6-L55`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/lib/thread-title-summarizer.ts#L6-L55), [`#L90-L95`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/lib/thread-title-summarizer.ts#L90-L95); [`routes/threads/$threadId.tsx#L660-L688`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/routes/threads/$threadId.tsx#L660-L688)). | Only as the *optional idle-time upgrade* in POCKETPAL.md A3. The deterministic first-sentence title comes first, because on the Fold an extra model call costs a full prefill. The `cleanTitle` rules are the spec for that upgrade. |
| J14 | **Tool approval with graded scope.** The options are *Deny · Allow Once · Allow in thread · Always allow {server} · Always allow {tool}*, with a security notice that conversation content could trick the assistant ([`locales/en/tools.json#L2-L14`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/locales/en/tools.json#L2-L14)). The request appears inside the reasoning trace, pinned open (J6 `forceOpen`). | For future skills and tools (§6.7): approval is an *activity step* in the turn, not a modal that hides the context. |
| J15 | **An empty home that talks about the user, not the engine.** It shows a centred *"How can I help you today?"*, a large composer and a model pill in the header. If no provider is usable, a Setup screen appears instead ([`routes/index.tsx#L36-L92`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/routes/index.tsx#L36-L92)). | The shape of §33's *What are you working on?* Skein adds *Recent* and knowledge actions, which Jan has no equivalent for. |
| J16 | **A deterministic mock backend for UI-level end-to-end tests.** `mock-openai.ts` is an OpenAI-compatible server that echoes the prompt as *"mock reply to: …"* and answers the title summarizer's non-streaming call ([`e2e/helpers/mock-openai.ts#L1-L40`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/e2e/helpers/mock-openai.ts#L1-L40)). llama.cpp is *"deliberately out of scope"* for the critical path ([`e2e/README.md#L54-L60`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/e2e/README.md#L54-L60)). Unit tests mock the whole `ServiceHub` ([`web-app/src/test/setup.ts#L8-L35`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/test/setup.ts#L8-L35)). | Skein's `FakeInferenceEngine` gains scenario scripts. Screenshot and UI tests never need a GGUF (§6.6). |

## Patterns Not Worth Adopting

| # | Jan pattern | Why Skein refuses it |
| --- | --- | --- |
| N-J1 | **Hyphenating `general.name`.** | `rawName.trim().replace(/\s+/g, '-')` ([`index.ts#L2054-L2056`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/extensions/llamacpp-extension/src/index.ts#L2054-L2056)) turns `Qwen2.5 3B Instruct` into `Qwen2.5-3B-Instruct`. That re-technicalises a human string, because the name doubles as an ID-like value in Jan's config. Skein keeps display text and identifiers separate, so it keeps the spaces. |
| N-J2 | **An engine logo as the "local" signal.** | The model pill shows a provider avatar, which for local models is the llama.cpp logo ([`DropdownModelProvider.tsx#L470-L517`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/containers/DropdownModelProvider.tsx#L470-L517)). That is implementation language rendered as an icon. Skein writes the word **Local** (§26, §34). The runtime name (llama.cpp, Vulkan or CPU) belongs in Technical details. |
| N-J3 | **Engine settings on the chat header.** | For llama.cpp models the pill carries a `ModelSetting` gear straight into sampling and engine parameters, next to the model name in every chat (same lines). Skein puts generation settings behind Model Details → *Advanced*, with *Automatic* as the default (recon PP-43, recon §7.3). |
| N-J4 | **Hover-only affordances and tooltip-only disclosure.** | Thread ⋯ is `showOnHover`. The technical model ID is only a `title` tooltip. Keyboard-shortcut badges sit in the nav ([`ThreadList.tsx#L180-L186`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/containers/ThreadList.tsx#L180-L186); [`NavMain.tsx#L74-L125`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/components/left-sidebar/NavMain.tsx#L74-L125)). A touch device has no hover. Every disclosure in Skein is a tap target. Shortcut hints appear only with a hardware keyboard attached on the unfolded Fold (§41). |
| N-J5 | **Percentage-based reading width.** | The column is `w-full md:w-4/5 xl:w-4/6` ([`$threadId.tsx#L1731-L1734`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/routes/threads/$threadId.tsx#L1731-L1734)). On the Fold's nearly square inner display a percentage produces a line length that depends on posture. Skein caps the message column in dp (≈640–720 dp) and centres it. |
| N-J6 | **Delete dialog focuses *Delete*, and Enter confirms.** | `onOpenAutoFocus` moves focus to the delete button, and `handleKeyDown` deletes on Enter ([`DeleteThreadDialog.tsx#L76-L99`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/containers/dialogs/DeleteThreadDialog.tsx#L76-L99)). This is efficient on desktop but contrary to §29's "not where accidental taps are likely". It is dangerous with a Fold hardware keyboard, where Enter also sends messages. Skein's default focus is *Cancel*. |
| N-J7 | **"Thread" vocabulary and a flat list.** | Jan uses *New Thread* / *Delete Thread* ([`locales/en/common.json#L185`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/locales/en/common.json#L185), [`#L221`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/locales/en/common.json#L221)) and one recency list with no date groups and no pinning. Skein says **chat** (§28–29) and groups by date with pins (POCKETPAL W10). |
| N-J8 | **Local API server bound to `0.0.0.0` with an optional key.** | The host toggle exposes the server to the LAN. The code that would require an API key is commented out ([`local-api-server.tsx#L119-L126`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/routes/settings/local-api-server.tsx#L119-L126); defaults at [`useLocalApiServer.ts#L60-L70`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/hooks/useLocalApiServer.ts#L60-L70)). Structurally impossible in Skein Core anyway (§6.4). |
| N-J9 | **Opt-in analytics and a consent panel.** | The `ANALYTICS` feature flag and *Help us improve* ([`routes/settings/privacy.tsx`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/routes/settings/privacy.tsx)). Skein has no telemetry, not even opt-in (spec §2.3). Jan's *privacy promises* list is a good copy format, but Skein's claims are stronger and verifiable ("no network permission"). |
| N-J10 | **A 3,003-line composer.** | `containers/ChatInput.tsx` hosts attachments, the file picker, assistant switcher, tools, token counter, agent mode and more. It is the end state of adding "one more control" to the composer. Skein's composer stays two rows, and capability-specific actions go in the `＋` sheet or the command palette. |
| N-J11 | **Reachable-by-URL but unlinked routes.** | `routes/settings/extensions.tsx` exists and is not in `SettingsMenu` (§6.7). That is acceptable on desktop with URLs. In Skein, a route that is not linked should not exist in production builds, which avoids "exposing unfinished features" (§2.13). |
| N-J12 | **Jan's mobile strategy.** | On Android and iOS Jan disables local inference, the Hub, the API server, MCP, projects and attachments (J10 table). The mobile app is a remote-provider client. This is useful evidence (§6.5) but the opposite of Skein, where on-device *is* the product. |
| N-J13 | **Hard-coded English in progress copy.** | `PromptProgress` builds *"Loading model…"* and *"Reading: …"* with literals, not i18n keys ([`PromptProgress.tsx#L78-L84`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/components/PromptProgress.tsx#L78-L84)). Minor, but Skein's activity strings go through resources from day one. |

## Potential Reusable Code

**None is recommended.**
- The implementation is TypeScript, React, Tauri and Rust, which is non-transferable to Kotlin and Compose.
- The licence metadata is inconsistent (see *License*).

The transferable items are **algorithms and copy rules**. Reimplement them from these descriptions, not by porting source:

| Item | Jan source | Skein use |
| --- | --- | --- |
| Name chain `general.name → basename(file) − .gguf → id` | `llamacpp-extension/src/index.ts#L2053-L2057`, `#L2112-L2115` | extended in §6.1 |
| Quant-token regex `(IQ\d+(_[A-Z0-9]+)+ \| Q\d+(_[A-Z0-9]+)* \| BF16 \| F16 \| F32)` at the end of a stem | `lib/models.ts#L107-L112` | filename fallback, disambiguator |
| ETA extrapolation `time_ms / processed × remaining`, with `~Ns left` / `~Nm Ns left` and a `1.2k` token format | `PromptProgress.tsx#L130-L163` | A1 phase 2 |
| Duration accumulation across windows, `max(1 s, ceil)`, "a few seconds" when unknown | `chain-of-thought.tsx#L121-L157`, `#L206-L235` | A1 summary |
| `cleanTitle` rules (strip reasoning tags and leftover markup, collapse whitespace, strip quotes, keep letters, digits and spaces, ≤ 10 words, reject under 2 characters) | `thread-title-summarizer.ts#L21-L55` | optional title upgrade |
| Budget-bounded reasoning step segmentation that "always advances" | `lib/reasoning.ts#L51-L65` | lane B window |

## Android/Fold Relevance

**Unfolded inner screen (Medium or Expanded width, landscape or portrait): high relevance for layout *structure*.** Jan's desktop shell is the clearest reference for `Navigation | Workspace | Optional Inspector` (§21):
- a floating left sidebar of 15 rem, resizable from 14 to 20 rem and collapsible ([`sidebar.tsx#L27-L34`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/components/ui/sidebar.tsx#L27-L34));
- the conversations list as the pane's body;
- a centred reading column;
- two-pane Settings (menu plus content).

The §6.5 answer maps each piece.

**Folded outer screen (Compact width): Jan is a negative reference.**
- Jan's own Android and iOS builds do not shrink the desktop onto a phone. They switch the sidebar to a sheet below 768 px (J11) and **turn off most of the workstation** (J10, N-J12).
- The hover affordances, shortcut badges, text-sm density and popover model menu (N-J4) must not appear on the outer screen.

**Live fold transitions:** Jan's breakpoint hook listens to `matchMedia` changes ([`use-mobile.ts`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/hooks/use-mobile.ts)), so it reflows on resize. This is the right principle, and it contrasts with PocketPal's value frozen at launch. Skein's equivalent is `currentWindowAdaptiveInfo()` / `WindowSizeClass`, recomputed on every configuration.

## Mac UX-Lab Relevance

**High, as a workflow reference (§6.6):**
- a DI seam (`ServiceHub`) with web, desktop and mobile implementations chosen once at start-up;
- a browser-only dev mode (`yarn dev:web` runs Vite with the default services);
- a fully mocked service hub for component tests;
- a deterministic mock inference backend for end-to-end tests of the real streaming loop.

**What does not transfer:** Jan's web mode is a *product* surface, not a lab. Skein must not grow a web product (§37 Layer C). The Kotlin-native equivalents are in §6.6.

## Recommended Action

**ADOPT, targeted:** J1–J8 (model identity chain, display-name-first and rename, estimated fit copy, variants as secondary, prompt progress, worked-for/thought-for summaries with persisted durations, current-step-only live view) and J16 (model-independent UI testing).

**STUDY ONLY:** J9–J15. These are settings grouping, capability gating, fold-aware menus, list mechanics, title upgrade, tool approval and the empty home. They are useful when their Skein bead arrives. The provider abstraction (§6.3), the local API server (§6.4) and extensions/MCP (§6.7) are **documented, not built**.

**REJECT:** N-J1–N-J13, and any Jan code.

## Expected Benefit

- **The header stops leaking filenames.** It reads `Qwen 2.5 3B · Local`, and the technical identity stays one tap away in Details (§26, §34).
- **The Models screen becomes a product.** It shows what is on this device, what is active, how to add a model, and details on demand, instead of a filename list with `UNKNOWN` licences and one-tap delete.
- **Slow prefill gets a truthful *Reading…* step with an ETA,** and every turn gets a one-line *Worked for …* account. Together with POCKETPAL.md A1 this closes the prompt's §27 gap.
- **Future external compute, skills and tools fit the IA without a redesign,** because their seams are named now and their UI stays hidden until real (§2, §6.3, §6.7).
- **UI iteration no longer needs a model.** Every activity and reasoning state becomes a fixture-driven preview and screenshot baseline on the MacBook.

## Expected Cost

| Item | Cost | Owner / note |
| --- | --- | --- |
| Friendly-name resolver (pure Kotlin, unit-tested, feature-side) | S (≈1 d) | UX workstream |
| Read `general.basename` / `name` / `size_label` / `finetune` / `version` as bounded, sanitised strings in `ModelInspection` | S | **Inference/IPC owners**. A separate bead: an additive parcel field. The UX layer only consumes it. |
| Models list + Model Details + rename + delete confirmation | M (≈3–4 d) | UX workstream. The lifecycle contract for model delete sits with the §32 audit. |
| *Fits this device* row | S in UI | Blocked on the recon's F-1 RAM model (PP-45/PP-46). Hidden until then. |
| Prefill-progress callback (`oneway`, counts only) | S | **Inference owners** (POCKETPAL.md A1 phase 2) |
| `FakeInferenceEngine` scenarios + previews + Roborazzi | M | UX workstream (`testing/` + `src/debug/`) |
| Provider / API server / extension seams | none now | documentation only |

---

## Answers to the prompt's specific questions

### 6.1 Make local AI feel normal

**How Jan does it, in order of effect:**
1. **The model has a name.** It resolves `general.name`, then the file stem, then the ID (J1). The user can rename it (J2). `getModelDisplayName` is used everywhere a model appears: pill, lists, search.
2. **The technical identity is present but demoted.** The ID is a tooltip. Quant variants hide behind *Show variants* (J4). The engine is an avatar (N-J2), which Skein improves on.
3. **Home talks about the user's task** (*"How can I help you today?"*, J15). Starting a model is automatic on send, and progress is phrased as *Loading model… / Reading…* (J5).
4. **Capability and fitness are expressed as estimates in plain language** (J3), not as RAM figures or quant math.

**Skein's proposed model identity scheme.**

**Display form:**

```text
Primary   Qwen 2.5 3B            ← friendly name (header title line of the subtitle, pickers, lists)
Secondary Local                   ← where it runs; status appended only when not ready: "Local · Starting…"
Tag       Abliterated · Vision    ← optional chips, from finetune / capabilities (Details, picker row)
Details   Q3_K_M · qwen2 · 16K of 32K context · 1.6 GB · SHA-256 2c5f…  ← Technical details only
```

**Resolution chain for *Primary*.** The first non-blank result wins:

| Priority | Source | Notes |
| --- | --- | --- |
| 1 | **User rename** (registry field, J2) | Never overwritten by re-import or metadata. |
| 2 | **Bundled manifest `name`** for the two first-run defaults (spec OQ-3) | Curated, like PocketPal's `display_name` (`"Gemma 3 1B"`). This guarantees first-run quality without metadata. |
| 3 | **GGUF-derived**: `pretty(general.basename)` + `' '` + `general.size_label`. Falls back to `normalise(general.name)` when `basename` is absent. | See the mapping table below. |
| 4 | **Filename-derived**: stem without `.gguf`, then without the shard suffix `-00001-of-0000N`, then without the quant token (J4 regex), then without `GGUF` and generic finetune tokens. Separators become spaces, then `pretty()`. | Must not reproduce PocketPal's trailing-`.` bug (POCKETPAL N1). |
| 5 | `Local model` | Paired with the size in Secondary. |

**`pretty()` rules:**
- Insert a space between a family word and its version when they are glued together: `Qwen2.5 → Qwen 2.5`, `Phi3 → Phi 3`, `gemma-3 → Gemma 3`.
- Apply canonical casing for an allow-list of families (Qwen, Gemma, Llama, Mistral, Phi, SmolLM, Granite, DeepSeek). Leave everything else as written.
- Uppercase the size label (`3b → 3B`, `8x7b → 8x7B`).
- Never add words that are not in the source.

**GGUF metadata → presentation mapping:**

| Source key | Example value | Used for | Tier |
| --- | --- | --- | --- |
| `general.basename` | `Qwen2.5` | family part of Primary (`Qwen 2.5`) | Primary |
| `general.size_label` | `3B` | size part of Primary | Primary |
| `general.name` | `Qwen2.5 3B Instruct Abliterated` | Primary fallback when `basename` is absent; shown as *Name in file* | Primary (fallback) / Details |
| `general.finetune` | `Instruct-abliterated` | tag chips. Generic tokens (`Instruct`, `it`, `chat`, `base`) are dropped; others title-cased (`Abliterated`, `Coder`). | Tag |
| `general.version` | `v2` | appended to Primary only if not already in `basename` | Primary |
| `general.file_type` → `ModelInspection.quantization` (exists) | `Q3_K_M` | Details as *Q3_K_M (3-bit)*; short form `Q3` is a disambiguator only | Details / disambiguator |
| `general.architecture` → `ModelInspection.architecture` (exists) | `qwen2` | Details | Details |
| `<arch>.context_length` → `ModelInspection.contextLength` (exists) | `32768` | Details as *Skein uses 16K · trained for 32K*. The 16K v1 cap is from spec §6. | Details |
| `ModelInspection.hasVision` (exists) | `true` | *Vision* tag | Tag |
| `ModelInspection.hasChatTemplate` / `chatTemplateOk` (exist) | `true` | Details as *Chat format: built in* / *Skein default* | Details |
| manifest `sha256`, verification outcome | `2c5f9d1e…` | Details (monospace), plus *Verified* state | Details |
| picked file name | `qwen2.5-3b-instruct-abliterated-q3_k_m.gguf` | Details (monospace, middle ellipsis) | Details |
| `sizeBytes` | `1.6 GB` | picker row secondary, Details | Secondary (picker) / Details |
| manifest licence SPDX | `UNKNOWN` | Details only, as *Licence not stated in the file*. Never a list-row headline as today. | Details |
| model `id` | `qwen2.5-3b-instruct-abliterated-q3-k-m-2c5f9d1e0a3b` | Details → *Technical ID* (copyable). **Never in the header.** | Details |
| execution location | local (all v1 models) | Secondary label `Local` | Secondary |

**Disambiguation.** When two installed models resolve to the same Primary label, append the short quant (`Qwen 2.5 3B · Q3` vs `· Q4`). If they still collide, append the first tag, then the import date. Only the colliding rows change.

**Worked example.** This example is illustrative. The exact keys in the owner's file were not inspected (see *What could not be determined* below).

| Source | Value |
| --- | --- |
| Picked file | `qwen2.5-3b-instruct-abliterated-q3_k_m.gguf` |
| `general.basename` | `Qwen2.5` |
| `general.size_label` | `3B` |
| `general.finetune` | `Instruct-abliterated` |
| `general.file_type` | `Q3_K_M` |

| Surface | Today | Proposed |
| --- | --- | --- |
| Header chip | `qwen2.5-3b-instruct-abliterated-q3-k-m-2c5f9d1e0a3b` (loaded) | `Qwen 2.5 3B · Local` |
| Models row | `qwen2.5-3b-instruct-abliterated-q3_k_m.gguf` / `1.6 GB · UNKNOWN` | `Qwen 2.5 3B` / `Local · 1.6 GB · Abliterated` |
| Details | none | everything else in the mapping table |

If the file carried no `general.*` naming keys, priority 4 yields `Qwen 2.5 3B` as well. It gets there by stripping `q3_k_m`, dropping `instruct`, moving `abliterated` to a tag and prettifying.

**Trust and safety rules.** GGUF strings are attacker-controlled input:
- **Where it is read.** Read the new keys **inside `:inference`**, as `ModelInspection` does today, because full GGUF parsing stays in the isolated process (spec §2.6; recon PP-16). Do not widen the app-side `GgufPreCheck`: its header states it extracts exactly two allowlisted scalars ([`GgufPreCheck.kt:1-35`](../../../core/inference/src/main/kotlin/app/skein/core/inference/models/GgufPreCheck.kt)).
- **Sanitisation.** Bound each string (≤ 128 characters stored, ≤ 40 displayed). Strip C0/C1 control characters, bidi overrides (U+202A–202E, U+2066–2069) and zero-width characters. Render only as plain text, never Markdown.
- **Precedent.** The Hub hand-off already treats `displayName` as an untrusted, sanitised hint ([`core/model/.../ModelOrigin.kt:43-67`](../../../core/model/src/main/kotlin/app/skein/core/model/ModelOrigin.kt)).
- **Trust is never inferred from the name.** A file calling itself `Gemma 4 (Verified)` gets no trust from it. Verification state is a separate, Core-derived row in Details.

**Where the rest of "normal" comes from:**
- The model starts on send with *Starting model* in the activity block. There is no mandatory *Load* button before chatting, unlike PocketPal's *Activate Model To Get Started*.
- The first-run picker speaks in outcomes (*Balanced · Private · No refusals*) with licences one tap away.
- Settings never say `n_ctx`, `mmap` or `GGUF` outside *Technical details* and *Advanced*.

### 6.2 Model management

**Jan's hierarchy, as shipped:**

| Layer | Jan surface | What lives there |
| --- | --- | --- |
| **Available** | Hub (`routes/hub/index.tsx`, `routes/hub/$modelId.tsx`) | Catalog plus Hugging Face search. One card per family, with a default variant and *Show variants* (J4). Fit estimates (J3). Download or *Use*. |
| **Downloaded / imported** | Settings → Model Providers → *Llama.cpp* page ([`$providerName.tsx#L1088-L1175`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/routes/settings/providers/$providerName.tsx#L1088-L1175)) | Installed models by display name, with capability icons and an *Imported* badge. Row actions: Edit (rename and capabilities), Settings, Favourite, Delete (dialog), Start/Stop. Embedding models are listed separately. *Import* sits on this page. |
| **Active** | The header model pill (`DropdownModelProvider.tsx`) | Per-thread selection, remembered last-used, favourites first, grouped by provider, fuzzy search. Embedding models and remote providers without keys are hidden ([`#L262-L304`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/containers/DropdownModelProvider.tsx#L262-L304), [`#L563-L660`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/containers/DropdownModelProvider.tsx#L563-L660)). |
| **Provider configuration** | Settings → Model Providers (*Local* / *Remote* / *hidden* ▸) (J9) | Base URL, API keys with fallbacks, custom headers, API type. |
| **Advanced model settings** | `ModelSetting` sheet (gear), Hardware page | Engine and sampling parameters, GPU selection. |

**Is Skein's current Models experience too complex? It is complex in the wrong places.**

It exposes implementation:
- the filename as the name;
- an SPDX licence as the second line;
- a slash command as the empty-state instruction;
- a hashed ID in the header.

It *under*-exposes the product:
- no loaded/active state;
- no details view;
- no fit estimate;
- no rename;
- no confirmation before deleting a multi-GB file.

**Proposed hierarchy.** This is §6.2's *On Device / Available / External Providers* made concrete for v1:

```text
Models                                                     (Settings ▸ Models, or model sheet ▸ Manage)
  On this device
  ┌──────────────────────────────────────────────────┐
  │ Qwen 2.5 3B                              ● Active │
  │ Local · 1.6 GB · Abliterated                      │
  └──────────────────────────────────── Details ›  ───┘
    Gemma 4 E4B                     Local · 3.1 GB  ›
  Add a model
    Choose a file…        import from this device (always available, works offline)
    Get models            only rendered when Skein Hub is installed (Core never has INTERNET)
  (External providers — not rendered in v1; reserved in the IA only, see §6.3)

Model details — Qwen 2.5 3B
  Name            Qwen 2.5 3B                               Rename
  Runs            On this device
  Status          Active · Ready                            (or: Loads when you send)
  Fits            Comfortably (estimated)                   hidden until the recon's F-1 RAM model exists
  Size            1.6 GB
  Quality         Q3_K_M (3-bit)
  Context         Skein uses 16K · trained for 32K
  Licence         Not stated in the file
  ▸ Technical details    file name · SHA-256 · verification · architecture · chat format · technical ID · imported on
  ▸ Advanced             generation settings (Automatic by default — recon §7.3)
  Delete model…          → "Delete Qwen 2.5 3B? This frees 1.6 GB. Chats keep their text."  [Cancel] [Delete]
```

**Rules:**
- **"Active" means the default model**, the one a new chat uses. Per-chat and per-persona model choice lives in the chat's model sheet (POCKETPAL W16), not here.
- **The default model is chosen in one place.** Today *Set default* lives in `ModelsScreen`. Tapping a row's *Make default* inside Details replaces it, so the list row itself carries no competing actions.
- **Delete lives only in Details, confirmed.** The dialog names the model and the space freed. It is disabled while generating, and it is **never** a list-row button (N-J6, §29 spirit). The *"Chats keep their text"* copy must be verified by the §32 lifecycle audit before shipping. `messages.model_id` is a plain text column with no foreign key in spec §5, so deleting a model leaves message text intact. This is inferred from the schema, not tested.
- **The picker (quick switch) and the Models page (management) never duplicate each other's controls.** The picker has only *select* and *Manage models ›*.

### 6.3 Local + selective external compute (architecture only; not to be built now)

**Jan's abstraction:**
- **Local engines** are extension classes deriving from `AIEngine`. They implement `list / get / load / unload / chat / import / abortImport / delete / update / getLoadedModels / isToolSupported` ([`core/src/browser/extensions/engines/AIEngine.ts#L331-L415`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/core/src/browser/extensions/engines/AIEngine.ts#L331-L415)). `OAIEngine` and `LocalOAIEngine` specialise it for OpenAI-shaped wire formats.
- **Remote providers** are *configuration* (`ProviderObject`), not code: `provider`, `base_url`, `api_key` plus `api_key_fallbacks`, `api_type: 'openai' | 'anthropic'`, `custom_header`, `settings[]` and `models[]` ([`types/modelProviders.d.ts#L39-L80`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/types/modelProviders.d.ts#L39-L80)).
- **Locality is structural.** `isLocalProvider(p)` is simply "the engine for `p` has `load`" ([`lib/utils.ts#L232-L235`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/lib/utils.ts#L232-L235)).
- **The UI is uniform over both.** Every model is addressed as `provider:modelId`. The picker groups by provider, and Settings splits *Local* / *Remote* providers (J9). Platform flags can remove whole provider classes (`DEFAULT_PROVIDERS`, J10).

**Implications for Skein's IA.** These keep external compute *addable later* without a redesign:
1. **Identity carries a location dimension distinct from provenance.**
   - Skein's `ModelOrigin` (`BUNDLED`, `PICKED`, `HUB`; [`ModelOrigin.kt:35-41`](../../../core/model/src/main/kotlin/app/skein/core/model/ModelOrigin.kt)) answers *where did this file come from*.
   - A separate presentation value answers *where does it run*: `Local` today, and eventually `External(providerName)`.
   - The Secondary label in §6.1 reads from this value, so a future external model renders as `Claude … · External (Anthropic)` with no header change.
2. **The model picker and Models page are organised by location**, with *On this device* always first and pre-selected. §6.2's layout already reserves the slot without rendering it.
3. **Records keep the model ID per message** (spec §5 `messages.model_id`). The activity block reserves a *Sending to ⟨provider⟩* step, so any off-device turn is visibly and permanently marked on the turn itself. This is how Skein would keep "local is the default identity" honest.
4. **The process boundary is the real constraint.**
   - Skein Core has no `INTERNET` permission, ever (spec §2.1).
   - The only APK allowed network access is Skein Hub, and Hub never reads the vault (spec §2.11).
   - External compute therefore cannot be a Core provider like Jan's. It would need its own architectural decision, for example a separate app and a narrow, user-approved hand-off of *specific* context.

   The UI conclusion is the same either way: the location dimension, the grouping and the per-turn marker are enough. Nothing else should be scaffolded now (§6.3: "Do not implement external compute as part of this UI wave").

### 6.4 Local API / workstation concept

**What Jan ships:**
- An OpenAI-compatible HTTP server at `127.0.0.1:1337` with prefix `/v1` ([`useLocalApiServer.ts#L60-L70`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/hooks/useLocalApiServer.ts#L60-L70)).
- Settings for host (`127.0.0.1` or `0.0.0.0`), port, prefix, an optional API key, trusted hosts, proxy timeout and, under *Advanced*, CORS and verbose logs. Run-on-startup and a logs window round it out ([`routes/settings/local-api-server.tsx#L285-L395`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/routes/settings/local-api-server.tsx#L285-L395)).
- The README positions it as *"Local server at `localhost:1337` for other applications"*.
- It is **disabled on Jan's own mobile builds** (J10).

**Relevance to future Skein tooling (documented, not recommended now):**

| Future need | Would a Jan-style server help? | Skein-compatible shape |
| --- | --- | --- |
| Skein desktop / MacBook tooling | Yes, on the Mac itself: a desktop Skein would own its own server. | Out of scope. It would be a separate product decision (§38 treats Compose Desktop only as a preview target). |
| CLI clients, local agents, repository tools on the phone | Not in Core. On Android **any** socket, loopback included, requires `android.permission.INTERNET`, which spec §2.1 forbids in Core. | A **signature-permission bound service** (AIDL) exposing a narrow, audited API to Skein-signed companions, or the existing `DocumentsProvider` for read access to content (spec §2.9). Both keep the no-network guarantee. |
| Trusted third-party local apps | A server would also expose the vault's intelligence to *any* local process. That undermines spec §2.9's "exposed only via DocumentsProvider". | Explicit per-request user approval, in the J14 style, if ever. Not a standing open port. |
| Local orchestration / workflows | A server is one option. An in-app command and skill runtime is the Skein-native one (§3 Level 2). | Command palette plus skills (future). |

**UI consequence now:** reserve nothing. No *Local API server* entry appears in Skein's Settings until one exists (§2, "no dead controls"). Jan's *core* settings list shows how easily a workstation feature becomes a permanent top-level setting.

### 6.5 Desktop UX, and what maps to the unfolded Fold

**Jan's desktop shell:**
- **Sidebar.** A floating, collapsible left sidebar (15 rem default, 14–20 rem resizable, toggled by ⌘B). It holds: *New chat* (⌘N), *New agent chat*, *New project*, *Search* (dialog), *Hub*, *Settings*. Below come *Projects*, then *Chats* (J12) ([`left-sidebar/index.tsx#L21-L55`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/components/left-sidebar/index.tsx#L21-L55), [`NavMain.tsx#L74-L140`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/components/left-sidebar/NavMain.tsx#L74-L140), [`NavChats.tsx#L19-L70`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/components/left-sidebar/NavChats.tsx#L19-L70)).
- **Chat header.** Only the model pill; the conversation title is not repeated, because the sidebar shows the selection ([`$threadId.tsx#L1716-L1720`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/routes/threads/$threadId.tsx#L1716-L1720)).
- **Reading column** at 100 %, 80 % or 66 % of the width (N-J5).
- **Empty home** (J15).
- **Settings** as a two-pane view: a 14.5 rem menu plus content (J9).
- **Density:** `text-sm` (14 px) throughout, with hover-revealed row actions.

**Mapping to Skein:**

| Jan pattern | Unfolded inner screen (Medium/Expanded) | Folded outer screen (Compact) |
| --- | --- | --- |
| Persistent left sidebar with conversations | **Maps.** Persistent `Conversations` pane (≈ 240–300 dp), collapsible to reclaim width. Selected row highlighted, streaming spinner per row (J12). | **Must not shrink.** It becomes the modal drawer (POCKETPAL W10/A3). No icon rail on the outer screen (§22). |
| Nav actions (New chat, Search, Hub, Settings) above the list | **Maps**, as the pane header: *New chat*, *Search*. *Models* and *Settings* go to the pane footer. | Drawer header and footer (POCKETPAL A3). |
| Header = model pill only, title in sidebar | **Maps** when the pane is visible: the header shows `Qwen 2.5 3B · Local` plus ⋮, because the title is visible in the pane. | **Does not map.** The outer screen needs *title over model* (POCKETPAL W9, §22). |
| Centred reading column | **Maps**, but capped in **dp** (≈ 640–720 dp), not percentage (N-J5). The inspector (§35) takes the right-hand space only when opened. | Full width minus a 16 dp gutter. |
| Two-pane Settings | **Maps** to a list-detail layout. | Single-pane list → detail route. |
| Model popover with search, favourites, provider groups | **Maps** as an anchored `DropdownMenu`, but without provider groups in v1 (local only). | Bottom sheet (J11). With two local models, **no search field and no favourites**, which would be desktop density for a list of two. |
| Hover-revealed row actions, tooltips, shortcut badges | Show ⋮ on the selected or focused row. Shortcut hints only when a hardware keyboard is attached (§41). | **Never** on the outer screen (N-J4). |
| `text-sm` density, 14 px body | Body text ≥ 16 sp for messages even when unfolded. Denser lists are acceptable in the pane. | Never. The Level 1 surface keeps phone typography (§22 "no tiny metadata"). |
| Resizable sidebar | Optional later. A fixed width is simpler and adequate at first. | n/a |
| Projects above Chats | Maps to the future *Workspace / Projects* grouping (§20), **not in v1**. | n/a |

**Empty states and visual hierarchy:** Jan's home has one sentence, one composer and one pill. Its hierarchy is carried by size and whitespace, not chrome. That is the tone §33 asks for, and Skein should match it on both screens.

### 6.6 MacBook UX lab: dev workflow ideas

**What Jan does:**
- **Platform selection happens once.** `ServiceHub` is an interface over roughly 20 services (`threads`, `models`, `providers`, `mcp`, …). `PlatformServiceHub.initialize()` dynamic-imports the Tauri, Mobile or Default implementations at start-up ([`services/index.ts#L56-L81`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/services/index.ts#L56-L81), [`#L108-L200`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/services/index.ts#L108-L200)).
- **Browser-only dev mode.** `yarn dev:web` runs the UI in a browser against the Default services, with no Rust and no model (root `package.json`, `dev:web`).
- **Component tests.** The whole hub is mocked with `vi.fn()` stubs (J16).
- **End-to-end tests.** WebdriverIO drives a real desktop build against a **deterministic mock OpenAI server**. The data folder is isolated per run through environment variables, and llama.cpp is excluded from the critical path on purpose (J16).
- **Computer-use QA.** `autoqa/` runs screen-recorded QA against an installed app.

**Skein translation.** It stays inside Compose and Android: no web product, no second architecture.

1. **Scenario-scripted fakes, not a mock server.**
   - Skein already injects `FakeInferenceEngine` at the composition root in tests (`app/src/test/.../AskPathComposeTest.kt`, with `script` and `tokenDelay`; [`testing/.../FakeInferenceEngine.kt:67-69`](../../../testing/src/main/kotlin/app/skein/testing/FakeInferenceEngine.kt)). That is Jan's mock provider, minus a network.
   - Add scenario parameters for every A1 state:
     - `loadDelay`;
     - `prefillDuration` and chunk count (drives the phase 2 progress events);
     - a scripted reasoning span;
     - `failAt(ErrorCode)`;
     - `stopLatency` (simulates the between-chunk cancel);
     - `droppedBatches`.
   - Keep these test and debug only, per POCKETPAL's `__automation__` rule.
2. **Fixture state for previews, not fakes wired through the pipeline.** §37 Layer A previews take plain UI state (`TurnActivity`, `ChatUiState`, `ModelListUiState`) built from fixtures in `src/debug/`, so a preview never needs a ViewModel. This is the Compose analogue of rendering against Jan's Default services.
3. **Deterministic, echoing content.** Jan's *"mock reply to: …"* proves the round trip. Skein's fake should echo a stable prefix plus the assembled user turn, as it already keys on `"User: hello"`. Screenshot tests can then assert that retrieval context and citations flowed, not just that text rendered.
4. **Isolation per run.** Jan redirects its data folder via environment. Skein's equivalent is `InMemoryVaultRepository` and `InMemoryModelRegistry`, which already exist in `testing/`. Use them for every lab scenario so no preview or screenshot test touches SQLCipher or a real GGUF.
5. **Keep real-model testing on its own lane.** Jan and PocketPal agree: real inference is slow and backend-specific, and belongs in a separate device test. Skein already has `tools/m0-benchmark` and `LlamaNativeTest`. The UX lab never waits on a model.

### 6.7 Extensions / MCP: how extensibility stays secondary

**How Jan keeps extensibility out of the way:**
1. **Extensions are architecture, not a primary surface.** Engines and features ship as extension packages (`extensions/*`, `BaseExtension`). The *Extensions* settings page exists (`routes/settings/extensions.tsx`) but **is not linked from the Settings menu** (J9 lists every entry; `route.settings.extensions` is referenced only by its own route file).
2. **Integrations are fenced and labelled.** *MCP Servers* and *Claude Code* sit in a separate *Integrations* group below all core settings, with an **Experimental** badge ([`SettingsMenu.tsx#L227-L275`](https://github.com/janhq/jan/blob/1cd96da93443c89c9f069f13a647dd9b6f8181e8/web-app/src/containers/SettingsMenu.tsx#L227-L275)).
3. **Tools surface in the flow of work.** A tools dropdown in the composer (`DropdownToolsAvailable.tsx`, which shows *"No tools available"* when empty) selects tools per chat. Tool calls appear inside the reasoning trace, and approvals pin that trace open (J6, J14).
4. **Platform gating removes what cannot run.** MCP settings are off on mobile (J10).

**Implications for Skein's future skills, tools and workflows. None of this is built in this wave.**
- **Skills are contextual.** They appear in the command palette (`/skill …`) and the composer `＋` sheet. They are never a primary destination (§20).
- **A running skill is an activity step** (A1: *Running skill ⟨name⟩*). Permission is requested *in* that step with Jan's graded options (J14): *Allow once / Allow in this chat / Always allow*. The expanded activity view shows exactly what context the skill received.
- **Configuration lives under Settings → *Skills & tools (Experimental)*,** below the essentials, and only once at least one skill exists. Until then, nothing is rendered (§2).
- **No unlinked routes in production** (N-J11). No plugin marketplace.

### What could not be determined

- **The owner's GGUF metadata.** I did not inspect the `general.*` keys in the owner's actual Qwen 2.5 3B abliterated file. The §6.1 worked example is illustrative. Community re-quantisations vary: some omit `basename` and `size_label`, and some carry a repository-style `general.name`. The resolver's filename fallback (priority 4) exists for exactly this case, and a unit-test corpus of real `general.*` dumps should be collected on the device lane.
- **The rendered Jan desktop UI.** It was not run, and no screenshots were captured. Every UI claim is read from source at the pinned SHA. Unreleased `main` behaviour (J6–J8) may still change before the next release.

---

**What specific problem in Skein can this project help us solve?** Making a local model feel like an ordinary assistant. Specifically:
- replacing the filename-and-hash model chip and the `UNKNOWN`-licence Models list with a friendly identity (`Qwen 2.5 3B · Local`), plus a Models hierarchy with details on demand;
- turning minute-long silent prefill and finished turns into a truthful *Reading… ~40s left* step and a persisted *Worked for …* summary;
- doing both while keeping future external compute, local tooling and extensions as documented seams rather than visible, unfinished UI.
