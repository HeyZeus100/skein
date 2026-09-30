package app.skein.feature.chat.entries

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.core.model.Document
import app.skein.core.model.NewMessage
import app.skein.core.model.Role
import app.skein.feature.chat.ChatScreen
import app.skein.testing.fakeVault
import app.skein.testing.scriptedEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatEntryPresentationTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `presentation survives scene disposal and entry store clear scrubs retained transcript`() {
        lateinit var chat: Document
        val vault = fakeVault { chat = chat("Disposable presentation fixture", Role.USER to "first") }
        val pipeline = pipelineOver(vault, scriptedEngine())
        val store = ViewModelStore()
        val owner =
            object : ViewModelStoreOwner {
                override val viewModelStore = store
            }
        val shown = mutableStateOf(true)
        lateinit var current: ChatEntryPresentation
        composeRule.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                if (shown.value) {
                    current =
                        viewModel(key = "chat.presentation") {
                            ChatEntryPresentation(chat.id, vault, pipeline, null, null, null)
                        }
                    SkeinTheme {
                        ChatScreen(chat.id, vault, pipeline, {}, { emptyList() }, presentation = current.model)
                    }
                }
            }
        }
        composeRule.waitUntil { current.model.messages.size == 1 }
        val retained = current
        composeRule.runOnIdle { shown.value = false }
        composeRule.waitForIdle()
        runBlocking { vault.appendMessage(chat.id, NewMessage(Role.ASSISTANT, "arrived offscreen")) }
        composeRule.waitUntil { retained.model.messages.size == 2 }
        composeRule.runOnIdle { shown.value = true }
        composeRule.runOnIdle {
            assertSame(retained, current)
            assertEquals(
                "arrived offscreen",
                current.model.messages
                    .last()
                    .displayText,
            )
            shown.value = false
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            // The production session entry owner calls clear at vault lock or entry pop.
            store.clear()
            assertTrue(retained.model.messages.isEmpty())
        }
        runBlocking { vault.appendMessage(chat.id, NewMessage(Role.USER, "after clear")) }
        composeRule.runOnIdle { assertTrue(retained.model.messages.isEmpty()) }
    }
}
