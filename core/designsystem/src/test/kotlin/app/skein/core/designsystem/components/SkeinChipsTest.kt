package app.skein.core.designsystem.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import app.skein.core.designsystem.icons.SkeinIcons
import app.skein.core.designsystem.theme.SkeinSize
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.testing.ui.skeinComposeRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §7.1: every chip's *touch* target is 48 dp even where its visual height
 * is 32/36 dp (§10.7) — `minimumInteractiveComponentSize` widens the hit
 * rectangle, not the chip's own rendered box, so
 * [assertTouchHeightIsEqualTo] (not a plain bounds assertion, which would
 * measure the smaller visual box) is the correct check here. Also covers
 * each chip's semantics: selected state, click action, and the remove
 * action's TalkBack label.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkeinChipsTest {
    @get:Rule
    val composeRule = skeinComposeRule()

    @Test
    fun `filter chip meets the 48 dp touch target and reports its click`() {
        var selected = false
        composeRule.setContent {
            SkeinTheme {
                SkeinFilterChip(
                    label = "Notes",
                    selected = selected,
                    onClick = { selected = !selected },
                    modifier = Modifier.testTag(TAG),
                )
            }
        }
        composeRule.onNodeWithTag(TAG).assertTouchHeightIsEqualTo(SkeinSize.touchTarget).assertIsNotSelected()
        composeRule.onNodeWithTag(TAG).performClick()
        assertTrue("onClick should have flipped the caller's selection state", selected)
    }

    @Test
    fun `filter chip renders as selected once toggled on`() {
        composeRule.setContent {
            SkeinTheme {
                SkeinFilterChip(label = "Notes", selected = true, onClick = {}, modifier = Modifier.testTag(TAG))
            }
        }
        composeRule.onNodeWithTag(TAG).assertIsSelected()
    }

    @Test
    fun `context chip meets the 48 dp touch target and exposes the caller's full-state description`() {
        val fullState = "Context: 2 notes, knowledge on. Double-tap to inspect."
        composeRule.setContent {
            SkeinTheme {
                SkeinContextChip(
                    label = "2 notes · Knowledge on",
                    onClick = {},
                    modifier = Modifier.testTag(TAG),
                    contentDescription = fullState,
                )
            }
        }
        composeRule.onNodeWithTag(TAG).assertTouchHeightIsEqualTo(SkeinSize.touchTarget).assertHasClickAction()
        composeRule.onNodeWithContentDescription(fullState).assertExists()
    }

    @Test
    fun `sources chip meets the 48 dp touch target and pluralises its count`() {
        composeRule.setContent {
            SkeinTheme {
                SkeinSourcesChip(count = 1, onClick = {}, modifier = Modifier.testTag("one"))
                SkeinSourcesChip(count = 3, onClick = {}, modifier = Modifier.testTag("three"))
            }
        }
        composeRule.onNodeWithTag("one").assertTouchHeightIsEqualTo(SkeinSize.touchTarget)
        composeRule.onNode(hasText("1 source")).assertExists()
        composeRule.onNode(hasText("3 sources")).assertExists()
    }

    @Test
    fun `input chip meets the 48 dp touch target and its remove action carries a TalkBack label`() {
        var removed = false
        composeRule.setContent {
            SkeinTheme {
                SkeinInputChip(
                    label = "Fold launch plan",
                    onRemove = { removed = true },
                    modifier = Modifier.testTag(TAG),
                    leadingIcon = SkeinIcons.Note,
                    removeLabel = "Remove Fold launch plan",
                )
            }
        }
        composeRule.onNodeWithTag(TAG).assertTouchHeightIsEqualTo(SkeinSize.touchTarget)
        composeRule.onNodeWithContentDescription("Remove Fold launch plan").performClick()
        assertTrue("the remove action should have fired", removed)
    }

    private companion object {
        const val TAG = "chip"
    }
}
