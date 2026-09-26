# Jetpack Compose samples (Jetchat, Reply, JetNews): reference study for Skein

**Bead:** `skein-xtov.7` (epic `skein-xtov`) · **Authority:** `docs/research/SKEIN_UI_UX_OVERHAUL_PROMPT.md` §§1–3, 10, 21–26, 37–39, 46, 52
**Companions:** `ANDROID_ADAPTIVE_SAMPLES.md` (geometry ground truth, adaptive strategy, navigation and `configChanges` decisions), `NOWINANDROID.md`, `ROBORAZZI.md`
**Status:** Research record. Advisory. It is an engineering reference, **not a visual template** (§10).

---

## 0. Provenance

| Field | Value |
| --- | --- |
| Repository | <https://github.com/android/compose-samples> |
| Commit inspected | `0bbd72d69834ec86a9a72bd3513118755fb286c5` (`main`, 2026-09-18, "🤖 Update Dependencies (#1716)") |
| Licence | `Apache-2.0` (root `LICENSE`; GitHub SPDX `Apache-2.0`) |
| Local clone | `research/clones/compose-samples/` (shallow, git-ignored, never committed) |
| Samples read | **Jetchat**, **Reply**, **JetNews** (all three asked for), plus Jetcaster `wear` for its test toolchain |
| Inspection date | 2026-09-26 |

Permalink prefix: `CS` = `https://github.com/android/compose-samples/blob/0bbd72d69834ec86a9a72bd3513118755fb286c5`

### 0.1 Toolchain match with Skein (the most useful single fact in this repo)

