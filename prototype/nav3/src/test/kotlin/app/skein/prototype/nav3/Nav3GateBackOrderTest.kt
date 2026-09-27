// skein-xtov.24.4 (AL-05): gate G5 — 12 scripted Back/Esc sequences against
// ADAPTIVE_LAYOUT_SPEC.md §3.6, through NavDisplay's own (navigationevent)
// back handling plus the shell's destination-root handler (§8.3 rule 6b).
package app.skein.prototype.nav3

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.DpSize
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1400dp-h1400dp")
class Nav3GateBackOrderTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val fx = GateFixture(tokens = 3)
    private lateinit var host: LiveHost

    private fun start(
        size: DpSize,
        top: Destination,
        vararg keys: ProtoKey,
    ) {
        host = LiveHost(rule, fx, size, stackOf(top, *keys))
    }

    private val tags
        get() =
            listOf(
                chatRowTag(fx.c1),
                chatPaneTag(fx.c1),
                INSPECTOR_TAG,
                PEEK_TAG,
                SHEET_TAG,
                DETAIL_PLACEHOLDER_TAG,
                noteRowTag(fx.n1),
                notePaneTag(fx.n1),
                CONNECTIONS_TAG,
                GRAPH_PANE_TAG,
                NODE_TAG,
            )

    /** What is on screen: every Back that is consumed must change it (§3.6 "exactly one visible thing"). */
    private fun visible(): List<String> = tags.filter { rule.exists(it) }

    private fun finishing(): Boolean {
        var f = false
        rule.activityRule.scenario.onActivity { f = it.isFinishing }
        return f
    }

    /** Presses Back and returns true if Skein consumed it (the Activity is not finishing). */
    private fun back(): Boolean {
        val before = visible() to host.nav.top
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        val consumed = !finishing()
        if (consumed) assertThat(visible() to host.nav.top).isNotEqualTo(before)
        return consumed
    }

    private fun stack() = host.nav.current.toList()

    @Test
    fun `01 phone chat - pops to the root, then Back is left to the system`() {
        start(Fold.OUTER, Destination.CHAT, ChatHomeKey, ChatKey(fx.c1))
        assertThat(back()).isTrue()
        assertThat(stack()).isEqualTo(listOf(ChatHomeKey))
        assertThat(back()).isFalse()
    }

    @Test
    fun `02 phone peek - Back pops the sheet entry`() {
        start(Fold.OUTER, Destination.CHAT, ChatHomeKey, ChatKey(fx.c1), ChatContextKey(fx.c1))
        assertThat(rule.exists(PEEK_TAG)).isTrue()
        assertThat(back()).isTrue()
        assertThat(stack()).isEqualTo(listOf(ChatHomeKey, ChatKey(fx.c1)))
    }

    @Test
    fun `03 phone expanded sheet - Back pops it, the chat is untouched`() {
        start(Fold.OUTER, Destination.CHAT, ChatHomeKey, ChatKey(fx.c1))
        rule.onNodeWithTag(OPEN_CONTEXT_TAG).performClick()
        rule.waitForIdle()
        val chatProbe = rule.probeText(ChatKey(fx.c1).contentKey)
        assertThat(back()).isTrue()
        assertThat(stack()).isEqualTo(listOf(ChatHomeKey, ChatKey(fx.c1)))
        assertThat(rule.probeText(ChatKey(fx.c1).contentKey)).isEqualTo(chatProbe)
    }

    @Test
    fun `04 dual - extra, then detail, then the Chat root is left to the system`() {
        start(Fold.INNER_LAND, Destination.CHAT, ChatHomeKey, ChatKey(fx.c1), ChatContextKey(fx.c1))
        assertThat(back()).isTrue()
        assertThat(visible()).containsAtLeast(chatRowTag(fx.c1), chatPaneTag(fx.c1)) // List | Detail
        assertThat(back()).isTrue()
        assertThat(visible()).containsAtLeast(chatRowTag(fx.c1), DETAIL_PLACEHOLDER_TAG) // List | placeholder
        assertThat(back()).isFalse()
    }

    @Test
    fun `05 dual knowledge - note, then the root switches to Chat with its stack unchanged`() {
        start(Fold.INNER_LAND, Destination.KNOWLEDGE, KnowledgeHomeKey(), NoteKey(fx.n1))
        host.nav.stacks
            .getValue(Destination.CHAT)
            .add(ChatKey(fx.c1))
        rule.waitForIdle()
        assertThat(back()).isTrue()
        assertThat(stack()).isEqualTo(listOf(KnowledgeHomeKey()))
        assertThat(back()).isTrue()
        assertThat(host.nav.top).isEqualTo(Destination.CHAT)
        assertThat(stack()).isEqualTo(listOf(ChatHomeKey, ChatKey(fx.c1)))
    }

    @Test
    fun `06 graph root on a phone - Back switches to Chat`() {
        start(Fold.OUTER, Destination.GRAPH, GraphKey(fx.n1))
        assertThat(back()).isTrue()
        assertThat(host.nav.top).isEqualTo(Destination.CHAT)
    }

    @Test
    fun `07 phone knowledge - peek, note, root, then Chat`() {
        start(Fold.OUTER, Destination.KNOWLEDGE, KnowledgeHomeKey(), NoteKey(fx.n1), ConnectionsKey(fx.n1))
        assertThat(back()).isTrue()
        assertThat(stack()).isEqualTo(listOf(KnowledgeHomeKey(), NoteKey(fx.n1)))
        assertThat(back()).isTrue()
        assertThat(stack()).isEqualTo(listOf(KnowledgeHomeKey()))
        assertThat(back()).isTrue()
        assertThat(host.nav.top).isEqualTo(Destination.CHAT)
    }

    @Test
    fun `08 dual graph - node, then canvas root switches to Chat`() {
        start(Fold.INNER_PORT, Destination.GRAPH, GraphKey(fx.n1), GraphNodeKey(fx.n1, fx.n7))
        assertThat(back()).isTrue()
        assertThat(stack()).isEqualTo(listOf(GraphKey(fx.n1)))
        assertThat(back()).isTrue()
        assertThat(host.nav.top).isEqualTo(Destination.CHAT)
    }

    @Test
    fun `09 an open drawer closes first and the stack is untouched`() {
        start(Fold.OUTER, Destination.CHAT, ChatHomeKey, ChatKey(fx.c1))
        rule.onNodeWithTag(DRAWER_BUTTON_TAG).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(DRAWER_SHEET_TAG).assertIsDisplayed()
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithTag(DRAWER_SHEET_TAG).assertIsNotDisplayed()
        assertThat(stack()).isEqualTo(listOf(ChatHomeKey, ChatKey(fx.c1)))
    }

    @Test
    fun `10 Esc pops like Back but never switches destination or leaves`() {
        start(Fold.INNER_LAND, Destination.CHAT, ChatHomeKey, ChatKey(fx.c1), ChatContextKey(fx.c1))

        fun esc() {
            rule.onNodeWithTag(STANDIN_COMPOSER_TAG).performClick()
            rule.onNodeWithTag(STANDIN_COMPOSER_TAG).performKeyInput { pressKey(Key.Escape) }
            rule.waitForIdle()
        }
        esc()
        assertThat(stack()).isEqualTo(listOf(ChatHomeKey, ChatKey(fx.c1)))
        host.nav.switchTo(Destination.KNOWLEDGE)
        host.nav.push(NoteKey(fx.n1))
        rule.waitForIdle()
        host.nav.switchTo(Destination.CHAT)
        rule.waitForIdle()
        esc()
        assertThat(stack()).isEqualTo(listOf(ChatHomeKey))
        assertThat(host.nav.escape()).isFalse() // at a root, Esc is not consumed
        assertThat(host.nav.top).isEqualTo(Destination.CHAT)
        assertThat(finishing()).isFalse()
    }

    @Test
    fun `11 a cancelled predictive back leaves everything as it was`() {
        start(Fold.OUTER, Destination.CHAT, ChatHomeKey, ChatKey(fx.c1))
        val before = visible()
        rule.activityRule.scenario.onActivity {
            it.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 500f, 0f, BackEventCompat.EDGE_LEFT))
            it.onBackPressedDispatcher.dispatchOnBackProgressed(
                BackEventCompat(120f, 500f, 0.5f, BackEventCompat.EDGE_LEFT),
            )
        }
        rule.waitForIdle()
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackCancelled() }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertThat(stack()).isEqualTo(listOf(ChatHomeKey, ChatKey(fx.c1)))
        assertThat(visible()).isEqualTo(before)
        rule.onNodeWithTag(chatPaneTag(fx.c1), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `12 a completed predictive back on dual pops the extra pane`() {
        start(Fold.INNER_LAND, Destination.CHAT, ChatHomeKey, ChatKey(fx.c1), ChatContextKey(fx.c1))
        rule.activityRule.scenario.onActivity {
            it.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 500f, 0f, BackEventCompat.EDGE_LEFT))
            it.onBackPressedDispatcher.dispatchOnBackProgressed(
                BackEventCompat(300f, 500f, 0.8f, BackEventCompat.EDGE_LEFT),
            )
        }
        rule.waitForIdle()
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertThat(stack()).isEqualTo(listOf(ChatHomeKey, ChatKey(fx.c1)))
        assertThat(visible()).containsAtLeast(chatRowTag(fx.c1), chatPaneTag(fx.c1))
    }
}
