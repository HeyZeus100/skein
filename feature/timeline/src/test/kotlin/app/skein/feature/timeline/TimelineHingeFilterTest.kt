package app.skein.feature.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import app.skein.core.designsystem.components.LocalSkeinWindowPartitions
import app.skein.core.designsystem.components.SkeinWindowPartitions
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.core.model.DocumentKind
import app.skein.core.model.Persona
import app.skein.testing.fakeVault
import app.skein.testing.ui.skeinComposeRule
import kotlinx.coroutines.flow.flowOf
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
class TimelineHingeFilterTest {
    @get:Rule
    val composeRule = skeinComposeRule()

    @Test
    fun `actual fixed filter row clears near-top hinge and restores without changing selection`() {
        val top = DpRect(0.dp, 0.dp, 1000.dp, 100.dp)
        val bottom = DpRect(0.dp, 108.dp, 1000.dp, 1000.dp)
        val partitions =
            mutableStateOf<SkeinWindowPartitions?>(
                SkeinWindowPartitions(DpRect(0.dp, 0.dp, 1000.dp, 1000.dp), top, bottom, listOf(top, bottom)),
            )
        val vault = fakeVault { note("Note", "Body") }
        val personas = flowOf(emptyList<Persona>())
        lateinit var state: TimelineState
        composeRule.setContent {
            SkeinTheme {
                CompositionLocalProvider(LocalSkeinWindowPartitions provides partitions.value) {
                    state = rememberTimelineState(vault, personaSource = personas)
                    Box(Modifier.fillMaxSize().padding(top = 80.dp)) {
                        TimelineScreen(state, onEntryClick = {})
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val filters = composeRule.onNodeWithTag(TimelineTestTags.FILTER_BAR).fetchSemanticsNode().boundsInRoot
        assertTrue("fixed filters clear near-top hinge: $filters", filters.top >= 108f)
        composeRule.onNodeWithTag(TimelineTestTags.kindChip(DocumentKind.NOTE)).assertIsDisplayed().performClick()
        val selected = state.window.value.filter
        partitions.value = null
        composeRule.waitForIdle()
        val flat = composeRule.onNodeWithTag(TimelineTestTags.FILTER_BAR).fetchSemanticsNode().boundsInRoot
        assertEquals("flat keeps original content origin", 80f, flat.top, 1f)
        assertEquals("posture preserves filter selection", selected, state.window.value.filter)
        partitions.value = SkeinWindowPartitions(DpRect(0.dp, 0.dp, 1000.dp, 1000.dp), top, bottom, listOf(top, bottom))
        composeRule.waitForIdle()
        assertEquals(filters, composeRule.onNodeWithTag(TimelineTestTags.FILTER_BAR).fetchSemanticsNode().boundsInRoot)
    }
}
