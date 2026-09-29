// AL-11: dispatched insets exercise the real Nav3 entries, not the vault-gate
// EdgeToEdgeSurface. This is Compose geometry evidence, not a real IME/device run.
package app.skein.feature.chat

import android.app.Application
import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.core.model.Document
import app.skein.core.model.Role
import app.skein.core.navigation.ChatKey
import app.skein.core.navigation.SkeinId
import app.skein.feature.chat.entries.ChatEntryTestTags
import app.skein.feature.chat.entries.EntriesHost
import app.skein.feature.chat.entries.pipelineOver
import app.skein.feature.shell.host.SkeinShellState
import app.skein.testing.fakeVault
import app.skein.testing.scriptedEngine
import app.skein.testing.ui.SkeinDevice
import app.skein.testing.ui.UxDeviceRule
import app.skein.testing.ui.UxSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34])
class ChatComposerImeInsetTest(
    private val device: SkeinDevice,
) {
    @get:Rule(order = 1)
    val registerHost =
        object : ExternalResource() {
            override fun before() {
                val app = ApplicationProvider.getApplicationContext<Application>()
                Shadows
                    .shadowOf(
                        app.packageManager,
                    ).addActivityIfNotPresent(ComponentName(app, ChatImeTestActivity::class.java))
            }
        }

    @get:Rule(order = 0)
    val deviceRule = UxDeviceRule(UxSpec(device, dark = false))

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<ChatImeTestActivity>()

    @Test
    fun `only the chat pane clears IME while its adjacent list keeps its geometry`() {
        lateinit var doc: Document
        val vault = fakeVault { doc = chat("Chat", Role.USER to "Question", Role.ASSISTANT to "Latest answer") }
        val pipeline = pipelineOver(vault, scriptedEngine())
        lateinit var shell: SkeinShellState
        composeRule.setContent { SkeinTheme { EntriesHost(vault, pipeline, null, { shell = it }) } }
        composeRule.runOnIdle { shell.navigate { goTo(it, ChatKey(SkeinId.of(doc.id))) } }
        composeRule.onNodeWithTag(COMPOSER_TEST_TAG).performClick().performTextInput("a draft")
        composeRule.waitForIdle()
        dispatchInsets(ime = 0, nav = 80)
        val composerBefore = bounds(COMPOSER_TEST_TAG)
        val transcriptBefore = bounds(MESSAGE_LIST_TEST_TAG)
        val adjacentBefore =
            if (device ==
                SkeinDevice.FOLD_INNER_1007
            ) {
                bounds(ChatEntryTestTags.CONVERSATIONS)
            } else {
                null
            }
        val decor = composeRule.activity.window.decorView
        val keyboardHeight = (decor.height * 0.4f).toInt()
        val keyboardTop = decor.height - keyboardHeight
        assertTrue("non-vacuous: keyboard would cover the composer", composerBefore.bottom > keyboardTop)

        dispatchInsets(ime = keyboardHeight, nav = 80)
        val composerAfter = bounds(COMPOSER_TEST_TAG)
        val transcriptDp = bounds(MESSAGE_LIST_TEST_TAG).height / composeRule.activity.resources.displayMetrics.density
        println("AL11 geometry device=$device transcript_with_injected_ime_dp=$transcriptDp")
        assertTrue("composer above keyboard", composerAfter.bottom <= keyboardTop + TOLERANCE)
        assertTrue("transcript gives keyboard room", bounds(MESSAGE_LIST_TEST_TAG).height < transcriptBefore.height)
        assertTrue("no double IME plus nav padding", composerAfter.bottom > keyboardTop - 100)
        adjacentBefore?.let {
            assertEquals("adjacent list is covered, never resized", it, bounds(ChatEntryTestTags.CONVERSATIONS))
        }
        composeRule.onNodeWithTag(COMPOSER_TEST_TAG).assertIsFocused()

        dispatchInsets(ime = 0, nav = 80)
        assertTrue(
            "composer clears gesture navigation",
            bounds(COMPOSER_TEST_TAG).bottom <= decor.height - 80 + TOLERANCE,
        )
        assertEquals(
            "closing IME restores geometry",
            composerBefore.bottom,
            bounds(COMPOSER_TEST_TAG).bottom,
            TOLERANCE,
        )
    }

    @Test
    fun `long pasted drafts scroll inside the bounded composer without consuming the transcript`() {
        lateinit var doc: Document
        val vault = fakeVault { doc = chat("Chat", Role.USER to "Question", Role.ASSISTANT to "Latest answer") }
        val pipeline = pipelineOver(vault, scriptedEngine())
        lateinit var shell: SkeinShellState
        composeRule.setContent { SkeinTheme { EntriesHost(vault, pipeline, null, { shell = it }) } }
        composeRule.runOnIdle { shell.navigate { goTo(it, ChatKey(SkeinId.of(doc.id))) } }
        val singleLine = bounds(COMPOSER_TEST_TAG).height
        composeRule.onNodeWithTag(COMPOSER_TEST_TAG).performTextInput((1..24).joinToString("\n") { "draft line $it" })
        val limit =
            when (device) {
                SkeinDevice.FOLD_OUTER_443_LAND -> 3
                SkeinDevice.FOLD_INNER_1007 -> 8
                else -> 6
            }
        assertTrue(
            "pasted lines must scroll inside the field",
            bounds(COMPOSER_TEST_TAG).height <= singleLine * limit + TOLERANCE,
        )
        assertTrue(
            "transcript keeps forty percent of pane height",
            bounds(MESSAGE_LIST_TEST_TAG).height >= bounds(CHAT_SCREEN_TEST_TAG).height * 0.4f - TOLERANCE,
        )
        if (device == SkeinDevice.FOLD_OUTER_524) {
            dispatchInsets(ime = 700, nav = 80)
            assertTrue(
                "compact keyboard caps at three lines",
                bounds(COMPOSER_TEST_TAG).height <= singleLine * 3 + TOLERANCE,
            )
        }
    }

    private fun dispatchInsets(
        ime: Int,
        nav: Int,
    ) {
        composeRule.runOnUiThread {
            ViewCompat.dispatchApplyWindowInsets(
                composeRule.activity.window.decorView,
                WindowInsetsCompat
                    .Builder()
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime))
                    .setVisible(WindowInsetsCompat.Type.ime(), ime > 0)
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, nav))
                    .setVisible(WindowInsetsCompat.Type.navigationBars(), nav > 0)
                    .build(),
            )
        }
        composeRule.waitForIdle()
    }

    private fun bounds(tag: String): Rect = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    companion object {
        private const val TOLERANCE = 4f

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun devices(): List<Array<Any>> =
            listOf(
                SkeinDevice.FOLD_OUTER_524,
                SkeinDevice.FOLD_OUTER_443_LAND,
                SkeinDevice.FOLD_INNER_1007,
            ).map { arrayOf<Any>(it) }
    }
}

class ChatImeTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(android.R.style.Theme_Material_NoActionBar)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
    }
}
