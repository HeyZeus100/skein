package app.skein.feature.chat

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.core.model.InferenceException
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatInferenceErrorTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `context full explains how to recover without retrying the unchanged request`() {
        val fixture = showFailure(InferenceException.ContextFull())
        composeRule.onNodeWithText(CONTEXT_FULL_BANNER_TEXT).assertIsDisplayed()
        composeRule.onNodeWithTag(RETRY_BUTTON_TEST_TAG).assertDoesNotExist()
        fixture.failure = null
        composeRule.onNodeWithTag(COMPOSER_TEST_TAG).performTextInput("Shorter question")
        composeRule.onNodeWithTag(SEND_BUTTON_TEST_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ERROR_BANNER_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithText(ERROR_FIXTURE_REPLY).assertIsDisplayed()
        assertEquals(2, fixture.preparationAttempts)
    }

    @Test
    fun `oversized transport request uses fixed copy and offers no unchanged retry`() {
        val privateDetail = "transport-secret-sentinel"
        val fixture = showFailure(InferenceException.TransactionTooLarge(privateDetail))
        composeRule.onNodeWithText(REQUEST_TOO_LARGE_BANNER_TEXT).assertIsDisplayed()
        composeRule.onNodeWithText(privateDetail, substring = true).assertDoesNotExist()
        composeRule.onNodeWithTag(RETRY_BUTTON_TEST_TAG).assertDoesNotExist()
        assertEquals(1, fixture.preparationAttempts)
    }

    @Test
    fun `model change allows a fresh preparation and a successful retry`() {
        val fixture = showFailure(InferenceException.ModelChanged())
        composeRule.onNodeWithText(MODEL_CHANGED_BANNER_TEXT).assertIsDisplayed()
        fixture.failure = null
        composeRule.onNodeWithTag(RETRY_BUTTON_TEST_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ERROR_BANNER_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithText(ERROR_FIXTURE_REPLY).assertIsDisplayed()
        assertEquals(2, fixture.preparationAttempts)
    }

    @Test
    fun `view model blocks direct retry of either oversized request type`() =
        runTest {
            for (failure in listOf(InferenceException.ContextFull(), InferenceException.TransactionTooLarge())) {
                val fixture = InferenceErrorFixture(failure)
                val viewModel =
                    ChatViewModel(fixture.chat.id, fixture.repository, fixture.pipeline, {}, backgroundScope)
                viewModel.send(ERROR_FIXTURE_QUERY)
                runCurrent()
                assertFalse(viewModel.canRetry)
                assertEquals(1, fixture.preparationAttempts)
                viewModel.retry()
                runCurrent()
                assertEquals(1, fixture.preparationAttempts)
                assertEquals(1, fixture.repository.listMessages(fixture.chat.id).size)
            }
        }

    private fun showFailure(failure: InferenceException): InferenceErrorFixture {
        val fixture = InferenceErrorFixture(failure)
        composeRule.setContent {
            SkeinTheme {
                ChatScreen(
                    fixture.chat.id,
                    fixture.repository,
                    fixture.pipeline,
                    onOpenSource = {},
                    wikilinkSuggest = { emptyList() },
                    initialMessage = ERROR_FIXTURE_QUERY,
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ERROR_BANNER_TEST_TAG).assertIsDisplayed()
        return fixture
    }
}
