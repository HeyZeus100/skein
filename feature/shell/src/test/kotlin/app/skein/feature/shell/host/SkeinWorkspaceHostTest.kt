package app.skein.feature.shell.host

import android.graphics.Bitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.HingeInfo
import androidx.compose.material3.adaptive.Posture
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.computeWindowSizeClass
import app.skein.core.designsystem.components.LocalSkeinWindowPartitions
import app.skein.core.designsystem.components.SkeinDestructiveDialog
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.core.designsystem.theme.SkeinThemeMode
import app.skein.core.navigation.ChatKey
import app.skein.core.navigation.Destination
import app.skein.core.navigation.NewChatKey
import app.skein.core.navigation.NoteKey
import app.skein.core.vault.session.UnlockManager
import app.skein.feature.shell.container.SkeinNavContainerTestTags
import app.skein.feature.shell.layout.SkeinPosture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1200dp-h1100dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SkeinWorkspaceHostTest {
    @get:Rule
    val rule = createAndroidComposeRule<FragmentActivity>()
    private val manager = UnlockManager(keyProvider = RecordingKeyProvider())
    private lateinit var workspace: SkeinWorkspaceState
    private val size = mutableStateOf(DpSize(1200.dp, 1000.dp))
    private val posture = mutableStateOf(Posture())
    private val observedPostures = mutableMapOf<String, SkeinPosture>()
    private val partitions = mutableMapOf<String, app.skein.core.designsystem.components.SkeinWindowPartitions?>()
    private var density = 1f
    private lateinit var inputModeManager: InputModeManager
    private val fontScale = mutableStateOf(1f)
    private val tabletopTop = mutableStateOf<Float?>(null)
    private val showPrimaryDialog = mutableStateOf(false)
    private val theme = mutableStateOf(SkeinThemeMode.LIGHT)
    private val focusRequesters = mutableMapOf<String, FocusRequester>()

    @Test
    fun `split child menu Search closes the rail container drawer and opens only its owner search`() {
        size.value = DpSize(1007.dp, 1043.dp)
        setHost(searchEnabled = true)
        rule.runOnIdle {
            workspace.primary.navigate { goTo(it, ChatKey(CHAT_A)) }
            workspace.secondary.navigate { goTo(it, NoteKey(NOTE_B)) }
        }
        val secondaryNav = workspace.secondary.nav
        rule.onNodeWithTag(WorkspaceTestTags.TOGGLE_SPLIT).performClick()
        rule.onNodeWithTag(SkeinNavContainerTestTags.RAIL).assertIsDisplayed()
        rule.onNodeWithTag(WorkspaceTestTags.TOGGLE_LIST).assertDoesNotExist()
        rule
            .onNode(
                hasContentDescription("Open navigation") and
                    hasAnyAncestor(hasTestTag(WorkspaceTestTags.PRIMARY_PANE)),
            ).performClick()
        rule.onNodeWithTag(SkeinNavContainerTestTags.DRAWER_SHEET).assertIsDisplayed()
        rule
            .onNode(
                hasText("Search or run a command") and
                    hasAnyAncestor(hasTestTag(SkeinNavContainerTestTags.DRAWER_SHEET)),
            ).performClick()
        rule.onNodeWithTag(SkeinNavContainerTestTags.DRAWER_SHEET).assertIsNotDisplayed()
        rule
            .onNode(
                hasTestTag(SkeinSearchTestTags.OVERLAY) and
                    hasAnyAncestor(hasTestTag(WorkspaceTestTags.PRIMARY_PANE)),
            ).assertIsDisplayed()
        rule.runOnIdle {
            assertEquals(WorkspacePane.PRIMARY, workspace.activePane)
            assertTrue(workspace.primary.searchOpen)
            assertTrue(!workspace.secondary.searchOpen)
            assertEquals(secondaryNav, workspace.secondary.nav)
            assertTrue(workspace.splitRequested)
        }
    }

    @Test
    fun `split rail destination dismisses only active owner search`() {
        assertSplitRailActionDismissesSearch(newChat = false)
    }

    @Test
    fun `split rail New chat dismisses only active owner search`() {
        assertSplitRailActionDismissesSearch(newChat = true)
    }

    private fun assertSplitRailActionDismissesSearch(newChat: Boolean) {
        size.value = DpSize(1007.dp, 1043.dp)
        setHost(searchEnabled = true)
        rule.onNodeWithTag(WorkspaceTestTags.TOGGLE_SPLIT).performClick()
        rule.runOnIdle {
            workspace.secondary.navigate { goTo(it, NoteKey(NOTE_B)) }
            workspace.secondary.openSearch()
            workspace.primary.openSearch()
        }
        val secondaryNav = workspace.secondary.nav
        // Each search requests focus when opened. Select the intended owner with a real field tap.
        rule
            .onNode(
                hasTestTag(SkeinSearchTestTags.FIELD) and
                    hasAnyAncestor(hasTestTag(WorkspaceTestTags.PRIMARY_PANE)),
            ).performTouchInput { click() }
        rule.onNodeWithTag(WorkspaceTestTags.PRIMARY_PANE).assertIsSelected()
        rule
            .onNode(
                hasTestTag(SkeinSearchTestTags.OVERLAY) and
                    hasAnyAncestor(hasTestTag(WorkspaceTestTags.PRIMARY_PANE)),
            ).assertIsDisplayed()
        rule
            .onNode(
                (if (newChat) hasContentDescription("New chat") else hasText("Settings")) and
                    hasAnyAncestor(hasTestTag(SkeinNavContainerTestTags.RAIL)),
            ).performClick()
        rule
            .onNode(
                hasTestTag(SkeinSearchTestTags.OVERLAY) and
                    hasAnyAncestor(hasTestTag(WorkspaceTestTags.PRIMARY_PANE)),
            ).assertDoesNotExist()
        rule
            .onNode(
                hasTestTag(SkeinSearchTestTags.OVERLAY) and
                    hasAnyAncestor(hasTestTag(WorkspaceTestTags.SECONDARY_PANE)),
            ).assertIsDisplayed()
        rule.runOnIdle {
            assertEquals(WorkspacePane.PRIMARY, workspace.activePane)
            assertTrue(!workspace.primary.searchOpen)
            assertTrue(workspace.secondary.searchOpen)
            assertEquals(secondaryNav, workspace.secondary.nav)
            assertTrue(workspace.splitRequested)
            if (newChat) {
                assertEquals(Destination.CHAT, workspace.primary.nav.topLevel)
                assertTrue(
                    workspace.primary.nav.currentStack
                        .last() is NewChatKey,
                )
            } else {
                assertEquals(Destination.SETTINGS, workspace.primary.nav.topLevel)
            }
        }
    }

    @Test
    fun `flat workspace controls expose labels on long press`() {
        setHost()
        rule.onNodeWithText("Switch workspace").assertDoesNotExist()
        rule.onNodeWithTag(WorkspaceTestTags.SWAP_PANES).performTouchInput { longClick() }
        rule.onNodeWithText("Switch workspace").assertIsDisplayed()
    }

    @Test
    fun `separating book and near top tabletop folds suppress workspace tooltips`() {
        setHost()
        for (vertical in listOf(true, false)) {
            posture.value =
                Posture(
                    isTabletop = !vertical,
                    hingeList =
                        listOf(
                            HingeInfo(
                                bounds =
                                    if (vertical) {
                                        Rect(600 * density, 0f, 600 * density, 1000 * density)
                                    } else {
                                        Rect(0f, 60 * density, 1200 * density, 68 * density)
                                    },
                                isFlat = false,
                                isVertical = vertical,
                                isSeparating = true,
                                isOccluding = false,
                            ),
                        ),
                )
            rule.waitForIdle()
            rule.onNodeWithTag(WorkspaceTestTags.SWAP_PANES).performTouchInput { longClick() }
            rule.onNodeWithText("Switch workspace").assertDoesNotExist()
            rule.onNodeWithTag(WorkspaceTestTags.SWAP_PANES).assertContentDescriptionEquals("Switch workspace")
            assertCompactControlsFit()
        }
    }

    @Test
    fun `content activation keeps child taps and accessible pane actions after swap`() {
        setHost()
        rule.runOnIdle {
            workspace.primary.navigate { goTo(it, ChatKey(CHAT_A)) }
            workspace.secondary.navigate { goTo(it, NoteKey(NOTE_B)) }
        }
        rule.onNodeWithTag(WorkspaceTestTags.TOGGLE_SPLIT).performClick()
        rule.onNodeWithTag("counter/secondary").performTouchInput { click() }
        assertEquals(WorkspacePane.SECONDARY, workspace.activePane)
        assertEquals("Count 1", text("counter/secondary"))
        rule.onNodeWithTag(WorkspaceTestTags.SECONDARY_PANE).assertIsSelected()
        rule.onNodeWithTag(WorkspaceTestTags.PRIMARY_PANE).assertIsNotSelected()
        rule.onNodeWithTag(WorkspaceTestTags.SWAP_PANES).performClick()
        activatePane(WorkspaceTestTags.PRIMARY_PANE, "Activate right workspace")
        assertEquals(WorkspacePane.PRIMARY, workspace.activePane)
        rule.onNodeWithTag(WorkspaceTestTags.PRIMARY_PANE).assertIsSelected()
        rule
            .onNodeWithTag(WorkspaceTestTags.TOGGLE_SPLIT)
            .assertContentDescriptionEquals("Hide split view")
            .assertIsSelected()
            .performClick()
        rule
            .onNodeWithTag(WorkspaceTestTags.TOGGLE_SPLIT)
            .assertContentDescriptionEquals("Show split view")
            .assertIsNotSelected()
        assertCompactControlsFit()
    }

    @Test
    fun `one contextual sidebar controls only the active owner and disappears without an adjacent list`() {
        setHost()
        rule.runOnIdle {
            workspace.primary.navigate { goTo(it, ChatKey(CHAT_A)) }
            workspace.secondary.navigate { goTo(it, NoteKey(NOTE_B)) }
        }
        rule.onAllNodesWithTag(EntryChromeTestTags.LIST_TOGGLE).assertCountEquals(0)
        rule.onNodeWithTag(WorkspaceTestTags.TOGGLE_LIST).assertContentDescriptionEquals("Hide chats").performClick()
        rule.onNodeWithTag("root-counter/primary").assertDoesNotExist()
        rule.onNodeWithTag("counter/primary").assertIsDisplayed()
        rule.onNodeWithTag(WorkspaceTestTags.TOGGLE_LIST).assertContentDescriptionEquals("Show chats")
        rule
            .onNodeWithTag(WorkspaceTestTags.SWAP_PANES)
            .assertContentDescriptionEquals("Switch workspace")
            .performClick()
        rule.onNodeWithTag(WorkspaceTestTags.TOGGLE_LIST).assertContentDescriptionEquals("Hide notes").performClick()
        rule.runOnIdle {
            assertTrue(!workspace.primary.isListExpanded(Destination.CHAT))
            assertTrue(!workspace.secondary.isListExpanded(Destination.KNOWLEDGE))
            assertTrue(workspace.secondary.isListExpanded(Destination.CHAT))
        }
        rule.onNodeWithTag(WorkspaceTestTags.TOGGLE_SPLIT).performClick()
        rule.onNodeWithTag(WorkspaceTestTags.TOGGLE_LIST).assertDoesNotExist()
        rule.onNodeWithTag(WorkspaceTestTags.SWAP_PANES).assertContentDescriptionEquals("Swap panes")
        rule.onNodeWithTag(WorkspaceTestTags.TOGGLE_SPLIT).performClick()
        rule.onNodeWithTag(WorkspaceTestTags.TOGGLE_LIST).assertContentDescriptionEquals("Show notes")
        rule.runOnIdle { workspace.activeShell.navigate { switchTo(it, Destination.GRAPH) } }
        rule.onNodeWithTag(WorkspaceTestTags.TOGGLE_LIST).assertDoesNotExist()
        rule.runOnIdle { workspace.activeShell.navigate { switchTo(it, Destination.SETTINGS) } }
        rule.onNodeWithTag(WorkspaceTestTags.TOGGLE_LIST).assertDoesNotExist()
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `keyboard focus activates visible owner without clearing the new child and hidden owner cannot take focus`() {
        setHost()
        rule.runOnIdle {
            workspace.primary.navigate { goTo(it, ChatKey(CHAT_A)) }
            workspace.secondary.navigate { goTo(it, NoteKey(NOTE_B)) }
            workspace.toggleSplit()
        }
        rule.runOnIdle { assertTrue(inputModeManager.requestInputMode(InputMode.Keyboard)) }
        rule.waitForIdle()
        assertEquals(InputMode.Keyboard, inputModeManager.inputMode)
        rule.runOnIdle { assertTrue(focusRequesters.getValue("counter/secondary").requestFocus()) }
        rule.onNodeWithTag("counter/secondary").assertIsFocused()
        assertEquals(WorkspacePane.SECONDARY, workspace.activePane)
        rule.runOnIdle { workspace.activate(WorkspacePane.PRIMARY) }
        rule.onNodeWithTag(WorkspaceTestTags.TOGGLE_SPLIT).performClick()
        rule.onNodeWithTag("counter/secondary").assertDoesNotExist()
        rule.runOnIdle {
            assertTrue(!focusRequesters.getValue("counter/secondary").requestFocus())
            assertEquals(WorkspacePane.PRIMARY, workspace.activePane)
        }
    }

    @Test
    fun `sidebar follows the active measured pane on an asymmetric book window`() {
        size.value = DpSize(2000.dp, 1000.dp)
        setHost(verticalHinge = true)
        rule.runOnIdle {
            workspace.primary.navigate { goTo(it, ChatKey(CHAT_A)) }
            workspace.secondary.navigate { goTo(it, NoteKey(NOTE_B)) }
            workspace.toggleSplit()
        }
        // The expanded rail makes the left page narrower; only the right page has an adjacent list.
        rule.onNodeWithTag(WorkspaceTestTags.TOGGLE_LIST).assertDoesNotExist()
        activatePane(WorkspaceTestTags.SECONDARY_PANE)
        rule.onNodeWithTag(WorkspaceTestTags.TOGGLE_LIST).assertContentDescriptionEquals("Hide notes").performClick()
        rule.onNodeWithTag("root-counter/secondary").assertDoesNotExist()
        rule.onNodeWithTag("counter/secondary").assertIsDisplayed()
        rule.onNodeWithTag(WorkspaceTestTags.SWAP_PANES).performClick()
        rule.onNodeWithTag(WorkspaceTestTags.TOGGLE_LIST).assertDoesNotExist()
        assertTrue(!workspace.secondary.isListExpanded(Destination.KNOWLEDGE))
    }

    @Test
    fun `constrained single view switches retained owners even while split preference stays on`() {
        setHost()
        rule.runOnIdle {
            workspace.primary.navigate { goTo(it, ChatKey(CHAT_A)) }
            workspace.secondary.navigate { goTo(it, NoteKey(NOTE_B)) }
            workspace.toggleSplit()
        }
        for (window in listOf(DpSize(400.dp, 900.dp), DpSize(1200.dp, 500.dp))) {
            size.value = window
            rule.waitForIdle()
            rule.onNodeWithTag(WorkspaceTestTags.TOGGLE_LIST).assertDoesNotExist()
            rule
                .onNodeWithTag(WorkspaceTestTags.TOGGLE_SPLIT)
                .assertContentDescriptionEquals("Split view needs more space")
                .assertIsNotEnabled()
            val before = workspace.activePane
            rule
                .onNodeWithTag(WorkspaceTestTags.SWAP_PANES)
                .assertContentDescriptionEquals("Switch workspace")
                .performClick()
            assertNotEquals(before, workspace.activePane)
            assertTrue(workspace.splitRequested)
            rule.onAllNodesWithTag(SkeinShellHostTestTags.NAV_DISPLAY).assertCountEquals(1)
        }
        size.value = DpSize(1200.dp, 1000.dp)
        rule.onNodeWithTag(WorkspaceTestTags.SWAP_PANES).assertContentDescriptionEquals("Swap panes")
        rule.onAllNodesWithTag(SkeinShellHostTestTags.NAV_DISPLAY).assertCountEquals(2)
    }

    @Test
    fun `split uses two independent owners and hides inactive semantics when collapsed`() {
        setHost()
        rule.onAllNodesWithTag(SkeinNavContainerTestTags.RAIL).assertCountEquals(1)
        rule.onAllNodesWithTag(SkeinShellHostTestTags.NAV_DISPLAY).assertCountEquals(1)
        rule.onNodeWithTag(WorkspaceTestTags.TOGGLE_SPLIT).performClick()
        rule.onAllNodesWithTag(SkeinShellHostTestTags.NAV_DISPLAY).assertCountEquals(2)
        rule.onNodeWithTag(WorkspaceTestTags.PRIMARY_PANE).assertIsDisplayed()
        rule.onNodeWithTag(WorkspaceTestTags.SECONDARY_PANE).assertIsDisplayed()
        activatePane(WorkspaceTestTags.SECONDARY_PANE)
        rule.onNodeWithTag(WorkspaceTestTags.TOGGLE_SPLIT).performClick()
        rule.onAllNodesWithTag(SkeinShellHostTestTags.NAV_DISPLAY).assertCountEquals(1)
        assertEquals(WorkspacePane.SECONDARY, workspace.activePane)
    }

    @Test
    fun `plain remembered entry state survives swap compact fallback and return`() {
        setHost()
        rule.runOnIdle {
            workspace.primary.navigate { goTo(it, ChatKey(CHAT_A)) }
            workspace.secondary.navigate { goTo(it, NoteKey(NOTE_B)) }
            workspace.toggleSplit()
        }
        rule.onNodeWithTag("counter/primary").performClick()
        val before = text("counter/primary")
        val primaryLeft =
            rule
                .onNodeWithTag(WorkspaceTestTags.PRIMARY_PANE)
                .fetchSemanticsNode()
                .boundsInRoot.left
        rule.onNodeWithTag(WorkspaceTestTags.SWAP_PANES).performClick()
        assertTrue(
            rule
                .onNodeWithTag(WorkspaceTestTags.PRIMARY_PANE)
                .fetchSemanticsNode()
                .boundsInRoot.left > primaryLeft,
        )
        assertEquals(before, text("counter/primary"))
        size.value = DpSize(500.dp, 900.dp)
        rule.waitForIdle()
        rule.onAllNodesWithTag(SkeinShellHostTestTags.NAV_DISPLAY).assertCountEquals(1)
        size.value = DpSize(1200.dp, 1000.dp)
        rule.waitForIdle()
        assertEquals(before, text("counter/primary"))
    }

    @Test
    fun `only the active pane owns Back even when the other has a detail`() {
        setHost()
        rule.runOnIdle {
            workspace.primary.navigate { goTo(it, ChatKey(CHAT_A)) }
            workspace.secondary.navigate { goTo(it, NoteKey(NOTE_B)) }
            workspace.toggleSplit()
            workspace.activate(WorkspacePane.PRIMARY)
        }
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertEquals(1, workspace.primary.nav.currentStack.size)
        assertEquals(
            NoteKey(NOTE_B),
            workspace.secondary.nav.currentStack
                .last(),
        )
        rule.runOnIdle { workspace.activate(WorkspacePane.SECONDARY) }
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertEquals(1, workspace.secondary.nav.currentStack.size)
    }

    @Test
    fun `active owner gates its retained dialog through split and compact fallback`() {
        setHost()
        rule.runOnIdle {
            workspace.secondary.navigate { goTo(it, NoteKey(NOTE_B)) }
            workspace.toggleSplit()
            showPrimaryDialog.value = true
        }
        rule.onNodeWithText("Primary dialog").assertIsDisplayed()
        val original = ShadowDialog.getLatestDialog()
        rule.runOnIdle { workspace.activate(WorkspacePane.SECONDARY) }
        rule.waitForIdle()
        rule.onNodeWithText("Primary dialog").assertDoesNotExist()
        assertTrue(!original.isShowing)
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertEquals(1, workspace.secondary.nav.currentStack.size)
        size.value = DpSize(400.dp, 900.dp)
        rule.waitForIdle()
        rule.onNodeWithText("Primary dialog").assertDoesNotExist()
        rule.runOnIdle { workspace.activate(WorkspacePane.PRIMARY) }
        rule.onNodeWithText("Primary dialog").assertIsDisplayed()
        assertTrue(showPrimaryDialog.value)
    }

    @Test
    fun `unsplit and narrow fallback retain original separating hinge partitions`() {
        size.value = DpSize(850.dp, 950.dp)
        setHost(verticalHinge = true)
        rule.runOnIdle {
            assertTrue(observedPostures.getValue("primary") is SkeinPosture.Book)
            val bounds = checkNotNull(partitions["primary"])
            assertTrue(bounds.reading.left > 0.dp)
            assertTrue(bounds.anchors.size == 2)
            workspace.toggleSplit()
        }
        rule.waitForIdle()
        // The first page is narrower than 360 dp after the rail, so fallback is one visible owner.
        rule.onAllNodesWithTag(SkeinShellHostTestTags.NAV_DISPLAY).assertCountEquals(1)
        rule.runOnIdle { assertTrue(observedPostures.getValue("primary") is SkeinPosture.Book) }
        rule
            .onNodeWithTag(WorkspaceTestTags.SWAP_PANES)
            .assertContentDescriptionEquals("Switch workspace")
            .performClick()
        assertEquals(WorkspacePane.SECONDARY, workspace.activePane)
        assertTrue(workspace.splitRequested)
    }

    @Test
    fun `tabletop keeps distinct reading and confirmation bounds in both workspaces`() {
        setHost(verticalHinge = false)
        rule.runOnIdle { workspace.toggleSplit() }
        rule.waitForIdle()
        rule.runOnIdle {
            for (owner in listOf("primary", "secondary")) {
                val bounds = checkNotNull(partitions[owner])
                assertNotEquals(bounds.reading, bounds.confirmation)
                assertTrue(bounds.reading.bottom <= bounds.confirmation.top)
                assertTrue(observedPostures.getValue(owner) is SkeinPosture.Tabletop)
            }
        }
    }

    @Test
    fun `near top tabletop hinge cannot clip large text workspace controls`() {
        size.value = DpSize(850.dp, 950.dp)
        fontScale.value = 2f
        tabletopTop.value = 28f
        setHost(verticalHinge = false)
        for (hingeTop in listOf(28f, 4f, 60f, 28.25f)) {
            tabletopTop.value = hingeTop
            rule.waitForIdle()
            for (tag in visibleControlTags()) {
                val bounds =
                    rule
                        .onNodeWithTag(tag)
                        .assertIsDisplayed()
                        .fetchSemanticsNode()
                        .boundsInWindow
                assertTrue("full touch target $tag $bounds", bounds.height / density >= 47.5f)
                assertTrue(
                    "control clears near-top hinge $tag $bounds",
                    bounds.bottom <= hingeTop * density || bounds.top >= (hingeTop + 8) * density,
                )
            }
            assertCompactControlsFit()
        }
    }

    @Test
    fun `compact font two controls stay reachable and synthetic captures preserve each attempt`() {
        val parent = File("build/agent-logs/workspace-visuals").apply { mkdirs() }
        val directory = Files.createTempDirectory(parent.toPath(), "attempt-").toFile()
        size.value = DpSize(852.dp, 883.dp)
        setHost()
        capture(directory, "open-fold-unsplit")
        assertCompactControlsFit()
        rule.onNodeWithTag(WorkspaceTestTags.TOGGLE_SPLIT).performClick()
        capture(directory, "open-fold-split")
        theme.value = SkeinThemeMode.DARK
        capture(directory, "open-fold-split-dark")
        size.value = DpSize(400.dp, 900.dp)
        fontScale.value = 2f
        rule.waitForIdle()
        for (tag in listOf(
            WorkspaceTestTags.TOGGLE_SPLIT,
            WorkspaceTestTags.SWAP_PANES,
        )) {
            val node = rule.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode()
            assertTrue("48dp target $tag", node.boundsInRoot.height / density >= 47.5f)
            assertTrue("48dp target $tag", node.boundsInRoot.width / density >= 47.5f)
        }
        capture(directory, "compact-font-two")
        assertCompactControlsFit()
    }

    private fun assertCompactControlsFit() {
        val controls =
            visibleControlTags()
                .map { tag ->
                    val bounds =
                        rule
                            .onNodeWithTag(tag)
                            .assertIsDisplayed()
                            .fetchSemanticsNode()
                            .boundsInRoot
                    assertTrue("48dp width $tag $bounds", bounds.width / density in 47.5f..48.5f)
                    assertTrue("48dp height $tag $bounds", bounds.height / density in 47.5f..48.5f)
                    bounds
                }.sortedBy { it.left }
        for ((first, second) in controls.zipWithNext()) {
            assertTrue("8dp gap between targets", (second.left - first.right) / density >= 7.5f)
            assertEquals(first.top, second.top, 0.5f)
        }
        assertTrue(
            "controls stay together instead of stretching into banners",
            (controls.last().right - controls.first().left) / density <= 160.5f,
        )
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        for (bounds in controls) {
            assertTrue(
                "control remains in viewport $bounds $root",
                bounds.left >= root.left &&
                    bounds.right <= root.right &&
                    bounds.top >= root.top &&
                    bounds.bottom <= root.bottom,
            )
        }
        for (label in listOf("Left", "Right")) {
            rule.onNodeWithText(label).assertDoesNotExist()
        }
    }

    private fun activatePane(
        pane: String,
        expectedLabel: String? = null,
    ) {
        val action =
            rule
                .onNodeWithTag(pane)
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
                .single()
        if (expectedLabel != null) assertEquals(expectedLabel, action.label)
        rule.runOnIdle { assertTrue(action.action()) }
    }

    private fun visibleControlTags(): List<String> =
        buildList {
            if (rule.onAllNodesWithTag(WorkspaceTestTags.TOGGLE_LIST).fetchSemanticsNodes().isNotEmpty()) {
                add(WorkspaceTestTags.TOGGLE_LIST)
            }
            add(WorkspaceTestTags.TOGGLE_SPLIT)
            add(WorkspaceTestTags.SWAP_PANES)
        }

    private fun capture(
        directory: File,
        name: String,
    ) {
        rule.waitForIdle()
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(
            directory,
            "$name.png",
        ).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private fun setHost(
        verticalHinge: Boolean? = null,
        searchEnabled: Boolean = false,
    ) {
        rule.setContent {
            SkeinTheme(mode = theme.value) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.WindowSize(size.value)) {
                    density = LocalDensity.current.density
                    inputModeManager = LocalInputModeManager.current
                    val hinge =
                        verticalHinge?.let { vertical ->
                            val x = size.value.width.value * density / 2
                            val y = (tabletopTop.value ?: (size.value.height.value / 2)) * density
                            HingeInfo(
                                bounds =
                                    if (vertical) {
                                        Rect(x, 0f, x, size.value.height.value * density)
                                    } else {
                                        Rect(
                                            0f,
                                            y,
                                            size.value.width.value * density,
                                            y + 8 * density,
                                        )
                                    },
                                isFlat = false,
                                isVertical = vertical,
                                isSeparating = true,
                                isOccluding = false,
                            )
                        }
                    val info =
                        WindowAdaptiveInfo(
                            WindowSizeClass.BREAKPOINTS_V2.computeWindowSizeClass(
                                size.value.width.value
                                    .toInt(),
                                size.value.height.value
                                    .toInt(),
                            ),
                            hinge?.let {
                                Posture(
                                    isTabletop = !it.isVertical,
                                    hingeList = listOf(it),
                                )
                            } ?: posture.value,
                        )
                    CompositionLocalProvider(LocalDensity provides Density(density, fontScale.value)) {
                        workspace = rememberSkeinWorkspaceState(manager)
                        SkeinWorkspaceHost(
                            workspace,
                            emptyList(),
                            emptyList(),
                            searchEnabled,
                            windowAdaptiveInfo = info,
                        ) { shell ->
                            SkeinShellHost(shell, { emptyMap() }, search = { emptyList() }) { entry ->
                                var counter by remember { mutableIntStateOf(0) }
                                observedPostures[shell.ownerKey] = LocalSkeinWindowLayout.current.posture
                                partitions[shell.ownerKey] = LocalSkeinWindowPartitions.current
                                Column(Modifier.fillMaxSize()) {
                                    shell.EntryTopBar(entry, shell.ownerKey)
                                    val counterTag =
                                        if (entry is ChatKey ||
                                            entry is NoteKey
                                        ) {
                                            "counter/${shell.ownerKey}"
                                        } else {
                                            "root-counter/${shell.ownerKey}"
                                        }
                                    val requester = remember { FocusRequester() }
                                    focusRequesters[counterTag] = requester
                                    Text(
                                        "Count $counter",
                                        Modifier.testTag(counterTag).focusRequester(requester).clickable { counter++ },
                                    )
                                }
                            }
                            if (shell.ownerKey == "primary" && showPrimaryDialog.value) {
                                SkeinDestructiveDialog(
                                    title = "Primary dialog",
                                    consequence = "This is a retained confirmation.",
                                    onDismiss = { showPrimaryDialog.value = false },
                                    onConfirm = {},
                                )
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun text(tag: String) =
        rule
            .onNodeWithTag(tag)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .single()
            .text
}
