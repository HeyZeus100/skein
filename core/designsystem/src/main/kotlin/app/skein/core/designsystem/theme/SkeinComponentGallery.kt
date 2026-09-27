// skein-xtov.23.11 (DS13, docs/ux/DESIGN_SYSTEM.md §15, §17): the Wave 2 exit
// evidence gallery. Extends [ThemeGallery] (still shown as its own section)
// with the "Tokens" row of §15's table — colour roles with contrast numbers,
// type scale, spacing and radius rulers, the full icon sheet — plus a small
// "Overlays" page for the three shadow-free defaults objects
// (SkeinMenuDefaults / SkeinSheetDefaults / SkeinDialogDefaults) and
// SkeinMarkdownStyle, none of which otherwise render anything of their own to
// screenshot.
//
// Every other component named in §15's table that lives in `:core:designsystem`
// (top app bar, list row, empty state, notice, status, search field,
// segmented control, focus ring, keycaps, the four chips, snackbar, the
// destructive dialog) already has a full per-state gallery recorded by
// SkeinComponentsScreenshotTest / SkeinInteractionPrimitivesScreenshotTest /
// SkeinOverlaysScreenshotTest (docs/ux/DESIGN_SYSTEM.md §15's own list) —
// this file does not re-capture them, only references them in the KDoc above.
// SkeinRenameDialog has no capture anywhere (known Robolectric ceiling,
// documented on SkeinOverlaysScreenshotTest): an OutlinedTextField inside an
// AlertDialog hangs `setContent`'s idle check under `@GraphicsMode(NATIVE)`.
package app.skein.core.designsystem.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.skein.core.designsystem.components.SkeinDialogDefaults
import app.skein.core.designsystem.components.SkeinMenuDefaults
import app.skein.core.designsystem.components.SkeinSectionHeader
import app.skein.core.designsystem.components.SkeinSheetDefaults
import app.skein.core.designsystem.icons.SkeinIcons
import app.skein.core.markdown.MarkdownAst
import app.skein.core.markdown.render.MarkdownRenderer
import java.lang.reflect.Modifier as ReflectModifier

/**
 * Which gallery page a screenshot test asks for (§15's "Tokens" row, split
 * into ten captures, and the overlays/markdown specimen). The Roborazzi
 * host's window is a fixed device size (Robolectric simulates a real
 * screen): a Compose node cannot be measured or painted taller than that,
 * `verticalScroll` included (it reports the *viewport* size, not the
 * content's), so one "everything" tokens page silently cropped past the
 * first screenful; a first split into four still clipped colour, type and
 * icons on phone's narrowest, most-wrapped case at font scale 200%; icons
 * needed thirds (some names wrap to 3 lines), colour and type only needed
 * halves — every page fits phone's 800 dp budget at that font scale now
 * (this bead's own recording is what caught all of it).
 */
enum class GalleryPage {
    TOKENS_THEME_SPECIMEN,
    TOKENS_COLOUR_1,
    TOKENS_COLOUR_2,
    TOKENS_TYPE_LARGE,
    TOKENS_TYPE_SMALL,
    TOKENS_SPACING,
    TOKENS_RADIUS,
    TOKENS_ICONS_1,
    TOKENS_ICONS_2,
    TOKENS_ICONS_3,
    OVERLAYS,
}

/**
 * The test tag a screenshot test captures ([androidx.compose.ui.test.onNodeWithTag],
 * not `onRoot()`): the same pattern `SkeinComponentsScreenshotTest` already
 * uses, so a capture reflects this node's own measured bounds rather than
 * the window's.
 */
const val GALLERY_TAG = "skein-component-gallery"

