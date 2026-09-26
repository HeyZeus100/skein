# Android Agent-Skills Assessment

Bead `skein-xtov.8` · epic `skein-xtov` (UI/UX overhaul) · Wave 0 deliverable 4 of 13
Authority: `docs/research/SKEIN_UI_UX_OVERHAUL_PROMPT.md` §9 (read with §1–3, §22–25, §39–41, §48)
Assessed: 2026-09-26 · Status: **assessment only.** No skill has been installed, vendored, or adopted.

---

## 0. Verdict in brief

- **The priority skill, `jetpack-compose/adaptive`, can't be loaded into Skein agents as written.** It has a hard prerequisite of Jetpack Navigation 3. It also states "You must use the Navigation 3 `SceneStrategy` approach … Do not use `ListDetailPaneScaffold` or `SupportingPaneScaffold`." Skein has no navigation library at all: it runs a hand-rolled `NavState` + `TabsState` + `AdaptivePaneHost` shell. A verbatim load would push any UI agent into an unplanned navigation rewrite. **Classification: USE AS REFERENCE.** Five of its rules should be excerpted into briefs now. It becomes USE NOW, vendored, only if the Wave 1 information-architecture decision adopts Navigation 3.
- **Adopting Navigation 3 is a real Wave 1 decision, not a given.** All the pieces exist at compatible versions: `adaptive-navigation3` 1.3.0 is in Skein's own Compose BOM, and its `ListDetailSceneStrategy` has hinge-aware directives and drag-to-resize panes. Two Skein features still don't fit the recipes cleanly: tabs that move between split panes (the Nav3 guide lists "shared destinations" as unsupported) and the 30 % timeline pane.
- **USE NOW (3 skills):**
  - `system/edge-to-edge`: IME and inset checklist for the composer and lists; no dependencies.
  - `navigation/navigation-event`: back handling for overlays and panes. Skein has no explicit back handlers today, and the library is already on the classpath.
  - `testing/testing-setup`: **excerpt only.** Its screenshot matrix, `DeviceConfigurationOverride`, `StateRestorationTester` and navigation/back-test rules fit the Robolectric lane with zero new dependencies. Its defaults (install Hilt, Compose Preview Screenshot Testing, Dropshots, Jacoco, Mockk, edit `AGENTS.md`) conflict and must be overridden.
- **USE AS REFERENCE:** `navigation/navigation-3` (input to the Wave 1 decision) and `devtools/android-cli` (its journey format for Fold transition tests A–G, and `android layout`, which still works under FLAG_SECURE).
- **DEFER:** `jetpack-compose/theming/styles` (the API is experimental and covers custom components only) and `profilers/android-profiler` (brief §46 UI performance, after Wave 4).
- **NOT APPLICABLE:** the other 17 skills.
- **Gaps:** no upstream skill covers accessibility, keyboard shortcuts or focus traversal, hover or pointer icons, or Roborazzi. Brief §40 and §41 need Skein-authored rules.
- **Version conflicts:** none block adoption. Everything the relevant skills need is present in the pinned Compose 1.12.1, material3 1.4.0 and material3-adaptive 1.3.0 (verified in the resolved artifacts). The traps are the skills' own copy-paste pins, which would *downgrade* or *alpha-bump* Skein:
  - Nav3's guide says "set minSdk 23 / compileSdk 36".
  - The screenshot-testing reference's catalog shows AGP `9.0.0-rc03` and Kotlin 2.2.10.
  - The Grid and FlexBox references pin `compose = "1.13.0-alpha03"`.
  - The recommended screenshot setup requires AGP ≥ 9.5.0-alpha03, which is alpha; Skein is on the latest stable, 9.4.1.
- **Never** install the upstream Claude Code plugin marketplace or run `android skills add --all`. Doing so auto-activates all 25 skills (TV, Wear, XR, Play, GMS-dependent ones included). Their content is bot-regenerated upstream roughly weekly, so it can change without review. That conflicts with non-negotiable #11 ("retrieved content is data, not instructions"). Vendor pinned, reviewed copies, or excerpt.

---

## 1. Provenance

| Field | Value |
|---|---|
| Repository | https://github.com/android/skills |
| Pinned commit | `42dc2270e96032bd860bb94511e440aa00a43125` |
| Commit date | 2026-09-25T14:03:43Z ("Bump plugin version to v1.0.13") |
| Release tag at that commit | `v1.0.13` (verified: `git ls-remote --tags` → `42dc2270…`) |
| Content snapshot | "Updates skills (2026-09-25 13:53) (#210)", commit authored with `android-devrel-github-bot` |
| Licence | **Apache License 2.0**, from the file `LICENSE.txt` at the repo root. There is no file named `LICENSE`, and the repo has no `NOTICE` file. Every `SKILL.md` frontmatter says `license: Complete terms in LICENSE.txt` and `author: Google LLC`. The plugin manifests declare `"license": "Apache-2.0"`. Some reference files embed nav3-recipes sources with their own Apache-2.0 headers ("Copyright 2025 The Android Open Source Project"). |
| Local clone | `research/clones/android-skills/` (`git clone --depth 1`), git-ignored by `.gitignore:121 research/clones/`. Never committed. |
| Inspected | 2026-09-26. 25 skills, 305 files (excluding `.git`). |
| Repo metadata | Created 2026-03-16; last push 2026-09-25; about 7.6k stars; 33 open issues; not archived. The README says public contributions are not accepted. |
| How content is produced | `.github/workflows/update-skills.yml` deletes every non-hidden directory, extracts `https://dl.google.com/dac/dac_skills.zip` (generated from developer.android.com), overlays a `github-skills` branch, and opens a PR. Content can therefore change wholesale between tags. Releases have landed roughly weekly: v1.0.11 on 09-07, v1.0.12 on 09-15, v1.0.13 on 09-25. **Always pin by SHA or tag; never link `main`.** |
| Distribution surfaces | `.claude-plugin/marketplace.json` (a Claude Code plugin marketplace listing all 25 skills, `"strict": false`), `.codex-plugin/plugin.json`, `.agents/plugins/marketplace.json`, and the Android CLI (`android skills add <id>`). |

### Skein baseline used for every cross-check

Sources: `gradle/libs.versions.toml`, `gradle/verification-metadata.xml` (resolved versions), the Compose BOM POM in the local Gradle cache, and module build files.

| Area | Skein today |
|---|---|
| Build | AGP **9.4.1** (built-in Kotlin, no `kotlin-android` plugin), Gradle 9.7.1, Kotlin 2.4.20, KSP 2.3.12, configuration cache on |
| SDKs | `compileSdk 37`, `minSdk 30`, `targetSdk 37` |
| Compose | BOM **2026.09.00**, which resolves ui / foundation / foundation-layout / runtime to **1.12.1**. `material3` **1.4.0** and `material3-adaptive` (`adaptive`) **1.3.0** are pinned explicitly and match the BOM. The BOM also carries `adaptive-layout`, `adaptive-navigation` and `adaptive-navigation3` 1.3.0 and `material3-adaptive-navigation-suite` 1.4.0, none of which Skein resolves today. The catalog alias `material3-adaptive-layout` exists but no module uses it. |
| Window / activity | `androidx.window` 1.5.1, `activity-compose` 1.13.0, lifecycle 2.11.0, `navigationevent` / `navigationevent-compose` **1.0.0** (transitive via activity) |
| Navigation | **None.** `feature/shell/.../nav/NavState.kt` (enum `Destination` + drawer + query), `tabs/TabsState`, and `split/SplitCoordinator`, switched in `SkeinApp.kt` (441 lines) |
| Adaptive shell | `feature/shell/src/main/kotlin/app/skein/feature/shell/layout/` (8 files, 789 lines): `currentWindowAdaptiveInfoV2()` + `WindowSizeClass.isWidthAtLeastBreakpoint` (600/840 dp), `FoldPosture` from `WindowInfoTracker`/`FoldingFeature` (tabletop forces a single pane), `AdaptivePaneHost` (30 % timeline weight, 40 dp `IconRail`), `SplitHost` (drag divider, 0.25–0.75, `rememberSaveable`), `EdgeToEdgeSurface` (`safeDrawing`, which includes the IME) |
| Activity | `MainActivity : FragmentActivity` (BiometricPrompt); `ComponentActivity.enableEdgeToEdge(statusBarStyle, navigationBarStyle)`; **FLAG_SECURE default-on**, with a live Settings toggle. The manifest declares **no** `android:configChanges`, so fold/unfold recreates the activity; **no** `windowSoftInputMode`; **no** `enableOnBackInvokedCallback`. |
| DI | **None by design** (`SkeinApplication` KDoc: "there is no DI"); fakes live in `:testing` |
| Tests | JUnit4; Robolectric **4.17** with `@Config(sdk = [34])` mandatory (Java 17 toolchain; SDK 35+ jars need Java 21, per bd memory `robolectric-sdk37-needs-java21`); Compose UI test 1.12.1; `@Config(qualifiers = "w400dp-h800dp")` already used; `:feature:shell` ships a debug-only `ComponentActivity` manifest because `ui-test-manifest` does not merge into library modules (`docs/TESTING.md`) |
| Screenshot testing | **None yet** (brief §12/§39 prefers evaluating Roborazzi) |
| Guards | `checkDependencyGuards` denies `com.google.android.gms`, `com.google.firebase`, `com.google.android.play` and `com.google.mlkit`; `LicenseAudit` allows only permissive licences for the foss runtime; `verification-metadata.xml` has `verify-metadata=true`, so every new artifact needs a checksum entry |
| Distribution | Obtainium, Accrescent and F-Droid; **not** the Play Store; "No Google Play Services dependency" (README) |

Upstream latest versions, from Google Maven and Maven Central metadata on 2026-09-26: material3-adaptive 1.3.0 stable (1.4.0-alpha02 exists); material3 1.4.0 stable (1.5.0-alpha29); navigation3 1.2.0 stable; AGP 9.4.1 stable (9.5.0-alpha07 latest alpha); Compose Preview Screenshot Testing plugin 0.0.1-alpha16; Roborazzi 1.75.0; Paparazzi 2.0.0-alpha05; Dropshots 0.6.0; navigationevent-compose 1.2.0-rc01. **Skein is already on the latest stable release of every Compose / adaptive / AGP artifact the relevant skills touch.**

