package app.skein

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertiesAndroid
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * skein-xtov.24.20 (UT-14, `UX_TEST_PLAN.md` §2.6): [debugTestTagsModifier]
 * is the one line between `tools/ux/fold-watch.sh` finding a node by
 * `resource-id` in `adb shell uiautomator dump` and it seeing nothing at
 * all. `BuildConfig.DEBUG` can't be flipped from inside a single Gradle unit
 * test task (`testFossDebugUnitTest` always compiles against the `debug`
 * build type), so this drives the [debugTestTagsModifier] parameter
 * directly rather than depending on the variant the test happens to run
 * under — see that function's own doc for why the parameter exists.
 *
 * Pinned to SDK 34 (bd memory `robolectric-sdk37-needs-java21`), matching
 * every other Robolectric test in this repo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityDebugTestTagsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `debug=true marks testTagsAsResourceId on the root`() {
        composeRule.setContent {
            Box(modifier = debugTestTagsModifier(debug = true).testTag(ROOT_TAG))
        }

        val config = composeRule.onNodeWithTag(ROOT_TAG).fetchSemanticsNode().config
        assertTrue(
            "expected a debug root to carry SemanticsPropertiesAndroid.TestTagsAsResourceId = true",
            config.contains(SemanticsPropertiesAndroid.TestTagsAsResourceId) &&
                config[SemanticsPropertiesAndroid.TestTagsAsResourceId],
        )
    }

    @Test
    fun `debug=false never sets testTagsAsResourceId (the release shape)`() {
        composeRule.setContent {
            Box(modifier = debugTestTagsModifier(debug = false).testTag(ROOT_TAG))
        }

        val config = composeRule.onNodeWithTag(ROOT_TAG).fetchSemanticsNode().config
        assertFalse(
            "a release build must never carry SemanticsPropertiesAndroid.TestTagsAsResourceId",
            config.contains(SemanticsPropertiesAndroid.TestTagsAsResourceId),
        )
    }

    private companion object {
        const val ROOT_TAG = "debug_test_tags_modifier_test_root"
    }
}
