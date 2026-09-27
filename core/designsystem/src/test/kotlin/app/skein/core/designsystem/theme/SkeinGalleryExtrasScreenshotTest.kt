// skein-xtov.23.11 (DS13, docs/ux/DESIGN_SYSTEM.md §15, §17): two small
// additions the full-state per-component galleries (SkeinComponentsScreenshotTest
// et al.) don't cover.
//
// `overlaysAndMarkdownPage`: `SkeinMenuDefaults`/`SkeinSheetDefaults`/
// `SkeinDialogDefaults` (shadow-free shape/colour tokens, §5.3) and
// `rememberSkeinMarkdownStyle()` (§10.16) render nothing of their own, so
// nothing else in the suite ever composes them. Captured at phone only,
// light/dark, font 1.0 — the §15 matrix note that font 2.0 and the extra two
// device sizes are only required for layout-sensitive or text-heavy
// components, and this page is a set of small fixed-size specimens, not a
// layout that reflows with width or wraps meaningfully differently at 200%.
//
// `listRowRtl`: §15's "Rows … also once in RTL." `SkeinListRow` is the one
// `:core:designsystem` row component in that list (the composer is
// `:feature:chat`, out of this bead's scope) — its leading icon and trailing
// meta/⋮ mirror under `LayoutDirection.Rtl`, captured once in light and dark.
package app.skein.core.designsystem.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.skein.core.designsystem.components.SkeinAction
import app.skein.core.designsystem.components.SkeinListRow
import app.skein.core.designsystem.components.SkeinSectionHeader
import app.skein.core.designsystem.icons.SkeinIcons
import app.skein.testing.ui.SkeinDevice
import app.skein.testing.ui.UxDeviceRule
import app.skein.testing.ui.UxSpec
import app.skein.testing.ui.assertEveryActionIsNamed
import app.skein.testing.ui.assertNoTextOverflow
import app.skein.testing.ui.assertTouchTargets
import app.skein.testing.ui.captureUx
import app.skein.testing.ui.runSweep
import app.skein.testing.ui.skeinComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SkeinGalleryExtrasScreenshotTest(
    private val spec: UxSpec,
) {
    @get:Rule(order = 0)
    val deviceRule = UxDeviceRule(spec)

    @get:Rule(order = 1)
    val composeRule = skeinComposeRule()

    @Test
    fun overlaysAndMarkdownPage() {
        composeRule.setContent {
            SkeinTheme {
                SkeinComponentGallery(GalleryPage.OVERLAYS)
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(GALLERY_TAG).captureUx(spec, "gallery-overlays")
        composeRule.assertTouchTargets()
        composeRule.assertEveryActionIsNamed()
        // Report-only: see SkeinTokensGalleryScreenshotTest.tokensPage's KDoc.
        runSweep(listOf("assertNoTextOverflow" to { composeRule.assertNoTextOverflow() }))
            .forEach { r -> println(summarize("gallery-overlays", spec, r)) }
    }

    @Test
    fun listRowRtl() {
        val menuActions =
            listOf(
                SkeinAction("Rename…", SkeinIcons.Rename) {},
                SkeinAction("Delete…", SkeinIcons.Delete, destructive = true) {},
            )
        composeRule.setContent {
            SkeinTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        Column(
                            modifier = Modifier.testTag(RTL_GALLERY).padding(vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            SkeinSectionHeader("اليوم")
                            SkeinListRow(
                                "محادثة",
                                onClick = {},
                                selected = true,
                                leadingIcon = SkeinIcons.Chat,
                                trailingMeta = "2h",
                                menuActions = menuActions,
                            )
                            SkeinListRow(
                                "ملاحظة",
                                onClick = {},
                                leadingIcon = SkeinIcons.Note,
                                trailingMeta = "9:41",
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(RTL_GALLERY).captureUx(spec, "gallery-list-row-rtl")
        composeRule.assertTouchTargets()
        composeRule.assertEveryActionIsNamed()
        runSweep(listOf("assertNoTextOverflow" to { composeRule.assertNoTextOverflow() }))
            .forEach { r -> println(summarize("gallery-list-row-rtl", spec, r)) }
    }

    private companion object {
        const val RTL_GALLERY = "gallery-rtl"

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun parameters(): List<Array<Any>> =
            listOf(
                UxSpec(SkeinDevice.PHONE, dark = false),
                UxSpec(SkeinDevice.PHONE, dark = true),
            ).map { arrayOf<Any>(it) }
    }
}
