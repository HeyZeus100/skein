package app.skein.core.designsystem.components

import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.Window
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.testing.ui.skeinComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1000dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SkeinPartitionedDialogTest {
    @get:Rule(order = 0)
    val fontScale =
        object : ExternalResource() {
            override fun before() {
                RuntimeEnvironment.setFontScale(2f)
            }
        }

    @get:Rule(order = 1)
    val composeRule = skeinComposeRule()
    private var launcherView: View? = null
    private var launcherIme: Int = 0

    @Test
    fun `actual Material dialog follows tabletop book and flat while preserving dismiss and cancel focus`() {
        val partitions = mutableStateOf<SkeinWindowPartitions?>(tabletop())
        var dismissed = false
        var window: Window? = null
        composeRule.setContent {
            val rootView = LocalView.current
            val ime = WindowInsets.ime.getBottom(LocalDensity.current)
            SideEffect {
                launcherView = rootView
                launcherIme = ime
            }
            SkeinTheme {
                CompositionLocalProvider(LocalSkeinWindowPartitions provides partitions.value) {
                    Box(Modifier.fillMaxSize())
                    SkeinAlertDialog(
                        onDismissRequest = { dismissed = true },
                        modifier = Modifier.testTag("dialog"),
                        title = {
                            val view = LocalView.current
                            SideEffect { window = (view.parent as? DialogWindowProvider)?.window }
                            Text("Remove model?")
                        },
                        text = { Text("Import it again to use it later.") },
                        confirmButton = { TextButton(onClick = {}) { Text("Delete") } },
                        dismissButton = { TextButton(onClick = { dismissed = true }) { Text("Cancel") } },
                    )
                }
            }
        }
        composeRule.waitForIdle()
        assertNotNull(window)
        assertPlaced(window!!, DpRect(0.dp, 510.dp, 1000.dp, 1000.dp))
        dispatchIme(240)
        composeRule.waitForIdle()
        assertEquals("launcher received real synthetic IME insets", 240, launcherIme)
        assertPlaced(window!!, DpRect(0.dp, 510.dp, 1000.dp, 760.dp))
        dispatchIme(0)
        composeRule.waitForIdle()
        partitions.value = book()
        composeRule.waitForIdle()
        assertPlaced(window!!, DpRect(510.dp, 0.dp, 1000.dp, 1000.dp))
        partitions.value = book().copy(confirmation = DpRect(760.dp, 0.dp, 1000.dp, 1000.dp))
        composeRule.waitForIdle()
        assertPlaced(window!!, DpRect(760.dp, 0.dp, 1000.dp, 1000.dp))
        partitions.value = null
        composeRule.waitForIdle()
        assertEquals(Gravity.CENTER, window!!.attributes.gravity)
        composeRule.onNodeWithText("Cancel").performClick()
        assertTrue(dismissed)
        dismissed = false
        composeRule.runOnIdle {
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(0, 1, action, -10f, -10f, 0)
                ShadowDialog.getLatestDialog().onTouchEvent(event)
                event.recycle()
            }
        }
        assertTrue("platform outside dismissal remains available", dismissed)
    }

    @Test
    fun `large text reading dialog keeps final actions visible in top partition`() {
        var window: Window? = null
        var actualFontScale = 0f
        composeRule.setContent {
            SkeinTheme {
                CompositionLocalProvider(
                    LocalSkeinWindowPartitions provides tabletop(),
                ) {
                    SkeinAlertDialog(
                        onDismissRequest = {},
                        partition = SkeinDialogPartition.READING,
                        modifier = Modifier.testTag("dialog"),
                        title = {
                            val view = LocalView.current
                            val fontScale = LocalDensity.current.fontScale
                            SideEffect {
                                window = (view.parent as? DialogWindowProvider)?.window
                                actualFontScale =
                                    fontScale
                            }
                            Text("License")
                        },
                        text = { Text("Long readable license. ".repeat(50)) },
                        confirmButton = { TextButton(onClick = {}) { Text("Close") } },
                    )
                }
            }
        }
        composeRule.waitForIdle()
        assertEquals("dialog receives real resource font scale", 2f, actualFontScale, 0f)
        assertPlaced(window!!, DpRect(0.dp, 0.dp, 1000.dp, 490.dp))
        composeRule.onNodeWithText("Close").assertIsDisplayed().performClick()
        val image = composeRule.onNodeWithTag("dialog").captureToImage().asAndroidBitmap()
        val artifact = File("build/agent-logs/partitioned-dialog-large-text-${System.currentTimeMillis()}.png")
        artifact.parentFile?.mkdirs()
        artifact.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun `destructive dialog retains Cancel focus in hinge partition`() {
        composeRule.setContent {
            SkeinTheme {
                CompositionLocalProvider(LocalSkeinWindowPartitions provides tabletop()) {
                    SkeinDestructiveDialog("Delete model?", "Import it to use it again.", {}, {})
                }
            }
        }
        composeRule.onNodeWithTag(SKEIN_DESTRUCTIVE_DIALOG_CANCEL_TEST_TAG).assertIsFocused()
    }

    private fun dispatchIme(bottom: Int) {
        composeRule.runOnUiThread {
            ViewCompat.dispatchApplyWindowInsets(
                launcherView!!,
                WindowInsetsCompat
                    .Builder()
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, bottom))
                    .setVisible(WindowInsetsCompat.Type.ime(), bottom > 0)
                    .build(),
            )
        }
    }

    private fun assertPlaced(
        window: Window,
        bounds: DpRect,
    ) {
        val size = composeRule.onNodeWithTag("dialog").fetchSemanticsNode().size
        val params = window.attributes
        assertEquals(Gravity.TOP or Gravity.LEFT, params.gravity)
        assertTrue("x=${params.x}, bounds=$bounds", params.x >= bounds.left.value)
        assertTrue("y=${params.y}, bounds=$bounds", params.y >= bounds.top.value)
        assertTrue("right=${params.x + size.width}, bounds=$bounds", params.x + size.width <= bounds.right.value + 1)
        assertTrue(
            "bottom=${params.y + size.height}, bounds=$bounds",
            params.y + size.height <= bounds.bottom.value + 1,
        )
        assertTrue(size.width > 100 && size.height > 100)
    }
}

private fun tabletop() =
    SkeinWindowPartitions(
        DpRect(0.dp, 0.dp, 1000.dp, 1000.dp),
        DpRect(0.dp, 0.dp, 1000.dp, 490.dp),
        DpRect(0.dp, 510.dp, 1000.dp, 1000.dp),
        listOf(DpRect(0.dp, 0.dp, 1000.dp, 490.dp), DpRect(0.dp, 510.dp, 1000.dp, 1000.dp)),
    )

private fun book() =
    SkeinWindowPartitions(
        DpRect(0.dp, 0.dp, 1000.dp, 1000.dp),
        DpRect(510.dp, 0.dp, 1000.dp, 1000.dp),
        DpRect(510.dp, 0.dp, 1000.dp, 1000.dp),
        listOf(DpRect(0.dp, 0.dp, 490.dp, 1000.dp), DpRect(510.dp, 0.dp, 1000.dp, 1000.dp)),
    )
