package app.skein.feature.editor.notetab

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import app.skein.core.vault.session.UnlockState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import us.aherrera.skein.core.model.Document
import us.aherrera.skein.core.model.DocumentKind
import us.aherrera.skein.core.model.NewDocument
import us.aherrera.skein.testing.InMemoryIndexStore
import us.aherrera.skein.testing.InMemoryVaultRepository

/**
 * bd `skein-fay` (plan `E6.I16`): the note header's share menu, exercised
 * through the real composable — not a substituted callback — so a passing
 * test proves [NoteTab] actually calls through to the pure
 * `app.skein.feature.editor.share` helpers and fires the resulting `Intent`
 * via real Android APIs (`Context.startActivity` / an activity-result
 * launcher), inspected here with Robolectric's `ShadowActivity`.
 *
 * Pinned to SDK 34 (bd memory `robolectric-sdk37-needs-java21`), same
 * `createAndroidComposeRule<ComponentActivity>()` shape as `:feature:shell`'s
 * `SkeinAppTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareMenuTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `the share button is visible in the note header`() {
        setContent()

        composeRule.onNodeWithTag(NoteTabTestTags.SHARE_BUTTON).assertIsDisplayed()
    }

    @Test
    fun `tapping share as text starts an ACTION_CHOOSER wrapping ACTION_SEND with the note's title and body`() {
        setContent()

        composeRule.onNodeWithTag(NoteTabTestTags.SHARE_BUTTON).performClick()
        composeRule.onNodeWithTag(NoteTabTestTags.SHARE_MENU_TEXT).performClick()
        composeRule.waitForIdle()

        val started = shadowOf(composeRule.activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, started?.action)
        val wrapped = started?.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        assertEquals(Intent.ACTION_SEND, wrapped?.action)
        assertEquals("Shared Note", wrapped?.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals("shared body", wrapped?.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun `tapping save as markdown launches an ACTION_CREATE_DOCUMENT for result`() {
        setContent()

        composeRule.onNodeWithTag(NoteTabTestTags.SHARE_BUTTON).performClick()
        composeRule.onNodeWithTag(NoteTabTestTags.SHARE_MENU_SAVE_MARKDOWN).performClick()
        composeRule.waitForIdle()

        val launched = shadowOf(composeRule.activity).peekNextStartedActivityForResult()
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, launched?.intent?.action)
        assertEquals("text/markdown", launched?.intent?.type)
        assertEquals("Shared Note.md", launched?.intent?.getStringExtra(Intent.EXTRA_TITLE))
    }

    @Test
    fun `the share button is disabled while the vault is not unlocked`() {
        setContent(unlockState = MutableStateFlow(UnlockState.Locked))

        composeRule.onNodeWithTag(NoteTabTestTags.SHARE_BUTTON).assertIsNotEnabled()
    }

    private fun setContent(unlockState: MutableStateFlow<UnlockState>? = null) {
        val repo = InMemoryVaultRepository()
        val index = InMemoryIndexStore()
        val doc = runBlocking { repo.note("Shared Note", body = "shared body") }

        composeRule.setContent {
            MaterialTheme {
                NoteTab(
                    docId = doc.id,
                    vaultRepository = repo,
                    indexStore = index,
                    unlockState = unlockState,
                )
            }
        }
        composeRule.waitForIdle()
    }

    private suspend fun InMemoryVaultRepository.note(
        title: String,
        body: String,
    ): Document = createDocument(NewDocument(kind = DocumentKind.NOTE, title = title, bodyMd = body))
}
