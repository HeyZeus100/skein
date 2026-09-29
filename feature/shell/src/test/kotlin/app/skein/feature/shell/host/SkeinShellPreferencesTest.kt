package app.skein.feature.shell.host

import android.os.Bundle
import androidx.compose.runtime.key
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.fragment.app.FragmentActivity
import app.skein.core.navigation.Destination
import app.skein.core.vault.session.UnlockManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkeinShellPreferencesTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<FragmentActivity>()

    private val manager = UnlockManager(keyProvider = RecordingKeyProvider())
    private lateinit var primary: SkeinShellState
    private lateinit var secondary: SkeinShellState

    @Test
    fun `pane owners and root drafts are distinct while preferences survive restoration`() {
        val restorer = StateRestorationTester(composeRule)
        restorer.setContent {
            primary = key("primary") { rememberSkeinShellState(manager, "primary") }
            secondary = key("secondary") { rememberSkeinShellState(manager, "secondary") }
        }
        composeRule.runOnIdle {
            assertNotSame(primary.stores, secondary.stores)
            assertNotSame(
                primary.entryState.getValue(Destination.CHAT),
                secondary.entryState.getValue(Destination.CHAT),
            )
            assertEquals("00000000-0000-0000-0000-000000000001", primary.rootDraftId)
            assertNotEquals(primary.rootDraftId, secondary.rootDraftId)
            primary.setNewChatKnowledgeEnabled(CHAT_A.value, primary.rootDraftId, false)
            primary.toggleList(Destination.CHAT)
            secondary.toggleList(Destination.KNOWLEDGE)
        }
        restorer.emulateSavedInstanceStateRestore()
        composeRule.runOnIdle {
            assertFalse(primary.newChatKnowledgeEnabled(CHAT_A.value, primary.rootDraftId))
            assertTrue(secondary.newChatKnowledgeEnabled(CHAT_A.value, secondary.rootDraftId))
            assertFalse(primary.isListExpanded(Destination.CHAT))
            assertTrue(primary.isListExpanded(Destination.KNOWLEDGE))
            assertFalse(secondary.isListExpanded(Destination.KNOWLEDGE))
        }
    }

    @Test
    fun `preferences reject content keys and remain bounded to sixteen draft identities`() {
        composeRule.setContent { primary = rememberSkeinShellState(manager) }
        composeRule.runOnIdle {
            assertThrows(IllegalArgumentException::class.java) {
                primary.setNewChatKnowledgeEnabled("PRIVATE SPACE TEXT", DRAFT_D.value, false)
            }
            assertThrows(IllegalArgumentException::class.java) {
                primary.setNewChatKnowledgeEnabled(CHAT_A.value, "PRIVATE DRAFT TEXT", false)
            }
            repeat(20) { index ->
                primary.setNewChatKnowledgeEnabled(
                    CHAT_A.value,
                    "0190a3c4-5b6d-7e8f-9a0b-${index.toString().padStart(12, '0')}",
                    false,
                )
            }
            assertEquals(16, primary.newChatKnowledgeChoices.size)
            assertTrue(primary.newChatKnowledgeEnabled(CHAT_A.value, "0190a3c4-5b6d-7e8f-9a0b-000000000000"))
            assertFalse(primary.newChatKnowledgeEnabled(CHAT_A.value, "0190a3c4-5b6d-7e8f-9a0b-000000000019"))
        }
    }

    @Test
    fun `vault reset clears presentation choices without affecting another owner`() {
        composeRule.setContent {
            primary = key("primary") { rememberSkeinShellState(manager, "primary") }
            secondary = key("secondary") { rememberSkeinShellState(manager, "secondary") }
        }
        composeRule.runOnIdle {
            primary.setNewChatKnowledgeEnabled(CHAT_A.value, primary.rootDraftId, false)
            secondary.setNewChatKnowledgeEnabled(CHAT_A.value, secondary.rootDraftId, false)
            primary.toggleList(Destination.CHAT)
            primary.resetForNewVault()
        }
        composeRule.runOnIdle {
            assertTrue(primary.newChatKnowledgeEnabled(CHAT_A.value, primary.rootDraftId))
            assertTrue(primary.isListExpanded(Destination.CHAT))
            assertFalse(secondary.newChatKnowledgeEnabled(CHAT_A.value, secondary.rootDraftId))
        }
    }

    @Test
    fun `malformed optional preference fields are ignored without accepting arbitrary text`() {
        composeRule.setContent { primary = rememberSkeinShellState(manager) }
        composeRule.runOnIdle {
            val wrongTypes =
                Bundle().apply {
                    putString("skein_list_visibility", "private sentinel")
                    putStringArrayList("skein_draft_choices", arrayListOf("private sentinel"))
                }
            primary.restorePreferences(wrongTypes)
            assertTrue(primary.isListExpanded(Destination.CHAT))
            assertTrue(primary.newChatKnowledgeChoices.isEmpty())
            val entries =
                Bundle().apply {
                    putParcelableArrayList(
                        "skein_draft_choices",
                        arrayListOf(
                            Bundle().apply { putString("space", "private sentinel") },
                            Bundle().apply {
                                putString("space", CHAT_A.value)
                                putString("draft", primary.rootDraftId)
                                putInt("enabled", 0)
                            },
                        ),
                    )
                }
            primary.restorePreferences(entries)
            assertEquals(1, primary.newChatKnowledgeChoices.size)
            assertFalse(primary.newChatKnowledgeEnabled(CHAT_A.value, primary.rootDraftId))
        }
    }
}
