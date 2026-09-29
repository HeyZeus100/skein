package app.skein.feature.shell.host

import android.graphics.Bitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.HingeInfo
import androidx.compose.material3.adaptive.Posture
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.computeWindowSizeClass
import app.skein.core.designsystem.components.SkeinDestructiveDialog
import app.skein.core.designsystem.components.LocalSkeinWindowPartitions
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.core.navigation.ChatKey
import app.skein.core.navigation.NoteKey
import app.skein.feature.shell.container.SkeinNavContainerTestTags
import app.skein.feature.shell.layout.SkeinPosture
import app.skein.core.vault.session.UnlockManager
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
import androidx.compose.ui.text.TextLayoutResult
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
    private val fontScale = mutableStateOf(1f)
    private val tabletopTop = mutableStateOf<Float?>(null)
    private val showPrimaryDialog = mutableStateOf(false)

    @Test
    fun `split uses two independent owners and hides inactive semantics when collapsed`() {
        setHost()
        rule.onAllNodesWithTag(SkeinNavContainerTestTags.RAIL).assertCountEquals(1)
        rule.onAllNodesWithTag(SkeinShellHostTestTags.NAV_DISPLAY).assertCountEquals(1)
        rule.onNodeWithTag(WorkspaceTestTags.TOGGLE_SPLIT).performClick()
        rule.onAllNodesWithTag(SkeinShellHostTestTags.NAV_DISPLAY).assertCountEquals(2)
        rule.onNodeWithTag(WorkspaceTestTags.PRIMARY_PANE).assertIsDisplayed()
        rule.onNodeWithTag(WorkspaceTestTags.SECONDARY_PANE).assertIsDisplayed()
        rule.onNodeWithTag(WorkspaceTestTags.ACTIVATE_SECONDARY).performClick()
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
        val primaryLeft = rule.onNodeWithTag(WorkspaceTestTags.PRIMARY_PANE).fetchSemanticsNode().boundsInRoot.left
        rule.onNodeWithTag(WorkspaceTestTags.SWAP_PANES).performClick()
        assertTrue(rule.onNodeWithTag(WorkspaceTestTags.PRIMARY_PANE).fetchSemanticsNode().boundsInRoot.left > primaryLeft)
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
        assertEquals(NoteKey(NOTE_B), workspace.secondary.nav.currentStack.last())
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
        for (hingeTop in listOf(28f, 4f, 60f)) {
            tabletopTop.value = hingeTop
            rule.waitForIdle()
            for (tag in listOf(WorkspaceTestTags.ACTIVATE_PRIMARY, WorkspaceTestTags.ACTIVATE_SECONDARY,
                WorkspaceTestTags.TOGGLE_SPLIT, WorkspaceTestTags.SWAP_PANES)) {
                val bounds = rule.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInWindow
                assertTrue("full touch target $tag $bounds", bounds.height / density >= 47.5f)
                assertTrue("control clears near-top hinge $tag $bounds", bounds.bottom <= hingeTop * density || bounds.top >= (hingeTop + 8) * density)
            }
            assertControlTextFits()
        }
    }

    @Test
    fun `compact font two controls stay reachable and synthetic captures preserve each attempt`() {
        val parent = File("build/agent-logs/workspace-visuals").apply { mkdirs() }
        val directory = Files.createTempDirectory(parent.toPath(), "attempt-").toFile()
        size.value = DpSize(852.dp, 883.dp)
        setHost()
        capture(directory, "open-fold-unsplit")
        rule.onNodeWithTag(WorkspaceTestTags.TOGGLE_SPLIT).performClick()
        capture(directory, "open-fold-split")
        size.value = DpSize(400.dp, 900.dp)
        fontScale.value = 2f
        rule.waitForIdle()
        for (tag in listOf(WorkspaceTestTags.TOGGLE_SPLIT, WorkspaceTestTags.SWAP_PANES,
            WorkspaceTestTags.ACTIVATE_PRIMARY, WorkspaceTestTags.ACTIVATE_SECONDARY)) {
            val node = rule.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode()
            assertTrue("48dp target $tag", node.boundsInRoot.height / density >= 47.5f)
            assertTrue("48dp target $tag", node.boundsInRoot.width / density >= 47.5f)
        }
        assertControlTextFits()
        capture(directory, "compact-font-two")
    }

    private fun assertControlTextFits() {
        for (label in listOf("Left", "Right")) {
            val results = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText(label, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            assertTrue("text fits $label", results.isNotEmpty() && results.none { it.hasVisualOverflow })
        }
    }

    private fun capture(directory: File, name: String) {
        rule.waitForIdle()
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private fun setHost(verticalHinge: Boolean? = null) {
        rule.setContent {
            SkeinTheme {
                DeviceConfigurationOverride(DeviceConfigurationOverride.WindowSize(size.value)) {
                    density = LocalDensity.current.density
                    val hinge = verticalHinge?.let { vertical ->
                        val x = size.value.width.value * density / 2
                        val y = (tabletopTop.value ?: (size.value.height.value / 2)) * density
                        HingeInfo(
                            bounds = if (vertical) Rect(x, 0f, x, size.value.height.value * density) else Rect(0f, y, size.value.width.value * density, y + 8 * density),
                            isFlat = false, isVertical = vertical, isSeparating = true, isOccluding = false,
                        )
                    }
                    val info = WindowAdaptiveInfo(
                        WindowSizeClass.BREAKPOINTS_V2.computeWindowSizeClass(size.value.width.value.toInt(), size.value.height.value.toInt()),
                        hinge?.let { Posture(isTabletop = !it.isVertical, hingeList = listOf(it)) } ?: posture.value,
                    )
                    CompositionLocalProvider(LocalDensity provides Density(density, fontScale.value)) {
                    workspace = rememberSkeinWorkspaceState(manager)
                    SkeinWorkspaceHost(workspace, emptyList(), emptyList(), false, windowAdaptiveInfo = info) { shell ->
                        SkeinShellHost(shell, { emptyMap() }) { entry ->
                            var counter by remember { mutableIntStateOf(0) }
                            observedPostures[shell.ownerKey] = LocalSkeinWindowLayout.current.posture
                            partitions[shell.ownerKey] = LocalSkeinWindowPartitions.current
                            Column(Modifier.fillMaxSize()) {
                                shell.EntryTopBar(entry, shell.ownerKey)
                                val counterTag = if (entry is ChatKey || entry is NoteKey) "counter/${shell.ownerKey}" else "root-counter/${shell.ownerKey}"
                                Text("Count $counter", Modifier.testTag(counterTag).clickable { counter++ })
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

    private fun text(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.Text].single().text
}
