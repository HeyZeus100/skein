# Skein — UX Test Plan

**Bead:** `skein-xtov.19` · **Epic:** `skein-xtov` (UX overhaul) · **Deliverable 12 of 13** (prompt §49)
**Authority:** `docs/research/SKEIN_UI_UX_OVERHAUL_PROMPT.md` §10–12, §22–25, §37, §39–41, §43, §45–47, §51
**Companion:** `docs/ux/MAC_UX_LAB_PLAN.md` (the MacBook loop that runs most of these tests)
**Inputs:** `docs/ux/research/ROBORAZZI_SPIKE.md` (what is wired today), `ROBORAZZI.md`, `NOWINANDROID.md`, `COMPOSE_SAMPLES.md`, `ANDROID_ADAPTIVE_SAMPLES.md`, `JAN.md` §6.6, `POCKETPAL.md` (A1, Mac UX-lab relevance), `docs/ux/ANDROID_SKILLS_ASSESSMENT.md` §6.3/§6.7/§10.3, `docs/ux/INFORMATION_ARCHITECTURE.md` (incl. §8a), `docs/ux/UX_AUDIT.md` ("Reconciliations"), `docs/ux/CHAT_UX_SPEC.md` §22/§25/§26, `docs/ux/KNOWLEDGE_UX_SPEC.md` §11.7/§15/§18/§22, `docs/ux/audit/*`, `docs/TESTING.md`, `docs/DEVICE_RUNNER.md`, `.github/workflows/*`
**Status:** Plan. No test, build or workflow change is made by this document. Items marked **[verify]** are inferred from documentation or a single source and must be confirmed by the bead that implements them.

**What this document owns:** the test layers, the device matrix, the screenshot tiers and naming, the baseline and CI policy, fixtures and the fake engine, fold tests A–G, the P0 regression ledger, and accessibility, keyboard, flow and performance test methods. **What the specs own:** which states exist (`CHAT_UX_SPEC.md` §26, `KNOWLEDGE_UX_SPEC.md` §22, later specs), acceptance criteria (`AC-…`) and budgets. Where a spec says "light and dark on every device", §3.3's tiers decide what is actually captured.

---

## 0. Decisions in one screen

| Question | Decision |
|---|---|
| What proves a UI change is correct? | A test at the lowest layer that can prove it, **plus** a screenshot, **plus** a before/after diff (§39). "It compiles" proves nothing. |
| Layers | L0 pure unit → L1 Compose UI (Robolectric) → L2 screenshots (Roborazzi) → L3 state restoration → L4 emulator lane → L5 owner-device acceptance (§2). L0–L3 run on the Mac and in CI in minutes, with no model and no vault. |
| Device matrix | 12 named windows, measured not assumed, keyed by their portrait dp width (§3.1). The spike's `fold-outer` (411 × 923 dp @ 420 dpi, "provisional") is wrong: the outer display is **443 × 994 dp at stock 390 dpi** and **524 × 1175 dp at the owner's forced 330 dpi** (measured while folded; both panels share the density; `UX_AUDIT.md` Reconciliations). **The first test bead (UT-0) re-records the outer display at 443 and 524.** |
| Screenshot matrix | Tiered, not a cross product: ~650 images at half scale, ~30–35 MiB (§3.3). |
| Screenshot names | `ux-baselines/<module-dir>/<device>/<state-id>__<theme>__fs<NNN>[__<mod>].png`. The **state id is the spec's name verbatim** (`chat-activity-starting`, `knowledge-list`, `graph-dense-80`), so spec tables slot in unchanged (§3.4–3.5). |
| Baselines | Committed per module under `ux-baselines/<module-dir>/` via Roborazzi's `outputDir`; `ux-baselines/before/` and `device-before/` are frozen history (§4.1). |
| Who records | The Mac only, never CI. An implementer records only the states its bead names ("record scope"); every other diff is a stop-and-report (§4.2). |
| CI | Screenshot tests already run as smoke tests inside `ci.yml`'s `check`. Add a separate `ux-screenshots` job: non-blocking verify with artifacts first; blocking once the Wave 3 shell is stable and after 10 consecutive green `main` runs (§4.3, §13). |
| Shared helper | Replace the seven copies of `UxScreenshots.kt` with one test-only Android library, **`:testing-ui`** (§5). |
| Fixtures and fake model | Split the JUnit-free fakes out of `:testing` into **`:testing-fakes`** so previews can use them without breaking the foss licence audit; add a fixture corpus and a **`ScenarioInferenceEngine`** (fast answer, slow prefill, reasoning, stop, failure, model loading) that implements the locked `InferenceEngine` contract and passes its contract test (§6). |
| Fold tests A–G | Each runs at L1/L3 in one composition (`DeviceConfigurationOverride.WindowSize` flip) **and** through recreation; screenshots of both ends at L2; real postures on the emulator at L4; the real fold by the owner with a watcher script at L5 (§7). |
| Partial answers | Settled from code (`UX_AUDIT.md`): **Stop** persists the partial answer with `INTERRUPTED_MARKER`; **leaving composition** (fold/recreation, tab or destination switch, citation tap, lock) cancels the collector and **loses** it. A dedicated regression test drives the second path with the fake engine (§8, `PartialAnswerSurvivesCompositionExitTest`). |
| P0s | Every P0 in the three audits and the device pass has a named regression test, a layer and a wave (§8). |
| Accessibility | Zero-dependency semantics sweeps first (touch target, accessible name, text overflow, dead controls); ATF via Roborazzi later; token contrast as a unit test (§9). |

---

## 1. Principles

