# Roborazzi: reference design for Skein's screenshot regression and MacBook UX lab

**Bead:** `skein-xtov.7` (epic `skein-xtov`) · **Authority:** `docs/research/SKEIN_UI_UX_OVERHAUL_PROMPT.md` §§2 (rule 10), 12, 25, 37–40, 46, 52
**Companions:** `ANDROID_ADAPTIVE_SAMPLES.md` (measured Fold geometry, adaptive strategy), `NOWINANDROID.md` (production screenshot architecture), `COMPOSE_SAMPLES.md` (toolchain twin)
**Status:** Research record. Advisory. This is the **reference design only**. The build change belongs to spike `skein-xtov.9`, which is running in parallel. Nothing here edits Gradle files.

---

## 0. Provenance

| Field | Value |
| --- | --- |
| Repository | <https://github.com/takahirom/roborazzi> |
| Commit inspected | `c636f7774261cb1b6dec28144ac906bd24e1dc15` (`main`, 2026-09-26, "Merge pull request #945 … generated-tests-explicit-api"); `gradle.properties` `VERSION_NAME=1.75.0` |
| Latest release | **1.75.0** (2026-09-21); 1.74.0 (2026-09-08); 1.73.0 (2026-08-25). 22 releases in 2026 so far (`gh api repos/takahirom/roborazzi/releases`) |
| Examples repository | <https://github.com/takahirom/roborazzi-usage-examples> at `30c640f2abca224260c260c9599c33f971db83f1` (2023-12-08; last push 2024-01-26). It pins Roborazzi `1.8.0-alpha-5` and Robolectric `4.11.1`. **Stale.** Useful only for its step-by-step onboarding diffs linked from its README. |
| Licences | Both `Apache-2.0` (root `LICENSE` files; GitHub SPDX `Apache-2.0`) |
| Local clones | `research/clones/roborazzi/`, `research/clones/roborazzi-usage-examples/` (shallow, git-ignored, never committed) |
| Maven Central POMs read | `io.github.takahirom.roborazzi:roborazzi:1.75.0`, `roborazzi-compose:1.75.0` |
| Inspection date | 2026-09-26 |

Permalink prefix: `RZ` = `https://github.com/takahirom/roborazzi/blob/c636f7774261cb1b6dec28144ac906bd24e1dc15`

---

## 1. Project assessment (§52 format)

### Project

<https://github.com/takahirom/roborazzi>. JVM screenshot testing for Android. It captures Views and Compose on **Robolectric Native Graphics** (RNG, Robolectric ≥ 4.10), with record, compare and verify Gradle tasks, diff images, an HTML report, Compose Preview scanning, accessibility checks, and UI-tree dumps for AI agents.

### License

`Apache-2.0`. It is test-only (`testImplementation`), so nothing ships in the APK. The `foss` licence audit (`licenseAudit*RuntimeClasspath`) is unaffected.

### Maintenance

Healthy but maintainer-concentrated. 1,049 stars; `pushed_at` 2026-09-26; 135 open issues; releases roughly every two weeks. It is maintained mainly by one author (takahirom), with regular outside contributors (for example `sergio-sastre`, author of ComposablePreviewScanner). **Bus-factor risk:** moderate. Mitigation: Skein's tests use only `captureRoboImage` + `RoborazziOptions`. The Robolectric-side calls (`RuntimeEnvironment.setQualifiers`, `@GraphicsMode(NATIVE)`) are standard, so a switch to another RNG-based tool would be mechanical.

### Relevant Skein problem

§39 requires that every visual change produce visual evidence: before and after, a diff, and a test. §37 requires iterating on UI on the MacBook without installing an APK on the Fold. Today Skein has Compose previews (`AdaptivePaneHostPreviews.kt`, `ChatScreenPreviews.kt`, `TabStripPreviews.kt`, `NavPreviews.kt`, `ThemeGallery.kt`) and Robolectric Compose tests. It has **no image baselines, no device matrix, and no way to see the Fold's outer and inner layouts side by side except on the phone.**

### Patterns worth adopting