/** One page per [GalleryPage] — see the type's KDoc for why these are kept small. */
@Composable
fun SkeinComponentGallery(
    page: GalleryPage,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth().testTag(GALLERY_TAG), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (page) {
                GalleryPage.TOKENS_THEME_SPECIMEN -> ThemeGallery()
                GalleryPage.TOKENS_COLOUR_1 -> {
                    SkeinSectionHeader("Colour 1/2 — roles and measured contrast (docs/ux/DESIGN_SYSTEM.md §6.5)")
                    ColourSection(page1 = true)
                }
                GalleryPage.TOKENS_COLOUR_2 -> {
                    SkeinSectionHeader("Colour 2/2 — roles and measured contrast (docs/ux/DESIGN_SYSTEM.md §6.5)")
                    ColourSection(page1 = false)
                }
                GalleryPage.TOKENS_TYPE_LARGE -> {
                    SkeinSectionHeader("Type scale — display/headline/title (§3.2)")
                    TypeScaleSection(large = true)
                }
                GalleryPage.TOKENS_TYPE_SMALL -> {
                    SkeinSectionHeader("Type scale — body/label + mono (§3.2, §3.3)")
                    TypeScaleSection(large = false)
                }
                GalleryPage.TOKENS_SPACING -> {
                    SkeinSectionHeader("Spacing (§4.1)")
                    SpacingSection()
                }
                GalleryPage.TOKENS_RADIUS -> {
                    SkeinSectionHeader("Radius (§5.1)")
                    RadiusSection()
                }
                GalleryPage.TOKENS_ICONS_1 -> {
                    SkeinSectionHeader("Icons 1/3 — Material Symbols Outlined, fill 0/1 (§9)")
                    IconSection(third = 0)
                }
                GalleryPage.TOKENS_ICONS_2 -> {
                    SkeinSectionHeader("Icons 2/3 — Material Symbols Outlined, fill 0/1 (§9)")
                    IconSection(third = 1)
                }
                GalleryPage.TOKENS_ICONS_3 -> {
                    SkeinSectionHeader("Icons 3/3 — Material Symbols Outlined, fill 0/1 (§9)")
                    IconSection(third = 2)
                }
                GalleryPage.OVERLAYS -> OverlaysGalleryContent()
            }
        }
    }
}

/** The three shadow-free overlay-default objects (no composable of their own) and the markdown specimen. */
@Composable
private fun OverlaysGalleryContent() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SkeinSectionHeader("Menu — SkeinMenuDefaults")
        OverlaySpecimen(
            shape = SkeinMenuDefaults.shape,
            container = SkeinMenuDefaults.containerColor,
            sample = "Rename…",
        )

        SkeinSectionHeader("Sheet — SkeinSheetDefaults")
        OverlaySpecimen(
            shape = SkeinSheetDefaults.shape,
            container = SkeinSheetDefaults.containerColor,
            sample = "Attach a file or note",
        )

        SkeinSectionHeader("Dialog — SkeinDialogDefaults")
        OverlaySpecimen(
            shape = SkeinDialogDefaults.shape,
            container = SkeinDialogDefaults.containerColor,
            sample = "Delete “Skein UX redesign”?",
        )

        SkeinSectionHeader("Tooltip — SkeinTooltip's content style (§5.3)")
        OverlaySpecimen(
            shape = RoundedCornerShape(SkeinRadius.radiusXs),
            container = MaterialTheme.colorScheme.surfaceContainer,
            sample = "Search · Ctrl+K",
        )

        SkeinSectionHeader("Markdown — rememberSkeinMarkdownStyle()")
        MarkdownSpecimen()
    }
}

@Composable
private fun ColourSection(page1: Boolean) {
    val colors = MaterialTheme.colorScheme
    val extended = LocalSkeinColors.current
    val pairs =
        remember(colors, extended, page1) {
            val first =
                listOf(
                    Triple("onSurface / surface", colors.onSurface, colors.surface),
                    Triple("onSurfaceVariant / surface", colors.onSurfaceVariant, colors.surface),
                    Triple("primary / surface", colors.primary, colors.surface),
                    Triple("onPrimary / primary", colors.onPrimary, colors.primary),
                    Triple(
                        "onSecondaryContainer / secondaryContainer",
                        colors.onSecondaryContainer,
                        colors.secondaryContainer,
                    ),
                    Triple("tertiary / surface", colors.tertiary, colors.surface),
                )
            val second =
                listOf(
                    Triple("error / surface", colors.error, colors.surface),
                    Triple("onError / error", colors.onError, colors.error),
                    Triple("success / surface", extended.success, colors.surface),
                    Triple("warning / surface", extended.warning, colors.surface),
                    Triple("outline / surface", colors.outline, colors.surface),
                    Triple("focusRing / surfaceContainerLow", extended.focusRing, colors.surfaceContainerLow),
                )
            if (page1) first else second
        }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        pairs.forEach { (label, fg, bg) -> ColourSwatch(label, fg, bg) }
    }
}

