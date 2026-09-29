package app.skein

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.skein.core.model.Capability
import app.skein.core.model.Model
import app.skein.core.model.ModelFormat
import app.skein.core.model.ModelRecord
import app.skein.core.vault.key.UnlockResult
import app.skein.core.vault.session.LockReason
import app.skein.feature.chat.COMPOSER_TEST_TAG
import app.skein.feature.chat.entries.ChatEntryTestTags
import app.skein.feature.shell.host.SkeinShellHostTestTags
import app.skein.feature.shell.testing.ShellTestTags
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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

    @Test
    @Config(qualifiers = "w524dp-h1175dp-port-330dpi")
    fun `Phone New chat renders a fresh draft on each action and restores the selected draft`() {
        assertFreshNewChats(drawer = true)
    }

    @Test
    @Config(qualifiers = "w1006dp-h1043dp-port-330dpi")
    fun `Dual New chat renders a fresh draft on each action and restores the selected draft`() {
        assertFreshNewChats(drawer = false)
    }

    @Test
    @Config(qualifiers = "w1600dp-h1100dp-land-160dpi")
    fun `Triple New chat renders a fresh draft on each action and restores the selected draft`() {
        assertFreshNewChats(drawer = false)
    }

    private fun assertFreshNewChats(drawer: Boolean) {
        app.enableSessionChat = true
        val model =
            Model(
                id = "new-chat-test-model",
                name = "New chat test model",
                path = "/tmp/new-chat-test-model.gguf",
                sha256 = "a".repeat(64),
                format = ModelFormat.GGUF,
                capabilities = setOf(Capability.TEXT),
                sizeBytes = 1000L,
            )
        runBlocking {
            app.modelRegistry.upsert(ModelRecord(model, blake3 = "b".repeat(64)))
            app.modelRegistry.setDefault(model.id)
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitComposer()
            composeRule.onNodeWithTag(COMPOSER_TEST_TAG).performTextInput("Root draft stays separate")
            newChat(drawer)
            awaitComposer()
            composeRule.onNodeWithTag(COMPOSER_TEST_TAG).assertTextEquals("")
            composeRule.onNodeWithTag(COMPOSER_TEST_TAG).performTextInput("First explicit draft")
            newChat(drawer)
            awaitComposer()
            composeRule.onNodeWithTag(COMPOSER_TEST_TAG).assertTextEquals("")
            composeRule.onNodeWithTag(COMPOSER_TEST_TAG).performTextInput("Second explicit draft")
            composeRule.onNodeWithTag(COMPOSER_TEST_TAG).performSemanticsAction(SemanticsActions.SetSelection) {
                it(6, 6, false)
            }
            scenario.recreate()
            awaitComposer()
            composeRule.onNodeWithTag(COMPOSER_TEST_TAG).assertTextEquals("Second explicit draft")
            assertEquals(
                TextRange(6),
                composeRule
                    .onNodeWithTag(
                        COMPOSER_TEST_TAG,
                    ).fetchSemanticsNode()
                    .config[SemanticsProperties.TextSelectionRange],
            )
        }
    }

    private fun newChat(drawer: Boolean) {
        if (drawer) {
            composeRule.onNodeWithContentDescription("Open navigation").performSemanticsAction(SemanticsActions.OnClick)
            composeRule.onNodeWithText(" New chat").performSemanticsAction(SemanticsActions.OnClick)
        } else {
            composeRule.onNodeWithContentDescription("New chat").performSemanticsAction(SemanticsActions.OnClick)
        }
    }

    private fun awaitComposer() {
        awaitTag(COMPOSER_TEST_TAG)
        composeRule.waitUntil("draft composer ready", WAIT_MILLIS) {
            !composeRule
                .onNodeWithTag(
                    COMPOSER_TEST_TAG,
                ).fetchSemanticsNode()
                .config
                .contains(SemanticsProperties.Disabled)
        }
    }

    private companion object {
        const val WAIT_MILLIS = 30_000L
    }
}
