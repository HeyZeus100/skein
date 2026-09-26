# Skein — MacBook UX Lab Plan

**Bead:** `skein-xtov.19` · **Epic:** `skein-xtov` (UX overhaul) · **Deliverable 13 of 13** (prompt §49)
**Authority:** `docs/research/SKEIN_UI_UX_OVERHAUL_PROMPT.md` §37 (layers A/B/C), §38 (Compose Desktop), §39, §6.6 (Jan's lab relevance)
**Companion:** `docs/ux/UX_TEST_PLAN.md` — the device matrix (§3.1), screenshot tiers and names (§3.3–3.5), baseline policy (§4), fixtures and the `ScenarioInferenceEngine` (§6) that this lab runs on. This document is the *loop*; that one is the *rules*.
**Inputs:** `docs/ux/research/ROBORAZZI_SPIKE.md`, `ROBORAZZI.md`, `NOWINANDROID.md`, `COMPOSE_SAMPLES.md`, `JAN.md` §6.6, `POCKETPAL.md` ("Mac UX-Lab Relevance"), `docs/ux/ANDROID_SKILLS_ASSESSMENT.md` §6.7, `docs/ux/CHAT_UX_SPEC.md` §26, `docs/ux/KNOWLEDGE_UX_SPEC.md` §22, `docs/ux/audit/DEVICE_BEFORE_PASS.md`, `docs/DEVICE_RUNNER.md`
**Status:** Plan. Nothing here changes code or build files. **[verify]** marks what the implementing bead must confirm.

---

## 0. Verdicts in one screen

| Question (§37/§38) | Verdict | One line |
|---|---|---|
| **Layer A** — real Compose previews of the screens that ship | **ADOPT** | Stateless screens rendered from the same fixture states the Roborazzi baselines use; Skein multipreview annotations for the measured Fold windows (§2). |
| **Layer B** — simulated device dimensions and states | **ADOPT** (through Roborazzi and `DeviceConfigurationOverride`; previews for sizes only) | Twelve measured windows; keyboard-up, bars and posture are simulated in screenshot tests, not previews; the real IME and folds go to the emulator and the Fold (§3). |
| Interactive work without a model | **ADOPT** | `ScenarioInferenceEngine` lab harnesses launched by Studio's *Run preview* or `PreviewActivity` on the Pixel 9 Pro Fold AVD — and on the real Fold, where `PreviewActivity` is not FLAG_SECURE (§4.6). |
| **Layer C** — a localhost `ux-lab/` prototype | **No `ux-lab/` now.** React + Vite: **REJECT.** A static single-file HTML page: **DEFER**, allowed only for a named IA question Compose cannot answer within a day, under strict rules (§5). |
| **Compose Desktop / Multiplatform preview target** (§38) | **REJECT** | Zero reusable UI without converting every feature module (and `:core:rag`) to KMP — the broad refactor §1 forbids — for a renderer that is *less* faithful than Robolectric's (§6). |

---

## 1. What the lab is for, and its rules

**Goal (§37):** iterate on Skein's UI on the MacBook without installing an APK on the Fold for every visual change, and without a model, a vault or FLAG_SECURE in the way.

**The loop, in order of speed:**

| Step | Tool | Feedback time | Answers |
|---|---|---|---|
| 1 | Compose Preview in Android Studio (Layer A) | ~1–5 s after an edit | "does this state look right at this size?" |
| 2 | Roborazzi compare (Layers A+B) | ~5–20 s per module | "what changed, at every window, in light, dark and large text?" |
| 3 | Lab harness on the Pixel 9 Pro Fold AVD | ~30–60 s per install | "does the interaction work: stop mid-stream, fold mid-answer, the soft keyboard?" |
| 4 | The real Fold (hardware runner) | a session | "is it true on the device?" |

**Rules:**
1. Everything in the lab renders **production composables** (the stateless screens that ship) from **fixture state**. No parallel "lab UI".
2. No model, no inference service, no SQLCipher vault, no network. The fake engine and fakes come from `:testing-fakes` (`UX_TEST_PLAN.md` §6).
3. Lab code lives in `src/debug/` (previews, preview states, harnesses) or `src/test/` (screenshot tests), **never `src/main/`** — POCKETPAL's "an automation bridge that cannot ship" rule, enforced by the `no-test-doubles-in-main` CI guard.
4. **Agents do not run `adb`** against the Fold or a local emulator (`DEVICE_RUNNER.md`; skills non-negotiable #9). Steps 1–2 are for everyone; step 3 is for the owner (or a human) on the Mac and for the CI emulator lane; step 4 is the hardware runner's.
5. Recording goldens follows `UX_TEST_PLAN.md` §4.2 (record scope only; Mac only).

---

## 2. Layer A — Compose previews of the components that ship

### 2.1 Conventions

- **Stateless screen first.** A preview calls `XxxScreen(uiState = …, onEvent = {})`, never a route that builds a ViewModel or pipeline (`NOWINANDROID.md`: the single most important enabler). A destination's stateless split arrives in its wave; its previews arrive in the same PR.
- **One state builder, two consumers.** Each feature has `src/debug/.../<Surface>PreviewStates.kt` exposing `val all: Map<String, () -> <Surface>UiState>` keyed by the **spec state id** (`chat-activity-starting`, `knowledge-list`, … from `CHAT_UX_SPEC.md` §26 / `KNOWLEDGE_UX_SPEC.md` §22). Previews call one entry each; the screenshot test iterates `all`, so previews and baselines cannot drift. Streaming states come from `ChatPreviewStates.fromScenario(id, checkpoint)`, the production reducer folded over a `ScenarioInferenceEngine` scenario to a checkpoint (`UX_TEST_PLAN.md` §6.3–6.4).
- **Where previews live:** `src/debug/kotlin/.../<Surface>Previews.kt` in the module that owns the screen. Existing previews in `src/main` move as their screens are redesigned (§2.4).
- **Naming:** one `@Preview` function per state, named after the state id in PascalCase (`ChatActivityStarting()`), `private`, with `group = "<surface>"` so Studio can filter.
- **Theme and determinism:** wrap content in `SkeinPreviewTheme { }` (debug-only, in the design-system module), which follows `isSystemInDarkTheme()` (so `uiMode` previews work), pins the clock to `FIXTURE_NOW` and the locale to `en-US`, and provides `LocalInspectionMode`-safe defaults for anything that would look up an Activity (posture, window info).
- **Density of previews:** use `device = "spec:…"` strings with an explicit `dpi`, never bare `widthDp`/`heightDp` (today's previews at 400/700/1000 dp have no density and match no Fold window, `AUDIT_SHELL.md` P2-07).
- **Keep Studio fast:** the day-to-day annotation is `@SkeinFoldPreviews` (2 renders per state); the full four-device T1 set belongs to Roborazzi, not to a preview panel with 40 states × 4 devices open.

### 2.2 Skein multipreview annotations

Defined once in the design-system module (`:core:designsystem` if Wave 2 extracts it, otherwise `:feature:shell`, package `…designsystem.preview`), in `src/main` so every feature can use them; annotation classes cost nothing in the APK. `:testing-ui` has a test that the specs below match `SkeinDevice` (`UX_TEST_PLAN.md` §3.1).

```kotlin
package app.skein.designsystem.preview

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.content.res.Configuration.UI_MODE_TYPE_NORMAL
import androidx.compose.ui.tooling.preview.Preview

private const val NIGHT = UI_MODE_NIGHT_YES or UI_MODE_TYPE_NORMAL

/** Day-to-day: the owner's two real windows (closed and open daily), light. */
@Preview(name = "fold-outer-524", group = "fold", device = "spec:width=524dp,height=1175dp,dpi=330", showBackground = true)
@Preview(name = "fold-inner-1007-land", group = "fold", device = "spec:width=1043dp,height=1007dp,dpi=330", showBackground = true)
annotation class SkeinFoldPreviews

/** Screenshot tier T1 (UX_TEST_PLAN.md §3.3), light. */
@Preview(name = "phone", group = "t1", device = "spec:width=360dp,height=800dp,dpi=320", showBackground = true)
@Preview(name = "fold-outer-524", group = "t1", device = "spec:width=524dp,height=1175dp,dpi=330", showBackground = true)
@Preview(name = "fold-inner-852", group = "t1", device = "spec:width=852dp,height=883dp,dpi=390", showBackground = true)
@Preview(name = "fold-inner-1007-land", group = "t1", device = "spec:width=1043dp,height=1007dp,dpi=330", showBackground = true)
annotation class SkeinDevicePreviews

/** T1 devices, dark (stacking two multipreview annotations gives their union, not a cross product). */
@Preview(name = "phone · dark", group = "t1-dark", device = "spec:width=360dp,height=800dp,dpi=320", uiMode = NIGHT, showBackground = true)
@Preview(name = "fold-outer-524 · dark", group = "t1-dark", device = "spec:width=524dp,height=1175dp,dpi=330", uiMode = NIGHT, showBackground = true)
@Preview(name = "fold-inner-852 · dark", group = "t1-dark", device = "spec:width=852dp,height=883dp,dpi=390", uiMode = NIGHT, showBackground = true)
@Preview(name = "fold-inner-1007-land · dark", group = "t1-dark", device = "spec:width=1043dp,height=1007dp,dpi=330", uiMode = NIGHT, showBackground = true)
annotation class SkeinDarkPreviews

/** Every Compact window, including compact height and the split-screen minimum. */
@Preview(name = "split-320", group = "compact", device = "spec:width=320dp,height=1007dp,dpi=330", showBackground = true)
@Preview(name = "phone", group = "compact", device = "spec:width=360dp,height=800dp,dpi=320", showBackground = true)
@Preview(name = "fold-outer-443", group = "compact", device = "spec:width=443dp,height=994dp,dpi=390", showBackground = true)
@Preview(name = "fold-outer-524", group = "compact", device = "spec:width=524dp,height=1175dp,dpi=330", showBackground = true)
@Preview(name = "fold-outer-443-land", group = "compact", device = "spec:width=994dp,height=443dp,dpi=390", showBackground = true)
@Preview(name = "fold-outer-524-land", group = "compact", device = "spec:width=1175dp,height=524dp,dpi=330", showBackground = true)
annotation class SkeinCompactPreviews

/** Every Expanded Fold window, plus the Medium and Large canaries. */
@Preview(name = "fold-inner-852", group = "wide", device = "spec:width=852dp,height=883dp,dpi=390", showBackground = true)
@Preview(name = "fold-inner-852-land", group = "wide", device = "spec:width=883dp,height=852dp,dpi=390", showBackground = true)
@Preview(name = "fold-inner-1007", group = "wide", device = "spec:width=1007dp,height=1043dp,dpi=330", showBackground = true)
@Preview(name = "fold-inner-1007-land", group = "wide", device = "spec:width=1043dp,height=1007dp,dpi=330", showBackground = true)
@Preview(name = "medium-791", group = "wide", device = "spec:width=791dp,height=820dp,dpi=420", showBackground = true)
@Preview(name = "large-1280", group = "wide", device = "spec:width=1280dp,height=800dp,dpi=320", showBackground = true)
annotation class SkeinWidePreviews

/** Text scaling on the narrowest real outer width (tier T3). */
@Preview(name = "fold-outer-443 · 100%", group = "font", device = "spec:width=443dp,height=994dp,dpi=390", fontScale = 1.0f, showBackground = true)
@Preview(name = "fold-outer-443 · 150%", group = "font", device = "spec:width=443dp,height=994dp,dpi=390", fontScale = 1.5f, showBackground = true)
@Preview(name = "fold-outer-443 · 200%", group = "font", device = "spec:width=443dp,height=994dp,dpi=390", fontScale = 2.0f, showBackground = true)
annotation class SkeinFontScalePreviews
```

Notes: a landscape window is given as width > height (no `orientation=` parameter needed) **[verify]** in the current Studio; `Devices.PIXEL_9_PRO_FOLD` renders only the stock inner display at 390 dpi, so the explicit specs are required for the outer display and the owner's 330 dpi.

### 2.3 The §37 preview list

Every item maps to spec state ids, so the preview, the screenshot and the acceptance criterion are one thing. "Fixture / scenario" ids are from `UX_TEST_PLAN.md` §6.

| §37 item | State ids (owner spec) | Fixture / scenario | Annotation | Wave |
|---|---|---|---|---|
| Empty chat | `chat-landing-ready`, `chat-landing-nomodel` (CHAT §26) | `F-VAULT-EMPTY`, `F-VAULT-MEDIUM` | `@SkeinFoldPreviews` + `@SkeinCompactPreviews` | 4 |
| Populated chat | `chat-content-stress`, `chat-long-title`, `chat-long-model-name` | `F-MD-MIXED`, `F-TITLE-LONG`, `F-MODEL-LONG` | `@SkeinFoldPreviews`, `@SkeinFontScalePreviews` | 4 |
| Active generation | `chat-activity-starting`, `-searching`, `-reading-5s`, `-reading-3m`, `-reading-progress`, `-writing`, `chat-reasoning-live`, `chat-stopping` | `S-SLOW-PREFILL`, `S-REASONING`, `S-STOP-MID-STREAM` at checkpoints | `@SkeinFoldPreviews` | 4 |
| Completed response | `chat-activity-collapsed`, `chat-reasoning-done`, `chat-stopped-partial` | the same scenarios, finished | `@SkeinFoldPreviews` | 4 |
| Activity expanded / collapsed | `chat-activity-expanded` / `chat-activity-collapsed` | `S-SLOW-PREFILL` | `@SkeinFoldPreviews`, `@SkeinDarkPreviews` | 4 |
| Conversation drawer | `shell-drawer-open` (UX_TEST_PLAN §3.4), `chats-list`, `chats-list-empty`, `chats-search` (CHAT §26) | `F-CHATS-HISTORY` | `@SkeinCompactPreviews` (drawer), `@SkeinWidePreviews` (list pane) | 3 / 5 |
| Knowledge | `knowledge-list`, `knowledge-list-empty`, `knowledge-search-results`, `knowledge-search-no-results`, `knowledge-detail-placeholder` (KNOWLEDGE §22) | `F-VAULT-MEDIUM`, `F-VAULT-EMPTY`, `F-KNOWLEDGE-LONG` | `@SkeinFoldPreviews` | 6 |
| Note editor | `note`, `note-new`, `note-long-content`, `note-save-error`, `note-not-found` | `F-NOTE-*`, `F-KNOWLEDGE-LONG` | `@SkeinFoldPreviews`, `@SkeinFontScalePreviews` | 6 |
| Graph | `graph`, `graph-selected`, `graph-dense-80`, `graph-list-view` | `F-GRAPH-8`, `F-GRAPH-80` (settled layout) | `@SkeinFoldPreviews` | 8 |
| Context inspector | `chat-inspector`, `chat-inspector-room`, `chat-chip-states` | `F-SOURCES`, `F-ATTACHED-2` | `@SkeinFoldPreviews` (sheet vs pane) | 7 |
| Model picker | `chat-model-sheet`, `chat-model-switch-pending`, `models-list` | `F-MODELS`, `F-MODEL-LONG` | `@SkeinFoldPreviews` | 9 |
| Settings | `settings-root`, `settings-privacy`, `settings-about` | — | `@SkeinFoldPreviews`, `@SkeinDarkPreviews` | 9 |
| Confirmation dialog | the design system's dialog pattern: `chat-rename-dialog`, `note-rename-dialog` | `F-TITLE-LONG` | `@SkeinFontScalePreviews` | 2 / 5 |
| Delete chat | `chat-delete-dialog`, `chat-delete-dialog-generating` | `F-TITLE-LONG` | `@SkeinFoldPreviews`, `@SkeinDarkPreviews` | 5 |
| Delete note | `note-delete-dialog`, `file-delete-dialog` | `F-NOTE-*`, `F-FILE-PDF` | `@SkeinFoldPreviews` | 6 |
| Error states | `chat-error-servicedied`, `chat-model-failed`, `chat-error-nomodel`, `note-save-error`, `models-error`, `gate-unlock-error` | `S-FAIL-SERVICE-DIED`, `S-FAIL-OOM-ON-LOAD`, `F-ERRORS` | `@SkeinFoldPreviews` | 4 / 6 / 9 |
| Empty states | `chat-landing-ready`, `chats-list-empty`, `knowledge-list-empty`, `connections-empty`, `graph-empty-vault`, `models-empty` | `F-VAULT-EMPTY` | `@SkeinCompactPreviews` | per wave |

**Owner same-scene comparisons (POCKETPAL.md "Mac UX-Lab Relevance"):** `shell-drawer-open` and `chat-reasoning-live` are rendered at `fold-outer-524` next to `docs/ux/research/pocketpal-screens/pocketpal-drawer.png` and `pocketpal-reasoning.png`, so the owner compares the same scene in both apps (bead ML-8).

### 2.4 Existing previews: keep, fix or delete

| File | Today | Plan |
|---|---|---|
| `feature/chat/.../ChatScreenPreviews.kt` (`src/main`) | 412 dp and 840 dp, no density; a composer hint production lacks (`AUDIT_CHAT_MODELS_SETTINGS.md` §14) | replaced in Wave 4 by `src/debug` `ChatPreviews.kt` over `ChatPreviewStates`; delete |
| `feature/settings/.../SettingsScreenPreviews.kt` (`src/main`) | 360/400/900 dp | re-annotate with `@SkeinFoldPreviews`, move to `src/debug` in Wave 9 |
| `feature/shell/.../NavPreviews.kt`, `AdaptivePaneHostPreviews.kt`, `TabStripPreviews.kt` (`src/main`) | the command bar, tabs and split — concepts the IA removes | delete with the Wave 3 shell |
| `feature/editor/.../SkeinEditorPreview.kt` (`src/main`) | 380 dp editor snapshots | keep until KN-6.4, then `NotePreviews` in `src/debug` |
| `feature/timeline/src/debug/.../TimelineScreenPreview.kt` | `debugCompileOnly(:testing)` — may not render in Studio **[verify]** | retired with the timeline (Wave 6) |
| `feature/shell/.../theme/ThemeGallery.kt` | light/dark token gallery | keep; move with the design system; add a Roborazzi capture of it as the token baseline |

### 2.5 Studio features worth using

- **Live Edit of literals** for spacing, colours and copy in a preview without a rebuild.
- **Interactive mode** on a single preview: clicks, text input and coroutines run, so an expand/collapse or a sheet can be exercised without an emulator. A `ChatLabHarness` preview (§4.6) with `Pacing.RealTime` streams in the panel.
- **Animation preview** for the activity block's live states (and to check the reduced-motion path renders a static dot).
- **UI Check mode** (accessibility and multi-size checks on a preview) **[verify]** in the installed Studio; a quick look, not evidence — the evidence is `UX_TEST_PLAN.md` §9.
- **Run preview on device** launches the preview through `PreviewActivity` on the selected AVD or device (§4.6).

---

## 3. Layer B — simulated device dimensions and states

### 3.1 Dimensions

The twelve windows of `UX_TEST_PLAN.md` §3.1, as preview annotations (§2.2) and as `SkeinDevice` qualifiers in Roborazzi. Key facts to keep in mind while designing: the closed Fold is **524 × 1175 dp** at the owner's setting and **443 × 994 dp** at stock; the open Fold is **1043 × 1007 dp** in the owner's daily landscape and only **852 dp** wide at stock (the tight two-pane case); closed and landscape is **compact height** and must stay single-pane.

### 3.2 States that the JVM cannot produce on its own, and how the lab simulates them

| State (§37 Layer B) | Screenshot tests (authoritative) | Previews | Real behaviour |
|---|---|---|---|
| **Keyboard visible** | `withIme(heightDp)` from `:testing-ui` = `DeviceConfigurationOverride.WindowInsets(... Type.ime() ...)`; Robolectric has no IME window and reports no `ime()` insets (spike finding), so this is the only way. Heights: 40 % / 50 % of the window until UT-15 measures the real ones. States: `chat-keyboard-up`, `chat-composer-multiline`, `note-ime`, `note-new` (tier T6). | **PROTOTYPE** (bead ML-4): a debug `PreviewImeInsets(height) { … }` that dispatches IME insets to `LocalView` the way `DeviceConfigurationOverride.WindowInsets` does internally. **[verify]** that Studio's layoutlib honours inset dispatch; if not, previews do not show keyboard-up states and the screenshot is the reference. | the AVD's soft keyboard; the Fold's keyboard (Test D) |
| **Drawer open** | the stateless shell takes its drawer state; the fixture passes `DrawerValue.Open` (`shell-drawer-open`) | the same fixture | the AVD / Fold |
| **Context open** | a back-stack fixture `[ChatList, Chat(42), Context(42)]`; the shell decides sheet vs pane per window (`chat-inspector`) | the same | — |
| **Long conversations, long titles, very long model names** | fixtures `F-CHAT-500`, `F-TITLE-LONG`, `F-MODEL-LONG`, `F-HUGE-MESSAGE` | the same | — |
| **System bars and cutout** | `withSystemBars()` (mod `bars`); Robolectric reports zero insets by default | `showSystemUi = true` draws decorative bars only (no insets) | the AVD / Fold |
| **Posture (tabletop, book)** | inject `WindowAdaptiveInfo(sizeClass, Posture(isTabletop = …, hingeList = …))` into the shell (mods `tabletop`, `book`) | the same parameter | AVD Extended Controls; the Fold |
| **Physical keyboard attached** | `DeviceConfigurationOverride.Keyboard(KEYBOARD_QWERTY, …)` (mod `kbd`) | — | the Fold with the owner's keyboard |
| **Font scale** | `fs150`, `fs200` | `@SkeinFontScalePreviews` | — |
| **A fold in the middle of a state** | one composition, `DeviceConfigurationOverride.WindowSize` flipped (T5) | — | AVD fold/unfold; the Fold |

### 3.3 What Layer B cannot show (go to the emulator or the Fold)

Real IME behaviour (resize, predictive text bar, IME composition), real edge-to-edge insets and cutout, the keyguard on fold and its vault-lock interplay, hinge-occluded regions on real hardware, TalkBack speech, the physical keyboard's key-repeat and IME switching, frame timing, emoji glyphs from the GrapheneOS font, and the SDK 34 vs Android 17 platform differences (Robolectric renders SDK 34; the phone runs 17).

---

## 4. The daily loop on the Mac (commands)

### 4.0 One-time setup

```sh
# ~/.zshrc (the Mac's shell is zsh)
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home
setopt pipefail          # so `./gradlew … | tee` fails when Gradle fails (UX_TEST_PLAN.md §13.4)

# in the worktree
mkdir -p .agent-logs     # logs go here (git-ignored), never /tmp
```

Always quote `--tests` patterns in zsh (`--tests 'app.skein.…*'`), or zsh aborts with "no matches found" before Gradle runs.

### 4.1 Edit → preview

Open the surface's `<Surface>Previews.kt` in Studio's split view; edit the composable or the preview state; Live Edit updates literals, other changes re-render after the incremental build (~1–5 s). Use `@SkeinFoldPreviews` while iterating; switch the file to `@SkeinCompactPreviews` / `@SkeinWidePreviews` / `@SkeinFontScalePreviews` when checking the edges.

### 4.2 Screenshots: smoke, compare, verify, record

**Today (the spike's layout, before UT-2):** goldens `verify` reads live in git-ignored `build/outputs/roborazzi`, and the committed set is the frozen `ux-baselines/before/`.

```sh
# Smoke: compose every state at every size, write nothing (~12 s warm for all seven modules)
./gradlew :feature:chat:testDebugUnitTest --tests 'app.skein.feature.chat.screenshots.*'

# Compare one module against the frozen before set (spike §4)
./gradlew :feature:chat:clearRoborazziDebug
mkdir -p feature/chat/build/outputs/roborazzi
cp -R ux-baselines/before/ feature/chat/build/outputs/roborazzi/
./gradlew --no-build-cache :feature:chat:compareRoborazziDebug
open feature/chat/build/reports/roborazzi/index.html
```

(After UT-0 renames the outer devices, a capture at `fold-outer-524` has no counterpart in `before/fold-outer/`: pair by hand using `UX_TEST_PLAN.md` §3.1's mapping.)

**After UT-2 (goldens committed under `ux-baselines/<module-dir>/`, `outputDir` wired):**

```sh
# Compare everything against the committed goldens (never fails; writes *_compare.png + reports).
# compareRoborazziDevDebug exists only once :app hosts the fold suite (UX_TEST_PLAN.md §7, UT-7).
./gradlew --no-build-cache compareRoborazziDebug compareRoborazziDevDebug --continue 2>&1 | tee .agent-logs/compare.log

# One module, one test class
./gradlew --no-build-cache :feature:chat:testDebugUnitTest \
  --tests 'app.skein.feature.chat.screenshots.ChatActivityScreenshotTest' -Proborazzi.test.compare=true

# Verify: fails on the first changed image (what CI runs)
./gradlew --no-build-cache verifyRoborazziDebug --continue

# Record — only the bead's record scope, from a clean tree
git status --short                                   # clean first
rm -rf feature/chat/build/intermediates/roborazzi feature/chat/build/outputs/roborazzi
./gradlew --no-build-cache :feature:chat:testDebugUnitTest \
  --tests '*ChatActivityScreenshotTest*' -Proborazzi.test.record=true
git status --short ux-baselines/                     # every changed file must be in the record scope
```

`tools/ux/shots` (bead ML-6, part of UT-2) wraps these so nobody retypes them: `shots smoke|compare|verify [module] [--tests <pattern>]`, `shots record <module> --tests <pattern>` (refuses to run without `--tests`, cleans intermediates, prints `git status --short ux-baselines/` afterwards), `shots open [module]`, `shots watch <module> <pattern>`. Logs go to `.agent-logs/ux/<UTC>-<command>.log`; the script is bash with `set -euo pipefail`.

### 4.3 The build-cache gotcha

`org.gradle.caching=true` is on, and the Roborazzi plugin keeps a copy of every image in `build/intermediates/roborazzi` and restores it into the outputs (`finalizeTestRoborazziDebug`). A record or compare whose inputs match an earlier run comes back `FROM-CACHE` with that run's files, including stale `*_compare.png`s (spike §4). So: **always `--no-build-cache`, and delete `build/intermediates/roborazzi` before a record.** Before UT-2 `clearRoborazziDebug` does both; **after UT-2 never run `clearRoborazzi*`** — it would delete the committed goldens (`UX_TEST_PLAN.md` §4.1). If it happens: `git restore ux-baselines/`.

### 4.4 Viewing diffs quickly

```sh
open feature/chat/build/reports/roborazzi/index.html                 # per-module HTML report
open feature/*/build/outputs/roborazzi/**/*_compare.png(N)            # every diff triptych in Preview.app
qlmanage -p feature/chat/build/outputs/roborazzi/**/*_compare.png(N) >/dev/null   # Quick Look; space/arrows to page
git diff --stat ux-baselines/                                         # which goldens a record changed
git show main:ux-baselines/feature-chat/fold-outer-524/chat-activity-starting__light__fs100.png > .agent-logs/old.png && open .agent-logs/old.png
```

`(N)` is zsh's null-glob qualifier: no error when nothing changed. Each `*_compare.png` is reference · diff · actual side by side. In Android Studio, the Roborazzi plugin (JetBrains Marketplace) shows a test's reference, actual and diff images next to the test.

### 4.5 An optional file-watch loop (no new tools)

Gradle's continuous build re-runs the task whenever an input changes (main, debug or test sources):

```sh
./gradlew --continuous --no-build-cache :feature:chat:testDebugUnitTest \
  --tests 'app.skein.feature.chat.screenshots.ChatActivityScreenshotTest' -Proborazzi.test.compare=true
```

Keep the `*_compare.png` files open in Preview.app (it reloads changed files) or the Roborazzi IDE plugin. Narrow `--tests` to the class you are editing; a whole-module watch re-runs every capture. No `fswatch`/`watchexec` needed.

### 4.6 The emulator loop (the owner or a human; agents use the CI lane)

**One-time: a Pixel 9 Pro Fold AVD on Apple Silicon.**

```sh
sdkmanager "system-images;android-35;google_apis;arm64-v8a" "emulator" "platform-tools"
avdmanager list device | grep -i fold                  # expect pixel_9_pro_fold
avdmanager create avd -n skein-fold -d pixel_9_pro_fold -k "system-images;android-35;google_apis;arm64-v8a"
emulator -avd skein-fold -no-snapshot-save &
adb -e wait-for-device
adb -e shell wm density 330                            # the owner's Display size; [verify] it applies folded and unfolded
adb -e emu help | grep -iE 'fold|posture'              # confirm the fold/unfold console commands on this emulator
```

**The production app on the AVD** (the vault needs a lock screen and a fingerprint):

```sh
./gradlew :app:installDevDebug                          # the dev flavour ships arm64-v8a and x86_64
# once: Settings › Security › set a PIN; Settings › Security › Fingerprint, answering each prompt with:
adb -e emu finger touch 1
# fold / unfold while the app runs:
adb -e emu fold
adb -e emu unfold
adb -e exec-out screencap -p > .agent-logs/emu-$(date +%s).png
```

**Fixture states and lab harnesses without the vault — `PreviewActivity`.** Debug builds merge ui-tooling's exported `androidx.compose.ui.tooling.PreviewActivity` (it is on the manifest-audit debug allowlist and absent from release). It renders any preview function by name:

```sh
adb -e shell am start -n app.skein/androidx.compose.ui.tooling.PreviewActivity \
  --es composable app.skein.feature.chat.ChatPreviewsKt.ChatActivityWriting
```

Studio's *Run preview on device* does the same from the gutter. This gives:
- real soft keyboard, real insets and real density for a fixture state, with no vault and no model;
- **interactive lab harnesses**: `ChatLabHarness` (bead ML-5) is a `src/debug` composable that wires the *real* chat ViewModel/turn controller to `InMemoryVaultRepository` and `ScenarioInferenceEngine(Pacing.RealTime(0.25))` from `:testing-fakes`. Tap Send, watch the activity block walk through `S-SLOW-PREFILL` in 25 % time, tap Stop mid-stream, fold the AVD mid-answer — all without a GGUF (the Jan "browser dev mode" idea, done natively; `JAN.md` §6.6);
- screenshots that are never black: `PreviewActivity` does not set FLAG_SECURE (only `MainActivity` does, `MainActivity.kt:834`).

Limits: `PreviewActivity` does not declare `MainActivity`'s `configChanges`, so a fold **recreates** it — it exercises recreation, not the production handled-configuration path; the preview function must be compiled into the installed APK (a library's `src/debug` is part of the app's debug build); function names are `<package>.<File>Kt.<function>` **[verify]** for `private` previews.

### 4.7 When to put a build on the real Fold

Through the hardware runner, one session at a time (`DEVICE_RUNNER.md`):

- **At the end of every wave:** L5 acceptance (fold tests A–G with `tools/ux/fold-watch.sh`, `UX_TEST_PLAN.md` §2.6), that wave's user flows, a TalkBack pass, and `ux-baselines/device/<wave>/` evidence after owner review.
- **Whenever the question is one only the device answers:** the real keyboard and its resize, insets and cutout, hinge and posture, the keyguard and vault lock on fold, FLAG_SECURE behaviour, biometrics, real-model generation across a fold (Test C), frame timing (§46), the physical keyboard.
- **Never** to answer "does it look right at this size?" — that is L2 on the Mac.

How, safely:
- Install over the existing app with the **same debug signing key** (`~/.android/debug.keystore` on this Mac); a different key fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. **Never uninstall Skein on the Fold: that deletes the owner's vault.**
- Fixture states on the Fold at real density: `adb shell am start -n app.skein/androidx.compose.ui.tooling.PreviewActivity --es composable …` (screenshots work even with FLAG_SECURE on, because it is not `MainActivity`).
- `screencap` on the Fold needs the physical display id: `adb shell dumpsys SurfaceFlinger --display-id`, then `adb exec-out screencap -p -d <id>`.

---

## 5. Layer C — an optional localhost prototype (`ux-lab/`): evaluation

### 5.1 Options

| Option | What it is | Cost | Fidelity to Compose |
|---|---|---|---|
| **None** | Compose previews + Roborazzi + lab harnesses on the AVD / Fold | none beyond Layers A/B | exact: it is the shipping code |
| **Static single page** | one `index.html` with inline CSS/JS and fake data, no build step, no dependencies, served by `python3 -m http.server` | low per experiment | low: a hand-drawn imitation of Material 3 components, fonts, insets and window classes |
| **React + Vite** | an npm project at `localhost:5173` | medium to set up; ongoing npm supply-chain upkeep (a lockfile, audits, updates) in a repository that verifies every Gradle artifact's checksum and aims for reproducible builds | low, and it invites growing a second component library |

### 5.2 Against each purpose in §37

| Purpose | Already covered by | Gap a web page would fill |
|---|---|---|
| Rapid design iteration | Previews with Live Edit (~1–5 s), Roborazzi compare | none for components that ship |
| Layout experiments | Multipreview across 12 windows; T4 canaries | continuous drag-resizing in a browser; partly covered by resizing the AVD window and by `DeviceConfigurationOverride.WindowSize` in a harness |
| Responsive testing | Roborazzi at every window, theme and font scale | none; a browser's CSS breakpoints are not Android window size classes |
| Interaction prototyping | Interactive preview mode; lab harnesses with the fake engine on the AVD and the Fold | none for real components |
| Navigation experiments | the Wave 3 Nav3 prototype gate (IA D8) in the real shell | a quick throwaway for an IA question *before* any Compose exists (e.g. IA §8 Q2: inspector replaces the list vs a 360 dp side sheet) |
| Stakeholder review | the owner is the stakeholder and has the Mac, the Fold, the PR image diffs and `PreviewActivity` on the Fold at real size | none |
| Open on the Fold's browser at real sizes | `PreviewActivity` shows the *real* composables on the Fold at real density | none |

**Divergence risk:** everything a web prototype shows about typography, touch targets, sheets, insets, the IME and window classes is an approximation that someone must re-verify in Compose. Decisions made on it can be wrong in exactly the places this overhaul is about (the outer display's width, large text, the keyboard).

### 5.3 Verdict

- **No `ux-lab/` directory now.** Layers A and B plus the lab harness cover every §37 purpose with the real UI.
- **React + Vite: REJECT.** It adds an npm toolchain and supply chain for a prototype that would diverge from the product, and it is the first step toward "accidentally turning Skein into a web application", which §37 forbids.
- **Static single-file page: DEFER.** Allowed only when all of these hold: (1) a named IA or navigation question in a spec cannot be answered by a Compose prototype within a day; (2) the owner asks for it; (3) it follows the rules below.

### 5.4 Rules, if a static experiment is ever created

1. Path `ux-lab/<question-slug>/index.html` plus a one-paragraph `README.md` stating the question, the spec section and the decision date. One file of HTML with inline CSS/JS; **no npm, no CDN, no web fonts from the network** (offline, like Skein).
2. **Never production:** not referenced by Gradle, CI, the licence audit or any APK; no code is ever ported from it — Compose implements the *decided spec*.
3. **No backend logic:** no retrieval, no prompt assembly, no storage. Fake data only, copied from the fixture catalogue (`UX_TEST_PLAN.md` §6.2).
4. **Local only:** `python3 -m http.server 5173 --bind 127.0.0.1 --directory ux-lab`; on the Fold, reach it only over USB with `adb reverse tcp:5173 tcp:5173` and open `http://localhost:5173` in the browser — never a LAN address (and only the hardware runner or the owner touches the Fold).
5. **Short-lived:** once the decision is recorded in the spec, keep two screenshots in the spec and delete the directory.

---

## 6. Compose Desktop / Multiplatform preview target — evaluation (§38)

**The idea:** a limited desktop target that renders Skein's composables in a resizable window on the Mac, possibly with JetBrains' Compose Hot Reload, as a faster preview loop.

**Measured starting point** (a script over `feature/*/src/main`, 2026-09-26): 15,454 lines of Kotlin in 109 files across 9 feature modules. 19 files (4,095 lines, **26 %**) directly import Android-only APIs (`android.*`, `androidx.activity`/`fragment`/`biometric`/`window`, `LocalContext`, `LocalView`, `R.*`). Every one of the 9 feature modules — and `:core:rag`, which `:feature:chat` needs for `CitationParser` — applies `com.android.library`. Only `:core:model` and `:core:markdown` are pure JVM today.

| Field | Assessment |
|---|---|
| **Migration cost** | **L (weeks), touching every feature module.** Each UI module must become Kotlin Multiplatform (AGP 9's `com.android.kotlin.multiplatform.library` model), resources move to Compose Multiplatform resources (`R.font` IBM Plex Mono, strings), the 26 % of Android-touching code moves behind `expect`/`actual` (SAF launchers, `BiometricPrompt`/`FragmentActivity`, `androidx.window` posture, `SecureTextField`'s `EditorInfo` flags, `LocalContext`), and every dependency must exist for desktop: material3-adaptive and Navigation 3 need their JetBrains multiplatform builds **[verify availability and versions]**; `androidx.window` has none. The JetBrains Compose compiler/runtime must line up with the AndroidX BOM `2026.09.00` Skein pins. |
| **Reusable UI %** | **0 % without the migration** (the unit of reuse is the module, and every module is Android-only). **~74 % of lines are candidates after it** (files with no direct Android import) — an upper bound, because the file-level count misses transitive Android use (theme fonts, `:core:rag`). |
| **Maintenance cost** | Two targets for every UI change and every dependency upgrade; Compose upgrades gated on JetBrains' release cadence; Skiko natives are **OS-classified artifacts**, so `verification-metadata.xml` must be written on both macOS and Linux (`UX_TEST_PLAN.md` §13.5); new licence-audit entries; a second set of tests, because Robolectric-based tests stay Android-only. |
| **Benefit vs Compose Preview + Roborazzi** | Small. The genuine wins are a mouse-resizable live window and hot reload. But the desktop renderer (Skia via Skiko on the JVM, desktop text shaping and density semantics) is **less** faithful to the phone than Robolectric's native graphics, which runs Android's own graphics stack on the host; and the facts this overhaul is about — the 443/524 dp outer width, the IME, insets, the keyguard, posture — do not exist on a desktop window. Previews already give all 12 windows in seconds, Roborazzi gives the evidence, and the resizable AVD plus lab harnesses give live interaction with the real framework. |
| **Risk** | **High.** It is exactly the broad refactor of production modules that §1 forbids, done for a tooling benefit; version skew between JetBrains and AndroidX Compose; a second rendering stack whose differences someone must keep explaining; pressure to grow desktop-only affordances. |

**Verdict: REJECT.** Re-evaluate only if Skein adopts Kotlin Multiplatform for an independent product reason (a desktop companion, for example); desktop previews would then come nearly free, and Roborazzi's `generateComposePreviewDesktopTests` (present in the 1.75.0 plugin) would cover them.

---

## 7. Tooling notes

### 7.1 Android Studio on Apple Silicon

- Previews render with **layoutlib** on the host JVM, not the device's graphics stack: good for layout and state, not the evidence (the Roborazzi image is).
- Many multipreviews make the panel slow; keep `@SkeinFoldPreviews` as the default, use groups to filter, and use Studio's reduced-resource preview mode on battery **[verify its current name]**. Give Studio at least 4 GB of heap when a file has many previews.
- Studio's *Run preview on device* and the gutter icon launch `PreviewActivity` on the selected AVD or device (§4.6).
- The Pixel 9 Pro Fold AVD needs an **arm64-v8a** system image on Apple Silicon.

### 7.2 Roborazzi's reports and helpers

- Per module: `build/reports/roborazzi/index.html` after a compare or verify; `*_compare.png` (reference · diff · actual) and `*_actual.png` under `build/outputs/roborazzi/` (or the `compare.outputDir` after UT-2).
- The **Roborazzi IDE plugin** (JetBrains Marketplace) shows the images next to the test.
- `roborazzi.dumpUiTree` (a property in 1.75.0) writes a `.uitree.json` sidecar with each node's test tag and bounds, so an agent can *prove* a geometry claim ("the composer spans the full width at `fold-outer-524`") instead of asserting it from a picture. PROTOTYPE it with bead ML-6.
- `roborazzi.record.resizeScale=0.5` halves image sizes (`UX_TEST_PLAN.md` §4.4).

### 7.3 android-cli layout dump and uiautomator

- `android layout` (from the `android` CLI; `ANDROID_SKILLS_ASSESSMENT.md` §6.7) prints the UI tree as JSON with `text`, `contentDesc`, `interactions`, `state`, `bounds` and `--diff`. It reads the accessibility tree, so it works under FLAG_SECURE. It is a network-installed binary (`curl … | bash`): Mac only, optional.
- The zero-install equivalent: `adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml .agent-logs/ && adb shell rm /sdcard/ui.xml`.
- Compose test tags appear as `resource-id` only with `testTagsAsResourceId = true` in debug builds (UT-14).
- The android-cli *journey* format is the reference for `tools/ux/journeys/` (as bash functions, not XML).
- Only the owner and the hardware runner point either tool at a device.

### 7.4 Keeping the Fold environment frozen

`DEVICE_RUNNER.md` treats the Fold as a controlled measurement target: no package upgrades, no settings changes except those a claimed bead names, deviations reported rather than "fixed". For UX work that means:

- The baseline configuration the lab designs against: inner and outer at **330 dpi**, font scale 1.0, animations on, dark mode as the owner uses it, TalkBack off, the owner's keyboard, *Continue using apps on fold* as set, Skein's *Block screenshots* toggle as the owner left it.
- `tools/ux/fold-watch.sh` records all of it in `env.txt` at the start of every run and prints any drift from the previous run before doing anything (`UX_TEST_PLAN.md` §2.6).
- A run that needs a different setting (font scale 2.0, TalkBack on, remove animations) sets it, records it, and **restores it** at the end; the report lists both.
- FLAG_SECURE is the owner's choice; sessions never turn it off on their own, and the report records its state.
- Installs use the Mac's debug key; Skein is never uninstalled (the vault would be deleted).

### 7.5 Shell notes

The Mac's shell is zsh: `setopt pipefail`, quote `--tests` patterns, `rc=$?` not `status=$?`, arrays instead of word-splitting. Full table: `UX_TEST_PLAN.md` §13.4.

---

## 8. Implementation beads

For the coordinator to file under `skein-xtov`. Test-infrastructure beads (UT-0 … UT-20) are in `UX_TEST_PLAN.md` §14; these build the lab on top of them. Sizes: S ≤ ½ day, M ≤ 2 days, L > 2 days.

| # | Title | Scope | Size | Wave | Depends on |
|---|---|---|---|---|---|
| ML-1 | Skein multipreview annotations + `SkeinPreviewTheme` | The §2.2 annotations in the design-system module; a debug `SkeinPreviewTheme` (follows system dark mode, pins the clock and locale); a `:testing-ui` test that the annotation specs match `SkeinDevice` | S | 2 | UT-1 |
| ML-2 | Preview conventions and clean-up | Document §2.1 in the design-system README; move or delete the existing previews per §2.4 as each screen is redesigned (the shell's in Wave 3) | S | 2–3 | ML-1 |
| ML-3 | Layer A preview sets | One `<Surface>PreviewStates` + `<Surface>Previews` per destination, keyed by spec state ids, delivered **inside** each wave bead (chat 4, conversation lifecycle 5, knowledge 6, inspector 7, graph 8, models and settings 9); this bead tracks coverage of §2.3 | S (tracking) | 3–9 | ML-1, UT-4 |
| ML-4 | `PreviewImeInsets` prototype | A debug helper that dispatches IME insets in previews; confirm layoutlib and `PreviewActivity` honour it; drop it if they don't (the screenshot path stays authoritative) | S | 4 | ML-1 |
| ML-5 | Interactive lab harnesses | `ChatLabHarness` (real chat state holder + `InMemoryVaultRepository` + `ScenarioInferenceEngine(RealTime)`) in `feature/chat/src/debug`, runnable in Studio's interactive mode and through `PreviewActivity` on the AVD and the Fold; later `KnowledgeLabHarness` | M | 4 (chat), 6 (knowledge) | UT-4 |
| ML-6 | `tools/ux/shots` wrapper (+ UI-tree dump prototype) | The §4.2 subcommands, logging to `.agent-logs/ux/`, the record-scope guard; try `roborazzi.dumpUiTree` on one test | S | 2 | UT-2 |
| ML-7 | AVD helper for the owner | `tools/ux/avd.sh`: create the `pixel_9_pro_fold` arm64 AVD, boot, set 330 dpi, install `devDebug`, fold/unfold shortcuts, capture to `.agent-logs/` | S | 3 | — |
| ML-8 | PocketPal same-scene comparisons | `shell-drawer-open` and `chat-reasoning-live` at `fold-outer-524` next to the owner's PocketPal screenshots, for the owner's review | S | 4 | ML-3 (chat) |

No bead for Layer C (no `ux-lab/` now; §5.3) or Compose Desktop (REJECT; §6).
