package app.skein

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.skein.core.vault.key.UnlockResult
import app.skein.core.vault.session.LockReason
import app.skein.feature.chat.entries.ChatEntryTestTags
import app.skein.feature.shell.host.SkeinShellHostTestTags
import app.skein.feature.shell.testing.ShellTestTags
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * skein-xtov.24.7 (AL-08): `MainActivity`'s default
 * NavDisplay shell. Its state is hoisted above `VaultGate`, the shell itself
 * composes only while the gate is open (M4a), and a lock takes it out of
 * composition until the next unlock. Same harness as [ShellHotfixComposeTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestSkeinApplication::class)
class NavShellComposeTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val app: TestSkeinApplication
        get() = ApplicationProvider.getApplicationContext()

    private fun awaitTag(tag: String) {
        try {
            composeRule.waitUntil("test tag \"$tag\"", WAIT_MILLIS) {
                composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: ComposeTimeoutException) {
            val tree = runCatching { composeRule.onRoot().printToString() }.getOrElse { "<failed: $it>" }
            throw AssertionError("Timed out waiting for $tag\n${tree.take(4_000)}", e)
        }
    }

    @Test
    fun `the default shell is NavDisplay behind the gate, across a lock`() {
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag(SkeinShellHostTestTags.NAV_DISPLAY)
            composeRule.onNodeWithTag(ShellTestTags.SKEIN_SHELL_ROOT).assertExists()
            // skein-xtov.24.8: NavShell's real entries; a drawer window's Chat root is the landing.
            awaitTag(ChatEntryTestTags.LANDING)

            val succeed = app.keyProvider.nextUnlock
            app.keyProvider.nextUnlock = { UnlockResult.UserCancelled }
            runBlocking { app.vault.unlockManager.lockAndAwait(LockReason.USER_REQUESTED) }
            awaitTag(ShellTestTags.BIOMETRIC_UNLOCK_RETRY_BUTTON)
            composeRule.onNodeWithTag(SkeinShellHostTestTags.NAV_DISPLAY).assertDoesNotExist()

            app.keyProvider.nextUnlock = succeed
            composeRule
                .onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_RETRY_BUTTON)
                .performSemanticsAction(SemanticsActions.OnClick)
            awaitTag(SkeinShellHostTestTags.NAV_DISPLAY)
        }
    }

    private companion object {
        const val WAIT_MILLIS = 30_000L
    }
}
