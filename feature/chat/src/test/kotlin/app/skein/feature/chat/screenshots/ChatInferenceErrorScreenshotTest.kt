package app.skein.feature.chat.screenshots

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.core.model.InferenceException
import app.skein.core.navigation.ChatKey
import app.skein.core.navigation.SkeinId
import app.skein.feature.chat.COMPOSER_TEST_TAG
import app.skein.feature.chat.ERROR_BANNER_TEST_TAG
import app.skein.feature.chat.ERROR_FIXTURE_NOW
import app.skein.feature.chat.ERROR_FIXTURE_QUERY
import app.skein.feature.chat.InferenceErrorFixture
import app.skein.feature.chat.SEND_BUTTON_TEST_TAG
import app.skein.feature.chat.entries.EntriesHost
import app.skein.feature.shell.host.SkeinShellState
import app.skein.testing.ui.SkeinDevice
import app.skein.testing.ui.UxDeviceRule
import app.skein.testing.ui.UxSpec
import app.skein.testing.ui.captureUx
import app.skein.testing.ui.skeinComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatInferenceErrorScreenshotTest(
    private val spec: UxSpec,
) {
    @get:Rule(order = 0)
    val deviceRule = UxDeviceRule(spec)

    @get:Rule(order = 1)
    val composeRule = skeinComposeRule()

    @Test
    fun contextFull() = captureFailure(InferenceException.ContextFull(), "context-full")

    @Test
    fun requestTooLarge() = captureFailure(InferenceException.TransactionTooLarge(), "request-too-large")

    @Test
    fun modelChanged() = captureFailure(InferenceException.ModelChanged(), "model-changed")

    private fun captureFailure(
        failure: InferenceException,
        state: String,
    ) {
        val fixture = InferenceErrorFixture(failure)
        lateinit var shell: SkeinShellState
        composeRule.setContent {
            SkeinTheme {
                EntriesHost(
                    fixture.repository,
                    fixture.pipeline,
                    size = null,
                    onShell = { shell = it },
                    clock = { ERROR_FIXTURE_NOW },
                )
            }
        }
        composeRule.runOnIdle { shell.navigate { goTo(it, ChatKey(SkeinId.of(fixture.chat.id))) } }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(COMPOSER_TEST_TAG).performTextInput(ERROR_FIXTURE_QUERY)
        composeRule.onNodeWithTag(SEND_BUTTON_TEST_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ERROR_BANNER_TEST_TAG).assertIsDisplayed()
        composeRule.onRoot().captureUx(spec, "chat-error-$state")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun parameters(): List<Array<Any>> =
            listOf(SkeinDevice.FOLD_OUTER_443, SkeinDevice.FOLD_OUTER_524, SkeinDevice.FOLD_INNER_1007_LAND)
                .flatMap { device ->
                    listOf(false, true).flatMap { dark ->
                        listOf(1f, 1.5f).map { scale -> arrayOf<Any>(UxSpec(device, dark, scale)) }
                    }
                }
    }
}
