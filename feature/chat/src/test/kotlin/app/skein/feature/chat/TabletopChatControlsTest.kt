package app.skein.feature.chat

import android.app.Application
import android.content.ComponentName
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.HingeInfo
import androidx.compose.material3.adaptive.Posture
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.computeWindowSizeClass
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.core.model.Document
import app.skein.core.model.Role
import app.skein.core.navigation.ChatKey
import app.skein.core.navigation.SkeinId
import app.skein.feature.chat.entries.ChatEntryTestTags
import app.skein.feature.chat.entries.EntriesHost
import app.skein.feature.chat.entries.pipelineOver
import app.skein.feature.shell.host.SkeinShellState
import app.skein.testing.fakeVault
import app.skein.testing.scriptedEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1043dp-h1006dp-land-xhdpi")
class TabletopChatControlsTest {
    @get:Rule(order = 0)
    val registerHost =
        object : ExternalResource() {
            override fun before() {
                val app = ApplicationProvider.getApplicationContext<Application>()
                Shadows
                    .shadowOf(app.packageManager)
                    .addActivityIfNotPresent(ComponentName(app, ChatImeTestActivity::class.java))
            }
        }

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<ChatImeTestActivity>()

    private var density = 1f
    private val tabletop = mutableStateOf(true)
    private lateinit var shell: SkeinShellState
    private lateinit var chat: Document
    private val vault = fakeVault { chat = chat("Chat", Role.USER to "Question", Role.ASSISTANT to "Answer") }

    @Test
    fun `conversation chip and composer avoid tabletop hinge as IME rises and restore when flat`() {
        setHost()
        composeRule.runOnIdle { shell.navigate { goTo(it, ChatKey(SkeinId.of(chat.id))) } }
        verifyControls(listOf(COMPOSER_TEST_TAG, ChatEntryTestTags.CONTEXT_ACTION))
    }

    @Test
    fun `landing composer avoids tabletop hinge as IME rises and restores when flat`() {
        setHost()
        verifyControls(listOf(COMPOSER_TEST_TAG))
    }

    @Test
    fun `controls scroll below hinge when header leaves no room in top partition`() {
        composeRule.setContent {
            SkeinTheme {
                density = LocalDensity.current.density
                Column(Modifier.fillMaxWidth().height(200.dp)) {
                    Spacer(Modifier.height(136.dp))
                    Spacer(Modifier.weight(1f))
                    HingeSafeChatControls(
                        contentTopInWindow = 136.dp,
                        bottomInWindow = 200.dp,
                        hinge = DpRect(0.dp, 136.dp, 1043.dp, 144.dp),
                    ) {
                        Button(onClick = {}, modifier = Modifier.height(48.dp)) { Text("First control") }
                        Button(onClick = {}, modifier = Modifier.height(48.dp)) { Text("Last control") }
                    }
                }
            }
        }
        dispatchInsets(0, nav = 0)
        composeRule.onNodeWithText("Last control").performScrollTo().assertIsDisplayed()
        val last = composeRule.onNodeWithText("Last control").fetchSemanticsNode().boundsInRoot
        assertTrue("scroll viewport stays below hinge", last.top >= 144f * density)
        assertTrue("last control remains reachable inside window", last.bottom <= 200f * density)
        composeRule.onNodeWithText("First control").performScrollTo().assertIsDisplayed()
        val first = composeRule.onNodeWithText("First control").fetchSemanticsNode().boundsInRoot
        assertTrue("first control remains reachable below hinge", first.top >= 144f * density)
    }

    private fun setHost() {
        val pipeline = pipelineOver(vault, scriptedEngine())
        composeRule.setContent {
            SkeinTheme {
                density = LocalDensity.current.density
                val info =
                    WindowAdaptiveInfo(
                        WindowSizeClass.BREAKPOINTS_V2.computeWindowSizeClass(1043, 1006),
                        Posture(
                            isTabletop = tabletop.value,
                            hingeList =
                                listOf(
                                    HingeInfo(
                                        Rect(0f, HINGE_TOP * density, 1043f * density, HINGE_BOTTOM * density),
                                        isFlat = !tabletop.value,
                                        isVertical = false,
                                        isSeparating = tabletop.value,
                                        isOccluding = tabletop.value,
                                    ),
                                ),
                        ),
                    )
                EntriesHost(vault, pipeline, null, { shell = it }, windowAdaptiveInfo = info)
            }
        }
        composeRule.waitForIdle()
    }

    private fun verifyControls(tags: List<String>) {
        val window = composeRule.activity.window.decorView
        val keyboardTops = listOf(1006f, 660f, 560f, 510f, 470f, 1006f)
        for (top in keyboardTops) {
            dispatchInsets((window.height - top * density).toInt().coerceAtLeast(0))
            for (tag in tags) {
                val node = composeRule.onNodeWithTag(tag).assertIsDisplayed()
                val bounds = node.fetchSemanticsNode().boundsInRoot
                assertTrue(
                    "$tag avoids tabletop hinge with keyboard top $top: $bounds",
                    bounds.bottom <= HINGE_TOP * density + 1f || bounds.top >= HINGE_BOTTOM * density - 1f,
                )
                assertTrue("control clears keyboard", bounds.bottom <= top * density + 1f)
            }
        }
        dispatchInsets((window.height - 530f * density).toInt())
        val guarded = composeRule.onNodeWithTag(COMPOSER_TEST_TAG).fetchSemanticsNode().boundsInRoot
        tabletop.value = false
        composeRule.waitForIdle()
        val flat = composeRule.onNodeWithTag(COMPOSER_TEST_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue("non-vacuous: flat control crosses former hinge", flat.top < HINGE_BOTTOM * density)
        assertTrue("flat returns to the keyboard: $flat", flat.bottom > HINGE_TOP * density)
        tabletop.value = true
        composeRule.waitForIdle()
        assertEquals(
            "posture guard does not accumulate clearance",
            guarded,
            composeRule.onNodeWithTag(COMPOSER_TEST_TAG).fetchSemanticsNode().boundsInRoot,
        )
    }

    private fun dispatchInsets(
        ime: Int,
        nav: Int = 80,
    ) {
        composeRule.runOnUiThread {
            ViewCompat.dispatchApplyWindowInsets(
                composeRule.activity.window.decorView,
                WindowInsetsCompat
                    .Builder()
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime))
                    .setVisible(WindowInsetsCompat.Type.ime(), ime > 0)
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, nav))
                    .build(),
            )
        }
        composeRule.waitForIdle()
    }

    private companion object {
        const val HINGE_TOP = 500f
        const val HINGE_BOTTOM = 508f
    }
}
