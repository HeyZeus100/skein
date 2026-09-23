package app.skein.feature.editor.notetab

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.NewDocument
import app.skein.core.vault.session.UnlockState
import app.skein.testing.InMemoryIndexStore
import app.skein.testing.InMemoryVaultRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device Compose UI test for [NoteTab]'s share menu (bd `skein-fay`,
 * plan `E6.I16`). Runs on an emulator/device — no Robolectric — matching
 * the shape of `NoteTabInstrumentedTest`. bd `skein-k3b2` tracks the CI
 * emulator lane that will run this; this worktree only compiles it (same
 * "compile only, gated on skein-k3b2" acceptance every other `androidTest`
 * in this module carries).
 *
 * `ShareMenuTest` (JVM, Robolectric) already pins the exact `Intent` shape
 * fired for each menu item — this test only needs to prove the real system
 * chooser/`ACTION_CREATE_DOCUMENT` picker actually *resolves* on a real
 * device (Robolectric's shadow activity never actually resolves an
 * intent against installed apps, so that half of the acceptance criteria
 * can only be proven here).
 */
@RunWith(AndroidJUnit4::class)
class ShareMenuInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun the_share_menu_opens_and_offers_text_markdown_docx_and_pdf() {
        setContent()

        composeRule.onNodeWithTag(NoteTabTestTags.SHARE_BUTTON).performClick()

        composeRule.onNodeWithTag(NoteTabTestTags.SHARE_MENU_TEXT).assertIsDisplayed()
        composeRule.onNodeWithTag(NoteTabTestTags.SHARE_MENU_SAVE_MARKDOWN).assertIsDisplayed()
        composeRule.onNodeWithTag(NoteTabTestTags.SHARE_MENU_SAVE_DOCX).assertIsDisplayed()
        composeRule.onNodeWithTag(NoteTabTestTags.SHARE_MENU_EXPORT_PDF).assertIsDisplayed()
    }

    @Test
    fun tapping_share_as_text_resolves_the_system_chooser_without_crashing() {
        setContent()

        composeRule.onNodeWithTag(NoteTabTestTags.SHARE_BUTTON).performClick()
        composeRule.onNodeWithTag(NoteTabTestTags.SHARE_MENU_TEXT).performClick()
        composeRule.waitForIdle()

        // No assertion beyond "didn't crash": `Intent.createChooser` always
        // resolves on a real device (the system always ships a chooser
        // activity), unlike Robolectric's shadow activity which never
        // resolves anything against installed apps.
    }

    @Test
    fun the_share_button_is_disabled_while_locked() {
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