1. **Test the product, not the model.** No UI test, preview or screenshot ever loads a GGUF, binds the inference service or opens SQLCipher. The only engines are fakes implementing `core/model`'s `InferenceEngine` (§6). Real-model behaviour stays on its own lane (`tools/m0-benchmark`, `LlamaNativeTest`, the hardware runner).
2. **Stateless screens are the unit of testing.** Every destination gets the Now-in-Android split: a stateful route (ViewModel or session holder) and a stateless `XxxScreen(uiState, onEvent)` (`NOWINANDROID.md` §1). Previews, screenshots and most Compose UI tests render the stateless screen from fixtures. A wave that redesigns a screen also delivers its stateless split; until then, tests drive the real screen through fakes, as the spike does.
3. **Measured geometry only.** Layout is a function of the window size class and height, never of a device model or density (`ANDROID_ADAPTIVE_SAMPLES.md` §1.3). Tests name windows by measured dp.
4. **Tests arrive with their wave, red first.** A wave's acceptance tests are written at the start of the wave, fail against today's code, and are merged green with the fix. No `@Ignore`d "future" tests in `main`: they rot.
5. **Deterministic or deleted.** No `Thread.sleep`, no wall-clock waits, no mixed virtual clocks. A screenshot diff that appears without a code change is a determinism bug, not a flake to retry (§13.3).
6. **Only the hardware runner touches the Fold** (`docs/DEVICE_RUNNER.md`; skills assessment non-negotiable #9). UX implementer agents run L0–L3 on the Mac and read the CI emulator lane; they never run `adb` against the Fold or a local emulator. The owner may use both directly.
7. **Visual changes need visual evidence** (§39): screenshot, before/after, test, explanation, and a diff when goldens change (§4.6).

---

## 2. Test layers

| Layer | Runs where | Proves | Cannot prove | Tooling | Wall time (Mac, warm) |
|---|---|---|---|---|---|
| **L0 Pure unit** | JVM, every module's `test`/`testDebugUnitTest` | Decision functions: layout directive, navigation type, back-stack rules, activity reducer, titles, model names, streaming render cost | Anything drawn | JUnit 4 + Truth, `Parameterized` tables | seconds |
| **L1 Compose UI** | Robolectric (SDK 34, Java 17) | Semantics, interactions, callbacks, navigation, focus, key input, dead controls, accessibility sweeps | Real IME, real insets, real rendering speed | `ui-test-junit4` 1.12.1, `DeviceConfigurationOverride`, fakes | ~1–3 s per class |
| **L2 Screenshots** | Robolectric native graphics + Roborazzi 1.75.0 | What each state looks like at every window, theme and font scale; visual regressions | Device emoji fonts, real system bars and IME (only simulated), animation | `captureRoboImage`, the `:testing-ui` device matrix | ~12 s for today's 172 cases |
| **L3 State restoration** | Robolectric | That fold, rotation, recreation, process death and vault lock keep the state §24 lists | Real `system_server` behaviour, the real keyguard | `DeviceConfigurationOverride.WindowSize` flips in one composition; `StateRestorationTester`; `ActivityScenario.recreate()` after `RuntimeEnvironment.setQualifiers` | seconds |
| **L4 Emulator** | Pixel 9 Pro Fold AVD (CI nightly; the owner's Mac) | Real posture changes, real IME, real insets, instrumentation | Real hinge hardware, GrapheneOS, the owner's density, real performance | Espresso Device API or `cmd device_state` via `UiAutomation`; `connected…AndroidTest` | minutes |
| **L5 Owner-device acceptance** | The owner's Pixel 9 Pro Fold, GrapheneOS | The real fold gesture, keyguard interplay, vault lock, real-model generation across a fold, TalkBack, the physical keyboard, frame budgets | nothing below it; it is the truth | Physical fold by the owner + `tools/ux/fold-watch.sh` (spec §2.6) + `uiautomator dump` + `screencap -d` | one session per wave |

### 2.1 L0 — pure unit tests

| Function (owner spec) | Test | Wave |
|---|---|---|
| `skeinPaneDirective(info, windowSize)` and the navigation-type decision (`ANDROID_ADAPTIVE_SAMPLES.md` §5.2–5.3; `ADAPTIVE_LAYOUT_SPEC.md`) | `SkeinLayoutDecisionTest`: a table over every device in §3.1 plus the breakpoint edges (599/600, 839/840, 1199/1200 dp width; 599/600 dp height for the two-pane gate), tabletop and book postures, split-screen halves (≈ 437 × 852, ≈ 517 × 1007, the 320 dp minimum) and the 360 dp minimum chat-pane rule | 3 |
| Navigator / back-stack rules: open by kind, de-duplication ("no duplicate chat", Test B), Back order (sheet → pop → Chat → exit, IA §3.8), re-selecting a destination clears its sub-stack | `SkeinNavigatorTest` (NiA's `NavigatorTest` shape, plain lists) | 3 |
| Size-aware drawer state (JetNews `rememberSizeAwareDrawerState` logic, Test G) | `DrawerPolicyTest` | 3 |
| Activity reducer `reduce(TurnActivity, TurnActivityEvent, nowMs)` (`CHAT_UX_SPEC.md` §9.12) | `TurnActivityReducerTest`: every event from every state; a no-op returns the same instance | 4 |
| Collapsed summary text and the calm rule (`CHAT_UX_SPEC.md` §9.5, AC-11) | `ActivitySummaryTextTest` | 4 |
| Provisional chat titles (`CHAT_UX_SPEC.md` §13.1, AC-23/24) | `ChatTitleTest` with the AC-23 corpus | 5 |
| `ModelNameResolver` (`CHAT_UX_SPEC.md` §19, AC-02) | `ModelNameResolverTest` with the `F-MODEL-LONG` fixtures (§6.2) | 4 / 9 |
| History groups *Today · Yesterday · Previous 7 days · Previous 30 days · by month* (IA §3.4 as revised in §8a) with an injected clock | `HistoryGroupingTest` | 5 |
| Streaming render cost (structural sharing, parse work per token) | `StreamingRenderCostTest` (§12) | 4 |
| `LivePreviewTransformer.transformedToOriginal(0)` maps past the hidden frontmatter (K-P0-2; `KNOWLEDGE_UX_SPEC.md` §6.4) | `LivePreviewTransformerTest` addition | ahead of 6 (KN-6.1) |

Shape of the central table test (values are proposals; `ADAPTIVE_LAYOUT_SPEC.md` owns them):

```kotlin
@RunWith(Parameterized::class)
class SkeinLayoutDecisionTest(private val row: Row) {
    data class Row(val name: String, val w: Int, val h: Int, val posture: TestPosture, val panes: Int, val nav: NavType) {
        override fun toString() = name
    }

    @Test fun decides() {
        val decision = skeinLayout(windowSize = DpSize(row.w.dp, row.h.dp), posture = row.posture.toPosture())
        assertThat(decision.maxPanes).isEqualTo(row.panes)
        assertThat(decision.navigation).isEqualTo(row.nav)
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun rows() = listOf(
            Row("phone",                360,  800, FLAT,     1, DRAWER),
            Row("split-320",            320, 1007, FLAT,     1, DRAWER),
            Row("fold-outer-443",       443,  994, FLAT,     1, DRAWER),
            Row("fold-outer-524",       524, 1175, FLAT,     1, DRAWER),
            Row("fold-outer-443-land",  994,  443, FLAT,     1, DRAWER), // compact height
            Row("fold-outer-524-land", 1175,  524, FLAT,     1, DRAWER), // Medium height, below the 600 dp gate
            Row("fold-inner-852",       852,  883, FLAT,     2, RAIL),
            Row("fold-inner-1007-land",1043, 1007, FLAT,     2, RAIL),
            Row("medium-791",           791,  820, FLAT,     1, RAIL),
            Row("large-1280",          1280,  800, FLAT,     3, RAIL_EXPANDED),
            Row("gate-599h",           1043,  599, FLAT,     1, DRAWER),
            Row("gate-600h",           1043,  600, FLAT,     2, RAIL),
            Row("book-1007",           1007, 1043, BOOK,     2, RAIL),
            Row("tabletop-1007-land",  1043, 1007, TABLETOP, 1, RAIL),
        )
    }
}
```

### 2.2 L1 — Compose UI tests (Robolectric)

- **Host.** Roborazzi's `RoborazziActivity` for every module (`registerRoborazziActivityToRobolectricIfNeeded()`), exposed by `:testing-ui` as `skeinComposeRule()`. That removes the per-module debug-manifest `ComponentActivity` entries `docs/TESTING.md` warns about. The unlock/setup screens keep their `FragmentActivity` host (the `AuthScreenshotTest` pattern).
- **Always** `@Config(sdk = [34])`, or `src/test/resources/robolectric.properties` with `sdk=34` per module; SDK 35+ needs Java 21.
- **Matchers.** Semantic matchers first (text, role, content description, state); a `testTag` only when more than three matchers would be needed (skills excerpt §10.3). Tags the device journeys need (§2.6) are constants in the design system.
- **Time.** Use `ScenarioInferenceEngine` with `Pacing.Gated` (§6.3) so the test decides exactly how far a generation has got. `composeRule.mainClock.autoAdvance = false` only for animation frames. Never mix `mainClock` with delays running on `Dispatchers.Main` in a ViewModel: under Robolectric they are different clocks.
- **Dead-control detector** (`assertNoDeadControls`, in `:testing-ui`). For a stateless screen rendered with recording callbacks, click every node with `SemanticsActions.OnClick`; each click must fire a callback or change the semantics tree (local expand/collapse). This is the generic regression test for "every visible control works" (§51) and for P0-03/P0-04/K-P0-6/CMS-P0-04/CMS-P0-05. It works in one composition because a stateless screen does not navigate by itself.
- **Assertion sweeps** (accessibility, §9) run on every L1 screen fixture: `assertTouchTargets()`, `assertEveryActionIsNamed()`, `assertNoTextOverflow()`.

### 2.3 L2 — screenshot tests (Roborazzi)

What is wired today (`ROBORAZZI_SPIKE.md`): the Roborazzi 1.75.0 plugin in seven feature modules, 153 committed "before" images, parameterised `*ScreenshotTest` classes, and `UxDeviceRule` setting qualifiers before the activity launches.

The modes (confirmed in the spike; plugin 1.75.0 also registers `verifyAndRecordRoborazzi<Variant>` and `clearRoborazzi<Variant>`):

| Mode | Command | Writes | Fails on a diff |
|---|---|---|---|
| none (smoke) | `./gradlew check` / `testDebugUnitTest` | nothing; every state still composes and lays out | no |
| record | `-Proborazzi.test.record=true` or `recordRoborazziDebug` | goldens (overwrites) | no |
| compare | `-Proborazzi.test.compare=true` or `compareRoborazziDebug` | `*_compare.png` (reference · diff · actual), `*_actual.png`, HTML report | no |
| verify | `-Proborazzi.test.verify=true` or `verifyRoborazziDebug` | the same, for changed images only | **yes** |

Rules for a screenshot test:

- One class per surface, named `*ScreenshotTest` (so it can be filtered), parameterised by `UxSpec(device, theme, fontScale, mod)` from `:testing-ui`.
- Render the **stateless** screen from a fixture state (§6) whenever it exists. Render the real screen through fakes only when the state cannot be built statically (today: most screens; after each wave: few).
- Wrap content in the product theme exactly as the app does (`SkeinTheme` plus the edge-to-edge surface).
- Deterministic inputs only: a fixed clock (`2026-09-26T10:00:00Z`, `TimeZone.setDefault(UTC)`), `Locale.US`, fixed document ids, `Pacing.Gated` for streaming, static elapsed times in activity fixtures, graph layouts run to rest with a fixed seed (`KNOWLEDGE_UX_SPEC.md` §22).
- Capture with `captureUx(spec, "<state-id>")`; `:testing-ui` appends `__<theme>__fs<NNN>[__<mod>]` (§3.5).
- Run the accessibility sweeps (§9) on the same composition before capturing, and fail after the image is written (NiA's order), so a failing state's image still exists for review.

### 2.4 L3 — state-restoration tests

Three mechanisms, each proving something different. A fold-affecting state needs **all three** (skills excerpt: "fold-affecting state also gets a size-change recreation test").

| Mechanism | What it simulates | What it proves | API |
|---|---|---|---|
| **One composition, window flip** | A fold with `android:configChanges` declared (the production path after Wave 3): no recreation, the window changes size | Re-layout without reset: the same back stack, draft, selection and generation; panes becoming sheets | `DeviceConfigurationOverride(DeviceConfigurationOverride.WindowSize(size)) { … }` with `size` held in a `mutableStateOf` and flipped between §3.1 sizes. It overrides `LocalWindowInfo`, which `currentWindowAdaptiveInfoV2()` reads (`ANDROID_ADAPTIVE_SAMPLES.md` §5.7). Present in ui-test 1.12.1 (checked in the cached AAR). |
| **Saved-state round trip** | Process death, or any recreation, for `rememberSaveable` state | That saveables restore (drawer flag, selection, scroll, focus flag, expansion latch) | `androidx.compose.ui.test.junit4.StateRestorationTester(composeRule)` → `setContent { … }` → `emulateSavedInstanceStateRestore()` (1.12.1 API, checked) |
| **Activity recreation with a new configuration** | A fold, rotation, density or font-scale change that is **not** handled (Display size, locale, night mode, font scale, task restore) | Retained holders survive (ViewModel, the session turn controller — chat bead C1), saveables restore, the new layout is chosen | `RuntimeEnvironment.setQualifiers("w524dp-h1175dp-port-330dpi")`, then `composeRule.activityRule.scenario.recreate()` |

Two more at the app level (`:app` Robolectric tests, Wave 3):

- **Handled configuration change** on `MainActivity`: `Robolectric.buildActivity(MainActivity::class.java).setup()`, then `controller.configurationChange(newConfig)`; assert the same Activity instance, and extend `ManifestPolicyTest` to require `screenSize|smallestScreenSize|screenLayout|orientation`. Both panels share one density, so `density` need not join `configChanges` (`UX_AUDIT.md` Reconciliations).
- **Vault lock round trip:** lock → unlock with the existing `ScriptedVaultKeyProvider` → the id-only back stack is restored and re-resolved (IA §6.3, D7). Nothing but ids may be in the saved Bundle: walk the saved `Bundle` and fail on any string equal to a fixture title, draft or message body (drafts live in an encrypted draft row and never in the Bundle, `CHAT_UX_SPEC.md` §7.7).

### 2.5 L4 — emulator lane

- **AVD:** the `pixel_9_pro_fold` hardware profile, API 35 `google_apis` x86_64 on CI (arm64-v8a on the Mac), **no PIN**: a separate job from today's keystore job, which needs a PIN, because with a PIN a posture change raises the keyguard. **[verify]** `avdmanager list device | grep -i fold` on the runner image and `reactivecircus/android-emulator-runner`'s `profile:` input.
- **What runs:** a small instrumented suite (`@FoldPostureTest`) that hosts the **new shell with fakes** in a test-only activity declared in the `androidTest` manifest with the **same `configChanges` as `MainActivity`**. It never goes through `MainActivity`: the vault gate needs biometric enrolment, which is not scriptable on the CI emulator (`emulator.yml`).
- **Posture control:** the Espresso Device API (`androidx.test.espresso.device`: `onDevice().setClosedMode()`, `setFlatMode()`, `setTabletopMode()`, `setBookMode()`, `setScreenOrientation(…)`), which needs `android.experimental.androidTest.enableEmulatorControl=true` and emulator gRPC control. **[verify]** that it works with an emulator started by `android-emulator-runner` rather than a Gradle Managed Device. The fallback needs no new dependency: `InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cmd device_state state 0")` (and `state reset`), safe on a PIN-less emulator.
- **What it adds over L3:** real `WindowInfoTracker` postures (tabletop, book, hinge bounds), the real soft keyboard (Test D), real system-bar insets, real recreation timing.
- **Not for performance numbers**: emulator frame timing is not representative.

### 2.6 L5 — owner-device acceptance and the watcher script (spec)

Device facts that shape this (`DEVICE_BEFORE_PASS.md`): screenshots work only while the owner has FLAG_SECURE turned off in Skein › Settings › Security (it may be on again); `screencap` needs `-d <display id>`; `cmd device_state state 0` behaves like the power button and brings up the keyguard on the outer display, so **unattended fold tests are impossible. The owner folds the phone by hand while a watcher records what happened.** One observed fold through the keyguard locked the vault and reset the shell (DBP-10).

**`tools/ux/fold-watch.sh` — specification** (written by the hardware-runner bead UT-15; bash, `set -euo pipefail`, nothing beyond `adb` and the Mac's `python3`):

*Invocation:* `tools/ux/fold-watch.sh --journey <A|B|C|D|E|F|G|flow-NN> [--serial <id>] [--settle-ms 1500] [--poll-ms 250] [--package app.skein] [--out .agent-logs/fold-watch]`

*Preflight (abort with a reason on failure):*
1. Exactly one device, or `ANDROID_SERIAL` set; `adb get-state` is `device`.
2. `app.skein` installed; record `dumpsys package app.skein | grep versionName` and `git rev-parse HEAD`.
3. Display map: logical ids for `wm` (`adb shell dumpsys display | grep -E 'mDisplayId|"(Inner|Outer) Display"'`) and **physical** ids for `screencap -d` (`adb shell dumpsys SurfaceFlinger --display-id`, lines like `Display <id> (HWC display N): … displayName="Inner Display"`).
4. Environment snapshot to `env.txt`: `getprop ro.build.fingerprint`; `wm size` and `wm density` for each logical display; `settings get system font_scale`; `settings get global animator_duration_scale`; `cmd uimode night`; `settings get secure enabled_accessibility_services`; physical keyboard present (`dumpsys input | grep -iE 'keyboard|qwerty'`); `cmd device_state print-state`. If the previous run's `env.txt` differs, print the diff first (the frozen-environment rule, `MAC_UX_LAB_PLAN.md` §7.4).
5. FLAG_SECURE probe: with Skein in front, `adb exec-out screencap -p -d <physical id>`. A PNG under ~20 KB, or a python check that finds every pixel black, means FLAG_SECURE is on: continue in **dump-only mode** (uiautomator works under FLAG_SECURE) and say so in the report.
6. `adb logcat -b events -c` and `adb logcat -b crash -c`, so later reads contain only this run.

*Setup (journey-specific, automated where possible):* journeys are bash functions in `tools/ux/journeys/<id>.sh` (`setup`, `prompt_human`, `assert_event`), not a new file format. `setup` taps nodes found by `resource-id` in a `uiautomator dump` (the centre of `bounds`) and types with `adb shell input text` (spaces as `%s`). Example for Test A: open a chat on the inner display, open the context inspector, attach a note, type the draft `draft-that-should-survive-a-fold`.

*Loop:*
- Every `poll-ms`: `adb shell cmd device_state print-state`; parse the state identifier (`CLOSED(0)`, `HALF_OPENED(1)`, `OPENED(2)`, … per the owner's `print-states`). On a change, wait `settle-ms`, then write event `k`:
  - `k-state.txt`: old → new, UTC timestamp;
  - `k-<inner|outer>.png` for each physical display (`adb exec-out screencap -p -d <id>`; an off display is black: keep it, label it);
  - `k-ui.xml` (`adb shell uiautomator dump /sdcard/fw.xml`, pull, delete; **[verify]** whether `uiautomator dump --windows` is needed to see the active display on this build);
  - `k-activity.txt`: `dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity'` and `pidof app.skein`;
  - `k-config.txt`: `dumpsys window displays | grep -E 'cur=|app='` (the dp configuration Skein now has);
  - `k-events.txt`: `logcat -d -b events` filtered to `app.skein` lifecycle tags (for example `wm_on_create_called`, `wm_on_destroy_called`, `wm_relaunch_*`; **[verify]** the tag names on Android 17), which shows whether the Activity was recreated;
  - `k-crash.txt`: `logcat -d -b crash`.
- **Keyguard:** if `dumpsys window` reports the keyguard showing (field names vary by release; **[verify]**), mark the event `KEYGUARD`, print "Unlock the phone now", wait until it clears, then capture `k+1` as the post-unlock state. The script never types a PIN and never simulates biometrics.
- **Vault gate:** the dump contains the unlock screen's text or tag → `VAULT_LOCKED`.

*Assertions* (per journey, from the dump and the event files). For Test A after `OPENED → CLOSED`: node `resource-id=composer` has text equal to the draft; `chat-title` equals the fixture title; no node with the landing text; the context inspector is a sheet or dismissed per spec; pid unchanged; zero relaunch events once `configChanges` has landed (before that: reported, not failed); no crash; not `VAULT_LOCKED`.

*Report:* `report.md` in the `DEVICE_RUNNER.md` results-template shape plus a per-event table and PASS/FAIL per assertion; `summary.json` for tooling.

*App prerequisite (debug builds only, Wave 3, bead UT-14):* `Modifier.semantics { testTagsAsResourceId = true }` on the root content in debug builds, so uiautomator exposes Compose test tags as `resource-id`. No module sets it today (grep), so device assertions can only match visible text.

*Rules:* the hardware runner (or the owner) runs it, one run at a time; outputs go to `.agent-logs/fold-watch/<UTC>-<journey>/` (git-ignored, never `/tmp`); images reach `ux-baselines/device/<wave>/` only after the owner reviews them, downscaled to 1076 px on the long edge like `device-before/`; the owner decides at the end of each session whether FLAG_SECURE goes back on, and the report records its state. The owner's real vault is on screen, so **nothing leaves `.agent-logs/` without the owner's review.** An option for later is a debug-only `applicationIdSuffix` (".ux") so acceptance runs against a fixture vault; it needs owner approval and a `ManifestPolicyTest`/manifest-audit update (bead UT-16).

### 2.7 Which layer answers which question

| Question | Lowest layer that can answer |
|---|---|
| Does the closed Fold get one pane and a drawer? | L0 (decision table), then L2 (what it looks like) |
| Does every control do something? | L1 dead-control detector |
| Does the draft survive a fold? | L3 (flip + recreation), confirmed at L5 |
| Does generation continue across a fold, and is a partial answer never lost? | L3 with `ScenarioInferenceEngine` and the retained turn controller; confirmed at L5 with a real model |
| Is the composer above the keyboard? | L2 with IME insets (simulated), L4 real IME, L5 on the Fold |
| Does the tabletop posture work? | L0 (decision), L4 (real posture), L5 |
| Is it accessible? | L0 contrast, L1 sweeps, L2 ATF, L5 TalkBack |
| Is it fast enough? | L0/L1 work counters, L5 frame metrics (§12) |

---

## 3. Device matrix and screenshot matrix

### 3.1 Devices

One enum in `:testing-ui` (`SkeinDevice`) and one set of `@Preview` specs (`MAC_UX_LAB_PLAN.md` §2.2) hold these; a unit test fails if they drift. Keys carry the **portrait dp width** so nobody has to remember which density a name meant. dp = px ÷ (dpi ÷ 160), rounded as WindowManager rounds.

| Key | Robolectric qualifiers | Window (dp) | Width / height class | Source | Why |
|---|---|---|---|---|---|
| `phone` | `w360dp-h800dp-port-xhdpi` | 360 × 800 | Compact / Medium | brief | narrowest phone; unchanged from the spike |
| `split-320` | `w320dp-h1007dp-port-330dpi` | 320 × 1007 | Compact / Expanded | Android's split-screen minimum on the inner display | header and chip stress only (`CHAT_UX_SPEC.md` §26) |
| `fold-outer-443` | `w443dp-h994dp-port-390dpi` | 443 × 994 | Compact / Expanded | measured px, stock 390 (`UX_AUDIT.md`) | closed Fold at stock density; the narrowest real outer width |
| `fold-outer-524` | `w524dp-h1175dp-port-330dpi` | 524 × 1175 | Compact / Expanded | **measured while folded** (1080 × 2424, 330 forced) | **the owner's closed Fold** |
| `fold-outer-443-land` | `w994dp-h443dp-land-390dpi` | 994 × 443 | Expanded / **Compact** | computed | compact height: must stay single-pane |
| `fold-outer-524-land` | `w1175dp-h524dp-land-330dpi` | 1175 × 524 | Expanded / Medium | computed from measured | the 600 dp height gate's real case |
| `fold-inner-852` | `w852dp-h883dp-port-390dpi` | 852 × 883 | Expanded / Medium | Studio `pixel_9_pro_fold` = Roborazzi `Pixel9ProFold` | the tightest two-pane layout (12 dp over the breakpoint) |
| `fold-inner-852-land` | `w883dp-h852dp-land-390dpi` | 883 × 852 | Expanded / Medium | computed | stock landscape; tabletop happens here |
| `fold-inner-1007` | `w1007dp-h1043dp-port-330dpi` | 1007 × 1043 | Expanded / Expanded | **measured** (`sw1007dp`) | the owner's open Fold, portrait |
| `fold-inner-1007-land` | `w1043dp-h1007dp-land-330dpi` | 1043 × 1007 | Expanded / Expanded | **measured** (`sw1007dp w1043dp h1007dp 330dpi`) | **the owner's daily configuration** |
| `medium-791` | `w791dp-h820dp-port-420dpi` | 791 × 820 | Medium / Medium | hypothetical (a larger Display size) | Medium canary: one pane plus rail |
| `large-1280` | `w1280dp-h800dp-land-xhdpi` | 1280 × 800 | Large / Medium | `MediumTablet` + land | the only three-pane tier |

**Mapping old names.** The committed `ux-baselines/before/` keeps the spike's folder names; the sibling specs were written against them too. This table is how a "before" is paired with an "after", and what a spec's device word means:

| Spike folder / spec word | Spike qualifiers | Key here | Note |
|---|---|---|---|
| `phone` | `w360dp-h800dp-port-xhdpi` | `phone` | same |
| `fold-outer` | `w411dp-h923dp-port-420dpi` | `fold-outer-443` **and** `fold-outer-524` | the spike's size matched neither real setting; UT-0 records both. Where a spec asks for font 2.0 "on fold-outer", use `fold-outer-443` (narrowest); everything else uses `fold-outer-524` in T1. Before/after pairs use the nearest (`fold-outer-443`) and the PR says so. |
| `fold-inner` | `w1006dp-h1043dp-port-330dpi` | `fold-inner-1007` | 1 dp narrower than measured |
| `fold-landscape` | `w1043dp-h1006dp-land-330dpi` | `fold-inner-1007-land` | 1 dp shorter than measured |
| `fold-inner-stock` | `w852dp-h883dp-port-390dpi` | `fold-inner-852` | same size |

### 3.2 Themes, font scales and modifiers

| Axis | Values | How |
|---|---|---|
| Theme | `light`, `dark` | `+notnight` / `+night` qualifier before launch (the spike), or `DeviceConfigurationOverride.DarkMode(true)` inside the composition |
| Font scale | `fs100`, `fs150`, `fs200` | `RuntimeEnvironment.setFontScale` before launch, or `DeviceConfigurationOverride.FontScale(2f)`. SDK 34 applies Android 14's non-linear scaling, as the phone does. |
| `ime` | keyboard up | `DeviceConfigurationOverride.WindowInsets(WindowInsetsCompat.Builder().setInsets(Type.ime(), Insets.of(0, 0, 0, imePx)).setVisible(Type.ime(), true).build())`. Robolectric has no IME window; this is the only way to capture "keyboard up". Height: 40 % of the window height in portrait and 50 % in landscape **until measured on the Fold** (UT-15 records the real keyboard heights for both displays into `SkeinDevice`). |
| `bars` | status + navigation bars | the same override with `statusBars` / `navigationBars` / `displayCutout`. Robolectric draws no bars and reports zero insets, so edge-to-edge bugs are invisible without it. Heights measured on the Fold like the IME. |
| `rtl` | right-to-left | `DeviceConfigurationOverride.LayoutDirection(LayoutDirection.Rtl)` (spot checks) |
| `tabletop`, `book` | posture | inject `WindowAdaptiveInfo(windowSizeClass, Posture(isTabletop = …, hingeList = …))` into the shell (a parameter with a default, NiA pattern). No Robolectric qualifier exists for folding features. |
| `kbd` | physical keyboard attached | `DeviceConfigurationOverride.Keyboard(Configuration.KEYBOARD_QWERTY, …)` (present in 1.12.1), for shortcut-hint states |

### 3.3 Tiers (so baselines stay reviewable)

The full cross product (12 devices × 2 themes × 3 font scales × ~150 states) would be ~10,000 images. Instead:

| Tier | Devices | Theme × font | Which states | Est. images |
|---|---|---|---|---|
| **T1 every state** | `phone`, `fold-outer-524`, `fold-inner-852`, `fold-inner-1007-land` (spec **C** rows: the two Compact devices only; **X** rows: the two Expanded devices only) | light × fs100 | every registry state (§3.4); the chat activity sub-states (`chat-activity-*`, `chat-reasoning-*`, `chat-stopping`, `chat-stopped-*`) on `fold-outer-524` and `fold-inner-1007-land` only | ~330 |
| **T2 dark** | the same four | dark × fs100 | spec rows marked ★ (chat) or **F** (knowledge), plus the plan-owned rows marked T2 | ~110 |
| **T3 text scaling** | `fold-outer-443` (all ★/**F** rows); `split-320` (header and chip rows); `phone` (landing, drawer, dialogs) | light × fs200; fs150 for the landing and `chat-content-stress` (continuity with the before set) | ★ / **F** / plan rows marked T3 | ~70 |
| **T4 layout canaries** | `fold-outer-443`, `fold-outer-443-land`, `fold-outer-524-land`, `fold-inner-852-land`, `fold-inner-1007`, `medium-791`, `large-1280` | light × fs100 | shell-level rows marked T4 (landing, drawer/rail, chat + inspector, knowledge + connections, graph + node) | ~70 |
| **T5 fold transitions** | pairs `fold-inner-1007-land ↔ fold-outer-524` and `fold-inner-852 ↔ fold-outer-443` | light × fs100 | before / after / after-recreate frames of Tests A–G (§7), in **one composition** | ~40 |
| **T6 keyboard up** | `fold-outer-524`, `fold-inner-1007-land` | light × fs100, mod `ime` | composer and editor rows marked T6 (`chat-keyboard-up`, `chat-composer-multiline`, `note-ime`, `note-new`) | ~10 |

**~630 images.** At `resizeScale = 0.5` the spike's text-heavy Fold captures (100–160 KiB at full size) land around 40–70 KiB and empty or dialog states around 15–25 KiB, so **~30–35 MiB** in git. Where a spec asks for "light and dark" on every row, this plan captures dark only for its ★/**F** rows; extend dark to all T1 states only if Wave 11 measures room under the budget (§4.4).

### 3.4 State registry

A state id is `<surface>-<rest>` in kebab case, the convention the spike introduced and the specs use. **The specs own their surfaces' state tables, and their names are used verbatim:**

| Surfaces | Owner and table | Tier markers |
|---|---|---|
| `chat-*`, `chats-*` | `CHAT_UX_SPEC.md` §26 (43 states) | ★ → T2 + T3 (fs200 on `fold-outer-443`); `chat-keyboard-up` and `chat-composer-multiline` → T6 |
| `knowledge-*`, `note-*`, `connections-*`, `file-*`, `citation-*`, `attach-picker-*`, `palette-knowledge-*`, `graph-*` | `KNOWLEDGE_UX_SPEC.md` §22 (44 states) | **F** → T2 + T3; **X** → Expanded devices only; **C** → Compact devices only; `note-ime`, `note-new` → T6 |
| `shell-*`, `gate-*`, `palette-*` (other than Knowledge's), `models-*`, `settings-*`, `dialog-*` | **this plan (seed below) until `ADAPTIVE_LAYOUT_SPEC.md`, `DESIGN_SYSTEM.md` and a models/settings spec take them over** | as marked |

A new state gets a row in its spec's table (with a "Tier" marker if it needs more than T1) and nowhere else; the implementing bead's record scope lists state ids from those tables.

**Seed states owned by this plan** (from IA §3.2–3.7 and prompt §33/§37):

| State id | What it shows | Fixture / scenario (§6) | Tiers | Wave |
|---|---|---|---|---|
| `shell-drawer-open` | Compact drawer: New chat, ⌕, five destinations, chats grouped Today / Yesterday / Previous 7 days / Previous 30 days / by month | `F-CHATS-HISTORY` | T1 T2 T3 | 3 |
| `shell-rail` | Expanded rail with Conversations \| Chat | `F-CHATS-HISTORY` | T1 T4 | 3 |
| `shell-detail-placeholder` | Expanded with nothing selected: "What are you working on?" + Recent (never "No tabs open") | `F-VAULT-MEDIUM` | T1 T4 | 3 |
| `shell-destination-roots` | each destination's root (one image per destination) at the four T1 devices | per destination | T1 | 3 |
| `palette-empty` | "Search or run a command" with recents | `F-VAULT-MEDIUM` | T1 | 3 → 10 |
| `palette-results` | mixed results with kinds and snippets | `F-VAULT-MEDIUM`, query "sub" | T1 T3 | 3 → 10 |
| `palette-chats` | the Chats section (chat search on Compact, IA §8a) | `F-CHATS-HISTORY` | T1 | 5 |
| `palette-commands` | commands with icons, descriptions, shortcuts | — | T1 | 10 |
| `palette-no-results` | empty result state | query "zzz" | T1 | 3 → 10 |
| `gate-setup`, `gate-unlock`, `gate-unlock-error`, `gate-recovery` | vault gate, restyled; recovery gains its action (CMS-P0-08) | — | T1 T2 | 2.5 → 9 |
| `models-list`, `models-empty`, `models-importing`, `models-details`, `models-error` | the Models destination | `F-MODELS`, `F-MODEL-LONG` | T1; T3 for `models-list` | 9 |
| `dialog-delete-model` | confirmation in the error colour | `F-MODELS` | T1 | 9 |
| `settings-root`, `settings-privacy`, `settings-personas`, `settings-about`, `settings-advanced` | Settings | — | T1; T2 T3 for `settings-root` | 9 |

(The landing, "What are you working on?", is `chat-landing-ready` / `chat-landing-nomodel` in `CHAT_UX_SPEC.md` §26; it is captured at T4 too.)

### 3.5 Naming convention (grammar)

```text
ux-baselines/<module-dir>/<device>/<state-id>__<theme>__fs<NNN>[__<mod>].png
ux-baselines/<module-dir>/transitions/<test>__<step>__<device>__<theme>.png

module-dir := Gradle path with ':' → '-', leading '-' dropped   (feature-chat, feature-shell, app)
device     := a key from §3.1
state-id   := [a-z]+(-[a-z0-9]+)+     (the spec's name verbatim; its first segment is the surface;
                                       never contains a device, theme or font scale)
theme      := light | dark
NNN        := 100 | 150 | 200
mod        := ime | bars | rtl | tabletop | book | kbd   (at most one; add a registry row for combinations)
test       := test-a … test-g
step       := before | after | after-recreate
```

`__` separates the variation axes, so a state id can contain any number of `-`. Examples: `ux-baselines/feature-chat/fold-outer-524/chat-activity-starting__light__fs100.png`; `ux-baselines/feature-editor/fold-outer-443/note-long-content__light__fs200.png`; `ux-baselines/app/transitions/test-a__after__fold-outer-524__light.png`. `ls ux-baselines/*/fold-outer-524/` gives the §39 device-first view across modules.

---

## 4. Baseline policy

### 4.1 Where baselines live

| Path | What | Mutable? |
|---|---|---|
| `ux-baselines/before/` | the spike's 153 JVM captures of the pre-overhaul UI (old device names, full resolution) | **Frozen.** Never re-recorded: the "before" of every first redesign. |
| `ux-baselines/device-before/` | the owner-device before pass | **Frozen.** |
| `ux-baselines/<module-dir>/…` | current goldens, one directory per Gradle module | Changed only with a UI change, under a record scope (§4.2) |
| `ux-baselines/device/<wave>/` | owner-reviewed device evidence from L5 runs | Append-only per wave |
| `feature/<m>/build/outputs/roborazzi/`, `build/reports/roborazzi/` | compare/actual images, HTML report | git-ignored (`build/`) |

**Wiring (bead UT-2):** in each module, `roborazzi { outputDir.set(rootProject.layout.projectDirectory.dir("ux-baselines/<module-dir>")); compare { outputDir.set(layout.buildDirectory.dir("outputs/roborazzi")) } }` (both properties exist on the 1.75.0 extension, checked with `javap`), plus `roborazzi.record.filePathStrategy=relativePathFromRoborazziContextOutputDirectory` and `roborazzi.record.resizeScale=0.5` in `gradle.properties` (both property names are in 1.75.0's jars). `verify` and `compare` then read the committed goldens directly; nobody copies the before set into build directories any more. Belt and braces: `.gitignore` gets `ux-baselines/**/*_compare.png` and `ux-baselines/**/*_actual.png`.

**Hazard after the switch:** `clearRoborazziDebug` deletes a module's *outputs*, which will then be the committed goldens. **Never run `clearRoborazzi*` after UT-2.** Clean with `rm -rf feature/<m>/build/intermediates/roborazzi feature/<m>/build/outputs/roborazzi`, and always pass `--no-build-cache` (the spike's stale-image caveat still applies). If it happens anyway: `git restore ux-baselines/`. UT-2's acceptance includes "a `verify` run leaves `git status ux-baselines/` clean", and the CI job asserts the same (§13.1).

**Why per module rather than device-first at the root:** Gradle 9 must not see two modules' Roborazzi tasks share an output directory (`ROBORAZZI.md` §6.1), and tests stay in their module so they can reach `internal` stateless screens.

### 4.2 Recording

- **Where:** the MacBook (macOS arm64), only. CI never records and never commits: no NiA-style bot re-baselining, which would defeat DCO sign-off and the human-reviewed before/after.
- **Who and what:** the implementing agent may record **only the states its bead lists** (the bead's *record scope*, for example "`chat-activity-*` on T1, T2"). After recording, `git status --short ux-baselines/` must show only files in scope. Anything else changed means the change leaked beyond its scope or a test is non-deterministic: stop, attach the `*_compare.png` files to the bead, hand back. A design-token change that legitimately moves every image is its own bead with a full re-record and nothing else in it.
  - This amends the skills-assessment excerpt (§10.3: "NEVER create or update reference images"). With goldens committed, the PR's image diff is the review gate, so the rule becomes "never record outside the record scope". The coordinator should update the dispatch excerpt to match.
- **How:** from a clean tree, `--no-build-cache`, with the exact commands in `MAC_UX_LAB_PLAN.md` §4.
- **Never** set `roborazzi.cleanupOldScreenshots=true`: it deletes goldens of tests that were not in the filtered run.

### 4.3 OS policy: CI compare vs verify, and when to flip

The fact: Roborazzi does not guarantee identical rendering across operating systems, and the spike recorded on macOS arm64 without ever comparing Linux x86_64. Robolectric's native graphics come from `nativeruntime-dist-compat` (one artifact holding mac-aarch64, mac-x86_64, linux-x86_64 and windows natives), so the renderer binary differs by host.

Plan (bead UT-3):
1. **Measure first.** Record on the Mac; run `verifyRoborazziDebug` on `ubuntu-latest` in a throwaway PR; count differing images and the largest per-image pixel delta.
2. **Pick the runner by the data.**
   - 0 differing images → `ubuntu-latest`.
   - Anti-aliasing-level differences only → `ubuntu-latest` with a CI-only `changeThreshold` (0.001–0.01, via `RoborazziOptions(compareOptions = CompareOptions(changeThreshold = …))` read from a system property the job sets). The Mac stays at 0.
   - Larger differences → a `macos-latest` job (Apple Silicon: the same mac-aarch64 native as the MacBook). Cost: macOS runner minutes (free for public repositories, about 10× Linux for private ones).
3. **Non-blocking** from the day the job lands: `continue-on-error: true`, artifacts uploaded on failure, a job summary listing changed images.
4. **Flip to blocking** when all of these hold: the Wave 3 shell has merged (shell goldens stop churning), 10 consecutive green runs on `main`, and no determinism bug open against a screenshot test. Record the flip in the workflow's comment and in `docs/TESTING.md`.

### 4.4 Image-size budget

- `resizeScale = 0.5` everywhere (the NiA precedent: 136 PNGs in ~4.9 MB). The comparison stays pixel-exact at the recorded scale.
- Per image: an alarm above 150 KiB (usually an unbounded list or a full-resolution capture).
- Total under `ux-baselines/` excluding `before/`, `device-before/` and `device/`: **warning at 40 MiB, failure at 50 MiB** (a `du -sk` step in the CI job). No Git LFS: it complicates clones and the reproducible-build checkout for little gain at this size.
- Levers before raising the budget: drop dark variants of states with no colour change; keep activity sub-states on two devices; phone at `mdpi`; move rarely-changing T4 canaries to a monthly re-record.

### 4.5 Reviewing diffs

1. **Locally:** `open feature/<m>/build/reports/roborazzi/index.html` (per module), or open every `*_compare.png` at once (each shows reference · diff · actual). Commands: `MAC_UX_LAB_PLAN.md` §4.4.
2. **In the PR:** committed goldens appear in GitHub's image diff (2-up, swipe, onion skin). New states show only "after"; for a first redesign the PR body pairs them with the frozen `ux-baselines/before/` image using §3.1's mapping.
3. **Per changed image, the reviewer asks:** intended? inside the record scope? any clipped text, overlap, touch target under 48 dp, content hidden by the IME? The accessibility sweeps (§9) catch most of these; the human judges what they cannot (hierarchy, spacing, emphasis).

### 4.6 The evidence rule for every UI PR (§39)

Every PR that changes pixels carries, in its body under **UX evidence**:

| Item | Minimum |
|---|---|
| Screenshots | the changed states on `fold-outer-524` and `fold-inner-1007-land`, light (more if the record scope says so) |
| Before/after | the `*_compare.png` from `compareRoborazziDebug` against `main`'s goldens, or the paired `ux-baselines/before/` image for a first redesign |
| Test | the name of every test added or changed, and its layer (§2) |
| Explanation | what changed and why, in two to five sentences, naming the spec section |
| Visual diff | `git diff --stat ux-baselines/`; every path inside the record scope |
| P0 link | if it fixes a P0: the P0 id and its regression test from §8 |

A UI PR without this section is not reviewed. "Implemented redesigned chat" is not evidence.

---

## 5. Shared screenshot helper module — decision

**Today:** `UxScreenshots.kt` (101 lines: `UxDevice`, `UxSpec`, `UxDeviceRule`, `captureUx`) is copied into seven modules, identical except for the package line; `UxFixtures.kt` is copied into two (shell, timeline).

**Decision: a test-only Android library `:testing-ui`**, consumed with `testImplementation(project(":testing-ui"))` (and `androidTestImplementation` for the emulator suite's shared pieces).

| Option | Verdict | Why |
|---|---|---|
| **`:testing-ui` module** | **ADOPT** | One home for the device matrix, capture, insets overrides, accessibility sweeps, the dead-control detector, key-input helpers and the RoborazziActivity rule. It depends on Robolectric, compose `ui-test`, Roborazzi and `androidx.test` only — **no feature module**, so no dependency cycle and no flavour (no `missingDimensionStrategy`). The same shape as NiA's `:core:screenshot-testing`. |
| Put it in `:testing` | REJECT | `:testing` is pure JVM by guard (`checkIsolationGuards`); Robolectric and Compose test APIs are Android-only. |
| `testFixtures` of `:feature:shell` | REJECT | It couples every module's tests to the shell (which Wave 2 is trying to stop features depending on), and AGP test fixtures for Android libraries add variant wiring for no gain over a plain module. |
| `:core:ui-testing` | REJECT the name | `:core:*` modules ship in the APK; this never does. `:testing-ui` sits next to `:testing` and says what it is. A flat directory (`testing-ui/`), because `:testing` is itself a project and nesting a project inside its directory confuses source-set globbing. |

**Cost:** one `build.gradle.kts` (~40 lines, `com.android.library`, `namespace = "app.skein.testing.ui"`, no resources), ~250 lines of Kotlin moved or written, seven copies deleted, seven `testImplementation` edges. No new external artifact, so **no `verification-metadata.xml` change**. Size S (bead UT-1).

**Contents:** `SkeinDevice` (§3.1, later with measured IME and bar heights), `UxSpec` (device, theme, font scale, mod), `UxDeviceRule`, `skeinComposeRule()`, `captureUx()` (naming per §3.5), `withIme()` / `withSystemBars()` / `withPosture()`, `FoldFlip` (a `mutableStateOf(DpSize)` driving `DeviceConfigurationOverride.WindowSize`), `recompositionCount()` (§12), `assertTouchTargets()`, `assertEveryActionIsNamed()`, `assertNoTextOverflow()`, `assertNoDeadControls()`, `pressShortcut()`, and a test that `SkeinDevice` matches the multipreview specs.

---

## 6. Fixtures and the fake inference engine

### 6.1 Where fixtures and fakes live

The constraint: `:testing` exposes JUnit 4 (EPL-1.0, off the foss allowlist) and `kotlinx-coroutines-test` as `api`. That is why `:feature:timeline` uses `debugCompileOnly(project(":testing"))` for its previews (`skein-64y9`): a `debugImplementation` edge put JUnit on `:app`'s fossDebug runtime classpath and failed `licenseAuditFossDebugRuntimeClasspath`. With `debugCompileOnly` the preview compiles, but the classes are on no runtime classpath: **[verify]** whether Android Studio renders `TimelineScreenPreview` at all.

Only 13 files in `testing/src/main` import JUnit or coroutines-test (the contract-test bases, `MainDispatcherRule`, `TempDirRule`, `SkeinLogCapture`). The fakes themselves (`InMemoryVaultRepository`, `FakeInferenceEngine`, `FakeRetrievalService`, `Builders.kt`, `SyntheticVault`, …) import neither.

**Decision (bead UT-4):** move the JUnit-free files into a new pure-JVM module **`:testing-fakes`** (`kotlin.jvm` plus the isolation guard; depends on `:core:model` and `kotlinx-coroutines-core` only), **keeping their packages** (`app.skein.testing…`), and make `:testing` `api(project(":testing-fakes"))`. Consequences:

- Every existing `testImplementation(project(":testing"))` consumer compiles unchanged (same packages, re-exported).
- Previews and interactive lab harnesses (`src/debug`) can use `debugImplementation(project(":testing-fakes"))` safely: its runtime closure is Kotlin stdlib, coroutines-core and `:core:model`, all already on `:app`'s classpath and licence-clean. `:feature:timeline` can drop its `debugCompileOnly` workaround.
- The fakes then exist in debug APKs (never release: `debugImplementation` only). A source guard keeps them out of production wiring: a CI step (`tools/ci/no-test-doubles-in-main.sh`, in the style of `no-content-logging.sh`) fails if any `*/src/main/**` file imports `app.skein.testing`.

New code in `:testing-fakes`: the fixture corpus (`app.skein.testing.corpus`) and the scenario engine (`app.skein.testing.scenario`). UI-state builders stay in each feature's `src/debug` because they need the feature's `internal` UI types (e.g. `feature/chat/src/debug/.../ChatPreviewStates.kt`, which `CHAT_UX_SPEC.md` §26 already requires: "fixtures are plain UI state built in `src/debug/`"). Debug-variant unit tests compile against `src/debug`, so screenshot tests and previews share the builders **[verify in UT-4]**.

### 6.2 Fixture catalogue

All fixtures are deterministic: fixed ids, fixed clock (`FIXTURE_NOW = 2026-09-26T10:00:00Z`, UTC), `Locale.US`, seeded randomness.

| Id | Content | Exercises | Used by |
|---|---|---|---|
| `F-TITLE-LONG` | a 100-character English title; a 60-character unbreakable word; a German compound; a CJK title; an emoji-leading title; an Arabic (RTL) title | ellipsis and wrapping in headers, drawer rows, dialogs (§45; `chat-long-title`) | chats, notes, dialogs |
| `F-TITLE-DUPES` | 5 chats whose provisional titles collide | the "no anonymous Chat" rule (§51, AC-24) | chats list |
| `F-MODEL-LONG` | the owner's id `qwen2.5-3b-instruct-abliterated-q3-k-m-2c5f9a121ae6`; filename `qwen2.5-3b-instruct-abliterated-q3_k_m.gguf`; `Meta-Llama-3.1-70B-Instruct-IQ2_XXS.gguf`; a 40-character display name (AC-03); a 90-character custom import name | P0-12 / CMS-P0-06, the header subtitle, the model sheet, Models, `ModelNameResolver` (§19.4 rows) | chat header, sheet, Models |
| `F-MODELS` | 3 on-device models (default, loaded, importing) and 1 failed | Models states, `chat-model-sheet`, `chat-model-switch-pending` | Models, sheet |
| `F-PERSONAS` | Default + 3 personas, one with a long name (the persona row appears only once personas exist, IA §8a) | persona row | sheet, settings |
| `F-MD-TABLE` | an 8-column table with long cells and a numeric column | horizontal scroll, table rendering | chat, note |
| `F-MD-CODE` | a 40-line Kotlin block; a 200-character line; a block with no language; inline code | horizontal scroll, the Copy button | chat, note |
| `F-MD-LIST` | a 6-level nested list, mixed ordered and unordered, a task list, a list ending in a citation | indentation, the spike's `[1` clip bug | chat, note |
| `F-MD-CITE` | citations `[1]`–`[12]`, `[10]` and `[1, 2]`, one inside bold, one split across stream pieces (`"[", "1", "]"`), one adjacent to a list end | `CitationParser` buffering, chip targets | chat |
| `F-MD-MIXED` | headings, block quote, links, a 200-character URL, emoji | general rendering (`chat-content-stress`) | chat, note |
| `F-CHAT-SHORT` | 4 turns with one citation | populated chat | chat |
| `F-CHAT-200` / `F-CHAT-500` | 200 / 500 messages generated from a seed: power-law lengths, every 10th with code, every 7th with citations, fixed timestamps | lazy list, scroll restoration, `chat-jump-to-latest`, the "open a 200-message chat" budget (§12) | chat, perf |
| `F-HUGE-MESSAGE` | a 5,000-character user message and a 20,000-character answer | `chat-huge-user-message`, block-level lazy items | chat, perf |
| `F-DRAFT-LONG` | a 12-line draft with a 300-character line | composer growth bound (`chat-composer-multiline`) | composer |
| `F-CHATS-HISTORY` | 30 chats across Today / Yesterday / Previous 7 days / Previous 30 days / two months, with `F-TITLE-LONG` members, one answering, one with a finished dot | drawer and list grouping (`chats-list`) | drawer, chats |
| `F-VAULT-EMPTY` | no documents | every empty state (§33) | landing, knowledge, graph |
| `F-VAULT-SMALL` / `-MEDIUM` / `-HUGE` | `SyntheticVault` presets (40 / 400 / 1,000 documents; already in `:testing`) | lists, search, paging | knowledge, palette |
| `F-KNOWLEDGE-LONG` | the Knowledge spec's long-content fixture: an 80-character title, `quarterly-report-2025-final-final-v3.pdf`, 6-deep lists, a 6-column table, a 200-character code line | `note-long-content`, list rows | knowledge, note |
| `F-NOTE-*` | a note with properties; a new empty note; a note with 40 backlinks; a note with a missing link | editor, Connections, K-P0-2 | note |
| `F-FILE-PDF` | an attachment with extracted text and pages | `file-viewer-*` | file |
| `F-SOURCES` | 8 retrieved passages from 3 notes with long source names (`2026-08 grow journal (final) (copy 3).pdf`) | inspector, sources | chat, inspector |
| `F-ATTACHED-2` | two attached notes | context chip states (`chat-chip-states`) | chat |
| `F-GRAPH-8` / `-80` / `-ISOLATED` | an 8-node neighbourhood; 80 nodes; a note with no links (settled layout, fixed seed) | `graph`, `graph-dense-80`, `graph-unconnected-note` | graph |
| `F-ERRORS` | one of each `InferenceException` subtype | AC-22: its sentence and action, never a class name | chat, models |

### 6.3 The scenario engine (`ScenarioInferenceEngine`)

**Goal:** one definition of "what the model does" that drives JVM tests, screenshots, previews and the Mac lab, without the production pipeline and without touching `inference-service`.

**Contract fit.** The locked `InferenceEngine` (`core/model/.../Inference.kt:325`) has `load`, `stream` (a cold `Flow<Token>` of `Token.Text` and exactly one `Token.Done`), `embed`, `cancel` and `unload`. The contract has no prefill-progress event and no reasoning token (`ChatTurnState.kt` says so). The engine therefore has two outputs:

1. the **token stream** through the real contract, so `SendPipeline`, `CitationParser`, persistence and cancellation run for real. Reasoning is emitted as a `<think>…</think>` span inside `Token.Text`, which is what a reasoning model sends today and what `ChatTurnState.Thinking` is documented to fold;
2. an **activity side channel** (`Flow<ScenarioActivity>`: model starting/ready, retrieval finished with counts, prefill progress `processed/total`) that the Wave 4 `TurnActivityEvent` source can consume in tests and the lab. When the inference owners add a real prefill-progress seam (chat ask B1), production feeds the same reducer and the scenario keeps feeding the tests.

```kotlin
// :testing-fakes — app.skein.testing.scenario (pure Kotlin/JVM, JUnit-free)
data class Scenario(val id: String, val steps: List<Step>)

sealed interface Step {
    data class LoadModel(val ms: Long, val failWith: InferenceException? = null) : Step
    data class Retrieve(val ms: Long, val passages: Int, val documents: Int) : Step   // side channel only
    data class Prefill(val ms: Long, val promptTokens: Int, val chunks: Int) : Step  // side-channel progress
    data class Reason(val pieces: List<String>, val msPerPiece: Long) : Step         // "<think>" … "</think>" as Text
    data class Answer(val pieces: List<String>, val msPerPiece: Long) : Step
    data object Stall : Step                                                          // waits for cancel()
    data class Fail(val error: InferenceException) : Step
    data class Finish(val reason: StopReason) : Step
}

sealed interface Pacing {
    data object Instant : Pacing                          // no delays: L0/L1 logic tests
    data class RealTime(val scale: Double = 1.0) : Pacing  // the lab harness on the emulator or the Fold
    class Gated(val gate: ScenarioGate) : Pacing          // screenshots and fold tests: the test releases steps
}

class ScenarioInferenceEngine(
    private val scenario: Scenario,
    private val pacing: Pacing = Pacing.Instant,
    private val stopLatencyMs: Long = 0, // cancel lands between chunks, so "Stopping…" is visible
) : InferenceEngine { /* load(): LoadModel; stream(): Prefill → Reason → Answer → Stall/Fail → Done */ }

// In a test: send; gate.releaseThrough(Checkpoint.AfterPiece(3)); capture or flip the window.
```

**Rules:**
- It must pass `InferenceEngineContractTest` through a fake-backed subclass in `testing/src/test`, like the other nine: a single `Done`, `Busy` on a second collector, cooperative cancellation, `ModelNotLoaded` after `unload`. That is its runnable check.
- Pacing in JVM UI tests is **`Gated`**, never `RealTime`: a ViewModel's `delay` runs on Robolectric's main looper, not on Compose's `mainClock`, and mixing the two is the classic flake. `RealTime` is for the interactive lab harness only.
- "Fakes must never grow real behaviour" (`docs/TESTING.md`): the engine scripts timing and outcomes; it never samples, tokenises or ranks.
- Retrieval timing lives in a trivial `DelayingRetrievalService(delegate = FakeRetrievalService(…), gate)` next to it.

**Scenario catalogue** (stable ids; previews and screenshots reference them; state names from `CHAT_UX_SPEC.md` §26):

| Id | Steps | Drives states |
|---|---|---|
| `S-FAST-ANSWER` | Load 0 · Retrieve 200 ms (3 passages, 2 docs) · Prefill 400 ms · Answer 24 pieces @ 30 ms · Finish EOS | populated chat; the calm rule (AC-11) |
| `S-SLOW-PREFILL` | Load 3.4 s · Retrieve 400 ms (7 passages, 3 docs) · Prefill 72 s in 8 chunks · Answer 212 pieces @ 85 ms · Finish EOS | `chat-activity-starting`, `-searching`, `-reading-5s`, `-reading-3m`, `-reading-progress`, `-writing`, `-collapsed`, `-expanded`; AC-05 |
| `S-REASONING` | Prefill 9.8 s · Reason 40 pieces over 12 s · Answer · Finish EOS | `chat-reasoning-live`, `chat-reasoning-done`; AC-07 |
| `S-REASONING-EXHAUSTED` | Prefill · Reason until the budget · Finish LENGTH | `chat-reasoning-exhausted` |
| `S-STOP-MID-STREAM` | Answer 10 pieces · Stall; the test calls Stop; `stopLatencyMs = 400` | `chat-stopping`, `chat-stopped-partial`; the Stop persistence path |
| `S-STOP-DURING-PREFILL` | Prefill · Stall; Stop | `chat-stopped-empty`; AC-19; no ghost row |
| `S-FAIL-SERVICE-DIED` | Answer 3 pieces · Fail(ServiceDied) | `chat-error-servicedied`; AC-20 (one user message after Try again) |
| `S-FAIL-OOM-ON-LOAD` | LoadModel fails OutOfMemory | `chat-model-failed` |
| `S-CONTEXT-FULL` | Answer · Finish LENGTH mid-sentence | the out-of-room card |
| `S-MODEL-LOADING` | LoadModel 20 s (held by the gate) | `chat-model-starting` |
| `S-MARKDOWN-HEAVY` | Answer from `F-MD-MIXED` with split citation markers | `chat-content-stress`, streaming citation buffering |
| `S-LONG-ANSWER` | Answer 2,000 pieces @ 0 ms | streaming cost and the 2,000-token budget (§12) |

### 6.4 How each consumer uses it

| Consumer | Uses |
|---|---|
| L0 reducer tests | the scenario's step list → `TurnActivityEvent`s → `reduce` (no engine) |
| L1 / L3 tests | `SendPipeline` with `ScenarioInferenceEngine(Pacing.Gated)` + `InMemoryVaultRepository` + `DelayingRetrievalService` |
| L2 screenshots | stateless screens from `ChatPreviewStates.fromScenario(id, checkpoint)` (the reducer folded to a checkpoint); pipeline-driven only for flows that cannot be built statically |
| Previews (`MAC_UX_LAB_PLAN.md` §2) | the same `ChatPreviewStates` functions: one artefact for previews and baselines |
| Lab harness on the AVD / Fold | `ChatLabHarness(scenario, Pacing.RealTime(0.25))` in `feature/chat/src/debug`, launched through Studio's "Run preview" or `PreviewActivity` (`MAC_UX_LAB_PLAN.md` §4.6) |

---

## 7. Fold acceptance tests A–G (prompt §25) at every layer

After Wave 3 the full shell (destinations and entries) is assembled in `:app`, because the shell cannot depend on feature modules. The JVM suite therefore lives at **`app/src/test/kotlin/app/skein/ux/FoldTransitionTest.kt`**, hosting the new shell with fakes (never `MainActivity`), and runs in the `dev` flavour only (`assumeTrue(BuildConfig.FLAVOR == "dev")`, so `check` does not run it twice) **[decide in Wave 3 with the module layout]**. Every test has three variants: **flip** (one composition), **recreate** (qualifiers + `scenario.recreate()`) and **screenshot** (T5 frames `before`, `after`, `after-recreate`).

Fixture for all tests: chat 42 titled "Skein UX redesign" (`F-CHAT-SHORT`); note 7 "Fold launch plan"; graph centred on 7 with node 12 selected; draft `draft-that-should-survive-a-fold`; two attached notes.

| Test | Setup | Action | Pass criteria (all must hold) | L1/L3 JVM | L2 (T5) | L4 emulator | L5 device | Wave |
|---|---|---|---|---|---|---|---|---|
| **A** Open → Closed | `fold-inner-1007-land`; back stack `[ChatList, Chat(42), Context(42)]`; draft typed; 2 notes attached | flip to `fold-outer-524` | single pane; `Chat(42)` shown with its title; no rail; drawer closed; the context inspector is a bottom sheet (or dismissed, per `ADAPTIVE_LAYOUT_SPEC.md`) and Back closes it to the chat; draft equal to the fixture; context chip still "2 notes"; back-stack ids unchanged; not on the landing; no uncaught exception | yes | `test-a__before__fold-inner-1007-land`, `test-a__after__fold-outer-524`, `test-a__after-recreate__fold-outer-524` | `setClosedMode()` | journey A | 3 |
| **B** Closed → Open | `fold-outer-524`; New chat; send "hello" with `S-FAST-ANSWER` | flip to `fold-inner-1007-land` | two panes `Conversations \| Chat`; the same chat selected; exactly one list row and one chat pane for it; the vault holds exactly one chat document (AC-23); 2 messages; draft kept | yes | pair | `setFlatMode()` | journey B | 3 |
| **C** While generating | `fold-inner-1007-land`; `S-SLOW-PREFILL` gated at `chat-activity-writing` with 5 pieces streamed | flip (and separately: recreate); then release the gate | the turn keeps streaming after the event (more text after release); Stop still visible; the activity clock does not reset (± 1 s, AC-10); the user's expansion latch is kept (AC-09); exactly one assistant message is persisted, not marked interrupted; no duplicate user row | yes (needs the session turn controller, chat bead C1) | pair at `chat-activity-writing` | yes, with the harness host | real model, journey C (hardware runner) | 4 |
| **D** Keyboard active | composer focused, draft typed, IME insets applied (`ime` mod) | flip | draft kept (AC-17); composer focused after the flip; composer bounds above the IME inset on both sizes; showing the IME again is optional | yes (simulated IME) | `__ime` pair | the real soft keyboard; a Jetchat-style `keyboardShown` semantics key | journey D (real keyboard) | 3 (draft, focus) / 4 (composer) |
| **E** Knowledge | `[KnowledgeList, Doc(7)]`, caret and scroll in the body, one pending edit inside the autosave debounce | flip; then flip back | the outer shows Doc 7 full-screen and Back goes to the list; flipping back shows list + Doc 7 with the same caret and scroll (`KNOWLEDGE_UX_SPEC.md` §15); the pending edit is saved (K-P0-9); text unchanged | yes | pair | yes | journey E | 6 |
| **F** Graph | `Graph(centre = 7, selected = 12)`, zoomed | flip; then flip back | node 12 still selected; its detail is a sheet on Compact and a supporting pane on Expanded; zoom and pan kept within tolerance | yes | pair | yes | journey F | 8 |
| **G** Drawer / sheet | (1) `fold-outer-524` with the drawer open; (2) the model sheet open; (3) the palette open with query "sub"; (4) a Connections sheet open | flip to Expanded, then back | (1) drawer closed and rail visible on Expanded; after flipping back the drawer is **not** reopened; (2) the sheet becomes its Expanded form or closes per spec, never an orphaned scrim; (3) palette open with the same query and matching results (P0-08); (4) the Connections sheet becomes the pane and the reverse | yes | pair per case | yes | journey G | 3 |

**Pass on the device (L5)** additionally requires: no keyguard-induced vault lock (DBP-10), or, if the keyguard appears, the id-only back stack restored after unlock; pid unchanged; no relaunch once `configChanges` is declared.

---

## 8. P0 regression ledger

Every P0 from `AUDIT_SHELL.md` §11, `AUDIT_KNOWLEDGE.md` §13, `AUDIT_CHAT_MODELS_SETTINGS.md` §15.1 and `DEVICE_BEFORE_PASS.md` gets a test that fails on today's code and passes after its fix. Waves: 2.5 is the IA's "hide the dead" change; others per prompt §48. Where the IA removes a P0's screen, the test guards its replacement.

| P0 | Finding (short) | Regression test | Layer | Wave |
|---|---|---|---|---|
| P0-01 | Compact landing is a glyph-only rail | `ShellLandingTest.compactLandingShowsTitledStartState` + `chat-landing-*` screenshots on `phone`, `fold-outer-524` + `assertEveryActionIsNamed` | L1 L2 | 3 |
| P0-02 | Navigation trap on Compact | `ShellNavigationTest.everyDestinationReachableWhileAChatIsOpen_compact` | L1 | 3 |
| P0-03 | Drawer destinations dead | `NavigationContainerTest.drawerItemsNavigate` (5 destinations, with a chat open) | L1 | 3 |
| P0-04 | Icon-rail buttons dead | `NavigationContainerTest.railItemsNavigate` + `assertNoDeadControls` on the shell | L1 | 3 |
| P0-05 | A chat reopens as a note | `OpenByKindTest` (list, search, citation, graph node, palette → the chat screen) + `SkeinNavigatorTest.openByKind` | L0 L1 | 3 |
| P0-06 | System Back leaves the app | `ShellBackTest.backClosesSheetThenPopsThenGoesToChatThenExits` | L1 | 3 |
| P0-07a | A fold closes the graph and models overlays | `FoldTransitionTest.testF_*` + `ModelsDestinationSurvivesRecreationTest` | L3 | 3 / 8 |
| P0-07b | A fold loses the draft and the generation; so does a tab switch | `FoldTransitionTest.testA/C/D_*` + `PartialAnswerSurvivesCompositionExitTest` (below) + `DraftSurvivesDestinationSwitchTest` | L3 | 3 / 4 |
| P0-07c | A fold cancels a model import | `ModelImportSurvivesRecreationTest` (gated fake import; recreate; progress continues) | L3 | 9 |
| P0-07d | A fold resets timeline filters | replaced: `KnowledgeFiltersSurviveRecreationTest` | L3 | 6 |
| P0-08 | The command bar desyncs after recreation | `PaletteRestorationTest.queryAndResultsAgreeAfterRecreation` | L3 | 3 / 10 |
| P0-09 | Split hides the secondary pane | removed with split; guard `PaneCountChangeKeepsDetailTest` (2 → 1 panes keeps the detail entry) | L3 | 3 |
| P0-10 | Pane re-parenting disposes content | `PaneReparentingKeepsStateTest`: a `remember { UUID }` sentinel exposed through semantics in the chat pane is unchanged after flat ↔ tabletop and 1 ↔ 2 panes in one composition | L3 | 3 |
| P0-11 | A vault lock resets the shell; the idle lock is never poked | `BackStackSurvivesVaultLockTest` (`:app`) + `UserActivityPokesIdleLockTest` (with the vault owner; chat ask B8: no idle lock during a generation) | L3 | 3 |
| P0-12 / CMS-P0-06 | The model chip crushes the command field | `ChatHeaderLayoutTest.longModelNameNeverPushesActionsOffScreen` at `fold-outer-443` and `split-320`, fs100 and fs200, `F-MODEL-LONG` (AC-03) + `assertNoTextOverflow` | L1 L2 | 3 / 4 |
| K-P0-1 | No delete for notes or files | `NoteDeleteFlowTest` (⋮ → Delete → confirm → gone, lands per `KNOWLEDGE_UX_SPEC.md` §8.4, open references updated) + `DeleteClosesOpenEntriesTest` | L1 | 6 |
| K-P0-2 | The first keystroke corrupts the frontmatter | `LivePreviewTransformerTest.transformedToOriginalZeroMapsPastFrontmatter` + `NoteTypingTest.firstKeystrokeLandsInBody` | L0 L1 | ahead of 6 (KN-6.1) |
| K-P0-3 / CMS-P0-02 | Everything opens as a note; attachments become editors | `OpenByKindTest` + `AttachmentOpensReadOnlyViewerTest` | L1 | 3 / 6 |
| K-P0-4 | No titled notes list | `KnowledgeListTest.compactShowsTitledFilterableList` + `knowledge-list` screenshots | L1 L2 | 6 |
| K-P0-5 | No way back from a note on Compact | `ShellBackTest.backFromNoteReturnsToKnowledgeList` | L1 | 3 |
| K-P0-6 / CMS-P0-05 | Dead Knowledge and Personas navigation | `NavigationContainerTest.exactlyFiveRealDestinations` + `assertNoDeadControls` | L1 | 2.5 / 3 |
| K-P0-7 | Image import and Save-as crash | `ImportFailureIsReportedTest` (the fake import throws → error UI, no crash) + `SaveAsIoErrorIsReportedTest` | L1 | 2.5 / 6 |
| K-P0-8 / CMS-P0-07 | The composer's `Create "x"` row is dead | `ComposerWikilinkCreateRowTest.createsTheNoteOrIsHidden` | L1 | 2.5 / 4 |
| K-P0-9 | Pending edits dropped on close, switch, lock or fold | `EditorFlushTest` (dispose, `ON_STOP`, before lock) + Test E | L1 L3 | ahead of 6 (KN-6.1) / 6 |
| CMS-P0-01 | An in-flight answer is lost when `ChatScreen` leaves composition | **Two paths, settled from code (`UX_AUDIT.md` Reconciliations):** (1) **Stop** already persists the partial text with `INTERRUPTED_MARKER` — keep `ChatScreenTest` "cancel during streaming keeps the partial text and persists an interrupted turn" as the guard. (2) **Leaving composition** loses it today: **`PartialAnswerSurvivesCompositionExitTest`** (spec below) + Test C | L3 | 4 |
| CMS-P0-03 | Chats cannot be deleted; `/chat` leaves empty documents | `ChatDeleteFlowTest` (AC-26/27/28) + `NewChatCreatesNoDocumentUntilFirstSendTest` (AC-23) | L1 | 5 |
| CMS-P0-04 | Dead Settings rows | `SettingsNoDeadControlsTest` (`assertNoDeadControls`) | L1 | 2.5 |
| CMS-P0-08 | Recovery-required dead end | `RecoveryScreenOffersActionsTest` + `gate-recovery` screenshot | L1 L2 | 2.5 / 9 |
| DBP-05b | Tapping a palette row does not run it | `PaletteTest.tapRunsTheCommand` | L1 | 3 / 10 |
| DBP-10 | A fold through the keyguard locks the vault and resets the shell; a bare "Authentication failed" | `BackStackSurvivesVaultLockTest` + `UnlockFailureCopyTest` + L5 journey `fold-keyguard` | L3 L5 | 3 |

**`PartialAnswerSurvivesCompositionExitTest` — specification** (feature/chat or `:app`, Robolectric, Wave 4, red until chat bead C1's session turn controller lands):

- Fixture: chat 42 with `F-CHAT-SHORT`; engine `ScenarioInferenceEngine(S-SLOW-PREFILL, Pacing.Gated)`; `InMemoryVaultRepository`; send "What is left before tagging?"; release the gate until 5 answer pieces have streamed and are visible.
- Variants (one test method each), fired mid-stream:
  1. **window flip** that changes the pane count: `fold-inner-1007-land` → `fold-outer-524` in one composition;
  2. **recreation**: `RuntimeEnvironment.setQualifiers(fold-outer-524)` + `scenario.recreate()`;
  3. **destination switch**: navigate to Knowledge, then back to the chat;
  4. **citation tap**: open a cited source, then Back (AC-34);
  5. **vault lock**: lock the session (the lock ends the turn by design, `CHAT_UX_SPEC.md` §9.11).
- Then release the rest of the gate (variants 1–4) and let the flow finish.
- Pass: the vault contains **exactly one** assistant message for the turn, never zero. Variants 1–4: the turn continued, the message holds the full scripted answer, not marked interrupted, and the UI shows it once with no duplicate user row. Variant 5: the message holds at least the 5 streamed pieces and ends with `INTERRUPTED_MARKER`; after unlock the chat shows the partial answer, not "No answer was saved".
- Today variants 1–5 fail (the `CancellationException` exits `collect`, the `finally` only stops the ticker, and the persist block never runs, `SendPipeline.kt:241-294`); they go green with C1.

### 8.1 Existing tests that pin defects (change them in the same wave)

| Test | What it pins | Wave |
|---|---|---|
| `SkeinAppTest` "an open tab still wins over a non-TIMELINE destination", "No tabs open — back to timeline" | P0-03, P1-01 | 3 |
| `ChatScreenTest` "ServiceDied … retry re-sends the same prompt" (asserts two identical user rows, L318–325) | CMS-P1-05; replaced by AC-20 | 4 |
| `ChatScreenTest` "tap opens preview, second tap shows excerpt" | an unreachable path (CMS-P1-07) | 4 |
| `PaneLayoutTest`, `AdaptiveLayoutStateTest`, `SplitCoordinatorTest`, `TabsStateTest` | the hand-rolled shell | replaced in 3 |
| `AdaptivePaneHostPreviews` at 400/700/1000 dp | not Fold sizes (P2-07) | 3 |

---

## 9. Accessibility tests (§40)

| Check | How | Layer | Tooling cost | Wave |
|---|---|---|---|---|
| **Touch targets ≥ 48 dp** | `assertTouchTargets()`: every node with `OnClick`, `OnLongClick` or a toggleable state has `SemanticsNode.touchBoundsInRoot` (what `assertTouchHeightIsEqualTo` reads, so `minimumInteractiveComponentSize` counts) of at least 48 × 48 dp. Exemption list only as a spec grants it (inline citations, `CHAT_UX_SPEC.md` AC-18). Known offenders today: theme options ~34 dp, context rows ~46 dp, the frontmatter chip ~20 dp. | L1 on every T1 fixture | none | 2 (helper), applied per wave |
| **Accessible names** | `assertEveryActionIsNamed()`: every actionable node's merged content description or text contains at least one letter (`\p{L}`). This catches glyph-only labels (the paperclip, stop and return glyphs, `$`, `⚹`) that TalkBack reads as symbols. | L1 | none | 2 |
| **State semantics** | selected (drawer, rail, list rows), expanded/collapsed (activity block, context), disabled with a reason (model Delete), headings on screen titles, the collapsed summary's `Role.Button` and action label (`CHAT_UX_SPEC.md` §9.10) | L1 assertions per surface | none | per wave |
| **Contrast (tokens)** | extend `SkeinColorContrastTest` (`WcagContrast`) to every content/container token pair in `DESIGN_SYSTEM.md`, light and dark: ≥ 4.5:1 body text; ≥ 3:1 large text, UI components and focus indicators | L0 | none | 2 |
| **Contrast, labels and targets on rendered pixels** | ATF `AccessibilityCheckPreset.LATEST` on each captured T1 state via `roborazzi-accessibility-check` (NiA runs it on Robolectric); warnings first, errors from Wave 11. Alternative: Compose's own `enableAccessibilityChecks()` (`ComposeAccessibilityValidator` is in ui-test 1.12.1; its ATF-backed implementation is the separate `ui-test-accessibility` artifact). **[verify]** Robolectric compatibility and pick one. | L2 | new artifacts → `verification-metadata.xml` (§13.5) | 3 (warn) → 11 (error) |
| **Font scale 200 %, no clipping on the outer display** | T3 screenshots at `fs200` on `fold-outer-443`, plus `assertNoTextOverflow()`: for every text node, invoke `SemanticsActions.GetTextLayoutResult` and require `!hasVisualOverflow` unless the node is intentionally ellipsized *and* its full text is reachable (content description or detail); plus "no node's right edge exceeds the root width" | L1 L2 | none | 3 → 11 |
| **TalkBack traversal order** | Automated approximation: assert the merged-tree order of key nodes (chat: title → model subtitle → messages oldest → newest → context chip → composer → Send, `CHAT_UX_SPEC.md` §21.2) and any explicit `traversalIndex` / `isTraversalGroup`. The real order: the owner with TalkBack on, per wave, on the Fold (a checklist in the wave's L5 report). | L1 + L5 | none | 4, 6, 11 |
| **Live regions** | one polite live region on the activity block; announcements per step start and outcome, at most one per 2 s, never per token (AC-08): count `LiveRegion` announcements over a scripted `S-SLOW-PREFILL` turn; assert the streaming text is not a live region | L1 | none | 4 |
| **Destructive confirmation** | delete chat / note / file / model require a dialog whose text matches the spec, whose confirm button uses the error colour, and where Cancel has initial focus and Enter does not delete (AC-26) | L1 + L2 | none | 5, 6, 9 |
| **Keyboard, focus** | §10 | L1 | none | 10 |
| **Reduced motion** | Skein reads "remove animations" (`Settings.Global.ANIMATOR_DURATION_SCALE == 0`) into a design-system local. Test: set it to 0 under Robolectric (`Settings.Global.putFloat`), render `chat-activity-reading-5s`: the active dot is static (screenshot), `waitForIdle()` returns (no infinite transition), and the graph draws its settled layout directly (`KNOWLEDGE_UX_SPEC.md` §11.7) | L1 L2 | none | 4, 8 |
| **On-device spot check** | `uiautomator dump` / `android layout` JSON for `contentDesc`, clickable bounds and `interactions` on the Fold; works under FLAG_SECURE | L5 | none | each wave's L5 |

---

## 10. Keyboard and pointer tests (§41, the unfolded Fold with a physical keyboard)

All at L1 with `performKeyInput { … }` / `performMouseInput { … }` (both in ui-test 1.12.1; `rightClick` exists), under `DeviceConfigurationOverride.Keyboard(KEYBOARD_QWERTY, …)` so shortcut hints render, on `fold-inner-1007-land`. The key assignments are the specs' (`CHAT_UX_SPEC.md` §7.4, §12.5, §21.4; `KNOWLEDGE_UX_SPEC.md` §5.10; the Wave 10 palette spec).

| Behaviour | Test | Wave |
|---|---|---|
| Enter sends, Shift+Enter inserts a newline; Enter never sends during IME composition; soft-keyboard users can type two lines with "Enter key sends" off (AC-15) | `ComposerKeysTest` | 4 |
| Esc stops a running generation; Esc closes the topmost sheet or palette | `EscapeKeyTest` | 4 / 10 |
| Ctrl+K opens the palette; arrows move the highlight; Enter runs; Esc closes | `PaletteKeyboardTest` | 10 |
| Ctrl+N starts a new chat with the composer focused | `NewChatShortcutTest` | 10 |
| Ctrl+Shift+I toggles the context inspector | `InspectorShortcutTest` | 7 / 10 |
| Ctrl+/ shows keyboard shortcuts | `ShortcutHelpTest` | 10 |
| Ctrl+Tab cycles recent work (MRU) | `RecentCycleTest` | 10 |
| Tab / Shift+Tab focus order through rail, list, chat and composer | `FocusOrderTest` (assert `isFocused()` along the expected sequence) | 10 |
| Links, citation chips and rows activate with Enter/Space | `KeyboardActivationTest` | 4 / 6 |
| Editor: Shift/Ctrl + arrows select (K-P1-9); Ctrl+S, Ctrl+Enter | `EditorKeysTest` | 6 |
| Right-click on a chat or note row opens its menu (Rename, Delete) | `RowContextMenuMouseTest` | 5 / 6 |
| Hover shows a hover state on rows and buttons | `HoverStateTest` (`performMouseInput { moveTo(center) }`, screenshot with mod `kbd`) | 10 |
| The keyboard is never required for basic use | every touch test | — |

On the device (L5, Wave 10): the owner runs the shortcut list with the physical keyboard; the hardware runner can also inject `adb shell input keycombination KEYCODE_CTRL_LEFT KEYCODE_K` (**[verify]** on Android 17).

---

## 11. User flows (§43) → layers

L1 is the primary layer for every flow: a Compose test walks the whole flow on fakes and asserts at each step. The other column lists where it is also checked.

| # | Flow | Primary test | Also | Wave |
|---|---|---|---|---|
| 1 | Launch | `LaunchFlowTest` (first run → landing; returning → the restored back stack) | L2 `chat-landing-*`; L5 cold start through the vault gate | 3 |
| 2 | Start new chat | `NewChatFlowTest` (✎ in drawer, rail and Compact header, the landing composer, Ctrl+N; no document until the first send) | L2 `chat-landing-ready` | 4 / 5 |
| 3 | Send prompt | `SendFlowTest` (`S-FAST-ANSWER`) | L2 | 4 |
| 4 | View response | `ResponseRenderingTest` (`S-MARKDOWN-HEAVY`) | L2 `chat-content-stress` | 4 |
| 5 | View activity | `ActivityBlockFlowTest` (live steps → collapsed → expanded) | L0 reducer; L2 `chat-activity-*` | 4 |
| 6 | Stop response | `StopFlowTest` (`S-STOP-MID-STREAM`, `S-STOP-DURING-PREFILL`) | L2 `chat-stopping`, `chat-stopped-*`; L5 with a real model | 4 |
| 7 | Retry | `RetryFlowTest` (one user message, AC-20; `Answer 2 of 2`, AC-21) | L2 `chat-regenerated-pager` | 4 |
| 8 | Open previous chat | `OpenPreviousChatFlowTest` (drawer, list, palette → the chat screen) | L0 `openByKind` | 3 |
| 9 | Rename chat | `RenameChatFlowTest` (AC-25) | L2 `chat-rename-dialog` | 5 |
| 10 | Delete chat | `ChatDeleteFlowTest` (AC-26/27/28) | L2 `chat-delete-dialog*` | 5 |
| 11 | Search chats | `SearchChatsFlowTest` (Expanded: the list's field; Compact: the palette's Chats section, IA §8a) | L2 `chats-search`, `palette-chats` | 5 |
| 12 | Create note | `CreateNoteFlowTest` (visible New note; created on the first character, `KNOWLEDGE_UX_SPEC.md` §6.2) | L2 `note-new` | 6 |
| 13 | Edit note | `EditNoteFlowTest` (K-P0-2; "Saved" status) | L2 `note`, `note-save-error` | 6 |
| 14 | Rename note | `RenameNoteFlowTest` (link rewrite with Undo, §7.2) | L2 `note-rename-dialog`, `note-rename-snackbar` | 6 |
| 15 | Delete note | `NoteDeleteFlowTest` | L2 `note-delete-dialog`, `note-deleted-snackbar` | 6 |
| 16 | Search Knowledge | `KnowledgeSearchFlowTest` (results, none, preparing, clear) | L2 `knowledge-search-*` | 6 |
| 17 | Open document | `OpenDocumentFlowTest` (note → editor, file → viewer) | L2 `file-viewer-*` | 6 |
| 18 | Attach Knowledge to chat | `AttachKnowledgeFlowTest` (＋ → picker → chip; the `[[` path) | L2 `chat-attach-picker`, `chat-chip-states` | 7 |
| 19 | Open citation | `CitationFlowTest` (the source opens at the passage; Back returns to the same chat scroll position; the answer keeps streaming, AC-34) | L2 `citation-landing`; L3 scroll restoration | 4 / 7 |
| 20 | Inspect context | `InspectContextFlowTest` (sheet on Compact, pane on Expanded; chat B never shows chat A's turn, AC-31) | L2 `chat-inspector`; T4 | 7 |
| 21 | Open graph node | `GraphNodeFlowTest` (select first, Open second) | L2 `graph-selected` | 8 |
| 22 | Switch model | `SwitchModelFlowTest` (header → Model sheet → pick; "Switches after this answer") | L2 `chat-model-sheet`, `chat-model-switch-pending` | 9 |
| 23 | Change persona | `ChangePersonaFlowTest` — only once personas exist (IA §8a: the persona row is hidden until then); until then the test asserts the row is absent | L2 | 9 |
| 24 | Close Fold | Test A (§7) | L2 T5, L4, L5 | 3 |
| 25 | Open Fold | Test B | L2 T5, L4, L5 | 3 |
| 26 | Rotate | `RotateFlowTest` (flip `fold-inner-1007` ↔ `fold-inner-1007-land`, and recreate) | L4 `setScreenOrientation`; L5 | 3 |
| 27 | Use physical keyboard | the §10 suite | L5 with the owner's keyboard | 10 |
| 28 | Return after backgrounding | `ReturnFromBackgroundTest` (`scenario.moveToState(CREATED)` → `RESUMED`; process death via `StateRestorationTester`; vault lock → unlock restores the id-only stack and the encrypted draft) | L5: background past the lock timeout | 3 |

Each flow test also answers the §43 questions that can be checked mechanically: the tap count to completion (asserted as a maximum) and no dead end (the last screen offers Back or a next action).

---

## 12. Performance (§46)

The budgets are the specs': `CHAT_UX_SPEC.md` §22.9 (streaming a 2,000-token answer with a 50-message history keeps p90 frame time ≤ 16 ms and no frame > 100 ms on the outer screen; a 200-message chat shows its latest message within 300 ms; scrolling a 20,000-character answer has no dropped-frame burst > 5) and `KNOWLEDGE_UX_SPEC.md` §11.7 / §18 (first graph ≤ 500 ms for 80 nodes; ≤ 8 ms per frame while settling; 0 frames idle; p95 ≤ 16 ms per keystroke frame for a 2,000-line note; list scrolling p95 ≤ 16 ms with 500 items). This section says how each is checked, and what is feasible now.

| Check | Where | What it proves | Feasible | Wave |
|---|---|---|---|---|
| **No recomposition when idle** (AC-36) | L1: `recompositionCount()` in `:testing-ui` reads `Recomposer.runningRecomposers.value.sumOf { it.changeCount }` (public in runtime 1.12.1, checked); with no live turn on screen, advance 10 s and assert the count is unchanged | the 200 ms ticker is gone | now | 4 |
| **Bounded recomposition while streaming** | L1: release 100 pieces of `S-LONG-ANSWER` in 50 ms batches; the change count grows with the number of UI updates (≤ 20 Hz, §22.2), not with pieces × rows | the UI update rate is capped | now | 4 |
| **Only the live item recomposes** | L0 `StreamingRenderCostTest`: after 100 appended pieces, every earlier item in the new list is referentially equal to the old one; plus, **[verify]**, a per-scope count through `RecomposerInfo.observe(…)` (a runtime tooling API) | rows other than the live one can skip | now (structural); per-scope later | 4 |
| **Incremental Markdown** | L0 with a counting parser seam: parse work per update is bounded by the open block, not the whole answer (today O(n²), CMS-P1-24) | the quadratic re-parse is gone | with the Wave 4 seam | 4 |
| **One transform per keystroke** | L1 counter on the editor's transform (`KNOWLEDGE_UX_SPEC.md` §18, KN-6.4) | editor cost per edit | with KN-6.4 | 6 |
| **Stability report** | Compose compiler reports on demand (`composeCompiler { reportsDestination; metricsDestination }` behind `-Pskein.composeReports=true`): `ChatMessageUi`, `ChatUiState` and activity types are stable and skippable | skippability | a build change in bead UT-17 | 4 |
| **Lazy rendering** | L1: `F-CHAT-500` composes only visible rows (rows with the row tag ≤ visible + prefetch); `F-HUGE-MESSAGE` renders as block-level items | lazy is lazy | now | 4 |
| **Recomposition counts (interactive)** | Layout Inspector's recomposition counts on the Mac's AVD while the lab harness streams `S-LONG-ANSWER` | a human sanity check | now, manual | 4 |
| **Frame timing on the device** | `adb shell dumpsys gfxinfo app.skein reset`; stream, scroll or fold; `dumpsys gfxinfo app.skein` (janky frames %) on the Fold, by the hardware runner | indicative only on a debug build | now, manual | 4, 11 |
| **Macrobenchmark** | a `com.android.test` `:benchmark` module and a `benchmark` build type (`initWith(release)`, profileable), `FrameTimingMetric` over the **lab harness** (fakes, no vault: a Macrobenchmark cannot pass a biometric vault gate) streaming `S-LONG-ANSWER` over `F-CHAT-SHORT` × 12 (50 messages), opening `F-CHAT-200`, scrolling `F-HUGE-MESSAGE`, opening the graph with `F-GRAPH-80`, typing into a 2,000-line note; `StartupTimingMetric` for the landing. Pass = the budgets above. | real frame numbers | later (bead UT-18, after Wave 4; hardware runner; not in CI; never on emulators) | §46, 8 |
| **Graph layout off the main thread** | L1: the layout runs on `Dispatchers.Default` (an injected dispatcher) and the main thread only receives the result | no main-thread layout storm | Wave 8 | 8 |

JVM wall-clock timings are never pass/fail criteria (CI machines vary); only deterministic counts are.

---

## 13. CI integration

### 13.1 Which job runs what

| Job (workflow) | Trigger | Runs | Blocking | Budget |
|---|---|---|---|---|
| `unit-lint-assemble` (`ci.yml`, ubuntu) | every push to `main`, every PR | L0, L1, L3, and the L2 screenshot tests **as smoke tests** (they compose every state at every size and write nothing); the accessibility sweeps; `SkeinColorContrastTest`; the `no-test-doubles-in-main` guard | **yes** | UX tests add ≤ 4 min; beyond that, move the `*ScreenshotTest` classes out of `check` into the screenshots job with a filter |
| **`ux-screenshots`** (new, `ux-screenshots.yml`) | PRs and `main` that touch `feature/**`, `app/src/**`, `testing-*/**`, `ux-baselines/**`, the version catalog | `./gradlew --no-build-cache verifyRoborazziDebug verifyRoborazziDevDebug --continue` (the `:app` UX tests use `DevDebug`); then `git diff --exit-code -- ux-baselines/` (a verify must never modify goldens); the size-budget check; on failure, upload `**/build/outputs/roborazzi/**` and `**/build/reports/roborazzi/**` | **no** at first (`continue-on-error: true`); blocking per §4.3 | ≤ 15 min |
| `emulator` (`emulator.yml`, existing) | nightly 06:00 UTC, dispatch | unchanged | no | 90 min |
| **`fold-postures`** (a new job in `emulator.yml`) | nightly, dispatch | `connectedDevDebugAndroidTest` filtered to `@FoldPostureTest` on a PIN-less `pixel_9_pro_fold` AVD | no | ≤ 60 min |
| Hardware runner (not CI) | per wave, serial | L5 journeys, frame checks, Macrobenchmark later | wave gate | one session |

A sketch of the new job (the same pinned actions as `ci.yml`; **[verify]** that these modules' unit tests need no `third_party` submodules or OpenSSL tarball — if `externalNativeBuild` runs, copy `ci.yml`'s cache steps):

```yaml
name: UX screenshots
on:
  pull_request:
    paths: ["feature/**", "app/src/**", "testing-ui/**", "testing-fakes/**", "ux-baselines/**", "gradle/libs.versions.toml"]
  push:
    branches: [main]
permissions:
  contents: read
jobs:
  verify:
    runs-on: ubuntu-latest        # or macos-latest, per the UT-3 measurement (§4.3)
    timeout-minutes: 20
    continue-on-error: true       # remove when the §4.3 flip criteria hold
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v4.3.0
      - uses: actions/setup-java@de7274f081f381c8f8158605e0321c36c376e2e6 # v4.8.0
        with: { distribution: temurin, java-version: "17" }
      - uses: gradle/actions/setup-gradle@748248ddd2a24f49513d8f472f81c3a07d4d50e1 # v4.4.4
        with: { cache-read-only: "${{ github.ref != 'refs/heads/main' }}" }
      - name: Verify screenshots
        shell: bash
        # verifyRoborazziDevDebug exists only once :app applies the Roborazzi plugin (UT-7); drop it until then
        run: ./gradlew --no-build-cache verifyRoborazziDebug verifyRoborazziDevDebug --continue --stacktrace
      - name: Goldens untouched by verify
        shell: bash
        run: git diff --exit-code -- ux-baselines/
      - name: Baseline size budget
        shell: bash
        run: |
          kb=$(du -sk --exclude=before --exclude=device-before --exclude=device ux-baselines | cut -f1)
          echo "ux-baselines: ${kb} KiB"
          [ "$kb" -le 51200 ] || { echo "::error::ux-baselines over 50 MiB"; exit 1; }
          [ "$kb" -le 40960 ] || echo "::warning::ux-baselines over 40 MiB"
      - name: Upload screenshot diffs
        if: failure()
        uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7.0.1
        with:
          name: ux-screenshot-diffs
          path: |
            **/build/outputs/roborazzi/**
            **/build/reports/roborazzi/**
          if-no-files-found: ignore
          retention-days: 14
```

(`du --exclude` is GNU; on `macos-latest` use `du -sk -I before -I device-before -I device`.)

### 13.2 Runtime budget

- Mac, warm: every screenshot test in ≤ 90 s (today 172 cases take ≈ 12 s; ~630 images plus ATF ≈ 60–90 s).
- CI `check` increase from UX tests: ≤ 4 min. `ux-screenshots`: ≤ 15 min. `fold-postures`: ≤ 60 min.
- Robolectric memory: if 2076 × 2152 captures run out of heap, `testOptions.unitTests.all { it.maxHeapSize = "4096m" }` in the affected module (Roborazzi FAQ).

### 13.3 Flake policy

- **Screenshots:** no retries. A diff with no relevant code change is a **determinism bug**: file it (label `ux-flake`), and for at most one week mark only that test method `@Ignore("ux-flake <bead id>")`; the bead's fix removes it. The usual causes: an unpinned clock or locale, a random id, a running animation, a gate released too early, a fixture iterating a `HashSet`, an unsettled graph layout.
- **Compose UI and restoration tests:** forbidden: `Thread.sleep`, real-time waits, `RealTime` pacing, `waitUntil` on anything not driven by a fake. `waitUntil` timeouts ≤ 10 s. A failure that does not reproduce locally in 20 runs is still a bug, not a re-run.
- **Emulator lane:** environment flakes exist (boot, keyguard; see the `emulator.yml` history). One automatic retry of the whole job is acceptable because the lane is non-blocking; every failure is still read.
- **Device (L5):** never "retry until green". A failed assertion is the result; record it with its event files (`DEVICE_RUNNER.md`: never infer a result).

### 13.4 Gotchas (local zsh, GitHub bash)

The MacBook's shell is zsh; GitHub runs bash.

| Gotcha | Symptom | Do this |
|---|---|---|
| **Pipe exit status (zsh)** | `./gradlew verifyRoborazziDebug \| tee .agent-logs/v.log` "succeeds" when Gradle failed: the pipeline's status is `tee`'s | `setopt pipefail` first, or check `${pipestatus[1]}` (zsh's array is lower-case and 1-based; bash's `PIPESTATUS[0]` does not exist in zsh) |
| **Pipe exit status (GitHub)** | a `run:` step without `shell:` runs `bash -e {0}`, without pipefail | give every step that pipes `shell: bash` (which is `bash --noprofile --norc -eo pipefail {0}`) |
| **`status` is read-only in zsh** | `status=$?` errors | use `rc=$?` |
| **Unquoted globs (zsh)** | `--tests app.skein.feature.chat.*` → "no matches found", and the command never runs | always quote: `--tests 'app.skein.feature.chat.screenshots.*'` |
| **`=` at the start of a word (zsh)** | `echo ====` → "= not found" (equals expansion) | quote it |
| **No word splitting (zsh)** | `mods="chat shell"; for m in $mods` loops once | use a literal list or an array: `for m in chat shell` |
| **`android-emulator-runner` script lines** | each line is its own command; a backslash continuation became a task named `\` (`emulator.yml` comment) | keep each command on one line, as `emulator.yml` does |
| **Build cache** | a record or verify restores stale images from `build/intermediates/roborazzi` | `--no-build-cache`, and clean intermediates (never `clearRoborazzi*` after UT-2, §4.1) |
| **Logs** | agent logs in `/tmp` are lost and not allowed | `.agent-logs/` in the worktree (git-ignored) |

### 13.5 Dependency verification

`gradle/verification-metadata.xml` enforces SHA-256 for every resolved artifact; a missing entry fails the build with "Dependency verification failed" and names the component. Any new test dependency in this plan (`roborazzi-accessibility-check` + ATF, `espresso-device`, `benchmark-macro-junit4`, `uiautomator`, `ui-test-accessibility`, ComposablePreviewScanner) needs, in the same PR:

```sh
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home
setopt pipefail   # zsh
./gradlew --write-verification-metadata sha256 <the tasks that resolve it> 2>&1 | tee .agent-logs/verif.log
git diff --stat gradle/verification-metadata.xml   # additions only; review every component
```

- The tasks must actually **resolve** the new configuration: `testDebugUnitTest` of the affected module for test dependencies; `assembleDevDebugAndroidTest` for `androidTest` dependencies; the `:benchmark` module's assemble for Macrobenchmark.
- Metadata written on the Mac lacks artifacts that only resolve on Linux (OS-classified natives). None of the artifacts above is OS-classified (Robolectric's `nativeruntime-dist-compat` is one unclassified jar); anything that is (Skiko, if Compose Desktop were ever adopted, `MAC_UX_LAB_PLAN.md` §6) must be written on both operating systems.
- Never loosen `<verify-metadata>`, and never add `<trust>` exceptions to make a PR green (`docs/VERIFICATION.md`).

---

## 14. Implementation beads

For the coordinator to file under `skein-xtov` (this agent does not run `bd`). Sizes: S ≤ ½ day, M ≤ 2 days, L > 2 days. "Wave 2" means before the Wave 3 shell starts.

| # | Title | Scope | Size | Wave | Depends on |
|---|---|---|---|---|---|
| **UT-0** | **Re-record the outer display at 443 and 524 dp** | Replace the spike's provisional `FOLD_OUTER` (`w411dp-h923dp-port-420dpi`) with `fold-outer-443` (`w443dp-h994dp-port-390dpi`) and `fold-outer-524` (`w524dp-h1175dp-port-330dpi`); correct the inner sizes to the measured 1007 × 1043 / 1043 × 1007; record every existing screenshot test's outer captures (light, dark, the font-150 set) on the Mac; `ux-baselines/before/` stays frozen. If UT-1 lands first, do it there instead of editing seven copies. | S | **first** | — |
| UT-1 | `:testing-ui` shared screenshot and UI-test module | Create the module (§5); move `UxScreenshots.kt` with the full §3.1 matrix; `skeinComposeRule()`; delete the seven copies | S | 2 | — |
| UT-2 | Committed goldens and naming | Roborazzi `outputDir` / `compare.outputDir` per module, `filePathStrategy`, `resizeScale=0.5`, `.gitignore` for compare/actual, §3.5 naming in `captureUx`, re-record the current set under the new names (the before set stays frozen), `ux-baselines/README.md`, the `tools/ux/shots` wrapper (`MAC_UX_LAB_PLAN.md` §4) | S | 2 | UT-1 |
| UT-3 | Mac↔Linux parity + the `ux-screenshots` CI job | The §4.3 measurement; the job (§13.1), non-blocking; later, the blocking flip | S | 2–3 | UT-2 |
| UT-4 | `:testing-fakes` split, fixture corpus, `ScenarioInferenceEngine` | Move the JUnit-free fakes (same packages) with an `api` re-export from `:testing`; the §6.2 corpus; the engine, gates and `DelayingRetrievalService`; a fake-backed `InferenceEngineContractTest` subclass; the `no-test-doubles-in-main` CI guard; switch `:feature:timeline` to `debugImplementation`; confirm Studio renders a preview that uses a fake | M | 2 | — |
| UT-5 | Accessibility sweep helpers | `assertTouchTargets`, `assertEveryActionIsNamed`, `assertNoTextOverflow`, `assertNoDeadControls`, `recompositionCount` in `:testing-ui`, each with a self-test on a deliberately bad fixture; the token-contrast extension | S | 2 | UT-1 |
| UT-6 | Layout decision and navigator tables | `SkeinLayoutDecisionTest`, `SkeinNavigatorTest`, `DrawerPolicyTest` (red first, against the Wave 3 functions) | S | 3 | `ADAPTIVE_LAYOUT_SPEC.md` |
| UT-7 | Fold A–G JVM suite (flip, recreate, T5 screenshots) | `app/src/test/.../ux/FoldTransitionTest.kt`: A, B, G and D (draft, focus) in Wave 3; C and D (composer) in Wave 4; E in 6; F in 8 | M (split per wave) | 3–8 | UT-1, UT-4, the Wave 3 shell |
| UT-8 | P0 regression ledger | Track §8: each wave bead's acceptance lists its P0 tests; this bead checks every row is green by Wave 11 | S | 2.5–11 | — |
| UT-9 | "Hide the dead" regression tests | K-P0-7, K-P0-8, CMS-P0-04, CMS-P0-05, CMS-P0-08 tests with the Wave 2.5 change (K-P0-2 and K-P0-9 ship with KN-6.1) | S | 2.5 | UT-5 |
| UT-10 | ATF on screenshots | `roborazzi-accessibility-check` or `ui-test-accessibility` (decided here) on T1 at warning level; verification metadata | S | 3 | UT-2 |
| UT-11 | Keyboard and pointer suite | §10 | M | 10 | UT-1 |
| UT-12 | Reduced-motion test | the §9 row | S | 4 | UT-1 |
| UT-13 | Emulator `fold-postures` job | a PIN-less `pixel_9_pro_fold` AVD job; a `@FoldPostureTest` host activity with production `configChanges`; the Espresso Device API or the `cmd device_state` fallback; tests A, B, D, G | M | 3 | UT-4, the Wave 3 shell |
| UT-14 | Debug-only `testTagsAsResourceId` and stable tag constants | the root modifier in debug builds; tag constants in the design system | S | 3 | — |
| UT-15 | `tools/ux/fold-watch.sh` + journeys A–G (hardware runner) | the §2.6 spec; measure IME and bar heights on both displays into `SkeinDevice`; first run at the end of Wave 3 | M | 3 | UT-14 |
| UT-16 | (Optional) a `.ux` debug application id for fixture-vault device runs | owner decision; manifest guard updates | S | 3+ | owner |
| UT-17 | Streaming cost checks + compose compiler reports | the §12 L0/L1 checks (AC-36 included), the counting-parser seam, the reports property | S | 4 | the Wave 4 chat |
| UT-18 | Macrobenchmark harness (hardware runner) | `:benchmark`, the `benchmark` build type, the harness activity with fakes, the §12 journeys; numbers in a wave report | L | after 4 (§46) | UT-4, the lab harness (ML-5) |
| UT-19 | `PartialAnswerSurvivesCompositionExitTest` | §8 specification, five variants; lands red at the start of Wave 4 and goes green with chat bead C1 | S | 4 | UT-4 |
| UT-20 | User-flow tests | one Compose flow test per §11 row, delivered inside each wave bead; this bead tracks coverage | S (tracking) | 3–10 | — |

---

## 15. Open questions and verification list

1. **[verify] Studio rendering with `debugCompileOnly`** (`TimelineScreenPreview`). If it fails, UT-4's split is the fix; if it works, UT-4 is still needed for the interactive lab harness (the classes must exist at runtime on the device).
2. **[verify] Debug-variant unit tests see `src/debug`**, so previews and screenshot tests share the same state builders.
3. **[verify] The Espresso Device API with `android-emulator-runner`**; otherwise the `cmd device_state` fallback.
4. **[verify] The Fold's IME and system-bar heights** (both displays, both orientations); UT-15 measures them.
5. **[verify] The lifecycle event-log tags and keyguard fields on Android 17**, for the watcher.
6. **Where the full shell's entry assembly lives after Wave 3** decides whether the A–G suite and the T4/T5 captures live in `:app` (assumed here) or in a dedicated module.
7. **Dark on every state?** The specs ask for light and dark everywhere; this plan captures dark for ★/**F** rows only (§3.3). Revisit at Wave 11 against the measured size.
