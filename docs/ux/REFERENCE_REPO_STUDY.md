# Skein reference-repo study (consolidated, Wave 0)

**Bead:** `skein-xtov.12` (epic `skein-xtov`) · **Brief:** `docs/research/SKEIN_UI_UX_OVERHAUL_PROMPT.md` §4–17, §52–53 · **Inspected:** 2026-09-26
**Companions:** [`UX_AUDIT.md`](UX_AUDIT.md) (the Skein problems referenced as `UX-P0-nn` / `UX-P1-nn`) · [`ANDROID_SKILLS_ASSESSMENT.md`](ANDROID_SKILLS_ASSESSMENT.md) (the full android/skills study)

## Summary (read this first)

- **Thirteen references were studied**: twelve external projects plus Google's `android/skills`. Each full study answers brief §52 and ends with "What specific problem in Skein can this project help us solve?". This file is the index and the synthesis; the studies stay the record.
- **No code is copied from any of them.** Three licences forbid it outright: Zed (GPL-3.0-or-later), Open WebUI (custom branding licence) and LobeHub (a commercial licence is needed for derivative works). For the rest, the stacks (React, React Native, Svelte, Tauri, Rust/GPUI) make rewriting cheaper than porting. What Skein takes is behaviour, rules and copy patterns, rewritten in Compose in Skein's own words.
- **ADOPT:** PocketPal (chat restraint and the activity block), Jan (model identity and Models hierarchy), Android adaptive-apps-samples (Material scaffolds, fold behaviour), Now in Android (retained state behind Navigation 3, stateless screens, screenshot helper), Roborazzi (already wired by the spike). **PROTOTYPE:** AnythingLLM (context chip, attach sheet, inspector) and android/skills (three skills to use now). **STUDY ONLY:** Continue, Zed, LibreChat, Open WebUI, LobeChat, Compose samples.
- **Licence flags that matter** (details in [§4](#4-licensing-summary)): Open WebUI's branding clause; LobeHub's commercial clause (its `package.json` "MIT" is not a grant); Zed's GPL; Jan was AGPL-3.0 until 2025-05-20 and eight of its packages still say so; PocketPal is plain MIT; Continue is Apache-2.0 but frozen and read-only.
- **Five concrete Skein designs already exist in the studies** and are inputs to their waves: the activity block ([POCKETPAL A1](research/POCKETPAL.md#a1-reasoning-and-activity-presentation-the-skein-activity-block)), the model identity scheme and Models hierarchy ([JAN §6.1–6.2](research/JAN.md#61-make-local-ai-feel-normal)), the command palette and shortcuts ([CONTINUE](research/CONTINUE.md#proposed-skein-command-palette--shortcuts)), the context presentation ([ANYTHINGLLM](research/ANYTHINGLLM.md#proposed-skein-context-presentation)), and the adaptive strategy ([ANDROID_ADAPTIVE_SAMPLES §5](research/ANDROID_ADAPTIVE_SAMPLES.md#5-proposed-skein-adaptive-strategy)).

---

## 1. Comparison

Recommended action uses the brief §52 vocabulary. "Reuse allowed?" is about the licence; in practice nothing is being copied (see the summary).

| Project | Repo URL | Licence (exact) | Pinned SHA / date | Maintenance | Recommended action | Skein problem it solves (one line) | Reuse allowed? |
|---|---|---|---|---|---|---|---|
| PocketPal AI | <https://github.com/a-ghorbani/pocketpal-ai> | `MIT` (Copyright (c) 2024 Asghar Ghorbani) | tag `v1.18.0` = `ebe1425a` (2026-09-25) | Active: releases every ~2 weeks, maintainer-led; no tablet/fold layout | **ADOPT** patterns W1–W21 (the activity block via PROTOTYPE); reject N1–N14 | The outer screen's chat: dead-air `thinking…`, anonymous undeletable "Chat"s, filename in the header | Yes (MIT, with notice). None planned: React Native |
| Jan | <https://github.com/janhq/jan> | `Apache-2.0` at the pinned SHA (abbreviated notice; was **AGPL-3.0** until `e8ca7f3c1b`, 2025-05-20; 8 `package.json` files still say AGPL-3.0) | `main` `1cd96da9` (2026-09-25); last release `v0.8.4` (2026-07-23) | Very active; two months of unreleased churn on `main` | **ADOPT** J1–J8 and J16; **STUDY ONLY** J9–J15 | Local AI that feels normal: friendly model identity, a real Models screen, prefill progress, "Worked for …" | Only post-`e8ca7f3c1b` files, with written confirmation. None planned |
| Continue | <https://github.com/continuedev/continue> | `Apache-2.0` | `5522c6f4` (2026-07-21); final `v2.0.0`/`v2.1.0-vscode` (2026-06-19) | **Unmaintained, read-only** | **STUDY ONLY** (feeds the Wave 10 palette prototype) | A simple chat box with deep workspace capability; the raw `/` parser becomes a palette | Yes (Apache-2.0 + NOTICE). None worth it: frozen TypeScript |
| Zed | <https://github.com/zed-industries/zed> | `GPL-3.0-or-later` (only `gpui` is `Apache-2.0`) | `933d8d93` (2026-09-25); `v1.21.0` (2026-09-23) | Very active; weekly releases; agent UI rewritten often | **STUDY ONLY** | Palette availability and ranking, focus rules, panes that stay calm on the inner display | **No** (GPL). Behaviour only |
| LibreChat | <https://github.com/LibreChat-AI/LibreChat> (moved from `danny-avila/LibreChat`) | `MIT` (Copyright (c) 2026 LibreChat) | `7b2362d7` (2026-09-25); `v0.8.8-rc4` (2026-09-23) | Very active (100+ commits a week) | **STUDY ONLY** | Conversation lifecycle (rename, archive, a delete that names the chat), personas as one "who answers" picker, shortcut rules | Yes (MIT). None worth porting |
| AnythingLLM | <https://github.com/Mintplex-Labs/anything-llm> | `MIT` (Copyright (c) Mintplex Labs Inc.) | `128a0157` (2026-09-25); `v1.16.2` (2026-09-22) | Active; chat surface changes fast | **PROTOTYPE** (patterns only) | Showing what knowledge is active in a chat: scopes, a context chip, a per-answer sources chip, one inspector | Yes (MIT). None: JSX/Node |
| Open WebUI | <https://github.com/open-webui/open-webui> | **"Open WebUI License"** (BSD-3-Clause + a branding clause) for code after `60d84a3` (2025-04-18); BSD-3-Clause `a76068d`…`60d84a3`; MIT before 2025-01-10. GitHub reports `NOASSERTION` | `8bd8b4fa` = `v0.11.4` (2026-09-21) | Very active; high churn in exactly the studied areas | **STUDY ONLY** | Configuration without clutter: inherit-by-default settings, one controls panel that reflows between sheet and pane | **No**: the branding clause is not on the allowlist. No code, strings, icons or name |
| LobeChat (LobeHub) | <https://github.com/lobehub/lobe-chat> → now <https://github.com/lobehub/lobehub> | **"LobeHub Community License"** (Apache-2.0 + clause 1(b): a commercial licence is required to distribute derivative works). Root `package.json` says `MIT`, which is not a grant | `canary` `769c006a` (2026-09-26); `v2.2.18` (2026-09-20) | Very active; growing into a suite | **STUDY ONLY** | A calm, dense composer: one `＋`, a context meter that appears only under pressure, a conversation that keeps its width | **No** (clause 1(b)). Ideas only |
| Android adaptive-apps-samples | <https://github.com/android/adaptive-apps-samples> | `Apache-2.0` | `62abdd2a` (2026-05-12) | Active DevRel samples; each sample pins its own (older) versions | **ADOPT** the APIs (via a Wave 3 prototype); copy no sample code | The hand-rolled pane host, which resets on every fold and gives the closed-landscape Fold two panes | Yes (Apache-2.0; keep the header and add NOTICE if a snippet is copied). Not needed |
| Jetpack Compose samples (Jetchat, Reply, JetNews) | <https://github.com/android/compose-samples> | `Apache-2.0` | `0bbd72d6` (2026-09-18) | Very active; bot dependency bumps; **same Compose BOM as Skein** | **STUDY ONLY** (patterns adopted) | A chat surface that survives the fold (draft, scroll, IME), a size-aware drawer, a navigation policy by window | Yes (as above). Patterns under 30 lines, rewritten |
| Now in Android | <https://github.com/android/nowinandroid> | `Apache-2.0` | `a49ed253` (2026-09-22) | Very active; migrated to Navigation 3 | **ADOPT** (patterns, rewritten; no Hilt, no bot commits) | Retained, testable screen state behind Navigation 3 entries; stateless screens; a multi-device screenshot + accessibility helper | Yes (as above). Screenshot helper and Navigator shapes, rewritten |
| Roborazzi | <https://github.com/takahirom/roborazzi> | `Apache-2.0` | `c636f777` (2026-09-26); release `1.75.0` (2026-09-21) | Healthy, releases every ~2 weeks; one main maintainer (bus-factor risk) | **ADOPT**: done, wired into 7 modules by `skein-xtov.9` | Visual evidence of every UI change at the Fold's real sizes, on the Mac, despite FLAG_SECURE | Yes: a test-only dependency, not in the APK |
| android/skills | <https://github.com/android/skills> | `Apache-2.0` (`LICENSE.txt`) | `v1.0.13` = `42dc2270` (2026-09-25) | Active; bot-regenerated from developer.android.com about weekly; no public contributions | **PROTOTYPE**: USE NOW `edge-to-edge`, `navigation-event`, `testing-setup` (excerpt); REFERENCE `adaptive`, `navigation-3`, `android-cli`; DEFER 2; N/A 17 | Rules agents can follow for adaptive layout, Back, insets and tests | Yes, as pinned vendored copies with `LICENSE.txt` and change notices. **Never** the plugin marketplace |

Also inspected: [`roborazzi-usage-examples`](https://github.com/takahirom/roborazzi-usage-examples) (`Apache-2.0`, `30c640f2`, 2023-12-08) is stale and useful only for its onboarding diffs. PocketPal's engineering and licensing recon is a separate document, [`docs/research/POCKETPAL_RECON.md`](../research/POCKETPAL_RECON.md) (pinned `v1.17.3`). All clones live under the git-ignored `research/clones/` and were never committed.

---

## 2. What we take from each Skein problem

Organised by problem, not by repository. Each section says what Skein does today (with its `UX_AUDIT.md` finding), what we take, and which studies back it.

### 2.1 Front door simplicity

*Today:* an untitled emoji column or a 70 % blank pane, no visible "New chat", and slash commands in the empty states (UX-P0-01, UX-P0-16, UX-P1-01, UX-P1-36).

- **Home is one sentence, a composer and a model label.** Jan's *"How can I help you today?"* home (J15), with Skein's *Recent* and knowledge actions added underneath.
- **The empty chat depends on state**, with one next action each (PocketPal W19/A6). No model: *Add a model to start* → **Choose a file…**. Model not loaded: the composer is live and the model starts on send. Import running: *Getting Qwen 2.5 3B ready · 42 %*.
- **Two-line header: title over `Qwen 2.5 3B · Local`** (PocketPal W9, the brief §22 sketch). On the outer display: `≡ · title/model · ⊕ (only when there is a chat to leave) · ⋯` (LibreChat L14).
- **On the outer display the drawer holds the destinations plus the recent chats**, grouped by date, with a full-width selected pill and Search and New chat within thumb reach (PocketPal W10/A3, LibreChat L15). On the inner display the same list becomes a persistent pane (Jan §6.5 mapping).
- **Depth enters through only three things**: the composer (sigils), the answer (collapsed disclosures) and the top bar (palette). Nothing else goes on the default screen (Continue, "key question"; Open WebUI O3 "only what's on"). The placeholder teaches one power affordance: *"Ask anything · [[ to add a note"* (Continue C2). A persona's description and 2–4 starter prompts fill the empty chat (LibreChat L8).

*Backed by:* [POCKETPAL](research/POCKETPAL.md), [JAN](research/JAN.md), [LIBRECHAT](research/LIBRECHAT.md), [CONTINUE](research/CONTINUE.md), [OPEN_WEBUI](research/OPEN_WEBUI.md).

### 2.2 Activity and reasoning

*Today:* one static `thinking…` covers retrieval, model load and prefill, which has run for over 10 minutes on the Fold. Stop is silently ignored early, and nothing remains afterwards (UX-P1-10, UX-P1-11, UX-P1-13).

- **The Skein activity block** is metadata above each answer, not a bubble, and has two lanes (PocketPal A1):
  - **Lane A: pipeline steps**, each driven by a real event: *Starting model → Searching your knowledge (7 passages) → Reading your message · 1:08 → Writing*. A step exists only if its event fires.
  - **Lane B: the model's reasoning**, shown only when the engine classifies tokens as reasoning, in a bounded window that follows the newest line. It auto-collapses on the first answer token unless the user has touched it (PocketPal W2–W3, W8).
- **After the turn:** `▸ Worked for 1m 31s · 3 sources`, persisted with the message, never re-measured on reload (Jan J6–J7, AnythingLLM A8). While live, show only the current step; show the full rail when done (Jan J8).
- **"Reading your message" gets an ETA** once prefill progress exists: `2.1k / 3.4k · ~40 s left` (Jan J5). This needs one counts-only callback from the inference owners; it is a separate seam bead, not a UI change.
- **"Stopping…" is a state** that holds until cancellation actually lands (PocketPal W5). Cancellation lands only between 512-token prefill chunks.
- **Copy uses three tenses per step**: *Searching your notes* → *Searched your notes · 3 used* (Continue C13, LibreChat L11, Open WebUI O13).
- **Accessibility:** one polite announcement per step, never per token.

*Backed by:* [POCKETPAL A1](research/POCKETPAL.md#a1-reasoning-and-activity-presentation-the-skein-activity-block), [JAN](research/JAN.md), [ANYTHINGLLM](research/ANYTHINGLLM.md), [CONTINUE](research/CONTINUE.md), [LIBRECHAT](research/LIBRECHAT.md), [OPEN_WEBUI](research/OPEN_WEBUI.md).

### 2.3 Model identity and management

*Today:* the chip shows a filename prefix, then a 51-character slug+hash. The Models list shows `.gguf` names and `UNKNOWN`. Delete is one tap. Models is reachable only through `/models` (UX-P0-02, UX-P1-21…26).

- **A name resolution chain** where the first non-blank value wins: user rename → bundled manifest name (the two first-run defaults) → GGUF `general.basename` + `size_label` → filename with quant, shard and `gguf` stripped → "Local model" (Jan J1–J2, extended in [JAN §6.1](research/JAN.md#61-make-local-ai-feel-normal)).
  - GGUF strings are attacker-controlled: read them in `:inference`, bound them, strip control, bidi and zero-width characters, and never infer trust from a name.
  - Don't reproduce PocketPal's trailing-period bug (N1) or Jan's hyphenation of names (N-J1).
- **Presentation:** primary `Qwen 2.5 3B`; secondary `Local` (status only when not ready: `Starting…`, `Loads when you send`); optional tags (`Abliterated`, `Vision`). Filename, quant, hash, context and licence live under *Technical details* (Jan §6.1; Open WebUI O9; LobeChat L3–L5, "only what tells rows apart"). Middle ellipsis only for the filename row in details (PocketPal W20).
- **Models hierarchy:** *On this device* (Active badge) · *Add a model* (Choose a file…; Get models only when Skein Hub is installed) · *Model details*, holding Rename, Make default and **Delete…**. Delete confirms by name and space freed, focuses Cancel, and never sits on a list row (Jan §6.2, N-J6). A fit estimate is hidden until a real RAM model exists (J3).
- **One sheet for "who answers"**, opened from the header subtitle: models plus persona plus *Manage models ›* (PocketPal W16). Personas can be presented as curated "Ask as ‹Persona›" specs with the raw model one level down (LibreChat L7). Unusable models stay visible, disabled, with a reason (Continue C11, Zed Z13).
- **No one-key model cycling and no side-by-side compare:** a model swap costs 2–5 s and only one model is resident (Continue, Zed, LibreChat, Open WebUI).

*Backed by:* [JAN §6.1–6.2](research/JAN.md#62-model-management), [POCKETPAL A4](research/POCKETPAL.md#a4-model-presentation-in-the-header-and-picker), [OPEN_WEBUI](research/OPEN_WEBUI.md), [LOBECHAT](research/LOBECHAT.md), [LIBRECHAT](research/LIBRECHAT.md), [CONTINUE](research/CONTINUE.md), [ZED](research/ZED.md).

### 2.4 Conversation lifecycle

*Today:* every chat is "Chat", can't be reopened, renamed or deleted, and piles up (UX-P0-05, UX-P0-06, UX-P1-09, UX-P1-27).

- **Titles:** a deterministic title from the first sentence of the first message, cut at a word boundary at ≤ 40 characters and stored without an ellipsis. An optional model-generated upgrade runs only when idle and only while the title is still automatic. A user rename is final (PocketPal W12/A3, Jan J13, LibreChat).
- **The list:** buckets Pinned · Today · Yesterday · Previous 7 days · Previous 30 days · month (LibreChat L4, PocketPal N8 fixed). A spinner on any chat still generating in the background (Jan J12). A visible `⋯` on outer-display rows plus long-press: Rename… · Pin · Archive · Delete… (LibreChat L1, Open WebUI O11). List-local keys on the inner display: Enter, Delete…, Shift+R (Zed Z15).
- **Delete:** a dialog that names the chat and what else is affected, with Cancel focused and the destructive button last. After deleting, land on a new chat (outer display) or the next row (inner display), and close every tab that showed it (LibreChat L2, PocketPal W13). What delete must do underneath is in [`LIFECYCLE_FINDINGS.md` §15](audit/LIFECYCLE_FINDINGS.md#15-proposed-deletion-contracts). **Archive**, the reversible sibling (LibreChat L3), needs a schema decision first (LIFECYCLE G16).
- **Drafts per chat**, kept across chat switches and folds (PocketPal W14; how in §2.7).

*Backed by:* [POCKETPAL A3](research/POCKETPAL.md#a3-navigation-drawer-conversation-grouping-titles-rename-and-delete), [LIBRECHAT](research/LIBRECHAT.md), [JAN](research/JAN.md), [OPEN_WEBUI](research/OPEN_WEBUI.md), [ZED](research/ZED.md).

### 2.5 Context and knowledge presentation

*Today:* `⚹ context` shows the last turn's retrieval, sometimes another chat's, as scores; 📎 imports permanently and only inserts link text; `[[` doesn't pin anything (UX-P1-15, UX-P1-17, UX-P1-18).

- **Three scopes, each with a visible lifetime:** *This message* (an inline `[[link]]` or a selection), *This chat* (attached), *Always* (from the persona). Plus Knowledge on/off for automatic search (AnythingLLM A1).
- **Before sending: one chip above the composer** (`2 notes · 1 file · Knowledge on`). **After answering: one sources chip per answer** (`3 sources`). **Behind both: one inspector**, a bottom sheet on the outer display and a supporting pane on the inner display (AnythingLLM P2–P5, A5–A7; Open WebUI O2, "one content, two containers").
- **Whole note vs Relevant parts** is chosen automatically by the token budget, explained, and changeable per item (Open WebUI O6; AnythingLLM A3 without its three-button modal, N6). Send visibly waits for an attachment that is still indexing (A4).
- **Everything that went into the prompt appears in the inspector, and everything the user asked for but was left out is labelled** (the lesson of AnythingLLM N2 and Open WebUI's hidden server-side merges). No scores, recall stages or token numbers without *Details* (Continue C7, Open WebUI O13, AnythingLLM N4).
- **A context meter only under pressure** (≥ 75 % or history trimmed), using PocketPal's "room" vocabulary (LobeChat L1, Continue C8, PocketPal W18).
- **Width budget on the inner display:** show the inspector pane only if the conversation keeps ≈ 420 dp, otherwise use a sheet (LobeChat L10).
- **`[[` stays the inline attach trigger.** Never `#`, which is a tag in Skein's Markdown (Open WebUI O5).

*Backed by:* [ANYTHINGLLM, Proposed context presentation](research/ANYTHINGLLM.md#proposed-skein-context-presentation), [OPEN_WEBUI](research/OPEN_WEBUI.md), [LOBECHAT](research/LOBECHAT.md), [CONTINUE](research/CONTINUE.md), [ZED](research/ZED.md).

### 2.6 Command palette and keyboard

*Today:* four commands; a tap on a row only fills the text; prefix matching; the composer's `/` does nothing; no shortcuts (UX-P0-16, UX-P1-37, UX-P1-38).

- **One `SkeinCommand` registry behind buttons, palette rows and chords.** Availability predicates hide what can't run, and a command is registered only once it works end to end (Continue §2; Zed Z1; LibreChat L13).
- **A tap or Enter runs the command.** Commands that need input push an argument stage: pick, text or confirm. Delete always confirms (Continue C4, §3).
- **Ranking:** exact → prefix → word start → initials → substring → fuzzy. Commands recently used from the palette rank first. The palette never dead-ends: its last rows are *Ask Skein "q"* and *Search Knowledge for "q"* (Continue §5; Zed Z2, Z6).
- **Container:** a full-screen route on the outer display, a centred dialog on the inner display. Focus returns to where it was *before* the command runs (Zed Z5).
- **Ctrl-only chords**, because Android reserves Meta: Ctrl+K palette, Ctrl+N new chat, Ctrl+Shift+N new note, Ctrl+W close, Esc through a defined ladder. Chords are shown only when a hardware keyboard is attached, and are listed in the system Meta+/ helper via `onProvideKeyboardShortcuts` (Continue §7–10; Zed, "Android/Fold relevance").
- **The composer's `/` opens the same palette**, filtered to chat commands (Continue §9).
- **Privacy:** persist command ids and timestamps only; typed queries stay in memory and are wiped on lock (Zed Z2–Z3 adapted).

*Backed by:* [CONTINUE, Proposed Skein command palette & shortcuts](research/CONTINUE.md#proposed-skein-command-palette--shortcuts), [ZED](research/ZED.md), [LIBRECHAT](research/LIBRECHAT.md).

### 2.7 Adaptive Fold layout and state preservation

*Today:* width-only tiers; the closed-landscape Fold gets two panes; every fold recreates the Activity and drops the draft, the running generation, overlays and the model import (UX-P0-07, UX-P0-08, UX-P0-09, UX-P1-04).

- **Decide layout only from measured window size and posture**, never from device model or density. The inner display is Expanded (852 dp at stock, 1043 dp at the owner's density) (ADAPTIVE §1).
- **Two panes need width ≥ 840 dp and height ≥ 600 dp.** The height gate keeps the closed Fold in landscape single-pane. Three panes only from 1200 dp, which the Fold never reaches (ADAPTIVE §5.1–5.2).
- **Material scaffolds through Navigation 3 scene strategies:** list-detail for Chat and Knowledge, supporting pane for Graph, and the inspector as an extra pane that becomes a sheet on small windows. Prototype this in Wave 3 against the §25 tests before switching over (ADAPTIVE §4.2; NiA's `NavigationState`; android/skills `adaptive` and `navigation-3` as reference).
- **Navigation per size:** a drawer on Compact width and on compact height; a rail on Medium and Expanded. Never both visible (ADAPTIVE §5.2; Reply's policy in COMPOSE_SAMPLES).
- **Declare the size `configChanges` *and* make state survive recreation anyway:**
  - a session-scoped chat-turn controller;
  - a `TextFieldState` draft;
  - a reverse-layout transcript with no forced scroll;
  - the graph selection in its navigation key;
  - the model import off the composition.

  Sources: ADAPTIVE §5.6 checklist; Jetchat (COMPOSE_SAMPLES); NiA's ViewModel + `SavedStateHandle` + stateless screens.
- **Test G:** a size-aware drawer state (JetNews). **The composer owns IME insets** (Jetchat; the `edge-to-edge` skill). **Back** via `NavigationBackHandler` for overlays, or Nav3's own back (the `navigation-event` skill). **Remember which side regions were open per posture** (Zed Z8).
- **Owner decision:** a draft that survives *process death* must go into the saved-state Bundle as plaintext (held by `system_server`) or into an encrypted vault row (ADAPTIVE §5.6 #5).

*Backed by:* [ANDROID_ADAPTIVE_SAMPLES](research/ANDROID_ADAPTIVE_SAMPLES.md), [NOWINANDROID](research/NOWINANDROID.md), [COMPOSE_SAMPLES](research/COMPOSE_SAMPLES.md), [ANDROID_SKILLS_ASSESSMENT](ANDROID_SKILLS_ASSESSMENT.md), [LOBECHAT L10](research/LOBECHAT.md), [ZED](research/ZED.md).

### 2.8 Screenshot testing and the MacBook UX lab

*Today:* Roborazzi 1.75.0 is wired into seven feature modules, and the "before" set (153 images) is committed. There is no shared helper, no accessibility check, and no baselines in CI yet ([ROBORAZZI_SPIKE](research/ROBORAZZI_SPIKE.md)).

- **A `:testing:screenshot` module modelled on NiA's `captureMultiDevice`:** the qualifiers are set before content; the accessibility check runs before capture; `resizeScale 0.5`; `changeThreshold 0` (NiA; ROBORAZZI §1, §6). The spike keeps an identical helper copy in each module until this exists.
- **Skein's measured device matrix, tiered to about 160 images** (ROBORAZZI §5). Correct `fold-outer` once the outer width is measured (see `UX_AUDIT.md` §10).
- **Fold transitions captured inside one composition** by flipping `DeviceConfigurationOverride.WindowSize`, so state continuity is proven, not just drawn (ADAPTIVE §5.7).
- **Stateless screens rendered from fixture `UiState`s**, plus scripted `FakeInferenceEngine` scenarios (load delay, prefill duration, reasoning span, stop latency). No preview or screenshot ever needs a GGUF or the vault (Jan J16/§6.6, NiA, PocketPal's "automation that cannot ship" rule).
- **Agent rules** (android/skills `testing-setup`, excerpted): screenshots before layout refactors; **agents never update reference images**. The Roborazzi UI-tree dump lets an agent prove bounds instead of describing a picture (ROBORAZZI; prototype it).
- **Open decision:** which OS records the canonical baselines. ROBORAZZI §7 says macOS, verified in CI later; the spike §9 says Linux, taken from a CI record run.

*Backed by:* [ROBORAZZI](research/ROBORAZZI.md), [ROBORAZZI_SPIKE](research/ROBORAZZI_SPIKE.md), [NOWINANDROID](research/NOWINANDROID.md), [COMPOSE_SAMPLES](research/COMPOSE_SAMPLES.md) (toolchain twin), [JAN §6.6](research/JAN.md#66-macbook-ux-lab-dev-workflow-ideas), [ANDROID_SKILLS_ASSESSMENT](ANDROID_SKILLS_ASSESSMENT.md).

### 2.9 Settings and progressive disclosure

*Today:* dead rows, a feature "Coming in v1.1", a bead id, implementation labels, and no Models or Personas entry (UX-P0-13, UX-P1-39).

- **Three levels: Settings (global) < Persona < This chat.** A null value means "inherit" and is shown as *Default · from Researcher*; the control appears only once the user makes it *Custom* (Open WebUI O1, LobeChat L6).
- **Group by how essential a setting is.** A labelled *Experimental* group for integrations, added only once one exists (Jan J9). A settings search index built from string resources that also feeds the palette (Open WebUI O12).
- **Hide what this build or device can never do; disable with a written reason only for transient states** (loading, locked, indexing, generating) (LobeChat L8, Jan J10).
- **Product-named parameters** (*Creativity*, *Response length*) in the persona editor and Models › Advanced, never in the chat (LobeChat L6, LibreChat L9, PocketPal A4).
- **A one-line explainer the first time a power concept is used** (AnythingLLM A10). **Written content rules**: one term per concept, "-ing…" for progress, never colour alone (LobeChat L13) go into `DESIGN_SYSTEM.md`.

*Backed by:* [OPEN_WEBUI](research/OPEN_WEBUI.md), [LOBECHAT](research/LOBECHAT.md), [JAN](research/JAN.md), [LIBRECHAT](research/LIBRECHAT.md), [ANYTHINGLLM](research/ANYTHINGLLM.md).

### 2.10 Extensibility, later

*Today:* nothing exists, and the brief says not to build it now (§6.3, §6.7).

- **Name the seams now; build nothing.** Model identity carries a *location* attribute (`Local`, later `External (provider)`). The activity block reserves a *Sending to ⟨provider⟩* step, so any off-device turn is marked on the turn itself. No provider picker is rendered while only local models exist (Jan §6.3, Open WebUI O9, LobeChat L4, AnythingLLM P0.6).
- **No local API server in Core**: any socket needs `INTERNET`. If one is ever needed, use a signature-permission AIDL service (Jan §6.4).
- **Skills and tools are contextual** (palette, `＋` sheet) and appear as an activity step with graded approval: *Allow once / in this chat / always* (Jan J14, LobeChat L14). A mode chip only once tools exist (Continue C10). Settings gets *Skills & tools (Experimental)* only once one exists. No unlinked routes in production, and no marketplace (Jan §6.7, N-J11).

*Backed by:* [JAN §6.3–6.7](research/JAN.md#63-local--selective-external-compute-architecture-only-not-to-be-built-now), [CONTINUE](research/CONTINUE.md), [LOBECHAT](research/LOBECHAT.md), [OPEN_WEBUI](research/OPEN_WEBUI.md), [ANYTHINGLLM](research/ANYTHINGLLM.md).

### 2.11 Decisions the studies hand to later waves

These are places where the studies disagree or stop short. Each needs an owner or architect decision, not more research.

| Decision | Options on the table | Wave |
|---|---|---|
| Adopt Navigation 3 with Material scene strategies? | ADAPTIVE and NiA say adopt, via a prototype. The skills assessment says it is a Wave 1 decision, because tabs that move between split panes don't fit Nav3's "shared destinations" limit | 1 |
| Navigation on the closed Fold | A drawer (ADAPTIVE §5.2, PocketPal, LibreChat L15) vs M3's default bottom bar (the `adaptive` skill). The studies agree on the drawer, because the composer owns the bottom edge | 1 |
| Inspector on the inner display at stock density | An extra pane that hides the list (ADAPTIVE §5.2), or a side sheet when the conversation would drop below ≈ 420 dp (LobeChat L10 / AnythingLLM P5). At 852 dp both rules apply; decide from screenshots | 3, 7 |
| Model and persona in one picker or two | One sheet with Models and Persona sections (PocketPal W16) vs persona-as-spec with the model one level down (LibreChat L7) | 4, 9 |
| Draft persistence across process death | Plaintext in the saved-state Bundle vs an encrypted vault row (ADAPTIVE §5.6 #5, COMPOSE_SAMPLES, NiA) | 4 |
| Preview-tab policy | Zed's per-source table: typed search and palette open pinned, browsing opens a preview (Z9), which amends spec §8.3 | 3 |
| Archive chats | Reversible archive (LibreChat L3, Open WebUI O11) needs a column or a frontmatter flag (LIFECYCLE G16) | 5 |
| Where canonical screenshot baselines are recorded | macOS (ROBORAZZI §7) vs Linux from CI (ROBORAZZI_SPIKE §9) | 11 |

---

## 3. What we explicitly do not copy

Consolidated from every study's "Patterns Not Worth Adopting". The study that rejected each item is in brackets.

**Code and assets**
- Any source, strings, icons, token values, fonts or branding. Open WebUI's and LobeHub's licences forbid it, as does Zed's GPL. PocketPal's fonts and brand assets are under their own licences. [all]
- The stacks and their defaults: React Native, React, Svelte, Tauri, GPUI, TipTap, Hilt, Jacoco, Mockk, Dropshots, Paparazzi. Compose Preview Screenshot Testing is deferred until it is stable. [PocketPal, Jan, Continue, Zed, NiA, Roborazzi, skills]

**Model presentation**
- Filename-derived model names; hyphenated names; an engine logo as the "local" signal. [PocketPal N1, Jan N-J1–N-J2]
- Engine or sampling settings one tap from every chat. [Jan N-J3, PocketPal A4]
- Throughput metrics in the default message footer. [PocketPal N5]
- One-key model cycling; side-by-side multi-model compare; "regenerate with every model". [Continue, Zed, LibreChat, Open WebUI, PocketPal N13]

**Interaction**
- Hover-only affordances, tooltip-only explanations, hover previews and drag-resize handles: a touch screen has none of these. [Jan N-J4, Continue, AnythingLLM N3, Open WebUI, LobeChat]
- One-click, Shift-held or keyboard-instant delete. A delete dialog that focuses *Delete* and confirms on Enter. Generic delete copy. [Continue, LibreChat, Jan N-J6, PocketPal N10]
- A sigil zoo (`@`, `+`, `$`), magic text such as `@agent`, and `#` for knowledge (it is a tag in Skein). [LibreChat, AnythingLLM N9, Open WebUI O5]
- Modes, tool approval and apply/diff machinery before model-initiated tools exist. [Continue C10]
- Meta or Cmd chords, Ctrl+Shift-for-everything, multi-stroke chords, which-key, remappable shortcuts, and one chord that means different things in different panes. [Zed, LibreChat, Continue]
- Palette-everything, with humanised internal action names. [Zed]

**Layout and visual**
- Choosing mobile vs desktop from the user agent or at launch, or shipping separate mobile and desktop apps. A fold must reflow live. [LobeChat, AnythingLLM N8, PocketPal N11]
- Wrapping the whole scaffold in `AnimatedContent`, which tears panes down on every change. `accompanist-adaptive`. Jetchat's Fragment/ViewBinding structure. [Adaptive samples, Compose samples]
- A glowing reasoning card, spring-bounce toggles, gradients, emoji avatars, blur. [PocketPal N2–N3, LobeChat, Compose samples]
- Percentage-based reading widths; nine date buckets; ellipses baked into stored titles. [Jan N-J5, PocketPal N8–N9]

**Configuration and knowledge**
- Settings sprawl: ~40 tabs, ~34 raw backend parameters, admin and permission matrices. [LobeChat, Open WebUI]
- Workspaces as hard silos with copied indexes; folders as another container. Personas already do this job. [AnythingLLM N1, Open WebUI O10]
- Context the user can't see (silent backfill, server-side merges, dropped pinned documents). "83 % match" similarity numbers. [AnythingLLM N2, N4, Open WebUI]
- A three-button context-window modal that asks a Level-1 user a Level-2 question. [AnythingLLM N6]

**Network, telemetry and plugins**
- Telemetry of any kind (even opt-in), third-party favicon fetches, web search, marketplaces, MCP, cloud connectors, a local API server bound to `0.0.0.0`, and server-side plugins (Valves, Pipelines). [Jan N-J8–N-J9, AnythingLLM N5, LobeChat, Open WebUI, Zed]

**Tooling and process**
- Bot re-recording of screenshots in CI; recording and verifying on different OSes; `cleanupOldScreenshots`; AI image assertions. [NiA, Roborazzi]
- Installing the android/skills plugin marketplace or running `android skills add --all`. Following the Nav3 guide's "set minSdk 23" or copying skills' version pins (AGP rc, Compose alpha). [skills]

---

## 4. Licensing summary

Skein is Apache-2.0. Its `foss` flavour allows only Apache-2.0, MIT, BSD-\*, ISC, CC0, OFL-1.1 and Unlicense runtime dependencies. No reference project's code is being copied (see the summary); this table exists so nobody reaches for it later by mistake.

| Project | Licence | Flag | Consequence for Skein |
|---|---|---|---|
| **Open WebUI** | "Open WebUI License": BSD-3 + a condition forbidding removal of "Open WebUI" branding for deployments over 50 users (code after 2025-04-18); older code BSD-3 or MIT; contributors grant relicensing rights | ⚠ **Custom licence, not on the allowlist** | No code, strings, icons, visual identity or name. Don't fork it as a Layer C prototype shell. Behaviour only |
| **LobeChat / LobeHub** | "LobeHub Community License" = Apache-2.0 + clause 1(b), a commercial licence for any distributed derivative; clause 2(a) lets the owner change terms; `package.json` says MIT (not a grant) | ⚠ **Community licence with a commercial clause** | No code, `DESIGN.md` prose, token values, locale strings or assets. `@lobehub/ui` is MIT but React-only |
| **Zed** | GPL-3.0-or-later (only `gpui` is Apache-2.0) | ⚠ **GPL** | No code and no transliteration. Behaviour and defaults only; overlapping chords (Ctrl+W, Ctrl+P) are industry conventions |
| **Jan** | Apache-2.0 at the pinned SHA (an abbreviated notice); **AGPL-3.0 from 2023-10-25 to 2025-05-20** (`e8ca7f3c1b`); 8 `package.json` files still declare AGPL-3.0; 9 `Cargo.toml` declare MIT | ⚠ **AGPL history, inconsistent metadata** | Any future adaptation must come from commits after `e8ca7f3c1b` and needs written confirmation from Menlo Research. None planned |
| **PocketPal AI** | MIT | ✓ | Adapting a file would need attribution. Fonts, icons and brand imagery are separately licensed and not cleared. None planned |
| **Continue** | Apache-2.0 | ⚠ **Unmaintained, read-only** since the final 2.x release | Legal to reuse with NOTICE, but nothing upstream will ever be fixed or synced. Never a dependency |
| LibreChat | MIT | ✓ | Legal with notice. Nothing worth porting (clean-room the composer-key function if needed) |
| AnythingLLM | MIT | ✓ (its terms document opt-out telemetry and a CDN model fallback; don't copy those behaviours) | Legal with notice. Nothing transfers |
| Android adaptive-apps-samples, Compose samples, Now in Android | Apache-2.0 | ✓ | A copied snippet keeps its "Copyright … The Android Open Source Project" header plus a `NOTICE` line. The patterns are written fresh |
| Roborazzi | Apache-2.0 | ✓ (single main maintainer) | A test-only dependency, not in the APK. The licence audit is unaffected; every artifact needs `verification-metadata.xml` entries |
| android/skills | Apache-2.0 (`LICENSE.txt`; no NOTICE) | ⚠ **Bot-regenerated weekly** | Vendor pinned copies with `LICENSE.txt`, provenance and change notices outside auto-discovered skill paths. Never the marketplace; never link `main` |

---

## 5. Full studies

| Study | Covers | Bead |
|---|---|---|
| [`research/POCKETPAL.md`](research/POCKETPAL.md) (+ the owner's screenshots [drawer](research/pocketpal-screens/pocketpal-drawer.png), [reasoning](research/pocketpal-screens/pocketpal-reasoning.png)) | PocketPal AI: chat restraint, the activity block, drawer and lifecycle | `skein-xtov.4` |
| [`research/JAN.md`](research/JAN.md) | Jan: model identity, Models hierarchy, progress, extensibility seams, Mac lab workflow | `skein-xtov.4` |
| [`research/CONTINUE.md`](research/CONTINUE.md) | Continue, plus the proposed Skein command palette and shortcuts | `skein-xtov.5` |
| [`research/ZED.md`](research/ZED.md) | Zed: palette availability and ranking, focus, panes, Android key constraints | `skein-xtov.5` |
| [`research/LIBRECHAT.md`](research/LIBRECHAT.md) | LibreChat: conversation lifecycle, personas as specs, shortcut registry | `skein-xtov.5` |
| [`research/ANYTHINGLLM.md`](research/ANYTHINGLLM.md) | AnythingLLM, plus the proposed Skein context presentation | `skein-xtov.6` |
| [`research/OPEN_WEBUI.md`](research/OPEN_WEBUI.md) | Open WebUI: settings cascade, controls panel, model presentation | `skein-xtov.6` |
| [`research/LOBECHAT.md`](research/LOBECHAT.md) | LobeChat/LobeHub: calm composer, context meter, width budget | `skein-xtov.6` |
| [`research/ANDROID_ADAPTIVE_SAMPLES.md`](research/ANDROID_ADAPTIVE_SAMPLES.md) | Measured Fold geometry, Nav3 vs alternatives, `configChanges`, the adaptive strategy | `skein-xtov.7` |
| [`research/COMPOSE_SAMPLES.md`](research/COMPOSE_SAMPLES.md) | Jetchat, Reply, JetNews patterns; toolchain twin | `skein-xtov.7` |
| [`research/NOWINANDROID.md`](research/NOWINANDROID.md) | Production architecture, Nav3 state, screenshot helper | `skein-xtov.7` |
| [`research/ROBORAZZI.md`](research/ROBORAZZI.md) | Screenshot tooling design, device matrix, baseline layout, OS policy | `skein-xtov.7` |
| [`research/ROBORAZZI_SPIKE.md`](research/ROBORAZZI_SPIKE.md) | What was wired, the 153 "before" images, commands | `skein-xtov.9` |
| [`ANDROID_SKILLS_ASSESSMENT.md`](ANDROID_SKILLS_ASSESSMENT.md) | All 25 android/skills classified, conflicts, excerpt for agent briefs | `skein-xtov.8` |