| | compose-samples (Jetchat/Reply/JetNews `gradle/libs.versions.toml`) | Skein `009cbb6` |
| --- | --- | --- |
| compose-bom | **2026.09.00** | **2026.09.00** |
| androidx.window | **1.5.1** | **1.5.1** |
| Robolectric | **4.17** | **4.17** |
| AGP | 9.3.1 | 9.4.1 |
| compileSdk | 37 (Jetcaster wear) | 37 |
| JDK on CI | **17** (`.github/workflows/build-sample.yml` L55, `Jetchat.yaml` L59, `Reply.yaml` L60) | **17** (`.github/workflows/ci.yml` L56) |
| Robolectric SDK pin | `@Config(sdk = [34])` + `@GraphicsMode(NATIVE)` (`CS/Jetcaster/wear/src/test/java/com/example/jetcaster/NavigationTest.kt` [L30–31](https://github.com/android/compose-samples/blob/0bbd72d69834ec86a9a72bd3513118755fb286c5/Jetcaster/wear/src/test/java/com/example/jetcaster/NavigationTest.kt#L30-L31)) | `@Config(sdk = [34])` (`docs/TESTING.md` L50) |
| Kotlin plugin | AGP built-in Kotlin (Jetcaster wear applies no `kotlin-android` plugin) | AGP built-in Kotlin |
| Roborazzi | **1.74.0** Gradle plugin applied (`Jetcaster/wear/build.gradle` L18, deps L153–155) | none yet (spike `skein-xtov.9`) |
| Navigation | navigation-compose 2.10.1 (Reply); **navigation3 1.2.0-rc01** + lifecycle-viewmodel-navigation3 **2.11.0** (JetNews) | none (hand-rolled `NavState`) |

**Consequence:** a Google-maintained project already runs Roborazzi's Gradle plugin, Robolectric 4.17 native graphics at SDK 34, and JDK 17 on AGP 9.x with compileSdk 37 and Skein's exact Compose BOM, and its CI builds and unit-tests it (`Jetcaster.yaml` → `build-sample.yml` L89 `./gradlew testDebug`). The Jetcaster wear test on the classpath does not itself call `captureRoboImage`, so this shows **the build wiring and runtime classpath work**, not image fidelity. `ROBORAZZI.md` §4 records the full compatibility verdict.

---

## 1. Project assessment (§52 format)

### Project

<https://github.com/android/compose-samples> at `0bbd72d6` (2026-09-18): Jetchat (chat UI), Reply (adaptive email client), JetNews (adaptive reader on Navigation 3).

### License

`Apache-2.0`. Compatible with Skein (Apache-2.0). No code is proposed for vendoring (see "Potential reusable code").

### Maintenance

Very active. 23.5 k stars; `pushed_at` 2026-09-25; 13 open issues. A bot bumps dependencies (`update_deps.yml`, e.g. commit #1716 on 2026-09-18). Each sample is its own Gradle build with a per-sample CI workflow. Its dependency versions track the latest stable releases closely, so the repo is a useful **early warning** for Skein's own upgrades.

### Relevant Skein problem

1. **Chat composer and transcript mechanics** (§26, §24, Tests C/D). Today the draft is `remember`-only (`feature/chat/…/ChatBottomBar.kt` L88), a forced auto-scroll fights the restored position (`MessageList.kt` L58), and the input has no IME-aware inset strategy beyond the shell-wide `safeDrawing` padding (`SkeinApp.kt` L296–301).
2. **Adaptive navigation policy** (§20, §22, §23). Skein has a modal drawer at every width plus a 40 dp glyph rail. Reply and JetNews show two production-quality policies for choosing bar, rail or drawer by window.
3. **Navigation 3 with a custom scene** (§24). JetNews shows how to write a list-detail scene *without* Material's two-partition limits. That matters if Skein ever wants a bespoke `Chat | Inspector` scene.
4. **Drawer and sheet state after posture change** (Test G). JetNews' size-aware drawer state.

### Patterns worth adopting

**Jetchat (chat and input)**

| Pattern | Where | Skein use |
| --- | --- | --- |
| Draft survives recreation: `var textState by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }`; the input-selector state is also saveable | `CS/Jetchat/app/src/main/java/com/example/compose/jetchat/conversation/UserInput.kt` [L148–157](https://github.com/android/compose-samples/blob/0bbd72d69834ec86a9a72bd3513118755fb286c5/Jetchat/app/src/main/java/com/example/compose/jetchat/conversation/UserInput.kt#L148-L157) | The minimum fix for Skein's draft loss. The preferred fix is a `TextFieldState` in a retained chat holder (`ANDROID_ADAPTIVE_SAMPLES.md` §5.6 #5). Note the privacy trade-off: saveable text lands in the saved-state Bundle, which `system_server` holds. That is an owner decision. |
| Transcript as `LazyColumn(reverseLayout = true)` | `Conversation.kt` [L328–345](https://github.com/android/compose-samples/blob/0bbd72d69834ec86a9a72bd3513118755fb286c5/Jetchat/app/src/main/java/com/example/compose/jetchat/conversation/Conversation.kt#L328-L345) | "Newest" is index 0, so new tokens and new messages stay pinned to the bottom *without* `animateScrollToItem`. A restored scroll position (user reading history mid-stream) is not overridden. Replaces `MessageList.kt`'s `LaunchedEffect(itemCount)`. |
| **Jump-to-bottom FAB** shown when not at the bottom | `JumpToBottom.kt` [L46](https://github.com/android/compose-samples/blob/0bbd72d69834ec86a9a72bd3513118755fb286c5/Jetchat/app/src/main/java/com/example/compose/jetchat/conversation/JumpToBottom.kt#L46) | Pairs with reverse layout for long generations (§26). |
| **IME insets owned by the composer only.** The Scaffold's `contentWindowInsets` excludes `WindowInsets.ime` and `navigationBars`; the input applies `Modifier.navigationBarsPadding().imePadding()`; manifest `android:windowSoftInputMode="adjustResize"` | `Conversation.kt` [L193–196](https://github.com/android/compose-samples/blob/0bbd72d69834ec86a9a72bd3513118755fb286c5/Jetchat/app/src/main/java/com/example/compose/jetchat/conversation/Conversation.kt#L193-L196), [L243](https://github.com/android/compose-samples/blob/0bbd72d69834ec86a9a72bd3513118755fb286c5/Jetchat/app/src/main/java/com/example/compose/jetchat/conversation/Conversation.kt#L243); `Jetchat/app/src/main/AndroidManifest.xml` L32 | Skein pads the *whole shell* by `WindowInsets.safeDrawing`, which includes the IME (`SkeinApp.kt` L296–301). The command bar, tabs and panes all shrink when the keyboard opens. In a two-pane Fold layout that also squeezes the *list* pane. Move IME handling to the chat composer; the shell keeps `safeDrawing.exclude(ime)`. |
| `BackHandler(onBack = dismissKeyboard)` while the input selector or keyboard is up | `UserInput.kt` L153 | §26 back behaviour: the first back dismisses the keyboard or attachment picker, not the chat. |
| `KeyboardOptions(imeAction = ImeAction.Send)` | `UserInput.kt` L557–559 | Skein already has "Enter sends" (handoff `75dc17f`). Keep the IME action consistent with it. |
| **Test hook for keyboard state:** a custom `SemanticsPropertyKey` `keyboardShownProperty` on the text field | `UserInput.kt` L464, L505–507; used by `Jetchat/app/src/androidTest/.../UserInputTest.kt` | A cheap, deterministic assertion for §25 Test D ("keyboard active across fold") that doesn't depend on the real IME. |
| **Drag-and-drop text into the conversation** (`Modifier.dragAndDropTarget` accepting `MIMETYPE_TEXT_PLAIN`) | `Conversation.kt` L137–178, L204 | On the unfolded Fold in split-screen, dragging text from another app into the chat is a natural attach-context gesture (§3 "attach context"). P2. |

**Reply (adaptive policy)**

| Pattern | Where | Skein use |
| --- | --- | --- |
| **Navigation type by window, posture first:** tabletop → bar; compact width *or* compact height → bar; ≥ 1200 dp → permanent drawer; otherwise → rail with a menu button that opens a modal drawer. Modal-drawer gestures are enabled only when the rail is showing. | `CS/Reply/app/src/main/java/com/example/reply/ui/navigation/ReplyNavigationComponents.kt` [L77–166](https://github.com/android/compose-samples/blob/0bbd72d69834ec86a9a72bd3513118755fb286c5/Reply/app/src/main/java/com/example/reply/ui/navigation/ReplyNavigationComponents.kt#L77-L166) (tabletop L94, 1200 dp L99, gestures L113) | Skein's §5.2 table uses the same *shape*: compact width or compact height → one thing, Medium/Expanded → rail, ≥ 1200 → expanded rail or permanent drawer. Skein picks a **drawer** where Reply picks a bar, because the composer owns the bottom edge. Reply's `isCompact()` (L77–78) treats compact height as compact. That is the same idea as Skein's two-pane height gate, and it catches the closed Fold in landscape (994 × 443 dp). |
| Custom `NavigationSuiteScaffoldLayout(layoutType = …)` wrapped in a `ModalNavigationDrawer` | same file L122–166 | This keeps Material's navigation suite but lets Skein override `navigationSuiteType` per its own table. Use it instead of the default `NavigationSuiteScaffold(...)` if the drawer-on-Compact decision stands. |
| **Posture as a tie-breaker at Medium:** Medium width + book or separating posture → dual pane | `CS/Reply/app/src/main/java/com/example/reply/ui/ReplyApp.kt` [L64–88](https://github.com/android/compose-samples/blob/0bbd72d69834ec86a9a72bd3513118755fb286c5/Reply/app/src/main/java/com/example/reply/ui/ReplyApp.kt#L64-L88) | Relevant only if the inner display ever becomes Medium (a larger Display size). Book posture is a strong signal that the user wants two pages, and it maps onto `calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth`. |
| **Selection lives in a `ViewModel`, layout derives from the window, and a `LaunchedEffect(contentType)` reconciles "detail-only" state when the pane count changes** | `ReplyHomeViewModel.kt` (`openedEmail`, `isDetailOnlyOpen`); `ReplyListContent.kt` [L70](https://github.com/android/compose-samples/blob/0bbd72d69834ec86a9a72bd3513118755fb286c5/Reply/app/src/main/java/com/example/reply/ui/ReplyListContent.kt#L70) | This is exactly §24's principle: posture changes never mutate *what* is selected, only *how* it's shown. Nav3 scenes make the reconciliation effect unnecessary (`ANDROID_ADAPTIVE_SAMPLES.md` §4.2). |
| Type-safe Navigation Compose routes (`composable<Route.Inbox>`) | `ReplyApp.kt` L128–153 | Evidence for the Nav2 option. It is not recommended; see the navigation decision in `ANDROID_ADAPTIVE_SAMPLES.md` §4.2. |

**JetNews (Navigation 3 + adaptive)**

| Pattern | Where | Skein use |
| --- | --- | --- |
| **Custom Nav3 `ListDetailScene` + `SceneStrategy`** driven by entry metadata (`NavMetadataKey`), with a detail placeholder and `AnimatedContent` keyed by `entry.contentKey` | `CS/JetNews/app/src/main/java/com/example/jetnews/ui/navigation/ListDetailScene.kt` [L39–132](https://github.com/android/compose-samples/blob/0bbd72d69834ec86a9a72bd3513118755fb286c5/JetNews/app/src/main/java/com/example/jetnews/ui/navigation/ListDetailScene.kt#L39-L132) | This is the escape hatch if Material's `ListDetailSceneStrategy` cannot express a Skein layout. Example: a PROTOTYPE "dense workbench" `Chat \| Inspector` scene at ≥ 1000 dp that keeps the list in the rail's flyout. Otherwise prefer the first-party `adaptive-navigation3` strategies. |
| **Size-aware drawer state:** when the screen is expanded, return an un-remembered `DrawerState(DrawerValue.Closed)` so the modal drawer can never be open, and disable its gestures | `CS/JetNews/app/src/main/java/com/example/jetnews/ui/JetnewsApp.kt` [L55–116](https://github.com/android/compose-samples/blob/0bbd72d69834ec86a9a72bd3513118755fb286c5/JetNews/app/src/main/java/com/example/jetnews/ui/JetnewsApp.kt#L55-L116) (`isExpandedScreen` L55, `rememberSizeAwareDrawerState` L102) | **Test G.** Unfold with the drawer open → the drawer disappears and the rail takes over. Refold → the drawer is closed, not reopened. Skein's `NavState.drawerOpen` is saveable and would otherwise reopen after recreation. |
| Nav3 back stacks per top-level key (`rememberNavigationState` with `rememberNavBackStack` + `rememberSaveableStateHolderNavEntryDecorator`) | `navigation/NavigationState.kt` L48, L105–108 | Same shape as NiA (`NOWINANDROID.md`). It shows Nav3 1.2 deep-link support (`deeplink/`) too, a future "open chat from notification" path. |
| **Predictive back** wired with `NavigationBackHandler` + `rememberNavigationEventState` and a custom `predictivePopTransitionSpec` | `JetnewsNavDisplay.kt` L100–130 | Predictive back on large screens is a Material expectation. Skein has manual `BackHandler`s only. |
| `HomeUiState` sealed interface (`HasPosts`/`NoPosts`) carrying `isLoading`, `errorMessages`, `searchInput` | `ui/home/HomeViewModel.kt` [L41](https://github.com/android/compose-samples/blob/0bbd72d69834ec86a9a72bd3513118755fb286c5/JetNews/app/src/main/java/com/example/jetnews/ui/home/HomeViewModel.kt#L41) | The shape for `ChatUiState` (Empty / Conversation / Generating / Failed), which drives both screens and screenshot fixtures (§37 Layer A). |
| Key-event modifier for hardware keyboards (`onPreviewKeyEvent`) | `ui/modifiers/KeyEvents.kt` L24–32 | §41 keyboard shortcuts on the unfolded Fold with a keyboard. The owner's configuration shows `qwerty` attached right now. |

### Patterns not worth adopting

- **Jetchat's app structure:** `AppCompatActivity` + `ComposeView` + `AndroidViewBinding(ContentMainBinding::inflate)` + **Fragment-based Navigation XML** (`NavActivity.kt`). It is a legacy interop demo. Skein is single-activity, Compose-only, and headed for Nav3.
- **Jetchat's `MainViewModel.drawerShouldBeOpened` event-as-state with a manual reset.** A derived, size-aware drawer state (JetNews) is simpler and correct across posture changes.
- **Jetchat's visual design, backdrop blur (`blur/`), `SurfaceViewBlur`, video player.** Not Skein's identity (§10: "engineering reference, not a visual template"). Blur is also costly (§46).
- **Reply's `accompanist-adaptive` `TwoPane` + `calculateDisplayFeatures`** (`ReplyListContent.kt` L81–99, `MainActivity.kt` L48–49). material3-adaptive's scaffolds and `HingePolicy` supersede it.
- **Reply's deprecated `WindowWidthSizeClass`** (material3-window-size-class) and `currentWindowAdaptiveInfo()` without V2. Skein already uses `currentWindowAdaptiveInfoV2()`. Keep it.
- **Hilt** (all three samples use `hilt-navigation-compose`). Skein's composition root is manual (`SkeinApplication`/`VaultSession`), and adding a DI framework is out of this overhaul's scope (§1 "do not broadly refactor").
- **Instrumented-only UI tests** (Jetchat/JetNews `androidTest`). Useful on the emulator lane, but Skein's fast loop should be JVM (Roborazzi, Compose UI tests under Robolectric).

### Potential reusable code

Nothing needs vendoring. The ideas that transfer are each under 30 lines to write fresh: `rememberSizeAwareDrawerState` (15 lines), the `keyboardShownProperty` semantics key (5 lines), reverse-layout transcript plus jump-to-bottom, and the Reply navigation-type `when`. If a snippet *is* copied verbatim (the size-aware drawer is the likeliest), keep the upstream `Copyright … The Android Open Source Project` / Apache-2.0 header in the file and add a line to `NOTICE`.

### Android/Fold relevance

- **Reply** is the only sample here that reasons about fold posture (`isBookPosture`/`isSeparating`, `ui/utils/WindowStateUtils.kt` L36–46). Its policy (tabletop → bar; compact height → bar) directly informs Skein's closed-landscape and tabletop rows.
- **JetNews** proves Nav3 + adaptive + predictive back on Skein's exact BOM.
- **Jetchat** shows the IME and scroll mechanics that decide whether Test C (generation across fold) and Test D (keyboard across fold) *feel* continuous.

### Mac UX-Lab relevance

High for the toolchain, medium for the patterns.
- The Jetcaster wear module is a working template for "Roborazzi + Robolectric 4.17 (SDK 34) + JDK 17 + AGP 9 built-in Kotlin + compileSdk 37". This is the configuration the spike `skein-xtov.9` must reproduce.
- Jetchat's `ConversationPreview`/`UserInputPreview` show fixture-driven previews of a chat surface (§37 Layer A: "empty chat, populated chat, active generation, keyboard-visible").

### Recommended action

**STUDY ONLY.** Adopt the named patterns (saveable or retained draft, reverse-layout transcript + jump-to-bottom, composer-owned IME insets, keyboard-state semantics key for tests, Reply-shaped navigation policy with a compact-height rule, size-aware drawer state, sealed `UiState`). Rewrite them in Skein's code; copy no sample code, no visuals and no app structure.

### Expected benefit

- The chat composer keeps its draft and scroll position through fold, rotation and process death (if the owner accepts saveable plaintext), and stays usable with the keyboard up on both displays.
- The drawer and rail never both appear, and the drawer never "re-opens" after an unfold.
- A reusable pattern for the `ChatUiState` fixtures that feed previews and screenshots.

### Expected cost

Low. These are local changes in `:feature:chat` (composer, message list) and `:feature:shell` (drawer state, inset ownership). There are no new dependencies unless Nav3 is adopted, and that is costed in `ANDROID_ADAPTIVE_SAMPLES.md`.

---

## 2. Answers to the prompt's specific questions (§10)

| §10 topic | Finding | Skein action |
| --- | --- | --- |
| Compose state handling | Jetchat: `rememberSaveable` for the draft and input mode. Reply: selection in a `ViewModel` exposed as `StateFlow`, collected with `collectAsStateWithLifecycle()` (`MainActivity.kt` L50). JetNews: `UiState` sealed interface built in `viewModelScope`. | Chat moves its turn state to a retained holder (session-scoped or `ViewModel`); the draft becomes `TextFieldState` there. See `ANDROID_ADAPTIVE_SAMPLES.md` §5.6. |
| Navigation | Reply: type-safe Navigation Compose 2.10. JetNews: Navigation 3 1.2.0-rc01 with a custom list-detail scene, deep links and predictive back. | Navigation 3 (the full comparison lives in `ANDROID_ADAPTIVE_SAMPLES.md` §4.2). |
| Chat and input interfaces | Jetchat's composer: saveable text, IME-owned insets, `ImeAction.Send`, back-dismisses-keyboard, jump-to-bottom, drag-and-drop. | Adopt; see the table above. |
| Adaptive layouts | Reply: rail, bar or drawer by width, height and posture; dual pane at Expanded or at Medium + book posture. JetNews: rail + list-detail scene at Expanded. | Skein's §5.2 table in `ANDROID_ADAPTIVE_SAMPLES.md`. |
| Foldable patterns | Reply only: book posture and separating hinge → dual pane with `TwoPane(displayFeatures)`. | Use material3-adaptive's `HingePolicy` instead. |
| Responsive components | Reply's `ReplyNavigationContentPosition` (TOP vs CENTER by height, L103–107) | A small but real detail: centre rail items only when height ≥ Medium. `NavigationSuiteScaffold`'s `navigationItemVerticalArrangement` does the same. |
| Testing | Jetchat and JetNews: instrumented Compose tests. Jetcaster wear: Robolectric `@Config(sdk = [34])` + `GraphicsMode.NATIVE` + Roborazzi plugin. | JVM-first; see `ROBORAZZI.md`. |
| Material 3 patterns | `NavigationSuiteScaffoldLayout`, `ModalNavigationDrawer`, `PermanentNavigationDrawer`, `NavigationRail` with a FAB header, `TopAppBar` scroll behaviours (`pinnedScrollBehavior` in Jetchat). | Use Material 3 1.4.0 components via the BOM. The Skein design system (§36) owns the look. |
| Animations | JetNews: scene `fadeIn togetherWith ExitTransition.KeepUntilTransitionsFinished` and a predictive-pop scale-out. Jetchat: `AnimatedVisibility` fades. | Keep fold transitions to cross-fades (§46 battery and usability); predictive back for detail → list. |
| Screen composition | Jetchat `ConversationContent(uiState, …)` is stateless over `ConversationUiState`, with previews. | Stateless screen + stateful route, as in NiA (`NOWINANDROID.md`). |

What specific problem in Skein can this project help us solve? Making the chat surface survive the fold as a user would expect: the draft, the scroll position mid-generation and the keyboard state all persist, the composer (not the whole shell) owns the IME, the drawer never reappears after an unfold, and the navigation policy treats the closed Fold in landscape (994 × 443 dp) as a phone. It also demonstrates that Skein's exact Compose BOM, Robolectric 4.17 (SDK 34), JDK 17 and AGP 9 toolchain already hosts the Roborazzi plugin in a maintained Google project.
