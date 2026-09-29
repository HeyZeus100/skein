package app.skein.feature.shell.host

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertTextEquals
import androidx.fragment.app.FragmentActivity
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.core.navigation.ChatHomeKey
import app.skein.core.navigation.ChatKey
import app.skein.core.navigation.NavMode
import app.skein.core.vault.session.UnlockManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1006dp-h1043dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SkeinSidebarHostTest {
    @get:Rule
    val rule = createAndroidComposeRule<FragmentActivity>()
    private val manager = UnlockManager(keyProvider = RecordingKeyProvider())
    private lateinit var shell: SkeinShellState
    private var mode = NavMode.PHONE

    @Test
    fun `hiding a 280dp list expands detail without changing navigation or remembered editor`() {
        rule.setContent {
            SkeinTheme {
                shell = rememberSkeinShellState(manager)
                SkeinShellHost(shell, { emptyMap() }) { entry ->
                    mode = LocalSkeinWindowLayout.current.navMode()
                    val list = entry == ChatHomeKey
                    Column(Modifier.fillMaxSize().testTag(if (list) "sidebar" else "detail")) {
                        shell.EntryTopBar(entry, if (list) "Chats" else "Conversation")
                        if (list) {
                            Text("Chat history")
                        } else {
                            var draft by remember { mutableStateOf("") }
                            BasicTextField(draft, { draft = it }, Modifier.testTag("draft"))
                        }
                    }
                }
            }
        }
        rule.runOnIdle { shell.navigate { goTo(it, ChatKey(CHAT_A)) } }
        rule.waitForIdle()
        val stack = shell.nav.currentStack
        val sidebarWidth = rule.onNodeWithTag("sidebar").fetchSemanticsNode().boundsInRoot.width
        val detailWidth = rule.onNodeWithTag("detail").fetchSemanticsNode().boundsInRoot.width
        assertEquals(280f, sidebarWidth, 1f)
        rule.onNodeWithTag("draft").performTextInput("retained draft")
        rule.onNodeWithTag(EntryChromeTestTags.LIST_TOGGLE).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("sidebar").assertDoesNotExist()
        rule.onNodeWithTag("draft").assertTextEquals("retained draft")
        assertTrue(rule.onNodeWithTag("detail").fetchSemanticsNode().boundsInRoot.width > detailWidth + 200)
        assertEquals(stack, shell.nav.currentStack)
        assertEquals(NavMode.DUAL, mode)
        rule.onNodeWithTag(EntryChromeTestTags.LIST_TOGGLE).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("sidebar").assertExists()
        rule.onNodeWithTag("draft").assertTextEquals("retained draft")
    }
}
