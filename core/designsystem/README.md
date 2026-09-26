# Skein Design System

Core theme, typography, spacing, shapes, motion, and icon system for the Skein Android app.

## Module contents

- **Theme tokens** (`app.skein.core.designsystem.theme.*`): Compose `ColorScheme`, `Typography`, `Shapes`, motion durations, spacing and layout scales
- **Icons** (`app.skein.core.designsystem.icons.SkeinIcons`): Material Symbols Outlined vector drawables
- **Markdown styling** (`app.skein.core.designsystem.theme.SkeinMarkdownStyle`): Inline and block text formatting for messages and notes
- **Multipreview annotations** (`app.skein.core.designsystem.preview.*`): Device-specific preview targets (Fold outer/inner, phone, Compact/Wide/Font scale variants)
- **Theme gallery** (`app.skein.core.designsystem.theme.ThemeGallery`): Token specimen preview, Roborazzi baseline reference

## Writing a preview

1. **Wrap in SkeinPreviewTheme** (debug-only in `src/debug`, see `SkeinPreviewTheme.kt`). It pins the clock to `PREVIEW_FIXTURE_NOW`, locale to `en-US`, and follows `isSystemInDarkTheme()`.

2. **Use stateless composables and fixture states**. Call `XxxScreen(uiState = …, onEvent = {})`, never a route that builds a ViewModel. Get state from `src/debug/<Surface>PreviewStates.kt` (e.g., `ChatPreviewStates.all["chat-activity-starting"]`). Fixtures come from `:testing-fakes` via `debugImplementation`.

3. **Choose an annotation**:
   - `@SkeinFoldPreviews`: Two real Fold windows (outer 524 dp, inner 1007 dp landscape, 330 dpi), light. Day-to-day.
   - `@SkeinDevicePreviews`: Four T1 devices (phone 360, fold outer 524, fold inner 852, fold inner 1007 landscape), light.
   - `@SkeinDarkPreviews`: Same four T1 devices, dark mode.
   - `@SkeinCompactPreviews`: Compact-class windows (split 320, phone 360, fold outer/inner at stock density, landscape variants), light.
   - `@SkeinWidePreviews`: Expanded and large windows, plus medium/large canaries, light.
   - `@SkeinFontScalePreviews`: 100 %, 150 %, 200 % text scaling on fold-outer-443, light.

4. **Name the preview** `private @<Annotation> fun <Surface><State>Preview()` keyed to the spec state id in PascalCase (e.g., `ChatActivityStarting`, `KnowledgeLimitSearchResults`), with `group = "<surface>"`.

## Shared state with Roborazzi

Every preview state comes from `src/debug/<Surface>PreviewStates.kt` `val all: Map<String, () -> <Surface>UiState>`, keyed by spec state id. The same `all` map is iterated by screenshot tests in `src/test/`, so previews and baselines stay in sync. No separate "lab states."

## Retiring old previews

Old previews in `src/main/` (e.g., `ChatScreenPreviews.kt`, `SettingsScreenPreviews.kt`) are **not deleted yet**. As each screen is redesigned in its wave, move or delete its previews to `src/debug` with the new multipreview annotations and stateless setup. See `docs/ux/MAC_UX_LAB_PLAN.md` §2.4 for the cleanup timeline per screen.
