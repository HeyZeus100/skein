# Skein — Adaptive Layout Spec

**Bead:** `skein-xtov.15` · **Epic:** `skein-xtov` (UX overhaul) · **Authority:** `docs/research/SKEIN_UI_UX_OVERHAUL_PROMPT.md` §1–3, §8, §21–25, §40–41, §45–48, §51
**Implements:** `docs/ux/INFORMATION_ARCHITECTURE.md` (the keystone: 5 destinations; drawer on Compact, rail on Medium+; list–detail; secondary surfaces as back-stack entries; an ids-only back stack that survives recreation and vault lock; D8 Navigation 3 prototype gate). Where this spec refines or questions the IA, it says so in §13. Nothing diverges silently.
**Main inputs:** `docs/ux/research/ANDROID_ADAPTIVE_SAMPLES.md` (§1 geometry, §4.2–4.4, §5 strategy), `COMPOSE_SAMPLES.md`, `NOWINANDROID.md`, `docs/ux/ANDROID_SKILLS_ASSESSMENT.md` (§6.4 edge-to-edge, §6.5 navigation-event, §6.1 adaptive), `docs/ux/audit/AUDIT_SHELL.md` (§4–5, P0s), `DEVICE_BEFORE_PASS.md`, `AUDIT_CHAT_MODELS_SETTINGS.md` §4.10, `AUDIT_KNOWLEDGE.md` §12, `docs/design/LOCK_POLICY_INDEXING.md` §4.
**Sibling specs** (written in parallel; referenced by name, not specified here): `DESIGN_SYSTEM.md` (tokens, components, copy), `CHAT_UX_SPEC.md`, `KNOWLEDGE_UX_SPEC.md`, `OBJECT_LIFECYCLE_SPEC.md`, `UX_TEST_PLAN.md`, `MAC_UX_LAB_PLAN.md`.

**Aligned with what has already landed on `main`:**
- `docs/ux/UX_AUDIT.md` "Reconciliations": the outer display was measured while the device was closed, and `SendPipeline`'s partial-answer paths were read.
- The IA's §8a revisions: ✎ (not ⌕) in the Compact chat header; ⌕ only in Medium+ top bars; the date groups; the **Model sheet**; chat search on Compact through the palette.
- `CHAT_UX_SPEC.md`: §3 anatomy, §6 scroll, §11 landing, §12 history, §15.4 landing after delete, §16 Model sheet, §17 inspector and opening a source; its bead C1 is the session turn controller.
- `OBJECT_LIFECYCLE_SPEC.md`: §3.5 stale ids, §4.7 navigation after delete, the draft keys, LC-20.

**Status:** Proposed. Nothing here is implemented. The code references are to `main` at `5538b4f`.

---

## 0. The spec in one screen

| Question | Answer |
|---|---|
| What decides the layout? | One pure function, `skeinWindowLayout(WindowAdaptiveInfo, measured window DpSize)`. It never reads the device model or the density. Width breakpoints come only from `WindowSizeClass` and the pane count only from `calculatePaneScaffoldDirective`. Skein adds three things on top: a **height gate**, a **minimum-pane-width guard** and a **book-posture tie-breaker** (§2). |
| The decision in five lines | 1. **Nav:** a modal drawer if width < 600 dp **or** height < 600 dp. Otherwise an 80 dp rail, or a 240 dp expanded rail at ≥ 1600 dp. 2. **Panes:** Material's directive (1 · 1 · 2 · 3 by width class) with the split placed on a separating hinge. 3. **Height gate:** height < 600 dp → 1 pane. 4. **Guard:** drop panes until the detail is ≥ 360 dp and the side panes are ≥ 280 dp. The side pane is 320 dp if that leaves the detail ≥ 480 dp, otherwise 280 dp. 5. **Secondary surfaces** (inspector, Connections, node detail, an opened source) are **panes** when there are ≥ 2 panes. With one pane, the first three are a **bottom sheet** below 600 dp wide and a **360 dp side sheet** from 600 dp, and after a window shrinks they appear **collapsed to a peek bar**. An opened source is a full-screen route instead. |
| Fold outer | **Compact**: **~524 × 1175 dp at the owner's 330 dpi** (measured while closed), ~443 × 994 dp at stock. Drawer, one pane, sheets. In landscape it is a **short window** (1175 × 524 for the owner, 994 × 443 at stock): still a drawer and one pane, even though Material would give two. |
| Fold inner | **Expanded** at stock (852 × 883) and at the owner's 330 dpi (1006 × 1043). Rail, **two panes**: `Conversations │ Chat`, `Knowledge │ Item`, `Canvas │ Node`. Opening the inspector gives `Chat │ Context` (the list yields). It is never three columns. |
| `configChanges` | **Declare** `screenSize|smallestScreenSize|screenLayout|orientation` (required) and `keyboard|keyboardHidden|navigation` (recommended). **Do not add `density`.** Both panels run at the same density (390 physical, 330 override), so a fold never changes `densityDpi`. **State must survive recreation anyway** (§7.3, §7.6). |
| Where state lives | The Nav3 back stack holds **typed ids only** and is hoisted **above the vault gate**. It survives recreation, fold, process death and lock. Per-entry saveables hold numbers and ids only. Text, content and the **in-flight turn** live in **session-scoped owners**, never in the composition. Today a cancelled collector loses the partial answer (`SendPipeline.kt:241-294`). Those owners are cleared on lock. Drafts are also kept in an **encrypted draft row** (§7). |
| Navigation library | **Navigation 3** + `adaptive-navigation3` scene strategies, **only after the D8 prototype gate** (§8.9: ten measurable criteria). The fallback is a hand-rolled `SnapshotStateList<SkeinKey>` rendered by Material's pane scaffolds, using the same keys, rules and tests. It is **not** Navigation Compose 2.x. |
| Acceptance | Tests A–G at three levels: JVM (a `DeviceConfigurationOverride.WindowSize` flip in one composition, `StateRestorationTester`, `ActivityScenario.recreate`, a process-death restore), the emulator (Pixel 9 Pro Fold AVD + Espresso Device API), and the owner's Fold (a physical fold plus a read-only watcher that polls `cmd device_state print-state` and captures both displays). §9. |

---

## 1. Ground truth: the windows Skein must serve

Measured and computed in `ANDROID_ADAPTIVE_SAMPLES.md` §1 and `DEVICE_BEFORE_PASS.md`. dp = px ÷ (dpi ÷ 160). Width classes (V2): Compact < 600 ≤ Medium < 840 ≤ Expanded < 1200 ≤ Large < 1600 ≤ XL. Height classes: Compact < 480 ≤ Medium < 900 ≤ Expanded.

| Surface | Pixels | Density | Portrait dp | Landscape dp | Status |
|---|---|---|---|---|---|
| Outer (closed) | 1080 × 2424 | **330 (owner, measured while closed)** | **524 × 1175** | **1175 × 524** | **measured**: `wm size` 1080×2424 and `wm density` physical 390 / override 330, read with the device CLOSED (`UX_AUDIT.md` Reconciliations) |
| Outer (closed) | 1080 × 2424 | 390 stock | 443 × 994 | 994 × 443 | computed from measured px and density |
| Inner (open) | 2076 × 2152 | 390 stock | **852 × 883** | **883 × 852** | computed; matches Roborazzi `Pixel9ProFold` |
| Inner (open) | 2076 × 2152 | **330 (owner, measured)** | **1006 × 1043** | **1043 × 1006** | measured (`sw1007dp w1043dp h1007dp 330dpi`) |
| Inner at a larger Display size (~420 dpi) | 2076 × 2152 | ~420 | 791 × 820 | 820 × 791 | the only way the inner display becomes **Medium** |
| Hinge | vertical line at x ≈ 1038 px in natural portrait | — | ≈ 426 dp (390) / ≈ 503 dp (330) from the left | a horizontal line at the same offset from the top | from the Studio device catalog; postures 0–30 / 30–150 / 150–180 |

Consequences that shape everything below:

1. **The owner's primary canvas is the inner display in landscape (1043 × 1006)**, because of the landscape grip. Two panes fit easily: the chat is ≈ 619 dp wide. The owner's closed Fold is **524 dp** wide in portrait.
2. **The stock inner display is only 12 dp inside Expanded.** One Display-size notch larger makes it Medium. Layout must follow the measured window, and Medium must be a first-class mode (§2.6 rows 11–12).
3. **The closed Fold in landscape is Expanded-width with a Compact or Medium height.** The owner's case is 1175 × 524, which Material classes as **Medium** height, so none of Material's own compact-height rules apply to it. Material alone would give it two panes, and §22 forbids that. Only the Skein height gate (600 dp) catches it.
4. **Three panes never fit on the Fold.** At 1043 dp: rail 80 + list 320 + inspector 320 + two spacers 48 leaves a 275 dp chat, narrower than the closed phone.
5. **Both panels share one density** (390 physical, 330 override). A fold changes the window size, never `densityDpi`. That settles the manifest question in §7.5.

### 1.1 Measuring on the device

The outer measurement is settled (`UX_AUDIT.md` Reconciliations). Every remaining device measurement in this spec (Tests A–G, gate criterion G8) needs a **physical** fold with Skein in the foreground. `adb shell cmd device_state state 0` cannot stand in for it: the platform treats a software state change like a power-button sleep, so the outer screen comes up on the keyguard and the vault locks (`DEVICE_BEFORE_PASS.md`). Read-only `cmd device_state print-state` is fine, and §9.4 uses it.

### 1.2 Correction to the screenshot matrix

The Roborazzi spike's `fold-outer` qualifier `w411dp-h923dp-port-420dpi` assumed 420 dpi and is wrong (`ROBORAZZI_SPIKE.md` §5; `UX_AUDIT.md` Reconciliations). §10.1 replaces it with the owner's **`w524dp-h1175dp-port-330dpi`**, which matches `fold-inner` at 330, and a stock variant **`w443dp-h994dp-port-390dpi`**, which matches `fold-inner-stock`.

---

## 2. Window model

### 2.1 Inputs, and what is never an input

| Input | Source | Why |
|---|---|---|
| Width class | `currentWindowAdaptiveInfoV2().windowSizeClass`, read with `isWidthAtLeastBreakpoint(WIDTH_DP_MEDIUM_LOWER_BOUND / _EXPANDED_ / _LARGE_ / _EXTRA_LARGE_)` | Material maintains the breakpoints, including Large and XL |
| Pane count | `calculatePaneScaffoldDirective(info, verticalHingePolicy = HingePolicy.AvoidSeparating)` | Material's 1 · 1 · 2 · 3 table, with the split placed on a separating hinge |
| **Measured window size** | `LocalWindowInfo.current.containerDpSize` (the same source `currentWindowAdaptiveInfoV2` reads, and the one `DeviceConfigurationOverride.WindowSize` overrides in tests) | `WindowSizeClass.minHeightDp` holds only the bucket's lower bound (0 / 480 / 900), so it cannot express a 600 dp gate or pane widths |
| Posture and hinges | `info.windowPosture`: `isTabletop`, `hingeList` (`bounds`, `isVertical`, `isSeparating`, `isOccluding`) | Replaces the hand-rolled `layout/FoldPosture.kt` |

**Never inputs:** `Build.MODEL`, `densityDpi`, `LocalConfiguration.screenWidthDp` (it lags a live resize), "is a foldable", "is a tablet", or orientation on its own. `WindowAdaptiveInfo` and the size are **parameters with defaults**, so tests, previews and screenshots can inject them. This is NiA's pattern, and today's `SkeinApp(windowSizeClass, posture)` already does it (`SkeinApp.kt:210-211`).

### 2.2 Vocabulary used in this document

| Term | Meaning |
|---|---|
| **Short window** | Measured height < 600 dp. This is not Material's "Compact height" (< 480). IA §3.4 calls it "Compact height (any window < 600 dp tall)". |
| **Mode** | The pair (navigation container, pane count) that the function returns. **Phone** = drawer + 1 pane. **Single** = rail + 1 pane. **Dual** = rail + 2 panes. **Triple** = rail + up to 3 panes. |
| **Side pane** | The list pane, or the extra/supporting pane: anything that is not the detail. There is one width for both, because Material's directive has one `defaultPanePreferredWidth`. |
| **Presentation** | How a secondary surface appears: `Pane`, `BottomSheet`, `SideSheet`, `FullScreen`, `CenteredPanel`, `AnchoredPanel`, `Dialog`. |
| **Peek** | A 48–56 dp bar docked at the bottom of the underlying pane, labelled with the surface ("Context · 2 notes · 3 sources ▴"). No scrim. The underlying pane stays fully interactive. Tapping it expands the sheet; Back or swiping it down pops the entry. |

### 2.3 The decision function

This is reference pseudocode. The Wave-3 implementer owns the code, but the behaviour of every branch below is normative.

```kotlin
data class SkeinWindowLayout(
    val size: DpSize,
    val widthClass: WindowSizeClass,          // as reported; never re-derived
    val isShort: Boolean,                     // size.height < TWO_PANE_MIN_HEIGHT
    val nav: NavContainer,                    // Drawer | Rail | ExpandedRail
    val maxPanes: Int,                        // 1..3 horizontal content panes
    val sidePaneWidth: Dp,                    // list and extra/supporting panes
    val posture: SkeinPosture,                // Flat | Book(hinge) | Tabletop(hinge)
    val directive: PaneScaffoldDirective,     // handed to the scene strategies
)

fun skeinWindowLayout(info: WindowAdaptiveInfo, size: DpSize): SkeinWindowLayout {
    val wsc = info.windowSizeClass
    val atLeastMedium = wsc.isWidthAtLeastBreakpoint(WIDTH_DP_MEDIUM_LOWER_BOUND)          // 600
    val atLeastXl     = wsc.isWidthAtLeastBreakpoint(WIDTH_DP_EXTRA_LARGE_LOWER_BOUND)     // 1600
    val isShort       = size.height < TWO_PANE_MIN_HEIGHT                                  // 600 dp, Skein gate
    val posture       = SkeinPosture.from(info.windowPosture)

    // 1. Navigation container: one per window, never two (IA §3.4, prompt §2 rule 12).
    val nav = when {
        !atLeastMedium || isShort -> NavContainer.Drawer
        atLeastXl                 -> NavContainer.ExpandedRail                             // 240 dp
        else                      -> NavContainer.Rail                                     // 80 dp
    }

    // 2. Material directive: 1 · 1 · 2 · 3 by width class; split placed on a separating hinge.
    val m3 = calculatePaneScaffoldDirective(info, verticalHingePolicy = HingePolicy.AvoidSeparating)
    var panes = m3.maxHorizontalPartitions

    // 3. Book posture on a Medium window: two pages, split at the fold (Reply precedent, COMPOSE_SAMPLES.md).
    if (posture is SkeinPosture.Book && atLeastMedium && panes == 1) panes = 2

    // 4. Skein height gate (§22: the closed Fold in landscape is a phone).
    if (isShort) panes = 1

    // 5. Tabletop (P2, behind bead AL-20): 1 horizontal pane, content above / controls below the hinge.
    if (TABLETOP_LAYOUTS_ENABLED && posture is SkeinPosture.Tabletop) panes = 1

    // 6. Minimum-pane-width guard, evaluated on real widths (after a hinge split, if any).
    val content = size.width - nav.width
    while (panes > 1 && !fits(panes, content, posture)) panes--

    // 7. Side pane width: 320 if the detail keeps its comfortable width, else 280.
    val side = if (panes == 1) SIDE_PANE_PREFERRED
               else if (content - (panes - 1) * SPACER - (panes - 1) * SIDE_PANE_PREFERRED >= DETAIL_COMFORT) SIDE_PANE_PREFERRED
               else SIDE_PANE_MIN

    return SkeinWindowLayout(size, wsc, isShort, nav, panes, side, posture,
        directive = m3.copy(maxHorizontalPartitions = panes, defaultPanePreferredWidth = side))
}

private fun fits(panes: Int, content: Dp, posture: SkeinPosture): Boolean = when (panes) {
    2 -> if (posture is SkeinPosture.Book) posture.startSide(content) >= SIDE_PANE_MIN && posture.endSide(content) >= DETAIL_MIN
         else content - SPACER - SIDE_PANE_MIN >= DETAIL_MIN
    3 -> content - 2 * SPACER - 2 * SIDE_PANE_MIN >= DETAIL_MIN
    else -> true
}

// Secondary surfaces (IA §3.3). Pane surfaces are back-stack keys; the others are transient UI state.
fun SkeinWindowLayout.presentationOf(s: Surface): Presentation = when (s) {
    ContextInspector, Connections, NodeDetail ->
        when { maxPanes >= 2 -> Pane; size.width < 600.dp -> BottomSheet; else -> SideSheet }
    OpenedSource,                                  // a cited note/file opened from a chat (CHAT_UX_SPEC.md §17.6)
    ModelDetails, SettingsCategory -> if (maxPanes >= 2) Pane else FullScreen      // a route on one pane
    ModelSheet        -> if (size.width < 600.dp) BottomSheet else AnchoredPanel
    AttachPicker      -> if (size.width < 600.dp || isShort) FullScreen else CenteredPanel
    CommandPalette    -> if (size.width < 600.dp) FullScreen else CenteredPanel
    RenameDialog, ConfirmDialog -> Dialog
}

// Tabletop placement: never across a separating hinge (§5).
fun SkeinWindowLayout.partitionOf(s: Surface): Partition = when {
    posture !is SkeinPosture.Tabletop -> Partition.Any
    s in setOf(CommandPalette, RenameDialog, AttachPicker, ModelSheet, OpenedSource) -> Partition.Top  // text entry or reading: the IME takes the bottom half
    else -> Partition.Bottom                                                                           // sheets, confirmations: the touch half
}
```

### 2.4 Constants (Skein-owned numbers; everything else comes from the adaptive APIs)

| Constant | Value | Rationale | Guarded by |
|---|---|---|---|
| `TWO_PANE_MIN_HEIGHT` | **600 dp** | §22: the closed Fold is "one dominant workspace". In landscape the IME covers about 45 % of the window, so a 443–524 dp-tall window cannot host two panes and a composer. It excludes every phone in landscape and keeps every Fold inner configuration (height ≥ 780 at any Display size). | truth-table tests, rows 2, 5, 6, 18, 21, 22, 28 |
| Rail width | **80 dp** (M3 `NavigationRail`) | `WideNavigationRailCollapsed` is 96 dp. The 16 dp saved matter on the 852 dp stock inner display. | row 7 |
| Expanded-rail width | **240 dp** (XL only) | At Large, an expanded rail plus three panes leaves the detail under 360 dp (1200 − 240 − 48 − 560 = 352). | rows 24, 25, 27 |
| `SPACER` | **24 dp** (M3 default `horizontalPartitionSpacerSize`) | It hosts the pane-expansion drag handle. | — |
| `SIDE_PANE_PREFERRED` | **320 dp** | IA §5.2. Material's default of 360 would leave the stock 852 dp inner display a 388 dp chat. | rows 9, 10 |
| `SIDE_PANE_MIN` | **280 dp** | Keeps a title plus a date legible at font scale 1.0. Titles ellipsize. | rows 7, 8 |
| `DETAIL_MIN` | **360 dp** | Chat bubbles plus the composer (`ANDROID_ADAPTIVE_SAMPLES.md` §5.2). This is what the guard enforces. | rows 13, 14, 19 |
| `DETAIL_COMFORT` | **480 dp** | Chooses 320 or 280 for the side pane. | rows 7–10 |
| `READABLE_MAX` | **720 dp** | Transcript and composer column, note text column, settings and model details. About 70–80 characters of body text. | screenshots |
| Bottom-sheet max width | **640 dp** (M3 default) | — | — |
| Side-sheet width | **360 dp** | M3 range is 256–400. | — |
| Palette width | **≤ 640 dp** | IA §3.3. | — |
| Drawer width | **min(360 dp, W − 56 dp)** | M3 maximum, while always leaving a tappable scrim. | row 1 |

