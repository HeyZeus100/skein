// skein-xtov.23.20 (ML-1, docs/ux/MAC_UX_LAB_PLAN.md §2.1, §8): the theme
// wrapper for previews. Debug-only (`src/debug`) — it never ships in a
// release build. Wraps `SkeinTheme`, which already follows
// `isSystemInDarkTheme()` in its default `SYSTEM` mode, so a preview's own
// `uiMode` (`SkeinDarkPreviews`' `UI_MODE_NIGHT_YES`) renders dark for free —
// no extra plumbing needed. What this adds: a fixed clock and a fixed
// locale, so two renders of the same preview state are identical regardless
// of the host machine's clock or region.
package app.skein.core.designsystem.preview

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import app.skein.core.designsystem.theme.SkeinTheme
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

/** `docs/ux/UX_TEST_PLAN.md` §6: the fixed "now" every deterministic fixture uses. */
val PREVIEW_FIXTURE_NOW: Instant = Instant.parse("2026-09-26T10:00:00Z")

/** The locale every [SkeinPreviewTheme] preview renders under. */
val PREVIEW_LOCALE: Locale = Locale.forLanguageTag("en-US")

/**
 * The clock a preview composable should read instead of [Clock.systemUTC] or
 * `System.currentTimeMillis()` for "now" (relative timestamps, "today"
 * headers, and so on). Nothing in `:core:designsystem` reads this yet; it is
 * provided so a future preview state can ask for "now" without becoming
 * non-deterministic under [SkeinPreviewTheme].
 */
val LocalPreviewClock = staticCompositionLocalOf<Clock> { Clock.systemUTC() }

/**
 * [SkeinTheme] for Compose previews/tooling (`docs/ux/MAC_UX_LAB_PLAN.md`
 * §2.1's "Theme and determinism" convention): same colours, type and shapes,
 * plus a pinned clock and locale. Only ever call this from a `src/debug`
 * preview — it is compiled into the debug variant alone, so a `src/main`
 * composable that referenced it would fail to compile in release.
 */
@Composable
fun SkeinPreviewTheme(content: @Composable () -> Unit) {
    val previewConfiguration = Configuration(LocalConfiguration.current).apply { setLocale(PREVIEW_LOCALE) }
    CompositionLocalProvider(
        LocalConfiguration provides previewConfiguration,
        LocalPreviewClock provides Clock.fixed(PREVIEW_FIXTURE_NOW, ZoneOffset.UTC),
    ) {
        SkeinTheme(content = content)
    }
}
