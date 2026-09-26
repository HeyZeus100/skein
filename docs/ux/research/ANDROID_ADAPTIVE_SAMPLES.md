# Android adaptive-apps-samples: reference study for Skein's fold-aware shell

**Bead:** `skein-xtov.7` (epic `skein-xtov`) · **Authority:** `docs/research/SKEIN_UI_UX_OVERHAUL_PROMPT.md` §§1–3, 8, 10–12, 21–25, 37–39, 46, 52
**Companions:** `COMPOSE_SAMPLES.md`, `NOWINANDROID.md`, `ROBORAZZI.md` (same directory)
**Status:** Research record. Advisory. It proposes an adaptive strategy for Wave 3 (§48). It changes no code, and it overrides no spec.

---

## 0. Provenance

| Field | Value |
| --- | --- |
| Repository | <https://github.com/android/adaptive-apps-samples> |
| Commit inspected | `62abdd2a3212061277bca44d8ee0bee04c3ea689` (`main`, 2026-05-12, "Merge pull request #47 from android/add-mediaquery-sample") |
| Licence | `Apache-2.0` (file `LICENSE.md`, verbatim Apache 2.0 text; GitHub reports the same SPDX id) |
| Local clone | `research/clones/adaptive-apps-samples/` (shallow, git-ignored by `.gitignore` `research/clones/`, never committed) |
| Library sources read | `androidx/androidx` mirror at `e6bfe347707b0e0003e253189cb5ec5f4dda8fbf` (androidx-main, 2026-09-26). The claims below that matter were checked again in the **released** Google Maven source jars: `adaptive-layout-android:1.3.0`, `adaptive-navigation-android:1.3.0`, `adaptive-navigation3-android:1.3.0`, `material3-adaptive-navigation-suite-android:1.4.0`, `material3-android:1.4.0`, `ui-test-android:1.12.1`, `ui-tooling-preview-android:1.12.1` |
| Skein baseline | `009cbb6cbd694659ba1bfbac7d70d8d0db78eab8` (2026-09-26). Versions from `gradle/libs.versions.toml`: compose-bom `2026.09.00` (resolves `ui` 1.12.1, `material3` 1.4.0, `material3-adaptive-navigation-suite` 1.4.0), material3-adaptive `1.3.0`, androidx.window `1.5.1`, AGP `9.4.1`, Kotlin `2.4.20`, Robolectric `4.17` |
| Inspection date | 2026-09-26 |

Permalink prefixes used below:

- `AAS` = `https://github.com/android/adaptive-apps-samples/blob/62abdd2a3212061277bca44d8ee0bee04c3ea689`
- `AX` = `https://github.com/androidx/androidx/blob/e6bfe347707b0e0003e253189cb5ec5f4dda8fbf`

---

## 1. Ground truth: Pixel 9 Pro Fold geometry (measured, not assumed)

The brief assumed the inner display is about 790–820 dp wide, which would make it Medium width. **That is wrong for this device at both of the densities that matter.** The inner display is **Expanded** width.

### 1.1 Evidence

