// skein-xtov.24.4 (AL-05): gates G3 (the Phone presentation of extra entries,
// through SkeinSheetSceneStrategy) and G4 (Skein's PaneScaffoldDirective is
// what the adaptive-navigation3 strategies actually lay out).
package app.skein.prototype.nav3

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import app.skein.core.model.Role
import app.skein.feature.chat.CHAT_SCREEN_TEST_TAG
import app.skein.feature.chat.COMPOSER_TEST_TAG
import app.skein.feature.chat.SEND_BUTTON_TEST_TAG
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1400dp-h1400dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Nav3GateScenesTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val fx = GateFixture(tokens = 3)

    @Before
    fun reset() = ProbeLedger.reset()

    private fun widthDp(tag: String): Dp =
        with(rule.density) {
            rule
                .onNodeWithTag(tag, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot.width
                .toDp()
        }

    private fun back() {
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    /** Evidence for reviewers (never a golden): build/nav3-gate-captures/<name>.png. */
    private fun capture(name: String) {
        val dir = File("build/nav3-gate-captures").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use {
            rule
                .onRoot()
                .captureToImage()
                .asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    // ---- G3 -------------------------------------------------------------------------------------

    @Test
    fun `G3 opened on a phone - bottom sheet over a scrim, Back pops, scrim tap pops`() {
        val host = LiveHost(rule, fx, Fold.OUTER, stackOf(Destination.CHAT, ChatHomeKey, ChatKey(fx.c1)))
        rule.onNodeWithTag(OPEN_CONTEXT_TAG).performClick()
        rule.waitForIdle()
        assertThat(rule.exists(SHEET_TAG)).isTrue()
        assertThat(rule.exists(SCRIM_TAG)).isTrue()
        assertThat(rule.exists(PEEK_TAG)).isFalse()
        val sheet = rule.onNodeWithTag(SHEET_TAG).fetchSemanticsNode().boundsInRoot
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        assertThat(sheet.bottom).isWithin(1f).of(root.bottom) // docked at the bottom
        assertThat(sheet.height).isWithin(root.height * 0.05f).of(root.height / 2) // half of the pane under the top bar
        capture("g3-phone-sheet-expanded")
        back()
        assertThat(host.nav.current.toList()).isEqualTo(listOf(ChatHomeKey, ChatKey(fx.c1)))
        assertThat(rule.exists(SHEET_TAG)).isFalse()
        assertThat(rule.exists(SCRIM_TAG)).isFalse()

        rule.onNodeWithTag(OPEN_CONTEXT_TAG).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(SCRIM_TAG).performTouchInput { click(Offset(width / 2f, height * 0.1f)) } // above the sheet
        rule.waitForIdle()
        assertThat(host.nav.current.toList()).isEqualTo(listOf(ChatHomeKey, ChatKey(fx.c1)))
    }

    @Test
    fun `G3 after a shrink - a peek with no scrim, the chat stays interactive, tap expands, Back pops`() {
        val host =
            LiveHost(
                rule,
                fx,
                Fold.INNER_LAND,
                stackOf(Destination.CHAT, ChatHomeKey, ChatKey(fx.c1), ChatContextKey(fx.c1)),
            )
        host.flip(Fold.OUTER)
        assertThat(rule.exists(PEEK_TAG)).isTrue()
        assertThat(rule.exists(SCRIM_TAG)).isFalse()
        capture("g3-phone-peek-after-shrink")
        // The chat under the peek takes input and sends.
        rule.onNodeWithTag(COMPOSER_TEST_TAG).performTextInput("q")
        rule.onNodeWithTag(SEND_BUTTON_TEST_TAG).performClick()
        rule.waitUntil(20_000) {
            fx.messages(fx.c1).lastOrNull()?.role == Role.ASSISTANT &&
                fx.messages(fx.c1).size == 14
        }
        val composer = rule.onNodeWithTag(COMPOSER_TEST_TAG).fetchSemanticsNode().boundsInRoot
        val peek = rule.onNodeWithTag(PEEK_TAG).fetchSemanticsNode().boundsInRoot
        assertThat(composer.bottom).isAtMost(peek.top) // the peek never covers the composer
        // Tap expands in place (same scene), Back pops the entry.
        val inspectorVm = rule.probeText(ChatContextKey(fx.c1).contentKey)
        rule.onNodeWithTag(PEEK_TAG).performClick()
        rule.waitForIdle()
        assertThat(rule.exists(SHEET_TAG)).isTrue()
        assertThat(rule.exists(SCRIM_TAG)).isTrue()
        assertThat(rule.probeText(ChatContextKey(fx.c1).contentKey)).isEqualTo(inspectorVm)
        back()
        assertThat(host.nav.current.toList()).isEqualTo(listOf(ChatHomeKey, ChatKey(fx.c1)))
        assertThat(rule.count(CHAT_SCREEN_TEST_TAG)).isEqualTo(1)
    }

    @Test
    fun `G3 a short wide window uses a 360 dp side sheet`() {
        LiveHost(rule, fx, Fold.OUTER_LAND, stackOf(Destination.KNOWLEDGE, KnowledgeHomeKey(), NoteKey(fx.n1)))
        rule.waitUntil(10_000) { rule.exists(OPEN_CONNECTIONS_TAG) }
        rule.onNodeWithTag(OPEN_CONNECTIONS_TAG).performClick()
        rule.waitForIdle()
        assertThat(rule.exists(SCRIM_TAG)).isTrue()
        assertThat(widthDp(SHEET_TAG).value).isWithin(1f).of(360f)
        capture("g3-short-side-sheet")
    }

    // ---- G4 -------------------------------------------------------------------------------------

    private fun chatPanes(size: DpSize): Int {
        val host = LiveHost(rule, fx, size, stackOf(Destination.CHAT, ChatHomeKey, ChatKey(fx.c1)))
        return listOf(chatRowTag(fx.c1), chatPaneTag(fx.c1)).count { rule.exists(it) }.also { host.size }
    }

    @Test
    fun `G4 994x443 is one pane although Material would give two`() {
        assertThat(chatPanes(Fold.OUTER_LAND_STOCK)).isEqualTo(1)
        assertThat(rule.exists(DRAWER_BUTTON_TAG)).isTrue()
    }

    @Test
    fun `G4 1175x524 is one pane`() {
        assertThat(chatPanes(Fold.OUTER_LAND)).isEqualTo(1)
    }

    @Test
    fun `G4 852x883 is two panes with a 280 dp list, and the list yields to Context`() {
        val host = LiveHost(rule, fx, Fold.INNER_STOCK, stackOf(Destination.CHAT, ChatHomeKey, ChatKey(fx.c1)))
        assertThat(rule.exists(chatRowTag(fx.c1))).isTrue()
        assertThat(rule.exists(chatPaneTag(fx.c1))).isTrue()
        assertThat(widthDp(chatRowTag(fx.c1)).value).isWithin(1f).of(280f) // Skein's side pane, not Material's 360
        rule.onNodeWithTag(OPEN_CONTEXT_TAG).performClick()
        rule.waitForIdle()
        assertThat(rule.exists(chatRowTag(fx.c1))).isFalse()
        assertThat(rule.exists(chatPaneTag(fx.c1))).isTrue()
        assertThat(rule.exists(INSPECTOR_TAG)).isTrue()
        assertThat(widthDp(INSPECTOR_TAG).value).isWithin(1f).of(280f)
        assertThat(host.nav.current.toList()).isEqualTo(listOf(ChatHomeKey, ChatKey(fx.c1), ChatContextKey(fx.c1)))
        capture("g4-852-chat-context")
    }

    @Test
    fun `G4 1280x800 is three panes with 320 dp sides`() {
        LiveHost(rule, fx, Fold.LARGE, stackOf(Destination.CHAT, ChatHomeKey, ChatKey(fx.c1), ChatContextKey(fx.c1)))
        assertThat(rule.exists(chatRowTag(fx.c1))).isTrue()
        assertThat(rule.exists(chatPaneTag(fx.c1))).isTrue()
        assertThat(rule.exists(INSPECTOR_TAG)).isTrue()
        assertThat(widthDp(chatRowTag(fx.c1)).value).isWithin(1f).of(320f)
        assertThat(widthDp(INSPECTOR_TAG).value).isWithin(1f).of(320f)
        capture("g4-1280-three-panes")
    }

    @Test
    fun `G4 the owner's grip is two panes with a 320 dp list`() {
        LiveHost(rule, fx, Fold.INNER_LAND, stackOf(Destination.CHAT, ChatHomeKey, ChatKey(fx.c1)))
        assertThat(widthDp(chatRowTag(fx.c1)).value).isWithin(1f).of(320f)
        assertThat(rule.exists(RAIL_TAG)).isTrue()
    }

    @Test
    fun `G4 Medium 791x820 is one pane with a rail`() {
        assertThat(chatPanes(Fold.MEDIUM)).isEqualTo(1)
        assertThat(rule.exists(RAIL_TAG)).isTrue()
    }
}
