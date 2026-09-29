package app.skein.foldable

import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.device.EspressoDevice.Companion.onDevice
import androidx.test.espresso.device.action.ScreenOrientation
import androidx.test.espresso.device.action.setClosedMode
import androidx.test.espresso.device.action.setFlatMode
import androidx.test.espresso.device.action.setScreenOrientation
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.skein.MainActivity
import app.skein.feature.shell.host.SkeinShellHostTestTags
import app.skein.feature.shell.testing.ShellTestTags
import app.skein.system.SecurityPrefs
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * AL-16's first production-Activity fold lane. Uses a fresh emulator's unopened vault, never
 * a test replacement for NavShell. It proves the pre-unlock boundary and live config contract;
 * unlocked A-G, IME, inference and system_server privacy remain separate open gates.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityFoldableGateTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Before
    fun suppressPermissionPrompt() {
        runBlocking {
            SecurityPrefs(ApplicationProvider.getApplicationContext()).setPostNotificationsAsked(true)
        }
    }

    @Test
    fun gateSurvivesClosedFlatClosedWithoutActivityReplacement() {
        onDevice().perform(setScreenOrientation(ScreenOrientation.PORTRAIT))
        onDevice().perform(setClosedMode())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertGate(scenario)
            var original: MainActivity? = null
            var closedWidth = 0
            scenario.onActivity {
                original = it
                closedWidth = it.resources.configuration.screenWidthDp
                recordGeometry(it, "closed_before")
            }
            assertTrue("profile must expose a compact cover display", closedWidth < 600)
            onDevice().perform(setFlatMode())
            assertGate(scenario)
            scenario.onActivity {
                assertSame("unfold recreated MainActivity", original, it)
                recordGeometry(it, "flat")
                assertTrue(
                    "unfold must expose a wider, at least medium inner display",
                    it.resources.configuration.screenWidthDp >= 600 &&
                        it.resources.configuration.screenWidthDp > closedWidth,
                )
            }
            onDevice().perform(setClosedMode())
            assertGate(scenario)
            scenario.onActivity {
                assertSame("refold recreated MainActivity", original, it)
                recordGeometry(it, "closed_after")
                assertTrue("refold must return to compact", it.resources.configuration.screenWidthDp < 600)
            }
        }
    }

    @Test
    fun gateSurvivesOuterLandscapeWithoutExposingNavDisplay() {
        onDevice().perform(setClosedMode())
        onDevice().perform(setScreenOrientation(ScreenOrientation.PORTRAIT))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertGate(scenario)
            var original: MainActivity? = null
            scenario.onActivity {
                original = it
                recordGeometry(it, "outer_portrait")
            }
            onDevice().perform(setScreenOrientation(ScreenOrientation.LANDSCAPE))
            assertGate(scenario)
            scenario.onActivity {
                assertSame("rotation recreated MainActivity", original, it)
                recordGeometry(it, "outer_landscape")
                assertTrue("outer landscape must be a short window", it.resources.configuration.screenHeightDp < 600)
            }
            onDevice().perform(setScreenOrientation(ScreenOrientation.PORTRAIT))
        }
    }

    private fun recordGeometry(
        activity: MainActivity,
        step: String,
    ) {
        val configuration = activity.resources.configuration
        val row =
            JSONObject()
                .put("step", step)
                .put("width_dp", configuration.screenWidthDp)
                .put("height_dp", configuration.screenHeightDp)
                .put("density_dpi", configuration.densityDpi)
                .put("activity_identity", System.identityHashCode(activity))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir, "foldable-gate-metrics.jsonl").appendText(row.toString() + "\n")
    }

    private fun assertGate(scenario: ActivityScenario<MainActivity>) {
        composeRule.waitUntil("fresh emulator setup gate", 30_000) {
            composeRule.onAllNodesWithTag(ShellTestTags.VAULT_SETUP_ROOT).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(ShellTestTags.VAULT_SETUP_ROOT).assertIsDisplayed()
        composeRule.onNodeWithTag(SkeinShellHostTestTags.NAV_DISPLAY).assertDoesNotExist()
        scenario.onActivity {
            assertTrue(
                "secure window must survive posture",
                it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0,
            )
        }
    }
}
