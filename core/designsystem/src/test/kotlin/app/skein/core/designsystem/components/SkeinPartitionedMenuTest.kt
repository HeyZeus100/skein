package app.skein.core.designsystem.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.testing.ui.skeinComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1000dp-h1000dp-mdpi")
class SkeinPartitionedMenuTest {
    @get:Rule(order = 0)
    val fontScale =
        object : ExternalResource() {
            override fun before() {
                RuntimeEnvironment.setFontScale(2f)
            }
        }

    @get:Rule(order = 1)
    val composeRule = skeinComposeRule()

    @Test
    fun `actual popup stays above tabletop hinge at large text and keeps selection`() {
        val expanded = mutableStateOf(true)
        val partitions =
            mutableStateOf<SkeinWindowPartitions?>(
                SkeinWindowPartitions(
                    DpRect(0.dp, 0.dp, 1000.dp, 1000.dp),
                    DpRect(0.dp, 0.dp, 1000.dp, 490.dp),
                    DpRect(0.dp, 510.dp, 1000.dp, 1000.dp),
                    listOf(DpRect(0.dp, 0.dp, 1000.dp, 490.dp), DpRect(0.dp, 510.dp, 1000.dp, 1000.dp)),
                ),
            )
        var menuBounds = Rect.Zero
        var selected = -1
        composeRule.setContent {
            SkeinTheme {
                CompositionLocalProvider(
                    LocalSkeinWindowPartitions provides partitions.value,
                ) {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.offset(20.dp, 420.dp).size(48.dp)) {
                            SkeinDropdownMenu(
                                expanded = expanded.value,
                                onDismissRequest = { expanded.value = false },
                                anchorBounds = DpRect(20.dp, 420.dp, 68.dp, 468.dp),
                                modifier =
                                    Modifier.testTag("menu").onGloballyPositioned {
                                        val origin = it.localToScreen(Offset.Zero)
                                        menuBounds =
                                            Rect(
                                                origin,
                                                androidx.compose.ui.geometry.Size(
                                                    it.size.width.toFloat(),
                                                    it.size.height.toFloat(),
                                                ),
                                            )
                                    },
                            ) {
                                repeat(4) { index ->
                                    DropdownMenuItem(text = { Text("Space $index") }, onClick = { selected = index })
                                }
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        assertTrue("popup visible: $menuBounds", menuBounds.height > 100f)
        assertTrue("popup cannot cross crease: $menuBounds", menuBounds.bottom <= 490f + 1)
        assertTrue("popup in window: $menuBounds", menuBounds.top >= 0f)
        composeRule.onNodeWithText("Space 0").performClick()
        assertEquals(0, selected)
        partitions.value = null
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Space 0").performClick()
        assertEquals(0, selected)
    }
}
