package app.skein

import android.os.Bundle
import android.os.Parcel
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.skein.core.model.ChatDraftKey
import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.NewDocument
import app.skein.core.model.NewMessage
import app.skein.core.model.Role
import app.skein.core.vault.key.UnlockResult
import app.skein.core.vault.session.LockReason
import app.skein.feature.chat.COMPOSER_TEST_TAG
import app.skein.feature.chat.entries.ChatEntryTestTags
import app.skein.feature.shell.host.SkeinShellHostTestTags
import app.skein.feature.shell.testing.ShellTestTags
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** AL-10/15: production MainActivity + NavShell + controlled drafts, with repository/key seams faked. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w524dp-h1175dp-port-330dpi", application = TestSkeinApplication::class)
class MainActivityDraftRetentionTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val app: TestSkeinApplication get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `production conversation restores the session draft and selection after activity recreation`() {
        val chat = seedChat()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openChat()
            typeDraft()
            var original: MainActivity? = null
            scenario.onActivity { original = it }
            assertPrivateBundle(scenario)
            scenario.recreate()
            awaitTag(COMPOSER_TEST_TAG)
            scenario.onActivity { assertNotSame(original, it) }
            assertDraft()
            assertEquals(1, runBlocking { app.repository.listMessages(chat.id) }.size)
            assertPrivateBundle(scenario)
        }
    }

    @Test
    fun `production lock removes content and unlock loads the encrypted-row draft with its selection`() {
        val chat = seedChat()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openChat()
            typeDraft()
            val succeed = app.keyProvider.nextUnlock
            app.keyProvider.nextUnlock = { UnlockResult.UserCancelled }
            runBlocking { app.vault.unlockManager.lockAndAwait(LockReason.USER_REQUESTED) }
            awaitTag(ShellTestTags.BIOMETRIC_UNLOCK_RETRY_BUTTON)
            composeRule.onNodeWithTag(SkeinShellHostTestTags.NAV_DISPLAY).assertDoesNotExist()
            composeRule.onNodeWithTag(COMPOSER_TEST_TAG).assertDoesNotExist()
            val stored = runBlocking { app.repository.readDraft(ChatDraftKey.Existing(chat.id)) }
            assertEquals(DRAFT, stored?.text)
            assertEquals(CARET, stored?.selectionStart)
            assertEquals(CARET, stored?.selectionEnd)
            assertPrivateBundle(scenario)
            // ComponentActivity save lowers its Lifecycle to CREATED. Complete the platform's
            // stop/start cycle before interacting with the replacement gate composition.
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            awaitTag(ShellTestTags.BIOMETRIC_UNLOCK_RETRY_BUTTON)

            app.keyProvider.nextUnlock = succeed
            composeRule
                .onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_RETRY_BUTTON)
                .performSemanticsAction(SemanticsActions.OnClick)
            awaitTag(COMPOSER_TEST_TAG)
            assertDraft()
            assertPrivateBundle(scenario)
        }
    }

    private fun seedChat(): Document {
        app.enableSessionChat = true
        return runBlocking {
            app.repository.createDocument(NewDocument(DocumentKind.CHAT, TITLE, "")).also {
                app.repository.appendMessage(it.id, NewMessage(Role.USER, MESSAGE))
            }
        }
    }

    private fun openChat() {
        awaitTag(ChatEntryTestTags.LANDING)
        composeRule.onNode(hasText(TITLE) and hasAnyAncestor(hasTestTag(ChatEntryTestTags.LANDING))).performClick()
        awaitTag(COMPOSER_TEST_TAG)
    }

    private fun typeDraft() {
        composeRule.onNodeWithTag(COMPOSER_TEST_TAG).performTextInput(DRAFT)
        composeRule.onNodeWithTag(COMPOSER_TEST_TAG).performSemanticsAction(SemanticsActions.SetSelection) {
            it(CARET, CARET, false)
        }
        assertDraft()
    }

    private fun assertDraft() {
        composeRule.onNodeWithTag(COMPOSER_TEST_TAG).assertTextEquals(DRAFT)
        assertEquals(
            TextRange(CARET),
            composeRule
                .onNodeWithTag(
                    COMPOSER_TEST_TAG,
                ).fetchSemanticsNode()
                .config[SemanticsProperties.TextSelectionRange],
        )
    }

    private fun awaitTag(tag: String) {
        composeRule.waitUntil("production tag $tag", 30_000) {
            try {
                composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
            } catch (_: IllegalStateException) {
                // The lock removes the session composition before the gate's replacement frame.
                false
            }
        }
    }

    /**
     * Scans the actual serialized Parcel, including nested Parcelable fields, rather than just
     * Bundle strings. This covers these three sentinels and the 64 KiB cap; the D7 all-surface
     * allowlist/epoch-number audit and fresh-process restoration remain separate acceptance gates.
     */
    private fun assertPrivateBundle(scenario: ActivityScenario<MainActivity>) {
        val saved = Bundle()
        scenario.onActivity {
            InstrumentationRegistry.getInstrumentation().callActivityOnSaveInstanceState(it, saved)
        }
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(saved)
            val bytes = parcel.marshall()
            assertTrue("saved state exceeds D7's 64 KiB limit", bytes.size < 64 * 1024)
            for (charset in listOf(Charsets.UTF_8, Charsets.UTF_16LE, Charsets.UTF_16BE)) {
                val serialized = String(bytes, charset)
                for (sentinel in listOf(TITLE, MESSAGE, DRAFT)) {
                    assertFalse("private fixture text reached the OS saved-state Parcel", serialized.contains(sentinel))
                }
            }
            parcel.setDataPosition(0)
            val roundTrip = parcel.readBundle(MainActivity::class.java.classLoader)
            assertEquals(saved.keySet(), roundTrip?.keySet())
        } finally {
            parcel.recycle()
        }
    }

    private companion object {
        const val TITLE = "private-fold-title-98c2"
        const val MESSAGE = "private-fold-message-7ec4"
        const val DRAFT = "private-fold-draft-2b0f with a caret"
        const val CARET = 10
    }
}
