package app.skein

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import app.skein.feature.shell.testing.ShellTestTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * E10.I1's sample Compose UI test: a Robolectric-hosted Compose test rule
 * exercising [MainActivity]'s real content the way a real device would
 * render it, without a device. Launches [MainActivity] directly (it is
 * already declared and exported in `AndroidManifest.xml` — see
 * `ManifestPolicyTest`) rather than relying on `ui-test-manifest`'s generic
 * `ComponentActivity` registration. Pinned to SDK 34 (bd memory
 * `robolectric-sdk37-needs-java21`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityComposeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `MainActivity renders the Skein shell and CommandBar`() {
        // Verify the shell root has been rendered (stable test tag, not brittle text)
        composeRule.onNodeWithTag(ShellTestTags.SKEIN_SHELL_ROOT).assertExists()
        // Verify the CommandBar renders the model status. CommandBar composes
        // this as "<name> · ●", so substring-match rather than exact so a
        // future icon or separator change doesn't break the test.
        composeRule.onNodeWithText("qwen", substring = true).assertExists()
    }
}
