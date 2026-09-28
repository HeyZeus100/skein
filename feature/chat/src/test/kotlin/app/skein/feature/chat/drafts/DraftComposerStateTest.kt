package app.skein.feature.chat.drafts

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.Lifecycle
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.core.model.ChatDraft
import app.skein.core.model.ChatDraftKey
import app.skein.core.model.VaultRepository
import app.skein.feature.shell.input.SecureBasicTextField
import app.skein.testing.InMemoryVaultRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DraftComposerStateTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository = InMemoryVaultRepository()
    private lateinit var store: SessionDraftStore
    private lateinit var adapter: DraftComposerState
    private val key = ChatDraftKey.New("space-a", "01926f3a-7c00-7000-8000-000000000001")

    @After
    fun closeSession() {
        if (::store.isInitialized) store.close()
        sessionScope.cancel()
    }

    @Test
    fun `controlled field restores text and selection and keeps IME range out of storage`() {
        runBlocking { repository.writeDraft(key, ChatDraft("recovered", 2, 5)) }
        show()
        composeRule.runOnIdle {
            assertEquals(TextFieldValue("recovered", TextRange(2, 5)), adapter.value)
            adapter.onValueChange(TextFieldValue("composing", TextRange(3), TextRange(0, 3)))
        }
        composeRule.runOnIdle {
            assertEquals(TextRange(0, 3), adapter.value.composition)
            assertEquals("composing", adapter.snapshot?.draft?.text)
            assertEquals(3, adapter.snapshot?.draft?.selectionStart)
        }
        runBlocking { store.flushOnStop() }
        assertEquals(ChatDraft("composing", 3), runBlocking { repository.readDraft(key) })
    }

    @Test
    fun `leaving and reentering composition preserves session draft and stopping flushes it`() {
        var shown by mutableStateOf(true)
        show(visible = { shown })
        composeRule.onNodeWithTag(FIELD).performTextInput("unsent draft")
        assertNull(runBlocking { repository.readDraft(key) })
        composeRule.runOnIdle { shown = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle { shown = true }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals("unsent draft", adapter.value.text) }
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("unsent draft", runBlocking { repository.readDraft(key) }?.text)
    }

    @Test
    fun `load failure disables field until explicit retry and close clears exposed text`() {
        runBlocking { repository.writeDraft(key, ChatDraft("encrypted draft")) }
        var failRead = true
        val failing =
            object : VaultRepository by repository {
                override suspend fun readDraft(key: ChatDraftKey): ChatDraft? {
                    if (failRead) error("private diagnostic")
                    return repository.readDraft(key)
                }
            }
        show(failing)
        composeRule.onNodeWithTag(FIELD).assertIsNotEnabled()
        composeRule.runOnIdle {
            assertEquals(DraftLoadState.LoadError, adapter.status)
            adapter.onValueChange(TextFieldValue("must not overwrite"))
            assertFalse(adapter.enabled)
            failRead = false
            adapter.retryLoad()
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertTrue(adapter.enabled)
            assertEquals("encrypted draft", adapter.value.text)
            store.close()
            assertEquals("", adapter.value.text)
            assertFalse(adapter.enabled)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(FIELD).assertIsNotEnabled()
        composeRule.runOnIdle {
            assertEquals(DraftLoadState.Closed, adapter.status)
            assertEquals("", adapter.value.text)
        }
    }

    private fun show(
        repo: VaultRepository = repository,
        visible: () -> Boolean = { true },
    ) {
        store = SessionDraftStore(repo, 1, { 1 }, { null }, sessionScope, debounceMillis = 60_000)
        composeRule.setContent {
            SkeinTheme {
                if (visible()) {
                    adapter = rememberDraftComposerState(store, key)
                    SecureBasicTextField(
                        value = adapter.value,
                        onValueChange = adapter::onValueChange,
                        enabled = adapter.enabled,
                        modifier = Modifier.testTag(FIELD),
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private companion object {
        const val FIELD = "draft-composer-fixture"
    }
}
