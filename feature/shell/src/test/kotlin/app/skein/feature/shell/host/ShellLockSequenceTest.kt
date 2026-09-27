// skein-xtov.24.7 (AL-08): the shell's half of the lock sequence
// (SECURITY_REVIEW_D7.md §6) through a real `UnlockManager`: pending writes are
// drained before the vault closes (M8), and the session entry stores are
// cleared by the lock itself — also while the Activity is stopped and its
// recomposer paused (M12) — after the key is zeroed. The TEARDOWN observer
// stands in for `VaultBootstrap`'s vault close.
package app.skein.feature.shell.host

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import app.skein.core.model.DocumentKind
import app.skein.core.model.NewDocument
import app.skein.core.navigation.ChatHomeKey
import app.skein.core.navigation.ChatKey
import app.skein.core.navigation.Destination
import app.skein.core.navigation.ObjectKind
import app.skein.core.vault.session.LockObserver
import app.skein.core.vault.session.LockObserverPriority
import app.skein.core.vault.session.LockReason
import app.skein.core.vault.session.UnlockManager
import app.skein.core.vault.session.UnlockState
import app.skein.testing.InMemoryVaultRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShellLockSequenceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<FragmentActivity>()

    private val events = CopyOnWriteArrayList<String>()
    private val manager = UnlockManager(keyProvider = RecordingKeyProvider(events))
    private val ledger = ProbeLedger()
    private lateinit var shell: SkeinShellState

    /** The shell, unlocked, with an open chat whose entry holds a T3 ViewModel. */
    private fun openChat() {
        composeRule.setContent {
            shell = rememberSkeinShellState(manager)
            SkeinShellHost(shell, { ids -> ids.associateWith { ObjectKind.CHAT } }) { key ->
                Probe(key, ledger) { events += "entry ViewModel cleared" }
            }
        }
        manager.unlockFor(composeRule.activity)
        composeRule.runOnIdle { shell.navigate { goTo(it, ChatKey(CHAT_A)) } }
        composeRule.onNodeWithTag(probeTag(ChatKey(CHAT_A))).assertExists()
    }

    private fun onVaultClose(block: suspend () -> Unit) {
        manager.addLockObserver(
            object : LockObserver {
                override val priority = LockObserverPriority.TEARDOWN

                override suspend fun onLocking(
                    epoch: Long,
                    budgetMillis: Long,
                ) = block()

                override fun onLocked(epoch: Long) = Unit

                override fun onUnlocked(epoch: Long) = Unit
            },
        )
    }

    @Test
    fun `the lock drains writers, then closes the vault, then zeroes the key, then clears the session`() {
        openChat()
        shell.stores.addPendingWriter {
            delay(50)
            events += "writer drained"
        }
        onVaultClose { events += "vault closed" }
        shell.stores.doOnLocked { events += "hook ran (${manager.state.value::class.simpleName})" }

        runBlocking { manager.lockAndAwait(LockReason.USER_REQUESTED) }

        assertEquals(
            listOf(
                "writer drained",
                "vault closed",
                "key zeroed",
                // The Chat root's and the chat's, both still in the stack.
                "entry ViewModel cleared",
                "entry ViewModel cleared",
                "hook ran (Locked)",
            ),
            events,
        )
        assertEquals(0, shell.stores.size)
    }

    @Test
    fun `a pending write is in the vault when the vault closes`() {
        openChat()
        val vault = InMemoryVaultRepository()
        shell.stores.addPendingWriter { vault.createDocument(NewDocument(DocumentKind.NOTE, "draft", bodyMd = "")) }
        val dropped = shell.stores.addPendingWriter { events += "a disposed writer ran" }
        dropped.dispose()
        var rowsAtClose = -1
        onVaultClose { rowsAtClose = vault.searchTitles("draft").size }

        runBlocking { manager.lockAndAwait(LockReason.IDLE_TIMEOUT) }

        assertEquals(1, rowsAtClose)
        assertTrue(events.none { it == "a disposed writer ran" })
    }

    @Test
    fun `the stacks and T2 survive a lock and unlock, with fresh T3 holders`() {
        val open = mutableStateOf(true)
        composeRule.setContent {
            shell = rememberSkeinShellState(manager)
            // What VaultGate does: the shell below it leaves composition while locked.
            if (open.value) {
                SkeinShellHost(shell, { ids -> ids.associateWith { ObjectKind.CHAT } }) { Probe(it, ledger) }
            }
        }
        manager.unlockFor(composeRule.activity)
        composeRule.runOnIdle { shell.navigate { goTo(it, ChatKey(CHAT_A)) } }
        val chat = composeRule.onNodeWithTag(probeTag(ChatKey(CHAT_A)))
        chat.performClick()
        chat.performClick()
        chat.assert(hasText("vm=2 t2=2"))

        runBlocking { manager.lockAndAwait(LockReason.USER_REQUESTED) }
        open.value = false
        composeRule.waitForIdle()
        manager.unlockFor(composeRule.activity)
        open.value = true
        composeRule.waitForIdle()

        assertEquals(listOf(ChatHomeKey, ChatKey(CHAT_A)), shell.nav.stack(Destination.CHAT))
        // The same place and T2 count; a new ViewModel (serial 3), since the lock cleared the old one.
        composeRule.onNodeWithTag(probeTag(ChatKey(CHAT_A))).assert(hasText("vm=3 t2=2"))
    }

    @Test
    fun `the lock clears every entry ViewModel while the Activity is stopped`() {
        openChat()
        val compositions = ledger.compositions.get()
        // Screen off: the Activity stops and Compose pauses its recomposer.
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)

        runBlocking { manager.lockAndAwait(LockReason.SCREEN_OFF_POLICY) }

        assertTrue(manager.state.value is UnlockState.Locked)
        assertEquals(listOf("chat.home", "chat/${CHAT_A.value}").sorted(), ledger.cleared.sorted())
        assertEquals(0, shell.stores.size)
        assertEquals("cleared by the lock, not by a recomposition", compositions, ledger.compositions.get())
    }
}
