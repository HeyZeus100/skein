package app.skein.feature.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import app.skein.core.designsystem.components.LocalSkeinWindowPartitions
import app.skein.core.designsystem.components.SkeinWindowPartitions
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.core.model.Persona
import app.skein.core.model.TimelineFilter
import app.skein.testing.ui.skeinComposeRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1000dp-h1000dp-mdpi")
class TimelineHingeMenuTest {
    @get:Rule val composeRule = skeinComposeRule()

    @Test
    fun `production menu fits launcher hinge partition and retains actions`() {
        var selected: String? = null
        val partitions =
            SkeinWindowPartitions(
                DpRect(0.dp, 0.dp, 1000.dp, 1000.dp),
                DpRect(0.dp, 0.dp, 1000.dp, 490.dp),
                DpRect(0.dp, 510.dp, 1000.dp, 1000.dp),
                listOf(DpRect(0.dp, 0.dp, 1000.dp, 490.dp), DpRect(0.dp, 510.dp, 1000.dp, 1000.dp)),
            )
        composeRule.setContent {
            SkeinTheme {
                CompositionLocalProvider(LocalSkeinWindowPartitions provides partitions) {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.offset(y = 420.dp)) {
                            FilterBar(
                                kinds = emptySet(),
                                filter = TimelineFilter(),
                                personas =
                                    listOf(
                                        Persona("work", "Work", null, null, 0L),
                                    ),
                                tags = emptyList(),
                                onPersona = {
                                    selected =
                                        it
                                },
                                onKind = {},
                                onTag = {},
                            )
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithText("Space").performClick()
        composeRule.waitForIdle()
        val popup = composeRule.onNode(isPopup()).fetchSemanticsNode()
        val bounds = Rect(popup.positionOnScreen, Size(popup.size.width.toFloat(), popup.size.height.toFloat()))
        assertTrue("actual popup has content: $bounds", bounds.height > 48f)
        assertTrue("actual popup stays above crease: $bounds", bounds.bottom <= 491f)
        assertTrue("actual popup stays in window: $bounds", bounds.top >= 0f)
        composeRule.onNodeWithText("Work").performClick()
        assertTrue(selected == "work")
    }
}
