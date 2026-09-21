package app.skein

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.skein.core.vault.key.UnlockResult
import app.skein.feature.shell.testing.ShellTestTags
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import us.aherrera.skein.core.model.DocumentKind
import us.aherrera.skein.core.model.NewDocument

/**
 * E10.I1's sample Compose UI test, extended by skein-2ige: a
 * Robolectric-hosted Compose rule exercising [MainActivity]'s real content
 * the way a device would render it. The activity is now gated on the vault
 * (`SkeinApplication.vault`), so the host application is
 * [TestSkeinApplication] — an in-memory vault behind a scripted key
 * provider — and each test launches the activity itself (an
 * `ActivityScenario` + [createEmptyComposeRule]) so it can script the
 * unlock outcome first. Pinned to SDK 34 (bd memory
 * `robolectric-sdk37-needs-java21`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestSkeinApplication::class)
class MainActivityComposeTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val app: TestSkeinApplication
        get() = ApplicationProvider.getApplicationContext()

    private fun awaitTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = WAIT_MILLIS) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `MainActivity shows the biometric unlock screen while the vault is locked`() {
        app.keyProvider.nextUnlock = { UnlockResult.NotInitialised }

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_ROOT).assertExists()
        }
    }

    @Test
    fun `MainActivity renders the Skein shell once the vault is unlocked and open`() {
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag(ShellTestTags.SKEIN_SHELL_ROOT)

            composeRule.onNodeWithTag(ShellTestTags.SKEIN_SHELL_ROOT).assertExists()
        }
    }

    @Test
    fun `the CommandBar renders the model status once unlocked`() {
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag(ShellTestTags.SKEIN_SHELL_ROOT)

            // CommandBar composes this as "<name> · ●", so substring-match
            // rather than exact so a future icon or separator change doesn't
            // break the test.
            composeRule.onNodeWithText("qwen", substring = true).assertExists()
        }
    }

    @Test
    fun `MainActivity does not render the shell while the vault is locked`() {
        app.keyProvider.nextUnlock = { UnlockResult.NotInitialised }

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.onNodeWithTag(ShellTestTags.SKEIN_SHELL_ROOT).assertDoesNotExist()
        }
    }

    @Test
    fun `a vault that fails to open shows the gate's failure state with a retry`() {
        app.failOpenWith = "wrong vault key"

        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag(VaultGateTestTags.OPEN_FAILED)

            composeRule.onNodeWithTag(VaultGateTestTags.RETRY).assertExists()
        }
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `the unlocked shell shows the timeline instead of No tabs open`() {
        runBlocking {
            app.repository.createDocument(
                NewDocument(kind = DocumentKind.NOTE, title = "Seeded Note", bodyMd = "content"),
            )
        }

        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag(ShellTestTags.SKEIN_SHELL_ROOT)
            composeRule.waitUntil(timeoutMillis = WAIT_MILLIS) {
                composeRule.onAllNodesWithContentDescription("Note").fetchSemanticsNodes().isNotEmpty()
            }

            // skein-64y9: a fresh launch (no tabs open, compact/single-pane
            // width) now lands on the timeline instead of `TabHost`'s "No
            // tabs open" placeholder — the seeded note shows up as a
            // `TimelineRail` glyph, accessible via its kind label.
            composeRule.onNodeWithContentDescription("Note").assertExists()
            composeRule.onNodeWithText("No tabs open", substring = true).assertDoesNotExist()
        }
    }

    @Test
    fun `retrying after a failed open brings the shell up once the vault opens`() {
        app.failOpenWith = "wrong vault key"

        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag(VaultGateTestTags.RETRY)
            app.failOpenWith = null
            composeRule.onNodeWithTag(VaultGateTestTags.RETRY).performClick()
            awaitTag(ShellTestTags.SKEIN_SHELL_ROOT)

            composeRule.onNodeWithTag(ShellTestTags.SKEIN_SHELL_ROOT).assertExists()
        }
    }

    private companion object {
        const val WAIT_MILLIS = 5_000L
    }
}
