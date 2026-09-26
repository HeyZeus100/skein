package app.skein.core.designsystem.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.onNodeWithTag
import app.skein.core.designsystem.icons.SkeinIcons
import app.skein.core.designsystem.theme.SkeinSize
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.testing.ui.skeinComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §5.3: the wrapper itself adds no shadow (`shadowElevation = 0.dp`, checked
 * by `NoShadowOrGradientTest`'s literal scan already covering this file) —
 * this test just proves the wrapped anchor still composes and keeps its own
 * 48 dp touch target, i.e. the wrapper doesn't interfere with the anchor's
 * own interaction contract.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkeinTooltipTest {
    @get:Rule
    val composeRule = skeinComposeRule()

    @Test
    fun `the wrapped anchor still renders at its own touch target`() {
        composeRule.setContent {
            SkeinTheme {
                SkeinTooltip(text = "Search · Ctrl+K") {
                    IconButton(onClick = {}, modifier = Modifier.testTag(TAG)) {
                        Icon(
                            painter = painterResource(SkeinIcons.Search),
                            contentDescription = "Search",
                            modifier = Modifier.size(SkeinSize.iconStandard),
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithTag(TAG).assertTouchHeightIsEqualTo(SkeinSize.touchTarget)
    }

    private companion object {
        const val TAG = "tooltip-anchor"
    }
}
