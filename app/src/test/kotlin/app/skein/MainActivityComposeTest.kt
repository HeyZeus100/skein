package app.skein

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
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
    fun `MainActivity renders the Skein root text`() {
        composeRule.onNodeWithText("Skein").assertExists()
    }
}
