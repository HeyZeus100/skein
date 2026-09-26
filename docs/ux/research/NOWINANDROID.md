# Now in Android: production-architecture reference study for Skein

**Bead:** `skein-xtov.7` (epic `skein-xtov`) · **Authority:** `docs/research/SKEIN_UI_UX_OVERHAUL_PROMPT.md` §§1–3, 11, 12, 21–25, 37–39, 46, 52
**Companions:** `ANDROID_ADAPTIVE_SAMPLES.md` (geometry, adaptive strategy, navigation and `configChanges` decisions), `COMPOSE_SAMPLES.md`, `ROBORAZZI.md`
**Status:** Research record. Advisory. It is a structural reference; §11 says "do not visually imitate it".

---

## 0. Provenance

| Field | Value |
| --- | --- |
| Repository | <https://github.com/android/nowinandroid> |
| Commit inspected | `a49ed253d75e61a2b6ab80a8da677b57437b08eb` (`main`, 2026-09-22, "Enable parallel IDE sync (#2140)") |
| Licence | `Apache-2.0` (root `LICENSE`; GitHub SPDX `Apache-2.0`) |
| Local clone | `research/clones/nowinandroid/` (shallow, git-ignored, never committed) |
| Relevant versions (`gradle/libs.versions.toml`) | AGP **9.3.2**, Kotlin 2.3.0, compose-bom-alpha 2025.09.01, material3-adaptive 1.1.0-rc01, **material3-adaptive-navigation3 1.3.0-alpha04**, **navigation3 1.0.0**, lifecycle-viewmodel-navigation3 2.10.0, **Robolectric 4.16**, **Roborazzi 1.56.0** (the first release with AGP 9 support) |
| Inspection date | 2026-09-26 |

Permalink prefix: `NIA` = `https://github.com/android/nowinandroid/blob/a49ed253d75e61a2b6ab80a8da677b57437b08eb`

---

## 1. Project assessment (§52 format)

### Project

<https://github.com/android/nowinandroid> at `a49ed253` (2026-09-22). This is Google's reference production app: multi-module, offline-first, Compose-only, adaptive, with a JVM screenshot suite.

### License

`Apache-2.0`. Compatible with Skein (Apache-2.0).

### Maintenance

Very active: 21.9 k stars, `pushed_at` 2026-09-26, 281 open issues/PRs, CI on every PR. It migrated to **Navigation 3** with `api`/`impl` feature modules. That is the strongest available signal that Nav3 is Google's forward path for Compose apps. Its compose BOM lags slightly (an alpha BOM from 2025-09), so treat NiA as an **architecture** reference, not a version reference.

### Relevant Skein problem

1. **Dependency direction forced six slots onto the shell.** `:feature:shell` cannot depend on the feature modules that host real screens. As a result `SkeinApp` takes `destinationContent`, `noteTabContent`, `chatTabContent`, `timelinePane`, `overlay` and `extraCommands` lambdas, which `:app` fills in (`SkeinApp.kt` L56–157 documents each one). Each new surface adds another slot.
2. **No retained, testable screen state.** Screen state holders (`ChatViewModel`, `SettingsViewModel`) are plain `@Stable` classes created in composition. This is a deliberate precedent, documented in `SettingsViewModel.kt` L31–35. They die with the composition, which causes the §24 losses.
3. **Screens are not renderable from fixtures without the data layer.** `ChatScreen(docId, vaultRepository, sendPipeline, …)` builds its own state holder (`ChatScreen.kt` L47–71). Screenshot tests of real states therefore need a live pipeline, or a separate preview-only composable (`ChatScreenPreviews.kt`).
4. **No screenshot architecture** (§39): no device qualifiers, no baselines, no CI verification.

### Patterns worth adopting

**Architecture and state**