---

## 2. How to read the classifications

| Classification | Meaning here |
|---|---|
| **USE NOW** | Wire into agent instructions at the next wave that touches the area, as a pinned vendored copy, a pinned link, or excerpted rules, with Skein overrides for its conflicting steps. |
| **USE AS REFERENCE** | Read by the orchestrator or architect (Opus / Fable) when making a specific decision. Not loaded into implementers by default; individual rules may still be excerpted. |
| **DEFER** | Potentially useful later. Blocked by API maturity, a pending decision, or wave timing. |
| **NOT APPLICABLE** | No fit with Skein's product, form factor or constraints. |

Cost: **S** = up to 1 agent bead (hours). **M** = 2–3 beads. **L** = 4+ beads or an Opus design spike first.

---

## 3. Summary table: all 25 skills

| Skill | Path | Classification | Cost | Deps | Agent-instructions? | One-line rationale |
|---|---|---|---|---|---|---|
| adaptive **(priority)** | `jetpack-compose/adaptive` | **USE AS REFERENCE** (promote to USE NOW if Wave 1 adopts Nav3) | S (excerpts) / L (verbatim) | Excerpts: none. Verbatim: `navigation3-runtime`/`-ui` 1.2.0, `adaptive-navigation3` 1.3.0, `material3-adaptive-navigation-suite` 1.4.0, screenshot plugin 0.0.1-alpha16 | Excerpt 5 rules into UI-impl / test / review briefs now; pinned link for Wave 1/3 architects; vendor only if Nav3 is adopted | Right patterns (pane scenes, hinge-aware directive, form-factor previews, never self-update baselines), but hard-wired to Nav3 and a Material nav bar Skein doesn't have |
| navigation-3 | `navigation/navigation-3` | **USE AS REFERENCE** (Wave 1 decision input) | L if adopted | `navigation3-runtime`/`-ui` 1.2.0 (+ optional `lifecycle-viewmodel-navigation3` 2.11.0); kotlinx-serialization plugin (already in catalog) applied to `:feature:shell` | Pinned link in the Wave 1 IA spike brief; use the recipes, not the Nav2→Nav3 migration guide | Nav3 scenes map neatly to NAVIGATION \| WORKSPACE \| CONTEXT, but adopting it is an architecture decision and its guide assumes a Nav2 app |
| testing-setup | `testing/testing-setup` | **USE NOW (excerpt only)** | S (excerpt) / M (matrix infra) | Excerpt: none (`DeviceConfigurationOverride` and `StateRestorationTester` ship in ui-test 1.12.1). Verbatim: Hilt, Compose Preview Screenshot Testing, Dropshots, Jacoco, Mockk | Excerpt Steps 8–11 into the test-role brief and `AGENTS.md`; **never load verbatim** | The screenshot matrix, config overrides, state-restoration and back-test rules fit Skein; its DI and tool defaults contradict Skein |
| edge-to-edge | `system/edge-to-edge` | **USE NOW** | S | None (`fitInside` / `WindowInsetsRulers` are in Compose 1.12.1) | Vendored pinned copy + 3 Skein overrides; load for UI-impl and review in Waves 2–7 | Composer / IME / list-inset checklist directly serves chat and fold Test D; Skein is already edge-to-edge |
| navigation-event | `navigation/navigation-event` | **USE NOW** (from Wave 3) | S–M | None new (`navigationevent-compose` 1.0.0 already resolved; declare it directly, do not bump) | Vendored pinned copy + override "if Nav3 is adopted, destinations use `NavDisplay` back"; UI-impl and review | Skein has no explicit back handlers; palette, sheets, panes and split need correct back plus predictive back for brief §24 / Test G |
| styles | `jetpack-compose/theming/styles` | **DEFER** | M–L | None (experimental opt-in flag only) | No (revisit when the Styles API is stable) | Experimental; custom components only (not Material); Wave 2 shouldn't build the design system on it |
| android-cli | `devtools/android-cli` | **USE AS REFERENCE** (hardware runner, Mac UX lab) | S | Host tool via `curl … \| bash`; network features (`docs`, `version-lookup`, `skills`) | Journey XML format → hardware-runner brief for Tests A–G; not for subagents (non-hardware agents never `adb`) | `android layout` (accessibility tree) works under FLAG_SECURE; `screen capture` returns black |
| android-profiler | `profilers/android-profiler` | **DEFER** | M | Host Python; downloads `trace_processor_shell` v57.2 from `commondatastorage.googleapis.com` at run time | Not now; coordinator / hardware runner for brief §46 later | UI jank / startup work belongs after Wave 4, and it records on the serial Fold |
| agp-9-upgrade | `build-system/agp/agp-9-upgrade` | NOT APPLICABLE | — | — | No | Skein is already on AGP 9.4.1 with built-in Kotlin and KSP 2.3.12 (≥ 2.3.6); only its Paparazzi–Gradle 9 note matters (§6.9) |
| migrate-xml-views-to-jetpack-compose | `jetpack-compose/migration/…` | NOT APPLICABLE | — | — | No | Skein is 100 % Compose (no `res/layout` XML) |
| camerax | `camera/camerax` | NOT APPLICABLE | — | — | No | No camera feature; its `foldables.md` is viewfinder-specific, and posture detection already exists in `FoldPosture.kt` |
| appfunctions | `device-ai/appfunctions` | NOT APPLICABLE | — | — | No | Exposes app actions to system agents; out of UX scope and at odds with the vault privacy model and non-negotiable #12 (needs a security decision, not a UX one) |
| ml-kit-genai-prompt-api | `device-ai/ml-kit-genai-prompt-api` | NOT APPLICABLE | — | — | No | `com.google.mlkit` is denied by `checkDependencyGuards`; Gemini Nano / AICore isn't Skein's inference path |
| restore-credentials | `identity/restore-credentials` | NOT APPLICABLE | — | — | No | Credential Manager restore keys for account sign-in; Skein has no accounts |
| verified-email | `identity/verified-email` | NOT APPLICABLE | — | — | No | Email verification through Google-issued credentials; no accounts and no network |
| media3-cast-integration | `media/media3-cast-integration` | NOT APPLICABLE | — | — | No | No media playback; Cast depends on Play services (denied) |
| engage-sdk-integration | `play/engage-sdk-integration` | NOT APPLICABLE | — | — | No | Play Engage SDK; Skein is not on Play and `com.google.android.play` is denied |
| play-billing-library-version-upgrade | `play/play-billing-…` | NOT APPLICABLE | — | — | No | No billing; Play-only |
| play-policy-insights | `play/play-policy-insights` | NOT APPLICABLE | — | — | No | Play Store policy audit (its Python scripts scrape `play.google.com`); Skein is not on Play |
| r8-analyzer | `performance/r8-analyzer` | NOT APPLICABLE (to this epic) | — | — | No | Release-size keep-rule tuning is build/release work, not UX |
| android-intent-security | `security/android-intent-security` | NOT APPLICABLE (to this epic) | — | — | No | Manifest / Intent audit; belongs to security review, outside the UX overhaul |
| android-permissions-security | `security/android-permissions-security` | NOT APPLICABLE (to this epic) | — | — | No | Permissions / IPC audit; security review, not UX (Skein's own manifest guards already cover the hard rules) |
| leanback-to-compose-tv-migration | `tv/…` | NOT APPLICABLE | — | — | No | TV form factor (its D-pad focus guidance isn't phone/Fold keyboard guidance) |
| wear-compose-m3 | `wear/wear-compose-m3` | NOT APPLICABLE | — | — | No | Wear OS form factor |
| display-glasses-with-jetpack-compose-glimmer | `xr/…` | NOT APPLICABLE | — | — | No | XR glasses form factor |

---

## 4. Coverage of the brief §9 topic list

| §9 topic | Skill(s) that cover it | What is covered | Gap Skein must fill itself |
|---|---|---|---|
| Jetpack Compose | adaptive, edge-to-edge, navigation-event, styles | Adaptive layout, insets, back, styling. The README says Google deliberately doesn't write skills for "basic Jetpack Compose best practices" | Everyday Compose conventions stay in Skein's own briefs |
| Adaptive layouts | adaptive (+ navigation-3 scenes) | Window size classes, `NavigationSuiteScaffold`, list-detail and supporting-pane scenes, adaptive columns, Grid / FlexBox, `mediaQuery` | Skein's 30 % timeline, split view, closed-fold drawer IA |
| Foldables | adaptive (`mediaQuery { windowPosture }`, the hinge-aware directive via the Nav3 recipe) | Posture (Tabletop / Book / Flat) | Live fold/unfold state continuity (brief §24) is **not** covered beyond "verify state restoration" (testing-setup Step 9) |
| Navigation | navigation-3, navigation-event | Back stacks, scenes, predictive back, dispatcher scoping | Tabs-in-split modelling |
| Accessibility | **none dedicated** | testing-setup: font scale 1.5 screenshots. adaptive's `debug.md`: Android Studio Compose UI Check + Layout Inspector semantics | **Everything in brief §40**: touch targets, labels for Skein's Unicode glyph icons (`≡ ◐ ✦ ◈ ⚹`), focus order, contrast (Skein already has `WcagContrast.kt`), TalkBack |
| Testing | testing-setup | Strategy, Robolectric UI tests, config overrides, state restoration, navigation tests | — |
| Screenshot testing | testing-setup, adaptive Step 1 | Size / theme / font-scale matrix; Compose Preview Screenshot Testing; Dropshots on device | **Roborazzi is only named, never set up**; nothing about FLAG_SECURE |
| Large-screen layouts | adaptive | As above | — |
| Keyboard input | adaptive `mediaQuery { keyboardKind }` only | Detect Physical / Virtual / None | **Shortcuts, `onKeyEvent` conventions, focus traversal, Keyboard Shortcuts Helper, command-palette keys (brief §41/§42)** |
| Pointer input | adaptive `mediaQuery { pointerPrecision }` only | Detect Fine / Coarse / Blunt; larger targets for Blunt | **Hover states, `pointerHoverIcon`, right-click / context menus, stylus** |

---

## 5. Version cross-check: skill assumptions vs Skein

| Skill assumption | Source | Skein reality | Verdict |
|---|---|---|---|
| App uses **Navigation 3** | adaptive "Prerequisites" | No navigation library | **Conflict**: architectural decision, Wave 1 (§6.2) |
| `adaptive-navigation3` for list-detail / supporting-pane scenes | adaptive Step 3 | BOM 2026.09.00 carries `adaptive-navigation3` 1.3.0, the same version as Skein's `material3-adaptive`. Its POM needs `navigation3-ui` ≥ 1.0.0, `navigationevent-compose` 1.0.1 and `activity-compose` 1.12.2 | Compatible; adoption would lift `navigationevent-compose` 1.0.0 → 1.0.1 |
| Nav3 core **1.2.0** | navigation-3 migration guide | Not present; 1.2.0 is the latest stable | Compatible. `lifecycle-viewmodel-navigation3` 2.11.0 matches Skein's lifecycle 2.11.0 |
| "Update `minSdk` to 23 and `compileSdk` to 36" | navigation-3 migration guide, Step 1 | minSdk 30, compileSdk 37 | **Trap: never lower.** Nav3 needs compileSdk ≥ 36 (satisfied) |
| `compileSdk` ≥ 36 | navigation-event | 37 | OK |
| `NavigationSuiteScaffold` | adaptive Step 2 | `material3-adaptive-navigation-suite` 1.4.0 is in the BOM but not resolved | Compatible version-wise; IA fit is questionable (§6.1) |
| `currentWindowAdaptiveInfoV2()` | adaptive / Nav3 recipes | Already used by `AdaptivePaneHost` and `SkeinApp` | Aligned |
| Grid / FlexBox / MediaQuery "available from Compose **1.11.0-beta01**" | adaptive SKILL.md | Present in Skein's resolved **1.12.1**: `GridKt` + `ExperimentalGridApi`, `FlexBoxKt` + `ExperimentalFlexBoxApi` (foundation-layout), and `MediaQueryKt` / `UiMediaScope` / `ComposeUiFlags.isMediaQueryIntegrationEnabled` (ui) | Usable without any bump, but **experimental** |
| `compose = "1.13.0-alpha03"` | adaptive's Grid and FlexBox `get-started.md` | 1.12.1 via BOM | **Trap:** copying it would put an alpha outside the BOM. Contradicts SKILL.md's own "1.11.0-beta01" |
| `Modifier.fitInside(WindowInsetsRulers.Ime.current)` | edge-to-edge | `WindowInsetsRulers` (ui) and `fitInside` (foundation-layout `RulerAlignmentKt`) present in 1.12.1 | Compatible |
| Styles API needs `compileSdk` 37 and foundation ≥ 1.12.0-alpha01 or BOM ≥ 2026.04.01, "requires updating to alpha version of Compose" | styles | compileSdk 37; foundation 1.12.1 contains `androidx.compose.foundation.style.Style` + `ExperimentalFoundationStyleApi` | Requirements met; **no alpha needed** (the skill's warning is stale); still experimental |
| Compose Preview Screenshot Testing via **AGP test suites** (recommended) needs **AGP ≥ 9.5.0-alpha03** | testing-setup | AGP 9.4.1 (latest stable) | **Conflict**: only the deprecated standalone plugin (`com.android.compose.screenshot` 0.0.1-alpha16) works on 9.4.1; the skill says not to upgrade AGP for this |
| Screenshot catalog example `agp = "9.0.0-rc03"`, `kotlin = "2.2.10"` | testing-setup `compose-screenshot-testing.md` | AGP 9.4.1, Kotlin 2.4.20 | **Trap:** copying it would downgrade |
| Paparazzi ≤ 2.0.0-alpha04 breaks on Gradle 9; "alpha04 is the latest" | agp-9-upgrade | No Paparazzi; Gradle 9.7.1; Paparazzi 2.0.0-alpha05 now exists | Stale note, but supports **not** choosing Paparazzi |
| Roborazzi | testing-setup (named in the Step 1 inventory only) | Not present; brief prefers it | No skill guidance at all; the decision belongs to the Roborazzi bead |
| Robolectric for UI tests (`test` source set) | testing-setup Step 6 | Robolectric 4.17, SDK 34 pin | Aligned; nothing in the skills requires SDK ≥ 35 at test runtime |
| `Devices.FOLDABLE` in previews | adaptive Step 1 | `Devices.FOLDABLE` is `spec:width=673dp,height=841dp`, a generic foldable. Compose 1.12.1 also has `Devices.PIXEL_9_PRO_FOLD` (`id:pixel_9_pro_fold`, resolved from Android Studio's device catalog) | Use `Devices.PIXEL_9_PRO_FOLD` and check which display it models. Give the other display (at least the outer screen) an explicit `spec:` measured on the device by the hardware runner |

---

## 6. Per-skill assessments

### 6.1 `jetpack-compose/adaptive` (priority): **USE AS REFERENCE**

Read in full: `SKILL.md` (301 lines, last-updated 2026-09-22) and all 11 references: `tooling/debug.md`, `navigation-3/recipes/material-listdetail.md`, `mediaquery/index.md`, `grid/{index,get-started,container-properties,item-properties}.md`, `flexbox/{index,get-started,container-behavior,item-behavior}.md` (2,019 lines total).

**What it provides**
- A 5-step workflow, framed as "follow these steps or a subset":
  1. **Verify current UI.** Screenshot tests must exist *before* changing layouts. It gives a `@FormFactorPreviews` multi-preview annotation (`Devices.PHONE`, `FOLDABLE`, `TABLET`, `DESKTOP`) plus `@PreviewTest`.
  2. **Adaptive navigation area.** Convert the bottom bar to `NavigationSuiteItem`s inside `NavigationSuiteScaffold`. Visibility is driven through `rememberNavigationSuiteScaffoldState()` + `show()`/`hide()` from a `LaunchedEffect`. Rule: hide the nav area on scroll-down or for immersive content, and deactivate full-screen detail on large screens.
  3. **Multi-pane via Nav3 scenes.** Mandatory: *"Do not use `ListDetailPaneScaffold` or `SupportingPaneScaffold`."* Use `rememberListDetailSceneStrategy` / `rememberSupportingPaneSceneStrategy` from `adaptive-navigation3`, with entry metadata `listPane(detailPlaceholder = …)`, `detailPane()`, `extraPane()`, `mainPane()` and `supportingPane()`. Rules:
     - avoid list-detail when the detail needs substantial space;
     - a detail screen that is full-screen on mobile must not be full-screen inside list-detail;
     - **detail screens must not show a back arrow in a list-detail layout**.
  4. **Adaptive columns.** `LazyColumn` becomes `LazyVerticalGrid(GridCells.Adaptive(min))`; non-lazy repeated `Column`s become the experimental `Grid`, only after confirming with the user.
  5. **App bars on scroll.** Use per-destination `exitUntilCollapsedScrollBehavior` / `enterAlwaysScrollBehavior`.
- Final step: build, run tests and run screenshot tests, but **"DO NOT update the reference images. Prompt the user to do this after they have viewed the screenshot diffs."**
- The references document:
  - `mediaQuery` / `derivedMediaQuery` over `UiMediaScope`: `windowWidth`/`Height`, `windowPosture` (Tabletop / Book / Flat), `pointerPrecision` (Fine / Coarse / Blunt / None), `keyboardKind` (Physical / Virtual / None), `hasCamera`, `hasMicrophone`, `viewingDistance`. Enabling it needs `ComposeUiFlags.isMediaQueryIntegrationEnabled = true` in `Application.onCreate`. Previews override `LocalUiMediaScope`.
  - `Grid`: tracks in dp / % / `fr` / intrinsic, gaps, named areas, spans.
  - `FlexBox`: CSS-flex semantics.
  - The Nav3 Material list-detail recipe, which shows `calculatePaneScaffoldDirective(currentWindowAdaptiveInfoV2()).copy(horizontalPartitionSpacerSize = 0.dp)`.
  - Android Studio's Layout Inspector (recomposition counts, semantics) and Compose **UI Check** (accessibility and adaptive audit, "Fix with AI").

**Compatibility with Skein**
- **Versions: fine.** Everything is present in Skein's pinned Compose 1.12.1 / material3-adaptive 1.3.0 BOM line (§5). `mediaQuery` on Android reads `androidx.window` `WindowLayoutInfo` / `FoldingFeature` (verified by disassembling `MediaQuery_androidKt`), plus `InputManager` for keyboard/pointer and `PackageManager.hasSystemFeature` for camera/mic. That is the same source Skein's `FoldPosture.kt` uses, with **no GMS**. `ui-android` 1.12.1 already depends on `androidx.window` (Skein pins 1.5.1).
- **Architecture: conflicts.** The Nav3 prerequisite and the ban on the Material pane scaffolds mean the skill's Steps 2–3 assume a destination/back-stack app with a bottom bar. Skein has a drawer + command bar + tab system (`TabsState`, `TabHost`, `RecentDropdown` on medium width), a user-toggled split (`SplitHost`), and a 30 % timeline pane with a rail.
  - `NavigationSuiteScaffold` defaults to a bottom `NavigationBar` on compact width. The brief's closed-fold concept (§22) is a drawer, not a bottom bar.
- **What the hand-rolled shell lacks, and the skill's approach provides:**
  - **Hinge awareness.** `calculatePaneScaffoldDirective(windowAdaptiveInfo, verticalHingePolicy)` takes a `HingePolicy` (`AlwaysAvoid` / `AvoidSeparating` / `AvoidOccluding` / `NeverAvoid`), verified in adaptive-layout 1.3.0. `computeAdaptiveLayout` only forces a single pane for tabletop; in book posture panes can straddle the hinge.
  - **Drag-to-resize panes.** `ListDetailSceneStrategy` 1.3.0 exposes `paneExpansionState` + `paneExpansionDragHandle`, which is what `SplitHost` hand-rolls.
  - **Back behaviour across pane collapse.** `backNavigationBehavior`.
- **Robolectric SDK 34:**
  - `mediaQuery` under Robolectric sees no folding feature, so posture resolves to non-foldable. Tests must override `LocalUiMediaScope`, the same pattern as today's injectable `posture` parameter on `AdaptivePaneHost`.
  - `ComposeUiFlags` is a process-global flag. It must also be set in `TestSkeinApplication`. Behaviour with the flag unset was not verified here.
  - The skill's `@PreviewTest` path is layoutlib-based and doesn't use Robolectric at all (§6.3).
- **Previews:** `Devices.FOLDABLE` (673×841 dp) is not the Pixel 9 Pro Fold. Skein's existing previews use 400 / 700 / 1000 dp widths. `Devices.PIXEL_9_PRO_FOLD` exists as a Studio device-catalog id. Pair it with a measured `spec:` for whichever display it doesn't model (at least the outer screen).

**Expected implementation cost**
- Excerpting the rules into briefs: **S**.
- Following it verbatim: **L**, because it is the Nav3 adoption (§6.2) plus `NavigationSuiteScaffold` + scene strategies replacing `AdaptivePaneHost` / `SplitHost`.
- Replacing `FoldPosture.kt` with `mediaQuery`: **S**, but it trades 103 lines of stable code for an experimental API plus a global flag. Not recommended unless a Wave 3 bead simplifies with it.

**Architectural implications**
- Adopting it moves pane arrangement from Skein-owned code into Material's directive / adapt-strategy model. The gain is hinge avoidance, standard pane motion, and back semantics tied to pane state. The cost is less control over the 30 % timeline share and the rail.
- The skill's ordering (screenshots before layout changes) implies the **screenshot-baseline infrastructure should land before Wave 3**, not in Wave 11. That also matches brief §39.

**Agent instructions**
- **Now:** excerpt these 5 rules into the UI-implementation, test and review briefs (draft text in §10.3):
  1. Screenshot tests before layout refactors.
  2. Never update reference images yourself.
  3. No back arrow on a detail pane, and no full-screen-on-phone detail inside multi-pane.
  4. `mediaQuery` / Grid / FlexBox are allowed only when the bead names them, never via a Compose bump.
  5. Posture and size come from window APIs, never device models.
- **Wave 1 / 3 architect:** pinned link `https://github.com/android/skills/blob/42dc2270e96032bd860bb94511e440aa00a43125/jetpack-compose/adaptive/SKILL.md`.
- **Only if Nav3 is adopted:** vendor it with a Skein overlay that replaces Step 2 (drawer-first IA) and Step 1 (Skein devices + the chosen screenshot tool).

**Dependencies:** none for the excerpts. Verbatim adoption adds `navigation3-runtime`/`-ui` 1.2.0, `adaptive-navigation3` 1.3.0 (pulls in `adaptive-layout` and `adaptive-navigation` 1.3.0) and `material3-adaptive-navigation-suite` 1.4.0. All are androidx Apache-2.0, pass `LicenseAudit`/`checkDependencyGuards`, and each needs `verification-metadata.xml` entries. The screenshot plugin adds more (§6.3).

**Reference only?** Yes for now: the whole skill for architects, the five rules for everyone.

---

### 6.2 `navigation/navigation-3`: **USE AS REFERENCE** (Wave 1 decision input; DEFER implementation)

Read: `SKILL.md` (index of links, last-updated 2026-09-24), the Nav3 `index.md`, and the full `migration-guide.md` (740 lines). The recipes most relevant to Skein were read for their approach and API surface: `material-listdetail` in full, and the descriptions plus key code of `material-supportingpane`, `scenes-twopane`, `scenes-listdetail`, `multiple-backstacks`, `bottomsheet` and `dialog`. The remaining recipes (deep links, Hilt/Koin modularisation, results, animations, conditional) were catalogued but not needed. There are 27 reference files (24 recipes) and 6,327 lines including `SKILL.md`.

**What it provides**
- The Nav3 model: a back stack of `NavKey`s (`@Serializable`), resolved through an `entryProvider { entry<K> { … } }` DSL and rendered by `NavDisplay(entries | backStack, onBack, sceneStrategies, entryDecorators)`.
- `rememberNavBackStack` / `rememberSerializable` persist state across configuration change and process death.
- Scenes (`Scene`, `SceneStrategy`, `SceneStrategyScope.calculateScene`) render several entries at once. The recipes include built-in `DialogSceneStrategy`, a custom `BottomSheetSceneStrategy` (copy-in), a custom two-pane `WindowSizeClass`-gated scene, and Material `ListDetailSceneStrategy` / `SupportingPaneSceneStrategy` with `BackNavigationBehavior`.
- Recipes also cover multiple back stacks with state retained per top-level route, conditional flows, results (`ResultEventBus`, `ResultEffect`, with a caution that results aren't process-death-safe), a lifecycle owner per entry, deep links, and Hilt / Koin modular recipes.
- The migration guide (Nav2 → Nav3) supplies `NavigationState` / `Navigator` classes and "AI Agent:" stop-and-ask checkpoints.

**Compatibility with Skein**
- **Versions:** compileSdk ≥ 36 ✓ (37). Nav3 1.2.0 is the latest stable. `adaptive-navigation3` 1.3.0 matches Skein's adaptive line. The serialization plugin is already in the catalog (`kotlin-serialization`) but not applied to `:feature:shell`.
- **Trap:** the guide's "update minSdk to 23" must never be followed.
- **Architecture:** Skein has **no Nav2**, so the migration guide's step list doesn't apply as written. Only the recipes and core concepts do. The guide's own assumptions (bottom bar, "exit through Home", atomic migration) don't match Skein. Its **unsupported features** list includes "shared destinations: screens that can move between different back stacks". Skein's tabs can move between split panes, so tabs probably have to stay outside the back stack (`TabsState`) with Nav3 owning only destinations and overlays. That needs an Opus spike.
- **Mapping to the brief:**
  - `ListDetailSceneStrategy` list / detail / **extraPane** gives the brief's `NAVIGATION | WORKSPACE | CONTEXT` (§23): conversations → conversation → context inspector; collections → document → metadata / backlinks.
  - `SupportingPaneSceneStrategy` gives "context inspector: sheet or route on phone, supporting pane on Fold" (Wave 7).
  - Back-stack persistence gives brief §24 (the activity **is** recreated on fold/unfold: no `configChanges`).
  - **A middle path exists.** The *custom* `scenes-listdetail` and `scenes-twopane` recipes gate on exactly the call Skein's `classifyWidth` already makes: `WindowSizeClass.isWidthAtLeastBreakpoint(WIDTH_DP_MEDIUM_LOWER_BOUND)`. They also expose a `LocalBackButtonVisibility` composition local so a detail entry can hide its back arrow inside a pane. Skein could keep its own pane proportions (the 30 % timeline) in a custom `Scene` while Nav3 owns only the back stack. That would deliberately deviate from the adaptive skill's "use the Material strategies" instruction, and the Wave 1 spike should weigh it.
- **Robolectric SDK 34:** `NavDisplay` runs on any API ≥ 23 (minSdk), so the JVM lane is fine. Predictive-back animation progress can't be meaningfully asserted on the JVM; that needs device verification by the hardware runner.

**Expected implementation cost:** **L**. It needs an Opus spike to model routes vs tabs vs split. After that, roughly 4–8 Sonnet beads: routes, the `SkeinApp` destination switch → `entryProvider`, the pane host → scene strategies, overlays → scenes or `NavigationBackHandler`, state-restoration tests, and Fold transition tests A–G.

**Architectural implications:** it replaces the enum `Destination` switch in `SkeinApp.kt` and the `destinationContent` slot pattern. `:app` would need to contribute entries to an `EntryProviderScope`, which fits the existing "leaf module wires real screens" design. It removes the need for most of `AdaptivePaneHost` / `SplitHost` if the Material strategies are used. It adds a second state model beside `TabsState` unless tabs are redesigned. It also satisfies brief rule 13 ("avoid duplicate navigation systems") only if the old `NavState` is fully retired.

**Agent instructions:** pinned link in the **Wave 1 IA spike** brief (Opus / Fable, architect role). The recipes are the reference; the migration guide's "AI Agent" checkpoints become questions the spike answers in the IA doc. Load into implementers (vendored, with overrides) only after the decision.

**Dependencies:** `androidx.navigation3:navigation3-runtime` and `navigation3-ui` 1.2.0; optionally `androidx.lifecycle:lifecycle-viewmodel-navigation3` 2.11.0. All androidx Apache-2.0; they need verification-metadata entries.

**Reference only?** Yes until Wave 1 decides.

---

### 6.3 `testing/testing-setup`: **USE NOW (excerpt only; never load verbatim)**

Read in full: `SKILL.md` (209 lines, last-updated 2026-09-23) and all 4 references: `compose/testing/common-patterns.md`, `studio/preview/compose-screenshot-testing.md`, `compose-screenshot-testing-with-testsuites.md`, `dependency-injection/hilt-testing.md` (outline).

**What it provides**
- **Step 1:** a testing-stack inventory, including a three-way screenshot taxonomy: device (Dropshots), Robolectric (**Roborazzi**), and layoutlib (Paparazzi / Compose Preview Screenshot Testing).
- **Step 2:** "no DI → install Hilt".
- **Step 3:** defaults of JUnit4, Jacoco, Compose Testing, Robolectric, **Compose Preview Screenshot Testing** (test suites if AGP ≥ 9.5.0-alpha03, else the deprecated standalone plugin; "don't upgrade AGP for this"), Dropshots, and Mockk only if needed.
- **Step 4:** fakes first, mocks second.
- **Steps 5–7:** unit, UI and database tests (UI tests in `test` via Robolectric).
- **Step 8, the screenshot matrix:**
  - every screen at **9 sizes**: widths 400 / 610 / 900 dp × heights 400 / 500 / 1000 dp;
  - plus 400×500 captures for each alternative theme and at **font scale 1.5**;
  - components in each theme and font scale;
  - state variations such as loading.
- **Step 9:** Compose behaviour tests use semantic matchers first (`testTag` only when more than 3 matchers are needed) and **always verify state restoration**.
- **Step 10:** navigation tests for back handling, deep links and "exit through home".
- **Step 11:** `DeviceConfigurationOverride` (`ForcedSize`, `FontScale`, `FontWeightAdjustment`, `DarkMode`, `LayoutDirection`, `Locales`).
- **Steps 12–14:** end-to-end tests (~5 %, UI Automator), Dropshots for device screenshots, Jacoco.
- **Final touches:** ask, then update `AGENTS.md` or create `docs/testing.md`.
- References:
  - `StateRestorationTester` (`emulateSavedInstanceStateRestore()`) and `createAndroidComposeRule<ComponentActivity>()` + `ui-test-manifest`.
  - Compose Preview Screenshot Testing: setup, the `screenshotTest` source set, `@PreviewTest`, `update…`/`validate…` tasks, reference paths, and an HTML report.
  - The AGP test-suite variant and a legacy-to-suite migration table.

**Compatibility with Skein**
- **Directly compatible, with zero new dependencies:**
  - Steps 6, 8, 9, 10 and 11: `DeviceConfigurationOverride` and `StateRestorationTester` ship in `ui-test` / `ui-test-junit4` 1.12.1, already on the test classpath.
  - The matrix complements the `@Config(qualifiers = "w…dp-h…dp")` style Skein already uses.
  - "Verify state restoration" maps exactly onto brief §24, since the activity is recreated on fold. Skein should add a size-change recreation (`RuntimeEnvironment.setQualifiers` + `recreate()`) on top of `StateRestorationTester`; that is a Skein addition, not the skill's.
- **Conflicts:**
  - **Step 2, install Hilt:** Skein has no DI by design; fakes live in `:testing`.
  - **Step 3 defaults:** Compose Preview Screenshot Testing vs the brief's Roborazzi preference. Its recommended path needs AGP 9.5 alpha and its standalone plugin is deprecated. Its layoutlib renderer is a second rendering stack beside the Robolectric lane; that stack is independent of the SDK 34 pin, and needs JDK ≥ 17.
  - **Dropshots and device screenshots:** captures through the platform path are black under FLAG_SECURE (§8).
  - **Jacoco / Mockk:** unrequested dependencies plus verification-metadata churn.
  - **Final touches editing `AGENTS.md`:** that is coordinator territory.
  - **`common-patterns.md`'s `createAndroidComposeRule<ComponentActivity>()` + `ui-test-manifest`:** this does not work in Skein library modules. `docs/TESTING.md` documents the `:feature:shell` debug-manifest workaround.
- **Robolectric SDK 34:** nothing in Steps 6–11 needs a newer SDK at test runtime.

**Expected implementation cost:** excerpt **S**. Implementing the Step 8 matrix for the shell and top-level screens with the chosen tool is **M**. The full 9-size matrix × every screen × themes grows combinatorially, so apply 3×3 only to shell-level screens and use Skein's named devices (phone, Fold outer, Fold inner, Fold landscape; brief §39) for feature screens.

**Architectural implications:** none if excerpted. The screenshot-tool choice (Roborazzi vs Compose Preview Screenshot Testing) belongs to the dedicated Roborazzi bead. The skill's matrix and rules are tool-agnostic and transfer to either.

**Agent instructions:** excerpt Steps 8–11 (with the Skein overrides in §10.3) into the **test-role** brief and the `AGENTS.md` testing section; **review** role checks matrix completeness. **Never** vendor it into an auto-loaded skills directory: its auto-activation description ("install testing libraries, set up test infrastructure") would trigger on any test task and pull in Hilt.

**Dependencies:** none (excerpt). Verbatim: Hilt, the screenshot plugin + `screenshot-validation-api` 0.0.1-alpha16, Dropshots, Jacoco, Mockk.

**Reference only?** The screenshot-testing references are reference material for the Roborazzi-vs-Compose-Preview decision.

---

### 6.4 `system/edge-to-edge`: **USE NOW**

Read in full: `SKILL.md` (426 lines, last-updated 2026-08-24; no references).

**What it provides**
- Prerequisites: Compose and targetSdk ≥ 35.
- Plan (activities, lists, FABs, every `TextField`); `enableEdgeToEdge()` before `setContent`; `android:windowSoftInputMode="adjustResize"`.
- Apply insets **once**. Order of preference:
  1. `Scaffold` `innerPadding` + `consumeWindowInsets`;
  2. Material components' own inset handling;
  3. `safeDrawingPadding()` / `windowInsetsPadding(WindowInsets.safeDrawing)`;
  4. `Modifier.fitInside(WindowInsetsRulers.SafeDrawing.current)` for deep nesting;
  5. inset-size modifiers for scrims.
- Adaptive scaffolds (`NavigationSuiteScaffold`, `ListDetailPaneScaffold`) **don't propagate `PaddingValues`**. Apply insets per screen, never on the scaffold parent.
- IME section with RIGHT/WRONG pairs: prefer `fitInside(WindowInsetsRulers.Ime.current)`; `imePadding()` must come before `verticalScroll`; never add `imePadding()` under `contentWindowInsets = safeDrawing` (double padding).
- System-bar icon contrast: with `ComponentActivity.enableEdgeToEdge`, **do not** set `isAppearanceLight*` manually. `isNavigationBarContrastEnforced = false` when a bottom bar exists.
- Lists get insets as `contentPadding`, never parent padding, plus a status-bar protection scrim.
- A full-screen `Dialog` needs `decorFitsSystemWindows = false`.
- A 6-item checklist.

**Compatibility with Skein**
- Already aligned: `MainActivity` calls `ComponentActivity.enableEdgeToEdge(statusBarStyle, navigationBarStyle)` with theme-derived styles (skein-1vfg). `EdgeToEdgeSurface` applies `WindowInsets.safeDrawing`, which already includes the IME, so the skill's "no second `imePadding()`" rule is exactly the risk for new chat/composer code.
- **Gap the skill flags:** the manifest declares no `windowSoftInputMode`. Worth evaluating in the Wave 4 composer bead against fold Test D (keyboard active across fold/unfold) and the Fold-smoke report "the status row was hidden under the keyboard" (handoff 2026-09-23). This is an evaluation item, not a verified bug.
- Versions: `fitInside` and `WindowInsetsRulers` exist in 1.12.1.
- Robolectric SDK 34: inset behaviour is only partially modelled on the JVM, so IME correctness is ultimately a hardware-runner check.

**Expected implementation cost:** **S** (a checklist applied per bead; a one-time audit of existing lists and text fields is S–M).

**Architectural implications:** none. It codifies one inset-owning layer per screen, which matters once panes or scaffolds are introduced (the adaptive scaffolds don't forward padding).

**Agent instructions:** vendor a **pinned copy** (Apache-2.0 `LICENSE.txt` alongside, modification notice) with three Skein overrides:
1. Don't add `enableEdgeToEdge` or `isAppearanceLight*`; `MainActivity` owns them.
2. `EdgeToEdgeSurface`/`safeDrawing` already includes the IME.
3. A `windowSoftInputMode` change needs its own bead with on-device evidence.

Load for the **UI-implementation** and **review** roles in Waves 2, 3, 4, 6 and 7.

**Dependencies:** none. **Reference only?** No; it is a working checklist.

---

### 6.5 `navigation/navigation-event`: **USE NOW (from Wave 3)**

Read in full: `SKILL.md` (309 lines, last-updated 2026-09-01) and all 4 references (`index`, `setup`, `dispatcher`, `handle-back`).

**What it provides**
- `NavigationEventDispatcher` / `NavigationEventHandler` / `NavigationEventInfo` / `NavigationEventInput` concepts. Priority is `PRIORITY_OVERLAY` before `PRIORITY_DEFAULT`, LIFO within a priority.
- Compose `NavigationBackHandler(state, isBackEnabled, onBackCancelled, onBackCompleted)` with `rememberNavigationEventState` and `NavigationEventTransitionState.InProgress` (`progress`, `swipeEdge`) for predictive-back animation.
- `rememberNavigationEventDispatcherOwner(enabled = …)` for tab/pager scoping.
- Troubleshooting with RIGHT/WRONG pairs:
  - don't override the activity's dispatcher (causes a StackOverflowError);
  - dialogs and sheets resolve their own owner;
  - one handler per state (otherwise `IllegalArgumentException`);
  - branch inside one handler.
- A checklist, including "`enableOnBackInvokedCallback` not false; defaults true on API 36+".
- **"If Navigation 3 is in use, use Nav3's built-in back support."**

**Compatibility with Skein**
- **Already on the classpath:** `navigationevent` and `navigationevent-compose` **1.0.0** resolve transitively through `activity-compose` 1.13.0, and are in verification-metadata. compileSdk 37 ≥ 36 ✓. `MainActivity` extends `FragmentActivity` → `ComponentActivity`, so the built-in owner applies. With targetSdk 37 (≥ 36), `enableOnBackInvokedCallback` defaults to true per the skill's checklist, and the manifest doesn't set it, so predictive back is on.
- **Gap it addresses:** grep finds no `BackHandler`, `NavigationBackHandler` or `OnBackPressedCallback` in any main source set. Back is consumed only by Material components that handle it themselves (`ModalNavigationDrawer`, `DropdownMenu` in `RecentDropdown` / `TabContextMenu`). The inline command palette (`CommandPalette` is a `Surface`, not a popup), an open split and the tab stack don't intercept back. Once the IME is down, back falls through to the activity.
  - Brief §24 / Test G ("temporary navigation UI resolves appropriately after posture change") and a calm closed-fold experience need back to close the palette, sheet, context pane or split first.
- **Robolectric SDK 34:** handlers can be exercised with `onBackPressedDispatcher.onBackPressed()` or Espresso `pressBack()`. Predictive progress animation is device-only.

**Expected implementation cost:** **S–M**: handlers for palette, sheet, pane and split, plus back tests (testing-setup Step 10).

**Architectural implications:** if Wave 1 adopts Nav3, destination back is owned by `NavDisplay` and this skill covers only overlays and non-destination state (palette, split). Otherwise it becomes Skein's back model. In both cases, one handler per state, with overlay priority for the palette.

**Agent instructions:** vendor a **pinned copy** with one override ("Nav3 adopted → destinations use `NavDisplay` back; this skill covers overlays only") and one pin ("declare `navigationevent-compose` directly at the resolved version; do not bump to 1.2.0-rc01"). Load for **UI implementation** (Waves 3, 4, 7, 10) and **review**.

**Dependencies:** none new. **Reference only?** No.

---

### 6.6 `jetpack-compose/theming/styles`: **DEFER**

Read: `SKILL.md` (226 lines, last-updated 2026-09-08). Reference titles: fundamentals, state-animations, styles-vs-modifiers, theming, designsystems/custom.

**What it provides:** the experimental Compose **Styles API** (`androidx.compose.foundation.style.Style`, `Modifier.styleable`, `rememberUpdatedStyleState`, state blocks like `disabled { … }`, `StyleScope` theme extensions) and a migration workflow: a `ComponentStyles` object, `style: Style = Style` parameters, and moving hard-coded defaults into styles. It covers **custom components and custom themes only; not Material component styles.**

**Compatibility with Skein**
- Requirements are met: compileSdk 37 ✓, and foundation 1.12.1 contains the API (verified), so the skill's "requires updating to alpha Compose" warning is stale.
- Still experimental: a module-wide `-opt-in=androidx.compose.foundation.style.ExperimentalFoundationStyleApi`.
- Skein's design system is Material 3 plus `SkeinTokens` (via `staticCompositionLocalOf`) and mostly Material components, so the skill's scope covers only the custom ones (composer, message rows, activity blocks, glyph buttons).
- Its "baseline screenshot via emulator / UI Automator" step conflicts with the Robolectric-first lane.

**Expected implementation cost:** **M–L** (touches every custom component; API churn risk).

**Architectural implications:** it would introduce a second styling mechanism beside Material theming and tokens during a design-system rewrite. That is the wrong moment to bet on an experimental API.

**Agent instructions:** no. Revisit when Styles leaves experimental. The Wave 2 design-system architect may skim `styles-vs-modifiers.md` as background.

**Dependencies:** none (compiler opt-in only). **Reference only?** Background reference at most.

---

### 6.7 `devtools/android-cli`: **USE AS REFERENCE** (hardware runner; Mac UX lab)

Read in full: `SKILL.md` (471 lines, last-updated 2026-09-12), `references/interact.md` and `references/journeys.md`.

**What it provides**
- The `android` CLI:
  - `sdk`, `create`, `run`, `install` (delta install), `emulator`;
  - `layout` (JSON UI tree with `text`, `contentDesc`, `interactions`, `state`, `bounds`, `center`, `--diff`);
  - `screen capture [--annotate]` + `screen resolve`;
  - `docs search|fetch` (online knowledge base), `skills add|update|find`;
  - `studio render-compose-preview`, `version-lookup` (internet).
- An interaction protocol: `adb shell input` tap / swipe / text rules; always visually inspect captures.
- **Journeys:** an XML list of `<action>`s ("tap…", "verify…") evaluated step-by-step, with a markdown result report.

**Compatibility with Skein**
- `android layout` reads the accessibility tree. That is the method the coordinator already uses on the Fold because FLAG_SECURE makes screenshots black (handoff §2: "the accessibility tree (`adb shell uiautomator dump`) is how the coordinator verified layout"). So it works under FLAG_SECURE. `screen capture` does not, unless the owner turns FLAG_SECURE off in Settings for the session.
- It is a Google-distributed binary installed via `curl … | bash`, and several commands are network-backed. That is acceptable on the developer Mac, but it must stay off the Fold and needs a hardware-runner decision before any use there.
- Skein non-negotiable #9: non-hardware subagents never `adb`.
- `render-compose-preview` requires a running Android Studio, which is relevant to the MacBook UX lab (brief §37 Layer A) but is not a CI path.

**Expected implementation cost:** **S** (reuse the journey format; the CLI itself is optional).

**Architectural implications:** none. The **journey XML format is a good fit for brief §25 Tests A–G** (fold/unfold scripts with "verify" steps) and for the §43 user-flow audit. `layout`'s `contentDesc` and `interactions` fields support on-device accessibility spot checks (§40).

**Agent instructions:** excerpt the journey format and the interaction rules into the **hardware-runner** brief only. Do not give it to UI or test subagents.

**Dependencies:** a host tool only; nothing in the Gradle graph. **Reference only?** Yes.

---

### 6.8 `profilers/android-profiler`: **DEFER**

Read: `SKILL.md` (56 lines, last-updated 2026-08-06) and its routing structure (recording and analysis orchestrators, Perfetto workflows, subsystem hint files, 7 example `.pftxt` trace configs).

**What it provides:** an intent-routing orchestrator for recording (system traces, heap dumps, CPU and native heap) and analysing (Perfetto SQL, triage, per-candidate analysis, jank / graphics / memory hints) Android performance data.

**Compatibility with Skein:** relevant to brief **§46 UI performance** (jank while streaming tokens, fold-transition frame drops), not to §9's list.
- `references/perfetto/bin/trace_processor` is a Python launcher that **downloads** `trace_processor_shell` v57.2 from `commondatastorage.googleapis.com` at run time. That is a network-fetched binary: host-only, needs owner approval, never on the Fold.
- Recording needs the device, so it is serial and hardware-runner-only.

**Expected implementation cost:** **M** when §46 work starts.

**Architectural implications:** none.

**Agent instructions:** not now. Later, for the coordinator or hardware runner only.

**Dependencies:** host Python + the downloaded Perfetto binary. **Reference only?** Yes, deferred.

---

### 6.9 `build-system/agp/agp-9-upgrade`: **NOT APPLICABLE** (one useful note)

Read in full: `SKILL.md` (103 lines, last-updated 2026-09-18) and `references/paparazzi-gradle-9.md`.

Skein already runs AGP 9.4.1 with built-in Kotlin (the catalog comments explain the absent `kotlin-android` and `parcelize` aliases), KSP 2.3.12 (the skill requires ≥ 2.3.6), no kapt, and no Hilt. None of the four gradle.properties flags the skill removes (`android.builtInKotlin`, `android.newDsl`, `android.uniquePackageNames`, `android.enableAppCompileTimeRClass`) are set.

**Relevant note:** Paparazzi ≤ 2.0.0-alpha04 breaks on Gradle 9 through internal-API use and needs HTML test reports disabled. The skill's "alpha04 is the latest" is stale (alpha05 now exists), but it is evidence for the brief's Roborazzi preference over Paparazzi. **No skill in the repo assumes Paparazzi instead of Roborazzi**; testing-setup defaults to Compose Preview Screenshot Testing.

---

## 7. Not applicable: one reason each

| Skill | Reason |
|---|---|
| `jetpack-compose/migration/migrate-xml-views-to-jetpack-compose` | Skein has no XML layouts; 100 % Compose. (adaptive's prerequisites point to it; irrelevant here.) |
| `camera/camerax` | No camera feature; its foldable guidance is viewfinder-in-tabletop, and posture detection already exists. |
| `device-ai/appfunctions` | Exposes app workflows to on-device system agents; outside UX, and a vault-exposure question for security review (non-negotiable #12 spirit). |
| `device-ai/ml-kit-genai-prompt-api` | ML Kit / Gemini Nano via AICore; `com.google.mlkit` is denied by `checkDependencyGuards`. |
| `identity/restore-credentials` | Account restore keys via Credential Manager; Skein has no accounts. |
| `identity/verified-email` | Google-issued verified-email credentials; no accounts, no network. |
| `media/media3-cast-integration` | No media; Cast requires Play services (denied). |
| `play/engage-sdk-integration` | Play Engage SDK; Play is denied and not a distribution channel. |
| `play/play-billing-library-version-upgrade` | No billing; Play-only. |
| `play/play-policy-insights` | Play policy audit with Python scripts that scrape `play.google.com`; Skein is not on Play. |
| `performance/r8-analyzer` | Keep-rule and size tuning; build/release concern, not UX. |
| `security/android-intent-security` | Security audit of Intents and exported components; security-review track, not this epic. |
| `security/android-permissions-security` | Permissions / IPC audit; security-review track; Skein's manifest guards already enforce the hard rules. |
| `tv/leanback-to-compose-tv-migration` | TV form factor. |
| `wear/wear-compose-m3` | Wear OS form factor. |
| `xr/display-glasses-with-jetpack-compose-glimmer` | XR glasses form factor. |
| `build-system/agp/agp-9-upgrade` | Already on AGP 9.4.1 with built-in Kotlin (§6.9). |

---

## 8. Conflicts between skill guidance and Skein's constraints

| # | Skill guidance | Skill | Skein constraint | Resolution |
|---|---|---|---|---|
| 1 | "Use Navigation 3 … Do not use `ListDetailPaneScaffold` / `SupportingPaneScaffold`" | adaptive | Hand-rolled nav + pane shell; brief rule "avoid duplicate navigation systems" | Wave 1 decision (Opus spike). Until then the rule is overridden in every brief. |
| 2 | "Update minSdk to 23 and compileSdk to 36" | navigation-3 | minSdk 30 / compileSdk 37 | Never lower. Nav3 needs compileSdk ≥ 36, which is met. |
| 3 | Nav3 doesn't support "shared destinations" across back stacks | navigation-3 | Tabs move between split panes | Keep tabs outside the Nav3 back stack, or redesign tabs; the spike decides. |
| 4 | "No DI → install Hilt" | testing-setup | No DI by design; fakes in `:testing` | Override: never add a DI framework. |
| 5 | Default screenshot tool = Compose Preview Screenshot Testing; test suites need AGP ≥ 9.5.0-alpha03; standalone plugin deprecated | testing-setup, adaptive | AGP 9.4.1 stable; brief prefers Roborazzi; Robolectric SDK 34 lane | The tool choice belongs to the Roborazzi bead. Skill rules (matrix, never self-update baselines) apply to either tool. Don't upgrade to an AGP alpha for screenshots. |
| 6 | Device screenshots (Dropshots; `android screen capture`) | testing-setup, android-cli | **FLAG_SECURE default-on**: platform captures are black (observed on the Fold). Whether a given library's in-process capture path is affected was not verified here. | Visual baselines are host-side only. Device checks use the accessibility tree (`android layout` / `uiautomator dump`). Any device capture needs the owner to toggle FLAG_SECURE off for that session, and must never contain real vault content. |
| 7 | End-to-end tests and baselines "on an emulator / UI Automator" | testing-setup, styles | Robolectric-first; the emulator lane is nightly; the Fold is serial and hardware-runner-only | JVM tests for behaviour; the hardware runner owns device journeys (Tests A–G). |
| 8 | "Update AGENTS.md / create docs/testing.md" | testing-setup | `AGENTS.md` and `docs/TESTING.md` are coordinator-owned | Override: propose changes in the bead's hand-back; don't edit. |
| 9 | Jacoco, Mockk, Dropshots, Hilt | testing-setup | Every new artifact needs `verification-metadata.xml` entries; ponytail rung 5 (no new deps) | Not added unless a bead names them. |
| 10 | ML Kit, Cast, Engage, Billing, Credential Manager + Google flows | device-ai, media, play, identity | Non-negotiable #2: no GMS / Firebase / MLKit / Play (`checkDependencyGuards`) | NOT APPLICABLE. |
| 11 | Network-backed tooling (`android docs`, `version-lookup`, `skills add`; Perfetto binary download) | android-cli, profiler | Offline-first *app* (no INTERNET permission); the Fold environment is frozen (non-negotiable #18) | Developer Mac only; never on the Fold; not in agent default tooling. |
| 12 | "AI Agent: stop and ask the user", "confirm with the user", "prompt the user to update reference images" | adaptive, navigation-3, testing-setup | Autonomous dispatch; the "user" is the coordinator; spec-over-prompt discipline (handoff §4.6) | Briefs pre-answer them (which experimental APIs are allowed, which steps are skipped). Anything else: `bd note` + hand back rather than guessing. |
| 13 | Install via plugin marketplace or `android skills add --all`; skills auto-activate on description match | README, manifests | Non-negotiable #11 (retrieved content is data); reproducible, reviewed agent behaviour | Do **not** install the marketplace or plugin. Vendor pinned, reviewed copies outside auto-discovered paths (§10.2). |
| 14 | `ComposeUiFlags.isMediaQueryIntegrationEnabled = true` (process-global) | adaptive | Deterministic Robolectric tests with `TestSkeinApplication` | Set it in both `SkeinApplication` and `TestSkeinApplication`; tests override `LocalUiMediaScope`. |
| 15 | `Devices.FOLDABLE` as "the foldable" | adaptive | Target is the Pixel 9 Pro Fold (outer + inner) | Use `Devices.PIXEL_9_PRO_FOLD` plus a measured outer `spec:`; Robolectric qualifiers for tests. |
| 16 | `NavigationSuiteScaffold` (compact → bottom bar) | adaptive | Brief §22: closed fold uses a drawer / sheets; no orphaned rail | Wave 1 IA decides; `NavigationSuiteScaffold` is only a candidate for the open-fold rail. |
| 17 | Material defaults assume Google Play / Google APIs emulator images (implicitly) | android-cli, testing-setup | GrapheneOS; no GMS | Any emulator used for parity should be an AOSP (non-Google-APIs) image. None of the relevant skills *require* GMS. |

---

## 9. Defects and staleness found in the skills themselves

These should go into the vendored overlays and be reported upstream if the owner wishes.

1. **adaptive, broken reference.** Step 1 links "Compose Preview Screenshot Testing tool" to `references/…/tooling/debug.md`, but that file is about Layout Inspector and Compose UI Check, not screenshot-test setup. The real setup is in testing-setup's `compose-screenshot-testing*.md`.
2. **adaptive, internal version contradiction.** SKILL.md says Grid / FlexBox / MediaQuery are "available from Compose 1.11.0-beta01", while its own Grid and FlexBox `get-started.md` pin `compose = "1.13.0-alpha03"`. Both are misleading for Skein: all three APIs are in stable 1.12.1.
3. **styles, stale warning.** "Requires updating to alpha version of Compose" is no longer true; foundation 1.12.1 stable contains the API.
4. **navigation-3, downgrade instruction.** "Update the project's minSdk to 23 and the compileSdk to 36" is phrased as an unconditional update.
5. **testing-setup, downgrade example.** The screenshot reference's catalog example pins `agp = "9.0.0-rc03"` and `kotlin = "2.2.10"`.
6. **agp-9-upgrade, stale.** "v2.0.0-alpha04 is the latest [Paparazzi] release"; alpha05 exists.
7. **Repo-wide churn.** Content is regenerated from a zip on each bot run, so links to `main` are unstable by construction.

---

## 10. Recommended adoption plan

### 10.1 By UX-overhaul wave

| Wave | What to wire in | Skills | Role(s) |
|---|---|---|---|
| **0: now** | Land this doc. Coordinator adds the §10.3 excerpt to the dispatch template (§6.3 of the handoff) for UI and test roles. File the follow-ups in §11. | adaptive (excerpt), testing-setup (excerpt) | Orchestrator |
| **Before Wave 3** (pulled forward from Wave 11) | **Screenshot baseline infrastructure first.** This is adaptive Step 1 and brief §39. The Roborazzi bead decides the tool; the testing-setup Step 8 matrix and the "never self-update baselines" rule apply either way. Capture the *current* shell at 400 / 700 / 1000 dp (matching existing previews) plus Fold outer / inner / landscape before any shell change. | testing-setup, adaptive | Test (Sonnet); review |
| **1: IA** | **Navigation 3 decision** (Opus spike): scenes vs the hand-rolled host; tabs-in-split modelling; `NavigationSuiteScaffold` vs drawer on the closed fold; `mediaQuery` adoption for posture, keyboard and pointer. Output feeds `INFORMATION_ARCHITECTURE.md` / `ADAPTIVE_LAYOUT_SPEC.md`. | navigation-3, adaptive (full, pinned link) | Architect (Opus / Fable) |
| **2: design system** | Edge-to-edge rules for every new primitive (sheets, dialogs, scrims, the composer container). Touch targets and labels are Skein-authored (no upstream skill). The pointer-precision idea from adaptive's `mediaQuery` goes into tokens as *refinements* for Fine pointers (hover states), never below the 48 dp touch minimum. Styles API: DEFER. | edge-to-edge (vendored), adaptive (excerpt) | UI impl; review |
| **3: adaptive shell** | **If Nav3 is adopted:** vendor adaptive + navigation-3 with overlays. `ListDetailSceneStrategy` / `SupportingPaneSceneStrategy` with `calculatePaneScaffoldDirective(…, HingePolicy.AvoidSeparating)`; `paneExpansionState` replaces `SplitHost`; state-restoration and size-change recreation tests.<br>**If not:** keep `AdaptivePaneHost`, but add hinge avoidance in book posture (the concept the directive models), the "no back arrow in pane" rule, and `NavigationBackHandler` for split / pane close.<br>Either way: Fold Tests A–G as Robolectric qualifier-change tests plus hardware-runner journeys. | adaptive, navigation-3, navigation-event, testing-setup, android-cli (journeys) | UI impl; test; hardware runner; review |
| **4: chat** | Composer IME: `fitInside(WindowInsetsRulers.Ime.current)` or the existing `safeDrawing` owner. Never double IME padding. Evaluate `windowSoftInputMode="adjustResize"` against Test D on the Fold. Message list insets go to `contentPadding`. Optional `enterAlwaysScrollBehavior` for the chat top bar on the closed fold. Back closes attach sheets and the palette before leaving the chat. | edge-to-edge, navigation-event, adaptive Step 5 | UI impl; test; review |
| **6: knowledge & notes** | List-detail with **extraPane** for metadata / backlinks (brief §23), via scenes or the host. Collections grid via `GridCells.Adaptive(min)` (adaptive Step 4); experimental `Grid` / `FlexBox` only if the bead approves. | adaptive, navigation-3 | UI impl; review |
| **7: context inspector** | Phone: sheet or route. Fold: supporting pane (`SupportingPaneSceneStrategy.supportingPane()` or the host). Back dismisses the supporting pane first (`BackNavigationBehavior`). | adaptive, navigation-3, navigation-event | UI impl; test |
| **10: command palette & keyboard** | `mediaQuery { keyboardKind == Physical }` to show shortcut hints and enable the power-user layer; `pointerPrecision == Fine` for hover affordances. **No upstream skill covers shortcuts, focus traversal, hover icons or stylus.** Write Skein rules from the Continue / Zed study. The palette registers an **overlay-priority** back handler. | adaptive (`mediaQuery`), navigation-event | UI impl; test; review |
| **11: baselines & accessibility** | Complete the testing-setup Step 8 matrix. Font scale 1.5 captures (`DeviceConfigurationOverride.FontScale(1.5f)`), dark and light (`DarkMode`), RTL spot check (`LayoutDirection`). Accessibility audit has **no upstream skill**. Use Skein's `WcagContrast.kt`, semantics tests, labels for glyph icons, and on-device `android layout` / `uiautomator` JSON (`contentDesc`, `interactions`) for spot checks under FLAG_SECURE. | testing-setup, android-cli | Test; hardware runner; review |
| **§46, after Wave 4** | Jank / frame analysis during streaming and fold transitions. | android-profiler | Hardware runner (host-side analysis) |

### 10.2 How to carry skills into agent instructions

- **Do not** install `https://github.com/android/skills` as a Claude Code plugin marketplace, and do not run `android skills add --all`:
  - It loads 25 auto-activating descriptions into every session.
  - Several of them contradict Skein (testing-setup would install Hilt; ml-kit would add a denied dependency).
  - Upstream content is regenerated by a bot, which is non-negotiable #11 territory.
- **Do not** place vendored copies under `.claude/skills/`. Claude Code discovers project skills there for sessions opened in the repo (and so for work dispatched from them), and activates them by description match. They would fire on unrelated tasks. Skein has no `.claude/skills/` today; `.claude/settings.json` holds only the `bd prime` hooks.
- **Vendored copy**, when a skill is loaded into implementers (edge-to-edge, navigation-event; adaptive and navigation-3 only if Nav3 is adopted), at a path such as `docs/agent-skills/android-skills@42dc227/<skill>/`, with:
  - upstream `LICENSE.txt` (Apache-2.0 §4(a));
  - a `PROVENANCE.md` (URL, SHA, tag, date);
  - a `SKEIN_OVERRIDES.md`, with modified files carrying a prominent change notice (Apache-2.0 §4(b)). The upstream has no NOTICE file to propagate.

  Dispatch briefs name the path explicitly per role. Vendored text is agent material, not shipped software, so a `NOTICE` entry is optional (owner's call).
- **Pinned links**, for reference-only reading by architects (adaptive full, navigation-3 recipes): always use `https://github.com/android/skills/blob/42dc2270e96032bd860bb94511e440aa00a43125/<path>`, never `main`.
- **Excerpts**, for rules every UI and test agent needs: the block in §10.3, placed in the dispatch template's per-role section and, once the coordinator agrees, in `AGENTS.md`.
- **Upgrades:** re-pin deliberately (a bead), diff the vendored skills, and re-review the overrides. Never auto-update.

### 10.3 Proposed excerpt (not yet applied: for the coordinator to place)

```
## Android UI rules (excerpted from android/skills @ 42dc227, Apache-2.0; Skein overrides applied)
Adaptive / fold
- Size and posture come from window APIs only: currentWindowAdaptiveInfoV2() + WindowSizeClass
  breakpoints (600/840 dp) and FoldPosture / androidx.window. Never branch on device model.
- In any multi-pane layout a detail pane shows no back arrow, and a screen that is full-screen on the
  phone must not go full-screen when it is a pane.
- Do NOT migrate to Navigation 3, NavigationSuiteScaffold, or pane scene strategies unless the bead
  says so (pending Wave 1 decision). Never lower minSdk (30) or compileSdk (37).
- Experimental Compose APIs already in the pinned Compose 1.12.1 — mediaQuery/derivedMediaQuery,
  Grid, FlexBox, Styles — only when the bead names them. Never bump Compose/foundation-layout to an
  alpha to get an API. mediaQuery needs ComposeUiFlags.isMediaQueryIntegrationEnabled in both
  SkeinApplication and TestSkeinApplication; tests/previews override LocalUiMediaScope.
Insets / back
- MainActivity owns enableEdgeToEdge and system-bar icon colours. Apply insets once per screen;
  lists take insets as contentPadding; EdgeToEdgeSurface/safeDrawing already includes the IME — never
  add imePadding() beneath it. Prefer Modifier.fitInside(WindowInsetsRulers.Ime.current) for composers.
- Back for overlays (palette, sheets, panes, split) uses NavigationBackHandler (navigationevent-compose,
  already on the classpath — declare, don't bump). One handler per NavigationEventState.
Tests
- Robolectric, @Config(sdk = [34]). Semantic matchers first; testTag only if >3 matchers.
- Every rememberSaveable/state holder gets a StateRestorationTester test; fold-affecting state also
  gets a size-change recreation test (qualifiers change + recreate()).
- Sizes, font scale, dark mode, RTL via DeviceConfigurationOverride (ForcedSize, FontScale(1.5f),
  DarkMode, LayoutDirection).
- Screenshot baselines: shell-level screens at 400/610/900 x 400/500/1000 dp plus Skein named devices
  (phone, Fold outer, Fold inner, Fold landscape); one 400x500 capture per theme and at font scale 1.5.
- NEVER create or update reference images. Run the comparison, attach the diff to the bead, hand back.
  Recording is a coordinator/owner step after visual review.
- Never add Hilt/Koin, Mockk, Jacoco, Dropshots, or any screenshot plugin unless the bead names it.
  No device/emulator screenshots (FLAG_SECURE); device checks are the hardware runner's job.
Where any upstream skill says "ask the user": write a `bd note` and hand back — don't guess, don't block.
```

---

## 11. Follow-ups for the coordinator to file

This agent does not run `bd`; the coordinator files these.

1. **Wave 1 spike (Opus):** "Adopt Navigation 3 + Material scene strategies for the adaptive shell?" Inputs: §6.1, §6.2, §8 rows 1–3 and 16. Output: a decision in `ADAPTIVE_LAYOUT_SPEC.md`.
2. **Pull screenshot-baseline infrastructure ahead of Wave 3.** Depends on the Roborazzi evaluation bead. Record the `AGP ≥ 9.5.0-alpha03` constraint of the Compose Preview Screenshot Testing test-suite path as an input.
3. **Back-handling audit:** no explicit back handlers exist. Palette, split and tab stack behaviour on back, including brief Test G. Wave 3.
4. **Composer IME / `windowSoftInputMode` evaluation on the Fold** (Test D). Wave 4; hardware runner.
5. **Hinge avoidance in book posture** for the hand-rolled host, if Nav3 isn't adopted. Wave 3.
6. **Skein-authored accessibility and keyboard/pointer rules**, since no upstream skill exists. Waves 2, 10 and 11; feed from the Continue / Zed reference study.
7. **Dispatch-template update:** add the §10.3 excerpt per role. Coordinator-owned.

---

## 12. Reference-study card (brief §52 format)

- **Project:** https://github.com/android/skills @ `42dc227` (v1.0.13)
- **License:** Apache-2.0 (`LICENSE.txt`)
- **Maintenance:** active; bot-regenerated from developer.android.com about weekly; no public contributions.
- **Relevant Skein problem:**
  - making the Fold shell adaptive without hard-coded breakpoints;
  - correct back and inset behaviour across fold/unfold;
  - a screenshot and test discipline agents can follow.
- **Patterns worth adopting:**
  - hinge-aware pane directives;
  - list / detail / extra-pane roles;
  - supporting pane for context;
  - "screenshots before layout refactors";
  - "agents never update baselines";
  - the 3×3 size matrix + font scale 1.5;
  - `DeviceConfigurationOverride`, `StateRestorationTester`;
  - `NavigationBackHandler` scoping;
  - inset-once rules.
- **Patterns not worth adopting:**
  - Nav3-as-prerequisite without an IA decision;
  - bottom-bar-first navigation;
  - Hilt / Mockk / Jacoco / Dropshots defaults;
  - device screenshots;
  - plugin / marketplace auto-install;
  - the experimental Styles API during the design-system rewrite.
- **Potential reusable code:** none directly. The recipe snippets are Apache-2.0 samples, and patterns are re-implemented in Skein code.
- **Android / Fold relevance:** high (adaptive, navigation-3, navigation-event, edge-to-edge).
- **Mac UX-lab relevance:** medium. Compose Preview multi-previews, `render-compose-preview` (Android Studio), and the testing-setup matrix for preview sets.
- **Recommended action:** **PROTOTYPE**. Excerpt now; vendor edge-to-edge and navigation-event; adaptive and navigation-3 wait on the Wave 1 Nav3 spike.
- **Expected benefit:** fewer adaptive, back and inset regressions from agents, and a shared screenshot matrix.
- **Expected cost:** S now; L only if Nav3 is adopted.

---

## Appendix A: Evidence and method

- Shallow clone at the pinned SHA; tag verified with `git ls-remote --tags`. Commit cadence and repo metadata from the GitHub REST API (unauthenticated, read-only).
- Read in full: every `SKILL.md` for adaptive, navigation-3, navigation-event, testing-setup, edge-to-edge, styles, android-cli, agp-9-upgrade and android-profiler, plus all of adaptive's, testing-setup's, navigation-event's and android-cli's references, and the Skein-relevant navigation-3 references. Frontmatter plus key sections read for the remaining 16.
- Skein versions come from `gradle/libs.versions.toml`, `gradle/verification-metadata.xml` and the cached Compose BOM 2026.09.00 POM.
- API presence verified by listing and `javap`-inspecting classes in the resolved AARs:
  - `foundation-layout` 1.12.1: `GridKt`, `FlexBoxKt`, `ExperimentalGridApi`, `ExperimentalFlexBoxApi`, `RulerAlignmentKt.fitInside`;
  - `ui` 1.12.1: `MediaQueryKt`, `UiMediaScope`, `ComposeUiFlags.isMediaQueryIntegrationEnabled`, `WindowInsetsRulers`; the Android `mediaQuery` implementation references `androidx.window` `FoldingFeature` / `WindowLayoutInfo`, `InputManager` and `PackageManager.hasSystemFeature`;
  - `foundation` 1.12.1: `androidx.compose.foundation.style.Style`, `ExperimentalFoundationStyleApi`;
  - `ui-tooling-preview` 1.12.1: `Devices.*` constants.
- Downloaded from Google Maven into the scratchpad (not the repo) and inspected: `adaptive-layout-android` 1.3.0 (`calculatePaneScaffoldDirective(WindowAdaptiveInfo, HingePolicy)`, `HingePolicy` values) and `adaptive-navigation3-android` 1.3.0 (`ListDetailSceneStrategy` exposes `directive`, `adaptStrategies`, `backNavigationBehavior`, `paneExpansionState`, `paneExpansionDragHandle`; POM dependencies as in §5).
- Latest-version checks from `maven-metadata.xml` on Google Maven and Maven Central.
- **Not verified here, and flagged as such where used:**
  - `mediaQuery` behaviour with the flag unset;
  - whether a specific device-screenshot library's in-process capture path is blocked by FLAG_SECURE;
  - on-device IME behaviour without `adjustResize`;
  - Android CLI telemetry and network behaviour beyond the documented network-backed commands.