@Composable
private fun ColourSwatch(
    label: String,
    foreground: Color,
    background: Color,
) {
    val ratio = remember(foreground, background) { WcagContrast.ratio(foreground.argbLong(), background.argbLong()) }
    Column(modifier = Modifier.width(108.dp)) {
        Box(
            modifier = Modifier.fillMaxWidth().height(48.dp).background(background),
            contentAlignment = Alignment.Center,
        ) {
            Text("Ag", color = foreground, style = MaterialTheme.typography.titleMedium)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
        Text(
            "%.2f:1".format(ratio),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun Color.argbLong(): Long = toArgb().toLong() and 0xFFFFFFFFL

/**
 * §3.2/§3.3's type scale, split by [large] so each half fits phone's 800 dp
 * budget even at font scale 200% (display/headline/title grow the most —
 * see [GalleryPage]'s KDoc): [large] `true` is display/headline/title (the
 * nine roles that grow the most at 200%), `false` is body/label + the four
 * mono roles.
 */
@Composable
private fun TypeScaleSection(large: Boolean) {
    val t = MaterialTheme.typography
    val mono = LocalSkeinMonoStyles.current
    val largeRoles =
        listOf(
            "displayLarge (45/52)" to t.displayLarge,
            "displayMedium (36/44)" to t.displayMedium,
            "displaySmall (32/40)" to t.displaySmall,
            "headlineLarge (28/36)" to t.headlineLarge,
            "headlineMedium (24/32)" to t.headlineMedium,
            "headlineSmall (22/28)" to t.headlineSmall,
            "titleLarge (20/28)" to t.titleLarge,
            "titleMedium (17/24)" to t.titleMedium,
            "titleSmall (14/20)" to t.titleSmall,
        )
    val smallRoles =
        listOf(
            "bodyLarge (16/24)" to t.bodyLarge,
            "bodyMedium (14/20)" to t.bodyMedium,
            "bodySmall (13/18)" to t.bodySmall,
            "labelLarge (14/20)" to t.labelLarge,
            "labelMedium (13/18)" to t.labelMedium,
            "labelSmall (12/16)" to t.labelSmall,
            "mono codeBlock (13/20)" to mono.codeBlock,
            "mono monoBody (14/20)" to mono.monoBody,
            "mono monoLabel (12/16)" to mono.monoLabel,
        )
    val roles = if (large) largeRoles else smallRoles
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        roles.forEach { (label, style) ->
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Ag Skein", style = style, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SpacingSection() {
    val entries =
        listOf(
            "space2" to SkeinSpacing.space2,
            "space4" to SkeinSpacing.space4,
            "space8" to SkeinSpacing.space8,
            "space12" to SkeinSpacing.space12,
            "space16" to SkeinSpacing.space16,
            "space20" to SkeinSpacing.space20,
            "space24" to SkeinSpacing.space24,
            "space32" to SkeinSpacing.space32,
            "space40" to SkeinSpacing.space40,
            "space48" to SkeinSpacing.space48,
            "space64" to SkeinSpacing.space64,
        )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        entries.forEach { (label, size) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(size).background(MaterialTheme.colorScheme.primary))
                Text(
                    "$label · ${size.value.toInt()}dp",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RadiusSection() {
    val entries =
        listOf(
            "radiusNone" to SkeinRadius.radiusNone,
            "radiusXs" to SkeinRadius.radiusXs,
            "radiusSm" to SkeinRadius.radiusSm,
            "radiusMd" to SkeinRadius.radiusMd,
            "radiusLg" to SkeinRadius.radiusLg,
            "radiusXl" to SkeinRadius.radiusXl,
        )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        entries.forEach { (label, radius) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(radius)),
                )
                Text(
                    "$label · ${radius.value.toInt()}dp",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Every [SkeinIcons] accessor (the same reflection `SkeinIconsTest` uses to
 * keep the object and the bundled drawables in lockstep), rendered outlined —
 * including the five `*Filled` (fill 1) companions alongside their outline
 * (fill 0) counterpart, so both states are in the sheet.
 */
@Composable
private fun IconSection(third: Int) {
    val icons =
        remember(third) {
            val all =
                SkeinIcons::class.java.declaredFields
                    .filter { it.type == Int::class.javaPrimitiveType && ReflectModifier.isPrivate(it.modifiers) }
                    .onEach { it.isAccessible = true }
                    .map { it.name to (it.get(SkeinIcons) as Int) }
                    .sortedBy { it.first }
            // Three roughly-even groups (some names — "ActivityFailed",
            // "KnowledgeFilled" — wrap to 3 lines at font scale 200%, so even
            // 24-and-24 halves still clipped phone's 800 dp budget by a row;
            // 16-and-16-and-16 is what finally fit, this bead's own
            // recording measured).
            val chunk = (all.size + 2) / 3
            all.chunked(chunk).getOrElse(third) { emptyList() }
        }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        icons.forEach { (name, id) ->
            Column(modifier = Modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    painter = painterResource(id),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(SkeinSize.iconStandard),
                )
                Text(
                    name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun OverlaySpecimen(
    shape: Shape,
    container: Color,
    sample: String,
) {
    Box(
        modifier =
            Modifier
                .background(container, shape)
                .border(SkeinSize.hairline, MaterialTheme.colorScheme.outlineVariant, shape)
                .padding(12.dp),
    ) {
        Text(sample, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** A short specimen exercising [rememberSkeinMarkdownStyle]'s heading, bold, code, wikilink, quote and citation. */
@Composable
private fun MarkdownSpecimen() {
    val style = rememberSkeinMarkdownStyle()
    val document =
        remember {
            MarkdownAst.parse(
                "## Fold launch plan\n\n" +
                    "Straw colonised in **14 days**, hardwood in `21`. " +
                    "See [[Fold launch plan]] for the full write-up [1].\n\n" +
                    "> Ready once the outer display verifies.",
            )
        }
    val annotated =
        remember(document, style) {
            MarkdownRenderer.toAnnotatedString(document, style, citations = listOf("Fold launch plan"))
        }
    Text(annotated, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
}

@Preview(name = "Component gallery — tokens: colour (dark)", showBackground = true)
@Composable
private fun SkeinTokensColourGalleryDarkPreview() {
    SkeinTheme(mode = SkeinThemeMode.DARK) {
        SkeinComponentGallery(GalleryPage.TOKENS_COLOUR_1)
    }
}

@Preview(name = "Component gallery — tokens: colour (light)", showBackground = true)
@Composable
private fun SkeinTokensColourGalleryLightPreview() {
    SkeinTheme(mode = SkeinThemeMode.LIGHT) {
        SkeinComponentGallery(GalleryPage.TOKENS_COLOUR_1)
    }
}

@Preview(name = "Component gallery — tokens: icons (dark)", showBackground = true)
@Composable
private fun SkeinTokensIconsGalleryDarkPreview() {
    SkeinTheme(mode = SkeinThemeMode.DARK) {
        SkeinComponentGallery(GalleryPage.TOKENS_ICONS_1)
    }
}

@Preview(name = "Component gallery — overlays (dark)", showBackground = true)
@Composable
private fun SkeinOverlaysGalleryDarkPreview() {
    SkeinTheme(mode = SkeinThemeMode.DARK) {
        SkeinComponentGallery(GalleryPage.OVERLAYS)
    }
}
