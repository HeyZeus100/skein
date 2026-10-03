package app.skein.foldable

import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.skein.MainActivity
import app.skein.feature.shell.host.SkeinShellHostTestTags
import app.skein.feature.shell.testing.ShellTestTags
import app.skein.system.SecurityPrefs
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
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
 * Host emulator-console fold/unfold drive this lane; unlocked A-G, IME, inference and
 * system_server privacy remain separate open gates.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityFoldableGateTest {
    private var scenario: ActivityScenario<MainActivity>? = null
    private var original: MainActivity? = null
    private val device = FoldableDeviceControl(::observeWindow)

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Before
    fun suppressPermissionPrompt() {
        device.requireTransportReady()
        runBlocking {
            SecurityPrefs(ApplicationProvider.getApplicationContext()).setPostNotificationsAsked(true)
        }
    }

    @After
    fun resetDeviceOverrides() {
        try {
            // Keep the same Activity alive through the guarded flat/rotation cleanup.
            device.reset()
            scenario?.let {
                assertGate(it)
                it.onActivity { activity -> assertSame("cleanup recreated MainActivity", original, activity) }
            }
        } finally {
            scenario?.close()
            scenario = null
        }
    }

    @Test
    fun gateSurvivesClosedFlatClosedWithoutActivityReplacement() {
        val scenario = launchActivity()
        device.closed()
        device.portrait()
        assertGate(scenario)
        var closedWidth = 0
        scenario.onActivity {
            assertSame("initial fold recreated MainActivity", original, it)
            closedWidth = it.resources.configuration.screenWidthDp
            recordGeometry(it, "closed_before")
        }
        assertTrue("profile must expose a compact cover display", closedWidth < 600)
        device.flat()
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
        device.closed()
        assertGate(scenario)
        scenario.onActivity {
            assertSame("refold recreated MainActivity", original, it)
            recordGeometry(it, "closed_after")
            assertTrue("refold must return to compact", it.resources.configuration.screenWidthDp < 600)
        }
    }

    @Test
    fun gateSurvivesOuterLandscapeWithoutExposingNavDisplay() {
        val scenario = launchActivity()
        device.closed()
        device.portrait()
        assertGate(scenario)
        scenario.onActivity {
            assertSame("initial fold recreated MainActivity", original, it)
            recordGeometry(it, "outer_portrait")
        }
        device.landscape()
        assertGate(scenario)
        scenario.onActivity {
            assertSame("rotation recreated MainActivity", original, it)
            recordGeometry(it, "outer_landscape")
            assertTrue(
                "outer landscape must be a short window",
                it.resources.configuration.screenHeightDp < 600 && ActivityWindowGeometry.capture(it).heightDp < 600,
            )
        }
        device.portrait()
    }

    private fun launchActivity(): ActivityScenario<MainActivity> =
        ActivityScenario.launch(MainActivity::class.java).also { launched ->
            scenario = launched
            launched.onActivity { original = it }
        }

    private fun observeWindow(): ActivityWindowGeometry {
        var observed: ActivityWindowGeometry? = null
        checkNotNull(scenario) { "Activity must be launched before device controls" }.onActivity {
            assertSame("device transition recreated MainActivity", original, it)
            observed = ActivityWindowGeometry.capture(it)
        }
        return checkNotNull(observed)
    }

    private fun recordGeometry(
        activity: MainActivity,
        step: String,
    ) {
        val window = ActivityWindowGeometry.capture(activity)
        val configuration = window.configuration
        val row =
            JSONObject()
                .put("step", step)
                .put("width_dp", configuration.screenWidthDp)
                .put("height_dp", configuration.screenHeightDp)
                .put("density_dpi", configuration.densityDpi)
                .put("orientation", configuration.orientation)
                .put("activity_identity", window.activityIdentity)
                .put("geometry_observer", "activity")
                .put(
                    "window_bounds_px",
                    JSONArray(listOf(window.bounds.left, window.bounds.top, window.bounds.right, window.bounds.bottom)),
                )
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
