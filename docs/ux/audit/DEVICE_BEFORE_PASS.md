# Device before-pass — Pixel 9 Pro Fold, current build

**Bead:** `skein-xtov.11` · **Date:** 2026-09-26 · **Build:** `app.skein` 0.1.0 (installed 2026-09-26 01:50) · **Device:** owner's Pixel 9 Pro Fold (`comet`), GrapheneOS, Android 17
**Method:** the owner turned *Block screenshots & screen recording* off in Skein › Settings › Security, then the coordinator drove the app over adb (`input tap/text/keyevent`, `uiautomator dump` for node bounds, `screencap -d <display id>`). No model load or generation was started by this pass.
**Images:** `ux-baselines/device-before/` (downscaled to 1076 px on the long edge). JVM screenshots of the same screens at every device size are in `ux-baselines/before/` (`skein-xtov.9`, `docs/ux/research/ROBORAZZI_SPIKE.md`).

## Geometry (measured)

| Display | Pixels | Density | dp | Width class |
|---|---|---|---|---|
| Inner (portrait) | 2076 × 2152 | 390 physical, **330 forced by the owner** | ~1006 × 1043 | Expanded |
| Inner (landscape) | 2152 × 2076 | 330 | ~1043 × 1006 | Expanded |
| Outer | 1080 × 2424 | 390 physical, 330 forced | ~524 × 1175 | Compact |

At stock density (390) the inner display is ~852 dp wide — still Expanded, by 12 dp. A larger *Display size* setting (≥ ~410 dpi) drops it to Medium. Layout must be derived from `WindowSizeClass`, never from an assumed density.

## What the screenshots show

| # | Capture | Finding |
|---|---|---|
| 01 | `inner-landscape/01-settings.png` | Settings leaks a bead id to users: *"Available once the first-run model picker lands (skein-bxk)"*. *"Biometric unlock — Coming in v1.1"* exposes an unfinished feature. The *Notifications* row's value is mis-aligned. Status-bar icons are white on the light theme (illegible). |
| 02 | `inner-landscape/02-drawer-open.png` | The modal drawer (Timeline · Notes · Graph · Personas · Settings) covers the timeline pane it duplicates. No Chat, Models or New chat entry. |
| 03 | `inner-landscape/03-timeline-landing.png` | Landing on the unfolded Fold: 30 % timeline, 70 % empty pane reading **"No tabs open — back to timeline"**. Every chat is titled **"Chat"**; previews read `user: … assistant: …`. Filter chips clip ("Fi…"). No visible New chat / New note. |
| 04 | `inner-landscape/04-chat-open.png` | **Tapping a chat in the timeline opens it in the note editor** (📄 tab, `— id 01a0dceb… ▸` frontmatter, raw `user:/assistant:` transcript, Backlinks 0). There is no composer: a past chat cannot be continued. (The assistant text is a degenerate repetition loop — an inference/sampling issue, out of scope here, but the UI gives no way to stop, retry or flag it.) |
| 05 | `inner-landscape/05-command-palette.png` | The `/` palette lists four raw commands (`/new note [title] — …`, `/chat — …`, `/import model — …`, `/models — …`) and pushes the whole layout down. |
| 05b | `inner-landscape/05b-palette-no-matching-after-tap.png` | **Tapping a palette row does not run it**: it fills the field with `/chat ` and the palette then says *"No matching commands"* (the trailing space breaks matching). Enter is required. |
| 06–08 | `inner-landscape/06-new-chat.png`, `07-…`, `08-context-panel.png` | The real chat surface: a lower-case `chat` header, `⚹ context`, an empty canvas with no empty state, and a `$` composer with 📎 and ⏎. `/chat` immediately adds an empty "Chat · just now" row to the timeline (this is where the wall of empty "Chat" rows comes from). Two tabs both read "Chat" — one a 📄 note-kind preview, one 💬. The context panel says *"no retrieved context for this turn"*. |
| 10 | `inner-landscape/10-vault-relocked-auth-failed.png` | After a fold that went through the system keyguard, Skein returned with its vault locked, the shell reset (all tabs and the draft gone), and a bare **"Authentication failed. / Try again"** with no app context. |
| P01 | `inner-portrait/01-landing-model-loaded.png` | Once a model is loaded the chip shows the **full model id** `qwen2.5-3b-instruct-abliterated-q3-k-m-2c5f9a121ae6 · ●`, squeezing the search field. Six tabs (Chat · Untitled · France · *Chat* · Chat · Chat) crowd the strip; names don't distinguish them. |
| P02 | `inner-portrait/02-chat-draft-context-keyboard.png` | Composer with keyboard up and the context panel open — the state used for fold test A. |

## Fold/unfold tests (prompt §25) — status

| Test | Result on the current build |
|---|---|
| A — open → closed (chat + context + draft) | **Could not be completed unattended.** `cmd device_state state 0` is treated like a power-button sleep: the outer screen comes up on the keyguard even with *Continue using apps on fold = Always*, and injected swipes cannot dismiss it. The one real fold observed ended with Skein's vault locked and the shell reset (row 10). Code audit (`AUDIT_SHELL.md` P0-7/10/11) independently shows the draft, inspector state, overlays and in-flight generation are all lost on recreation. |
| B — closed → open | Not run (same blocker). Code audit: same state loss in reverse. |
| C — while generating | Not run (no generation in this pass). Code audit: generation is cancelled and the partial answer is never persisted (`AUDIT_CHAT_MODELS_SETTINGS.md` P0-1). |
| D — keyboard active | Setup captured (P02); fold not completed. |
| E/F/G — document / graph node / drawer | Not run. Code audit: graph overlay and drawer state are plain `remember` and are lost. |

These tests become **device acceptance tests for the rebuilt shell** (see `docs/ux/UX_TEST_PLAN.md`), run with a *physical* fold and a watcher that polls `cmd device_state print-state` and captures both displays on each change.

## Side effects of this pass

Two empty chats created by `/chat` remain in the vault (there is no delete UI yet — itself finding P0 in all three audits). The draft text *"draft that should survive a fold"* may remain in a composer.
