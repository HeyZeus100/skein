package app.skein

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.skein.core.vault.key.UnlockResult
import app.skein.core.vault.session.LockReason
import app.skein.feature.chat.entries.ChatEntryTestTags
import app.skein.feature.editor.entries.KnowledgeEntryTestTags
import app.skein.feature.models.entries.ModelsEntryTestTags
import app.skein.feature.shell.container.SkeinNavContainerTestTags
import app.skein.feature.shell.testing.ShellTestTags
import app.skein.shell.NotificationDeepLinks
import app.skein.shell.NotificationDestination
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual exported Activity, vault gate, lifecycle and NavDisplay; no alternate routing fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestSkeinApplication::class)
class MainActivityNotificationDeepLinkTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val app: TestSkeinApplication get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `initial notification waits for the real vault session before opening Models`() {
        app.openReadySignal = CompletableDeferred()
        ActivityScenario.launch<MainActivity>(link("models")).use { scenario ->
            awaitTag(VaultGateTestTags.OPENING)
            composeRule.onNodeWithTag(ShellTestTags.SKEIN_SHELL_ROOT).assertDoesNotExist()
            assertPending(scenario, NotificationDestination.MODELS)
            app.openReadySignal.complete(Unit)
            awaitTag(ModelsEntryTestTags.IMPORT_ACTION)
            assertPending(scenario, null)
        }
    }

    @Test
    fun `locked pending survives recreation and newest valid intent is applied after unlock`() {
        val succeed = app.keyProvider.nextUnlock
        app.keyProvider.nextUnlock = { UnlockResult.UserCancelled }
        ActivityScenario.launch<MainActivity>(link("models")).use { scenario ->
            awaitTag(ShellTestTags.BIOMETRIC_UNLOCK_RETRY_BUTTON)
            scenario.recreate()
            awaitTag(ShellTestTags.BIOMETRIC_UNLOCK_RETRY_BUTTON)
            assertPending(scenario, NotificationDestination.MODELS)
            deliver(scenario, link("ingest"))
            deliver(scenario, link("models?delete=private-document"))
            assertPending(scenario, NotificationDestination.KNOWLEDGE)
            composeRule.onNodeWithTag(KnowledgeEntryTestTags.LIST).assertDoesNotExist()
            app.keyProvider.nextUnlock = succeed
            click(ShellTestTags.BIOMETRIC_UNLOCK_RETRY_BUTTON)
            awaitTag(KnowledgeEntryTestTags.LIST)
            // Merely tapping the notification cannot fabricate a running indexing pass.
            composeRule.onNodeWithTag(KnowledgeEntryTestTags.PREPARATION_STATUS).assertDoesNotExist()
            assertPending(scenario, null)
        }
    }

    @Test
    fun `new intent routes while open and consumed initial notification never replays on recreation`() {
        ActivityScenario.launch<MainActivity>(link("models")).use { scenario ->
            awaitTag(ModelsEntryTestTags.IMPORT_ACTION)
            deliver(scenario, link("ingest"))
            awaitTag(KnowledgeEntryTestTags.LIST)
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            awaitTag(ChatEntryTestTags.LANDING)
            scenario.recreate()
            awaitTag(ChatEntryTestTags.LANDING)
            composeRule.onNodeWithTag(ModelsEntryTestTags.IMPORT_ACTION).assertDoesNotExist()
            assertPending(scenario, null)
        }
    }

    @Test
    fun `notification taps dismiss an open drawer for both same and different destinations`() {
        ActivityScenario.launch<MainActivity>(link("models")).use { scenario ->
            awaitTag(ModelsEntryTestTags.IMPORT_ACTION)
            openDrawer()
            deliver(scenario, link("models"))
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(SkeinNavContainerTestTags.DRAWER_SHEET).assertIsNotDisplayed()
            composeRule.onNodeWithTag(ModelsEntryTestTags.IMPORT_ACTION).assertIsDisplayed()
            openDrawer()
            deliver(scenario, link("ingest"))
            awaitTag(KnowledgeEntryTestTags.LIST)
            composeRule.onNodeWithTag(SkeinNavContainerTestTags.DRAWER_SHEET).assertIsNotDisplayed()
            composeRule.onNodeWithTag(KnowledgeEntryTestTags.LIST).assertIsDisplayed()
        }
    }

    @Test
    fun `hostile initial data and navigation extras cannot seed a stack or reach the saved Bundle`() {
        val hostile =
            link("models/$SENTINEL")
                .putExtra("topLevel", "MODELS")
                .putExtra("stack", SENTINEL)
                .putExtra("delete", true)
        ActivityScenario.launch<MainActivity>(hostile).use { scenario ->
            awaitTag(ChatEntryTestTags.LANDING)
            deliver(scenario, link("ingest").setAction(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, SENTINEL))
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(ChatEntryTestTags.LANDING).assertExists()
            val saved = Bundle()
            scenario.onActivity {
                InstrumentationRegistry.getInstrumentation().callActivityOnSaveInstanceState(it, saved)
            }
            val strings = bundleStrings(saved)
            assertFalse(strings.any { it.contains(SENTINEL) || it.contains("app://skein/") })
            assertPending(scenario, null)
        }
    }

    @Test
    fun `lock while stopped clears stale pending and a later locked intent waits for a new session`() {
        val succeed = app.keyProvider.nextUnlock
        app.openReadySignal = CompletableDeferred()
        ActivityScenario.launch<MainActivity>(link("models")).use { scenario ->
            awaitTag(VaultGateTestTags.OPENING)
            assertPending(scenario, NotificationDestination.MODELS)
            scenario.moveToState(Lifecycle.State.CREATED)
            app.keyProvider.nextUnlock = { UnlockResult.UserCancelled }
            runBlocking { app.vault.unlockManager.lockAndAwait(LockReason.USER_REQUESTED) }
            assertPending(scenario, null)
            deliver(scenario, link("ingest"))
            assertPending(scenario, NotificationDestination.KNOWLEDGE)
            scenario.moveToState(Lifecycle.State.RESUMED)
            awaitTag(ShellTestTags.BIOMETRIC_UNLOCK_RETRY_BUTTON)
            app.openReadySignal.complete(Unit)
            app.keyProvider.nextUnlock = succeed
            click(ShellTestTags.BIOMETRIC_UNLOCK_RETRY_BUTTON)
            awaitTag(KnowledgeEntryTestTags.LIST)
            assertPending(scenario, null)
        }
    }

    @Test
    fun `actual vault reset clears pending notification before creating the replacement vault`() {
        app.keyProvider.nextUnlock = { UnlockResult.Failed("key envelope corrupt") }
        ActivityScenario.launch<MainActivity>(link("models")).use { scenario ->
            awaitTag(ShellTestTags.BIOMETRIC_UNLOCK_RESET_BUTTON)
            assertPending(scenario, NotificationDestination.MODELS)
            click(ShellTestTags.BIOMETRIC_UNLOCK_RESET_BUTTON)
            awaitTag(ShellTestTags.VAULT_RESET_ROOT)
            composeRule.onNodeWithTag(ShellTestTags.VAULT_RESET_CONFIRM_FIELD).performTextInput("RESET")
            click(ShellTestTags.VAULT_RESET_CONTINUE_BUTTON)
            awaitTag(ShellTestTags.VAULT_RESET_FINAL_BUTTON)
            click(ShellTestTags.VAULT_RESET_FINAL_BUTTON)
            awaitTag(ShellTestTags.VAULT_SETUP_ROOT)
            assertPending(scenario, null)
        }
    }

    private fun link(path: String) =
        Intent(Intent.ACTION_MAIN, Uri.parse("app://skein/$path"), app, MainActivity::class.java)

    private fun deliver(
        scenario: ActivityScenario<MainActivity>,
        intent: Intent,
    ) {
        scenario.onActivity { InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(it, intent) }
    }

    private fun assertPending(
        scenario: ActivityScenario<MainActivity>,
        destination: NotificationDestination?,
    ) {
        scenario.onActivity {
            val pending = ViewModelProvider(it)[NotificationDeepLinks::class.java].pending.value
            if (destination == null) assertNull(pending) else assertEquals(destination, pending)
        }
    }

    private fun click(tag: String) = composeRule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)

    private fun openDrawer() {
        composeRule.onNodeWithContentDescription("Open navigation").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithTag(SkeinNavContainerTestTags.DRAWER_SHEET).assertIsDisplayed()
    }

    private fun awaitTag(tag: String) {
        composeRule.waitUntil("test tag $tag", 30_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Suppress("DEPRECATION")
    private fun bundleStrings(value: Any?): List<String> =
        when (value) {
            is Bundle -> value.keySet().flatMap { listOf(it) + bundleStrings(value.get(it)) }
            is List<*> -> value.flatMap(::bundleStrings)
            is Array<*> -> value.flatMap(::bundleStrings)
            is String -> listOf(value)
            else -> emptyList()
        }

    private companion object {
        const val SENTINEL = "private-title-and-command-that-must-not-restore"
    }
}
