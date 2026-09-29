package app.skein.feature.shell.host

import android.app.Application
import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import app.skein.core.designsystem.theme.SkeinTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1000dp-h800dp-land-xhdpi")
class EntryInsetsTest {
    @get:Rule(order = 1)
    val registerHost =
        object : ExternalResource() {
            override fun before() {
                val app = ApplicationProvider.getApplicationContext<Application>()
                Shadows
                    .shadowOf(
                        app.packageManager,
                    ).addActivityIfNotPresent(ComponentName(app, EntryInsetTestActivity::class.java))
            }
        }

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<EntryInsetTestActivity>()

    @Test
    fun `outer edges are protected once and list bottom padding lets its final row clear navigation`() {
        composeRule.setContent {
            SkeinTheme {
                Row(Modifier.fillMaxSize().testTag("panes")) {
                    repeat(2) { pane ->
                        Box(Modifier.weight(1f)) {
                            EntryInsets {
                                LazyColumn(
                                    Modifier.fillMaxSize().testTag("list-$pane"),
                                    contentPadding = entryBottomPadding(),
                                ) {
                                    items(100) { index ->
                                        Text(
                                            "Row $index",
                                            Modifier.fillMaxWidth().height(32.dp).testTag("row-$pane-$index"),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val paneOrigin = composeRule.onNodeWithTag("panes").fetchSemanticsNode().boundsInRoot
        val window = composeRule.activity.window.decorView
        composeRule.runOnUiThread {
            ViewCompat.dispatchApplyWindowInsets(
                window,
                WindowInsetsCompat
                    .Builder()
                    .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(80, 40, 60, 0))
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 100))
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 500))
                    .setVisible(WindowInsetsCompat.Type.ime(), true)
                    .build(),
            )
        }
        composeRule.waitForIdle()
        val left = composeRule.onNodeWithTag("list-0").fetchSemanticsNode().boundsInRoot
        val right = composeRule.onNodeWithTag("list-1").fetchSemanticsNode().boundsInRoot
        assertEquals("left cutout edge", 80f, left.left, 1f)
        assertEquals("no repeated left inset on internal pane", window.width / 2f, right.left, 1f)
        assertEquals("right cutout edge", window.width - 60f, right.right, 1f)
        assertEquals("top inset only if pane touches it", maxOf(paneOrigin.top, 40f), left.top, 1f)
        assertEquals("each pane has the same top edge", maxOf(paneOrigin.top, 40f), right.top, 1f)
        assertEquals("IME does not resize a non-input list", window.height.toFloat(), left.bottom, 1f)
        composeRule.onNodeWithTag("list-1").performScrollToIndex(99)
        assertEquals(
            "last row can scroll clear of gesture navigation",
            window.height - 100f,
            composeRule
                .onNodeWithTag("row-1-99")
                .fetchSemanticsNode()
                .boundsInRoot.bottom,
            1f,
        )
    }
}

class EntryInsetTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(android.R.style.Theme_Material_NoActionBar)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
    }
}