These values become layout tokens in `DESIGN_SYSTEM.md`. This spec owns their rationale and their tests.

### 2.5 Presentation matrix (secondary surfaces × mode)

| Surface (IA §3.3) | Back-stack key? | Phone (< 600 wide) | Short and ≥ 600 wide | Single (Medium, not short) | Dual (Expanded) | Triple (Large/XL) | Tabletop |
|---|---|---|---|---|---|---|---|
| Context inspector | yes, `ChatContextKey` | bottom sheet (half height; drags to full) | side sheet 360 | side sheet 360 | **extra pane**; the list yields | third pane | bottom partition |
| Opened source (the cited note or file, at the passage; `CHAT_UX_SPEC.md` §17.6) | yes, `ChatSourceKey` | **full screen** | full screen | full screen | **extra pane beside the chat**; the inspector yields | third pane | top partition |
| Connections | yes, `ConnectionsKey` | bottom sheet | side sheet | side sheet | extra pane; the list yields | third pane | bottom partition |
| Graph node detail | yes, `GraphNodeKey` | bottom sheet | side sheet | side sheet | supporting pane | supporting pane | bottom partition (Reflow) |
| Model details | yes, `ModelDetailsKey` | route | route | route | detail pane (in Models) | detail pane | — |
| Settings category | yes, `SettingsCategoryKey` | route | route | route | detail pane | detail pane | — |
| Model sheet (IA §3.3, `CHAT_UX_SPEC.md` §16; a persona row only once personas exist) | no (entry-retained UI state) | modal bottom sheet | anchored panel 360 | anchored panel | anchored panel under the header subtitle | anchored panel | top partition |
| Attach knowledge picker | no | full screen | full screen | centred panel 560 | centred panel 560 × ≤ 80 % height | centred panel | top partition |
| Command palette | no (shell-retained) | full screen | centred, full height, 640 | centred 640, top-anchored | centred 640, top-anchored | centred 640 | top partition |
| Rename (text) | no | dialog | dialog | dialog | dialog | dialog | top partition |
| Delete confirmation | no | dialog | dialog | dialog | dialog | dialog | bottom partition |
| Navigation drawer | no | modal drawer | modal drawer | — | — | — | — |

**Size-change rule.** When a window change turns an open back-stack surface from `Pane` into a sheet, the sheet is **collapsed to its peek**. The same applies to a sheet restored after recreation or process death. When the user opens it explicitly, it opens half-expanded (bottom sheet) or fully (side sheet). An opened source is never a sheet: it is a full-screen route on one pane, so it has no peek. §7.4 has the full transition contract.

### 2.6 Truth table

"Side ∣ detail" gives dp widths when there are two or more panes. With one pane it gives the content column (capped at `READABLE_MAX` for chat and notes). "Extra" is where the inspector, source, connections and node detail go.

