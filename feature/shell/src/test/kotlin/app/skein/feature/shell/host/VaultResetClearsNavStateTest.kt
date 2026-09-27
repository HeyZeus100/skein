// skein-xtov.24.21 (SECURITY_REVIEW_D7.md M4e): a vault reset clears every
// hoisted stack, the T2 SaveableStateHolders and the session entry stores, so
// no id from the old vault survives in memory or in the saved-state Bundle.
// The shell sits under a test-owned SaveableStateRegistry, the stand-in for the
// Activity's saved state, so the test can read exactly what would be saved.
package app.skein.feature.shell.host

import android.os.Bundle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.fragment.app.FragmentActivity
import app.skein.core.navigation.ChatKey
import app.skein.core.navigation.ObjectKind
import app.skein.core.navigation.SkeinNavigationState
import app.skein.core.vault.session.LockReason
import app.skein.core.vault.session.UnlockManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultResetClearsNavStateTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<FragmentActivity>()

    private val manager = UnlockManager(keyProvider = RecordingKeyProvider())
    private val ledger = ProbeLedger()
    private val registry = SaveableStateRegistry(restoredValues = null) { true }
    private lateinit var shell: SkeinShellState

    @Test
    fun `after a vault reset the stacks are at their roots and nothing of the old vault is saved`() {
        val open = mutableStateOf(true)
        composeRule.setContent {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                shell = rememberSkeinShellState(manager)
                // What VaultGate does: the shell below it leaves composition while locked.
                if (open.value) {
                    SkeinShellHost(shell, { ids -> ids.associateWith { ObjectKind.CHAT } }) { Probe(it, ledger) }
                }
            }
        }
        manager.unlockFor(composeRule.activity)
        composeRule.runOnIdle { shell.navigate { goTo(it, ChatKey(CHAT_A)) } }
        val chat = composeRule.onNodeWithTag(probeTag(ChatKey(CHAT_A)))
        chat.performClick()
        chat.assert(hasText("vm=2 t2=1"))

        // The reset screen is reachable only from the unlock screen: the vault is locked first.
        runBlocking { manager.lockAndAwait(LockReason.USER_REQUESTED) }
        open.value = false
        composeRule.waitForIdle()
        assertTrue("the stack and its T2 are saved across a lock", savedStateNames(CHAT_A.value))

        composeRule.runOnIdle { shell.resetForNewVault() }
        composeRule.waitForIdle()

        assertEquals(SkeinNavigationState.initial(), shell.nav)
        assertEquals(0, shell.stores.size)
        assertFalse("an id of the reset vault is still in the saved state", savedStateNames(CHAT_A.value))

        // The new vault's first unlock: the same id, if it existed again, starts with empty T2.
        manager.unlockFor(composeRule.activity)
        open.value = true
        composeRule.waitForIdle()
        composeRule.runOnIdle { shell.navigate { goTo(it, ChatKey(CHAT_A)) } }
        composeRule.onNodeWithTag(probeTag(ChatKey(CHAT_A))).assert(hasText("vm=4 t2=0"))
    }

    private fun savedStateNames(id: String): Boolean = mentions(registry.performSave(), id)

    private fun mentions(
        value: Any?,
        id: String,
    ): Boolean =
        when (value) {
            is String -> id in value
            is Map<*, *> -> value.any { (k, v) -> mentions(k, id) || mentions(v, id) }
            is Iterable<*> -> value.any { mentions(it, id) }
            is Array<*> -> value.any { mentions(it, id) }
            is Bundle -> value.keySet().any { mentions(it, id) || mentions(bundleValue(value, it), id) }
            else -> value != null && id in value.toString()
        }
}

@Suppress("DEPRECATION") // Bundle.get: any value type the shell's saver may have written.
private fun bundleValue(
    bundle: Bundle,
    key: String,
): Any? = bundle.get(key)
