# Roborazzi spike — JVM screenshots of the current UI (skein-xtov.9)

Bead `skein-xtov.9` of epic `skein-xtov` (UX overhaul). Authority:
`docs/research/SKEIN_UI_UX_OVERHAUL_PROMPT.md` §12 (Roborazzi), §37 Layer A/B
(fixture previews at device sizes), §39 (screenshot regression).

**Why this exists.** The app sets `FLAG_SECURE`, so device screenshots are
black by default. JVM screenshot tests (Robolectric native graphics +
Roborazzi) are the regression source of truth for the overhaul: every wave
records before/after images at the same device sizes. This spike proves the
toolchain works in this build and commits the **before** set
(`ux-baselines/before/`, 153 images, 12.2 MiB).

## 1. Version decision

**Roborazzi 1.75.0** (released 2026-09-21, the latest on Maven Central at the
time of the spike).

- AGP 9 support: `RoborazziPlugin` gained AGP 9.0 compatibility in #782
  (fixes #777, "recordRoborazziRelease can't be found" on AGP 9), Jan 2026.
  Works here with AGP 9.4.1, built-in Kotlin, Gradle 9.7.1, configuration
  cache on.
- Gradle 9 fix for the shared output directory race (#830, shipped in 1.65.0).
- 1.73.0: identical images skip the per-pixel comparison (fast verify).
- 1.74.0: popups inside dialogs are placed correctly by
  `captureScreenRoboImage`.
- 1.75.0's headline change (faster *generated* preview tests, `renderScale`)
  only affects `generateComposePreviewRobolectricTests`, which we do not use.
- Runtime deps: `roborazzi-core`, `roborazzi-painter`, `com.dropbox.differ`
  0.3.0, `androidx.test.espresso:espresso-core` 3.5.1. Roborazzi itself is
  built against older Compose/Robolectric (its own catalog: Compose 1.8,
  Robolectric 4.14, AGP 8.13); here Gradle resolves the project's Robolectric
  4.17 and Compose BOM 2026.09.00 and everything works — no version was
  forced or pinned for it.

Catalog entries (`gradle/libs.versions.toml`): version `roborazzi`, libraries
`roborazzi`, `roborazzi-compose`, plugin `roborazzi`
(`io.github.takahirom.roborazzi`). The root `build.gradle.kts` declares the
plugin `apply false` (one classloader, like the other plugins).

## 2. What is wired

| Module | Plugin | Test deps added | Other build change |
|---|---|---|---|
| `:feature:shell` | yes | roborazzi, roborazzi-compose, `project(":feature:timeline")` (test-only, so the shell renders the real timeline pane; timeline does not depend on shell → no cycle) | — |
| `:feature:chat` | yes | roborazzi, roborazzi-compose | — |
| `:feature:settings` | yes | roborazzi, roborazzi-compose | — |
| `:feature:editor` | yes | roborazzi, roborazzi-compose | — |
| `:feature:graph` | yes | roborazzi, roborazzi-compose | — |
| `:feature:timeline` | yes | robolectric, roborazzi, roborazzi-compose, ext-junit, compose-ui-test-junit4, activity-compose, `project(":feature:shell")` (for `SkeinTheme`) | `missingDimensionStrategy("distribution", "foss")` (the shell pulls in `:core:vault`'s flavour, same line every other feature module has), `isIncludeAndroidResources = true` |
| `:feature:models` | yes | roborazzi, roborazzi-compose, `project(":feature:shell")` | `missingDimensionStrategy("distribution", "foss")` |

No production code changed. `:app` untouched.

Test code lives in `src/test/kotlin/app/skein/feature/<module>/screenshots/`:

- `UxScreenshots.kt` — device table (`UxDevice`), `UxSpec` (device + dark +
  font scale), `UxDeviceRule`, `captureUx()`. **Identical copy in every
  module** (test source sets cannot share code without a new module). The
  follow-up is a small `:testing:screenshot` Android library (the Now in
  Android `core:screenshot-testing` pattern) once the overhaul adds more
  screenshot tests — not done here to keep the spike's build change minimal.
- One `*ScreenshotTest.kt` per module (+ `AuthScreenshotTest` and a fixture
  file in shell; a copy of that fixture in timeline).

How a test works:

- `ParameterizedRobolectricTestRunner`, `@Config(sdk = [34])` (Robolectric
  SDK 35+ needs Java 21), `@GraphicsMode(GraphicsMode.Mode.NATIVE)`.
- `UxDeviceRule` (rule `order = 0`) sets the Robolectric qualifiers,
  `+night`/`+notnight` and `RuntimeEnvironment.setFontScale` **before** the
  Compose rule (`order = 1`) launches its activity, so `LocalConfiguration`,
  window metrics and `WindowSizeClass` see the device from the first frame.
  `SkeinApp` computes its own `WindowSizeClass` from the real window; posture
  is pinned to `FoldPosture.Unknown`, which the shell lays out exactly like a
  flat (open) Fold — only tabletop changes the layout (`computeAdaptiveLayout`).
- Host activity: Roborazzi's `RoborazziActivity`
  (`Theme.Translucent.NoTitleBar.Fullscreen`, no action bar), registered with
  Robolectric's package manager by `registerRoborazziActivityToRobolectricIfNeeded()`
  — so modules without a debug `ComponentActivity` manifest entry (models)
  work too. The unlock/setup screens need a `FragmentActivity`, so
  `AuthScreenshotTest` registers its own `UxFragmentActivity`
  (`Theme.Material.NoActionBar`, the app's window theme).
- `composeRule.onRoot().captureUx(spec, "<screen>")` writes
  `build/outputs/roborazzi/<device>/<screen>[-font150][-dark].png`.

## 3. Default behaviour (confirmed)

`captureRoboImage` returns immediately unless a Roborazzi task type is set
(`if (!roborazziOptions.taskType.isEnabled()) return` in 1.75.0; the task
type comes from the `roborazzi.test.record|verify|compare` system properties,
which the Gradle plugin fills from the matching `-P` properties).

- `./gradlew check` / `testDebugUnitTest` with no property: the screenshot
  tests still run (they compose and lay out every screen, i.e. they are smoke
  tests) but write and compare nothing. Confirmed: a full `check` of the seven
  modules passed and wrote 0 images.
- `-Proborazzi.test.record=true`: writes/overwrites the images.
- `-Proborazzi.test.verify=true`: compares against the images already in
  `build/outputs/roborazzi` and **fails** on a difference (writes
  `*_compare.png` / `*_actual.png` next to them).
- `-Proborazzi.test.compare=true`: writes `*_compare.png` diffs, never fails.

So CI (`./gradlew ktlintCheck lint check`) cannot start failing on pixels.

## 4. Commands

`export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home` first.

```sh
# Record every module's screenshots from scratch.
./gradlew clearRoborazziDebug
./gradlew --no-build-cache recordRoborazziDebug          # = testDebugUnitTest -Proborazzi.test.record=true

# Verify (fails on any pixel difference) / compare (diff images, never fails).
./gradlew --no-build-cache verifyRoborazziDebug          # = testDebugUnitTest -Proborazzi.test.verify=true
./gradlew --no-build-cache compareRoborazziDebug         # = testDebugUnitTest -Proborazzi.test.compare=true

# Just one module / one class:
./gradlew --no-build-cache :feature:shell:testDebugUnitTest \
  --tests 'app.skein.feature.shell.screenshots.*' -Proborazzi.test.record=true

# Refresh the committed "before" set from the per-module outputs:
for m in shell chat settings editor graph timeline models; do
  cp -R feature/$m/build/outputs/roborazzi/ ux-baselines/before/
done
find ux-baselines/before \( -name '*_actual.png' -o -name '*_compare.png' \) -delete
```

**Build-cache caveat.** `org.gradle.caching=true` and the plugin keep a copy
of every image in `build/intermediates/roborazzi` and restore it into
`build/outputs/roborazzi` (`finalizeTestRoborazziDebug`). A record run whose
inputs match an earlier run is therefore `FROM-CACHE`/`UP-TO-DATE` and
restores that run's files — including stale `*_compare.png`s. For a trustworthy
record: `clearRoborazziDebug` (deletes outputs *and* intermediates) and
`--no-build-cache`.

**Comparing an overhaul wave against the committed before set.** The goldens
`verify` reads live in each module's (git-ignored) `build/outputs/roborazzi`.
To diff a branch against `ux-baselines/before/`, copy the before set into
each module's output dir (extra files are ignored — only captured paths are
compared) and run compare:

```sh
./gradlew clearRoborazziDebug
for m in shell chat settings editor graph timeline models; do
  mkdir -p feature/$m/build/outputs/roborazzi && cp -R ux-baselines/before/ feature/$m/build/outputs/roborazzi/
done
./gradlew --no-build-cache compareRoborazziDebug   # *_compare.png = before | diff | after
# or verifyRoborazziDebug to fail on the first changed screen
```

Checked during the spike: with the before set seeded into `:feature:shell`
and one golden swapped for another screen, `verifyRoborazziDebug` failed
exactly that one test (`landing[phone]`) and wrote its
`_actual`/`_compare` pair.

## 5. Device qualifiers

| Folder | Robolectric qualifiers | px (captured) | Source |
|---|---|---|---|
| `phone` | `w360dp-h800dp-port-xhdpi` | 720×1600 | Narrow phone, per the brief |
| `fold-outer` | `w411dp-h923dp-port-420dpi` | 1076×2420 | Cover panel 1080×2424 px (Google Store tech specs, 422 ppi). Its dp size under the owner's density override is **unmeasured** — 420 dpi is a coordinator-provided **provisional** assumption. Measure with `adb shell wm size; adb shell wm density` on the cover display and update `UxDevice.FOLD_OUTER`. |
| `fold-inner` | `w1006dp-h1043dp-port-330dpi` | 2072×2149 | Inner panel 2076×2152 px (measured on the owner's device; Google Store specs agree), physical density 390, **owner's forced density override 330** (measured) → 1006×1043 dp. |
| `fold-landscape` | `w1043dp-h1006dp-land-330dpi` | 2149×2072 | Same panel rotated. |
| `fold-inner-stock` | `w852dp-h883dp-port-390dpi` | 2074×2149 | Stock density 390: Android Studio's `pixel_9_pro_fold` hardware profile (AOSP `sdklib/devices/nexus.xml`: 2076×2152, 390dpi, folded 1080×2424) = Roborazzi's `RobolectricDeviceQualifiers.Pixel9ProFold` (`w852dp-h883dp-…-390dpi`) = Compose `Devices.PIXEL_9_PRO_FOLD` (`id:pixel_9_pro_fold`). Captured for `shell-landing` only. |

Captured bitmaps are a few px short of the panel (dp are integers; Robolectric
rounds). Robolectric draws no system bars and reports zero insets, so
`safeDrawing` padding is 0 in every capture — on the device the status bar and
navigation bar take space these images give to content.

Window size classes these produce: phone and cover are COMPACT (single pane,
`TimelineRail` as landing); inner portrait/landscape (1006/1043 dp) and the
stock inner (852 dp) are EXPANDED (dual pane: expanded timeline left, tabs
right). `fold-inner` at 330 dpi is therefore the owner's real layout.

## 6. What was captured

153 images, every one in light and dark (`-dark` suffix) at the four main
sizes unless noted.

| Screen | Name | How it is composed |
|---|---|---|
| App shell, launch state | `shell-landing` | `SkeinApp` wired like `MainActivity`: real `TimelineScreen` (expanded) / `TimelineRail` from a fixture vault (7 notes/chats at fixed timestamps), chip `qwen2.5-3b-instruct- · not loaded` (MainActivity's 20-char truncation of `qwen2.5-3b-instruct-abliterated-q3_k_m.gguf`). Also `fold-inner-stock` and `-font150` on fold-outer. |
| First run | `shell-empty` | Empty vault, chip `no model`. |
| Model chip, loaded | `shell-model-loaded` | Chip with the full GGUF filename, active glyph. |
| Drawer open | `shell-drawer-open` | Tap on "Open navigation drawer"; phone and fold-outer only. |
| Vault unlock | `vault-unlock` | `BiometricUnlockScreen` in `SkeinTheme` + `EdgeToEdgeSurface` (as `VaultGate`), waiting on the prompt. |
| Unlock error | `vault-unlock-error` | Unreadable envelope: the state with the reset affordance. |
| Vault setup | `vault-setup` | `VaultSetupScreen`, first run. |
| Chat, empty | `chat-empty` | Real `ChatScreen` (ViewModel, `SendPipeline`, bottom bar) over an empty chat. |
| Chat, long | `chat-long` | 8 persisted turns with citation records, a numbered list, a Kotlin code block, a task list; also `-font150` on fold-outer. |
| Chat, streaming | `chat-streaming` | Long chat + a turn whose engine streamed part of an answer and stalls (cancel button showing). |
| Context panel | `chat-context-open` | After a completed turn, "context" toggled open (two retrieved notes with scores). |
| Models | `models`, `models-empty` | `ModelsScreen` (as the `/models` overlay) with the long GGUF (default, loaded) and two others; empty list. |
| Settings | `settings` | `SettingsScreen` at shipped defaults (first screenful). |
| Note tab | `note`, `note-backlinks` | Real `NoteTab` (editor, frontmatter chip, wikilinks); backlinks drawer expanded with 3 linking notes. |
| Graph | `graph` | `GraphScreen` (as the overlay) over an 8-note wikilink neighbourhood. |
| Timeline | `timeline`, `timeline-empty` | `TimelineScreen` alone, `expanded` only on the dual-pane sizes. |

### Not captured, and why

- **Chat / note inside the shell** (command bar + tab strip + timeline pane
  around them): `SkeinApp`'s chat/note slots are filled by `:app`; only a
  module that sees shell + chat + editor + timeline can compose that, i.e.
  `:app` (out of scope) or a future screenshot module. Chat and note are
  captured full-window instead (what the tab content area renders, minus
  the ~110 dp of shell chrome above it).
- **Keyboard visible**: Robolectric shows no IME window and reports no `ime()`
  insets.
- **Real fold posture / hinge, tabletop, fold↔unfold transitions**: no
  `FoldingFeature` is simulated; posture is pinned (see §2).
- **System biometric prompt**: a system window; `vault-unlock` shows the
  screen behind it.
- **Below-the-fold content** (e.g. the rest of Settings, About/licenses),
  command palette / search results, share menu, onboarding, personas: not in
  this bead's list; each is a few lines in the existing test classes.
- **Delete-chat / delete-note confirmations**: those flows do not exist yet.

### Visible "before" problems (for the overhaul, not fixed here)

- Command bar collapses on phone and cover with a long model chip: the chip
  text has no width bound, the search field gets ~0 width, its placeholder
  wraps one character per line and the bar grows to half the screen
  (`phone|fold-outer/shell-landing*`, worse at font 1.5).
- Closed-Fold landing is the glyph-only `TimelineRail` stretched full screen
  (no titles) (`phone|fold-outer/shell-landing`).
- Dark theme: in the shell's expanded timeline pane the entry titles are
  near-black on near-black (`fold-inner|fold-landscape/shell-landing-dark`),
  while the same `TimelineScreen` on its own is fine (`*/timeline-dark`) — a
  content-colour inheritance problem in the pane host, not in the row.
- Models: the "· default" label wraps one character per line next to a long
  name (`phone/models`).
- Settings › Indexing: a row label wraps per character and overlaps its hint
  (`fold-outer/settings*`).
- Chat: an inline citation chip clips (`[1`) and swallows the following
  list line break (`fold-outer/chat-long`); while streaming, text only appears
  when a `[n]` closes or the turn ends (`CitationParser` buffers), so the
  streaming bubble stops mid-sentence (`*/chat-streaming`).
- Graph: labels run off-screen / overlap edges on narrow screens
  (`fold-outer/graph*`).

## 7. Determinism, fonts, flakiness

- **Repeatable**: recorded twice from scratch (second time `--offline`,
  `clearRoborazziDebug` + `--no-build-cache`): all 153 PNGs byte-identical.
  `verify` with `--offline` passed for all 7 modules. Robolectric's native
  runtime ships inside `org.robolectric:nativeruntime-dist-compat` (already a
  Robolectric 4.17 dependency) and the SDK 34 `android-all-instrumented` jar
  was already cached by the existing Robolectric tests — nothing is
  downloaded at test time.
- Sources of nondeterminism removed: relative times (fixed `now`/UTC zone
  and a fake clock in the fixtures), the note header's frontmatter **document
  id** (fixed ids — `InMemoryVaultRepository` otherwise mints random UUIDs),
  streaming timing (the capture waits for the exact text the parser has
  released; the engine then stalls forever).
- Fonts: the app's bundled IBM Plex Mono renders through native graphics
  exactly as on device. Emoji glyphs (📄 💬 ⏸) come from Robolectric's
  bundled Noto Color Emoji and may differ from GrapheneOS's font. The
  focused composer draws its caret (captured deterministically).
- Cross-OS: images were recorded on macOS arm64. Linux x86_64 native
  rendering has not been compared; expect anti-aliasing-level differences.
  Record and verify on the same OS, or give verify a tolerance
  (`RoborazziOptions(compareOptions = CompareOptions(changeThreshold = …))`).
- Speed: the 172 screenshot test cases (153 captures + 19 parameterisations
  skipped by `assume*`) run in ~12 s warm on the MacBook.

## 8. Image budget

12.2 MiB for 153 PNGs, full resolution everywhere: fold-inner 3.4 MiB (36),
fold-landscape 3.4 MiB (36), fold-outer 3.4 MiB (42), phone 1.9 MiB (38, at
xhdpi 720×1600), fold-inner-stock 154 KiB (1). Text-heavy Fold captures are
~100–160 KiB each. If later waves need room: drop dark variants of screens
with no colour change, capture phone at `mdpi`, or set
`roborazzi.record.resizeScale=0.5` for the phone only — not needed now.

## 9. CI recommendation

- **Now:** nothing to add. CI's `ktlintCheck lint check` job (ubuntu) already
  runs the screenshot tests as smoke tests (every screen composes at every
  size, light and dark) without writing or comparing images.
- **Do not run `verify` in CI yet.** The goldens are in git-ignored build
  dirs, were recorded on macOS, and the overhaul is about to change every
  screen on purpose.
- **Once a wave's "after" set is stable:** commit goldens per module
  (e.g. `feature/<m>/src/test/screenshots/`, via the plugin's `outputDir`),
  recorded on Linux (download them from a CI record run so the OS matches),
  and add a separate, initially non-blocking `screenshots` job on
  `ubuntu-latest` that runs `./gradlew --no-build-cache verifyRoborazziDebug`
  and uploads `feature/*/build/outputs/roborazzi` plus
  `feature/*/build/reports/roborazzi` as artifacts on failure. Make it
  blocking after a few green runs. Keep it out of the main `check` job so a
  pixel diff never hides a real test failure.

## 10. Dependency verification

`gradle/verification-metadata.xml` gained 28 components (sha256, generated by
`--write-verification-metadata sha256` over the record runs, then reviewed:
additions only): the Roborazzi artifacts and Gradle plugin marker, `differ`
0.3.0, `espresso-core`/`espresso-idling-resource` 3.5.1, `androidx.test:runner`
1.5.2, `kotlinx-io` 0.3.3, `kotlinx-serialization` 1.6.3 (plugin classpath),
`jspecify` 0.3.0, `kotlin-stdlib-common` 2.4.0 metadata, and the plugin's
report webjars (`webjars-locator-lite`, `materializecss`,
`material-design-icons`). None is OS-classified. Native graphics needs
`org.robolectric:nativeruntime` 4.17 and `nativeruntime-dist-compat` 1.0.19,
both already pinned; the dist-compat jar carries linux x86_64, mac
aarch64/x86_64 and windows natives in one unclassified artifact, so there is
no linux variant to add.

## 11. Every captured image

All paths are in the repository root (`ux-baselines/before/`); each module's
live copy is `feature/<module>/build/outputs/roborazzi/<device>/<file>`.

| Image | Module / test class | KiB |
|---|---|---|
| `ux-baselines/before/phone/chat-context-open-dark.png` | `:feature:chat` `ChatScreenshotTest` | 94 |
| `ux-baselines/before/phone/chat-context-open.png` | `:feature:chat` `ChatScreenshotTest` | 95 |
| `ux-baselines/before/phone/chat-empty-dark.png` | `:feature:chat` `ChatScreenshotTest` | 13 |
| `ux-baselines/before/phone/chat-empty.png` | `:feature:chat` `ChatScreenshotTest` | 13 |
| `ux-baselines/before/phone/chat-long-dark.png` | `:feature:chat` `ChatScreenshotTest` | 90 |
| `ux-baselines/before/phone/chat-long.png` | `:feature:chat` `ChatScreenshotTest` | 91 |
| `ux-baselines/before/phone/chat-streaming-dark.png` | `:feature:chat` `ChatScreenshotTest` | 89 |
| `ux-baselines/before/phone/chat-streaming.png` | `:feature:chat` `ChatScreenshotTest` | 90 |
| `ux-baselines/before/phone/graph-dark.png` | `:feature:graph` `GraphScreenshotTest` | 54 |
| `ux-baselines/before/phone/graph.png` | `:feature:graph` `GraphScreenshotTest` | 54 |
| `ux-baselines/before/phone/models-dark.png` | `:feature:models` `ModelsScreenshotTest` | 50 |
| `ux-baselines/before/phone/models-empty-dark.png` | `:feature:models` `ModelsScreenshotTest` | 16 |
| `ux-baselines/before/phone/models-empty.png` | `:feature:models` `ModelsScreenshotTest` | 16 |
| `ux-baselines/before/phone/models.png` | `:feature:models` `ModelsScreenshotTest` | 50 |
| `ux-baselines/before/phone/note-backlinks-dark.png` | `:feature:editor` `NoteTabScreenshotTest` | 95 |
| `ux-baselines/before/phone/note-backlinks.png` | `:feature:editor` `NoteTabScreenshotTest` | 94 |
| `ux-baselines/before/phone/note-dark.png` | `:feature:editor` `NoteTabScreenshotTest` | 78 |
| `ux-baselines/before/phone/note.png` | `:feature:editor` `NoteTabScreenshotTest` | 77 |
| `ux-baselines/before/phone/settings-dark.png` | `:feature:settings` `SettingsScreenshotTest` | 71 |
| `ux-baselines/before/phone/settings.png` | `:feature:settings` `SettingsScreenshotTest` | 70 |
| `ux-baselines/before/phone/shell-drawer-open-dark.png` | `:feature:shell` `ShellScreenshotTest` | 19 |
| `ux-baselines/before/phone/shell-drawer-open.png` | `:feature:shell` `ShellScreenshotTest` | 18 |
| `ux-baselines/before/phone/shell-empty-dark.png` | `:feature:shell` `ShellScreenshotTest` | 14 |
| `ux-baselines/before/phone/shell-empty.png` | `:feature:shell` `ShellScreenshotTest` | 15 |
| `ux-baselines/before/phone/shell-landing-dark.png` | `:feature:shell` `ShellScreenshotTest` | 20 |
| `ux-baselines/before/phone/shell-landing.png` | `:feature:shell` `ShellScreenshotTest` | 20 |
| `ux-baselines/before/phone/shell-model-loaded-dark.png` | `:feature:shell` `ShellScreenshotTest` | 20 |
| `ux-baselines/before/phone/shell-model-loaded.png` | `:feature:shell` `ShellScreenshotTest` | 20 |
| `ux-baselines/before/phone/timeline-dark.png` | `:feature:timeline` `TimelineScreenshotTest` | 105 |
| `ux-baselines/before/phone/timeline-empty-dark.png` | `:feature:timeline` `TimelineScreenshotTest` | 19 |
| `ux-baselines/before/phone/timeline-empty.png` | `:feature:timeline` `TimelineScreenshotTest` | 19 |
| `ux-baselines/before/phone/timeline.png` | `:feature:timeline` `TimelineScreenshotTest` | 104 |
| `ux-baselines/before/phone/vault-setup-dark.png` | `:feature:shell` `AuthScreenshotTest` | 64 |
| `ux-baselines/before/phone/vault-setup.png` | `:feature:shell` `AuthScreenshotTest` | 64 |
| `ux-baselines/before/phone/vault-unlock-dark.png` | `:feature:shell` `AuthScreenshotTest` | 12 |
| `ux-baselines/before/phone/vault-unlock-error-dark.png` | `:feature:shell` `AuthScreenshotTest` | 31 |
| `ux-baselines/before/phone/vault-unlock-error.png` | `:feature:shell` `AuthScreenshotTest` | 31 |
| `ux-baselines/before/phone/vault-unlock.png` | `:feature:shell` `AuthScreenshotTest` | 12 |
| `ux-baselines/before/fold-outer/chat-context-open-dark.png` | `:feature:chat` `ChatScreenshotTest` | 159 |
| `ux-baselines/before/fold-outer/chat-context-open.png` | `:feature:chat` `ChatScreenshotTest` | 160 |
| `ux-baselines/before/fold-outer/chat-empty-dark.png` | `:feature:chat` `ChatScreenshotTest` | 23 |
| `ux-baselines/before/fold-outer/chat-empty.png` | `:feature:chat` `ChatScreenshotTest` | 23 |
| `ux-baselines/before/fold-outer/chat-long-dark.png` | `:feature:chat` `ChatScreenshotTest` | 158 |
| `ux-baselines/before/fold-outer/chat-long-font150-dark.png` | `:feature:chat` `ChatScreenshotTest` | 173 |
| `ux-baselines/before/fold-outer/chat-long-font150.png` | `:feature:chat` `ChatScreenshotTest` | 175 |
| `ux-baselines/before/fold-outer/chat-long.png` | `:feature:chat` `ChatScreenshotTest` | 160 |
| `ux-baselines/before/fold-outer/chat-streaming-dark.png` | `:feature:chat` `ChatScreenshotTest` | 152 |
| `ux-baselines/before/fold-outer/chat-streaming.png` | `:feature:chat` `ChatScreenshotTest` | 154 |
| `ux-baselines/before/fold-outer/graph-dark.png` | `:feature:graph` `GraphScreenshotTest` | 87 |
| `ux-baselines/before/fold-outer/graph.png` | `:feature:graph` `GraphScreenshotTest` | 87 |
| `ux-baselines/before/fold-outer/models-dark.png` | `:feature:models` `ModelsScreenshotTest` | 74 |
| `ux-baselines/before/fold-outer/models-empty-dark.png` | `:feature:models` `ModelsScreenshotTest` | 25 |
| `ux-baselines/before/fold-outer/models-empty.png` | `:feature:models` `ModelsScreenshotTest` | 25 |
| `ux-baselines/before/fold-outer/models.png` | `:feature:models` `ModelsScreenshotTest` | 73 |
| `ux-baselines/before/fold-outer/note-backlinks-dark.png` | `:feature:editor` `NoteTabScreenshotTest` | 142 |
| `ux-baselines/before/fold-outer/note-backlinks.png` | `:feature:editor` `NoteTabScreenshotTest` | 141 |
| `ux-baselines/before/fold-outer/note-dark.png` | `:feature:editor` `NoteTabScreenshotTest` | 110 |
| `ux-baselines/before/fold-outer/note.png` | `:feature:editor` `NoteTabScreenshotTest` | 109 |
| `ux-baselines/before/fold-outer/settings-dark.png` | `:feature:settings` `SettingsScreenshotTest` | 113 |
| `ux-baselines/before/fold-outer/settings.png` | `:feature:settings` `SettingsScreenshotTest` | 113 |
| `ux-baselines/before/fold-outer/shell-drawer-open-dark.png` | `:feature:shell` `ShellScreenshotTest` | 40 |
| `ux-baselines/before/fold-outer/shell-drawer-open.png` | `:feature:shell` `ShellScreenshotTest` | 40 |
| `ux-baselines/before/fold-outer/shell-empty-dark.png` | `:feature:shell` `ShellScreenshotTest` | 23 |
| `ux-baselines/before/fold-outer/shell-empty.png` | `:feature:shell` `ShellScreenshotTest` | 23 |
| `ux-baselines/before/fold-outer/shell-landing-dark.png` | `:feature:shell` `ShellScreenshotTest` | 34 |
| `ux-baselines/before/fold-outer/shell-landing-font150-dark.png` | `:feature:shell` `ShellScreenshotTest` | 43 |
| `ux-baselines/before/fold-outer/shell-landing-font150.png` | `:feature:shell` `ShellScreenshotTest` | 43 |
| `ux-baselines/before/fold-outer/shell-landing.png` | `:feature:shell` `ShellScreenshotTest` | 34 |
| `ux-baselines/before/fold-outer/shell-model-loaded-dark.png` | `:feature:shell` `ShellScreenshotTest` | 34 |
| `ux-baselines/before/fold-outer/shell-model-loaded.png` | `:feature:shell` `ShellScreenshotTest` | 34 |
| `ux-baselines/before/fold-outer/timeline-dark.png` | `:feature:timeline` `TimelineScreenshotTest` | 156 |
| `ux-baselines/before/fold-outer/timeline-empty-dark.png` | `:feature:timeline` `TimelineScreenshotTest` | 31 |
| `ux-baselines/before/fold-outer/timeline-empty.png` | `:feature:timeline` `TimelineScreenshotTest` | 31 |
| `ux-baselines/before/fold-outer/timeline.png` | `:feature:timeline` `TimelineScreenshotTest` | 156 |
| `ux-baselines/before/fold-outer/vault-setup-dark.png` | `:feature:shell` `AuthScreenshotTest` | 91 |
| `ux-baselines/before/fold-outer/vault-setup.png` | `:feature:shell` `AuthScreenshotTest` | 91 |
| `ux-baselines/before/fold-outer/vault-unlock-dark.png` | `:feature:shell` `AuthScreenshotTest` | 22 |
| `ux-baselines/before/fold-outer/vault-unlock-error-dark.png` | `:feature:shell` `AuthScreenshotTest` | 45 |
| `ux-baselines/before/fold-outer/vault-unlock-error.png` | `:feature:shell` `AuthScreenshotTest` | 45 |
| `ux-baselines/before/fold-outer/vault-unlock.png` | `:feature:shell` `AuthScreenshotTest` | 22 |
| `ux-baselines/before/fold-inner/chat-context-open-dark.png` | `:feature:chat` `ChatScreenshotTest` | 172 |
| `ux-baselines/before/fold-inner/chat-context-open.png` | `:feature:chat` `ChatScreenshotTest` | 175 |
| `ux-baselines/before/fold-inner/chat-empty-dark.png` | `:feature:chat` `ChatScreenshotTest` | 27 |
| `ux-baselines/before/fold-inner/chat-empty.png` | `:feature:chat` `ChatScreenshotTest` | 27 |
| `ux-baselines/before/fold-inner/chat-long-dark.png` | `:feature:chat` `ChatScreenshotTest` | 149 |
| `ux-baselines/before/fold-inner/chat-long.png` | `:feature:chat` `ChatScreenshotTest` | 152 |
| `ux-baselines/before/fold-inner/chat-streaming-dark.png` | `:feature:chat` `ChatScreenshotTest` | 158 |
| `ux-baselines/before/fold-inner/chat-streaming.png` | `:feature:chat` `ChatScreenshotTest` | 162 |
| `ux-baselines/before/fold-inner/graph-dark.png` | `:feature:graph` `GraphScreenshotTest` | 126 |
| `ux-baselines/before/fold-inner/graph.png` | `:feature:graph` `GraphScreenshotTest` | 126 |
| `ux-baselines/before/fold-inner/models-dark.png` | `:feature:models` `ModelsScreenshotTest` | 66 |
| `ux-baselines/before/fold-inner/models-empty-dark.png` | `:feature:models` `ModelsScreenshotTest` | 29 |
| `ux-baselines/before/fold-inner/models-empty.png` | `:feature:models` `ModelsScreenshotTest` | 29 |
| `ux-baselines/before/fold-inner/models.png` | `:feature:models` `ModelsScreenshotTest` | 66 |
| `ux-baselines/before/fold-inner/note-backlinks-dark.png` | `:feature:editor` `NoteTabScreenshotTest` | 119 |
| `ux-baselines/before/fold-inner/note-backlinks.png` | `:feature:editor` `NoteTabScreenshotTest` | 118 |
| `ux-baselines/before/fold-inner/note-dark.png` | `:feature:editor` `NoteTabScreenshotTest` | 94 |
| `ux-baselines/before/fold-inner/note.png` | `:feature:editor` `NoteTabScreenshotTest` | 94 |
| `ux-baselines/before/fold-inner/settings-dark.png` | `:feature:settings` `SettingsScreenshotTest` | 134 |
| `ux-baselines/before/fold-inner/settings.png` | `:feature:settings` `SettingsScreenshotTest` | 133 |
| `ux-baselines/before/fold-inner/shell-empty-dark.png` | `:feature:shell` `ShellScreenshotTest` | 45 |
| `ux-baselines/before/fold-inner/shell-empty.png` | `:feature:shell` `ShellScreenshotTest` | 47 |
| `ux-baselines/before/fold-inner/shell-landing-dark.png` | `:feature:shell` `ShellScreenshotTest` | 137 |
| `ux-baselines/before/fold-inner/shell-landing.png` | `:feature:shell` `ShellScreenshotTest` | 142 |
| `ux-baselines/before/fold-inner/shell-model-loaded-dark.png` | `:feature:shell` `ShellScreenshotTest` | 137 |
| `ux-baselines/before/fold-inner/shell-model-loaded.png` | `:feature:shell` `ShellScreenshotTest` | 142 |
| `ux-baselines/before/fold-inner/timeline-dark.png` | `:feature:timeline` `TimelineScreenshotTest` | 131 |
| `ux-baselines/before/fold-inner/timeline-empty-dark.png` | `:feature:timeline` `TimelineScreenshotTest` | 34 |
| `ux-baselines/before/fold-inner/timeline-empty.png` | `:feature:timeline` `TimelineScreenshotTest` | 34 |
| `ux-baselines/before/fold-inner/timeline.png` | `:feature:timeline` `TimelineScreenshotTest` | 130 |
| `ux-baselines/before/fold-inner/vault-setup-dark.png` | `:feature:shell` `AuthScreenshotTest` | 79 |
| `ux-baselines/before/fold-inner/vault-setup.png` | `:feature:shell` `AuthScreenshotTest` | 79 |
| `ux-baselines/before/fold-inner/vault-unlock-dark.png` | `:feature:shell` `AuthScreenshotTest` | 26 |
| `ux-baselines/before/fold-inner/vault-unlock-error-dark.png` | `:feature:shell` `AuthScreenshotTest` | 42 |
| `ux-baselines/before/fold-inner/vault-unlock-error.png` | `:feature:shell` `AuthScreenshotTest` | 42 |
| `ux-baselines/before/fold-inner/vault-unlock.png` | `:feature:shell` `AuthScreenshotTest` | 26 |
| `ux-baselines/before/fold-landscape/chat-context-open-dark.png` | `:feature:chat` `ChatScreenshotTest` | 169 |
| `ux-baselines/before/fold-landscape/chat-context-open.png` | `:feature:chat` `ChatScreenshotTest` | 172 |
| `ux-baselines/before/fold-landscape/chat-empty-dark.png` | `:feature:chat` `ChatScreenshotTest` | 28 |
| `ux-baselines/before/fold-landscape/chat-empty.png` | `:feature:chat` `ChatScreenshotTest` | 27 |
| `ux-baselines/before/fold-landscape/chat-long-dark.png` | `:feature:chat` `ChatScreenshotTest` | 149 |
| `ux-baselines/before/fold-landscape/chat-long.png` | `:feature:chat` `ChatScreenshotTest` | 152 |
| `ux-baselines/before/fold-landscape/chat-streaming-dark.png` | `:feature:chat` `ChatScreenshotTest` | 159 |
| `ux-baselines/before/fold-landscape/chat-streaming.png` | `:feature:chat` `ChatScreenshotTest` | 162 |
| `ux-baselines/before/fold-landscape/graph-dark.png` | `:feature:graph` `GraphScreenshotTest` | 128 |
| `ux-baselines/before/fold-landscape/graph.png` | `:feature:graph` `GraphScreenshotTest` | 128 |
| `ux-baselines/before/fold-landscape/models-dark.png` | `:feature:models` `ModelsScreenshotTest` | 66 |
| `ux-baselines/before/fold-landscape/models-empty-dark.png` | `:feature:models` `ModelsScreenshotTest` | 30 |
| `ux-baselines/before/fold-landscape/models-empty.png` | `:feature:models` `ModelsScreenshotTest` | 30 |
| `ux-baselines/before/fold-landscape/models.png` | `:feature:models` `ModelsScreenshotTest` | 66 |
| `ux-baselines/before/fold-landscape/note-backlinks-dark.png` | `:feature:editor` `NoteTabScreenshotTest` | 119 |
| `ux-baselines/before/fold-landscape/note-backlinks.png` | `:feature:editor` `NoteTabScreenshotTest` | 119 |
| `ux-baselines/before/fold-landscape/note-dark.png` | `:feature:editor` `NoteTabScreenshotTest` | 94 |
| `ux-baselines/before/fold-landscape/note.png` | `:feature:editor` `NoteTabScreenshotTest` | 94 |
| `ux-baselines/before/fold-landscape/settings-dark.png` | `:feature:settings` `SettingsScreenshotTest` | 132 |
| `ux-baselines/before/fold-landscape/settings.png` | `:feature:settings` `SettingsScreenshotTest` | 131 |
| `ux-baselines/before/fold-landscape/shell-empty-dark.png` | `:feature:shell` `ShellScreenshotTest` | 45 |
| `ux-baselines/before/fold-landscape/shell-empty.png` | `:feature:shell` `ShellScreenshotTest` | 46 |
| `ux-baselines/before/fold-landscape/shell-landing-dark.png` | `:feature:shell` `ShellScreenshotTest` | 137 |
| `ux-baselines/before/fold-landscape/shell-landing.png` | `:feature:shell` `ShellScreenshotTest` | 143 |
| `ux-baselines/before/fold-landscape/shell-model-loaded-dark.png` | `:feature:shell` `ShellScreenshotTest` | 138 |
| `ux-baselines/before/fold-landscape/shell-model-loaded.png` | `:feature:shell` `ShellScreenshotTest` | 143 |
| `ux-baselines/before/fold-landscape/timeline-dark.png` | `:feature:timeline` `TimelineScreenshotTest` | 131 |
| `ux-baselines/before/fold-landscape/timeline-empty-dark.png` | `:feature:timeline` `TimelineScreenshotTest` | 34 |
| `ux-baselines/before/fold-landscape/timeline-empty.png` | `:feature:timeline` `TimelineScreenshotTest` | 34 |
| `ux-baselines/before/fold-landscape/timeline.png` | `:feature:timeline` `TimelineScreenshotTest` | 130 |
| `ux-baselines/before/fold-landscape/vault-setup-dark.png` | `:feature:shell` `AuthScreenshotTest` | 80 |
| `ux-baselines/before/fold-landscape/vault-setup.png` | `:feature:shell` `AuthScreenshotTest` | 79 |
| `ux-baselines/before/fold-landscape/vault-unlock-dark.png` | `:feature:shell` `AuthScreenshotTest` | 27 |
| `ux-baselines/before/fold-landscape/vault-unlock-error-dark.png` | `:feature:shell` `AuthScreenshotTest` | 42 |
| `ux-baselines/before/fold-landscape/vault-unlock-error.png` | `:feature:shell` `AuthScreenshotTest` | 42 |
| `ux-baselines/before/fold-landscape/vault-unlock.png` | `:feature:shell` `AuthScreenshotTest` | 27 |
| `ux-baselines/before/fold-inner-stock/shell-landing.png` | `:feature:shell` `ShellScreenshotTest` | 154 |
