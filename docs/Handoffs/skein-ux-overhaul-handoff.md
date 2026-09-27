# Skein UX overhaul — handoff

**Written:** 2026-09-26 evening, at `main` = `75342b2` (+ this commit) · **Epic:** `skein-xtov` · **Coordinator session:** UX workstream (a separate session owns inference; its handoff is `skein-v1-autonomous-completion.md` — don't edit it from here)
**Authority:** `docs/research/SKEIN_UI_UX_OVERHAUL_PROMPT.md` → `docs/ux/UX_MIGRATION_PLAN.md` (waves, gates, owner decisions §2.0) → the specs in `docs/ux/`.

---

## 0. TL;DR for the next session

1. **Waves 0, 1, Stage H and Wave 2 are done and on `main`.** Wave 3 (the new adaptive shell) is **8 of 20** beads in: decision function, Nav3 verdict (**Navigation 3**), security review (approved with conditions), `:core:navigation`, the drawer/rail container, keyboard resize, debug test tags — all merged.
2. **Nothing is in flight.** `AL-07` (the navigation container, `feature/shell/.../container/`: `SkeinNavigationContainer`, drawer/rail content, `groupChatHistory`, `LocalSkeinDrawerOpener`) merged after the first version of this handoff; it follows the spike's one-`ModalNavigationDrawer` shape, not `NavigationSuiteScaffold`, and uses its own small `SkeinDestination` enum that AL-08 maps to `:core:navigation` keys.
3. **Next on the critical path:** `AL-08` (skein-xtov.24.7) — the `NavDisplay` shell host. It is unblocked (AL-05, AL-06, SEC-D7 closed). Then `AL-09a/b` re-host every screen and **switch the app to the new shell**, then AL-10…14, AL-15/16 fold tests, **AL-17 = physical fold on the owner's Fold (Wave 3 gate)**.
4. **The owner has a fresh build on the Fold** (installed 20:23, `bfe4dca`). Their walk-through of everything merged so far is `skein-xtov.34` (checklist in the bead). Install a new build after every batch of visible changes — the phone had silently fallen 74 commits behind `main` before this was noticed.
5. **Unanswered owner question:** whether to start the two backend beads that gate later waves now, in parallel with the shell — `skein-cash` (correct document delete incl. migration 010; gates Wave 5) and `skein-a0mm` (folder/zip Markdown import into a Space, Space export; gates Wave 6). Ask, or start them if the owner has said yes.

---

## 1. What the owner decided (don't re-litigate)

All in `UX_MIGRATION_PLAN.md` §2.0; the IA is `INFORMATION_ARCHITECTURE.md`.
- Plan accepted with its recommended defaults. Five destinations **Chat · Knowledge · Graph · Models · Settings**; drawer on Compact, rail on Medium+; tabs, timeline, icon rail, split view and the `$ search or /command` bar are retired in Wave 3; command palette in Wave 10.
- **After a lock:** keep the user's place (ids-only back stack), drafts (encrypted vault rows) and the partial answer (lock ends a turn through Stop). Security review `docs/ux/SECURITY_REVIEW_D7.md` = APPROVED WITH CONDITIONS M1–M14, attached to AL-06/08/10/15 and chat C1 bead notes.
- **Look:** IBM Plex Sans (UI) + Plex Mono (code/technical), renamed Skein Sans/Mono; desaturated cyan accent; dynamic colour off; Latin only.
- **Spaces** (new, IA §8b): domains built on personas — each Space has its own notes/files, instructions and default model; retrieval is already persona-scoped. Switcher appears only with ≥ 2 Spaces. Owner wants Markdown folder import from a computer (copy to phone, import folder) and Space/vault export — backend ask `skein-a0mm`.
- Tabletop layouts stay a Wave 8 polish item; keyboard suggestions in notes stay off; no undo for deletes (confirm, then hard delete).

## 2. What is on `main`

| Area | Where | Beads |
|---|---|---|
| Audit, research, specs (13 docs) | `docs/ux/*.md`, `docs/ux/audit/`, `docs/ux/research/` | xtov.1–.20, .33 (all closed) |
| Review prototype (not in repo) | https://claude.ai/artifact/KpWqGyqxqs5HWS9GtZuTPf (private) | — |
| Stage H P0 hotfixes | editor first-keystroke/flush/Save-as; hide dead controls; open by kind; visible New chat/New note; palette tap runs; chip ellipsis; Back closes overlays; `configChanges`; image-attach crash; model delete confirm | xtov.21, .22 (closed); device walk **xtov.34 open** |
| Wave 2 design system | `:core:designsystem` — tokens v2 (48 roles, contrast test mirrors `docs/ux/tools/contrast.py`), Skein Sans/Mono (`tools/fonts/`), shapes/spacing/motion, `SkeinIcons` (48 Material Symbols, `tools/icons/`), `rememberSkeinMarkdownStyle`, components (`components/`: dialogs, snackbar, chips, focus ring, keycaps, top bar, list row, empty state, notice, status, search field, segmented control), gallery, guards (`NoShadowOrGradientTest`, `NoHardCodedColorTest`, …), preview annotations + README | xtov.23.* (closed) |
| Wave 2 test infra | `:testing-ui` (SkeinDevice with measured Fold sizes, `captureUx`, a11y helpers), `:testing-fakes` (+ `ScenarioInferenceEngine`, fixture corpus, `checkNoTestDoublesInMain`), committed goldens `ux-baselines/<module>/<device>/…`, `tools/ux/shots`, `.github/workflows/ux-screenshots.yml` (non-blocking; green on Linux with `maxDistance = 0.02`) | xtov.23.14–.23, .35 (flip to blocking after 10 green main runs), .36 (shots: add core-designsystem, guard clear) |
| Copy pass | ~65 strings; table in `docs/ux/COPY_CHANGES_WAVE2.md` | xtov.23.13 |
| Wave 3 so far | `feature/shell/.../layout/SkeinWindowLayout.kt` (+ 38 truth-table tests); `:core:navigation` (keys, total codec, Navigator, 78 tests); `feature/shell/.../container/` drawer/rail container (content kept at one composition position); Nav3 + adaptive deps in the catalog; verdict in `ADAPTIVE_LAYOUT_SPEC.md` §8.9 "Result"; `adjustResize`; debug-only `testTagsAsResourceId` (tag list in `docs/TESTING.md`) | xtov.24.1–.6, .24.20 closed |
| Nav3 prototype (reference, not merged) | origin branch `spike/nav3-prototype` | — |

The old shell (timeline, tabs, command bar) is still what the app shows; the new pieces are built alongside and not wired into `MainActivity` yet — by design, so `main` stays usable until AL-09a/b switch it.

## 3. Wave 3 — what's left, in order

`ADAPTIVE_LAYOUT_SPEC.md` §12 is the source; each bead's notes carry its security conditions.
1. **AL-08** `NavDisplay` host (AL-07's container is its frame): one decorated entry list per destination, Skein-owned stacks + codec (never `rememberNavBackStack`), explicit `tag/uuid` content keys, a ~30-line Activity-scoped `SessionEntryStores` decorator cleared by the lock (not the stock ViewModel decorator — it fails M12), `NavDisplay` kept at one composition position across drawer↔rail, peek as a custom scene; nothing renders before unlock; `setRecentsScreenshotEnabled(false)`. Consider a debug toggle to run the new shell on the device before it becomes the default.
2. **AL-09a/b**: re-host Chat/Knowledge and Graph/Models/Settings as entries; delete `TabsState`/`TabHost`/`TabStrip`/`RecentDropdown`/`TimelineRail`/`SkeinApp` slots, overlays, `AdaptivePaneHost`/`SplitHost`, the old `NavDrawer`/`IconRail`/command bar, `PaneLayout.kt`/`FoldPosture.kt`, and the deprecated theme shims in `feature/shell/.../theme/ThemeForwarding.kt`. This is the switch the owner will see.
3. AL-10 retained chat state (entry VM over a session turn controller, `DraftStore`, `FocusRequester` on the composer), AL-11 insets/IME, AL-12 back + predictive back, AL-13 transition rules, AL-14 deep links, AL-18 Roborazzi matrix, AL-19 hinge guards.
4. **AL-15** JVM fold tests A–G (+ Bundle privacy per M3), **AL-16** emulator lane, **AL-17** owner-device watcher (`tools/fold-watch.sh`, physical fold — `cmd device_state` hits the keyguard and relocks the vault).

After Wave 3: Wave 4 chat (C1 turn controller first) and Wave 6 knowledge can run in parallel; see the plan's dependency sketch.

## 4. Backend asks filed for other owners

`skein-342p` prefill progress (inference, Wave 4 C12) · `skein-c2t9` reasoning spans (inference, gated) · `skein-2lpf` idle-lock poke + no lock mid-answer (security) · `skein-6b6x` recovery screen action (security) · `skein-cash` correct delete LC-03..08 + migration 010 (**gates Wave 5**) · `skein-a0mm` bulk import/export for Spaces (**gates Wave 6**) · lifecycle bugs `skein-g1bd`, `-cbn2`, `-p24q`, `-xath`, `-azrr`, `-50w6`.

## 5. How this workstream runs (and what went wrong today)

- **Dispatch:** one bead per agent, `isolation: worktree`, briefs self-contained (read list, task, rules, pre-close checks, commit message, ≤ N-word report). Opus for architecture/state/security, Sonnet for bulk UI, Haiku for docs.
- **Merge recipe:** `git pull --rebase` → `tools/loop/handback-check.sh <sha> --expect-tests` → `git merge --no-ff` → run each touched module's `:check` + `:app:testFossDebugUnitTest :app:ktlintCheck` (+ `lintFossDebug` if resources) + `./gradlew --no-build-cache verifyRoborazziDebug` → push → close bead → remove *that* agent's worktree **by name**. `git pull --rebase` flattens merge commits; that's fine.
- **Never sweep worktrees in a loop while agents run** — a commit-less live branch looks merged to `git cherry`/merge-base. Today three live agents lost their worktrees this way; they were recreated from `origin/main` and redid their work.
- **Never run `clearRoborazziDebug` directly** — module output dirs point into `ux-baselines/`, so it deletes committed goldens (restore: `git ls-files -d ux-baselines | xargs git checkout --`). Record with `tools/ux/shots record <module> --tests <filter>`; `core/designsystem` isn't in `shots` yet (xtov.36) — use `./gradlew --no-build-cache :core:designsystem:recordRoborazziDebug --tests '<filter>'`.
- **zsh:** `"$B:ux-baselines/…"` triggers the `:u` modifier — quote as `"${B}:path"`; never pipe gradle to judge success.
- **Device:** screenshots work while Skein › Settings › Block screenshots is off; `screencap` needs `-d <display id>` (inner `4619827677550801152`, outer `…153`). Owner runs density 330: inner ≈ 1006×1043 dp (Expanded), outer ≈ 524×1175 dp. Install with `adb install -r` only (never uninstall — it wipes the vault). The inference session shares the device and `MainActivity.kt` — keep hunks small and rebase late.
- **Evidence:** every UI change carries before/after screenshots; review evidence lives in `ux-baselines/stage-h/` and `ux-baselines/wave2/`, committed goldens in `ux-baselines/<module>/`.

## 6. Open owner actions

- `skein-xtov.34`: ~10-minute walk on the Fold of the Stage H fixes, system bars (DS3) and keyboard resize (AL-03) — checklist in the bead.
- Answer: start `skein-cash` / `skein-a0mm` in parallel now?
- Physical fold test at the end of Wave 3 (AL-17).
