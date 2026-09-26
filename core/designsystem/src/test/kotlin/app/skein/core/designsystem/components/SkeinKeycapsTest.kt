package app.skein.core.designsystem.components

import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.onNodeWithContentDescription
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.testing.ui.skeinComposeRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** §7.3: "Shortcut hints (keycaps...) appear only while a hardware keyboard is attached." */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkeinKeycapsTest {
    @get:Rule
    val composeRule = skeinComposeRule()

    @Test
    fun `pure rule - present only with a hardware keyboard that is not folded away`() {
        val qwerty = Configuration.KEYBOARD_QWERTY
        val noKeys = Configuration.KEYBOARD_NOKEYS
        val notHidden = Configuration.HARDKEYBOARDHIDDEN_NO
        val hidden = Configuration.HARDKEYBOARDHIDDEN_YES

        assertTrue(isSkeinHardwareKeyboardPresent(configuration(qwerty, notHidden)))
        assertFalse(isSkeinHardwareKeyboardPresent(configuration(noKeys, notHidden)))
        assertFalse(isSkeinHardwareKeyboardPresent(configuration(qwerty, hidden)))
    }

    @Test
    fun `keycaps are hidden with no hardware keyboard attached`() {
        composeRule.setContent {
            SkeinTheme {
                val noKeyboard =
                    configuration(Configuration.KEYBOARD_NOKEYS, Configuration.HARDKEYBOARDHIDDEN_YES)
                CompositionLocalProvider(LocalConfiguration provides noKeyboard) {
                    SkeinKeycaps("Ctrl", "K")
                }
            }
        }
        composeRule.onNodeWithContentDescription("Ctrl+K").assertDoesNotExist()
    }

    @Test
    fun `keycaps render with their chord as the TalkBack label when a hardware keyboard is attached`() {
        composeRule.setContent {
            SkeinTheme {
                val withKeyboard =
                    configuration(Configuration.KEYBOARD_QWERTY, Configuration.HARDKEYBOARDHIDDEN_NO)
                CompositionLocalProvider(LocalConfiguration provides withKeyboard) {
                    SkeinKeycaps("Ctrl", "K")
                }
            }
        }
        composeRule.onNodeWithContentDescription("Ctrl+K").assertExists()
    }

    private fun configuration(
        keyboard: Int,
        hardKeyboardHidden: Int,
    ): Configuration =
        Configuration().apply {
            this.keyboard = keyboard
            this.hardKeyboardHidden = hardKeyboardHidden
        }
}