| # | Window (example) | W × H dp | Width / height class | Short | Posture | Nav | Panes | Side ∣ detail (∣ extra) | Extra | Palette / attach | Note |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | Phone portrait | 360 × 800 | Compact / Medium | no | — | drawer (304) | 1 | 360 | bottom sheet | full / full | narrow-phone baseline |
| 2 | Phone landscape | 800 × 360 | Medium / Compact | **yes** | — | drawer | 1 | column 720 | side sheet | centred (full height) / full | M3 would pick a rail |
| 3 | Fold outer portrait, stock | 443 × 994 | Compact / Expanded | no | — | drawer (360) | 1 | 443 | bottom sheet | full / full | today: glyph-rail landing (P0-01) |
| 4 | **Fold outer portrait @330 (owner, measured)** | **524 × 1175** | Compact / Expanded | no | — | drawer (360) | 1 | 524 | bottom sheet | full / full | the owner's closed phone |
| 5 | Fold outer landscape, stock | 994 × 443 | Expanded / Compact | **yes** | — | drawer | **1** (M3: 2) | column 720 | side sheet | centred / full | today: dual pane (§22 violation) |
| 6 | **Fold outer landscape @330 (owner)** | **1175 × 524** | Expanded / **Medium** | **yes** | — | drawer | **1** (M3: 2, and M3's compact-height rules miss it) | column 720 | side sheet | centred / full | only the 600 dp gate catches it (524 < 600) |
| 7 | **Fold inner portrait, stock** | **852 × 883** | Expanded / Medium | no | flat | rail 80 | 2 | **280 ∣ 468** | extra 280 (chat 468) | centred / centred | 12 dp inside Expanded |
| 8 | Fold inner landscape, stock | 883 × 852 | Expanded / Medium | no | flat | rail | 2 | 280 ∣ 499 | extra 280 | centred / centred | — |
| 9 | **Fold inner portrait @330 (owner)** | **1006 × 1043** | Expanded / Expanded | no | flat | rail | 2 | **320 ∣ 582** | extra 320 (chat 582) | centred / centred | — |
| 10 | **Fold inner landscape @330 (owner's grip)** | **1043 × 1006** | Expanded / Expanded | no | flat | rail | 2 | **320 ∣ 619** | extra 320 (chat 619) | centred / centred | primary design target |
| 11 | Inner, larger Display size (~420 dpi), portrait | 791 × 820 | Medium / Medium | no | flat | rail | 1 (Knowledge: 2, **prototype**, 280 ∣ 407) | column 711 | side sheet | centred / centred | `ANDROID_ADAPTIVE_SAMPLES.md` §4.4 |
| 12 | Same, landscape | 820 × 791 | Medium / Medium | no | flat | rail | 1 (Knowledge prototype: 280 ∣ 436) | column 720 | side sheet | centred / centred | — |
| 13 | Inner portrait @330, **book** (half-open) | 1006 × 1043 | Expanded / Expanded | no | book, hinge x ≈ 503 | rail | 2, split **at the fold** | ≈ 411 ∣ ≈ 491 | extra pane | centred / centred | stock: ≈ 334 ∣ ≈ 414 |
| 14 | Medium inner, **book** | 791 × 820 | Medium / Medium | no | book, hinge x ≈ 395 | rail | **2** (tie-breaker; the guard passes) | ≈ 303 ∣ ≈ 384 | extra pane | centred / centred | — |
| 15 | Inner landscape @330, **tabletop** | 1043 × 1006 | Expanded / Expanded | no | tabletop, hinge y ≈ 503 | rail | Wave 3: **2**, flat layout with hinge guards. P2 (AL-20): 1 pane × (top content / bottom controls) | 320 ∣ 619 | extra pane (Wave 3); bottom partition (P2) | top partition | the drag handle is hidden (it would sit on the hinge) |
| 16 | Inner split-screen half, side by side @330 | ≈ 517 × 1006 | Compact / Expanded | no | — | drawer | 1 | 517 | bottom sheet | full / full | — |
| 17 | Same @390 | ≈ 437 × 852 | Compact / Medium | no | — | drawer | 1 | 437 | bottom sheet | full / full | — |
| 18 | Inner split, stacked (if the launcher splits top and bottom) @330 | ≈ 1006 × 515 | Expanded / Medium | **yes** | — | drawer | **1** | column 720 | side sheet | centred / full | M3 would give 2 panes and a rail |
| 19 | Inner split 2/3, landscape @330 | ≈ 690 × 1006 | Medium / Expanded | no | — | rail | 1 (Knowledge prototype **refused by the guard**: 690 − 80 − 24 − 280 = 306 < 360) | column 610 | side sheet | centred / centred | — |
| 20 | Inner split 1/3 | ≈ 340 × 1006 | Compact / Expanded | no | — | drawer (284) | 1 | 340 | bottom sheet | full / full | below `DETAIL_MIN`; one pane is all that is possible, and nothing may clip (§45) |
| 21 | Outer split (stacked) | ≈ 443 × 490 | Compact / Medium | yes | — | drawer | 1 | 443 | bottom sheet (≤ 90 % height) | full / full | — |
| 22 | Free-form, small | 700 × 500 | Medium / Medium | **yes** | — | drawer | 1 | column 700 | side sheet | centred / full | — |
| 23 | Free-form, medium | 1000 × 700 | Expanded / Medium | no | — | rail | 2 | 320 ∣ 576 | extra 320 | centred / centred | — |
| 24 | **Large**: tablet landscape, desktop | 1280 × 800 | Large / Medium | no | — | rail 80 | **3** | 320 ∣ 512 ∣ 320 (without an extra: 320 ∣ 856, column 720) | third pane | centred / centred | IA §3.4 Large |
| 25 | Large, lower edge | 1200 × 800 | Large / Medium | no | — | rail | 3 | 280 ∣ 512 ∣ 280 | third pane | centred / centred | an expanded rail here would break the guard |
| 26 | Tablet portrait | 800 × 1280 | Medium / Expanded | no | — | rail | 1 (Knowledge prototype 2) | column 720 | side sheet | centred / centred | — |
| 27 | Desktop **XL** | 1920 × 1080 | XL / Expanded | no | — | **expanded rail 240** | 3 | 320 ∣ 992 (column 720) ∣ 320 | third pane | centred / centred | — |
| 28 | Short wide desktop window | 1400 × 560 | Large / Medium | **yes** | — | drawer | 1 | column 720 | side sheet | centred / full | — |

Each row becomes one JVM unit test of `skeinWindowLayout()` (bead AL-04) and, for the device rows, one Roborazzi device (§10.1).

### 2.7 Live resizes

- With `configChanges` declared (§7.5), fold, unfold, rotation, split-screen and free-form drag are **recompositions**. A Display-size (density) change still recreates the Activity, and the state in §7.3 survives it. `skeinWindowLayout()` is recomputed every frame from `LocalWindowInfo`, so no hysteresis is needed. A drag-resize that crosses 600 or 840 dp repeatedly changes the *scene* and never the *back stack*, so nothing is lost (§7).
- **Transitions:** a cross-fade of ≤ 200 ms between scenes, with no shared-element choreography (`ANDROID_ADAPTIVE_SAMPLES.md` §3 "Patterns not worth adopting"). An animator duration scale of 0 (reduced motion) means no animation.
- **No loading flash on reflow.** Retained holders keep their data, so a list never shows "Nothing here yet" during a fold (today's P1-11).

---

## 3. Navigation containers

### 3.1 One container per mode

| Mode | Container | Opened by | Never |
|---|---|---|---|
| Phone (width < 600, or short) | **Modal navigation drawer** | ☰ in the top app bar; Ctrl+K for search | a bottom bar (it would fight the composer and the IME for the bottom edge; IA §3.4); a rail |
| Single, Dual, Triple (Large) | **Navigation rail, 80 dp**, labels always shown | always visible | a hamburger or drawer as well |
| Triple (XL) | **Expanded rail, 240 dp** (icons + labels side by side) | always visible | a drawer as well |

### 3.2 `NavigationSuiteScaffold` usage

Use Material's navigation suite so that one component owns the rail at every size, but choose its type from Skein's table instead of the default `navigationSuiteType()` (Material would give a bottom bar on Compact). Wrap it in a `ModalNavigationDrawer` for the Phone mode. This is Reply's structure (`COMPOSE_SAMPLES.md`).

```kotlin
ModalNavigationDrawer(
    drawerState = drawerState,                         // size-aware: §3.3
    gesturesEnabled = layout.nav == Drawer && drawerState.isOpen,   // swipe closes; ☰ opens (no edge swipe: it collides with system Back)
    drawerContent = { SkeinDrawerSheet(drawerState, …) },           // §3.3 contents
) {
    NavigationSuiteScaffold(
        navigationSuiteType = when (layout.nav) {
            Drawer -> NavigationSuiteType.None
            Rail -> NavigationSuiteType.NavigationRail
            ExpandedRail -> NavigationSuiteType.WideNavigationRailExpanded
        },
        primaryActionContent = { NewChatAction() },     // ✎ at the top of the rail
        navigationItems = { SkeinDestinations.forEach { NavigationSuiteItem(…) } },
        navigationItemVerticalArrangement = Arrangement.Top,   // Settings is pinned separately at the bottom
    ) { SkeinNavDisplay(…) }
}
```

The type, parameter and overload names above are those of navigation-suite 1.4.0 as recorded in `ANDROID_ADAPTIVE_SAMPLES.md` §3. The AL-07 bead confirms them. If the suite cannot place Settings at the bottom, use the suite's `NavigationRail` with a spacer; do not hand-roll a rail.

### 3.3 Drawer (Phone mode)

**Contents, top to bottom** (IA §3.4):

```text
┌──────────────────────────────┐
│ [✎ New chat]                 │  primary button, full width
│ ⌕ Search or run a command    │  opens the palette (also chat search on Compact); closes the drawer
│                              │
│ 💬 Chat                      │  ▌selected = current destination
│ 📚 Knowledge                 │
│ ✦ Graph                      │  only once the Graph destination is wired (IA §3.2)
│ ▦ Models                     │
│ ⚙ Settings                   │
│ ──────────────────────────── │
│ Chats                        │
│  Today                       │
│  ▌Skein UX redesign        ⋮ │  ⋮ / long-press: Rename · Delete; ▌ = the open chat
│   RAG architecture         ⋮ │
│  Yesterday                   │
│   Mycology research        ⋮ │
│  Previous 7 days · Previous  │
│  30 days · August · …        │  lazy, paged; no "show more" wall
└──────────────────────────────┘
 width = min(360 dp, window width − 56 dp); the scrim stays tappable
```

Groups, row anatomy, the answering indicator and the empty state belong to `CHAT_UX_SPEC.md` §12. On a Phone window this section **is** the conversation history (`CHAT_UX_SPEC.md` §12.1). There is no separate full-screen list (§8.2).

**Behaviour:**

- **The drawer closes when** the user picks a destination, New chat, a chat row or the search field (the palette opens after it closes), taps the scrim, swipes it closed, or presses Back or Esc.
- **Exception, for Rename and Delete launched from a history row:** the drawer **stays open** under the dialog and shows the result in place: the row renamed, or removed with focus moved to its neighbour (`CHAT_UX_SPEC.md` §15.4, `OBJECT_LIFECYCLE_SPEC.md` §4.7).
- **Size-aware drawer state (Test G).** The drawer state is composition state, not a saveable. When the container changes away from `Drawer` (unfold, split exit, resize), the drawer is **snapped to Closed**. When the container becomes `Drawer` again (fold), it starts **Closed**. It is never restored open after a recreation, a process death or an unlock. This is JetNews' `rememberSizeAwareDrawerState`, plus an explicit snap-to-closed so that a remembered `Open` cannot resurface on refold. Today `NavState.drawerOpen` is saveable and reopens over the new layout (P2-05).
- Chat row: tapping it performs a **go to** (§8.3): switch to Chat, open the chat, close the drawer. ⋮ holds Rename and Delete; delete semantics are in `OBJECT_LIFECYCLE_SPEC.md`.

### 3.4 Rail (Single, Dual, Triple)

```text
┌────┐
│ ✎  │  New chat (primary action, 56 dp, label "New chat" for TalkBack and tooltip)
│    │
│ 💬 │  Chat
│Chat│
│ 📚 │  Knowledge          labels are always visible (§40: no glyph-only navigation)
│ ✦  │  Graph              (only once it is wired)
│ ▦  │  Models
│    │
│ ⚙  │  Settings, pinned to the bottom
└────┘
 80 dp; items are 56 dp tall; the selected indicator follows the current destination
```

- There is **no chat history in the rail.** On Dual and Triple windows it lives in the Conversations list pane (IA §3.4). On a Single window it is the Chat root screen (§8.2).
- There is no ⌕ in the rail. On Medium+ windows the palette is reached from ⌕ in the top app bars (IA §3.3 as revised) and from Ctrl+K.
- **Keyboard:** the rail is a focus group with arrow-key traversal. Ctrl+1 to Ctrl+5 select destinations (the full shortcut map belongs to the Wave 10 palette work; the shell only routes keys, §6.5).
- **Tabletop:** items are top-aligned, and Settings is pinned to the bottom. The AL-19 hinge guard asserts that no item intersects a separating hinge (§5.4).

### 3.5 Top app bars per destination

Rules:
- Pane surfaces have their own bars, and no shell-wide bar exists (IA D1).
- **A detail shown beside its list never has a back arrow** (adaptive skill rule; `ANDROID_SKILLS_ASSESSMENT.md` §6.1).
- **⌕ (the palette) appears only in Medium+ top bars.** On a Phone window the palette is reached from the drawer's ⌕ field, `/` at the start of the composer, and Ctrl+K (IA §3.3 and §8a as revised).
- In the Phone mode, **Chat uses ☰ even as a pushed conversation**, because the drawer *is* the chat switcher there. Its trailing actions are **✎ New chat and ⋮**, both hidden on an unsaved new chat (`CHAT_UX_SPEC.md` §4.1). Every other pushed detail uses ←.

| Surface | Phone (drawer) | Single (rail) | Dual / Triple (inside a pane) |
|---|---|---|---|
| Chat root | the **landing** (below; §8.2) | **Chats** (the Conversations list) · ⌕; no nav icon (the rail is visible) | list pane: an inline "⌕ Search chats" field at the top (`CHAT_UX_SPEC.md` §12.4); the placeholder detail is the landing |
| New chat (landing) | ☰ **New chat** / *Qwen 2.5 3B · Local ▾* | ← **New chat** / subtitle · ⌕ | **New chat** / subtitle · ⌕ |
| Conversation | ☰ **Title** / *Qwen 2.5 3B · Local ▾* · ✎ ⋮ | ← Title / subtitle · ⌕ ⋮ | Title / subtitle · ⌕ ⋮ (no nav icon; ✎ is in the rail) |
| Context inspector | sheet header "Context" + focus selector ✕ | side-sheet header ✕ | pane header ✕ |
| Opened source (note or file) | ← Title · ⋮ | ← Title · ⋮ | Title · ⋮ ✕ (the extra pane closes back to the chat) |
| Knowledge root | ☰ **Knowledge** · ⋮ (Import file, Sort) + "New note" FAB | **Knowledge** · ⌕ ⋮ + FAB | list-pane header **Knowledge** · ✎ New note · ⇪ Import · ⋮ |
| Note | ← Title · ✦ ⋮ | ← Title · ✦ ⋮ | Title · ✦ ⌕ ⋮ |
| File | ← Name · ⋮ | ← Name · ⋮ | Name · ⌕ ⋮ |
| Connections | sheet header ✕ | side sheet ✕ | pane header ✕ |
| Graph | ☰ **Graph** / *Centred on "Fold launch plan"* · ⋮ (Centre on…, Fit, Legend) | **Graph** / subtitle · ⌕ ⋮ | same, in the main pane |
| Node detail | sheet header ✕ | side sheet ✕ | supporting-pane header ✕ |
| Models | ☰ **Models** · ⇪ | **Models** · ⌕ ⇪ | list-pane header **Models** · ⇪ |
| Model details | ← Friendly name · ⋮ | ← … | Friendly name · ⌕ ⋮ |
| Settings | ☰ **Settings** | **Settings** · ⌕ | list-pane header **Settings** |
| Settings category | ← Category | ← Category | Category · ⌕ |

**Scroll behaviour:**
- The Chat bar is pinned, so the model identity stays visible.
- List screens (Knowledge, Models, Settings) use `enterAlwaysScrollBehavior` in the Phone mode and are pinned inside panes.
- In a **short window**, or on Compact with the soft keyboard up, the Chat header keeps one line: the title, with the model ▾ beside it (`CHAT_UX_SPEC.md` §3.4).

### 3.6 Back order (IA §3.8, made concrete)

Back always does exactly one visible thing, first match wins:

1. The IME is up: hide it (the system does this).
2. A transient overlay is open: close the topmost of dialog, palette, anchored panel or menu, then the drawer.
3. The top of the current stack is a sheet or peek surface (inspector, Connections, or node detail shown as a sheet): pop it.
4. The current stack has more than its root: pop, with predictive back. In Dual and Triple modes, popping an extra pane restores `List │ Detail`, and popping a detail restores `List │ placeholder`. **A pop that would produce no visible change keeps popping** (Material's `PopUntilScaffoldValueChange`, made explicit in the navigator, §8.3). Example: on Phone, Short, Dual and Triple windows, Back from the New-chat landing leaves the Chat root, because on those windows the root itself shows the landing. On a Single window, Back from the landing shows the Conversations list.
5. At the root of a destination other than Chat: switch to Chat, whose stack is unchanged.
6. At the Chat root: do not consume Back; the system finishes the task with its predictive back-to-home animation.

Esc on a hardware keyboard follows steps 1–4 and **never** reaches 5–6.

---

## 4. Wireframes per destination × window class

### 4.0 Conventions

```text
☰ drawer   ← back   ⌕ palette   ⋮ overflow   ✎ new   ⇪ import   ＋ attach   ↑ send   ■ stop
▾ opens a sheet or menu   ▴ peek (tap to expand)   ✦ connections   ░ scrim   ║ pane spacer (24 dp)
⋮⋮ pane-expansion drag handle (inside the spacer)   ▌ selected row
```

The canonical Dual device is the owner's inner display (1006 × 1043 portrait, 1043 × 1006 landscape, 330 dpi). Stock-density widths (852 × 883) are given in parentheses. The Phone canonical is the outer display (524 × 1175 for the owner, 443 × 994 at stock). Visual tokens, type and copy belong to `DESIGN_SYSTEM.md`. Chat internals (activity block, composer controls, citations) belong to `CHAT_UX_SPEC.md`, and Knowledge internals to `KNOWLEDGE_UX_SPEC.md`. These wireframes fix **structure, widths and what moves where**.

**Pane-expansion handle** (Material `paneExpansionDraggable`, which is accessible and RTL-aware from adaptive 1.2/1.3):

| Scene | Handle | Anchors | Saved |
|---|---|---|---|
| Conversations │ Chat · Chat │ Context | yes | side pane 280 / 320 / 400 dp | per destination and mode (per-entry saveable, a number) |
| Knowledge │ Item · Item │ Connections | yes | 280 / 320 / 400 dp | same |
| Canvas │ Node | yes | 280 / 320 / 400 dp | same |
| Models │ Details · Settings │ Category | no (fixed 320 / 280) | — | — |
| Any scene in tabletop | **hidden** | — | — |

### 4.1 First run and the landing ("What are you working on?")

The landing **is** an unsaved new chat (`CHAT_UX_SPEC.md` §11.1; the draft key `NewChatKey`, §8.2; `OBJECT_LIFECYCLE_SPEC.md` C1). No chat row exists until the first send, which ends the wall of empty "Chat" rows that `/chat` creates today. Its content (copy, the no-model card, the number of Recent items) belongs to `CHAT_UX_SPEC.md` §11. This section fixes structure and geometry.

**Phone — outer 524 × 1175 (owner) / 443 × 994 (stock), first run, no model**

```text
┌──────────────────────────────┐
│ ☰  New chat                  │  64 dp; ✎ and ⋮ hidden on an unsaved new chat
│    No model · Add one        │  subtitle → import (CHAT_UX_SPEC.md §4.3)
├──────────────────────────────┤
│                              │
│  What are you working on?    │
│                              │
│ ┌──────────────────────────┐ │
│ │ Add a model to start     │ │  no-model card, only while no model is usable
│ │ [ Choose a model file ]  │ │
│ └──────────────────────────┘ │
│  ✎ New note   ⤓ Import file  │  starting actions (IA §3.7)
│  ⌕ Search knowledge          │
│                              │
├──────────────────────────────┤
│ ◉ Knowledge on               │  context chip
│ ＋  Ask Skein…        (↑ off)│  full width; drafting allowed
└──────────────────────────────┘
```

When returning with a model ready, the card is absent, the subtitle reads `Qwen 2.5 3B · Local ▾`, and a **Recent** list (3 items on Compact) sits under the actions. The composer is not auto-focused on touch, and it is focused when a hardware keyboard is attached (`CHAT_UX_SPEC.md` §11.1).

**Dual — inner 1006 × 1043, returning user**

```text
┌────┬──────────────────────╥─────────────────────────────────────────┐
│ ✎  │ ⌕ Search chats       ║ New chat                             ⌕  │
│    │ Today                ║ Qwen 2.5 3B · Local ▾                   │
│ 💬 │  Skein UX redesign   ║                                         │
│ 📚 │  RAG architecture    ║        What are you working on?         │
│ ✦  │ Yesterday            ║                                         │
│ ▦  │  Mycology research   ⋮⋮  ✎ New note   ⤓ Import file           │
│    │ Previous 7 days      ║   ⌕ Search knowledge                    │
│    │  Continue repo …     ║                                         │
│    │                      ║   Recent (up to 8)                      │
│    │                      ║   📄 Fold launch plan            3h     │
│ ⚙  │                      ╟─────────────────────────────────────────┤
│    │                      ║ ◉ Knowledge on                          │
│    │                      ║ ＋  Ask Skein…                      ↑   │
└────┴──────────────────────╨─────────────────────────────────────────┘
 80    side 320 (280)          detail 582 (468); landing column ≤ 720
```

This is also the Conversations pane's `detailPlaceholder`, so a bare `[ChatHomeKey]` and `[ChatHomeKey, NewChatKey]` look identical, and Back from here leaves the app (§3.6 step 4). On first run the Conversations pane shows `No chats yet` (`CHAT_UX_SPEC.md` §11.2) in place of the list.

**Short — outer landscape 1175 × 524 (owner) / 994 × 443 (stock):** the Phone landing with a one-line header. The actions sit in one row, the column is capped at 720 and centred, and Recent scrolls under them.

### 4.2 Chat

Anatomy, copy and behaviour are `CHAT_UX_SPEC.md`'s (§3–§10). Here are the geometry and the per-mode arrangement.

**Phone — conversation (outer 524 × 1175 / 443 × 994)**

```text
┌──────────────────────────────┐
│ ☰  Skein UX redesign     ✎ ⋮ │  title (1 line, ellipsis); ✎ New chat · ⋮ Rename · Context · Delete
│    Qwen 2.5 3B · Local ▾     │  subtitle → Model sheet
├──────────────────────────────┤
│                          You │
│        ┌───────────────────┐ │
│        │Explain this arch… │ │
│        └───────────────────┘ │
│ Skein                        │
│ ▸ Worked for 6.2s · 3 sources│  activity block (CHAT_UX_SPEC.md §9)
│ The architecture…  [1]       │
│         ┌─────────────────┐  │
│         │ Jump to latest ↓│  │  only when scrolled up and new content exists
│         └─────────────────┘  │
├──────────────────────────────┤
│ ◉ 2 notes · Knowledge on     │  context chip → inspector
│ ＋  Ask Skein…             ↑ │  full width; ↑ becomes ■ while answering
└──────────────────────────────┘
```

**Phone — keyboard up**

```text
┌──────────────────────────────┐
│ ☰  Skein UX redesign ▾   ✎ ⋮ │  header keeps one line with the IME up (CHAT_UX_SPEC.md §3.4)
├──────────────────────────────┤
│ Skein                        │  the transcript shrinks from the top; the newest
│ The architecture has three   │  message stays pinned above the composer
│ layers…                      │  (reverseLayout; CHAT_UX_SPEC.md §6)
├──────────────────────────────┤
│ ◉ 2 notes · Knowledge on     │
│ ＋  How does the vault|    ↑ │  ≤ 3 lines with the IME up; never pushes the list below 40 %
├──────────────────────────────┤
│ q w e r t y u i o p          │  IME, consumed by the composer only (§6.2)
│  a s d f g h j k l           │
└──────────────────────────────┘
```

**Phone — inspector opened by the user** (bottom sheet, half height, drags to full; content per `CHAT_UX_SPEC.md` §17)

```text
┌──────────────────────────────┐
│ ☰  Skein UX redesign     ✎ ⋮ │
│░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░│  chat under a scrim
│░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░│
├───────────── ─── ────────────┤  drag handle
│ Context   Latest answer ▾  ✕ │  focus selector
│ Qwen 2.5 3B · Local ›        │  Session
│ Knowledge  ● Search Knowledge│
│  Added: 📄 Fold plan  📄 …   │
│  Used in this answer    3 ›  │
│ Activity  ▸ Worked for 6.2s  │
└──────────────────────────────┘
 max width 640, centred
```

**Phone — after folding with the inspector open (Test A):** the inspector entry stays on the stack but is **collapsed into the chip row**. The row reads `Context · 2 notes · 3 sources ▴`, with no scrim, and the chat is fully usable. Tapping the row expands the sheet. Back pops the entry, and the chip returns to its normal label.

```text
│ The architecture…  [1]       │
├──────────────────────────────┤
│ Context · 2 notes · 3 src  ▴ │  peek (replaces the chip row in place)
│ ＋  draft that should surv… ↑│  draft intact, caret intact
└──────────────────────────────┘
```

**Phone — opened source** (a citation or an inspector passage: `ChatSourceKey`, full screen; `CHAT_UX_SPEC.md` §17.6)

```text
┌──────────────────────────────┐
│ ←  Fold launch plan        ⋮ │  ← returns to the inspector sheet if it was open, then to the chat at the same scroll
├──────────────────────────────┤
│ …                            │
│ ▌Targets M2 for the ask path.│  the passage, highlighted until the user scrolls or edits
│ …                            │
└──────────────────────────────┘
```

**Phone — Model sheet** (`CHAT_UX_SPEC.md` §16; a Persona section appears only once personas exist)

```text
├───────────── ─── ────────────┤
│ Model                        │
│ Used for all chats           │
│ ON THIS DEVICE               │
│ ◉ Qwen 2.5 3B                │
│   1.6 GB · Loaded            │
│ ○ Gemma 4 E4B                │
│ Model details              › │  → ModelDetailsKey (follow, §8.3)
│ Manage models              › │  → Models (go to)
└──────────────────────────────┘
```

**Phone — attach picker** (full screen; the search field takes the IME; content per `CHAT_UX_SPEC.md` §18.2)

```text
┌──────────────────────────────┐
│ ✕  Add to this chat     Done │
├──────────────────────────────┤
│ ⌕ Search notes and files     │
│ [All] [Notes] [Files]        │
│ ☑ 📄 Fold launch plan        │
│ ☐ 📄 Vault format            │
│ ☐ 📎 spec.pdf                │
├──────────────────────────────┤
│ ⤓ Import a file…             │
└──────────────────────────────┘
```

**Short — outer landscape 1175 × 524 (owner) / 994 × 443 (stock)**

```text
┌──────────────────────────────────────────────────────────────────┐
│ ☰  Skein UX redesign · Qwen 2.5 3B · Local ▾                ✎ ⋮  │  one-line header, 56 dp
├──────────────────────────────────────────────────────────────────┤
│            You  Explain this architecture.                       │
│            Skein  ▸ Worked for 6.2s · 3 sources                  │  column ≤ 720, centred
│            The architecture…                                     │
├──────────────────────────────────────────────────────────────────┤
│            ◉ 2 notes · Knowledge on                              │
│            ＋  Ask Skein…                                   ↑    │  ≤ 3 lines
└──────────────────────────────────────────────────────────────────┘
 one pane · drawer · inspector = 360 dp side sheet from the end edge · palette centred, full height
```

The §22 "full-width composer" applies to the portrait closed state. In landscape the composer spans the 720 dp column, which is not squeezed. The transcript budget with the IME up is a cross-spec concern (§13 item 5).

**Single — Medium inner 791 × 820**

```text
┌────┬──────────────────────────────────────────────────┐
│ ✎  │ ←  Skein UX redesign                        ⌕ ⋮  │  ← to Conversations (the Chat root on a rail window)
│ 💬 │    Qwen 2.5 3B · Local ▾                         │
│ 📚 │──────────────────────────────────────────────────│
│ ✦  │ You  Explain this architecture.                  │
│ ▦  │ Skein  ▸ Worked for 6.2s · 3 sources             │
│    │ The architecture…                                │
│ ⚙  │──────────────────────────────────────────────────│
│    │ ◉ 2 notes · Knowledge on                         │
│    │ ＋  Ask Skein…                               ↑   │
└────┴──────────────────────────────────────────────────┘
 80    chat 711 (single pane; column ≤ 720)
 inspector = modal side sheet 360 from the end edge over a scrim; Model sheet = anchored panel;
 an opened source = full-screen route with ←
```

**Dual — conversation (inner 1006 × 1043)**, as in `CHAT_UX_SPEC.md` §3.3, with pane widths:

```text
┌────┬──────────────────────╥─────────────────────────────────────────┐
│ ✎  │ ⌕ Search chats       ║ Skein UX redesign                   ⌕ ⋮ │
│    │ Today                ║ Qwen 2.5 3B · Local ▾                   │
│ 💬 │ ▌Skein UX redesign  ⋮║─────────────────────────────────────────│
│ 📚 │  RAG architecture   ⋮║ You  Explain this architecture.         │
│ ✦  │ Yesterday            ⋮⋮                                        │
│ ▦  │  Mycology research  ⋮║ Skein                                   │
│    │ Previous 7 days      ║ ▸ Worked for 6.2s · 3 sources           │
│    │  Continue repo …  ◌ ⋮║ The architecture…  [1]                  │
│ ⚙  │                      ╟─────────────────────────────────────────┤
│    │                      ║ ◉ 2 notes · Knowledge on                │
│    │                      ║ ＋  Ask Skein…                      ↑   │
└────┴──────────────────────╨─────────────────────────────────────────┘
 rail 80   side 320 (280)        chat 582 (468); landscape 619 (499); ◌ = answering in the background
```

**Dual — keyboard up:** the IME covers the bottom of **both** panes. Only the chat pane consumes the inset: the composer rises and the transcript shrinks. The Conversations pane **does not resize**; its lower rows are simply covered. Today's shell-wide `safeDrawing` padding shrinks every pane (`SkeinApp.kt:296-301`), and that stops.

**Dual — inspector open:** `rail │ Chat │ Context`. The list yields its place. Back restores `rail │ Conversations │ Chat` (IA §5.2).

```text
┌────┬───────────────────────────────────╥──────────────────────────┐
│ ✎  │ Skein UX redesign             ⌕ ⋮ ║ Context  Latest answer▾ ✕│
│    │ Qwen 2.5 3B · Local ▾             ║ Qwen 2.5 3B · Local ›    │
│ 💬 │───────────────────────────────────║ Knowledge                │
│ 📚 │ You  Explain this architecture.   ║  ● Search Knowledge      │
│ ✦  │                                   ⋮⋮ Added: 📄 Fold plan     │
│ ▦  │ Skein                             ║  Used in this answer     │
│    │ ▸ Worked for 6.2s · 3 sources     ║   📄 Fold plan · 2 ›     │
│    │ The architecture…  [1]            ║ Activity                 │
│ ⚙  │───────────────────────────────────║  ▸ Found 3 notes         │
│    │ Context · 2 notes · 3 sources ▴   ║                          │
│    │ ＋  Ask Skein…                ↑   ║                          │
└────┴───────────────────────────────────╨──────────────────────────┘
 rail 80   chat 582 (468)                     extra 320 (280)
```

**Dual — opened source:** tapping `[1]` or a passage pushes `ChatSourceKey`. The note or file takes the extra pane beside the chat, scrolled to the passage, and the inspector yields: `rail │ Chat │ Fold launch plan`. Back restores `Chat │ Context` exactly, then `Conversations │ Chat` (`CHAT_UX_SPEC.md` §17.6).

**Dual — Model sheet:** an anchored panel, 360 dp wide, drops from the header subtitle. It closes on any change of width class (§7.4). **Attach picker:** a centred modal panel, 560 dp wide and at most 80 % of the height, over a scrim, with the same content as the Phone version.

**Triple — Large 1280 × 800:** `rail 80 │ Conversations 320 │ Chat 512 │ Context 320`. Without the inspector: `rail │ Conversations 320 │ Chat 856`, with the column capped at 720 and centred. On XL (1920 × 1080): `expanded rail 240 │ 320 │ 992 (column 720) │ 320`.

### 4.3 Knowledge

**Phone — list**

```text
┌─────────────────────────────┐
│ ☰  Knowledge              ⋮ │  ⋮: Import file · Sort (no ⌕ on Compact: the palette is in the drawer)
├─────────────────────────────┤
│ ⌕ Search notes and files    │  inline filter with snippets (≠ palette)
│ [All] [Notes] [Files] [AI]  │  single-select chips; horizontally scrollable, never clipped
│ Recent                      │
│ 📄 Fold launch plan    3h ⋮ │  ⋮ / long-press: Rename · Delete · Ask about this
│    Targets M2 for the ask…  │
│ 📎 spec.pdf            1d ⋮ │
│ 📄 Weekly review       1d ⋮ │
│                     ┌─────┐ │
│                     │ ✎ + │ │  FAB "New note" → pushes NewNoteKey (a draft; no row until the first commit)
│                     └─────┘ │
└─────────────────────────────┘
```

**Phone — item (note)**

```text
┌─────────────────────────────┐
│ ←  Fold launch plan     ✦ ⋮ │  ✦ Connections · ⋮ Rename · Delete · Share · Ask about this
├─────────────────────────────┤
│ Fold launch plan            │  title (editable per KNOWLEDGE_UX_SPEC.md)
│                             │
│ Targets M2 for the ask path.│  text column ≤ 720
│ Owner smoke test on the …   │
│ See [[Vault format]].       │  wikilink → follow (push NoteKey)
└─────────────────────────────┘
```

**Phone — Connections** (bottom sheet; after a fold it starts as a peek: `Connections · 3 links ▴`)

```text
├──────────── ─── ────────────┤
│ Connections               ✕ │
│ Linked from (2)             │
│  📄 Weekly review           │
│  💬 Chat about the fold     │  a chat opens in Chat (go to)
│ Links to (1)                │
│  📄 Vault format            │
│ Local graph  Open in Graph ›│  → Graph (go to), centred on this note
│  [mini canvas, 160 dp]      │
│ Properties                  │
└─────────────────────────────┘
```

**Short — outer landscape:** the list and item are single panes. The list gets a 720 column; Connections is a 360 dp side sheet.

**Single — Medium 791 × 820:** list → item as single panes behind the rail, with ← on the item. **Prototype (AL-22, IA open question 1):** two panes, `rail 80 │ list 280 │ item 407`, via `calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth` for the Knowledge scene only. Keep it if the Roborazzi `fold-inner-medium` captures show a readable item column; otherwise stay single.

**Dual — list │ item (inner 1006 × 1043)**

```text
┌────┬──────────────────────╥─────────────────────────────────────────┐
│ ✎  │ Knowledge    ✎ ⇪ ⋮   ║ Fold launch plan                  ✦ ⌕ ⋮ │  no ← in a detail pane
│    │ ⌕ Search notes…      ║─────────────────────────────────────────│
│ 💬 │ [All][Notes][Files]› ║ Fold launch plan                        │
│ 📚 │ ▌Fold launch plan 3h ║                                         │
│ ✦  │  spec.pdf         1d ⋮⋮ Targets M2 for the ask path. Owner      │
│ ▦  │  Weekly review    1d ║ smoke test on the Pixel 9 Pro Fold…     │
│    │  Vault format     3d ║                                         │
│ ⚙  │                      ║                                         │
└────┴──────────────────────╨─────────────────────────────────────────┘
 rail 80   side 320 (280)        item 582 (468); text column ≤ 720
```

Empty selection (`[KnowledgeHomeKey]` only): the detail placeholder reads "Select a note or file", with **New note** and **Import file** buttons. A bare placeholder is not allowed.

**Dual — Connections open:** `rail │ Note │ Connections 320` (the list yields). Back restores `rail │ list │ note`.

**Triple — Large:** `rail │ list 320 │ note 512 │ connections 320`.

### 4.4 Graph

The Graph destination appears in the drawer and rail **only once it is wired** as IA §3.2 requires: centred on the most recently opened note, with a purposeful empty state. Until then it is hidden (AL-01).

**Phone — canvas**

```text
┌─────────────────────────────┐
│ ☰  Graph                  ⋮ │  ⋮: Centre on… (palette scoped to notes) · Fit · Legend
│    Centred on Fold launch…  │
├─────────────────────────────┤
│        ○ Vault format       │
│       /                     │
│  ● Fold launch plan ── ○ …  │
│       \                     │
│        ○ Weekly review      │
│                             │
│ [Legend ▾]              [⤢] │  collapsible legend · fit to screen
└─────────────────────────────┘
```

**Phone — node selected** (bottom sheet; the node is highlighted and kept in view above the sheet)

```text
│  ● Fold launch plan ── ◉ …  │  ◉ selected
├──────────── ─── ────────────┤
│ Weekly review             ✕ │
│ Note · edited 1d · 4 links  │
│ [ Open ]   [ Centre here ]  │  Open → follow (push NoteKey); Centre → replace GraphKey
│ Linked from (2) · Links to …│
└─────────────────────────────┘
```

**Short and Single:** canvas single pane; node detail is a 360 dp side sheet.

**Dual — canvas │ node (inner 1006 × 1043)**

```text
┌────┬───────────────────────────────────╥──────────────────────────┐
│ ✎  │ Graph · Centred on Fold launch ⌕ ⋮║ Weekly review          ✕ │
│    │                                   ║ Note · edited 1d         │
│ 💬 │        ○ Vault format             ║ [ Open ] [ Centre here ] │
│ 📚 │       /                           ⋮⋮Linked from (2)          │
│ ✦  │  ● Fold launch plan ── ◉ Weekly…  ║  📄 Fold launch plan     │
│ ▦  │                                   ║ Links to (1)             │
│ ⚙  │ [Legend ▾]                   [⤢]  ║ Properties               │
└────┴───────────────────────────────────╨──────────────────────────┘
 rail 80   canvas 582 (468)                   supporting 320 (280)
```

With no node selected, the canvas takes the full width (only the main pane is on the stack). After a window change, the selected node, or the focus node if there is none, is **kept in view**: zoom and pan are normalised to the focus, not to raw pixels.

### 4.5 Models

**Phone — list** (sections and copy per the Wave 9 models work and `DESIGN_SYSTEM.md`)

```text
┌─────────────────────────────┐
│ ☰  Models                 ⇪ │  ⇪ Import model
├─────────────────────────────┤
│ On this device              │
│ Qwen 2.5 3B · Local         │
│ 1.6 GB · Ready · Default    │
│ Gemma 2 2B · Local          │
│ 1.4 GB                      │
│ Importing Phi-3 mini…  42 % │  progress row from the app-scoped job (B6); never over the composer
└─────────────────────────────┘
```

**Phone — details** (a route with ←): friendly summary, **Use by default**, **Remove**, and an *Advanced details* disclosure (filename, format, quantization, size, context, backend, hash, licence, in monospace).

**Dual:** `rail │ Models list 320 (280) │ Model details (column ≤ 720)`. There is no drag handle. The placeholder reads "Select a model", or "Import a model" when the list is empty.

### 4.6 Settings

**Phone — categories:** ☰ Settings · Appearance · Privacy & security · Personas (hidden until `skein-3iw`) · Knowledge & search · About · **Advanced ▸** (collapsed). **Category:** a route with ←. **Dual:** `rail │ Categories 320 (280) │ Category (column ≤ 720)`, with Appearance preselected as the placeholder detail so the right side is never empty.

### 4.7 Command palette

**Phone** (full screen; the field is focused and the IME is up)

```text
┌─────────────────────────────┐
│ ←  Search or run a command  │
├─────────────────────────────┤
│ Recent                      │
│  💬 Skein UX redesign       │
│  📄 Fold launch plan        │
│ Commands                    │
│  ✎ New chat          Ctrl+N │  shortcut hints only when a hardware keyboard is present
│  📄 New note                │
│  ⇪ Import file              │
│  ▦ Switch model             │
│  ✦ Open Graph               │
├─────────────────────────────┤
│ (IME)                       │  results take ime as contentPadding
└─────────────────────────────┘
```

With a query, the results are grouped as **Chats** (title and in-message hits; on Compact this is chat search, `CHAT_UX_SPEC.md` §12.4), **Notes and files**, and **Commands**. Opening a result is a **go to** (§8.3).

**Dual, Single, Triple:** a centred overlay, 640 dp wide, top-anchored at 12 % of the height (at least 48 dp below the status bar), with a height of at most min(560 dp, window − IME − 48 dp) over a light scrim. **Short and ≥ 600 wide:** centred, 640 dp wide, full height minus the IME. **Tabletop:** confined to the top partition, because the IME occupies the bottom half. Palette content and ranking belong to the Wave 10 palette work (IA §4). The palette's layout, state and back behaviour are specified here.

### 4.8 Lock, unlock and setup

**Phone — locked** (the vault gate; product language per IA §3.1)

```text
┌─────────────────────────────┐
│                             │
│            Skein            │
│                             │
│       Skein is locked       │
│                             │
│      [ Unlock Skein ]       │  biometric prompt (system window)
│                             │
│  Use your fingerprint or    │
│  screen lock.               │
└─────────────────────────────┘
```

**Any wider window:** the same centred column, at most 480 dp wide. **No rail and no drawer:** the shell is not composed while locked. Setup ("Set up Skein") uses a centred column of at most 560 dp. After unlock, the saved back stack is re-resolved (§7.7) and the user lands **where they were**. While data loads, the destination shows a loading skeleton, never "Nothing here yet" (today's P1-11) or "No tabs open".

### 4.9 What hides, levitates or moves, by mode

| Element | Phone | Short | Single | Dual | Triple | Tabletop (P2) |
|---|---|---|---|---|---|---|
| Conversations list | the drawer's Chats section (the Chat root shows the landing) | the drawer's Chats section | the Chat root screen | side pane | side pane | bottom partition |
| Knowledge, Models, Settings lists | root screen | root screen | root screen | side pane | side pane | bottom partition |
| Detail (chat, note, graph) | pushed screen | pushed screen, column 720 | pushed screen, column ≤ 720 | detail pane | detail pane | top partition |
| Inspector, Connections | bottom sheet → **peek** after a shrink | side sheet | side sheet | extra pane (list hides) | third pane | bottom partition |
| Node detail | bottom sheet / peek | side sheet | side sheet | supporting pane | supporting pane | bottom partition |
| Opened source (from a chat) | full-screen route | full-screen route | full-screen route | extra pane beside the chat | third pane | top partition |
| Model sheet | bottom sheet | anchored panel | anchored panel | anchored panel | anchored panel | top partition |
| Attach picker | full screen | full screen | centred panel | centred panel | centred panel | top partition |
| Palette | full screen | centred, full height | centred | centred | centred | top partition |
| Drawer | modal | modal | — | — | — | — |
| Rail | — | — | 80 dp | 80 dp | 80 dp (XL: 240) | 80 dp, items above the hinge |
| Drag handle | — | — | — | yes (per §4.0) | yes | **hidden** |
| Chat header with the IME up | one line (`CHAT_UX_SPEC.md` §3.4) | one line | two lines, pinned | two lines, pinned | two lines, pinned | two lines, pinned |

---

## 5. Hinge and posture

### 5.1 Source of truth

`currentWindowAdaptiveInfoV2().windowPosture` provides `isTabletop`, and `hingeList` provides `bounds`, `isVertical`, `isSeparating` and `isOccluding`. **Delete** `layout/FoldPosture.kt`. It reads only the first `FoldingFeature`, ignores `isSeparating` and the hinge bounds, and starts at `Unknown` (`AUDIT_SHELL.md` §4.1). `SkeinPosture` is a three-case projection:

- `Flat`: no separating hinge.
- `Book(hinge)`: a vertical, separating hinge.
- `Tabletop(hinge)`: a horizontal, separating hinge.

It is injectable for tests. Robolectric reports no `FoldingFeature`, so JVM tests pass a hand-built `WindowAdaptiveInfo(windowSizeClass, Posture(isTabletop = …, hingeList = …))`, as NiA does.

### 5.2 Flat (open, 180°)

The Fold's inner panel is continuous. When flat, the hinge should report `isSeparating = false` and `isOccluding = false`, so **Skein does nothing special**, and `HingePolicy.AvoidSeparating` is a no-op. **To verify on device (AL-19):** a debug-build hinge overlay logs `hingeList` in each posture. If the flat inner display ever reports `isSeparating = true`, the two-pane split would snap to the fold at 503 dp. At 330 dpi that is harmless (411 ∣ 491), but the overlay confirms the assumption.

### 5.3 Book (half-open, vertical hinge; inner display in portrait)

- **Expanded:** two panes, **split at the fold** by `AvoidSeparating`. The side pane grows to the hinge: ≈ 411 ∣ 491 at 330 dpi, ≈ 334 ∣ 414 at stock. The guard checks the real widths.
- **Medium (a larger Display size):** the tie-breaker makes it **two** panes (≈ 303 ∣ 384), because a book posture is the strongest signal that the user wants two pages (Reply precedent). Without it, a single chat pane would straddle the crease.
- **Single-pane destinations in book posture** (Settings category, or a detail that follows a link into a single-pane scene): the content column is placed entirely **on one side of the hinge** (the end side), never across it.

### 5.4 Tabletop (half-open, horizontal hinge; inner display in landscape: the owner's grip)

**Wave 3 (required, bead AL-19): hinge-safe guards on the flat layout**

1. **No fixed interactive control may intersect a separating hinge's bounds.** This covers buttons, text fields, the composer, the drag handle, FABs, sheet handles, rail items, chips and dialog buttons. Scrolling content (list rows, transcript) may pass through the hinge while it scrolls. It must not *rest* a control there: the list's bottom `contentPadding` and the transcript's jump-to-latest button avoid the hinge band.
2. **Modals stay inside one partition.** Dialogs and sheets never straddle the hinge. Text-entry and reading modals (palette, rename, attach picker, Model sheet, an opened source) go in the **top** partition, because the IME opens in the bottom half. Confirmations and bottom sheets go in the **bottom** partition, the touch half; a bottom sheet's maximum height equals the bottom partition's height.
3. **The pane-expansion handle is hidden**, because it is vertically centred and would sit on the hinge.
4. **Rail:** items are top-aligned in the top partition, and Settings is pinned to the bottom of the bottom partition. The guard test asserts that no item bounds intersect the hinge. If a large font scale pushes an item into the band, the rail drops that item's label spacing to compact; if it still intersects, the item moves below the hinge.

**P2 (bead AL-20): tabletop layouts** (`panes = 1`, vertical partitions)

```text
inner landscape 1043 × 1006 @330, half-open; hinge at y ≈ 503
┌────┬─────────────────────────────────────────────────────────────┐
│ ✎  │ Skein UX redesign · Qwen 2.5 3B · Local ▾              ⌕ ⋮  │
│ 💬 │ Skein                                                       │  TOP = reading half
│ 📚 │ ▸ Worked for 6.2s · 3 sources                               │  transcript
│ ✦  │ The architecture has three layers…                          │
│ ▦  │                                                             │
╞════╪═══════════════════ hinge: no controls ═════════════════════╡
│    │ ◉ 2 notes · Knowledge on                                    │  BOTTOM = touch half
│    │ ＋  Ask Skein…                                          ↑   │  composer at the hinge side
│    │ Today  Skein UX redesign · RAG architecture · …             │  conversations (covered by the IME while typing)
│ ⚙  │                                                             │
└────┴─────────────────────────────────────────────────────────────┘
```

| Destination | Top partition | Bottom partition |
|---|---|---|
| Chat | transcript | composer + context chip; conversations list when the IME is hidden; the inspector if open |
| Knowledge item | document | Connections if open, otherwise the list |
| Graph | canvas | node detail if selected, otherwise legend and search (Material supporting-pane `Reflow`, if the scene strategy produces it; otherwise a posture-aware layout inside the entry) |
| Models, Settings | flat single pane, hinge-padded | — |

### 5.5 The owner's habit: the landscape grip

- **Primary canvas:** the inner display in landscape, 1043 × 1006 (row 10). Design and screenshot Dual-landscape first. Rotating to inner portrait (row 9) keeps the same mode, so the layout stays stable: the pane count does not change, and only the widths do (619 → 582).
- **Half-folding in the same grip gives tabletop**, not book. Tabletop is the likeliest half-fold posture for this owner, so the Wave 3 hinge guards are required, and the P2 tabletop layouts rank above book-specific polish.
- **Closed in landscape** (row 5 or 6) is a short window: a drawer, one pane and side sheets. The height gate exists for this case.
- **A hardware keyboard is often attached** (the owner's configuration reports `qwerty`). In `device-before/inner-portrait/02-chat-draft-context-keyboard.png`, only the IME-dismiss chevron is visible under the composer, which is consistent with a hardware keyboard replacing the soft IME. See §6.5.

---

## 6. Keyboard, IME and insets

### 6.1 Baseline

`MainActivity` already calls `enableEdgeToEdge(statusBarStyle, navigationBarStyle)` with theme-derived styles (skein-1vfg), and it keeps that ownership (skills override 1). No screen sets `isAppearanceLight*`. Material's adaptive scaffolds and the navigation suite **do not forward `PaddingValues`**, so insets are applied **per pane, once**, never on the scaffold parent (`ANDROID_SKILLS_ASSESSMENT.md` §6.4).

### 6.2 Inset ownership

| Layer | Consumes | Does not consume |
|---|---|---|
| Shell root (`ModalNavigationDrawer` + suite) | nothing. **Remove** today's `windowInsetsPadding(WindowInsets.safeDrawing)` on the shell column (`SkeinApp.kt:296-301`), which shrinks every pane when the IME opens | — |
| Rail | its own `safeDrawing` side (start) + top + bottom system bars | IME |
| Drawer sheet | `safeDrawing` except the IME (Material default) | IME |
| Each pane's `Scaffold` | `contentWindowInsets = safeDrawing.only(Top + the pane's outer horizontal edge)`; the `TopAppBar` uses `TopAppBarDefaults.windowInsets` | **IME and bottom navigation bars**; the pane's bottom-most element owns them |
| Chat composer | `WindowInsets.ime.union(WindowInsets.navigationBars)` (prefer `Modifier.fitInside(WindowInsetsRulers.Ime.current)` where it composes cleanly) | — |
| Transcript and lists | insets as **`contentPadding`**, never parent padding; the transcript uses `reverseLayout = true`, so the newest message stays above the composer as the IME rises | — |
| Bottom sheets | Material `ModalBottomSheet` defaults (`BottomSheetDefaults.windowInsets`); sheet content with a text field takes `imePadding` inside the sheet | — |
| Levitated or overlay panes (peek, side sheet) | apply `safeDrawing` inside the pane content | — |
| Palette | Phone: the field at the top, results with the IME as `contentPadding`. Centred: height clamped above the IME (§4.7) | — |
| Dialogs | Material dialogs; full-screen dialogs set `decorFitsSystemWindows = false` | — |
| Display cutout | whichever pane or rail touches the cutout edge applies that edge's `safeDrawing` inset. The outer display's punch hole is top-centre in portrait; the inner display's is in a corner, which is a side edge in landscape | — |

**Double-padding rule:** never add `imePadding()` below a layer that already consumes `safeDrawing`, because `safeDrawing` includes the IME. The Wave 3 inset bead (AL-11) removes the shell-level `safeDrawing` first, so this rule becomes easy to keep.

### 6.3 Composer and IME by mode

The content rules are `CHAT_UX_SPEC.md` §3.4 and §7.1. The geometry is this spec's.

- **Phone:** the composer is full width and the chip row stays. With the IME up, the header drops to one line (title + ▾) and the transcript shrinks from the top.
- **Short windows (height < 600):** a one-line header, the composer capped at 3 lines, and the chip kept.
  - **Measured concern:** with the IME up, the transcript budget is about 443 − IME (≈ 200–220) − header 56 − chip 48 − composer 56 ≈ 60–80 dp at stock, and ≈ 110–130 dp at the owner's 524 dp height.
  - The Test D landscape variant on the device measures it. If it is under 120 dp, §13 item 5 proposes a fallback, which `CHAT_UX_SPEC.md` decides.
  - If the platform shows its full-screen extract editor in landscape, accept it.
- **Dual:** only the pane that owns the focused field reacts. A covered list pane is acceptable. A resized list pane is not.
- **Composer growth:** 6 lines on Compact, 8 on Medium and Expanded, 3 on short windows or with the IME up on Compact. It never pushes the message list below 40 % of the window height, then it scrolls internally (`CHAT_UX_SPEC.md` §7.1). This is today's unbounded growth fix (`AUDIT_CHAT_MODELS_SETTINGS.md` §2.4).

### 6.4 Sheets, palette and dialogs with the IME

- A bottom sheet that contains a text field (rare: the Phone attach picker is full screen instead) rises with the IME and caps at the full height minus the status bar.
- The centred palette never lets results sit under the IME. It shrinks its results list, not its field.
- The rename dialog stays centred above the IME. In tabletop it goes in the top partition.

### 6.5 Hardware keyboard present or absent, and pointers

| | Absent (soft IME) | Present (`Configuration.keyboard == QWERTY` and `hardKeyboardHidden == NO`) |
|---|---|---|
| Layout | IME rules above | no IME (the platform may show a small toolbar); the full height is available |
| Send | IME action **Send**; a newline via the IME's return key when multi-line (`CHAT_UX_SPEC.md` decides) | **Enter** sends, **Shift+Enter** inserts a newline |
| Shortcut hints | hidden | shown in palette rows and tooltips |
| Focus | touch-first | visible focus rings; rail and list arrow-key traversal; Tab order is list → detail → extra |
| Shell key routing (shell-owned; the full map is Wave 10) | — | Ctrl+K palette · Ctrl+N new chat · Esc = Back steps 1–4 (§3.6) · Ctrl+Shift+I inspector toggle · Ctrl+1…5 destinations. Handled in the shell's `onPreviewKeyEvent`, and never consumed while a text field has an active IME composition |
| Pointer (mouse or touchpad) | — | hover states; right-click on rows opens the same menu as ⋮ or long-press; the drag handle works with a mouse; scroll wheel on the graph zooms around the pointer |

With `keyboard|keyboardHidden|navigation` in `configChanges` (§7.5), attaching or detaching the keyboard causes **no recreation** on API 30–36. Android 17 already skips it by default. `LocalConfiguration` updates, and the table above re-evaluates. Keyboard support is never required for basic use (§41).

### 6.6 The IME across a display swap (Test D)

On the Pixel Fold the Activity stays on logical display 0 and the *panel* behind it changes, so the IME window is re-laid out rather than moved. Whether it stays visible after a fold is platform behaviour. **Skein's contract:**

1. **Kept:** the draft text, the selection and caret, and the composer's focus (a retained flag, §7.3 row 35). With `configChanges`, the composition survives, and so does the focused node.
2. If the IME is still showing, the composer sits directly above it with no transcript jump. The `reverseLayout` anchor holds.
3. If the IME was dismissed, the composer is at the bottom, still focused. **One tap** brings the IME back. **Skein never force-shows the IME** after a swap, because an unfold would otherwise cover half the newly opened space.
4. After recreation (for example a Display-size change), focus is re-requested once. After an **unlock or a process death, it is not**: no keyboard pops up over the unlock result. On the landing, the hardware-keyboard auto-focus rule of `CHAT_UX_SPEC.md` §11.1 applies instead.
5. With a hardware keyboard, typing continues across the swap without a lost keystroke. The device test types during the fold.

### 6.7 `windowSoftInputMode`

The manifest declares none today. Recommendation: declare `android:windowSoftInputMode="adjustResize"`, as the edge-to-edge skill and Jetchat do, so that IME insets are dispatched to Compose. The skills assessment requires its own bead with on-device evidence (override 3), so it is bead **AL-03**, verified against Test D on both displays. It is not bundled into the `configChanges` change.

---

## 7. Live transition contract

### 7.1 Storage tiers

| Tier | Mechanism | Holds | Survives recreation | Survives fold (live) | Survives process death | Survives vault lock |
|---|---|---|---|---|---|---|
| **T1 Back stack** | Nav3 `rememberNavBackStack`, one per destination, **hoisted above `VaultGate`** in `MainActivity.setContent` | typed `@Serializable` keys: **ids and enums only** | ✓ | ✓ | ✓ (Bundle) | ✓ (it is not inside the gated composition) |
| **T2 Per-entry saveable** | `rememberSaveable` inside entries, via `rememberSaveableStateHolderNavEntryDecorator` over a `SaveableStateHolder` **hoisted above `VaultGate`** | numbers, booleans, enums, ids | ✓ | ✓ | ✓ | ✓ (entries are disposed, not popped, on lock, so their state is kept) |
| **T3 Entry-retained** | `ViewModel` per entry via `rememberViewModelStoreNavEntryDecorator`, over a **session-scoped `ViewModelStoreOwner`** | vault content, typed text, pending UI | ✓ | ✓ | ✗ | ✗ (**cleared on lock**; security) |
| **T4 Shell-retained** | a session-scoped shell `ViewModel` (same owner) | palette state, pending deep link, dialog targets | ✓ | ✓ | ✗ | ✗ |
| **T5 Session-scoped controller** | objects owned by `VaultSession` (for example `ChatScope`, `LOCK_POLICY_INDEXING.md` §4; the turn controller is `CHAT_UX_SPEC.md` C1) | in-flight turns, draft store, file imports | ✓ | ✓ (also when the UI leaves: navigation, a pop, a pane change) | ✗ | ✗ (ended or flushed at LOCKING) |
| **T6 App-scoped controller** | `Application` scope | model import | ✓ | ✓ | ✗ | ✓ |
| **T7 Vault** | encrypted SQLite | messages, notes, **encrypted draft rows**, per-chat context and bindings | ✓ | ✓ | ✓ | ✓ (after unlock) |
| **T8 Composition** | `remember` above `NavDisplay` | drawer open, hover, anchored menus | ✗ | ✓ unless the container changes | ✗ | ✗ |

**The session-scoped `ViewModelStoreOwner`** lives in an Activity-level `ViewModel`, so it survives recreation. It holds one `ViewModelStore` per `VaultSession` and calls `clear()` when that session closes. Using the Activity's own store owner for entries would let decrypted content outlive a lock. That is **forbidden**.

### 7.2 The privacy constraint: the saved-state Bundle is outside the vault

The Activity's saved state is held by `system_server` for process-death restore, outside Skein's encryption and zeroisation.

- **Allowed in the Bundle (T1, T2):** vault row ids, message ids, the model registry id (see B9), enums, booleans, ints and floats.
- **Forbidden:** any user-typed text, titles, filenames (including model filename slugs), message or note content, search queries, timestamps of user activity, and URIs of shared or imported files.
- **Forbidden APIs in feature code:** `rememberTextFieldState()` (it is saveable by default), `rememberSaveable(saver = TextFieldState.Saver | TextFieldValue.Saver)`, `rememberSaveable { mutableStateOf("…") }` for any text, `SavedStateHandle` string entries, and `EditorState.Saver`. `EditorState.Saver` exists at `feature/editor/…/EditorState.kt:236` and must stay unused.
- **Where text goes instead:** T3 or T5 in memory, and T7 for durability. Drafts use the **encrypted draft row** recommended in IA §6.3 and D7.
- **Today's shell already leaks into the Bundle, and the redesign removes both leaks:**
  - `TabsState.Saver` writes **tab titles** (`feature/shell/…/tabs/TabsState.kt:144`).
  - `NavState.Saver` writes the **typed command-bar text** (`feature/shell/…/nav/NavState.kt:75`).
- **Enforcement:**
  - the **Bundle privacy test** (§9.3): sentinel strings typed into every field, then a recursive scan of the captured Bundle;
  - a review-checklist line in the implementation beads;
  - a serialisation test of every `NavKey` (only id or enum fields).

### 7.3 State table (prompt §24 list, plus the shell's own state)

✓ = survives. ✗ = lost, **by design** where noted. ~ = partially survives, see the note. The "Fold" column assumes `configChanges` is declared (§7.5). The density does not change on a fold (measured). Without the flags, a fold is a recreation.

| # | State | Lives in | Recreation | Fold | Process death | Vault lock | Notes · today |
|---|---|---|---|---|---|---|---|
| 1 | **Current workspace** (destination) | T1 top-level key | ✓ | ✓ | ✓ | ✓ | today `NavState` saveable; lost on lock (P0-11) |
| 2 | Per-destination history (list → item → linked item) | T1 sub-stacks | ✓ | ✓ | ✓ | ✓ | today `TabsState` (titles in Bundle) |
| 3 | **Active conversation** | T1 `ChatKey(chatId)` | ✓ | ✓ | ✓ | ✓ | Test B "no duplicate": the key is identity |
| 4 | New-chat landing (unsent) | T1 `NewChatKey` + draft "new" (#12) | ✓ | ✓ | ✓ | ✓ | no empty chat rows are created |
| 5 | **Selected document** | T1 `NoteKey` / `FileKey` | ✓ | ✓ | ✓ | ✓ | Test E |
| 6 | **Selected graph node** | T1 `GraphNodeKey(nodeId)` | ✓ | ✓ | ✓ | ✓ | Test F; today lost (P0-07a) |
| 7 | Graph focus (centre) | T1 `GraphKey(focusDocId)` | ✓ | ✓ | ✓ | ✓ | today `graphDocId` `remember` |
| 8 | Selected model details, settings category | T1 | ✓ | ✓ | ✓ | ✓ | — |
| 9 | **Context inspector open** + focused answer | T1 `ChatContextKey(chatId, focusMessageId?)`; section collapse state is T4 (session) | ✓ | ✓ | ✓ | ✓ | presentation per §2.5; a **peek** after a shrink or restore; today lost (P1-19) |
| 10 | Connections open | T1 `ConnectionsKey` | ✓ | ✓ | ✓ | ✓ | same presentation rule |
| 11 | Opened source (a cited note or file at its passage) | T1 `ChatSourceKey(chatId, docId, anchorId?)` | ✓ | ✓ (full-screen route ↔ extra pane) | ✓ | ✓ | dropped silently if the document was deleted (`OBJECT_LIFECYCLE_SPEC.md` §3.5) |
| 12 | **Draft text** + caret + pending attachments | T5 `DraftStore` (a `TextFieldState` per chat id or "new") + T7 **encrypted draft row** (B3 = `CHAT_UX_SPEC.md` P4) | ✓ | ✓ | ✓ (row) | ✓ (row flushed at LOCKING) | row written after about 2 s idle, on `ON_STOP`, and by the lock observer; deleted on send. Keystrokes after the last write can be lost only on a crash or kill without `ON_STOP`. Today lost (P0-07b) |
| 13 | **In-flight generation** (streaming turn + partial answer) | T5 **session-scoped turn controller** (`CHAT_UX_SPEC.md` bead C1), **never the composition** | ✓ | ✓ | ✗ nothing can run: the unanswered message shows `No answer was saved.` · **Try again** (`CHAT_UX_SPEC.md` §9.11) | ✗ the turn ends. With B2, it ends through the Stop path and the partial answer is **saved with the interrupted marker**; without B2, `No answer was saved.` · Try again | Test C. **Today:** Stop (`engine.cancel()` → `StopReason.CANCELLED`) saves the partial text with `INTERRUPTED_MARKER`, but leaving composition (fold or recreation, tab switch, citation tap, lock) cancels the collecting coroutine, and the persist block after `collect` never runs, so the partial answer is lost (`SendPipeline.kt:241-294`; `UX_AUDIT.md` Reconciliations) |
| 14 | Note editor unsaved keystrokes | T3 editor holder (`pendingBody`) → autosave into T7; flush-or-recovery-draft at LOCKING (E7.I4 design) | ✓ | ✓ | ~ (flushed on `ON_STOP`; the last debounce window only on a kill) | ✓ | today K-P0-9 (`flushAll` never called) |
| 15 | Editor caret, selection, scroll | T2 (ints) | ✓ | ✓ | ✓ | ✓ | — |
| 16 | Rename dialog (target + edited text) | T3 | ✓ | ✓ | ✗ | ✗ | text never goes into the Bundle |
| 17 | Delete confirmation (target) | T3 | ✓ | ✓ | ✗ | ✗ | the user re-initiates; safe for a destructive action |
| 18 | Knowledge search query | T3 | ✓ | ✓ | ✗ | ✗ | text |
| 19 | Knowledge filter chips | T2 (enum) | ✓ | ✓ | ✓ | ✓ | today reset (P0-07d) |
| 20 | Conversations list search query | T3 | ✓ | ✓ | ✗ | ✗ | text |
| 21 | **Palette open** + query + highlighted row | T4 | ✓ | ✓ (re-laid out full screen ↔ centred) | ✗ | ✗ | today the command bar desyncs (P0-08) |
| 22 | Attach picker open + query + checked items | T3 (chat entry) | ✓ | ✓ (re-presented) | ✗ | ✗ | committing moves the ids into the draft (#12) |
| 23 | **Active model selection** | the registry default, one for all chats in v1 (`CHAT_UX_SPEC.md` §16.3); per chat later (B11) | ✓ | ✓ | ✓ | ✓ | never UI state; a queued switch ("Switches after this answer") lives with the turn controller |
| 24 | **Persona** | T7 once personas exist (hidden in v1, IA §8a) | ✓ | ✓ | ✓ | ✓ | today there is no persona concept |
| 25 | **Attached context / active knowledge** ("2 notes · Knowledge on") | T7 per-chat context set (B10 = `CHAT_UX_SPEC.md` B9) | ✓ | ✓ | ✓ | ✓ | today only `[[…]]` text |
| 26 | Inspector content for *this* chat's focused answer | derived from that turn's `AssembledPrompt` per chat (`CHAT_UX_SPEC.md` §17.4, chat-side) | ✓ | ✓ | ~ (re-derived from persisted citations) | ~ | today session-wide `lastOutcome` leaks between chats |
| 27 | **Transcript scroll anchor** | T2: anchor message id + offset + a "following" flag | ✓ | ✓ | ✓ | ✓ | re-applied when data first arrives, **never by index**; today forced to the bottom (`MessageList.kt:58`) |
| 28 | List scroll (Conversations, Knowledge, Models, Settings) | T2 `LazyListState` | ✓ | ✓ | ✓ | ✓ | the list shows a loading state until the first emission, so the restore is not clobbered |
| 29 | **Activity-block expansion** | T2: a set of expanded message ids | ✓ | ✓ | ✓ | ✓ | — |
| 30 | Graph zoom and pan | T2 (floats, normalised to the focus) | ✓ | ✓ (selected node kept in view) | ✓ | ✓ | today lost |
| 31 | Graph layout positions | T3 graph holder; seeded layout (B12) | ✓ | ✓ | ~ (recomputed from the same seed) | ~ | — |
| 32 | User-dragged pane widths | T2 at shell level, per destination and mode | ✓ | ✓ (per mode: a width dragged on the inner display does not apply outside) | ✓ | ✓ | — |
| 33 | **Drawer open** | T8 + size-aware | ✗ (closed) | closes if the container changes; kept while it stays a drawer (outer portrait ↔ landscape) | ✗ | ✗ | Test G; today reopens (P2-05) |
| 34 | Model sheet (anchored panel on ≥ 600 dp), ⋮ menus, tooltips | T3 (panel) / T8 (menus) | ✓ if the width class is unchanged | **closes on a width-class change** (the anchor moved) | ✗ | ✗ | Test G |
| 35 | Composer focus | T3 flag | ✓ (re-requested once) | ✓ | ✗ | ✗ (no IME pop-up after unlock) | Test D |
| 36 | IME visibility | platform | — | not forced (§6.6) | — | — | Test D |
| 37 | **Model import** (multi-GB copy + hash) | T6 app-scoped job (B6) | ✓ | ✓ | ✗ → partial file cleaned at next start; "Import interrupted · Try again" | ✓ (the copy continues; registration at unlock through the existing rescue, skein-gg11.18) | today cancelled by a fold (P0-07c) |
| 38 | File import into Knowledge / composer attach import | T5 session job (B7) | ✓ | ✓ | ✗ | ✗ (cancelled; no partial document) | today composition-scoped; crashes on images (K-P0-7) |
| 39 | Indexing progress | WorkManager + session `StateFlow` (existing) | ✓ | ✓ | ✓ (re-enqueued at unlock) | ✗ by design (pauses; resumes at unlock) | — |
| 40 | Unlock prompt in progress | `BiometricPrompt` fragment | ✓ (fragment-retained) | ✓ (no recreation) | ✗ | — | — |
| 41 | Setup or recovery passphrase fields | composition only | ✗ **by design** (secrets are never saved) | ✓ | ✗ | — | — |
| 42 | Pending deep link (notification tapped while locked) | T4 | ✓ | ✓ | ✗ | applied after unlock (§8.7) | today ignored |

### 7.4 What the user sees on a transition

1. **The back stack does not change** on fold, unfold, rotation, resize or split. Only the *scene* is recomputed.
2. **Pane → sheet** (the window shrank): the extra or supporting surface appears as a **peek** (§2.2). For Chat, the peek replaces the context-chip row in place. **Sheet → pane** (the window grew): it becomes the pane. Test A's "secondary panes disappear appropriately" is met because the chat is the one dominant surface, while nothing is lost and it is one tap to return.
3. **Drawer ↔ rail:** the drawer is snapped closed (§3.3).
4. **Anchored panels and menus** (the Model sheet as an anchored panel, ⋮ menus, tooltips) **close** when the width class changes. The Model sheet as a Phone bottom sheet persists across a rotation that keeps the width class. **Dialogs, the palette and full-screen or centred pickers persist** and are re-laid out.
5. **Focus** is kept and the IME is not forced (§6.6). **Scroll:** the transcript anchor holds, and "following" stays following.
6. **The detail is not re-created.** Retained holders keep data, there is no loading flash, and a generation in flight keeps streaming into the same turn.
7. Motion is a cross-fade of ≤ 200 ms, or none when the animator scale is 0.

### 7.5 `android:configChanges`: the recommendation

```xml
<activity
    android:name="app.skein.MainActivity"
    android:exported="true"
    android:configChanges="screenSize|smallestScreenSize|screenLayout|orientation|keyboard|keyboardHidden|navigation">
    <!-- no "density": both Fold panels share one density (measured), so a fold never changes densityDpi -->
```

- **Required: the four size flags.** Android's own guidance ("Handle configuration changes → Restrict activity recreation") says that recreation for size changes is disabled with exactly these four. Skein qualifies as Compose-only: no `AndroidView`, no `AndroidFragment`, and no reader of `LocalConfiguration` that needs a manual refresh (`ANDROID_ADAPTIVE_SAMPLES.md` §4.3). The gain: fold, unfold, rotation, split-screen and free-form resize become recompositions. Generation, import, `BiometricPrompt` and animations continue, and nothing flashes.
- **Recommended: `keyboard|keyboardHidden|navigation`.** Android 17 no longer restarts for these by default, but minSdk is 30. On API 30–36 devices, attaching a hardware keyboard would recreate the Activity. Compose handles the change, and §6.5 depends on it.
- **Decided: no `density`.**
  - **The decision rule:** add `density` only if a fold changes `densityDpi`; otherwise a Display-size change is allowed to recreate the Activity.
  - **The measurement settles it** (`UX_AUDIT.md` Reconciliations). With the device **closed**, `wm density` reported physical 390 / override 330, the same values as the inner panel, so both panels share one density and a fold never changes `densityDpi`. A user-initiated Display-size change still recreates the Activity. That is rare, and §7.6 covers it.
  - **Revisit only if** a future OS release introduces a separate display size per panel, or Skein targets a device whose panels differ. Re-run the closed and open `wm density` reads first. If the values then differ, add `density` only after these three checks pass:
    - (a) **JVM:** a Robolectric `ActivityController.configurationChange(config)` with only `densityDpi` changed keeps the same Activity instance, and Roborazzi captures after the flip equal a cold start at the target density (0-pixel diff).
    - (b) **Code audit:** no `remember {}` that captures px values from `LocalDensity` without a density key, no `imageResource` or `ImageBitmap` cached across a density change, and no `Resources.displayMetrics` reads.
    - (c) **Device:** 10 fold/unfold cycles, compared against cold-start captures.
- **Guard:** extend `app/src/test/kotlin/app/skein/ManifestPolicyTest.kt` to assert the flag set exactly. Add a Robolectric test in which `ActivityController.configurationChange(config)` for a size-only change (443 × 994 → 1006 × 1043 qualifiers) keeps the **same Activity instance**.
- **Not declared:** `uiMode`, `fontScale`, `locale` and `layoutDirection`. Those recreations are rare, and the state in §7.3 survives them. Do not declare `resizeableActivity=false` or `screenOrientation`: Android 16+ ignores them on large screens at target 37, so **Skein cannot avoid being resized** (`ANDROID_ADAPTIVE_SAMPLES.md` §4.1).

### 7.6 What must survive recreation anyway

The flags are not a fix (Android: "Avoid opting out as a quick fix"). Recreation still happens for font scale, dark mode, locale, Display size, process death and task restore, and the "Don't keep activities" developer option. **Nor are the flags enough for an in-flight answer.** Even without recreation, anything that removes the chat from composition (navigating to a source, switching destination, a pane change) cancels a collector owned by the composition. The turn must therefore live in the session-scoped turn controller (§7.3 row 13). Every ✓ in the Recreation column of §7.3 is therefore a **test obligation**. §9 runs Tests A–G on both the live path and the recreation path. The pane re-parenting hazard also disappears: `NavDisplay` keys content by entry, not by call site (today `AdaptivePaneHost`'s `when` branches dispose the primary pane without any recreation, P0-10).

### 7.7 Vault lock and unlock, as the shell sees them

1. **LOCKING** (the `SessionState`/`LockObserver` registry, E3.I3b):
   - The draft store writes its encrypted rows (B3).
   - The editor flushes, or writes a recovery draft (E7.I4; `OBJECT_LIFECYCLE_SPEC.md` §6.3 flush rule (c)).
   - Turn controllers end their turns. With B2 they use the **Stop path** (`engine.cancel()` → `StopReason.CANCELLED` → the partial answer is saved with the interrupted marker), still inside the LOCKING window.
   - File imports are cancelled (B7).
2. **LOCKED:** `VaultGate` shows "Unlock Skein", and `NavDisplay` leaves composition. The session `ViewModelStore` is **cleared** (all entry `ViewModel`s receive `onCleared`), the shell's session holders are cleared, and the drawer is closed. **Kept:** the back stacks (T1) and the hoisted `SaveableStateHolder` (T2). Both hold ids and numbers only.
3. **Unlock:** the session opens, then the navigator **re-resolves every id** through a batch kind-and-existence lookup (B8). Entries whose id no longer resolves are **dropped silently**; the user already saw the delete (`OBJECT_LIFECYCLE_SPEC.md` §3.5). Draft keys (`NewChatKey`, `NewNoteKey`) are never stale. A deleted graph focus re-centres on the most recently opened document that still exists. The pending deep link, if any, is applied, then `NavDisplay` composes. Entries create fresh holders. Drafts come back from their rows, and scroll anchors are re-applied when data arrives. The user is where they were. This fixes P0-11's "lock resets the shell" without putting plaintext outside the vault (IA D7; security review requested).
4. **Idle lock while typing** (P0-11's other half) needs `UnlockManager.touch()` on user input (B5), and it must not fire mid-generation (`CHAT_UX_SPEC.md` B8). Without these, the restore above still works, but with the default policy the lock fires 5 minutes after unlock in the middle of use. The owner runs a 60-minute idle lock with screen-off lock off, yet a fold still came back locked (`DEVICE_BEFORE_PASS.md` row 10). The cause is unknown (`UX_AUDIT.md` open question 2). The §9.4 watcher's lock marker will show which path fires.

---

## 8. Navigation architecture

### 8.1 Shape

- **One top-level key plus one back stack per destination** (NiA's `NavigationState`): Chat, Knowledge, Graph, Models, Settings. `NavDisplay` renders the current destination's stack. Switching destinations keeps every other stack intact.
- **Hoisted above `VaultGate`** in `MainActivity.setContent`, together with the entry `SaveableStateHolder` and the session store owner (§7.1). Today the whole shell sits inside `unlockedContent`, which is why a lock resets it (`MainActivity.kt:914-915`).
- **Skein owns the stack.** It is a `SnapshotStateList` that it can read, test and rewrite: "open chat 42 with its inspector" is one list assignment.
- **Keys live in a new `:core:navigation` module** (pure Kotlin plus kotlinx-serialization, already in the catalog). Features depend on keys, never on one another, and `:app` assembles the entry provider. This removes `SkeinApp`'s six content slots (`NOWINANDROID.md`; `SkeinApp.kt:56-157`). An `api`/`impl` split per feature is added only where a cycle actually appears.

### 8.2 Keys

All keys are `@Serializable` and implement `NavKey`. **Fields are ids and enums only** (§7.2). The scene key groups a destination's entries, so that a Knowledge detail never pairs with the Chat list.

| Key | Fields | Role metadata | Scene key | Notes |
|---|---|---|---|---|
| `ChatHomeKey` | — | `ListDetailSceneStrategy.listPane(detailPlaceholder = { NewChatLanding() })` | Chat | the Chat root; how it renders depends on the navigation container (below) |
| `NewChatKey` | — | `detailPane()` | Chat | a **draft key**: the landing, an unsaved new chat (`OBJECT_LIFECYCLE_SPEC.md` C1). The first send **replaces** it with `ChatKey(id)`. At most one per stack; never stale |
| `ChatKey` | `chatId` | `detailPane()` | Chat | — |
| `ChatContextKey` | `chatId`, `focusMessageId?` | `extraPane()` | Chat | the inspector, focused on the latest answer or on one answer (`CHAT_UX_SPEC.md` §17.2); changing the focus replaces it |
| `ChatSourceKey` | `chatId`, `docId`, `anchorId?` | `extraPane()` | Chat | the cited note or file, opened by kind at the passage (`CHAT_UX_SPEC.md` §17.6). One pane: a full-screen route (**not** a sheet). Two panes: the extra pane beside the chat |
| `KnowledgeHomeKey` | `filter: KnowledgeFilter` | `listPane(detailPlaceholder = { KnowledgeEmptyDetail() })` | Knowledge | root |
| `NoteKey` | `docId`, `anchorId?` | `detailPane()` | Knowledge | open by kind |
| `NewNoteKey` | `draftId` (a pre-minted UUIDv7) | `detailPane()` | Knowledge | a **draft key**: the row is created at the first non-blank commit (`OBJECT_LIFECYCLE_SPEC.md` §6.2). With no row, it restores as an empty draft; never stale |
| `FileKey` | `docId`, `anchorId?` | `detailPane()` | Knowledge | a file viewer (extracted text at minimum; never an empty editor, K-P0-3) |
| `ConnectionsKey` | `docId` | `extraPane()` | Knowledge | — |
| `GraphKey` | `focusDocId?` | `SupportingPaneSceneStrategy.mainPane()` | Graph | root; "Centre here" **replaces** it |
| `GraphNodeKey` | `focusDocId?`, `nodeId` | `supportingPane()` | Graph | selecting another node replaces it; Back deselects |
| `ModelsHomeKey` | — | `listPane(detailPlaceholder = { ModelsEmptyDetail() })` | Models | root |
| `ModelDetailsKey` | `modelId` (opaque; B9) | `detailPane()` | Models | — |
| `SettingsHomeKey` | — | `listPane(detailPlaceholder = { AppearanceCategory() })` | Settings | root |
| `SettingsCategoryKey` | `category: SettingsCategory` | `detailPane()` | Settings | — |
| `PersonaKey` | `personaId` | `detailPane()` | Settings | hidden until `skein-3iw` |

**How the Chat root renders.** This is the one key whose single-pane rendering depends on the navigation container. It follows IA §3.4, `CHAT_UX_SPEC.md` §12.1 and `OBJECT_LIFECYCLE_SPEC.md` §4.7:

| Mode | `[ChatHomeKey]` shows | Where the chat history is | Back from a conversation |
|---|---|---|---|
| **Phone / Short** (drawer) | **the landing** (it shares the "new" draft with `NewChatKey`) | the drawer's Chats section | → the landing → leave the app |
| **Single** (rail, one pane) | **the Conversations list**, full width | this screen | → Conversations → leave the app |
| **Dual / Triple** | Conversations list pane │ landing placeholder | the list pane | → `Conversations │ landing` → leave the app |

The stack is identical in every mode. Only the rendering differs, so a fold never changes what is open. The landing's draft, focus and Recent list are keyed by "new", not by the composable that happens to show them.

The metadata helper and parameter names are those of `adaptive-navigation3` 1.3.0 (`ListDetailSceneStrategy.kt` L236–261, per `ANDROID_ADAPTIVE_SAMPLES.md` §4.2). The spike confirms the exact names, including how a scene key is passed.

### 8.3 Navigator rules (pure Kotlin, unit-tested)

1. **Go to** (from the palette, drawer history, "Manage models ›", a deep link, "Open in Graph", or a chat row in Connections) **switches to the object's home destination** and makes the object that stack's detail. Chat and new chat go to Chat. Note and file go to Knowledge. Model goes to Models. Setting goes to Settings.
2. **Follow** (links inside content: a wikilink, a citation or inspector passage (as `ChatSourceKey`), "Model details ›" in the Model sheet, "Open" on a graph node) **pushes onto the current stack**, so Back returns to the same place and scroll position (IA §3.8; `CHAT_UX_SPEC.md` §6.7). A followed key whose scene key differs from the current scene renders single-pane, with a readable column and ←.
3. **Selecting from a visible list replaces the detail.** It does not accumulate history, and Back goes to the list or root. Selecting while a detail is the only visible pane (Phone or Single) pushes the detail onto the list.
4. **De-duplicate:** if the key is already in the current stack, pop back to it instead of pushing a second copy (Test B "no duplicate chat"). There is one `NewChatKey` per stack.
5. **New chat (✎, Ctrl+N):** pop the Chat stack to `[ChatHomeKey]`, push `NewChatKey`, and focus the composer. **Re-selecting the current destination** in the rail or drawer pops that stack to its root.
6. **Back:** §3.6. `NavDisplay` handles Back only when its stack has more than one entry. Two mechanisms make the rest exact:
   - **(a) An elided view of the stack.** `NavDisplay` is given `nav.visibleStack(layout)`, a derived view of the saved stack. It elides entries that would render identically to the entry below them: today that is only a trailing `NewChatKey` over a root that already renders the landing (Phone, Short, Dual, Triple). The saved stack is untouched, so unfolding to a Single window shows the landing over the Conversations list again. Back on a Phone landing therefore sees a one-entry stack and is **not consumed**, and the system plays its predictive back-to-home. The draft lives under "new" in the draft store, so eliding the entry loses nothing.
   - **(b) A shell handler for destination roots.** `NavigationBackHandler(isBackEnabled = nav.topLevel != Chat && nav.currentStack.size == 1)` switches to Chat. At the Chat root nothing is enabled, so the system handles Back.
7. **Sanitise:** after unlock and after process-death restore, **silently** drop keys whose ids no longer resolve or whose kind changed (B8; `OBJECT_LIFECYCLE_SPEC.md` §3.5).
   - Draft keys are never dropped.
   - A deleted graph focus re-centres on the most recently opened document that still exists, and falls back to the Graph empty state.
   - A deleted selected node closes its detail.
8. **Delete** (the prune API is `OBJECT_LIFECYCLE_SPEC.md` LC-20, delivered in AL-06/AL-08): remove **every** entry whose key names the id, from every stack, including the pane entries (inspector, source, Connections, node detail). Then show the new top.
   - Deleting the open chat in any mode leaves `[ChatHomeKey]`: the landing on Phone, and `Conversations │ landing` on Dual. It **never opens a different chat** (`CHAT_UX_SPEC.md` §15.4, `OBJECT_LIFECYCLE_SPEC.md` §4.7).
   - A fold right after a delete changes nothing, because the stack is already pruned.

### 8.4 Scene strategies and the directive

Chain, first match wins:

1. **`SkeinSheetSceneStrategy`** (Nav3 `OverlayScene`, following the bottom-sheet recipe) is active **only when `layout.maxPanes == 1`** and the top entry's presentation (§2.5) is a sheet: the inspector, Connections or node detail. It renders the entry as a bottom sheet, side sheet or peek, with the entries below as `overlaidEntries`. `ChatSourceKey` is not a sheet. On one pane it falls through to step 2, where the list-detail scene shows the current (extra) destination as the single full-screen pane.
2. **`rememberListDetailSceneStrategy(directive = layout.directive, …)`** handles Chat, Knowledge, Models and Settings, with `BackNavigationBehavior.PopUntilScaffoldValueChange` and the drag handle per §4.0.
3. **`rememberSupportingPaneSceneStrategy(directive = layout.directive, …)`** handles Graph.
4. The default single-pane scene handles followed keys outside their scene.

The **directive** is always Skein's (§2.3): Material's directive with the height gate, the guard and the side-pane width applied. The strategies are never left to compute their own. Material's alternative for step 1 is `AdaptStrategy.Levitate(…).onlyIfSinglePane(directive)` for the extra pane (`ANDROID_ADAPTIVE_SAMPLES.md` §5.3). The spike tries it first. The peek-without-scrim state is the reason a custom overlay scene may be needed (gate criterion G3).

### 8.5 Entry decorators and scopes

`entryDecorators` are:

- `rememberSaveableStateHolderNavEntryDecorator(saveableStateHolder = hoistedHolder)` for T2;
- `rememberViewModelStoreNavEntryDecorator(viewModelStoreOwner = sessionStoreOwner)` for T3.

Both parameter names are to be verified in the spike. `lifecycle-viewmodel-navigation3` 2.11.0 matches Skein's lifecycle version. Entry holders are plain `viewModel { ChatViewModel(session…) }` factories: no Hilt (`NOWINANDROID.md`). Each entry is a stateful route over a **stateless screen** (`XxxScreen(uiState, callbacks)`), so previews and Roborazzi render from fixtures.

### 8.6 Back handling (navigation-event)

- **Stack pops:** `NavDisplay`'s built-in handling (navigationevent) gives predictive back. It pops sheets, extras and details per §3.6, over the elided `visibleStack` (§8.3 rule 6a). Destination roots other than Chat are handled by the shell handler in rule 6b. The Chat root is left to the system.
- **Transient overlays:**
  - The palette registers one `NavigationBackHandler(state = rememberNavigationEventState(…), isBackEnabled = palette.isOpen, onBackCompleted = palette::close)` at **overlay priority**.
  - The drawer uses Material's own back handling, with the `drawerState` overload of `ModalDrawerSheet`. Today's overload without state may not close on Back (`AUDIT_SHELL.md` §12 item 3).
  - Dialogs and popups dismiss through their own windows.
- **One handler per state.** Never override the Activity's dispatcher. Declare `navigationevent-compose` at the version the Nav3 dependencies resolve, which the skills assessment expects to be 1.0.1; do not bump it further (`ANDROID_SKILLS_ASSESSMENT.md` §6.5, §5).
- A **detail beside its list shows no ←**, and entries read `LocalSkeinWindowLayout` to decide.

### 8.7 Deep links and share targets

- **Existing notification intents** are explicit `MainActivity` intents with `FLAG_ACTIVITY_SINGLE_TOP` and data `app://skein/models` (`notify/ModelNotifier.kt:39-50`) or `app://skein/ingest` (`notify/IndexingNotifier.kt:70-81`). `MainActivity` handles neither `intent.data` nor `onNewIntent`, so today both are ignored.
  - **Spec:** a tiny parser with an **allowlist of paths**. `models` → go to Models. `ingest` → go to Knowledge, whose list header shows "Preparing for search…".
  - It is read in `onCreate` and `onNewIntent`, then stored as a **pending deep link** (T4). It is applied **only after unlock** (§7.7 step 3), and nothing is revealed before the gate.
- **Untrusted input.** `MainActivity` is exported (it is the launcher), so any app can send it an explicit intent with arbitrary data. Deep links carry **no ids and no actions** (never "delete" or "open doc X from outside"). Unknown paths are ignored silently.
- **Share targets:** none today. The manifest has no `ACTION_SEND` filter (K-P1-11). When one is added later ("Ask Skein about this", "Import to Knowledge"), it follows the same rules. The shared `Uri` or text is held **in memory** in an app-scoped holder (never in the Bundle or the back stack), a `ShareInboxKey(requestToken)` is pushed after unlock, and the grant is used before it expires. The key shape is reserved here; nothing is built now.

### 8.8 Wiring sketch (reference only)

```kotlin
// MainActivity.setContent — hoisted ABOVE the gate, so a lock never resets navigation.
setContent {
    val nav = rememberSkeinNavigationState()                  // top-level key + one NavBackStack<SkeinKey> per destination
    val entryState = rememberSaveableStateHolder()            // T2 (ids/numbers), survives lock
    val sessionStores = rememberSessionStoreOwner(vault.session) // T3/T4, cleared when the session closes
    LaunchedEffect(intent) { nav.offerDeepLink(intent.data) } // allowlisted; applied after unlock
    VaultGate(vault, …) { session -> SkeinShell(session, nav, entryState, sessionStores) }
}

@Composable
fun SkeinShell(
    session: VaultSession, nav: SkeinNavigationState, entryState: SaveableStateHolder, stores: ViewModelStoreOwner,
    info: WindowAdaptiveInfo = currentWindowAdaptiveInfoV2(),
    size: DpSize = LocalWindowInfo.current.containerDpSize,
) {
    val layout = skeinWindowLayout(info, size)
    LaunchedEffect(session) { nav.sanitise(session.repository) }            // B8
    CompositionLocalProvider(LocalSkeinWindowLayout provides layout) {
        SkeinNavigationSuite(layout, nav) {                                  // §3.2
            NavigationBackHandler(                                           // §8.3 rule 6b: destination root → Chat
                state = rememberNavigationEventState(NavigationEventInfo.None),
                isBackEnabled = nav.topLevel != TopLevel.Chat && nav.currentStack.size == 1,
                onBackCompleted = { nav.switchTo(TopLevel.Chat) },
            )
            NavDisplay(
                backStack = nav.visibleStack(layout),                        // §8.3 rule 6a: elided view; the saved stack is untouched
                onBack = { nav.back(layout) },                               // §3.6
                sceneStrategy = rememberSkeinSheetSceneStrategy(layout)
                    then rememberListDetailSceneStrategy(directive = layout.directive,
                        backNavigationBehavior = BackNavigationBehavior.PopUntilScaffoldValueChange)
                    then rememberSupportingPaneSceneStrategy(directive = layout.directive),
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(entryState),
                    rememberViewModelStoreNavEntryDecorator(viewModelStoreOwner = stores),
                ),
                entryProvider = skeinEntries(session, nav),                  // assembled in :app from feature providers
            )
        }
    }
}
```

**Deleted by this design:** `feature/shell/…/layout/{AdaptivePaneHost, AdaptiveLayoutState, PaneLayout, FoldPosture, IconRail, SplitHost}.kt`, `tabs/*`, `split/*`, `nav/{NavState, NavDrawer, CommandBar*}.kt`, `SkeinApp`'s slot parameters, `TimelineRail`, and most of `MainActivity`'s 1,037 lines of wiring. Tests that lock in current defects change with them (IA §6.2).

### 8.9 The D8 prototype gate: Navigation 3 or the fallback

**The spike (AL-05):**
- It is time-boxed to 4 working days, on a throwaway branch.
- It re-hosts today's `ChatScreen`, one note and `GraphScreen` behind `NavDisplay`, using the keys and strategies above.
- It runs every criterion below and records the results in a table appended to this section.

| # | Criterion | Pass if | Measured by |
|---|---|---|---|
| G1 | Fold Tests A–G at the JVM level | **All 7 pass**, on both the live-flip path and the recreation path | the §9 JVM suite, run against the prototype |
| G2 | Nothing is re-created on a flip | the `ChatViewModel` and turn-controller instance ids are unchanged across 443 ↔ 1043 flips; 0 extra collector subscriptions | instance-identity counters in the test harness |
| G3 | The Phone presentation of extra entries | bottom sheet with scrim when opened; **peek without scrim after a shrink**; Back pops; the chat stays interactive under the peek. Achieved with ≤ 150 lines of Skein code and **no fork** of `adaptive-navigation3` | JVM semantics assertions + Roborazzi captures |
| G4 | The Skein directive is honoured | a custom `PaneScaffoldDirective` passed to the strategies yields 1 pane at 994 × 443 and 1175 × 524, 2 panes at 852 × 883, the list yielding on Dual, and 3 panes at 1280 × 800 | truth-table rows 5, 6, 7, 24 through the real scene |
| G5 | Back order | 12 scripted Back/Esc sequences match §3.6, including "no Back without a visible change" and a predictive-back *cancel* that leaves the state unchanged | JVM + one device check |
| G6 | Process-death restore and privacy | identical back stacks after a restore from a captured Bundle; the Bundle privacy scan finds 0 sentinels | §9.3 |
| G7 | Survives a vault lock | stacks and T2 survive lock and unlock; every entry `ViewModel` receives `onCleared` on lock; drafts return from the (fake) row | fake `LockObserver` in the JVM harness |
| G8 | Transition cost | on the Fold, over 10 fold/unfold cycles: **no dropped-frame regression** against the current build (`dumpsys gfxinfo app.skein framestats` 90th percentile), and no empty-state flash in the watcher captures | device (AL-17 watcher) |
| G9 | Dependency cost | only `navigation3-runtime`/`-ui` 1.2.0, `lifecycle-viewmodel-navigation3` 2.11.0, `material3-adaptive-navigation3` 1.3.0 and `material3-adaptive-navigation-suite` 1.4.0 (+ the `navigationevent-compose` 1.0.0 → 1.0.1 bump). All androidx, Apache-2.0. They pass `LicenseAudit` and `checkDependencyGuards`, and verification-metadata is updated. Release APK delta ≤ 400 KB after R8 | build |
| G10 | Code and test lane | runs under the existing Robolectric `@Config(sdk = [34])` lane; the shell layer's line count goes **down** (the files listed in §8.8 are deleted) | CI + `git diff --stat` |

**Decision rule:**
- **Nav3** if G1–G7, G9 and G10 pass. G8 may be fixed forward.
- **Fallback** if any of G1, G3, G4, G6 or G7 fails and cannot be fixed without forking the library or writing more than 300 lines of workaround.

**The fallback is not Navigation Compose 2.x.** Nav Compose hides the back stack, needs a second navigator inside each list-detail destination, and has no native scenes (`ANDROID_ADAPTIVE_SAMPLES.md` §4.2). The fallback is a **minimal hand-rolled back stack**:

- the same `SkeinKey`s and the same `Navigator` rules and tests, in a `SnapshotStateList<SkeinKey>` per destination, saved with a `Saver` that serialises keys (ids only);
- rendered by Material's `NavigableListDetailPaneScaffold` and `SupportingPaneScaffold`, with navigators derived from the stack;
- pane content wrapped in `movableContentOf`;
- a `SaveableStateHolder.SaveableStateProvider(key)` per key;
- a small per-key `ViewModelStore` map under the same session owner.

Because the keys, rules and tests are written against `SkeinNavigator` over `List<SkeinKey>`, **the gate decides only the rendering layer.** AL-06 (keys and rules) proceeds either way.

---

## 9. Acceptance tests A–G

### 9.1 Levels and harness

| Level | Where | What it proves | Limits |
|---|---|---|---|
| **JVM** | Robolectric `@Config(sdk = [34])`, `@GraphicsMode(NATIVE)`, the existing lane. The Wave-3 harness includes a `SkeinShellHarness` (bead AL-15) with `InMemoryVaultRepository` fixtures, a **slow fake `SendPipeline`** (one token per 50 ms, 200 tokens), a fake `LockObserver`, a fake draft-row store, and an injectable `WindowAdaptiveInfo` and posture | behaviour, state, back order and privacy; fast, on the Mac and in CI | no IME window, no real hinge, zero insets |
| **Emulator** | Pixel 9 Pro Fold AVD (profile `pixel_9_pro_fold`, an AOSP or `default` image where available; if only a Google-APIs image offers the profile, that is acceptable for layout because Skein uses no GMS; record which image). The Espresso **Device API** (`androidx.test.espresso.device`: `onDevice().setClosedMode()`, `.setFlatMode()`, `.setTabletopMode()`, `.setBookMode()`, `.setScreenOrientation(…)`) needs `testOptions.emulatorControl.enable = true`. Nightly `emulator.yml` gets a fold job (today it is API 35 `google_apis` with no profile) | real IME, real insets, real posture events, real configuration changes | emulator display metrics, not GrapheneOS |
| **Owner's device** | the physical Pixel 9 Pro Fold, GrapheneOS, 330 dpi; a **physical** fold by hand; the **read-only watcher** in §9.4 | the real thing: panels, density, keyguard interplay, hardware keyboard | serial; `needs-hardware`, `needs-human-review` |

**Common fixture** ("Fold fixture"):
- a chat `c1` "Fold test" with 12 turns, 3 of them with citations to notes `n1` and `n2`;
- `n1` and `n2` attached to `c1`;
- the model "Qwen 2.5 3B" (fake engine in JVM and emulator tests);
- a draft `draft that should survive a fold ✓` with the caret at 10;
- the transcript scrolled so that turn 5 is the anchor (not at the bottom);
- one expanded activity block (turn 7).

The **live flip** is written like this:

```kotlin
var size by mutableStateOf(DpSize(1043.dp, 1006.dp))
compose.setContent { DeviceConfigurationOverride(DeviceConfigurationOverride.WindowSize(size)) { harness.Shell() } }
// … act …
size = DpSize(524.dp, 1175.dp); compose.waitForIdle()   // then repeat with 443 × 994
```

### 9.2 The tests

Each test lists its **steps** and then its **expected** results. "State intact" means all of the following hold:
- the back stack equals the expected list;
- the draft text and selection are unchanged;
- the transcript anchor (turn 5) is the first fully visible item;
- the activity block of turn 7 is still expanded;
- the chat's `ViewModel` instance id is unchanged on the live path;
- the number of chat rows in the repository is unchanged.

#### Test A — Open → closed (chat + inspector + knowledge context + draft)

- **JVM A1 (live):**
  - Steps: start at 1043 × 1006 with `[ChatHome, Chat(c1), ChatContext(c1)]`, with the inspector's Knowledge section showing `n1` and `n2`; flip to 524 × 1175; flip back; repeat with 443 × 994.
  - Expected after folding:
    - drawer mode (☰ shown, no rail tag);
    - exactly one pane, the chat;
    - the inspector **not** shown as a pane; the peek row reads "Context · 2 notes · 3 sources ▴" and there is no scrim;
    - state intact; no new chat row; no crash.
  - Expected after unfolding again: `Chat │ Context` again. Back once gives `Conversations │ Chat`.
- **JVM A2 (recreation):** the same, with the flip replaced by `ActivityScenario.recreate()` at the new qualifiers. Expected: state intact, **including** the `ViewModel` instance id. The Activity is new, but the session store owner lives in an Activity-level `ViewModel` that survives recreation (§7.1).
- **JVM A3 (process death):**
  - Steps: capture `onSaveInstanceState`; tear down the fake session; create a new Activity from the Bundle with a fresh session; unlock.
  - Expected: the stack is restored; the draft comes from the fake row; the inspector shows as a peek; **the Bundle privacy scan is clean** (§9.3).
- **Emulator:**
  - Steps: `setFlatMode()` + landscape; seed through the UI (open the chat from the Conversations pane, type the draft, open the inspector from the chip); then `setClosedMode()`.
  - Expected: as A1, asserted with Compose semantics on the device. `ActivityScenario.onActivity` reports the **same Activity instance** (proves `configChanges`). Screenshots are taken of both states.
- **Device:**
  - Steps: with the watcher running, the owner seeds the state by hand on the inner display in landscape grip, then folds.
  - Expected:
    - the outer capture shows a single chat pane with the peek row;
    - the `uiautomator` dump contains the draft text in the composer node;
    - logcat shows **0 new `MainActivity.onCreate`** lines (a debug-only marker) and 1 `onConfigurationChanged`;
    - no lock event (debug lock-state marker).

#### Test B — Closed → open (active conversation, no duplicate, supporting panes appear)

- **JVM B1 (live):**
  - Steps: start at 443 × 994 with `[ChatHome, Chat(c1)]`, the draft typed and the transcript anchored; flip to 1006 × 1043.
  - Expected:
    - `Conversations │ Chat(c1)` with row `c1` selected;
    - **exactly one** chat pane and one `c1` row;
    - state intact.
  - Variant: if the peek was showing before the flip, the inspector becomes the extra pane (`Chat │ Context`).
- **JVM B2 and B3:** recreation and process death, as in Test A.
- **Emulator:** `setClosedMode()`; start a conversation through the UI; `setFlatMode()`. Assertions as B1; same Activity instance.
- **Device:** start folded with a chat open and a draft; unfold. Check the inner-display capture and dump.

#### Test C — While generating

- **JVM C1:**
  - Steps: send a prompt; at token 40, flip 1043 → 443 → 1043 → 524, with 30 tokens between flips.
  - Expected:
    - the **same turn id** keeps streaming;
    - the final message holds all 200 tokens and has no "interrupted" marker;
    - Stop (■) stays visible throughout;
    - one collector subscription;
    - if the transcript was following, it stays at the bottom; if the user had scrolled up, the anchor holds and jump-to-latest shows.
- **JVM C2 (recreation mid-stream):** the same result, because the turn controller is session-scoped (`CHAT_UX_SPEC.md` C1).
- **JVM C2b (navigation mid-stream):**
  - Steps: while streaming, tap a citation (`ChatSourceKey` is pushed), wait 30 tokens, press Back. Separately, switch to Knowledge and back.
  - Expected: the turn is uninterrupted in both cases.
  - This is the case that loses the answer today: leaving composition cancels the collector, and the persist block never runs (`SendPipeline.kt:241-294`).
- **JVM C3 (lock mid-stream):**
  - With B2: the turn ends through the Stop path, and after unlock the chat shows the partial answer with its interrupted marker and **Try again**.
  - Without B2: `No answer was saved.` · **Try again**.
  - In both cases nothing streams after the lock, and the vault closes only after the turn has ended (fake `LockObserver` ordering).
- **JVM C4 (process death mid-stream):**
  - Expected: after restore and unlock, the unanswered message shows `No answer was saved.` · **Try again** (`CHAT_UX_SPEC.md` §9.11).
  - Expected: no empty assistant row, and no crash.
- **Emulator:** the debug fake engine flavour; `setClosedMode()` and `setFlatMode()` during streaming.
- **Device:**
  - Steps: send a real prompt with a long expected answer; fold during prefill, then again mid-stream.
  - Expected: the answer continues and completes.

#### Test D — Keyboard active

- **JVM D1:**
  - Steps: focus the composer and type; flip sizes.
  - Expected:
    - `assertIsFocused()` on the composer;
    - text and selection intact;
    - the composer's bounds are inside the window and above the (zero) bottom inset.
  - The IME itself is not simulated on the JVM. A Jetchat-style `keyboardShownProperty` semantics key is used where a test needs to assert Skein's *intent*.
- **Emulator:**
  - Steps: bring up the real IME; `setClosedMode()`, then `setFlatMode()`.
  - Expected:
    - the composer is visible and not overlapped by the IME window (compare the composer's bounds with the IME's inset);
    - draft, caret and focus intact;
    - if the IME was dismissed, **one tap** re-shows it;
    - Skein never force-shows it;
    - no transcript jump.
- **Device:**
  - Repeat with the soft keyboard on both displays, in portrait and in outer landscape. The landscape run is a short window: **measure the visible transcript height** with the IME up, which decides §13 item 5.
  - Repeat with the **hardware keyboard attached**, typing continuously across the fold. Expected: no lost keystroke.

#### Test E — Knowledge (document selected)

- **JVM E1:**
  - Steps: start at 1006 × 1043 with `[KnowledgeHome, Note(n1), Connections(n1)]`; the caret is mid-text; one keystroke is pending inside the autosave debounce. Flip to 443 × 994, then back.
  - Expected when folded:
    - the note is a single pane with ←;
    - Connections is a peek;
    - caret and scroll intact;
    - the pending keystroke is neither lost nor duplicated (it is saved once).
  - Expected when unfolded: `Note │ Connections`. Back → `list │ note`, with the list selection on `n1`.
  - Recreation, process death and lock variants per §7.3 rows 14–15.
- **Emulator and device:** as in Test A, with the note.

#### Test F — Graph (node selected)

- **JVM F1:**
  - Steps: `[Graph(n1), GraphNode(n1, n7)]` at 2× zoom and panned; flip to 443 × 994, then back.
  - Expected when folded: the canvas at full width; node `n7` highlighted **and inside the visible canvas**; node detail as a peek.
  - Expected when unfolded: `Canvas │ Node(n7)`; the zoom level is preserved.
  - Recreation and process-death variants: identical (T1 + T2).
- **Emulator and device:** as in Test A.

#### Test G — Drawer and sheet state

- **JVM G1:**
  - Steps: at 443 × 994, open the drawer; flip to 1006 × 1043; then back.
  - Expected: after the unfold, the rail is visible and the drawer is **not open**; after the refold, the drawer is **closed**.
- **JVM G2:**
  - Steps: at 1006 × 1043, open the Model sheet (an anchored panel); flip to 524 × 1175. Also at 524 × 1175, open it (a bottom sheet) and flip to 1175 × 524.
  - Expected, first case: the anchored panel is closed (the width class changed); no crash; the model selection is unchanged.
  - Expected, second case: the Phone bottom sheet turns into an anchored panel, because the width goes from < 600 to ≥ 600 dp. The width class changed, so it closes too. The user reopens it with one tap.
- **JVM G3:**
  - Steps: open the palette; type `fold`; highlight row 2; flip both ways.
  - Expected: the palette is re-laid out (full screen ↔ centred) with the same query and the same highlighted row.
- **JVM G4:**
  - Steps: open a Delete confirmation; flip.
  - Expected: the dialog persists, re-centred, with the same target.
- **JVM G5:**
  - Steps: at 443 × 994, open the attach picker with 2 items checked; unfold.
  - Expected: it becomes the centred panel with the same 2 items checked.
- **JVM G6:**
  - Steps: open the drawer; recreate.
  - Expected: the drawer is closed.
- **Emulator and device:** G1–G3 by hand and through the Device API. Expected: no drawer reopens and no orphaned scrim is left behind.

### 9.3 Supporting tests (in the same suites)

- **Truth table:** one test per row of §2.6. Input: `WindowAdaptiveInfo` + size + posture. Expected: nav, panes, side-pane width, and the presentation for each surface.
- **Back order:** 12 scripted sequences (§3.6), including Esc parity and "no invisible Back".
- **Bundle privacy:**
  - Steps: type unique sentinels into every text surface: composer, rename, Knowledge search, Conversations search, palette, attach search and the editor. Then `onSaveInstanceState`, and recursively flatten the Bundle (including nested Bundles, `Parcelable` string fields and serialised keys).
  - Expected: **no sentinel appears** and no title appears. This must fail against today's `TabsState` and `NavState` (a useful red-first check).
- **Key serialisation:** every `SkeinKey` serialises only fields named `*Id`, or enums.
- **Manifest:** the exact `configChanges` set, and the Robolectric same-instance test for size changes (§7.5).
- **Lock:** stacks survive; `onCleared` fires for every entry `ViewModel`; draft rows are written before the fake vault closes; after unlock, deleted ids are sanitised.
- **Deep links:** allowlisted paths navigate only after unlock; unknown paths and paths carrying ids are ignored.

### 9.4 The owner's-device watcher (bead AL-17)

`cmd device_state state <n>` is **not used**: it acts like a power-button sleep, brings up the keyguard, and locks the vault (`DEVICE_BEFORE_PASS.md`). The owner folds by hand. A read-only watcher on the Mac records every change.

**Preconditions:**
- Settings › Display › "Continue using apps on fold" is set to **Always**.
- In Skein › Settings › Security, "Block screenshots" is **off** for the session only. `screencap` is black under `FLAG_SECURE`, although `uiautomator dump` still works. It is switched back on afterwards.
- A **debug** build with:
  - `testTagsAsResourceId = true`, so Compose test tags appear in `uiautomator` dumps;
  - a `SkeinLog` marker on `MainActivity.onCreate` and `onConfigurationChanged` (an instance counter, no content);
  - a lock-state marker;
  - the hinge overlay (AL-19).
- **Test content only:** captures leave the device.

**Sketch of `tools/fold-watch.sh`** (written in AL-17; BSD `date` has no `%N`, so milliseconds come from Python):

```sh
OUT=${1:-fold-run-$(date +%Y%m%d-%H%M%S)}; mkdir -p "$OUT"
adb shell dumpsys SurfaceFlinger --display-id > "$OUT/displays.txt"   # physical display ids for screencap -d
INNER=…; OUTER=…                                                      # parsed from displays.txt
prev=""
while :; do
  st=$(adb shell cmd device_state print-state | tr -d '\r')          # read-only; e.g. OPENED / CLOSED / HALF_OPENED
  if [ "$st" != "$prev" ]; then
    ts=$(python3 -c 'import time; print(int(time.time()*1000))'); sleep 1.5   # settle
    for id in $INNER $OUTER; do adb exec-out screencap -p -d "$id" > "$OUT/$ts-$st-$id.png"; done
    adb shell uiautomator dump /sdcard/fw.xml >/dev/null && adb pull /sdcard/fw.xml "$OUT/$ts-$st.xml" >/dev/null
    adb shell dumpsys window displays | grep -E 'cur=|dpi' > "$OUT/$ts-$st-window.txt"
    adb logcat -d -s SkeinShell:D SkeinLock:D > "$OUT/$ts-$st-log.txt"
    prev=$st
  fi
  sleep 0.2
done
```

**A small checker** (Python, in the same bead) asserts on each `.xml` + log pair:
- the expected tags are present (`chat-pane`, `inspector-peek` or `inspector-pane`, `rail` or `drawer-button`);
- the composer node's text equals the draft;
- no new `onCreate` line; no lock line.

It writes `report.md` with the images inline, which becomes the evidence attached to the bead. The same run records G8 (`dumpsys gfxinfo app.skein framestats` before and after 10 cycles) and the `wm density` reads in both states. They confirm the §7.5 decision on the build under test.

---

## 10. Screenshot states for Roborazzi

Tooling as in `ROBORAZZI_SPIKE.md`: the `UxDevice` table, `captureUx()`, `resizeScale 0.5`, ATF accessibility checks before each capture (the NiA pattern), and baselines recorded only by the owner or coordinator after visual review. Posture is injected through `WindowAdaptiveInfo`, because Robolectric has no `FoldingFeature`. The IME is not captured on the JVM; keyboard-up states come from the emulator lane.

### 10.1 Device matrix

| Device folder | Qualifiers | Mode | Status |
|---|---|---|---|
| `phone` | `w360dp-h800dp-port-xhdpi` | Phone | exists |
| `phone-land` | `w800dp-h360dp-land-xhdpi` | Phone (short) | **new** |
| `fold-outer` | **`w524dp-h1175dp-port-330dpi`** | Phone | **correct** the wrong `w411dp-h923dp-420dpi` to the owner's measured outer display |
| `fold-outer-stock` | `w443dp-h994dp-port-390dpi` | Phone | **new** (stock density; the narrowest Fold width) |
| `fold-outer-land` | `w1175dp-h524dp-land-330dpi` | Phone (short) | **new**: the owner's closed landscape. Material classes it as Medium height, and only the 600 dp gate makes it one pane |
| `fold-outer-land-stock` | `w994dp-h443dp-land-390dpi` | Phone (short) | **new** (the tightest height) |
| `fold-inner` | `w1006dp-h1043dp-port-330dpi` | Dual | exists |
| `fold-landscape` | `w1043dp-h1006dp-land-330dpi` | Dual | exists (primary target) |
| `fold-inner-stock` | `w852dp-h883dp-port-390dpi` | Dual (280 side panes) | exists for `shell-landing` only → **full set** |
| `fold-inner-medium` | `w791dp-h820dp-port-420dpi` | Single | **new** (decides the Knowledge Medium prototype) |
| `split-half` | `w517dp-h1006dp-port-330dpi` | Phone | **new** |
| `tablet-large` | `w1280dp-h800dp-land-mdpi` | Triple | **new** |
| `fold-landscape+tabletop`, `fold-inner+book` | the base device + an injected `Posture` with a hinge at 503 dp | Wave 3: guards; P2: tabletop layouts | **new** |

### 10.2 States

**Tier 1** (every device in §10.1 except the posture variants and the two `-stock` outer variants; light, with dark on `fold-outer` and `fold-landscape`):

| Destination | States |
|---|---|
| Shell | `landing-first-run` (no-model card) · `landing-returning` (Recent) · `nav-drawer-open` (Phone devices) / `nav-rail` (others) · `palette-empty` · `palette-query` |
| Chat | `chat-conversation` (long, citations, code block) · `chat-streaming` (activity live, ■) · `chat-inspector` (sheet / side sheet / extra pane / third pane per mode) · `chat-inspector-peek` (Phone, Short) · `chat-source-open` (full screen on one pane; extra pane on Dual) |
| Knowledge | `knowledge-list` · `knowledge-note` · `knowledge-connections` · `knowledge-file` |
| Graph | `graph-canvas` · `graph-node-selected` |
| Models | `models-list` (default + loaded + importing row, long names) · `models-details` |
| Settings | `settings-categories` · `settings-category` (Privacy & security) |
| Gate | `unlock` · `setup` |

**Tier 2** (Fold set: `fold-outer`, `fold-outer-stock`, `fold-outer-land`, `fold-landscape`, `fold-inner-stock`):
- `chat-model-sheet` · `chat-attach-picker` · `chat-activity-expanded` · `chat-empty-no-model`;
- `knowledge-empty` · `knowledge-search-no-results` · `knowledge-delete-confirm` · `knowledge-rename`;
- `graph-empty` · `models-empty` · `unlock-error`;
- `-font150` of `chat-conversation`, `knowledge-list`, `nav-drawer-open`, `palette-query` and `settings-category` on `fold-outer-stock` and `phone` (the narrowest widths);
- long-content fixtures (§45: long chat and note titles, long model names, long filenames, tables, nested lists) on `fold-outer-stock`.

`CHAT_UX_SPEC.md` §26 lists the chat-content fixtures. This section fixes the devices and modes they are captured on.

**Posture variants:** `fold-landscape+tabletop` for `chat-conversation`, `chat-model-sheet` (top partition), `knowledge-delete-confirm` (bottom partition) and `graph-node-selected`. `fold-inner+book` for `chat-conversation` and `knowledge-note`.

### 10.3 Transition pairs

These are captured inside the §9.2 live-flip tests: `transition-<A…G>-before.png` and `-after.png`. The pairs are fold-landscape → fold-outer (A, C, D, E, F, G1) and fold-outer → fold-inner (B, G1 reverse). They show reviewers what a fold does, and they detect a regression that changes the post-fold layout.

The **budget** is about 30 states × 11 core devices, plus dark, font and posture subsets: roughly 420 PNGs at 0.5 scale, about 14 MB committed. The spike's 153 images measured 0.5–1 MB per device folder.

---

## 11. Backend asks

These are needs, not designs. Storage and APIs belong to the owning epics (prompt §1: no broad backend refactors). Where a sibling spec already files the same need, this table **cross-references it rather than filing it twice**.

| # | Ask | For (§7.3 row, test) | Size | Owning epic | Same as |
|---|---|---|---|---|---|
| **B1** | **The in-flight turn lives in a session-scoped turn controller** per chat (the `ChatScope` of `LOCK_POLICY_INDEXING.md` §4). It collects `SendPipeline.send(…)` independently of any composition and exposes a per-chat `StateFlow` of the turn state, with one collector per turn. It is **required**: today a cancelled collector skips the persist block and the partial answer is lost (`SendPipeline.kt:241-294`) | #13; Tests C1, C2, C2b; G2 | M | chat-side (UX epic, `feature/chat`) | **`CHAT_UX_SPEC.md` bead C1**: not a backend ask; listed because Wave 3 depends on it |
| **B2** | **Lock ends a turn through the Stop path.** At LOCKING, the lock observer calls `engine.cancel()` for each running turn and waits (within the flush budget) for `StopReason.CANCELLED`, so the existing persist-with-`INTERRUPTED_MARKER` block runs before the vault closes. **This changes `LOCK_POLICY_INDEXING.md`'s "partial output discarded" row** and needs E3 and security sign-off. Without it, the UI shows `No answer was saved.` · Try again | #13; Test C3 | S | E3.I3b (lock sequence) + the ask path | extends `CHAT_UX_SPEC.md` §9.11 |
| **B3** | An **encrypted draft row** per chat id (or the "new" draft key), holding the draft text and pending attachment ids. It is written by a session draft store (debounced, on `ON_STOP`, and by the lock observer), read on unlock or restore, and deleted on send. It could share `recovery_drafts` (`LOCK_POLICY_INDEXING.md` §4.3). **Owner sign-off: IA D7** | #4, #12; Tests A3, B3 | M (migration) | E2 vault + E3.I3b | `CHAT_UX_SPEC.md` **P4**; `OBJECT_LIFECYCLE_SPEC.md` C1 and the "Drafts" row (keyed by `chat_doc_id` with `ON DELETE CASCADE`) |
| **B4** | **Session lifecycle hooks for UI holders:** register UI flushers at LOCKING (drafts, `FlushRegistry.flushAll()`), and a "session closed" callback so the shell can clear its session `ViewModelStore` | §7.1, §7.7; G7 | S | E3.I3b `SessionState`/`LockObserver` | `OBJECT_LIFECYCLE_SPEC.md` §6.3 flush rule (c) |
| **B5** | **`UnlockManager.touch()` on user interaction** (idle measured from the last input, not from unlock), and **no idle lock mid-generation** | P0-11; §7.7 step 4 | S | E3.I3a | `CHAT_UX_SPEC.md` **B8** (the generation half) |
| **B6** | An **app-scoped model-import job**: Application scope, `StateFlow<ImportProgress>`, survives fold, recreation and lock (registration deferred to unlock through the existing rescue, skein-gg11.18), with partial files cleaned at the next start after a process death | #37; P0-07c | M | E4 models (`ModelManager`) / UX Wave 9 | — |
| **B7** | A **session-scoped file-import job** (Knowledge import, composer attach) off the composition scope, with progress and errors as state, and image and PDF failures reported rather than thrown (K-P0-7) | #38 | M | E2 import/export (`ImportService`) | — |
| **B8** | **Batch id resolution:** `kindsOf(ids) → Map<id, Kind?>` (null = deleted), cheap and content-free, so the navigator can sanitise and dispatch by kind after unlock or restore | §7.7, §8.3 rule 7 | S | E2 `VaultRepository` | serves `OBJECT_LIFECYCLE_SPEC.md` LC-20 (the restore filter) |
| **B9** | An **opaque model registry id** for `ModelDetailsKey`, if today's ids embed filename slugs (for example `qwen2.5-3b-instruct-abliterated-q3-k-m-2c5f9a121ae6`), so no filename-derived string reaches the Bundle | §7.2 | S | E4 models | — |
| **B10** | A **per-chat context set** (attached doc ids + mode + "Knowledge on or off") | #25 | M | RAG / chat pipeline | `CHAT_UX_SPEC.md` **B9** |
| **B11** | **Per-chat model** (later; v1 uses one default for all chats) and a persona binding once personas exist | #23, #24 | M | Models / E0.I13 | `CHAT_UX_SPEC.md` **B12** |
| **B12** | **A deterministic graph layout** (seeded per focus), so positions are stable across recreation and restore | #31 | S | E6 graph | — |

The inspector's per-chat scoping (§7.3 row 26) is chat-side work (`CHAT_UX_SPEC.md` §17.4) and is not listed.

---

## 12. Implementation beads

Sizes: S ≤ 1 day, M 2–4 days, L 5+ days (or time-boxed). Waves follow prompt §48. "2.5" is the IA §6.4 "hide the dead" slot. The coordinator files these beads; this spec only lists them.

| # | Title | Scope | Size | Wave | Depends on |
|---|---|---|---|---|---|
| **AL-01** | **Hide dead shell controls now** | Remove the 40 dp icon rail (all 5 buttons dead, P0-04). Remove the drawer's placeholder items Notes, Graph and Personas (P0-03, K-P0-6). Make the drawer's Timeline and Settings items clear the active tab so they stop being inert, or hide them while a tab is open. Replace "No tabs open — back to timeline" with neutral copy. Keep split and tabs working until Wave 3 replaces them; this bead only hides what does nothing. Coordinate the non-shell dead rows (Settings "Coming in v1.1", bead ids, the composer's `Create "x"` row) with their audits | S | 2.5 | — |
| **AL-02** | **Declare `configChanges` + manifest guard** | The flag set in §7.5 (no `density`); `ManifestPolicyTest`; the Robolectric same-instance test for a size change. An early win: overlays, import and the biometric prompt survive a fold even before Wave 3. Pane re-parenting (P0-10) and composition-owned collectors (§7.3 row 13) still drop chat state until AL-08 and AL-10 | S | 2.5 | — |
| **AL-03** | `windowSoftInputMode="adjustResize"` with device evidence | Its own bead per skills override 3; Test D on both displays | S | 3 | AL-02 |
| **AL-04** | **Window decision function** | `skeinWindowLayout()`, `presentationOf`, `partitionOf`, the constants (as tokens in `DESIGN_SYSTEM.md`), `SkeinPosture` from `windowPosture`; one test per §2.6 row; delete `PaneLayout.kt` and `FoldPosture.kt` | S | 3 | — |
| **AL-05** | **Nav3 prototype spike (D8 gate)** | A throwaway branch: keys + `NavDisplay` + the sheet, list-detail and supporting strategies over today's Chat, a note and Graph; run G1–G10; append the verdict to §8.9 | L (time-boxed 4 days) | 3 | AL-04 |
| **AL-06** | **`:core:navigation`: keys + `Navigator`** | The §8.2 keys (draft keys included), `SkeinNavigationState` (top level + one stack per destination), the §8.3 rules (mode-aware Back, go to vs follow, de-duplication, prune on delete, silent sanitise), unit tests, and the key-serialisation privacy test. **Delivers the navigation half of `OBJECT_LIFECYCLE_SPEC.md` LC-20** | M | 3 | AL-05 (either outcome) |
| **AL-07** | **Adaptive navigation container** | `NavigationSuiteScaffold` + `ModalNavigationDrawer` per §3.2; the size-aware drawer state; drawer contents with chat history (IA §3.4, `CHAT_UX_SPEC.md` §12); rail contents; top-bar conventions (§3.5); delete `NavDrawer`, `IconRail` and the command bar | M | 3 | AL-04; `DESIGN_SYSTEM.md` components (Wave 2) |
| **AL-08** | **`NavDisplay` shell host** | The scene chain (§8.4) with Skein's directive; entry decorators with the hoisted `SaveableStateHolder` and the **session `ViewModelStoreOwner`**; stacks hoisted above `VaultGate`; the lock and unlock sequence (§7.7); the peek-on-shrink rule; the Chat root's rendering by mode (§8.2) | L | 3 | AL-05 go, AL-06, B4 |
| **AL-09a** | **Re-host Chat and Knowledge as entries** | Conversations pane (from the timeline list pieces), the `NewChatKey` landing, `ChatRoute`, the inspector as `ChatContextKey`, an opened source as `ChatSourceKey`; the Knowledge list (timeline pieces, chats excluded), `NoteRoute`, `NewNoteKey`, a minimal `FileRoute`, `ConnectionsKey`; open by kind; gone states per `OBJECT_LIFECYCLE_SPEC.md` §3.5; delete `TabsState`, `TabHost`, `TabStrip`, `RecentDropdown`, `TimelineRail` and the `SkeinApp` slots | L | 3 | AL-08 |
| **AL-09b** | **Re-host Graph, Models and Settings as entries** | The Graph destination (centred on the most recent note, empty state) + `GraphNodeKey` select-then-open; Models list │ details; Settings categories │ category (split today's single screen); delete the overlays and `AdaptivePaneHost`/`SplitHost`/`SplitCoordinator` | M | 3 | AL-08 |
| **AL-10** | **Retained chat state** | `ChatViewModel` as an entry `ViewModel` over the session turn controller; the draft in the session `DraftStore` (+ the B3 row); a reverse-layout transcript with an anchor; delete the forced scroll; composer focus flag | M | 3 (hands over to Wave 4) | AL-09a; `CHAT_UX_SPEC.md` C1; B3 |
| **AL-11** | **Insets and IME ownership** | Remove the shell-level `safeDrawing`; per-pane insets (§6.2); composer IME ∪ navigation bars; palette, sheet and dialog rules; short-window IME geometry | M | 3 | AL-08 |
| **AL-12** | **Back and overlay handling** | `NavigationBackHandler` for the palette and overlays; the drawer's state overload; Esc parity; the 12 back-order tests; predictive back on pops | S | 3 | AL-08 |
| **AL-13** | **Transition presentation rules** | Peek after a shrink or restore; anchored panels close on a width-class change; dialogs, palette and pickers persist; Test G in full | S | 3 | AL-08 |
| **AL-14** | **Notification deep links** | `onNewIntent`; the allowlist parser; pending until unlock; tests | S | 3 | AL-06, AL-08 |
| **AL-15** | **Fold tests A–G: JVM** | `SkeinShellHarness`, the §9.2 JVM tests (C2b included), and the §9.3 supporting tests (truth table, back order, **Bundle privacy**, manifest, lock, deep links) | M | 3 | AL-08 … AL-14 |
| **AL-16** | **Fold tests A–G: emulator** | A Pixel 9 Pro Fold profile job in `emulator.yml`; the Espresso Device API; the same assertions, with the real IME | M | 3 | AL-15; `skein-k3b2` |
| **AL-17** | **Owner's-device fold watcher + runbook** | `tools/fold-watch.sh`, the checker, the evidence layout, the debug markers (`onCreate` count, lock state, hinge); run A–G and record G8 (`needs-hardware`, `needs-human-review`) | S | 3 (the Wave 3 validation gate) | AL-15 |
| **AL-18** | **Roborazzi matrix for the new shell** | The §10.1 device corrections and additions; posture injection; Tier 1 baselines; transition pairs. Tier 2 moves to Wave 11 | M | 3 (end) → 11 | AL-09a, AL-09b |
| **AL-19** | **Hinge guards + hinge debug overlay** | The §5.4 Wave 3 rules; the drag handle hidden in tabletop; the rail-intersection test; verify that flat means non-separating on the device | S | 3 | AL-08 |
| **AL-20** | Tabletop layouts (P2) | The §5.4 P2 table for Chat, Knowledge and Graph; flip `TABLETOP_LAYOUTS_ENABLED` | M | 8 (with Graph) | AL-19 |
| **AL-21** | Large/XL and pointer polish | Triple at Large verified on a desktop window; the expanded rail at XL; hover; right-click menus; mouse on the drag handle; shell key routing (§6.5) | M | 10 | AL-09a, AL-09b |
| **AL-22** | Knowledge two panes at Medium (prototype) | IA open question 1; decide from `fold-inner-medium` screenshots | S | 6 | AL-18 |
| **AL-23** | Inspector on Dual: yield the list, or a side sheet | IA open question 2; decide from screenshots | S | 7 | AL-18 |

**Critical path:** AL-04 → AL-05 (gate) → AL-06 → AL-08 → AL-09a → AL-10 / AL-11 → AL-15 → AL-17 (the Wave 3 "validate before proceeding" gate of prompt §48).

---

## 13. Issues with the IA

These are refinements, not reversals. Each is flagged so that the owner can overrule it. Items raised in an earlier draft of this spec that the IA's §8a revisions, `CHAT_UX_SPEC.md` or `OBJECT_LIFECYCLE_SPEC.md` have since settled (the Chat root on Compact, the landing as a draft key, how citations open) are implemented as those documents decide (§8.2, §4.2) and are not repeated here.

1. **The Chat root renders differently by container** (§8.2). It is the landing where the drawer holds the history (Phone, Short), and the Conversations list where a rail does (Single, Medium). The IA does not say what a Medium window's Chat root is. `CHAT_UX_SPEC.md` §12.1 says "Medium: the list pane reached by Back or the rail", and this spec makes that concrete without letting the back stack depend on the container.
2. **Large uses the collapsed rail; the expanded rail starts at XL.** IA §3.4 allows "expanded rail or permanent drawer" at ≥ 1200 dp. The guard shows that an expanded rail plus three panes leaves the chat below 360 dp between 1200 and about 1230 dp, and uncomfortably narrow up to about 1400 dp. The expanded rail therefore starts at 1600 dp.
3. **The peek after a shrink** (§2.2, §7.4) extends IA §3.3's "Compact: bottom sheet". An inspector (or Connections, or a node detail) that was open on the inner display reappears *collapsed* on the outer one, so that the chat stays the dominant surface (§22, Test A). This is compatible with `CHAT_UX_SPEC.md` §17.1, whose fold rule covers the other direction (sheet → pane).
4. **Two search affordances on Medium+ list screens.** The palette ⌕ in the detail pane's top bar (IA §3.3 as revised) sits alongside inline list filters ("⌕ Search chats", "⌕ Search notes and files"). Labels must keep them distinct: a list filter narrows the list, while the palette searches everything and runs commands. `DESIGN_SYSTEM.md` and the copy owners should confirm.
5. **Cross-spec: the transcript budget in short windows with the IME up.** `CHAT_UX_SPEC.md` §3.4 keeps a one-line header, the chip and a 3-line composer. On the stock 994 × 443 outer landscape with the soft keyboard, that leaves roughly 60–80 dp of transcript (about 110–130 dp at the owner's 524 dp height; §6.3). If the Test D landscape run confirms less than 120 dp, the proposed fallback is that **the header hides while the IME is up**, and the model ▾ remains reachable from the composer's ＋ menu. `CHAT_UX_SPEC.md` owns the call.
6. **Model details followed from Chat render single-pane.** IA §3.3 gives Model details as "Route / Detail pane". When it is followed from the Model sheet, it sits outside the Models scene, so it renders single-pane with ←, even on the inner display. Using "Go to Models" instead would lose the way back to the chat. "Manage models ›" in the same sheet is the go-to.
7. **Cross-spec: a partial answer at lock** (B2). `LOCK_POLICY_INDEXING.md`'s state table discards it. The Stop path already saves partial answers with an interrupted marker. Ending locked turns through the Stop path makes lock behave like Stop, and it needs E3 and security sign-off.

---

## 14. Open questions for the owner

1. **"Continue using apps on fold":** which setting do you use? The live-transition contract assumes "Always". With "Swipe up to continue" or "Never", a fold may turn the screen off. Your screen-off lock is off, yet a fold already came back locked once (`DEVICE_BEFORE_PASS.md` row 10), with the cause still unknown. The restore path (§7.7) brings you back to where you were in any case, but through the unlock screen.
2. **Drafts at rest (IA D7):** do you accept an encrypted draft row written after about 2 s idle, on backgrounding and at lock? It gives drafts that survive a lock or process death with no plaintext outside the vault.
3. **Navigation after a lock (IA D7):** do you accept that the back stack (ids only, no titles or text) is kept by the system across a lock, so that you return to the same chat or note after unlocking?
4. **A partial answer at lock (B2):** should locking mid-answer keep what was written so far, as Stop does, or discard it, as the lock design says today?
5. **Tabletop:** do you half-fold in your landscape grip to read answers? If so, the P2 tabletop layouts (AL-20) move up from Wave 8.

---

## Appendix A: Evidence index

| Claim | Evidence |
|---|---|
| Inner 1006/1043 dp at 330; stock 852 × 883 | `ANDROID_ADAPTIVE_SAMPLES.md` §1.1–1.2; `DEVICE_BEFORE_PASS.md` "Geometry" |
| Outer 524 × 1175 dp at 330 (measured closed), 443 × 994 at stock; both panels share one density | `docs/ux/UX_AUDIT.md` "Reconciliations" (`wm size` / `wm density` read with the device CLOSED) |
| `cmd device_state state` brings up the keyguard | `DEVICE_BEFORE_PASS.md` "Fold/unfold tests" |
| No `configChanges`; every fold recreates the Activity | `app/src/main/AndroidManifest.xml` (the `MainActivity` element); `AUDIT_SHELL.md` §5.1 |
| Pane re-parenting disposes content without recreation | `layout/AdaptivePaneHost.kt` (three `when` branches calling `RightZone`); `AUDIT_SHELL.md` P0-10 |
| The shell pads everything by `safeDrawing`, IME included | `SkeinApp.kt:296-301`; `layout/EdgeToEdge.kt` |
| The Bundle today holds tab titles and command text | `tabs/TabsState.kt:144`; `nav/NavState.kt:75` |
| In-flight answers are lost when the chat leaves composition; Stop saves the partial answer with `INTERRUPTED_MARKER`, but a cancelled collector skips the persist block | `AUDIT_CHAT_MODELS_SETTINGS.md` §4.10; `feature/chat/…/SendPipeline.kt:241-294`; `UX_AUDIT.md` "Reconciliations" |
| Compact header ✎; palette ⌕ only on Medium+; Model sheet; chat search via the palette on Compact; landing = unsaved new chat; source opening; landing after delete | IA §8a; `CHAT_UX_SPEC.md` §4.1, §11, §12, §15.4, §16, §17.6 |
| Stale ids pruned on delete and dropped silently on restore; draft keys never stale | `OBJECT_LIFECYCLE_SPEC.md` §3.5, §4.7, §6.2 (LC-20) |
| A lock resets the shell; the idle lock is never poked | `AUDIT_SHELL.md` §5.4, P0-11; `MainActivity.kt:914-915` |
| Partial output is discarded on lock by design; `ChatScope` | `docs/design/LOCK_POLICY_INDEXING.md` §4.2–4.3 and its per-state table |
| Notification deep links exist but are ignored | `app/src/main/kotlin/app/skein/notify/ModelNotifier.kt:39-50`, `IndexingNotifier.kt:70-81`; no `onNewIntent` in `MainActivity.kt` |
| Material directive: 1 · 1 · 2 · 3; Levitate / Reflow / Hide; `navigationSuiteType` | `ANDROID_ADAPTIVE_SAMPLES.md` §1.3, §3, §4.1 (checked in the released 1.3.0 / 1.4.0 source jars) |
| Nav3 1.2.0 + `adaptive-navigation3` 1.3.0 + `lifecycle-viewmodel-navigation3` 2.11.0 | `ANDROID_ADAPTIVE_SAMPLES.md` §4.2; `ANDROID_SKILLS_ASSESSMENT.md` §5 |
| Size-aware drawer; IME owned by the composer; reverse-layout transcript | `COMPOSE_SAMPLES.md` (JetNews, Jetchat) |
| Per-destination stacks; entry decorators; stateless screens; screen-size tests | `NOWINANDROID.md` |
