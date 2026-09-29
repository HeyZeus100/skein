package app.skein.feature.chat

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.core.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MessageListAnchoringTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `latest message stays bottom anchored when the viewport shrinks and new messages stay chronological`() {
        val height = mutableStateOf(400.dp)
        val messages = mutableStateOf((0..19).map(::message))
        composeRule.setContent {
            SkeinTheme {
                MessageList(
                    messages.value,
                    null,
                    emptyMap(),
                    { _, _ -> false },
                    { _, _ -> },
                    Modifier.fillMaxWidth().height(height.value),
                )
            }
        }
        composeRule.onNodeWithTag(messageRowTestTag("19")).assertIsDisplayed()
        val gapBefore = bottomGap("19")
        composeRule.runOnIdle { height.value = 200.dp }
        composeRule.onNodeWithTag(messageRowTestTag("19")).assertIsDisplayed()
        assertEquals("keyboard resize keeps newest bottom anchored", gapBefore, bottomGap("19"), 1f)
        composeRule.runOnIdle { messages.value = messages.value + message(20) }
        composeRule.onNodeWithTag(messageRowTestTag("20")).assertIsDisplayed()
        val older = composeRule.onNodeWithTag(messageRowTestTag("19")).fetchSemanticsNode().boundsInRoot
        val newest = composeRule.onNodeWithTag(messageRowTestTag("20")).fetchSemanticsNode().boundsInRoot
        assertTrue("reversed item storage still reads chronologically top to bottom", older.bottom < newest.top)
        assertEquals(gapBefore, bottomGap("20"), 1f)
    }

    private fun bottomGap(id: String): Float =
        composeRule
            .onNodeWithTag(MESSAGE_LIST_TEST_TAG)
            .fetchSemanticsNode()
            .boundsInRoot.bottom -
            composeRule
                .onNodeWithTag(messageRowTestTag(id))
                .fetchSemanticsNode()
                .boundsInRoot.bottom

    private fun message(index: Int) =
        ChatMessageUi(index.toString(), Role.USER, "Message $index", false, emptyMap(), index.toLong())
}
