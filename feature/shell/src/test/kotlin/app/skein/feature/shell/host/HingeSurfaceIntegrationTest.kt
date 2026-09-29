package app.skein.feature.shell.host

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.HingeInfo
import androidx.compose.material3.adaptive.Posture
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.computeWindowSizeClass
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.core.navigation.ChatContextKey
import app.skein.core.navigation.ChatKey
import app.skein.core.navigation.ChatSourceKey
import app.skein.core.navigation.ObjectKind
import app.skein.core.navigation.SkeinId
import app.skein.core.vault.session.UnlockManager
import app.skein.testing.ui.skeinComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual host/scene composition, rather than a geometry helper standing in for wired UI. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HingeSurfaceIntegrationTest {
    @get:Rule
    val composeRule = skeinComposeRule()

    private val manager = UnlockManager(RecordingKeyProvider())
    private lateinit var shell: SkeinShellState
    private var density = 1f
    private var sheetBounds = Rect.Zero
    private var chatBounds = Rect.Zero
    private var rootBounds = Rect.Zero
    private var sourceBounds = Rect.Zero
    private var contextCompositionCount = 0
    private var underlyingTaps = 0

    @Test
    fun `expanded bottom sheet stays below tabletop hinge and returns to flat size`() {
        val separating = mutableStateOf(true)
        val size = DpSize(524.dp, 1175.dp)
        setHost(size, separating)
        composeRule.runOnIdle {
            shell.navigate { goTo(it, ChatKey(CHAT_A)) }
            shell.navigate { follow(it, ChatContextKey(CHAT_A)) }
        }
        composeRule.waitForIdle()
        val peekBounds = composeRule.onNodeWithTag(SheetTestTags.PEEK).fetchSemanticsNode().boundsInRoot
        assertTrue("peek keeps its row height: $peekBounds", peekBounds.height <= 80f * density)
        assertTrue("peek leaves the underlying chat usable: $chatBounds", chatBounds.height > 500f * density)
        composeRule.onNodeWithTag(SheetTestTags.PEEK).performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        assertTrue("sheet starts below crease: $sheetBounds", sheetBounds.top >= HINGE_BOTTOM * density - 1f)
        assertTrue("sheet bottom remains in window", sheetBounds.bottom <= size.height.value * density + 1f)
        assertTrue("sheet has real content", sheetBounds.height > 48f * density)

        separating.value = false
        composeRule.waitForIdle()
        val parentHeight =
            composeRule
                .onNodeWithTag(
                    SkeinShellHostTestTags.NAV_DISPLAY,
                ).fetchSemanticsNode()
                .boundsInRoot.height
        assertEquals(parentHeight * 0.5f, sheetBounds.height, 2f)
        composeRule.onNodeWithTag(SheetTestTags.EXPANDED).assertExists()
        composeRule.runOnIdle { shell.sheets.collapseAll() }
        composeRule.waitForIdle()
        assertEquals("entry-local remember survives peek to expanded and back", 1, contextCompositionCount)
        assertTrue("collapsed entry is a row again", sheetBounds.height <= 80f * density)
    }

    @Test
    fun `search is confined above hinge while its modal window blocks underlying controls`() {
        setHost(DpSize(1006.dp, 1043.dp), mutableStateOf(true))
        composeRule.runOnIdle {
            shell.navigate { goTo(it, ChatKey(CHAT_A)) }
            shell.openSearch()
        }
        composeRule.waitForIdle()
        val overlay = composeRule.onNodeWithTag(SkeinSearchTestTags.OVERLAY).fetchSemanticsNode().boundsInWindow
        assertTrue("search cannot cross the crease", overlay.bottom <= HINGE_TOP * density + 1f)
        assertTrue("search remains usable", overlay.height > 48f * density)
        // A real pointer event, not a semantic click that could bypass the modal's hit testing.
        composeRule.onNodeWithTag("hinge_underlying").performTouchInput {
            click(
                bottomCenter -
                    androidx.compose.ui.geometry
                        .Offset(0f, 12f),
            )
        }
        composeRule.runOnIdle { assertEquals(0, underlyingTaps) }
    }

    @Test
    fun `single-pane chat stays on book end page and expands again when the hinge becomes flat`() {
        val separating = mutableStateOf(true)
        setHost(DpSize(700.dp, 1000.dp), separating, vertical = true)
        composeRule.runOnIdle { shell.navigate { goTo(it, ChatKey(CHAT_A)) } }
        composeRule.waitForIdle()
        assertTrue(
            "single pane cannot cross the book crease: $chatBounds",
            chatBounds.left >= HINGE_BOTTOM * density - 1f,
        )
        assertTrue("chat remains interactive", chatBounds.width > 48f * density)
        composeRule.onNodeWithTag("hinge_underlying").performTouchInput { click() }
        val narrowWidth = chatBounds.width
        separating.value = false
        composeRule.waitForIdle()
        assertTrue("flat window restores full content width", chatBounds.width > narrowWidth + 100f * density)
        assertEquals(1, underlyingTaps)
    }

    @Test
    fun `book guard preserves the start-side list when Material already splits the entries`() {
        setHost(DpSize(1006.dp, 1043.dp), mutableStateOf(true), vertical = true)
        composeRule.runOnIdle { shell.navigate { goTo(it, ChatKey(CHAT_A)) } }
        composeRule.waitForIdle()
        assertTrue("start list remains usable: $rootBounds", rootBounds.width > 200f * density)
        assertTrue("list stays before the crease", rootBounds.right <= HINGE_TOP * density + 1f)
        assertTrue("detail stays after the crease", chatBounds.left >= HINGE_BOTTOM * density - 1f)
        assertTrue("detail remains usable", chatBounds.width > 200f * density)
    }

    @Test
    fun `expanded tabletop inspector and source stay in their designated partitions`() {
        setHost(DpSize(1006.dp, 1043.dp), mutableStateOf(true))
        composeRule.runOnIdle {
            shell.navigate { goTo(it, ChatKey(CHAT_A)) }
            shell.navigate { follow(it, ChatContextKey(CHAT_A)) }
        }
        composeRule.waitForIdle()
        assertTrue("inspector pane is below the hinge: $sheetBounds", sheetBounds.top >= HINGE_BOTTOM * density - 1f)
        assertTrue("inspector remains usable", sheetBounds.height > 48f * density)
        composeRule.runOnIdle {
            shell.navigate { follow(it, ChatSourceKey(CHAT_A, SOURCE_ID)) }
        }
        composeRule.waitForIdle()
        assertTrue("source pane is above the hinge: $sourceBounds", sourceBounds.bottom <= HINGE_TOP * density + 1f)
        assertTrue("source remains usable", sourceBounds.height > 48f * density)
    }

    private fun setHost(
        size: DpSize,
        separating: androidx.compose.runtime.MutableState<Boolean>,
        vertical: Boolean = false,
    ) {
        composeRule.setContent {
            SkeinTheme {
                DeviceConfigurationOverride(DeviceConfigurationOverride.WindowSize(size)) {
                    density = LocalDensity.current.density
                    val info =
                        WindowAdaptiveInfo(
                            WindowSizeClass.BREAKPOINTS_V2.computeWindowSizeClass(
                                size.width.value.toInt(),
                                size.height.value.toInt(),
                            ),
                            Posture(
                                isTabletop = separating.value && !vertical,
                                hingeList =
                                    listOf(
                                        HingeInfo(
                                            if (vertical) {
                                                Rect(
                                                    HINGE_TOP * density,
                                                    0f,
                                                    HINGE_BOTTOM * density,
                                                    size.height.value * density,
                                                )
                                            } else {
                                                Rect(
                                                    0f,
                                                    HINGE_TOP * density,
                                                    size.width.value * density,
                                                    HINGE_BOTTOM * density,
                                                )
                                            },
                                            isFlat = !separating.value,
                                            isVertical = vertical,
                                            isSeparating = separating.value,
                                            isOccluding = separating.value,
                                        ),
                                    ),
                            ),
                        )
                    shell = rememberSkeinShellState(manager)
                    SkeinShellHost(
                        shell,
                        resolveKinds = { ids -> ids.associateWith { ObjectKind.CHAT } },
                        search = { emptyList() },
                        windowAdaptiveInfo = info,
                    ) { key ->
                        if (key is ChatContextKey) {
                            remember { contextCompositionCount++ }
                            val sizeModifier =
                                if (LocalSheetMode.current ==
                                    SheetMode.PEEK
                                ) {
                                    Modifier.fillMaxWidth().height(48.dp)
                                } else {
                                    Modifier.fillMaxSize()
                                }
                            Box(sizeModifier.onGloballyPositioned { sheetBounds = it.boundsInWindow() }) {
                                Text("Context")
                            }
                        } else if (key is ChatSourceKey) {
                            Box(Modifier.fillMaxSize().onGloballyPositioned { sourceBounds = it.boundsInWindow() }) {
                                Text("Source")
                            }
                        } else {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .testTag(if (key is ChatKey) "hinge_underlying" else "hinge_root")
                                    .onGloballyPositioned {
                                        if (key is ChatKey) {
                                            chatBounds = it.boundsInWindow()
                                        } else {
                                            rootBounds =
                                                it.boundsInWindow()
                                        }
                                    }.clickable { underlyingTaps++ },
                            ) {
                                Text("Chat")
                            }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val HINGE_TOP = 500f
        const val HINGE_BOTTOM = 508f
        val SOURCE_ID = SkeinId.of("11111111-1111-4111-8111-111111111111")
    }
}