| Pattern | Where | Skein use |
| --- | --- | --- |
| **Sealed `UiState` + `ViewModel` + `StateFlow` via `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Loading)`** | `NIA/feature/interests/impl/src/main/kotlin/…/InterestsViewModel.kt` [L51–62, L84–93](https://github.com/android/nowinandroid/blob/a49ed253d75e61a2b6ab80a8da677b57437b08eb/feature/interests/impl/src/main/kotlin/com/google/samples/apps/nowinandroid/feature/interests/impl/InterestsViewModel.kt#L51-L93) (`sealed interface InterestsUiState { Loading; Interests(selectedTopicId, topics); Empty }`) | `ChatUiState`, `KnowledgeUiState`, `GraphUiState`. The `WhileSubscribed(5_000)` grace period is **exactly the fold window**: collectors disappear for about 1 s during recreation, and the upstream (vault observers) is *not* restarted. |
| **Selection and query in `SavedStateHandle`** (`savedStateHandle.getStateFlow(key, initial)`) | `InterestsViewModel.kt` L51 (selected topic); `feature/search/impl/.../SearchViewModel.kt` [L53](https://github.com/android/nowinandroid/blob/a49ed253d75e61a2b6ab80a8da677b57437b08eb/feature/search/impl/src/main/kotlin/com/google/samples/apps/nowinandroid/feature/search/impl/SearchViewModel.kt#L53) (search query) | The selected graph node, the selected document and the Knowledge search query survive both recreation and process death. **The chat draft is different:** `SavedStateHandle` stores plaintext in the saved-state Bundle held by `system_server`. That is an owner decision; see `ANDROID_ADAPTIVE_SAMPLES.md` §5.6 #5. |
| **Stateful entry → stateless screen split.** The public `ForYouScreen(onTopicClick, viewModel = hiltViewModel())` collects flows and calls an `internal fun ForYouScreen(isSyncing, onboardingUiState, feedState, …callbacks)` | `NIA/feature/foryou/impl/src/main/kotlin/…/ForYouScreen.kt` [L109–135](https://github.com/android/nowinandroid/blob/a49ed253d75e61a2b6ab80a8da677b57437b08eb/feature/foryou/impl/src/main/kotlin/com/google/samples/apps/nowinandroid/feature/foryou/impl/ForYouScreen.kt#L109-L135); previews L506–600 | **The single most important enabler for §37 Layer A and §39.** Every Skein surface gets a stateless `XxxScreen(uiState, callbacks)` that previews and Roborazzi tests render from fixtures, with no vault and no model. `ChatScreen` is the first candidate. |
| **Test doubles, not mocks,** in `:core:testing` (`TestNewsRepository`, `TestUserDataRepository`, test data objects) | `NIA/core/testing/src/main/kotlin/…/{repository,data}/` | Skein already has `:testing` with `InMemoryVaultRepository`, `RecordingTabController` and `SkeinLogCaptureRule`. Extend it with `ChatUiState`/`KnowledgeUiState` **fixtures** (long titles, long model names, very long conversations, §37 Layer B) that previews and screenshots share. |

**Navigation (Navigation 3)**

| Pattern | Where | Skein use |
| --- | --- | --- |
| **`NavigationState` = one top-level back stack + one sub-stack per top-level key**, all `rememberNavBackStack` (saveable across config change and process death) | `NIA/core/navigation/src/main/kotlin/…/NavigationState.kt` [L37–78](https://github.com/android/nowinandroid/blob/a49ed253d75e61a2b6ab80a8da677b57437b08eb/core/navigation/src/main/kotlin/com/google/samples/apps/nowinandroid/core/navigation/NavigationState.kt#L37-L78) | Chat, Knowledge and Graph each keep their own stack. Switching destinations and back restores the open chat or document, and the fold changes none of it. |
| **Entries decorated with `rememberSaveableStateHolderNavEntryDecorator()` + `rememberViewModelStoreNavEntryDecorator()`** | `NavigationState.kt` [L83–102](https://github.com/android/nowinandroid/blob/a49ed253d75e61a2b6ab80a8da677b57437b08eb/core/navigation/src/main/kotlin/com/google/samples/apps/nowinandroid/core/navigation/NavigationState.kt#L83-L102) (`ViewModelStore` decorator at L90) | Per-destination retained `ViewModel`s and per-destination `rememberSaveable`, *keyed by entry, not by call site*. This removes the pane re-parenting hazard identified in `ANDROID_ADAPTIVE_SAMPLES.md` §2.2. |
| **`Navigator` with explicit rules** (re-selecting a top-level key clears its sub-stack; going back from a top-level root pops to the previous top level; `goToKey` de-duplicates) | `core/navigation/.../Navigator.kt` L26–90, plus a unit test `NavigatorTest.kt` | Skein's rules ("open chat 42 when it is already open" must not duplicate, per Test B's "no duplicate chat") become plain unit tests on a list. |
| **`feature:X:api` exposes only `@Serializable NavKey`s + `Navigator.navigateToX()` extensions; `feature:X:impl` owns the `EntryProviderScope<NavKey>.xEntry(navigator)` and screens; `:app` assembles `entryProvider { forYouEntry(navigator); … }`** | `NIA/feature/topic/api/src/main/kotlin/…/TopicNavKey.kt` (`data class TopicNavKey(val id: String) : NavKey`); `feature/interests/impl/.../InterestsEntryProvider.kt` [L31–51](https://github.com/android/nowinandroid/blob/a49ed253d75e61a2b6ab80a8da677b57437b08eb/feature/interests/impl/src/main/kotlin/com/google/samples/apps/nowinandroid/feature/interests/impl/navigation/InterestsEntryProvider.kt#L31-L51); `app/.../NiaApp.kt` [L257–271](https://github.com/android/nowinandroid/blob/a49ed253d75e61a2b6ab80a8da677b57437b08eb/app/src/main/kotlin/com/google/samples/apps/nowinandroid/ui/NiaApp.kt#L257-L271) | **Solves problem 1.** The shell hosts `NavDisplay` and the navigation suite. Features contribute entries; features reach one another through `api` keys. Nothing needs to be threaded through `SkeinApp` slots. Skein can start lighter: one `:core:navigation` module with all `NavKey`s, adding `api`/`impl` splits only where a cycle appears. |
| **Adaptive list-detail via metadata**: `entry<InterestsNavKey>(metadata = ListDetailSceneStrategy.listPane { InterestsDetailPlaceholder() })`, `entry<TopicNavKey>(metadata = ListDetailSceneStrategy.detailPane())`, `NavDisplay(entries, sceneStrategy = rememberListDetailSceneStrategy(), onBack)` | `InterestsEntryProvider.kt` L33–36; `feature/topic/impl/.../TopicEntryProvider.kt` L32–35; `NiaApp.kt` L257–271 | Exactly Skein's Chat and Knowledge: `ChatListKey` (list, placeholder "Start a conversation") / `ChatKey(docId)` (detail) / `ChatContextKey(docId)` (extra). See `ANDROID_ADAPTIVE_SAMPLES.md` §5.3. |
| `NiaNavigationSuiteScaffold` wrapper in `:core:designsystem` using `NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(windowAdaptiveInfo)` | `NIA/core/designsystem/.../component/Navigation.kt` L186–193 | Put Skein's navigation-suite wrapper, with its own type policy (drawer on Compact and compact height), in the design-system layer. Material 1.4.0's newer `navigationSuiteType()` is preferable to NiA's `calculateFromAdaptiveInfo`. |
| **`WindowAdaptiveInfo` is a parameter with a default** (`windowAdaptiveInfo: WindowAdaptiveInfo = currentWindowAdaptiveInfo()`) | `NiaApp.kt` L90–94, L141–148 | Tests and screenshots inject size and posture. Skein already does this for `windowSizeClass` and `posture` (`SkeinApp.kt` L210–211). Collapse the two into a single `WindowAdaptiveInfo` parameter when `FoldPosture` is removed. |

**Modularisation and component boundaries**

| Pattern | Where | Skein use |
| --- | --- | --- |
| `:core:designsystem` (theme, tokens, `Nia*` wrappers of M3 components) vs `:core:ui` (shared UI that knows domain models, plus `@DevicePreviews`) | `settings.gradle.kts` `include(":core:designsystem")`, `include(":core:ui")`; `core/ui/.../DevicePreviews.kt` L26–31 | Skein keeps its theme, tokens and `SecureTextField` inside `:feature:shell`, which is why `:feature:settings`/`:feature:editor`/`:feature:chat` depend on the *shell*. That is the root of the cycle in problem 1. Extract `:core:designsystem` (theme, tokens, secure inputs, navigation-suite wrapper) so features depend on it, not on the shell. §36 (design system) and §48 Wave 2 are the natural moment. |
| Module graph README per module (auto-generated mermaid) | `core/screenshot-testing/README.md` | Nice to have. Skein's `docs/ARCHITECTURE.md` already holds the module map. |

**Screenshot-testing architecture (§12 and §39): the most directly reusable part**

| Pattern | Where | Skein use |
| --- | --- | --- |
| Dedicated **`:core:screenshot-testing`** module that `api`s `roborazzi`, `roborazzi-accessibility-check`, compose-ui-test and Robolectric, and holds the capture helpers | `NIA/core/screenshot-testing/build.gradle.kts`; `.../ScreenshotHelper.kt` | Skein: `:testing:screenshot` (or inside `:testing`), with Skein's device matrix and theme wrapper. The spike `skein-xtov.9` decides the module name. |
| `DefaultRoborazziOptions`: `CompareOptions(changeThreshold = 0f)` (pixel-perfect) + `RecordOptions(resizeScale = 0.5)` (halves PNG size) | `ScreenshotHelper.kt` [L56–62](https://github.com/android/nowinandroid/blob/a49ed253d75e61a2b6ab80a8da677b57437b08eb/core/screenshot-testing/src/main/kotlin/com/google/samples/apps/nowinandroid/core/testing/util/ScreenshotHelper.kt#L56-L62) | Adopt both. A 330 dpi inner-landscape capture is 2152 × 2076 px; at 0.5 it becomes 1076 × 1038. |
| **`captureMultiDevice(name) { … }`** loops an enum of `spec:` strings, calls `RuntimeEnvironment.setQualifiers("w${w}dp-h${h}dp-${dpi}dpi")`, sets content under `LocalInspectionMode provides true` + `DeviceConfigurationOverride.DarkMode(darkMode)`, **runs ATF accessibility checks** via `checkRoboAccessibility(...)` *before* capturing, and rethrows accessibility failures only after the screenshot is written | `ScreenshotHelper.kt` [L64–150](https://github.com/android/nowinandroid/blob/a49ed253d75e61a2b6ab80a8da677b57437b08eb/core/screenshot-testing/src/main/kotlin/com/google/samples/apps/nowinandroid/core/testing/util/ScreenshotHelper.kt#L64-L150) (`setQualifiers` at L97) | **Adopt the shape; replace the devices.** NiA's `FOLDABLE` is `673 × 841 dp` (Medium portrait) at 480 dpi. That is not the Pixel 9 Pro Fold at any setting (§1 of `ANDROID_ADAPTIVE_SAMPLES.md`). Use Skein's measured matrix (`ROBORAZZI.md` §5). The accessibility-then-screenshot ordering covers §40's audit and §39's screenshots in one pass. |
| **`captureMultiTheme(name) { desc -> … }`**: one composition, `key(androidTheme, darkMode, dynamicTheming)` around content, then permutations captured by flipping `mutableStateOf`s | `ScreenshotHelper.kt` [L156–228](https://github.com/android/nowinandroid/blob/a49ed253d75e61a2b6ab80a8da677b57437b08eb/core/screenshot-testing/src/main/kotlin/com/google/samples/apps/nowinandroid/core/testing/util/ScreenshotHelper.kt#L156-L228) | Skein has no dynamic colour (it has its own palette, `SkeinColors`). Light and dark × font scale are the permutations. The same flip-a-state technique also simulates **fold/unfold inside one composition** for §25 (with `DeviceConfigurationOverride.WindowSize`). |
| **App-level screen-size tests:** Robolectric canvas `@Config(qualifiers = "w1000dp-h1000dp-480dpi")`, content forced to a size with `DeviceConfigurationOverride.ForcedSize(DpSize(w, h))`, and a matching explicit `WindowAdaptiveInfo(WindowSizeClass.compute(w, h), Posture())` passed to `NiaApp`. Nine tests cover compact/medium/expanded × compact/medium/expanded heights and assert *which navigation component* shows. | `NIA/app/src/testDemo/kotlin/…/ui/NiaAppScreenSizesScreenshotTests.kt` [L59–231](https://github.com/android/nowinandroid/blob/a49ed253d75e61a2b6ab80a8da677b57437b08eb/app/src/testDemo/kotlin/com/google/samples/apps/nowinandroid/ui/NiaAppScreenSizesScreenshotTests.kt#L59-L231) | The right pattern for Skein's §5.2 table: one test per row (Compact, compact-height, Medium, Expanded stock and 330, Large), each asserting drawer vs rail and 1/2/3 panes. The explicit `WindowAdaptiveInfo` also lets tests force `Posture(isTabletop = true, …)`. With compose `ui-test` 1.12.1, prefer `DeviceConfigurationOverride.WindowSize`, which overrides `LocalWindowInfo`, over `ForcedSize` + a hand-made info. |
| **Determinism**: `TimeZone.setDefault(UTC)` in `@Before`; `@LooperMode(PAUSED)`; `@GraphicsMode(NATIVE)`; `LocalInspectionMode provides true` | `ForYouScreenScreenshotTests.kt`, `NiaAppScreenSizesScreenshotTests.kt` L59–113 | Skein's timeline shows dates: pin the time zone and inject a clock. `LocalInspectionMode` also disables Skein's `rememberFoldPosture` Activity lookup path in previews. |
| **Baselines committed next to the tests** (`src/test/screenshots/<Name>_<device>[_dark].png`, `src/testDemo/screenshots/…`). 136 PNGs in total, ≈ 4.9 MB, at `resizeScale 0.5` (counted at `a49ed253`). | e.g. `feature/foryou/src/test/screenshots/ForYouScreenPopulatedFeed_foldable.png`; `core/designsystem/src/test/screenshots/Background/…` | Evidence that a committed-PNG workflow stays small at half scale. Skein's layout proposal is in `ROBORAZZI.md` §6 (a single root, `ux-baselines/`, per §39). |
| **CI:** `./gradlew verifyRoborazziDemoDebug` (continue-on-error) → on PR failure, refuse forks → `recordRoborazziDemoDebug` → auto-commit "🤖 Updates screenshots"; upload `**/build/outputs/roborazzi/*_compare.png` as an artifact. `gradle.properties` sets `roborazzi.test.verify=true`, so plain `test` also verifies; the coverage job passes `-Proborazzi.test.verify=false`. | `NIA/.github/workflows/Build.yaml` L103–157, L249–252; `gradle.properties` | Adopt **verify + compare-artifact upload**. **Do not adopt the bot auto-commit** (see below). |

### Patterns not worth adopting

- **Bot auto-commit of re-recorded screenshots on PRs** (`stefanzweifel/git-auto-commit-action`, `Build.yaml` L124–130). Skein enforces DCO sign-off (`.github/workflows/dco.yml`), and its rule is "visual changes require screenshot evidence" reviewed by a human (§2 rule 10, §39: before/after plus explanation). A bot silently re-baselining defeats that. Record locally (Mac UX lab) or in an explicit manual workflow, and commit with a sign-off.
- **Hilt** and `hiltViewModel`, `HiltTestApplication`, `@HiltAndroidTest`. Skein uses a manual composition root (`SkeinApplication` → `VaultServices` → `VaultSession`). Plain `viewModel { ChatViewModel(session…) }` factories under `rememberViewModelStoreNavEntryDecorator()` are enough.
- **Dynamic colour and `androidTheme` permutations** in `captureMultiTheme`. Skein has a fixed palette (`SkeinColors`, WCAG-checked by `SkeinColorContrastTest`).
- **NiA's device specs** (`PHONE 640×360`, i.e. landscape; `FOLDABLE 673×841`; `TABLET 1280×800`; all at 480 dpi). They are not Skein's devices, and 480 dpi inflates PNGs.
- **Network, sync and WorkManager layers, and Room/Proto DataStore patterns.** Skein is offline-first by construction (no `INTERNET` permission) and has its own vault. Out of scope (§1).
- **`api`/`impl` split for every feature on day one.** It helps a 20-module team. Skein needs it only where the shell/feature cycle bites. Start with a `:core:navigation` of keys plus a `:core:designsystem`.
- **An alpha Compose BOM** (`compose-bom-alpha`). Skein stays on the stable BOM.

### Potential reusable code

- **`ScreenshotHelper.kt`'s `captureForDevice` structure** (about 60 lines: qualifier set → content under inspection mode and theme override → ATF check → capture → rethrow). Worth re-writing for Skein with Skein's matrix. If lifted verbatim, keep the Apache-2.0 header (`Copyright 2023 The Android Open Source Project`) and add a `NOTICE` line.
- **`NavigationState` + `Navigator`** (about 170 lines, with a unit test). Small enough to re-write against Skein's keys. The de-duplication rule in `goToKey` is the part to copy semantically.
- Nothing else.

### Android/Fold relevance

- NiA is adaptive: `NavigationSuiteScaffold` picks bar or rail, and list-detail runs through `ListDetailSceneStrategy` on Nav3. It is **not fold-specific**. There is no posture logic, and its "foldable" test device is a Medium-portrait approximation.
- Its structural answers still carry over to the Fold. Back stacks that survive recreation, `ViewModel` stores per entry, and scene-based list-detail are the mechanism that keeps chat, document and inspector state constant while the scene changes between the 443 dp outer display and the 852–1043 dp inner display.

### Mac UX-Lab relevance

High.
- (a) `@DevicePreviews` (`core/ui/DevicePreviews.kt` L26–31: phone, phone_in_landscape, foldable, tablet, desktop) is the §37 Layer B pattern. Replace the devices with Skein's measured specs.
- (b) Stateless screens + shared fixtures make Layer A previews and Roborazzi baselines one artefact.
- (c) `./gradlew recordRoborazzi…`/`compareRoborazzi…` runs on the MacBook in seconds per module, with no APK install. That is the loop §37 asks for.
- (d) The accessibility check that runs alongside captures gives §40 evidence on every UI change.

### Recommended action

**ADOPT** (patterns, re-written; no Hilt, no bot commits):
- the stateless-screen split with sealed `UiState` + `ViewModel`/`StateFlow` (`WhileSubscribed(5_000)`);
- `SavedStateHandle` for non-sensitive selections;
- the Nav3 `NavigationState`/`Navigator`/entry-decorator architecture with `ListDetailSceneStrategy` metadata (via the Wave-3 prototype, per `ANDROID_ADAPTIVE_SAMPLES.md` §4.2);
- a `:core:designsystem` extraction to break the shell/feature cycle;
- the `:core:screenshot-testing` helper design (multi-device, multi-theme, ATF-then-capture, `resizeScale 0.5`, `changeThreshold 0`), with Skein's device matrix.

### Expected benefit

- The six `SkeinApp` slots disappear. New surfaces are added as entries, not shell parameters.
- Chat, Knowledge and Graph state is retained across the fold and restored after process death where appropriate.
- Every surface is renderable from fixtures, so previews and screenshots come almost for free.
- An accessibility check runs with every screenshot.

### Expected cost

Medium to high, staged:
- `:core:designsystem` extraction (mechanical, Wave 2);
- a Nav3 prototype and migration of `NavState`/`TabsState` (Wave 3, with new androidx deps: `navigation3-runtime`/`-ui` 1.2.0, `lifecycle-viewmodel-navigation3` 2.11.0, `material3-adaptive-navigation3` 1.3.0);
- converting `ChatViewModel`/`SettingsViewModel` to retained holders. This reverses the documented "plain `@Stable` class" precedent, and that should be an explicit decision recorded in the ADR/handoff;
- the screenshot helper module (spike `skein-xtov.9`).

---

## 2. Answers to the prompt's specific questions (§11 and the task brief)

| Question | Answer |
| --- | --- |
| Production Compose architecture | Offline-first repositories → `ViewModel` builds `UiState` via `combine(...).stateIn(viewModelScope, WhileSubscribed(5_000), Loading)` → a stateful entry composable collects with `collectAsStateWithLifecycle()` → an **internal** stateless screen takes `UiState` + callbacks. Skein should mirror it. Its `VaultRepository.observe*` flows already play the repository role. |
| State flows and screen state | Sealed interfaces per screen (`InterestsUiState`, `OnboardingUiState`, `NewsFeedUiState`); `SavedStateHandle.getStateFlow` for user selections. |
| Modularisation and component boundaries | `core:{designsystem, ui, navigation, data, domain, model, testing, screenshot-testing}` + `feature:X:{api,impl}`. For Skein the minimum viable version is `:core:designsystem` + `:core:navigation` (keys). |
| Adaptive design | `NavigationSuiteScaffold` (bar or rail) + `ListDetailSceneStrategy` on Nav3; `WindowAdaptiveInfo` injected for tests. No posture handling; Skein adds that (`ANDROID_ADAPTIVE_SAMPLES.md` §5.5). |
| Navigation (type-safe / Nav3?) | **Navigation 3 1.0.0** with `@Serializable` `NavKey`s, per-top-level sub-stacks, the saveable and `ViewModelStore` entry decorators, and adaptive-navigation3 `1.3.0-alpha04` scene strategies. Skein should adopt Nav3 at 1.2.0 with adaptive-navigation3 1.3.0 (stable), per the decision in `ANDROID_ADAPTIVE_SAMPLES.md` §4.2. |
| Unidirectional data flow | Events go up as callbacks (`onTopicCheckedChanged = viewModel::updateTopicSelection`); state comes down as `UiState`; navigation events go to the `Navigator`, never into the `ViewModel`. (NiA's own TODOs at `InterestsEntryProvider.kt` L42 and L45 admit one leak and one scene-unaware flag.) |
| Test organisation | `src/test` (JVM, Robolectric: unit tests, Compose UI tests, Roborazzi screenshots) vs `src/androidTest` (instrumented); `:core:testing` test doubles; `:core:screenshot-testing` capture helpers; per-flavour screenshot sets (`src/testDemo/screenshots`). |
| **Screenshot-testing architecture** | **Roborazzi 1.56.0 on Robolectric 4.16 native graphics, AGP 9.3.2.** Device qualifiers come from `spec:` strings applied with `RuntimeEnvironment.setQualifiers("w…dp-h…dp-…dpi")`. Dark and light via `DeviceConfigurationOverride.DarkMode`. `@DevicePreviews` multipreview is used for IDE previews, *not* for screenshot generation: NiA writes explicit tests rather than using Roborazzi's preview scanner. Baselines are committed PNGs under each module's `src/test/screenshots/` at half scale with a zero-tolerance compare. CI runs `verifyRoborazziDemoDebug`, uploads `*_compare.png` diffs, and on PR failure re-records and bot-commits. Skein adopts all of it except the bot commit and the device specs. |

What specific problem in Skein can this project help us solve? Breaking the `:feature:shell` ↔ feature dependency cycle that forced six content slots onto `SkeinApp`, and replacing composition-scoped `@Stable` state holders with retained, `SavedStateHandle`-backed `ViewModel`s behind Navigation 3 entries. Chat, document and graph state then survive fold, recreation and process death. The stateless-screen + fixture + `captureMultiDevice`/ATF pattern then gives Skein a screenshot-and-accessibility suite that runs on the MacBook in seconds.
