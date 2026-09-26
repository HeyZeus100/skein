package app.skein.feature.editor.notetab

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import app.skein.core.model.DocumentKind
import app.skein.core.model.NewDocument
import app.skein.testing.InMemoryIndexStore
import app.skein.testing.InMemoryVaultRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream

/**
 * UX-P0-12 (Save-as half) / K-P0-7: a Save as `.md`/`.docx` destination that
 * can't be opened or written shows a message instead of crashing the app.
 * Drives the real activity-result callback in [NoteTab] through Robolectric's
 * `ShadowActivity.receiveResult`, same launcher `ShareMenuTest` inspects.
 *
 * Pinned to SDK 34 (bd memory `robolectric-sdk37-needs-java21`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SaveAsIoErrorIsReportedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val destination: Uri = Uri.parse("content://app.skein.test.documents/note.md")

    @Test
    fun writeFailureShowsAMessageAndDoesNotCrash() {
        val failing =
            object : OutputStream() {
                override fun write(b: Int): Unit = throw IOException("disk full")
            }
        shadowOf(composeRule.activity.contentResolver).registerOutputStream(destination, failing)

        saveAsMarkdownTo(destination)

        assertEquals(SAVE_AS_FAILED_MESSAGE, ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun unopenableDestinationShowsAMessageAndDoesNotCrash() {
        shadowOf(composeRule.activity.contentResolver)
            .registerOutputStreamSupplier(destination) { throw FileNotFoundException("gone") }

        saveAsMarkdownTo(destination)

        assertEquals(SAVE_AS_FAILED_MESSAGE, ShadowToast.getTextOfLatestToast())
    }

    private fun saveAsMarkdownTo(uri: Uri) {
        val repo = InMemoryVaultRepository()
        val doc = runBlocking { repo.createDocument(NewDocument(DocumentKind.NOTE, title = "N", bodyMd = "body")) }
        composeRule.setContent {
            MaterialTheme { NoteTab(docId = doc.id, vaultRepository = repo, indexStore = InMemoryIndexStore()) }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(NoteTabTestTags.SHARE_BUTTON).performClick()
        composeRule.onNodeWithTag(NoteTabTestTags.SHARE_MENU_SAVE_MARKDOWN).performClick()
        composeRule.waitForIdle()
        val activity = shadowOf(composeRule.activity)
        val launched = activity.nextStartedActivityForResult
        activity.receiveResult(launched.intent, Activity.RESULT_OK, Intent().setData(uri))
        composeRule.waitForIdle()
    }
}