| Source | What it says |
| --- | --- |
| **Owner's device, via `adb` on 2026-09-26** (serial `52241FDKD000LV`, `product:comet`, build `google/comet/comet:17/CP2A.260805.005/2026091001`, Android 17, GrapheneOS) | `wm density` → `Physical density: 390`, `Override density: 330`. `wm size` → `Physical size: 2076x2152`. `settings get secure display_density_forced` → `330`. `dumpsys window displays` (inner, landscape) → `sw1007dp w1043dp h1007dp 330dpi xlrg … land`. `dumpsys display` → `"Inner Display" 2076 x 2152 … density 390` and `"Outer Display" 1080 x 2424 … density 390`. `cmd device_state print-states` → `CLOSED(0)`, `HALF_OPENED(1)`, `OPENED(2)`, `REAR_DISPLAY_STATE(3)`, `CONCURRENT_INNER_DEFAULT(4)`, `REAR_DISPLAY_OUTER_DEFAULT(5)`. Current state `2` (OPENED). |
| Stock firmware (`comet` vendor partition dump, Android 16 `CP1A.260305.018`) | `ro.sf.lcd_density=390`: [`comet/vendor/build.prop` L59](https://github.com/DangerousAndroid/AluminiumOS/blob/a2b497095f282852d4d30f70ec214abc869e040d/comet/vendor/build.prop). The device's `displayconfig/display_port_{0,1}.xml` define no density mapping, so both panels inherit 390. |
| Android Studio device catalog (`sdklib/devices/nexus.xml`, `mirror-goog-studio-main` head, fetched 2026-09-26) | `pixel_9_pro_fold`: `pixel-density 390dpi`, `x-dimension 2076`, `y-dimension 2152`, `foldable-region` folded `1080 x 2424`, hinge `areas 1038-0-0-2152` (a vertical fold line at x≈1038 px in natural orientation), postures `0-30 / 30-150 / 150-180`. |
| Roborazzi's generated qualifiers (from the same catalog) | `Pixel9ProFold = "w852dp-h883dp-large-notlong-notround-any-390dpi-keyshidden-nonav"`: [`RobolectricDeviceQualifiers.kt` L56](https://github.com/takahirom/roborazzi/blob/c636f7774261cb1b6dec28144ac906bd24e1dc15/roborazzi/src/main/java/com/github/takahirom/roborazzi/RobolectricDeviceQualifiers.kt#L56) |
| Compose tooling | `Devices.PIXEL_9_PRO_FOLD = "id:pixel_9_pro_fold"` (`ui-tooling-preview` 1.12.1 `Device.kt` L57). It renders the inner display at 390 dpi. |
| Public spec sheets | Inner 8.0", 2076 × 2152, 373 ppi. Outer 6.3", 1080 × 2424, 422 ppi ([Wikipedia](https://en.wikipedia.org/wiki/Pixel_9_Pro_Fold), [GSMArena](https://www.gsmarena.com/google_pixel_9_pro_fold-13220.php), [PhoneArena](https://www.phonearena.com/reviews/google-pixel-9-pro-fold-vs-pixel-fold_id6354)). The panel ppi (373/422) is the *physical* pixel density. The *logical* density Android uses for dp is 390 on both panels. |

### 1.2 Resulting window sizes

dp = px ÷ (dpi ÷ 160), rounded the way WindowManager rounds (`(int)(px/density + 0.5)`). Width classes: Compact < 600 ≤ Medium < 840 ≤ Expanded < 1200 ≤ Large < 1600 ≤ XL. Height classes: Compact < 480 ≤ Medium < 900 ≤ Expanded ([developer.android.com: window size classes](https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes)).

| Surface | Density | Portrait w × h (dp) | Landscape w × h (dp) | Width class P / L | Height class P / L | Status |
| --- | --- | --- | --- | --- | --- | --- |
| Outer (closed) | 390 stock | **443 × 994** | **994 × 443** | Compact / Expanded | Expanded / **Compact** | computed from measured px and density |
| Outer (closed) | 330 owner override, *if it applies to the outer panel* | 524 × 1175 | 1175 × 524 | Compact / Expanded | Expanded / Medium | **unverified.** `wm density -d 3` could not report an override for the inactive outer display. Measure while folded (see §1.4). |
| Inner (open) | 390 stock | **852 × 883** | **883 × 852** | **Expanded** / **Expanded** | Medium / Medium | computed. Matches Roborazzi's `Pixel9ProFold` |
| Inner (open) | 330 owner override | **1007 × 1043** | **1043 × 1007** | **Expanded** / **Expanded** | Expanded / Expanded | **measured** (`sw1007dp w1043dp h1007dp`) |
| Inner (open) | 420 (the brief's premise; also the gen-1 Pixel Fold's density; roughly one Display-size step *larger* than stock) | 791 × 820 | 820 × 791 | Medium / Medium | Medium / Medium | hypothetical. It is the "Medium inner" case, reachable only by enlarging Display size |
| Inner, split-screen half | 390 / 330, landscape | ≈ 437 × 852 / ≈ 517 × 1007 | — | Compact | Medium / Expanded | approximate (a divider takes a few dp) |

Coordinator note, reconciled: the outer display's *stock* density is **390**, not 420. The outer panel is therefore ≈ 443 dp wide at stock density, not ≈ 411 dp. Both `dumpsys display` and the firmware property confirm it.

### 1.3 Consequences

1. **Skein must derive every layout decision from the window's measured size class and never from a density or device model.** At the owner's 330 dpi the inner display is comfortably Expanded (1043 dp). At stock 390 it is only just Expanded (852 dp, 12 dp over the breakpoint). One notch larger Display size makes it Medium. Split-screen makes it Compact. All of these happen on the *same phone*.
2. **Three content panes never fit on the Fold at any density.** Material's own directive allows three horizontal partitions only from Large (≥ 1200 dp) upward (`calculatePaneScaffoldDirective`, [AX `PaneScaffoldDirective.kt` L48–106](https://github.com/androidx/androidx/blob/e6bfe347707b0e0003e253189cb5ec5f4dda8fbf/compose/material3/adaptive/adaptive-layout/src/commonMain/kotlin/androidx/compose/material3/adaptive/layout/PaneScaffoldDirective.kt#L48-L106)). The same table ships in `adaptive-layout-android:1.3.0`: Compact → 1, Medium → 1, Expanded → 2, else → 3. The §21 layout `Navigation | Workspace | Inspector` becomes *rail + two panes* on the Fold, not three columns.
3. **The closed Fold in landscape is Expanded-width with Compact height** (994 × 443 at stock). Both Material and Skein's current shell hand it a two-pane layout. §22 forbids that ("no persistent multi-pane UI" when closed). A height gate is required (see §5).
4. The fold line is centred on the inner display: x ≈ 1038 px in natural portrait, i.e. ≈ 426 dp at 390 or ≈ 503 dp at 330. Half-folding in **portrait** gives *book* posture (vertical hinge). Half-folding in **landscape**, which is how the owner uses the device today, gives *tabletop* (horizontal hinge).

### 1.4 One measurement still owed (it decides a manifest flag)

Fold the phone with Skein in the foreground, then run:

```bash
adb shell wm density            # does the outer panel also report Override density: 330?
adb shell dumpsys window displays | grep -E 'cur=|overrideConfig'
```

If the outer panel stays at 390 while the inner is at 330, **every fold or unfold also changes `densityDpi`**. That recreates the Activity even after the four size flags in §4.3 are declared, unless `density` is added to `android:configChanges` as well.

---

## 2. What Skein does today (the concrete problem)

The files are at `009cbb6`, under `feature/shell/src/main/kotlin/app/skein/feature/shell/`, and `app/src/main/...`.

### 2.1 Layout decisions

`classifyWidth` (`layout/PaneLayout.kt` L13–21) looks at width only. `computeAdaptiveLayout` (L44–80) then maps the classes this way: COMPACT → single pane; MEDIUM → single pane plus the "Recent ▾" dropdown with the timeline hidden; EXPANDED → timeline (30 %, `SkeinTokens.timelineShare`) plus tabs, with an optional `⧉` split. Tabletop forces a single pane.

| Real window | Skein class | What renders | Verdict vs brief |
| --- | --- | --- | --- |
| Outer portrait 443 × 994 | COMPACT | single pane, command bar, modal drawer | roughly §22-shaped |
| **Outer landscape 994 × 443** | **EXPANDED** (height ignored) | **timeline pane + tabs, split available** | **violates §22** |
| Inner 852 × 883 / 883 × 852 (stock) | EXPANDED | timeline ≈ 256–265 dp plus tabs | works; not M3 |
| Inner 1007 × 1043 / 1043 × 1007 (owner) | EXPANDED | same | works |
| Inner at a larger Display size (≈ 780–820 dp) | MEDIUM | **one pane, timeline hidden, 780 dp of width for a single column** | fails §23 ("do not merely enlarge the phone layout") |
| Inner split-screen half | COMPACT | single pane | fine |

Other findings:

- **The icon rail is 40 dp** (`SkeinTokens.railWidth`, `layout/IconRail.kt` L58–83). That is below the 48 dp minimum touch target, and it is glyph-only (`▸ ◐ ✦ ◈ ⚹`), which runs against §40.
- **The drawer is modal at every width** (`nav/NavDrawer.kt` L35–41). That is a second navigation system alongside the rail and the command bar, which §2 rule 12 forbids ("avoid duplicate navigation systems").
- **Hand-rolled posture code** (`layout/FoldPosture.kt`) re-implements what `currentWindowAdaptiveInfoV2().windowPosture` already provides (`Posture.isTabletop`, `hingeList`). Skein reads both today: `SkeinApp` takes `currentWindowAdaptiveInfoV2()` for size and `rememberFoldPosture()` for the hinge (`SkeinApp.kt` L210–211).

### 2.2 State across a fold or unfold (§24)

`MainActivity` declares no `android:configChanges` (`app/src/main/AndroidManifest.xml` L81–88). Every fold and every unfold therefore **destroys and recreates the Activity**. Only state held in a saveable or retained holder survives:

| §24 item | Holder today | Survives recreation? | Survives a live re-layout (after `configChanges`)? |
| --- | --- | --- | --- |
| Current workspace or destination | `NavState` via `rememberSaveable` (`nav/NavState.kt` L88–92) | yes | yes (hoisted in `SkeinApp`) |
| Active conversation or document (as a tab) | `TabsState` via `rememberSaveable` (`tabs/TabsState.kt` L173–180) | yes | yes (hoisted) |
| **Draft text** | `ChatBottomBar`: `var fieldValue by remember { … }` (`feature/chat/…/ChatBottomBar.kt` L88) | **no** | **no** (see pane re-parenting below) |
| **Response being generated** | `ChatViewModel` is a plain `@Stable` class created with `remember(...)` and driven by `rememberCoroutineScope()` (`ChatScreen.kt` L59–71); `send()` collects `sendPipeline.send(...)` in that scope (`ChatViewModel.kt` L180–240) | **no.** The scope is cancelled, the pipeline sees a cancellation, and the turn is persisted as interrupted | **no** |
| Context inspector open | `ChatViewModel.contextPanelOpen` (`ChatViewModel.kt` L115) | no | no |
| Scroll position | `rememberLazyListState()` (`MessageList.kt` L47) is saveable, **but** `LaunchedEffect(itemCount) { animateScrollToItem(last) }` (L58) jumps to the bottom whenever messages reload | effectively no | effectively no |
| Note editor text | `EditorState` via `rememberSaveable` | yes | yes |
| **Selected graph node / graph open** | `var graphDocId by remember { … }` in `MainActivity.UnlockedShell` (L386). Zoom and pan use `remember` (`GraphView.kt` L108–109) | **no** | no |
| Models overlay open | `remember` (`MainActivity.kt` L413) | no | yes (outside the panes) |
| **Model import in progress** (multi-GB copy plus hash) | `modelImportScope = rememberCoroutineScope()` (`MainActivity.kt` L465, L505–506) | **no. Folding the phone cancels the import** | yes |
| Vault unlock | `SkeinApplication.vault` (Application scope); `gatePhase` shows `Open` straight away when the session exists | yes, with no re-prompt. `ProcessLifecycleOwner`'s stop debounce keeps lock-on-background from firing during recreation | yes |

**The pane re-parenting hazard.** This matters *after* `configChanges` is added. `AdaptivePaneHost` calls `RightZone(...)` from three different `when (result.timelineMode)` branches (`layout/AdaptivePaneHost.kt` L57–95). Compose identifies content by call site, so moving from HIDDEN (outer) to FULL (inner) **disposes the whole primary-pane subtree and rebuilds it**. Everything held in plain `remember` inside a tab, including the chat state holder, the draft, and the running generation, is lost even without an Activity restart. The lesson for the redesign: **pane content must never own state that has to outlive a layout change.** Such state belongs in retained holders (a `ViewModel` or a session-scoped object). UI-only bits go in `rememberSaveable`. Material's scaffolds and Nav3's `NavDisplay` both rely on this discipline.

---

## 3. Project assessment (§52 format)

### Project

<https://github.com/android/adaptive-apps-samples> at `62abdd2a` (2026-05-12).

### License

`Apache-2.0`. It is compatible with Skein, which is also Apache-2.0 (root `LICENSE`, `NOTICE`). Copying a substantial snippet would need the upstream copyright header and a `NOTICE` entry. **This study recommends copying no code.** Every pattern below is a use of public androidx APIs.

### Maintenance

The repository is active (GitHub `pushed_at` 2026-09-19; last `main` merge 2026-05-12; 14 open issues; owned by the Android DevRel org). It is not a library: each sample is a stand-alone Gradle project on its own dependency set. Versions vary a lot between samples. `list-detail-compose` uses adaptive `1.2.0-beta02`, `supporting-pane-compose` uses adaptive `1.1.0-beta01`, AdaptiveJetStream uses adaptive `1.1.0` with navigation-suite `1.4.0` and navigation-compose `2.9.5`, and MediaQuery uses AGP `9.1.0` with Nav3 `1.0.1`. So these samples show **patterns, not versions.** Skein's pinned material3-adaptive `1.3.0` is newer than every sample.

### Relevant Skein problem

1. The hand-rolled shell hard-codes three width tiers and a 30/70 split. It has no concept of *panes as navigation destinations*, so it cannot turn a pane into a sheet on small windows (context inspector) or back into a pane on large ones (§21, §35).
2. The fold transition resets state (§2.2 above).
3. There are two navigation systems (modal drawer plus a 40 dp rail) and no Material navigation suite (§20, §22).
4. There is no strategy for Medium width, for compact height, or for posture.

### Patterns worth adopting

| Pattern | Where it is shown | Why it matters for Skein |
| --- | --- | --- |
| **`ListDetailPaneScaffold` driven by `rememberListDetailPaneScaffoldNavigator`**, with `BackHandler(enabled = navigator.canNavigateBack())` and a `rememberSaveable` selection | `AAS/CanonicalLayouts/list-detail-compose/app/src/main/java/com/example/listdetailcompose/ui/ListDetailSample.kt` [L98–171](https://github.com/android/adaptive-apps-samples/blob/62abdd2a3212061277bca44d8ee0bee04c3ea689/CanonicalLayouts/list-detail-compose/app/src/main/java/com/example/listdetailcompose/ui/ListDetailSample.kt#L98-L171) (selection at L99, navigator L100, back L105, scaffold L113) | Chat (`Conversations \| Chat`) and Knowledge (`Documents \| Document`) are both list-detail. The navigator saves its destination history with `rememberSaveable` (adaptive-navigation 1.3.0 `ThreePaneScaffoldNavigator.kt` L326). It computes *what is visible* from the current directive, so folding re-derives one pane or two from the same history. **That is §24's "the UI reflows; the app does not reset."** |
| **`SupportingPaneScaffold`** with `Modifier.preferredWidth(...)` on the supporting pane | `supporting-pane-compose/.../SupportingPaneSample.kt` [L69–172](https://github.com/android/adaptive-apps-samples/blob/62abdd2a3212061277bca44d8ee0bee04c3ea689/CanonicalLayouts/supporting-pane-compose/app/src/main/java/com/example/supportingpanecompose/ui/SupportingPaneSample.kt#L69-L172) (navigator L71, width L89) | Graph (`Canvas \| Selected node`) and the chat context inspector are "main plus supporting" shapes. |
| **Pane expansion drag handle**: `rememberPaneExpansionState(navigator.scaffoldValue)` plus `VerticalDragHandle` with `Modifier.paneExpansionDraggable(...)` | `ListDetailSample.kt` L155–167; `SupportingPaneSample.kt` L172 | This replaces Skein's own `SplitHost` draggable divider (`layout/SplitHost.kt`) with an accessible and RTL-aware version. adaptive 1.2 added semantics for `paneExpansionDraggable`; 1.3 added RTL anchors. |
| **`NavigationSuiteScaffold` with `NavigationSuiteItem`** and `navigationItemVerticalArrangement` | `AAS/AdaptiveNavigationSample/.../MainActivity.kt` [L91–154](https://github.com/android/adaptive-apps-samples/blob/62abdd2a3212061277bca44d8ee0bee04c3ea689/AdaptiveNavigationSample/app/src/main/java/com/google/sample/adaptivenavigationsample/MainActivity.kt#L91-L154); JetStream `AppWithNavigationSuiteScaffold.kt` [L109–141](https://github.com/android/adaptive-apps-samples/blob/62abdd2a3212061277bca44d8ee0bee04c3ea689/AdaptiveJetStream/jetstream/src/main/java/com/google/jetstream/presentation/app/withNavigationSuiteScaffold/AppWithNavigationSuiteScaffold.kt#L109-L141) (with `rememberNavigationSuiteScaffoldState` show/hide) | One component replaces the rail, bar and drawer for every size. `NavigationSuiteScaffoldDefaults.navigationSuiteType(adaptiveInfo)` ships in navigation-suite 1.4.0 (source jar L1159). It picks `ShortNavigationBarCompact` for Compact width, `ShortNavigationBarMedium` for tabletop or compact height, and `WideNavigationRailCollapsed` otherwise. The collapsed rail is 96 dp, or 80 dp in the narrow variant (`NavigationRailCollapsedTokens`). `show()`/`hide()` covers immersive states such as a full-screen document. |
| **Navigation type chosen from input and form factor**, not only from width | JetStream `NavigationComponentType.kt` [L35–72](https://github.com/android/adaptive-apps-samples/blob/62abdd2a3212061277bca44d8ee0bee04c3ea689/AdaptiveJetStream/jetstream/src/main/java/com/google/jetstream/presentation/app/NavigationComponentType.kt#L35-L72) | This precedent lets Skein *deliberately* depart from the M3 default on Compact (drawer instead of bottom bar, see §5). |
| **`WindowSizeClass` extension helpers** (`isWidthMedium()`, `isWidthAtLeastExpanded()`, `isWidthAtLeastLarge()`) built on `isWidthAtLeastBreakpoint` | JetStream `components/feature/WindowSizeClass.kt` [L21–36](https://github.com/android/adaptive-apps-samples/blob/62abdd2a3212061277bca44d8ee0bee04c3ea689/AdaptiveJetStream/jetstream/src/main/java/com/google/jetstream/presentation/components/feature/WindowSizeClass.kt#L21-L36) | This is how Skein's `classifyWidth` should read, plus a height gate. |
| **Saveable app-level UI state** with a `Saver` (`AppState`) | JetStream `app/AppState.kt` [L28–103](https://github.com/android/adaptive-apps-samples/blob/62abdd2a3212061277bca44d8ee0bee04c3ea689/AdaptiveJetStream/jetstream/src/main/java/com/google/jetstream/presentation/app/AppState.kt#L28-L103) | Skein's `NavState`/`AdaptiveLayoutState` already follow this pattern. Keep doing it. |
| **Multipreview device annotations** (`@AdaptivePreview` = phone + foldable + tablet + desktop + …) feeding **Compose Preview Screenshot Testing** (`com.android.compose.screenshot`, `@PreviewTest`) | JetStream `components/JetStreamPreview.kt` [L26–50](https://github.com/android/adaptive-apps-samples/blob/62abdd2a3212061277bca44d8ee0bee04c3ea689/AdaptiveJetStream/jetstream/src/main/java/com/google/jetstream/presentation/components/JetStreamPreview.kt#L26-L50); `jetstream/src/screenshotTest/.../ScreenScreenshotTests.kt`; plugin `0.0.1-alpha13` | The multipreview idea transfers to Skein's MacBook UX lab. The screenshot tool does not: see `ROBORAZZI.md` §"Alternatives". |
| **Posture from `mediaQuery { windowPosture == Posture.Tabletop }`** and pointer-precision touch targets | `AAS/MediaQuery/.../MediaQuerySample.kt` L123–124 (tabletop), L233 (touch targets), L176 (keyboard kind), L374 (window info) | `mediaQuery` exists in Skein's resolved `ui` 1.12.1 (`androidx/compose/ui/MediaQuery.kt`), but it is `@ExperimentalMediaQueryApi`. For now use `currentWindowAdaptiveInfoV2().windowPosture`. Revisit `mediaQuery` for keyboard-attached and pointer-precision adaptations (§41) once it is stable. |
| **Keyboard shortcuts and desktop context menus** | JetStream `app/KeyboardShortcuts.kt` L29–40; `components/desktop/ContextMenuArea.kt`, `BackNavigationContextMenu.kt` | Shows §41/§42 (keyboard and power-user input, command palette) working on large and desktop windows. |
| **No sample declares `android:configChanges`** (a grep over every `AndroidManifest.xml` in the repo finds nothing) | all samples | The samples rely on saveable and navigator state surviving recreation. That is necessary but, per current Android guidance, not sufficient for Skein. See §4.3. |

### Patterns not worth adopting

- **Wrapping the whole scaffold in `AnimatedContent(targetState = isListAndDetailVisible)` to get shared-element transitions** (`ListDetailSample.kt` L111–112). Every time the pane count changes, the entire scaffold is torn down and rebuilt. That is the re-parenting hazard from §2.2, applied on purpose. Skein needs state continuity more than a hero animation of an icon.
- **Activity-embedding / SlidingPaneLayout / Fragment variants** (`list-detail-activity-embedding`, `list-detail-sliding-pane`, `supporting-pane-fragments`, `*-views`). Skein is Compose-only and single-activity.
- **Accompanist `accompanist-adaptive`** (listed in `list-detail-compose/app/build.gradle`). It is superseded by material3-adaptive.
- **JetStream's TV, XR and Automotive branches** (`AppWithSpatialNavigation`, `TopBar` for leanback). They are out of scope.
- **Treating the sample's dependency versions as guidance.** Skein is already ahead of them.

### Potential reusable code

None worth vendoring. Each pattern is a use of the public API that takes 10–40 lines. Write them fresh against material3-adaptive 1.3.0 and navigation-suite 1.4.0. **Licence impact: none.**

### Android/Fold relevance

High. This is the primary Fold reference (§8). It covers every §8 topic *except* live state preservation across a transition, which it leaves to saveable navigators. Its API surface (directive, navigators, adapt strategies, posture, navigation suite) is exactly what Skein should delegate to, instead of `PaneLayout.kt`/`FoldPosture.kt`.

### Mac UX-Lab relevance

Medium to high. (a) Each sample opens in Android Studio on the Mac and runs on the **Pixel 9 Pro Fold AVD**, whose device definition carries the correct hinge and posture list (§1.1). You can try fold and unfold, tabletop and book on the emulator without the phone. (b) The multipreview pattern (`@AdaptivePreview`) is what Layer A/B of §37 needs, with Skein's measured devices substituted (see `ROBORAZZI.md` §5).

### Recommended action

**ADOPT.** Adopt the material3-adaptive scaffolds, navigators, adapt strategies and `NavigationSuiteScaffold` these samples demonstrate, replacing `AdaptivePaneHost`/`PaneLayout`/`FoldPosture`/`IconRail`/`NavDrawer`. Copy no sample code. Wave 3 should land this behind the existing shell first (a prototype branch validated against §25), then switch.

### Expected benefit

- Fold and unfold stop resetting UI, *once paired with the state fixes in §4*.
- The context inspector becomes a sheet on the outer display and a pane on the inner one with **no bespoke code** (`AdaptStrategy.Levitate(...).onlyIfSinglePane(...)`).
- One navigation system at every size.
- Correct behaviour on Medium, compact-height, split-screen, tabletop, book and desktop windows. The M3 team maintains the breakpoints (Large and XL were added in 1.2).
- Pane widths become draggable and accessible, with a large-screen-quality back and predictive-back story (`NavigableListDetailPaneScaffold`, `BackNavigationBehavior`).

### Expected cost

Medium. Adding `material3-adaptive-layout`/`-navigation` (already in the catalog at 1.3.0) and `material3-adaptive-navigation-suite` (BOM) brings **no new dependency families**. It needs Apache-2.0 androidx artifacts, and verification-metadata entries for anything not yet resolved. The work: rewrite `SkeinApp`'s layout layer (≈ 800 lines in `feature/shell/layout` shrink to ≈ 200); re-home the timeline and tabs as list panes; and move chat state to a retained holder (§4). Most pane APIs are `@ExperimentalMaterial3AdaptiveApi`, so opt-in annotations are required. Expect minor API churn across 1.x.

---

## 4. Answers to the prompt's specific questions

### 4.1 §8 topic checklist

| §8 topic | Answer for Skein |
| --- | --- |
| phones / tablets / foldables | Size classes only, never device type ("Window size classes are explicitly not determined by physical device type", [use-window-size-classes](https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes)). The Fold alone produces Compact, Medium (at a larger Display size), Expanded, and Compact-height windows (§1.2). |
| List-Detail | `ListDetailPaneScaffold` for Chat (`Conversations \| Chat`) and Knowledge (`Documents \| Document`), with the **Extra** role for the inspector or relationships. Prefer the Nav3 `ListDetailSceneStrategy` (§4.2). |
| Supporting Pane | `SupportingPaneScaffold` for Graph (`Canvas \| Selected node`). Its default supporting adapt strategy is `Reflow(Main)` (adaptive-layout 1.3.0 `SupportingPaneScaffold.kt` `adaptStrategies()`), which stacks the supporting pane under main on a tall single pane. |
| adaptive navigation | `NavigationSuiteScaffold` with a Skein-specific `navigationSuiteType` (drawer on Compact and compact height; rail on Medium and Expanded; expanded rail or drawer from Large). See §5.2. |
| responsive grids | Not needed for v1 surfaces. The Grid/FlexBox samples (`AAS/Grid`, `AAS/FlexBox`) are for future Knowledge collection views. They use Compose `Grid`/`FlexBox` layout APIs, which are too new to take on now. |
| window-size behaviour | `currentWindowAdaptiveInfoV2()`, already used by Skein (`SkeinApp.kt` L210). It reports Large and XL widths (`WindowSizeClass.computeFromDpSizeV2`) and reads `LocalWindowInfo.containerSize`, so it follows live resizes without an Activity restart. |
| posture changes | `windowPosture.isTabletop` and `windowPosture.hingeList` from the same call. Delete `FoldPosture.kt`; keep an injectable parameter for tests, as NiA does (`NOWINANDROID.md`). |
| fold state | On the Fold the inner display is one continuous panel. When flat, the hinge is expected to be non-separating and non-occluding (verify with a debug overlay). When `HALF_OPENED` it always reports `isSeparating = true` ([make-your-app-fold-aware](https://developer.android.com/develop/ui/compose/layouts/adaptive/foldables/make-your-app-fold-aware)). The default `HingePolicy.AvoidSeparating` therefore changes nothing when flat and splits panes exactly at the fold in book posture. |
| pane adaptation | `AdaptStrategy.Hide` (default), `Reflow(under)` for single-pane stacking, `Levitate(alignment, scrim)` for sheets or dialogs, and `.onlyIfSinglePane(directive)` to become a pane when there is room. All four are verified present in `adaptive-layout-android:1.3.0`. |
| desktop-sized windows / split-screen | Free-form and split windows give arbitrary sizes. Split halves of the inner display are Compact. External or desktop windows can be Large or XL. Nothing extra is needed beyond size classes, a Large tier with three panes, keyboard shortcuts, and `configChanges` so that drag-resizing does not recreate the Activity. Android 16+ ignores `screenOrientation`/`resizeableActivity`/aspect-ratio limits on sw ≥ 600 dp for apps targeting 36, and that opt-out stops working at target 37, which Skein targets ([behavior-changes-16](https://developer.android.com/about/versions/16/behavior-changes-16)). **Skein has no way to avoid being resized.** |

### 4.2 Navigation: Navigation Compose vs Navigation 3 vs the hand-rolled switch

The goal is to preserve the active chat, drafts, selected document, selected graph node and scroll across fold/unfold (§24) with no unnecessary Activity restart.

| Criterion | Keep hand-rolled `NavState` + `TabsState` | Navigation Compose 2.x (type-safe `composable<Route>`) | **Navigation 3** (`androidx.navigation3` 1.2.0 stable, 2026-09-23) |
| --- | --- | --- | --- |
| Back stack survives recreation and process death | yes (`Saver`s) | yes (`NavController` state) | yes. `rememberNavBackStack(...)` over `@Serializable NavKey`s |
| **Adaptive multi-pane from one back stack** | hand-written (today's `when` branches, which re-parent content) | not native. Put a `NavigableListDetailPaneScaffold` *inside* one destination and run a second navigator (see Reply, `COMPOSE_SAMPLES.md`) | **native.** `NavDisplay(sceneStrategy = rememberListDetailSceneStrategy())` from `adaptive-navigation3` **1.3.0** (Skein's material3-adaptive version) turns `[ChatList, Chat(id), Context(id)]` into one pane on the outer display and two on the inner, with the same back stack. `listPane{}`/`detailPane()`/`extraPane()` metadata (source jar `ListDetailSceneStrategy.kt` L236–261). `SupportingPaneSceneStrategy` handles Graph. |
| Per-destination retained state (`ViewModel`) | none. Skein uses plain `@Stable` classes, which die with the composition | per `NavBackStackEntry` | per `NavEntry` via `rememberViewModelStoreNavEntryDecorator()` (`lifecycle-viewmodel-navigation3` 2.11.0, the same version as Skein's lifecycle) |
| Per-destination `rememberSaveable` survives re-layout | only if call sites are stable | yes | yes, via `rememberSaveableStateHolderNavEntryDecorator()`, keyed by entry, not by call site |
| Predictive back | manual `BackHandler`s | yes | yes (`NavigationBackHandler` + `rememberNavigationEventState`, see JetNews) |
| Who owns the back stack | Skein | the library (opaque `NavController`) | **Skein** (a `SnapshotStateList` it can read, test and rewrite, e.g. "open this chat and its inspector") |
| Production adopters inspected | — | Reply (compose-samples) | **Now in Android** (Nav3 1.0.0 plus adaptive-navigation3, `NOWINANDROID.md`) and **JetNews** (Nav3 1.2.0-rc01 with a custom `ListDetailScene`, `COMPOSE_SAMPLES.md`) |
| New dependencies | none | `navigation-compose` | `navigation3-runtime`/`-ui` 1.2.0, `lifecycle-viewmodel-navigation3` 2.11.0, `material3-adaptive-navigation3` 1.3.0. All Apache-2.0 and all androidx. `kotlinx-serialization` is already in the catalog. |

**Recommendation: adopt Navigation 3, through a Wave-3 prototype, and do not add Navigation Compose 2.x.**
- Nav3's back stack is the one object that stays constant across a fold. The *Scene* is the part that changes. That is exactly how §24 is phrased.
- The M3 adaptive team ships first-party scene strategies for the two canonical layouts Skein needs, at Skein's exact material3-adaptive version.
- A hand-rolled switch could be patched (hoist the state, add `movableContentOf`), but it would re-implement Nav3 scenes badly.

Keep `NavState`/`TabsState` working until the prototype passes §25 on the device. The tab-strip concept (preview/pinned tabs, `⧉` split) is an IA question for §20. In Nav3 terms, "tabs" become either top-level sub-stacks (NiA's `NavigationState.subStacks`) or a "Recent" list. They do not remain a second, parallel navigation model.

### 4.3 `android:configChanges`: declare it, *and* make state survive recreation

Current Android guidance (["Handle configuration changes"](https://developer.android.com/guide/topics/resources/runtime-changes), section "Restrict activity recreation") says:

> "You can prevent automatic activity recreation for certain configuration changes. In modern Compose-only apps, your UI is recomposed either way, but it's recommended to handle the configuration change directly."

It lists the benefits as improved performance, "Fluid animations … smooth layout transitions", and "State preservation". For size changes it says:

> "`Activity` recreation is disabled for size-based configuration changes when you have `android:configChanges="screenSize|smallestScreenSize|orientation|screenLayout"` in your manifest file."

The same page also says:

> "**Avoid opting out as a quick fix:** Don't opt out of `Activity` recreation as a shortcut to avoid state loss. … you can still lose the state due to `Activity` recreation from other configuration changes, process death, or closing the app. It is impossible to entirely disable `Activity` recreation."

**Recommendation for Skein: do both.**

1. **Declare** `android:configChanges="screenSize|smallestScreenSize|screenLayout|orientation"` on `app.skein.MainActivity`.
   - Skein qualifies as "Compose-only". A grep of `app/src/main`, `feature/` and `core/` finds no `AndroidView(`, no `AndroidFragment`, and no `LocalConfiguration`/`onConfigurationChanged` reader that would need manual refresh. The biometric prompt's headless fragment is unaffected.
   - Gain: generation, model import, `BiometricPrompt` and drawer animations continue through a fold. The fold becomes a recomposition, not a teardown.
   - Add `density` **only if** §1.4 shows the outer panel at a different density. Compose re-reads density from the configuration, but test the graph canvas and any `painterResource` that depends on density first.
   - Android 17 (API 37, which Skein targets and the owner's phone runs) no longer restarts activities for `keyboard|keyboardHidden|navigation|touchscreen|colorMode` by default (same page, section "Android 17").
2. **And** make every §24 item survive recreation anyway (checklist in §5.6). Recreation still happens for Display-size (density) changes, locale, night mode, font scale, process death and task restore. Without state that survives recreation, the flags only hide the bug.
3. Guard it: extend the existing `ManifestPolicyTest` to assert the flags on `MainActivity`. Add a Robolectric test where `ActivityController.configurationChange(newConfig)` must keep the same Activity instance.

### 4.4 What should Skein show when the inner display *is* Medium?

This happens at a larger Display size, in free-form or desktop windows, on tablets in portrait, and in future devices. Material's default directive gives **one pane** at Medium. Its code comment on the two-pane variant is explicit:

> "We recommend to use `calculatePaneScaffoldDirective`, unless you have a strong use case to show two panes on a medium-width window, which can make your layout look too packed."

(adaptive-layout 1.3.0 `PaneScaffoldDirective.kt` L109–125, `calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth`.)

For Skein:

- **Chat at Medium: one pane.** Show the conversation, full width up to a readable max of about 720 dp, plus a collapsed **navigation rail** (M3's `WideNavigationRailCollapsed` for Medium width with height ≥ 480). The conversation list is the list pane of the same list-detail scene. At one partition it is reached by back or the rail's Chats item, not by a drawer.
- **Context inspector at Medium: a levitated side sheet** (`AdaptStrategy.Levitate(alignment = Alignment.CenterEnd, scrim = { LevitatedPaneScrim() })`) over the chat. It is not a pane.
- **Knowledge at Medium: PROTOTYPE two panes** with `calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth`, for Knowledge only. Reading a document next to its list is the "strong use case". At 780 dp, an 80 dp rail, a 300 dp list and a 24 dp spacer leave ≈ 376 dp of document. That is tight, which is why this is a prototype and not a default. Decide from Roborazzi screenshots at the `fold-inner-medium` qualifier (`ROBORAZZI.md` §5).
- **Navigation at Medium: rail, not drawer.** Material's `navigationSuiteType` does the same whenever height is not Compact.

---

## 5. Proposed Skein adaptive strategy

### 5.1 Principles

1. **Measure; don't assume.** Every decision is a pure function of `WindowAdaptiveInfo` (width class, height class, posture). Keep Skein's existing `computeAdaptiveLayout` idea of a pure, unit-tested decision function, but wrap Material's `calculatePaneScaffoldDirective` instead of re-deriving tiers.
2. **Two content panes need width ≥ 840 dp *and* height ≥ 600 dp.** The height gate is a Skein-specific deviation from M3, justified by §22 ("closed = single dominant workspace") and by chat ergonomics: the IME covers roughly 45 % of a landscape phone. It excludes every phone in landscape, including the owner's closed Fold at 330 dpi (1175 × 524). It keeps every Fold inner configuration (height ≥ 780 at any Display size) and every tablet. Implement it as `directive.copy(maxHorizontalPartitions = 1)` when height < 600 dp. The constant needs its own test and comment.
3. **Three content panes only from Large (≥ 1200 dp)**, which is M3's default. §1.3 shows why the Fold never qualifies: at 1043 dp, rail 80 + list 260 + two spacers 48 + inspector 280 leaves a 375 dp chat, narrower than the closed phone.
4. **One navigation system per size.** A drawer where there is no room for a rail. A rail where there is. Never both visible.
5. **Panes are destinations.** The inspector, the node detail and a document's relationships are back-stack entries, not Booleans. That is what lets Material turn them into sheets or panes automatically, and it gives Test A ("secondary panes disappear appropriately") for free.

### 5.2 Per window class

| Window (examples) | Directive (max horizontal panes) | Navigation component | Chat | Context inspector | Knowledge | Graph |
| --- | --- | --- | --- | --- | --- | --- |
| **Compact width** (Fold outer portrait 443 × 994; narrow phones 360–411; inner split-screen halves ≈ 437–517) | 1 | **Modal drawer** from a `☰` in the top bar (it holds the destinations plus the recent conversations, §22 mock-up). **Deliberately not** M3's `ShortNavigationBarCompact`: a bottom bar would fight the composer and the IME for the bottom edge. The JetStream precedent picks navigation by context; PocketPal restraint points the same way. | Single pane, full-width composer, list reached via the drawer or back | **Levitated bottom sheet** (`Levitate(Alignment.BottomCenter, scrim).onlyIfSinglePane(directive)`), or reflowed under the chat only if height ≥ 900 | List, then a full-screen detail route; relationships as a bottom sheet | Canvas full screen; node detail as a bottom sheet (`SupportingPaneScaffold` supporting = `Levitate(BottomCenter)`) |
| **Compact height, width ≥ 600** (Fold outer landscape 994 × 443; outer at 330 dpi landscape 1175 × 524 is caught by the height < 600 gate) | 1 (Skein gate) | Modal drawer (same as Compact). M3 would pick `ShortNavigationBarMedium` here, which costs 64 dp of scarce height. | Single pane, composer docked, transcript above | Side sheet (`Levitate(CenterEnd)`) | Single pane | Canvas; node sheet |
| **Medium width, height ≥ 600** (inner at a larger Display size ≈ 780–820; tablets portrait; free-form windows) | 1. **Knowledge only: 2** (PROTOTYPE, `…WithTwoPanesOnMediumWidth`) | **Collapsed rail** (`WideNavigationRailCollapsed`, 80–96 dp), which is M3's default | Single pane (max readable width ≈ 720 dp, centred), list via the rail or back | Side sheet (`Levitate(CenterEnd)`) | `Documents \| Document` if the prototype passes, otherwise a single pane | Canvas; node side sheet |
| **Expanded width, height ≥ 600** (**Fold inner stock 852 × 883 / 883 × 852**; **owner's inner 1007 × 1043 / 1043 × 1007**; tablets landscape < 1200) | 2 | **Collapsed rail, narrow 80 dp.** Keep the rail visible: it replaces today's 40 dp glyph rail and the modal drawer. | **`Conversations \| Chat`**, list pane `preferredWidth(320.dp)` (the M3 default is 360 dp; at stock 852 dp, 852 − 80 − 360 − 24 leaves a 388 dp chat, narrower than the outer display, while 320 leaves ≈ 428 dp; at the owner's 1043 it leaves ≈ 619 dp), with a pane-expansion drag handle | **Extra pane.** Opening it makes the scene `Chat \| Context`, and the list hides. That is Material's two-partition behaviour, and back restores `Conversations \| Chat`. PROTOTYPE the alternative of levitating it as a 360 dp side sheet to keep the list; choose from screenshots. | `Documents \| Document`; relationships/backlinks as the **Extra** pane (replaces the list when opened) | `SupportingPaneScaffold`: `Canvas \| Selected node` |
| **Large / XL width, height ≥ 600** (tablets ≥ 1280; desktop and external-display windows) | 3 | **Expanded rail** (`WideNavigationRailExpanded`, 220–360 dp) or a permanent drawer from 1200 dp (Reply's rule, `COMPOSE_SAMPLES.md`) | **`Conversations \| Chat \| Context`**, i.e. §21 exactly | Third pane, shown on demand | `Documents \| Document \| Relationships` | `Canvas \| Node \| Related` |
| **Tabletop** (inner, half-open, horizontal hinge, i.e. the owner's landscape grip half-folded) | M3 gives 2 vertical partitions. Skein: 1 horizontal | M3 bar → Skein keeps whatever the width class gives, minus the drawer gesture | Transcript above the hinge; composer below it (pad the transcript bottom by the hinge bounds from `windowPosture.hingeList`). P2. | Sheet | Document above; controls below | Canvas above; node detail below (Reflow) |
| **Book** (inner, half-open, vertical hinge, portrait grip) | 2. The default `HingePolicy.AvoidSeparating` excludes the hinge, so the split lands on the fold | Rail | `Conversations \| Chat` split at the fold | Extra pane | `Documents \| Document` at the fold | `Canvas \| Node` at the fold |

**Minimum-width rules inside panes:** chat bubbles and the composer need ≥ 360 dp. Below that (only a pathological split or free-form window), drop to one pane regardless of class. That is a second guard in the same decision function.

### 5.3 Wiring sketch (reference only; the Wave-3 implementer owns the code)

```kotlin
// One pure function, unit-tested per row of §5.2. `windowSize` is the *measured* window
// (LocalWindowInfo.current.containerDpSize): WindowSizeClass.minHeightDp is only the bucket's
// lower bound (0 / 480 / 900), so it cannot express the 600 dp gate.
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
fun skeinPaneDirective(info: WindowAdaptiveInfo, windowSize: DpSize): PaneScaffoldDirective {
    val base = calculatePaneScaffoldDirective(info)            // M3: 1/1/2/3 by width class
    val tallEnough = windowSize.height >= SKEIN_TWO_PANE_MIN_HEIGHT // 600.dp
    return if (!tallEnough) base.copy(maxHorizontalPartitions = 1) else base
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun SkeinNavDisplay(backStack: NavBackStack<NavKey>, info: WindowAdaptiveInfo = currentWindowAdaptiveInfoV2()) {
    val directive = skeinPaneDirective(info, LocalWindowInfo.current.containerDpSize)
    val listDetail = rememberListDetailSceneStrategy<NavKey>(
        directive = directive,
        adaptStrategies = ListDetailPaneScaffoldDefaults.adaptStrategies(
            extraPaneAdaptStrategy = AdaptStrategy.Levitate(
                alignment = if (info.windowSizeClass.minWidthDp < 600) Alignment.BottomCenter else Alignment.CenterEnd,
                scrim = { LevitatedPaneScrim() },
            ).onlyIfSinglePane(directive),
        ),
    )
    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        sceneStrategy = listDetail, // chained with rememberSupportingPaneSceneStrategy for Graph
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            entry<ChatListKey>(metadata = ListDetailSceneStrategy.listPane { ChatEmptyDetail() }) { ChatListPane() }
            entry<ChatKey>(metadata = ListDetailSceneStrategy.detailPane()) { key -> ChatPane(key.docId) }
            entry<ChatContextKey>(metadata = ListDetailSceneStrategy.extraPane()) { key -> ContextInspector(key.docId) }
            // KnowledgeListKey / DocKey / RelationsKey follow the same shape; GraphKey uses SupportingPaneSceneStrategy.
        },
    )
}
```

(`minWidthDp`/`minHeightDp` exist in `window-core` 1.5.1, `WindowSizeClass.kt` L58–60, but they hold the **bucket lower bound**, not the window size. That is why the height gate reads `LocalWindowInfo.current.containerDpSize`, the same source `currentWindowAdaptiveInfoV2` uses and the one that `DeviceConfigurationOverride.WindowSize` overrides in tests.)

### 5.4 Chat context inspector and knowledge detail across transitions

| Transition | Before | After (no reset) |
| --- | --- | --- |
| Inner (Expanded) → fold → outer (Compact), inspector open | back stack `[ChatList, Chat(42), Context(42)]`, scene `Chat \| Context` | same back stack; scene = single pane `Chat(42)` with **`Context(42)` levitated as a bottom sheet**; back closes the sheet (Test A) |
| Outer → unfold → inner, chat open | `[ChatList, Chat(42)]`, single pane | `Conversations \| Chat(42)` (Test B); no duplicate chat, because the key is identity |
| Inner, document open → fold | `[KnowledgeList, Doc(7)]`, two panes | single pane `Doc(7)`; back returns to the list (Test E) |
| Graph, node selected → fold | `[Graph(focus = 7, selected = 12)]`, `Canvas \| Node` | canvas full screen with node 12 highlighted; node detail as a sheet (Test F) |
| Outer, drawer open → unfold | modal drawer open | drawer state **forced closed** when the navigation type is not the drawer (JetNews `rememberSizeAwareDrawerState`, `COMPOSE_SAMPLES.md`) (Test G) |

### 5.5 Hinge and posture handling

- Source: `currentWindowAdaptiveInfoV2().windowPosture` (`isTabletop`, `hingeList` with `isSeparating`/`isOccluding`/`bounds`). Drop `FoldPosture.kt`/`rememberFoldPosture`. Inject `WindowAdaptiveInfo` as a parameter so tests and previews can force postures (NiA passes `windowAdaptiveInfo = WindowAdaptiveInfo(windowSizeClass, Posture())`, see `NOWINANDROID.md`).
- Book posture: nothing bespoke. `HingePolicy.AvoidSeparating` (the default) puts the pane split on the hinge.
- Tabletop: single pane, controls below the hinge (P2). The owner's landscape habit makes this the most likely half-fold.
- Flat: no hinge avoidance. The inner panel is continuous.
- Never put interactive controls within the hinge `bounds` when `isSeparating` ([fold-aware guidance](https://developer.android.com/develop/ui/compose/layouts/adaptive/foldables/make-your-app-fold-aware)).

### 5.6 Live-transition state-preservation checklist (exact APIs)

| # | §24 item | Holder | API |
| --- | --- | --- | --- |
| 1 | No Activity restart on fold, unfold or resize | manifest | `android:configChanges="screenSize|smallestScreenSize|screenLayout|orientation"` (+ `density` if §1.4 says so) on `MainActivity`; guarded by `ManifestPolicyTest` |
| 2 | Current workspace, active conversation, selected document, open inspector | Nav3 back stack | `@Serializable data class ChatKey(val docId: String) : NavKey`, etc.; `rememberNavBackStack(ChatListKey)` (saveable across recreation and process death) |
| 3 | Per-screen UI state (expanded activity rows, citation excerpts, list scroll) | per-entry saveable holder | `rememberSaveableStateHolderNavEntryDecorator()`; `rememberSaveable` inside panes; `rememberLazyListState()` |
| 4 | **Response being generated** | retained, not in the composition | Move collection of `SendPipeline.send(...)` out of `rememberCoroutineScope()`. Preferred: a **vault-session-scoped** turn controller keyed by chat `docId` (it lives with `VaultSession`, is cancelled by lock, and outlives any UI). Minimum: `class ChatViewModel : ViewModel()` using `viewModelScope`, obtained via `viewModel { … }` under `rememberViewModelStoreNavEntryDecorator()`. UI reads `StateFlow<ChatUiState>` through `collectAsStateWithLifecycle()`. |
| 5 | **Draft text** | retained holder (+ an owner decision on process death) | `val draft = TextFieldState()` in the retained `ChatViewModel`, bound with `BasicTextField(state = viewModel.draft)`. Surviving *process death* needs either `rememberSaveable(saver = TextFieldState.Saver)`/`SavedStateHandle`, which put **plaintext in the Activity's saved-state Bundle held by `system_server`** (Skein's editor already does this for note text, `EditorState` via `rememberSaveable`), or an encrypted draft row in the vault. **Owner decision**: flag it for §9-style review. |
| 6 | Scroll position | saveable `LazyListState` + no forced scroll | Keep `rememberLazyListState()`. Switch to `reverseLayout = true` (Jetchat) so "at bottom" is index 0. **Delete** `LaunchedEffect(itemCount) { animateScrollToItem(last) }`. Auto-follow only while `listState.firstVisibleItemIndex == 0`. |
| 7 | Selected graph node, zoom and pan | back-stack key + saveable | `GraphKey(focusDocId, selectedNodeId)`; `rememberSaveable(stateSaver = …)` for zoom and pan (or the graph `ViewModel`). Replace `var graphDocId by remember` in `MainActivity`. |
| 8 | Drawer or sheet state (Test G) | size-aware | JetNews `rememberSizeAwareDrawerState(isDrawerNavigation)`, which returns a fresh `DrawerState(Closed)` when the drawer isn't the navigation type. Levitated panes resolve through the directive automatically. |
| 9 | Focus and IME (Test D) | saveable flag | `rememberSaveable { mutableStateOf(false) }` for "composer focused"; `FocusRequester.requestFocus()` after a posture change. `WindowInsets.ime` is consumed by the composer only (Jetchat pattern). Expect the IME to hide on a display swap. The draft and focus must survive; showing the IME again is optional. |
| 10 | Active model, persona, attached context | already persisted (vault or prefs) or session-scoped | Keep them out of UI state. Attached-context chips belong to the retained chat holder (#4). |
| 11 | Long-running open tasks (model import) | not the composition | Move the import off `rememberCoroutineScope()` (`MainActivity.kt` L465): Application/session scope or WorkManager, with progress as a `StateFlow`. |
| 12 | Stable composition identity | layout | No state-owning content under alternating `when` call sites (`AdaptivePaneHost` today). `NavDisplay`/`ListDetailPaneScaffold` key content by entry or role. If an interim hand-rolled layout remains, wrap pane content in `remember { movableContentOf { … } }`. |

### 5.7 §25 acceptance tests: where each one runs

| Test | JVM (Robolectric, Mac and CI) | Device or emulator |
| --- | --- | --- |
| A Open → Closed; B Closed → Open | **Live re-layout path:** one composition, wrap the shell in `DeviceConfigurationOverride(DeviceConfigurationOverride.WindowSize(size))` (present in `ui-test` 1.12.1, and it overrides `LocalWindowInfo`, which `currentWindowAdaptiveInfoV2` reads), and flip `size` between `DpSize(1043.dp, 1007.dp)` and `DpSize(443.dp, 994.dp)` via a `mutableStateOf`. Assert the back stack, draft, active chat and sheet or pane. **Recreation path:** `StateRestorationTester` for saveables; `ActivityScenario.recreate()` for retained holders. Screenshots of each end state with Roborazzi. | Pixel 9 Pro Fold AVD on the Mac (Extended Controls → fold/unfold). Espresso Device API `onDevice().setClosedMode()/setFlatMode()/setTabletopMode()` on the emulator lane (`skein-k3b2`). On the owner's phone, candidate automation is `adb shell cmd device_state state 0` / `state reset` (shell holds `CONTROL_DEVICE_STATE`; validate before relying on it). |
| C While generating | a fake `SendPipeline` emitting slowly; flip size mid-stream; assert the turn continues and is not interrupted | the real model on the Fold |
| D Keyboard active | focus plus text; flip size; assert the draft and focus (Jetchat's custom `keyboardShownProperty` semantics key pattern, `COMPOSE_SAMPLES.md`) | real IME |
| E Knowledge; F Graph | as A/B with `DocKey`/`GraphKey` | device |
| G Drawer/sheet | open the drawer at Compact, flip to Expanded, assert the drawer is closed and the rail is visible | device |

---

## 6. Summary for the orchestrator

- **Geometry:** inner 852 × 883 dp (stock 390 dpi) or **1007 × 1043 dp** (owner's measured 330 dpi override); both are **Expanded**. Outer 443 × 994 dp at stock (Compact); the outer override is unverified. Medium inner only happens at a *larger* Display size.
- **Layout:** Material directive + a Skein height gate (≥ 600 dp for two panes). Two panes on the Fold inner display; three only at ≥ 1200 dp. The inspector is an extra pane that levitates on small windows.
- **Navigation:** Navigation 3 + adaptive-navigation3 1.3.0 scene strategies (prototype first). `NavigationSuiteScaffold` with a drawer on Compact and compact height, a rail on Medium and Expanded.
- **configChanges:** declare the four size flags (plus `density` if the outer panel differs) **and** fix the six state holders that die today: the draft, the generation, the inspector flag, forced scroll, the graph selection and the model import.

What specific problem in Skein can this project help us solve? Replacing the hand-rolled `AdaptivePaneHost` width tiers, which re-parent content, drop state on every fold, give the closed-landscape Fold two panes and waste a Medium window, with Material's directive-driven list-detail and supporting-pane scaffolds and a single navigation suite. The same back stack then reflows between the 443 dp outer display and the 852–1043 dp inner display without resetting the user's chat, draft, document or graph selection.
