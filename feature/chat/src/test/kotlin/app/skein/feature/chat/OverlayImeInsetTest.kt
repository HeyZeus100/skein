// AL-11: actual inspector and Connections entries with synthetic platform insets.
// This verifies Compose ownership, not a real keyboard or physical display swap.
package app.skein.feature.chat

import android.app.Application
import android.content.ComponentName
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.core.model.Document
import app.skein.core.model.Role
import app.skein.core.navigation.ChatKey
import app.skein.core.navigation.NoteKey
import app.skein.core.navigation.SkeinId
import app.skein.feature.chat.entries.ChatEntryTestTags
import app.skein.feature.chat.entries.EntriesHost
import app.skein.feature.chat.entries.pipelineOver
import app.skein.feature.editor.entries.KnowledgeEntryTestTags
import app.skein.feature.editor.notetab.NoteTabTestTags
import app.skein.feature.shell.host.EntryChromeTestTags
import app.skein.feature.shell.host.SheetTestTags
import app.skein.feature.shell.host.SkeinShellState
import app.skein.testing.fakeVault
import app.skein.testing.scriptedEngine
import app.skein.testing.ui.SkeinDevice
import app.skein.testing.ui.UxDeviceRule
import app.skein.testing.ui.UxSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34])
class OverlayImeInsetTest(
    private val device: SkeinDevice,
) {
    @get:Rule(order = 0)
    val deviceRule = UxDeviceRule(UxSpec(device, dark = false))

    @get:Rule(order = 1)
    val registerHost =
        object : ExternalResource() {
            override fun before() {
                val app = ApplicationProvider.getApplicationContext<Application>()
                Shadows
                    .shadowOf(app.packageManager)
                    .addActivityIfNotPresent(ComponentName(app, ChatImeTestActivity::class.java))
            }
        }

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<ChatImeTestActivity>()

    private lateinit var chat: Document
    private lateinit var note: Document
    private val vault =
        fakeVault {
            chat = chat("Chat", Role.USER to "Question", Role.ASSISTANT to "Answer")
            note = note("Note", "A note with Connections")
        }
    private lateinit var shell: SkeinShellState

    @Test
    fun `inspector content and close clear IME and restore through peek and navigation-only insets`() {
        setHost()
        composeRule.runOnIdle { shell.navigate { goTo(it, ChatKey(SkeinId.of(chat.id))) } }
        composeRule.onNodeWithTag(ChatEntryTestTags.CONTEXT_ACTION).performClick()
        verifyOverlay(
            ChatEntryTestTags.INSPECTOR,
            CHAT_SCREEN_TEST_TAG,
            "Sources appear here after a reply in this session.",
        )
    }

    @Test
    fun `Connections final action remains scrollable above IME and restores through peek`() {
        setHost()
        composeRule.runOnIdle { shell.navigate { goTo(it, NoteKey(SkeinId.of(note.id))) } }
        composeRule.onNodeWithTag(NoteTabTestTags.GRAPH_BUTTON).performClick()
        verifyOverlay(KnowledgeEntryTestTags.CONNECTIONS, NoteTabTestTags.ROOT, "Open in Graph")
    }

    private fun setHost() {
        val pipeline = pipelineOver(vault, scriptedEngine())
        composeRule.setContent { SkeinTheme { EntriesHost(vault, pipeline, null, { shell = it }) } }
        composeRule.waitForIdle()
        dispatchInsets(ime = 0)
    }

    private fun verifyOverlay(
        overlayTag: String,
        underlyingTag: String,
        lastText: String,
    ) {
        composeRule.onNodeWithTag(SheetTestTags.EXPANDED).assertIsDisplayed()
        val before = bounds(overlayTag)
        val underlying = bounds(underlyingTag)
        val window = composeRule.activity.window.decorView
        val keyboardHeight = (window.height * 0.4f).toInt()
        val keyboardTop = window.height - keyboardHeight
        assertEquals("overlay clears navigation once", window.height - NAV_HEIGHT.toFloat(), before.bottom, TOLERANCE)
        assertTrue("non-vacuous: retained keyboard covers original overlay", before.bottom > keyboardTop)

        dispatchInsets(ime = keyboardHeight)
        val after = bounds(overlayTag)
        assertEquals("overlay consumes the remaining IME exactly once", keyboardTop.toFloat(), after.bottom, TOLERANCE)
        assertEquals("overlay insets do not resize the underlying entry", underlying, bounds(underlyingTag))
        val close =
            composeRule.onNode(
                hasTestTag(EntryChromeTestTags.NAV_ICON) and hasAnyAncestor(hasTestTag(overlayTag)),
            )
        close.assertIsDisplayed()
        assertTrue("Close clears keyboard", close.fetchSemanticsNode().boundsInRoot.bottom <= keyboardTop + TOLERANCE)
        val last = composeRule.onNodeWithText(lastText)
        last.performScrollTo().assertIsDisplayed()
        val scrolled = last.fetchSemanticsNode().boundsInRoot
        assertTrue("last content clears keyboard", scrolled.bottom <= keyboardTop + TOLERANCE)
        assertTrue("last content clears pinned header", scrolled.top >= close.fetchSemanticsNode().boundsInRoot.bottom)

        composeRule.runOnIdle { shell.sheets.collapseAll() }
        composeRule.onNodeWithTag(SheetTestTags.PEEK).assertIsDisplayed().performClick()
        last.assertIsDisplayed()
        assertEquals(
            "entry scroll survives expanded to peek and back",
            scrolled,
            last.fetchSemanticsNode().boundsInRoot,
        )
        assertEquals("expanded content still consumes the IME once", after, bounds(overlayTag))

        dispatchInsets(ime = 0)
        assertEquals("navigation-only insets restore expanded geometry", before, bounds(overlayTag))
    }

    private fun dispatchInsets(ime: Int) {
        composeRule.runOnUiThread {
            ViewCompat.dispatchApplyWindowInsets(
                composeRule.activity.window.decorView,
                WindowInsetsCompat
                    .Builder()
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime))
                    .setVisible(WindowInsetsCompat.Type.ime(), ime > 0)
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, NAV_HEIGHT))
                    .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
                    .build(),
            )
        }
        composeRule.waitForIdle()
    }

    private fun bounds(tag: String): Rect = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    companion object {
        private const val NAV_HEIGHT = 80
        private const val TOLERANCE = 4f

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun devices(): List<Array<Any>> =
            listOf(SkeinDevice.FOLD_OUTER_524, SkeinDevice.FOLD_OUTER_443_LAND).map { arrayOf<Any>(it) }
    }
}