| Pattern | Where | Skein use |
| --- | --- | --- |
| **`captureRoboImage(path, roborazziOptions) { composable }`** for stateless screens rendered from fixtures, and `composeTestRule.onRoot().captureRoboImage(...)` for interactive flows | `RZ/docs/topics/how_to_use.md` L3–132; `roborazzi-compose/.../RoborazziCompose.kt` (overloads at L16–58) | The primary API. Render `ChatScreen(uiState = fixture)`, not `MainActivity`: the vault, `FLAG_SECURE` and the model never enter a screenshot test. |
| **`RoborazziComposeOptions { size(w, h); fontScale(f); uiMode(...); locale(...); background(...); inspectionMode(true) }`**, applied through a single `RuntimeEnvironment.setQualifiers(...)` call | `roborazzi-compose/.../RoborazziComposeOptions.kt` L17–38 (single `setQualifiers`), L182 `size`, L272 `uiMode`, L290 `locale`, L304–316 `fontScale` (calls Robolectric `setFontScale`), L321 `inspectionMode` | Font scale 1.3/2.0 and light/dark per capture without separate test classes. |
| **Device qualifiers** via `@Config(qualifiers = …)` or `RuntimeEnvironment.setQualifiers(…)`, including cumulative `+night` / `+land` | `docs/topics/how_to_use.md` L134–188; generated catalog [`RobolectricDeviceQualifiers.kt` L1–76](https://github.com/takahirom/roborazzi/blob/c636f7774261cb1b6dec28144ac906bd24e1dc15/roborazzi/src/main/java/com/github/takahirom/roborazzi/RobolectricDeviceQualifiers.kt#L1-L76) (`Pixel9ProFold` L56, `SmallPhone` L29, `MediumTablet` L31, `SmallDesktop` L23). The format is `w…dp-h…dp-<size>-<long>-<round>-<uimodeType>-<dpi>-keyshidden-<nav>` ([`Devices.kt` L23–34](https://github.com/takahirom/roborazzi/blob/c636f7774261cb1b6dec28144ac906bd24e1dc15/roborazzi/src/test/java/com/github/takahirom/roborazzi/Devices.kt#L23-L34)). | Skein's measured matrix is in §5. Robolectric swaps w and h when orientation is given ([device configuration](https://robolectric.org/device-configuration/)), so `+land` on a portrait qualifier yields the landscape window. |
| **Record, compare, verify tasks** (`recordRoborazziDebug`, `compareRoborazziDebug`, `verifyRoborazziDebug`, `verifyAndRecordRoborazziDebug`), or the same through `-Proborazzi.test.{record,compare,verify}=true` on `testDebugUnitTest`. Reports go to `build/reports/roborazzi/index.html`; diffs are `*_compare.png`. | `docs/topics/build_setup.md` L113–228 | Skein variant names: `…FossDebug` / `…DevDebug` in `:app`; plain `…Debug` in libraries such as `:feature:shell` (no flavours; `missingDimensionStrategy`). |
| `roborazzi { outputDir.set(file(...)) }` + `roborazzi.record.filePathStrategy=relativePathFromRoborazziContextOutputDirectory` | `build_setup.md` "Gradle DSL Options"; `gradle_properties_options.md` L27–33 | Puts baselines in the repo-root `ux-baselines/` tree of §39 (layout in §6). |
| `RecordOptions(resizeScale = 0.5)` + `CompareOptions(changeThreshold = 0f)` | NiA `ScreenshotHelper.kt` L56–62 (`NOWINANDROID.md`); `gradle_properties_options.md` L19–25 | Halves PNG dimensions for the 2152 × 2076 px inner captures. The compare stays pixel-exact on the *recorded* scale. |
| **Accessibility check alongside capture** (`roborazzi-accessibility-check`, ATF `AccessibilityCheckPreset.LATEST`) | NiA `ScreenshotHelper.kt` L111–149 | §40 (touch targets, contrast, labels) on every captured state. It would have flagged the 40 dp rail and its glyph-only items (`IconRail.kt`). |
| **Compose Preview → generated tests** (`generateComposePreviewRobolectricTests { enable = true; packages = …; robolectricConfig = mapOf("sdk" to "[34]") ; annotationFilter = RoboPreviewInclude }`). It honours `@Preview` `device`, `widthDp`/`heightDp`, `uiMode`, `locale`, `fontScale`, `showBackground`/`backgroundColor` | `docs/topics/preview_support.md` L6–56, L147–172; `roborazzi-compose-preview-scanner-support/.../RoborazziPreviewScannerSupport.kt` L209–221; scanner `ComposablePreviewScanner` 0.9.1 (`gradle/libs.versions.toml` L66) | **§37 Layer A/B for free:** Skein's multipreview annotations (§5.3) become baselines with no hand-written test. Use `annotationFilter = RoboPreviewInclude` so only curated previews are captured. `renderScale` (1.75.0, experimental) trades fidelity for speed. |
| **UI-tree dump for AI agents** (`roborazzi.dumpUiTree=true` writes a `.uitree.json` sidecar with each node's `testTag` and `bounds`) | `docs/topics/ui_tree_dump.md` L86–110 ("Primary use case: letting an AI agent prove its UI fixes") | Skein's UI work is done largely by agents. An agent can *prove* "the composer spans the full width at `fold-outer`" or "the rail is ≥ 48 dp" from bounds, instead of claiming it from a picture. The sidecar never affects diffing. PROTOTYPE it in the spike. |
| Agent skill shipped in the repository (`skills/roborazzi/SKILL.md` + references) | `RZ/skills/roborazzi/` | Candidate for `docs/ux/ANDROID_SKILLS_ASSESSMENT.md` (§9): "USE AS REFERENCE" for agents writing Skein screenshot tests. |
| Determinism knobs: `@GraphicsMode(NATIVE)`, `@LooperMode(PAUSED)`, `LocalInspectionMode provides true`, `TimeZone.setDefault(UTC)`, `robolectric.pixelCopyRenderMode=hardware` | `gradle_properties_options.md` L71–90; NiA tests | Adopt all. Skein's `NoShadowOrGradientTest` bans shadows, which removes the main reason for hardware render mode, but it still helps shape anti-aliasing. |

### Patterns not worth adopting

- **Recording on one OS and verifying on another.** Rendering differs across macOS, Ubuntu and Windows, and the FAQ says so explicitly ("there are no guarantees for identical rendering across all environments", `docs/topics/faq.md` L93–97, citing NiA issue #1242). Skein's UX lab is a MacBook while CI is `ubuntu-latest`, so see §7.
- **Bot re-recording in CI** (NiA's auto-commit). It conflicts with DCO sign-off and with §39's human-reviewed before and after.
- **`roborazzi.cleanupOldScreenshots=true` by default.** It deletes baselines of tests that weren't in the filtered run (`gradle_properties_options.md` L49–56).
- **AI-powered image assertions** (`roborazzi-ai-gemini`/`-openai`). They need network model APIs, which contradicts Skein's offline stance.
- **Compose Multiplatform / Desktop / iOS modules.** Skein is Android-only, and §38 recommends against a CMP migration for previews.
- **`captureRoboGif`/video** for now. A GIF of the fold transition is appealing, but the transition is a *configuration* change that RNG does not animate. Static before and after frames are the evidence.

### Potential reusable code

None to vendor. The only helper worth writing is a Skein `captureDevices(name, devices = SkeinDevices.tier1) { … }` of about 60 lines, modelled on NiA's `captureForDevice` (see `NOWINANDROID.md`), with Skein's qualifier constants (§5).

### Android/Fold relevance

High. It is the only practical way to put **all six Fold windows** (outer and inner × portrait and landscape × stock and 330 dpi) plus Medium and tablet canaries in one PR diff. Robolectric's qualifier system expresses each measured window exactly, including the owner's `sw1007dp w1043dp h1007dp 330dpi xlarge land`. It is also the only runnable check on the **§25 fold tests** that needs no device (`ANDROID_ADAPTIVE_SAMPLES.md` §5.7).

### Mac UX-Lab relevance

Very high. This is §37's Layer A + B engine:

- `./gradlew :feature:chat:recordRoborazziDebug` on the MacBook renders every chat state at every Fold size in seconds, with no APK and no phone.
- `compareRoborazziDebug` produces side-by-side `*_compare.png` plus an HTML report during iteration.
- The Roborazzi IDE plugin (`docs/topics/idea_plugin.md`, JetBrains Marketplace #24561) shows the images inside Android Studio.

### Recommended action

**ADOPT.** Wire it via spike `skein-xtov.9`, using the device matrix (§5), baseline layout (§6) and OS policy (§7) below.

### Expected benefit

- Visual evidence for every UI wave (§39, §48 Wave 11).
- Deterministic regression detection for the fold layouts that Skein cannot otherwise test without the phone.
- An accessibility audit that runs on every capture (§40).
- Preview-driven baselines that cost no extra test code.
- Agent-verifiable bounds (UI-tree dump).

### Expected cost

Low to medium. The build wiring: one Gradle plugin, plus `roborazzi`, `roborazzi-compose`, optionally `roborazzi-junit-rule`, `roborazzi-accessibility-check` and `roborazzi-compose-preview-scanner-support` + `ComposablePreviewScanner`. **Every new artifact needs entries in `gradle/verification-metadata.xml`** (Skein verifies dependencies). The ongoing cost is baseline churn in reviews and about 15–25 MB of PNGs in git (§6.3).

---

## 2. What Roborazzi replaces or complements in Skein today

| Skein today | With Roborazzi |
| --- | --- |
| `@Preview` sets in `feature/shell/.../AdaptivePaneHostPreviews.kt` at 400/700/1000 dp **with no density**. 700 dp and 1000 dp are not Fold sizes at any Display-size setting. | Previews annotated with Skein's measured devices (§5.3) → generated baselines |
| Robolectric Compose tests assert semantics only (`SkeinAppTest`, `CommandBarLayoutTest`, …) | The same tests can `onRoot().captureRoboImage(...)` at the end |
| Fold behaviour verified only by installing on the phone | JVM captures of outer and inner in portrait and landscape at stock and 330 dpi. The phone is used only for final confirmation |

---

## 3. Alternatives considered

| Tool | Verdict | Why |
| --- | --- | --- |
| **Roborazzi** | **ADOPT** | Runs on Skein's existing Robolectric 4.17 stack, so the same test can drive Compose, Espresso and `ActivityScenario`. It is proven on Skein's exact BOM and Robolectric (compose-samples) and on AGP 9 (NiA, compose-samples). It supports preview scanning, accessibility checks and UI-tree dumps. |
| **Compose Preview Screenshot Testing** (`com.android.compose.screenshot`, Google) | STUDY ONLY | Used by AdaptiveJetStream at `0.0.1-alpha13` (`screenshotTest` source set, `@PreviewTest`; `ANDROID_ADAPTIVE_SAMPLES.md`). Still alpha, **previews only** (no interaction, no state flips mid-test, so it cannot simulate a fold inside one composition), layoutlib-based, and tied to AGP releases. Revisit when it is stable. |
| **Paparazzi** (Cash App, layoutlib) | REJECT | A second rendering stack beside Robolectric. It cannot run `ActivityScenario` or Robolectric shadows that Skein's tests rely on. Its support for new compileSdk and AGP versions has historically lagged, and Skein is on compileSdk 37 and AGP 9.4. |
| On-device screenshots (emulator lane `skein-k3b2`) | Complement | Keep one or two instrumented captures on the Pixel 9 Pro Fold AVD for real-inset and real-IME truth. They are too slow for the §37 loop. |

---

## 4. Compatibility verdict for Skein's toolchain

**Verdict: COMPATIBLE. Expected to work with no toolchain changes.** Three residual items for the spike to confirm are listed at the end of this section.

| Skein pin | Roborazzi evidence | Status |
| --- | --- | --- |
| **AGP 9.4.1** (built-in Kotlin, no `kotlin-android` plugin) | Release **1.56.0** "Added AGP 9.0 compatibility to RoborazziPlugin" (PR #782). 1.57.0 fixed AGP 9 KMP preview generation. 1.61–1.64 hardened for Gradle 9 (named task inputs, image-input filtering, plugin validation). In the field: **NiA on AGP 9.3.2 + Roborazzi 1.56.0**; **compose-samples Jetcaster wear on AGP 9.3.1 + Roborazzi 1.74.0 + built-in Kotlin + compileSdk 37**. | ✅, but 9.4.1 is newer than any verified pairing. **Confirm in spike** that the tasks register. |
| **Robolectric 4.17**, `@Config(sdk = [34])` | Roborazzi does **not** pin Robolectric: the 1.75.0 POM lists `roborazzi-core`, `differ`, `espresso-core 3.5.1` and `kotlin-stdlib 2.0.21`, and no Robolectric, so the project's version wins. 1.46.1 fixed screenshot sizing for **Robolectric 4.15+** (`setQualifiers` semantics). **compose-samples runs Robolectric 4.17 + `@Config(sdk = [34])` + `GraphicsMode.NATIVE`** (Jetcaster wear `NavigationTest.kt` L30–31) with Roborazzi 1.74.0 on the test classpath. Native graphics needs SDK ≥ 26, with ≥ 28 recommended (`faq.md` L99–106). | ✅ |
| **Java 17** (CI `ci.yml` L56; `docs/TESTING.md` L50–54: SDK 35+ `android-all` needs Java 21) | Roborazzi is built with a JDK 17 toolchain and targets Java 11 bytecode (`gradle/libs.versions.toml`: `javaToolchain = "17"`, `javaTarget = "11"`). compose-samples CI runs JDK 17. The Java 21 constraint belongs to Robolectric's SDK 35+ jars and is avoided by the SDK 34 pin that Skein already uses. | ✅ |
| **Kotlin 2.4.20** | POM declares `kotlin-stdlib 2.0.21` as a *minimum* ("pinned to the minimum supported Kotlin version … on newer Kotlin, Gradle's conflict resolution picks the consumer's higher version", libs.versions.toml comment) | ✅ |
| **compose-bom 2026.09.00** (`ui`/`ui-test` 1.12.1) | Roborazzi compiles against Compose 1.8.3 and uses the consumer's Compose at runtime. compose-samples uses the **same BOM** with Roborazzi 1.74.0. | ✅ |
| **Configuration cache on** (`gradle.properties`) | 1.60.0 "Add integrity check for configuration cache"; remote-cache friendly | ✅ |
| **Gradle dependency verification** | New artifacts (plugin, `roborazzi*`, `differ`, ComposablePreviewScanner, ATF) must be added to `gradle/verification-metadata.xml` | ⚠️ spike work, not an incompatibility |
| `testOptions.unitTests.isIncludeAndroidResources = true` | Required for resources and fonts in RNG. `:feature:shell` already sets it; other modules need it. | ⚠️ per-module |
| Skein font (IBM Plex Mono, `feature/shell/src/main/res/font/*.ttf`) | RNG renders `res/font` resources. System text uses the Roboto bundled in `android-all`. | ✅ expected; confirm in spike. It is visible in the first `ThemeGallery` capture. |

**Items the spike must confirm:**
1. AGP 9.4.1 task registration.
2. The first capture on the Mac vs `ubuntu-latest` (quantify the OS diff, §7).
3. The heap needed for 2152 × 2076 px captures (the FAQ suggests `maxHeapSize = "4096m"` if out-of-memory errors appear, `faq.md` L108–122).

---

## 5. Recommended device-qualifier matrix for Skein

Measured and computed values come from `ANDROID_ADAPTIVE_SAMPLES.md` §1. Strings follow Roborazzi's generated format, with orientation inserted in its Android qualifier position (after `round`, before the UI-mode type `any`). **Density is part of every string**, because the same Fold changes size class with Display size.

### 5.1 Devices

| Key (folder name) | Qualifier string | Window (dp) | Width / height class | Why it's in the matrix |
| --- | --- | --- | --- | --- |
| `phone-narrow` | `w360dp-h640dp-normal-long-notround-any-xhdpi-keyshidden-nonav` (= `RobolectricDeviceQualifiers.SmallPhone`) | 360 × 640 | Compact / Medium | worst-case phone width and height |
| `fold-outer` | `w443dp-h994dp-normal-long-notround-port-any-390dpi-keyshidden-nonav` | 443 × 994 | Compact / Expanded | **closed Fold at stock density**. §22 primary surface |
| `fold-outer-land` | `w994dp-h443dp-normal-long-notround-land-any-390dpi-keyshidden-nonav` (or `fold-outer` + `RuntimeEnvironment.setQualifiers("+land")`) | 994 × 443 | Expanded / **Compact** | closed Fold in landscape. Must stay single-pane (the height gate) |
| `fold-outer-330` | `w524dp-h1175dp-large-long-notround-port-any-330dpi-keyshidden-nonav` | 524 × 1175 | Compact / Expanded | owner's Display size, **if it applies to the outer panel** (unverified; measure per `ANDROID_ADAPTIVE_SAMPLES.md` §1.4, then keep or drop) |
| `fold-inner` | `w852dp-h883dp-large-notlong-notround-port-any-390dpi-keyshidden-nonav` (= `Pixel9ProFold` + `port`) | 852 × 883 | **Expanded** (by 12 dp) / Medium | open Fold at stock density. The *tight* two-pane case |
| `fold-inner-land` | `w883dp-h852dp-large-notlong-notround-land-any-390dpi-keyshidden-nonav` | 883 × 852 | Expanded / Medium | stock landscape; tabletop posture happens in this orientation |
| `fold-inner-330` | `w1007dp-h1043dp-xlarge-notlong-notround-port-any-330dpi-keyshidden-nonav` | 1007 × 1043 | Expanded / Expanded | **owner's measured** override, portrait |
| `fold-inner-330-land` | `w1043dp-h1007dp-xlarge-notlong-notround-land-any-330dpi-keyshidden-nonav` | 1043 × 1007 | Expanded / Expanded | **owner's measured daily configuration** (`sw1007dp w1043dp h1007dp 330dpi xlrg land`) |
| `fold-inner-medium` | `w791dp-h820dp-large-notlong-notround-port-any-420dpi-keyshidden-nonav` | 791 × 820 | **Medium** / Medium | canary for a larger Display size, free-form windows and tablets in portrait. It is the brief's original assumption, kept as a regression guard. |
| `tablet` | `w1280dp-h800dp-xlarge-notlong-notround-land-any-xhdpi-keyshidden-nonav` (= `MediumTablet` + `land`) | 1280 × 800 | **Large** / Medium | the only tier where three panes (§21) render |
| `desktop-window` | `w1366dp-h768dp-xlarge-long-notround-any-mdpi-keyshidden-nonav` (= `SmallDesktop`) | 1366 × 768 | Large / Medium | desktop/external-display windows (keyboard, pointer; §41) |

Modifiers applied on top:

| Axis | Values | How |
| --- | --- | --- |
| Theme | light, dark | light = default (`notnight`); dark = `RuntimeEnvironment.setQualifiers("+night")`, or `RoborazziComposeOptions { uiMode(UI_MODE_NIGHT_YES) }`, or `DeviceConfigurationOverride.DarkMode(true)` (compose `ui-test` 1.12.1 `DeviceConfigurationOverride.android.kt` L160). Skein's `SkeinThemeMode` must honour the system mode in tests. |
| Font scale | **1.0, 1.3, 2.0** | `RoborazziComposeOptions { fontScale(1.3f) }` (calls Robolectric `RuntimeEnvironment.setFontScale`), or `DeviceConfigurationOverride.FontScale(1.3f)` (L82). SDK 34 applies Android 14's **non-linear** font scaling above 1.0, as on the phone. |
| Keyboard visible (§37 Layer B) | IME shown / hidden | `DeviceConfigurationOverride.WindowInsets(WindowInsetsCompat.Builder().setInsets(Type.ime(), Insets.of(0, 0, 0, imePx)).build())` (L406). Robolectric has no real IME, so this is the only way to screenshot "keyboard up". Use ≈ 45 % of the height in portrait. |
| System bars | status and navigation insets | the same `WindowInsets` override (`statusBars`, `navigationBars`, `displayCutout`). Robolectric renders **no** insets by default, so edge-to-edge bugs such as `skein-1vfg` are invisible without it. |
| Posture | flat / tabletop / book | inject `WindowAdaptiveInfo(windowSizeClass, Posture(isTabletop = …, hingeList = …))` into the shell (NiA pattern). There is no Robolectric qualifier for folding features. |

### 5.2 Pruned matrix (to keep baselines reviewable)

The full cross product (11 devices × 2 themes × 3 font scales × ~20 states) would be over 1,300 images. Use tiers instead:

| Tier | Devices | Theme × font | States |
| --- | --- | --- | --- |
| **T1: every surface state** | `fold-outer`, `fold-inner`, `fold-inner-330-land`, `phone-narrow` | light × 1.0 | all §37 Layer A states (empty chat, populated, generating, completed, activity expanded/collapsed, drawer, knowledge list/detail, note editor, graph, context inspector, model picker, settings, delete chat/note dialogs, errors, empties) |
| **T2: dark** | same 4 | dark × 1.0 | key states only: chat populated, chat generating, knowledge detail, delete dialog, settings |
| **T3: text scaling** | `phone-narrow`, `fold-outer` | light × 1.3 and 2.0 | chat populated, composer with a long draft, drawer, dialogs, settings, model picker with a very long model name |
| **T4: shell/layout** | `fold-outer-land`, `fold-inner-land`, `fold-inner-330`, `fold-inner-medium`, `tablet`, `desktop-window` (+ `fold-outer-330` once verified) | light × 1.0 | shell-level states: chat + inspector, knowledge + relationships, graph + node, drawer open → closed after resize |
| **T5: fold transitions (§25)** | pairs: `fold-inner-330-land` ↔ `fold-outer`; `fold-inner` ↔ `fold-outer` | light × 1.0 | before and after frames of Tests A, B, E, F, G, captured inside **one composition** by flipping `DeviceConfigurationOverride.WindowSize(DpSize)`, so state continuity is proven, not just drawn |

Estimate: T1 ≈ 18 states × 4 = 72, T2 ≈ 5 × 4 = 20, T3 ≈ 6 × 2 × 2 = 24, T4 ≈ 4 × 6 = 24, T5 ≈ 5 × 2 × 2 = 20. **About 160 images.**

### 5.3 Matching multipreview annotations (§37 Layer A/B in the IDE)

Keep the IDE and CI in sync by declaring the same devices as `@Preview` specs in a shared test-fixtures location (for example `:testing` or a new `:core:designsystem`):

```kotlin
@Preview(name = "fold-outer",          device = "spec:width=443dp,height=994dp,dpi=390")
@Preview(name = "fold-inner",          device = "spec:width=852dp,height=883dp,dpi=390")
@Preview(name = "fold-inner-330-land", device = "spec:width=1043dp,height=1007dp,dpi=330")
@Preview(name = "phone-narrow",        device = "spec:width=360dp,height=640dp,dpi=320")
annotation class SkeinFoldPreviews
```

Roborazzi's preview scanner maps `device` specs to Robolectric qualifiers (the preview-support options in §1). `Devices.PIXEL_9_PRO_FOLD` (`"id:pixel_9_pro_fold"`) exists but renders only the stock inner display. Use explicit specs so the outer display and the owner's 330 dpi are covered.

---

## 6. Recommended baseline folder layout

### 6.1 Layout

§39 proposes a device-first `ux-baselines/` tree. Gradle 9 must not see two modules' Roborazzi tasks sharing one output directory: the FAQ's `separateOutputDirs` note describes the `Cannot access input property 'roborazziImageInput'` race (`build_setup.md`, "Separate output directories per variant/target"). So the layout is **module first, then device**, under one repository-root folder:

```text
ux-baselines/                                   # git-tracked; reviewed in PRs (§39)
  README.md                                     # the §5 matrix, how to record/compare/verify, OS policy (§7)
  feature-chat/                                 # one dir per Gradle module = that module's roborazzi.outputDir
    fold-outer/
      chat__empty__light__fs100.png
      chat__generating__light__fs100.png
      chat__generating__dark__fs100.png
      chat__populated__light__fs200.png
      chat__keyboard__light__fs100.png
    fold-inner/
      chat__context-open__light__fs100.png
    fold-inner-330-land/
      chat__list-detail__light__fs100.png
    phone-narrow/
  feature-knowledge/
    fold-inner/knowledge__detail__light__fs100.png
  feature-graph/
    fold-inner/graph__node-selected__light__fs100.png
  feature-shell/                                # shell/layout tier (T4) and fold-transition pairs (T5)
    fold-outer-land/shell__chat__light__fs100.png
    transitions/
      testA__open-to-closed__before__fold-inner-330-land.png
      testA__open-to-closed__after__fold-outer.png
```

- **Name:** `<surface>__<state>__<theme>__fs<scale×100>.png`, with the device as the directory. `ls ux-baselines/*/fold-outer/` gives §39's device-first view across modules.
- **Wiring (spike):** in each module, `roborazzi { outputDir.set(rootProject.layout.projectDirectory.dir("ux-baselines/<module-dir>")) }`, plus `roborazzi.record.filePathStrategy=relativePathFromRoborazziContextOutputDirectory` in `gradle.properties`. Tests then call `captureRoboImage("fold-outer/chat__empty__light__fs100.png", …)`.
- **Tests stay in their module** (`feature/chat/src/test/...Screenshots.kt`) so they can reach `internal` stateless screens, as NiA does (`NOWINANDROID.md`).
- **Comparison output stays under `build/`** (`*_compare.png`, `build/reports/roborazzi/`). Only baselines are committed.

### 6.2 Task hygiene

- Keep screenshot tests in classes named `*ScreenshotTest` so they can be filtered (`faq.md` Q1).
- Set `roborazzi.test.verify` **only** in the dedicated CI job, not in `gradle.properties`. That keeps `./gradlew check` fast and OS-independent.
- Pin SDK once per module in `src/test/resources/robolectric.properties` (`sdk=34`) rather than repeating `@Config(sdk = [34])` on every class. This matches Skein's rule in `docs/TESTING.md`.

### 6.3 Size budget

- `resizeScale = 0.5`. NiA holds 136 committed PNGs in ≈ 4.9 MB at 0.5.
- Skein's ~160 images, several at 2152 × 2076 px source, should land around **15–25 MB**.
- No Git LFS: it complicates clones and reproducible-build checkouts for little gain at this size.
- Add a README note: "re-record only the images your change affects; never `cleanupOldScreenshots`".

---

## 7. Record/verify OS policy (the one real pitfall)

The facts: Skein's UX lab and agents run on **macOS** (the MacBook, darwin). Skein's CI runs **`ubuntu-latest`** (`.github/workflows/ci.yml`). Roborazzi's FAQ says rendering is not identical across OSes.

**Recommendation:**

1. **Canonical baselines are recorded on macOS** (the machine where humans and agents review UI) with `recordRoborazzi…`, and committed with DCO sign-off together with the UI change (§39 before and after in the PR).
2. **CI (ubuntu) runs `compareRoborazzi…` as a non-blocking job** and uploads `**/build/outputs/roborazzi/*_compare.png` plus the HTML report as an artifact (NiA `Build.yaml` L152–157).
3. Promote to **blocking** only after either (a) a `macos-latest` CI job runs `verifyRoborazzi…` (the Roborazzi docs' own store-screenshots example uses `runs-on: macos-latest`, `docs/topics/github_actions.md` L5–40), or (b) the spike measures macOS vs ubuntu diffs at 0 px for Skein's fonts. If (b) shows small anti-aliasing differences, a `changeThreshold` of about 0.001–0.01 on the CI job only is acceptable. Local stays at 0.
4. Never let CI rewrite baselines.

---

## 8. Known pitfalls and mitigations

| Pitfall | Mitigation |
| --- | --- |
| Cross-OS rendering drift | §7 |
| Robolectric SDK 35+ needs Java 21 | keep SDK 34 (`robolectric.properties`) |
| **No window insets and no IME in Robolectric** (edge-to-edge and keyboard bugs are invisible) | `DeviceConfigurationOverride.WindowInsets(...)` for status, nav, cutout and IME variants (§5.1) |
| Infinite animations (`CircularProgressIndicator` in `OpeningVault`, streaming cursor, the "Worked for 6.2s" ticker driven by `delay(THINKING_TICK_MS)` in `ChatViewModel`) time out or flicker | render stateless screens from fixtures with a *static* elapsed time; `composeTestRule.mainClock.autoAdvance = false` + `advanceTimeBy(...)`; for generated preview tests use `@RoboComposePreviewOptions` manual clock |
| Time and locale in the timeline and chat timestamps | `TimeZone.setDefault(UTC)` in `@Before`, an injected clock, locale via `RoborazziComposeOptions { locale("en-US") }` |
| `RuntimeEnvironment.setQualifiers` "does not trigger any action on extant activities" ([Robolectric docs](https://robolectric.org/device-configuration/)) | set qualifiers **before** `setContent` (NiA does); for live-resize tests use `DeviceConfigurationOverride.WindowSize` inside the composition instead |
| Posture has no qualifier | inject `WindowAdaptiveInfo` (parameter with default, NiA and Skein's existing `posture` parameter) |
| Out-of-memory on large captures | `testOptions.unitTests.all { maxHeapSize = "4096m" }` if needed (FAQ) |
| Shared output dir across tasks (Gradle 9) | per-module `outputDir` (§6.1); `separateOutputDirs` if multiple variants run in one invocation |
| Flaky async content (vault flows, `LaunchedEffect`) | render stateless screens from `UiState` fixtures; `@LooperMode(PAUSED)` + `waitForIdle()` |
| Baseline churn from unrelated changes (fonts, tokens) | tiered matrix (§5.2); design-token changes land in their own PR with a full re-record |
| `FLAG_SECURE`, vault and biometric in tests | never launch `MainActivity` for screenshots; the shell and screens are composables with fixtures |

---

## 9. Answers to the prompt's specific questions (§12, §39 and the task brief)

| Question | Answer |
| --- | --- |
| Local JVM screenshot tests? | Yes. Robolectric Native Graphics on the MacBook, via `recordRoborazzi*` / `compareRoborazzi*`. |
| Compose capture? | `captureRoboImage { … }` for composables; `onRoot()/onNodeWithTag().captureRoboImage()` inside Compose UI tests. |
| Baseline images, visual diffs? | Committed PNGs under `ux-baselines/<module>/<device>/`; `*_compare.png` + HTML report under `build/`. |
| Dark and light? | `+night` qualifier, `uiMode` option, or `DeviceConfigurationOverride.DarkMode`. |
| Phone, folded, unfolded and landscape dimensions? | Exact qualifier strings in §5.1, including the owner's measured `w1043dp-h1007dp … 330dpi` and a stock-density set. |
| Font scale? | 1.0 / 1.3 / 2.0 via `fontScale(...)` or `DeviceConfigurationOverride.FontScale`, non-linear at SDK 34. |
| UI-state fixtures? | Sealed `UiState` fixtures in `:testing`, rendered through stateless screens (NiA pattern). The same fixtures feed previews. |
| Compose Preview scanning? | `generateComposePreviewRobolectricTests` + ComposablePreviewScanner 0.9.1, filtered with `RoboPreviewInclude`, `robolectricConfig = mapOf("sdk" to "[34]")`. |
| CI verification? | Non-blocking ubuntu compare with artifacts now. Blocking verify once a macOS job exists or OS parity is proven (§7). |
| Compatible with Robolectric 4.17 + SDK 34 + Java 17 + AGP 9.x? | **Yes** (§4). AGP 9 support since 1.56.0; Robolectric not pinned by Roborazzi; compose-samples runs the same BOM, Robolectric 4.17 at SDK 34 and JDK 17 with Roborazzi 1.74.0 on AGP 9.3.1. Recommended version: **1.75.0**. |
| Known pitfalls? | §8 (OS drift, insets and IME absent, animations, qualifiers vs existing activities, heap, shared output dirs, image size). |

What specific problem in Skein can this project help us solve? Giving every Skein UI change machine-checked visual evidence across the Fold's real windows: outer 443 × 994, inner 852 × 883 at stock density, and the owner's measured 1043 × 1007 at 330 dpi, plus narrow-phone, Medium and tablet canaries, in light and dark and at font scale 1.0/1.3/2.0. The tests run on the MacBook in seconds, need no APK install, include an accessibility check, and produce before-and-after diffs of the §25 fold transitions within one composition.
