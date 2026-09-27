// skein-xtov.24.4 (AL-05): gate G1 (recreation path) — the §9.2 JVM "…2"
// variants: the flip is `ActivityScenario.recreate()` at the new window
// qualifiers, so the stacks come back from the saved-state Bundle, T2 from the
// hoisted SaveableStateHolder, and T3 from the Activity-scoped session stores.
package app.skein.prototype.nav3

import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import app.skein.core.model.Role
import app.skein.feature.chat.CHAT_SCREEN_TEST_TAG
import app.skein.feature.chat.COMPOSER_TEST_TAG
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1043dp-h1006dp")
class Nav3GateFoldRecreationTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val fx = GateFixture()
    private var scenario: ActivityScenario<ProtoActivity>? = null

    @Before
    fun setUp() {
        ProbeLedger.reset()
        ProtoHost.deps = fx.deps
        ProtoHost.gate = fx.gate
        ProtoHost.creates = 0
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    private val nav get() = ProtoHost.nav!!

    private fun launch(initial: () -> ProtoNavigationState) {
        ProtoHost.initialNav = initial
        scenario = ActivityScenario.launch(ProtoActivity::class.java)
        rule.waitForIdle()
    }

    private fun recreateAt(qualifiers: String) {
        RuntimeEnvironment.setQualifiers(qualifiers)
        scenario!!.recreate()
        rule.waitForIdle()
    }

    private fun back() {
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    private fun bump(contentKey: String) =
        rule.onNodeWithTag(bumpTag(contentKey), useUnmergedTree = true).performClick()

    @Test
    fun `A2 recreation open to closed and back keeps stack, T2, T3 and the T3 draft`() {
        val chat = ChatKey(fx.c1).contentKey
        val context = ChatContextKey(fx.c1).contentKey
        launch(stackOf(Destination.CHAT, ChatHomeKey, ChatKey(fx.c1), ChatContextKey(fx.c1)))
        rule.onNodeWithTag(COMPOSER_TEST_TAG).performTextInput(DRAFT)
        rule.onNodeWithTag(STANDIN_COMPOSER_TAG).performTextInput(DRAFT)
        bump(chat)
        bump(context)
        val chatProbe = rule.probeText(chat)
        val contextProbe = rule.probeText(context)
        val stack = nav.stacks.getValue(Destination.CHAT).toList()

        for (folded in listOf("w524dp-h1175dp", "w443dp-h994dp")) {
            recreateAt(folded)
            assertThat(rule.exists(DRAWER_BUTTON_TAG)).isTrue()
            assertThat(rule.count(CHAT_SCREEN_TEST_TAG)).isEqualTo(1)
            assertThat(rule.exists(PEEK_TAG)).isTrue() // a restored sheet shows collapsed (§2.5)
            assertThat(rule.exists(SCRIM_TAG)).isFalse()
            assertThat(nav.stacks.getValue(Destination.CHAT).toList()).isEqualTo(stack)
            assertThat(rule.probeText(chat)).isEqualTo(chatProbe) // same T3 instance, T2 counter restored
            assertThat(rule.probeText(context)).isEqualTo(contextProbe)
            assertThat(rule.editableText(STANDIN_COMPOSER_TAG)).isEqualTo(DRAFT) // a T3 draft survives
            // Today's composer keeps its draft in `remember`: gone after a recreation until AL-10 (T5 + B3).
            assertThat(rule.editableText(COMPOSER_TEST_TAG)).isEmpty()

            recreateAt("w1043dp-h1006dp")
            assertThat(rule.exists(RAIL_TAG)).isTrue()
            assertThat(rule.exists(INSPECTOR_TAG)).isTrue()
            assertThat(rule.exists(PEEK_TAG)).isFalse()
            assertThat(rule.probeText(chat)).isEqualTo(chatProbe)
        }
        assertThat(ProtoHost.creates).isEqualTo(5)
        assertThat(ProbeLedger.createdCount(chat)).isEqualTo(1)
        assertThat(fx.chatDocumentCount()).isEqualTo(2)
        back()
        assertThat(nav.stacks.getValue(Destination.CHAT).toList()).isEqualTo(listOf(ChatHomeKey, ChatKey(fx.c1)))
        assertThat(rule.exists(chatRowTag(fx.c1))).isTrue()
    }

    @Test
    fun `B2 recreation closed to open shows Conversations and the chat once`() {
        val chat = ChatKey(fx.c1).contentKey
        RuntimeEnvironment.setQualifiers("w443dp-h994dp")
        launch(stackOf(Destination.CHAT, ChatHomeKey, ChatKey(fx.c1)))
        bump(chat)
        val chatProbe = rule.probeText(chat)
        recreateAt("w1006dp-h1043dp")
        assertThat(rule.count(CHAT_SCREEN_TEST_TAG)).isEqualTo(1)
        assertThat(rule.count(chatRowTag(fx.c1))).isEqualTo(1)
        rule.onNodeWithTag(chatRowTag(fx.c1), useUnmergedTree = true).assertIsSelected()
        assertThat(rule.probeText(chat)).isEqualTo(chatProbe)
        assertThat(nav.current.toList()).isEqualTo(listOf(ChatHomeKey, ChatKey(fx.c1)))
    }

    @Test
    fun `C2 a turn held in T3 keeps streaming across two recreations`() {
        launch(stackOf(Destination.CHAT, ChatHomeKey, ChatKey(fx.c1)))
        rule.onNodeWithTag(T3_SEND_TAG).performClick()

        fun waitForTokens(n: Int) =
            rule.waitUntil(60_000) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
                fx.emitted.get() >= n
            }
        waitForTokens(40)
        recreateAt("w443dp-h994dp")
        waitForTokens(70)
        recreateAt("w1043dp-h1006dp")
        rule.waitUntil(60_000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            fx.messages(fx.c1).size == 14
        }
        val answer = fx.messages(fx.c1).last()
        assertThat(answer.role).isEqualTo(Role.ASSISTANT)
        assertThat(answer.contentMd).isEqualTo(fx.answer.joinToString(""))
        assertThat(fx.streams.get()).isEqualTo(1)
    }

    @Test
    fun `E2 recreation keeps the note, Connections as a peek, and back to Note and Connections`() {
        val note = NoteKey(fx.n1).contentKey
        RuntimeEnvironment.setQualifiers("w1006dp-h1043dp")
        launch(stackOf(Destination.KNOWLEDGE, KnowledgeHomeKey(), NoteKey(fx.n1), ConnectionsKey(fx.n1)))
        bump(note)
        val noteProbe = rule.probeText(note)
        recreateAt("w443dp-h994dp")
        assertThat(rule.exists(notePaneTag(fx.n1))).isTrue()
        assertThat(rule.exists(PEEK_TAG)).isTrue()
        assertThat(rule.probeText(note)).isEqualTo(noteProbe)
        recreateAt("w1006dp-h1043dp")
        assertThat(rule.exists(CONNECTIONS_TAG)).isTrue()
        assertThat(rule.exists(PEEK_TAG)).isFalse()
        back()
        assertThat(nav.current.toList()).isEqualTo(listOf(KnowledgeHomeKey(), NoteKey(fx.n1)))
        rule.onNodeWithTag(noteRowTag(fx.n1), useUnmergedTree = true).assertIsSelected()
    }

    @Test
    fun `F2 recreation keeps the graph focus and the selected node`() {
        val graph = GraphKey(fx.n1).contentKey
        RuntimeEnvironment.setQualifiers("w1006dp-h1043dp")
        launch(stackOf(Destination.GRAPH, GraphKey(fx.n1), GraphNodeKey(fx.n1, fx.n7)))
        bump(graph)
        val graphProbe = rule.probeText(graph)
        recreateAt("w443dp-h994dp")
        assertThat(rule.exists(GRAPH_PANE_TAG)).isTrue()
        rule.onNodeWithText("Node · 3 links ▴").assertIsDisplayed()
        assertThat(rule.probeText(graph)).isEqualTo(graphProbe)
        recreateAt("w1006dp-h1043dp")
        assertThat(rule.exists(NODE_TAG)).isTrue()
        assertThat(nav.current.toList()).isEqualTo(listOf(GraphKey(fx.n1), GraphNodeKey(fx.n1, fx.n7)))
    }

    @Test
    fun `G6 an open drawer is closed after a recreation`() {
        RuntimeEnvironment.setQualifiers("w443dp-h994dp")
        launch(stackOf(Destination.CHAT, ChatHomeKey))
        rule.onNodeWithTag(DRAWER_BUTTON_TAG).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(DRAWER_SHEET_TAG).assertIsDisplayed()
        recreateAt("w443dp-h994dp")
        rule.onNodeWithTag(DRAWER_SHEET_TAG).assertIsNotDisplayed()
    }
}
