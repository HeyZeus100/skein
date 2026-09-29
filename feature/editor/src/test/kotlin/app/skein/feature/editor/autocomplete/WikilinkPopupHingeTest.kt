package app.skein.feature.editor.autocomplete

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.skein.core.designsystem.components.LocalSkeinWindowPartitions
import app.skein.core.designsystem.components.SkeinWindowPartitions
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.testing.ui.skeinComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1000dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WikilinkPopupHingeTest {
    @get:Rule
    val composeRule = skeinComposeRule()

    @Test
    fun `actual autocomplete stays in caret partition and keyboard selection scrolls final row into view`() {
        val top = DpRect(0.dp, 0.dp, 1000.dp, 100.dp)
        val bottom = DpRect(0.dp, 108.dp, 1000.dp, 1000.dp)
        val partitions =
            mutableStateOf<SkeinWindowPartitions?>(
                SkeinWindowPartitions(DpRect(0.dp, 0.dp, 1000.dp, 1000.dp), top, bottom, listOf(top, bottom)),
            )
        var popupBounds = Rect.Zero
        var replacement = ""
        val host =
            object : AutocompleteHost {
                override val textBeforeCursor = "[[No"

                override fun replaceRange(
                    start: Int,
                    end: Int,
                    with: String,
                ) {
                    replacement = with
                }
            }
        lateinit var state: WikilinkAutocompleteState
        composeRule.setContent {
            SkeinTheme {
                CompositionLocalProvider(LocalSkeinWindowPartitions provides partitions.value) {
                    state = rememberWikilinkAutocompleteState(host, suggest = { (1..8).map { Suggestion("Note $it") } })
                    LaunchedEffect(Unit) { state.onTextChanged() }
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.offset(20.dp, 70.dp).size(48.dp)) {
                            WikilinkAutocompletePopup(
                                state,
                                modifier =
                                    Modifier.onGloballyPositioned {
                                        popupBounds =
                                            Rect(
                                                it.localToScreen(Offset.Zero),
                                                Size(it.size.width.toFloat(), it.size.height.toFloat()),
                                            )
                                    },
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        assertTrue("actual separate popup clears hinge: $popupBounds", popupBounds.bottom <= 100f + 1)
        assertTrue("nonempty popup viewport", popupBounds.height >= 48f)
        composeRule.runOnIdle { repeat(state.suggestions.lastIndex) { state.moveDown() } }
        composeRule.onNodeWithTag(wikilinkSuggestionTestTag(state.suggestions.lastIndex)).assertIsDisplayed()
        partitions.value = null
        composeRule.waitForIdle()
        assertTrue("flat restores unconstrained popup height", popupBounds.height > 100f)
        composeRule.onNodeWithTag(wikilinkSuggestionTestTag(state.suggestions.lastIndex)).performClick()
        assertEquals("[[No]]", replacement)
    }

    @Test
    fun `position selects caret side and preserves ordinary RTL offsets without a hinge`() {
        val left = IntRect(0, 0, 490, 1000)
        val right = IntRect(510, 0, 1000, 1000)
        var selected: IntRect? = null
        val provider = WikilinkPopupPosition(IntOffset(60, 10), listOf(left, right)) { selected = it }
        val origin =
            provider.calculatePosition(
                IntRect(480, 300, 580, 350),
                IntSize(1000, 1000),
                LayoutDirection.Ltr,
                IntSize(240, 200),
            )
        assertEquals(right, selected)
        assertEquals(IntOffset(540, 310), origin)
        val flat = WikilinkPopupPosition(IntOffset(60, 10), emptyList()) {}
        assertEquals(
            IntOffset(280, 310),
            flat.calculatePosition(
                IntRect(480, 300, 580, 350),
                IntSize(1000, 1000),
                LayoutDirection.Rtl,
                IntSize(240, 200),
            ),
        )
    }
}
