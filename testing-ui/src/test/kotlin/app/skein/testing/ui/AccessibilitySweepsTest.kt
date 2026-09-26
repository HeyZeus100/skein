// skein-xtov.23.19 (UT-5): each helper's self-test — a deliberately bad
// fixture the helper must FAIL, and a good one it must pass — proving the
// assertions actually assert something rather than always passing.
package app.skein.testing.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// GraphicsMode.NATIVE: assertNoTextOverflow's fixtures need real glyph
// metrics (Robolectric's legacy graphics mode stubs text measurement to
// near-zero widths, so nothing would ever appear to overflow) — same reason
// the screenshot tests already use it.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccessibilitySweepsTest {
    @get:Rule
    val composeRule = skeinComposeRule()

    // -- assertTouchTargets --------------------------------------------

    @Test
    fun `assertTouchTargets fails a node that doesn't clear an explicitly larger minimum`() {
        // This Compose Foundation version auto-expands `clickable`'s own
        // touchBoundsInRoot to 48dp regardless of visual size (verified: a
        // raw 24dp clickable Box, even packed edge-to-edge against
        // neighbours, still reports 48dp touch bounds here) — so a
        // genuine *default-threshold* violation can't be reproduced through
        // ordinary composition in this Compose version. Exercising the same
        // comparison against an explicitly stricter [min] instead is exactly
        // as real a test of this helper's logic.
        composeRule.setContent {
            Box(Modifier.testTag("tiny").size(48.dp).clickable {})
        }

        val error = assertThrows(AssertionError::class.java) { composeRule.assertTouchTargets(min = 200.dp) }

        assertThat(error.message).contains("tiny")
    }

    @Test
    fun `assertTouchTargets passes a small clickable Box because clickable auto-expands its touch bounds`() {
        composeRule.setContent {
            // Visually 24dp, but `Modifier.clickable` pads touchBoundsInRoot
            // to 48dp itself — proving the helper reads that property rather
            // than the node's visual size.
            Box(Modifier.testTag("small-but-clickable").size(24.dp).clickable {})
        }

        composeRule.assertTouchTargets()
    }

    // -- assertEveryActionIsNamed ----------------------------------------

    @Test
    fun `assertEveryActionIsNamed fails a clickable node with no text or description`() {
        composeRule.setContent {
            Box(Modifier.testTag("glyph").size(48.dp).clickable {})
        }

        val error = assertThrows(AssertionError::class.java) { composeRule.assertEveryActionIsNamed() }

        assertThat(error.message).contains("glyph")
    }

    @Test
    fun `assertEveryActionIsNamed passes a clickable node with a content description`() {
        composeRule.setContent {
            Box(
                Modifier
                    .testTag("send")
                    .size(48.dp)
                    .semantics { contentDescription = "Send message" }
                    .clickable {},
            )
        }

        composeRule.assertEveryActionIsNamed()
    }

    // -- assertNoTextOverflow ---------------------------------------------

    @Test
    fun `assertNoTextOverflow fails a Text node that is clipped at this width`() {
        composeRule.setContent {
            BasicText(
                text = "This line is far too long to fit in the width given to it",
                modifier = Modifier.testTag("clipped").width(40.dp),
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }

        val error = assertThrows(AssertionError::class.java) { composeRule.assertNoTextOverflow() }

        assertThat(error.message).contains("clipped")
    }

    @Test
    fun `assertNoTextOverflow passes a Text node that fits`() {
        composeRule.setContent {
            BasicText(
                text = "Short",
                modifier = Modifier.testTag("fits").width(200.dp),
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }

        composeRule.assertNoTextOverflow()
    }

    @Test
    fun `assertNoTextOverflow exempts a node the caller marks as intentionally ellipsized`() {
        composeRule.setContent {
            BasicText(
                text = "This line is also far too long to fit in the width given to it",
                modifier = Modifier.testTag("intended-ellipsis").width(40.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        composeRule.assertNoTextOverflow(intentionallyEllipsized = hasTestTag("intended-ellipsis"))
    }

    // -- assertNoDeadControls ----------------------------------------------

    @Test
    fun `assertNoDeadControls fails a click handler the recorder never hears from`() {
        val recorder = ClickRecorder()
        composeRule.setContent {
            Box(Modifier.testTag("dead").size(48.dp).clickable {})
        }

        val error = assertThrows(AssertionError::class.java) { composeRule.assertNoDeadControls(recorder) }

        assertThat(error.message).contains("dead")
    }

    @Test
    fun `assertNoDeadControls passes a click handler wired to the recorder`() {
        val recorder = ClickRecorder()
        composeRule.setContent {
            Box(Modifier.testTag("alive").size(48.dp).clickable { recorder.recordClick() })
        }

        composeRule.assertNoDeadControls(recorder)

        assertThat(recorder.count).isEqualTo(1)
    }
}
