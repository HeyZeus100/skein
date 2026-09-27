// skein-xtov.24.4 (AL-05): gate G1 (live path) and G2 — Fold Tests A–G of
// ADAPTIVE_LAYOUT_SPEC.md §9.2 as live flips in ONE composition
// (DeviceConfigurationOverride.WindowSize), against the Nav3 prototype.
package app.skein.prototype.nav3

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextRange
import app.skein.core.model.Role
import app.skein.feature.chat.CANCEL_BUTTON_TEST_TAG
import app.skein.feature.chat.CHAT_SCREEN_TEST_TAG
import app.skein.feature.chat.COMPOSER_TEST_TAG
import app.skein.feature.chat.SEND_BUTTON_TEST_TAG
import app.skein.feature.editor.SKEIN_EDITOR_TEST_TAG
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1400dp-h1400dp")
class Nav3GateFoldLiveTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val fx = GateFixture()

    @Before
    fun reset() = ProbeLedger.reset()

    private fun chatVm() = rule.probeText(ChatKey(fx.c1).contentKey)

    /** Everything "state intact" means that the nav layer can see (§9.2). */
    private fun assertChatIntact(
        host: LiveHost,
        vm: String,
        subscriptions: Int,
    ) {
        assertThat(rule.editableText(COMPOSER_TEST_TAG)).isEqualTo(DRAFT)
        assertThat(chatVm()).isEqualTo(vm)
        assertThat(fx.vault.observeMessagesCalls.get()).isEqualTo(subscriptions)
        assertThat(ProbeLedger.createdCount(ChatKey(fx.c1).contentKey)).isEqualTo(1)
        assertThat(fx.chatDocumentCount()).isEqualTo(2)
        assertThat(
            host.nav.stacks
                .getValue(Destination.CHAT)
                .size,
        ).isAtLeast(2)
    }

    // ---- Test A: open -> closed (chat + inspector + draft) ----------------------------------------

    @Test
    fun `A1 open to closed keeps the chat, turns the inspector into a peek, and back again`() {
        val host =
            LiveHost(
                rule,
                fx,
                Fold.INNER_LAND,
                stackOf(Destination.CHAT, ChatHomeKey, ChatKey(fx.c1), ChatContextKey(fx.c1)),
            )
        // Dual: Chat | Context, the list yields.
        assertThat(rule.exists(RAIL_TAG)).isTrue()
        assertThat(rule.exists(chatPaneTag(fx.c1))).isTrue()
        assertThat(rule.exists(INSPECTOR_TAG)).isTrue()
        assertThat(rule.exists(chatRowTag(fx.c1))).isFalse()
        rule.onNodeWithTag(COMPOSER_TEST_TAG).performTextInput(DRAFT)
        rule.onNodeWithTag(bumpTag(ChatKey(fx.c1).contentKey)).performClick()
        val vm = chatVm()
        val subscriptions = fx.vault.observeMessagesCalls.get()
        val expectedStack =
            host.nav.stacks
                .getValue(Destination.CHAT)
                .toList()

        for (folded in listOf(Fold.OUTER, Fold.OUTER_STOCK)) {
            host.flip(folded)
            // Drawer mode, exactly one pane (the chat), the inspector a peek with no scrim.
            assertThat(rule.exists(DRAWER_BUTTON_TAG)).isTrue()
            assertThat(rule.exists(RAIL_TAG)).isFalse()
            assertThat(rule.count(CHAT_SCREEN_TEST_TAG)).isEqualTo(1)
            assertThat(rule.exists(PEEK_TAG)).isTrue()
            assertThat(rule.exists(SCRIM_TAG)).isFalse()
            rule.onNodeWithText("Context · 2 notes · 3 sources ▴").assertIsDisplayed()
            assertThat(
                host.nav.stacks
                    .getValue(Destination.CHAT)
                    .toList(),
            ).isEqualTo(expectedStack)
            assertChatIntact(host, vm, subscriptions)
            assertThat(chatVm()).endsWith("t2=1")

            host.flip(Fold.INNER_LAND)
            assertThat(rule.exists(PEEK_TAG)).isFalse()
            assertThat(rule.exists(INSPECTOR_TAG)).isTrue()
            assertThat(rule.exists(chatPaneTag(fx.c1))).isTrue()
            assertChatIntact(host, vm, subscriptions)
        }
        // Back once gives Conversations | Chat.
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertThat(
            host.nav.stacks
                .getValue(Destination.CHAT)
                .toList(),
        ).isEqualTo(listOf(ChatHomeKey, ChatKey(fx.c1)))
        assertThat(rule.exists(chatRowTag(fx.c1))).isTrue()
        assertThat(rule.exists(chatPaneTag(fx.c1))).isTrue()
        assertChatIntact(host, vm, subscriptions)
        // The popped inspector's T3 holder is gone; nothing else was cleared.
        assertThat(ProbeLedger.clearedCount(ChatContextKey(fx.c1).contentKey)).isEqualTo(1)
        assertThat(ProbeLedger.clearedCount(ChatKey(fx.c1).contentKey)).isEqualTo(0)
    }

    // ---- Test B: closed -> open (no duplicate, supporting panes appear) --------------------------

    @Test
    fun `B1 closed to open shows Conversations and the same chat once, row selected`() {
        val host = LiveHost(rule, fx, Fold.OUTER_STOCK, stackOf(Destination.CHAT, ChatHomeKey, ChatKey(fx.c1)))
        assertThat(rule.exists(chatRowTag(fx.c1))).isFalse() // one pane: the chat
        rule.onNodeWithTag(COMPOSER_TEST_TAG).performTextInput(DRAFT)
        val vm = chatVm()
        val subscriptions = fx.vault.observeMessagesCalls.get()

        host.flip(Fold.INNER_PORT)
        assertThat(rule.exists(RAIL_TAG)).isTrue()
        assertThat(rule.count(CHAT_SCREEN_TEST_TAG)).isEqualTo(1)
        assertThat(rule.count(chatRowTag(fx.c1))).isEqualTo(1)
        rule.onNodeWithTag(chatRowTag(fx.c1), useUnmergedTree = true).assertIsSelected()
        assertThat(
            host.nav.stacks
                .getValue(Destination.CHAT)
                .toList(),
        ).isEqualTo(listOf(ChatHomeKey, ChatKey(fx.c1)))
        assertChatIntact(host, vm, subscriptions)
    }

    @Test
    fun `B1 variant - a peek before the unfold becomes the extra pane`() {
        LiveHost(
            rule,
            fx,
            Fold.OUTER_STOCK,
            stackOf(Destination.CHAT, ChatHomeKey, ChatKey(fx.c1), ChatContextKey(fx.c1)),
        ).apply {
            assertThat(rule.exists(PEEK_TAG)).isTrue() // restored/unexpanded sheet shows as a peek
            val inspectorVm = rule.probeText(ChatContextKey(fx.c1).contentKey)
            flip(Fold.INNER_PORT)
            assertThat(rule.exists(PEEK_TAG)).isFalse()
            assertThat(rule.exists(INSPECTOR_TAG)).isTrue()
            assertThat(rule.exists(chatPaneTag(fx.c1))).isTrue()
            assertThat(rule.exists(chatRowTag(fx.c1))).isFalse() // Chat | Context: the list yields
            assertThat(rule.probeText(ChatContextKey(fx.c1).contentKey)).isEqualTo(inspectorVm)
        }
    }

    // ---- Test C: while generating -----------------------------------------------------------------

    @Test
    fun `C1 a turn keeps streaming into the same holder across four flips`() {
        val host = LiveHost(rule, fx, Fold.INNER_LAND, stackOf(Destination.CHAT, ChatHomeKey, ChatKey(fx.c1)))
        val vm = chatVm()
        val subscriptions = fx.vault.observeMessagesCalls.get()
        rule.onNodeWithTag(COMPOSER_TEST_TAG).performTextInput("q")
        rule.onNodeWithTag(SEND_BUTTON_TEST_TAG).performClick()

        rule.waitUntil(60_000) { fx.emitted.get() >= 40 }
        for ((size, next) in listOf(Fold.OUTER_STOCK to 70, Fold.INNER_LAND to 100, Fold.OUTER to 130)) {
            host.flip(size)
            assertThat(rule.exists(CANCEL_BUTTON_TEST_TAG)).isTrue() // Stop stays visible
            assertThat(chatVm()).isEqualTo(vm)
            // One collector, never re-subscribed.
            assertThat(fx.vault.observeMessagesCalls.get()).isEqualTo(subscriptions)
            rule.waitUntil(60_000) { fx.emitted.get() >= next }
        }
        rule.waitUntil(120_000) { fx.messages(fx.c1).size == 14 }
        val answer = fx.messages(fx.c1).last()
        assertThat(answer.role).isEqualTo(Role.ASSISTANT)
        assertThat(answer.contentMd).isEqualTo(fx.answer.joinToString(""))
        assertThat(answer.contentMd).doesNotContain("skein:interrupted")
        assertThat(chatVm()).isEqualTo(vm)
        assertThat(fx.streams.get()).isEqualTo(1) // one turn, one engine stream
    }

    // ---- Test D: keyboard active --------------------------------------------------------------------

    @Test
    fun `D1 composer text, selection and focus across flips`() {
        val host = LiveHost(rule, fx, Fold.INNER_LAND, stackOf(Destination.CHAT, ChatHomeKey, ChatKey(fx.c1)))
        rule.onNodeWithTag(COMPOSER_TEST_TAG).performTextInput(DRAFT)
        // Today's composer has no FocusRequester, so focus is checked on the stand-in that
        // carries AL-10's pattern (T3 flag + one requestFocus keyed on LocalSceneIdentity).
        rule.onNodeWithTag(STANDIN_COMPOSER_TAG).performClick()
        rule.onNodeWithTag(STANDIN_COMPOSER_TAG).performTextInput(DRAFT)
        rule.onNodeWithTag(STANDIN_COMPOSER_TAG).assertIsFocused()
        for (size in listOf(Fold.INNER_STOCK, Fold.OUTER, Fold.INNER_LAND, Fold.OUTER_STOCK, Fold.OUTER_LAND)) {
            host.flip(size)
            val composer = rule.onNodeWithTag(COMPOSER_TEST_TAG).fetchSemanticsNode()
            assertThat(composer.config.getOrNull(SemanticsProperties.EditableText)?.text).isEqualTo(DRAFT)
            assertThat(
                composer.config.getOrNull(SemanticsProperties.TextSelectionRange),
            ).isEqualTo(TextRange(DRAFT.length))
            val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
            assertThat(root.contains(composer.boundsInRoot.bottomRight - Offset(1f, 1f))).isTrue()
            rule.onNodeWithTag(STANDIN_COMPOSER_TAG).assertIsFocused()
            assertThat(rule.editableText(STANDIN_COMPOSER_TAG)).isEqualTo(DRAFT)
        }
    }

    // ---- Test E: Knowledge ---------------------------------------------------------------------------

    @Test
    fun `E1 note and Connections across a fold, pending keystroke saved once`() {
        val host =
            LiveHost(
                rule,
                fx,
                Fold.INNER_PORT,
                stackOf(Destination.KNOWLEDGE, KnowledgeHomeKey(), NoteKey(fx.n1), ConnectionsKey(fx.n1)),
            )
        rule.waitUntil(10_000) { rule.exists(SKEIN_EDITOR_TEST_TAG) }
        assertThat(rule.exists(notePaneTag(fx.n1))).isTrue()
        assertThat(rule.exists(CONNECTIONS_TAG)).isTrue()
        val noteVm = rule.probeText(NoteKey(fx.n1).contentKey)
        val saves = fx.vault.updateBodyCalls.get()
        rule.onNodeWithTag(SKEIN_EDITOR_TEST_TAG).performTextInput("Z")

        host.flip(Fold.OUTER_STOCK) // inside the 500 ms autosave debounce
        assertThat(rule.count(SKEIN_EDITOR_TEST_TAG)).isEqualTo(1)
        assertThat(rule.exists(PEEK_TAG)).isTrue()
        assertThat(rule.exists(SCRIM_TAG)).isFalse()
        assertThat(rule.probeText(NoteKey(fx.n1).contentKey)).isEqualTo(noteVm)

        host.flip(Fold.INNER_PORT)
        assertThat(rule.exists(CONNECTIONS_TAG)).isTrue()
        assertThat(rule.exists(PEEK_TAG)).isFalse()
        rule.waitUntil(10_000) { fx.vault.updateBodyCalls.get() > saves }
        Thread.sleep(1_000)
        rule.waitForIdle()
        assertThat(fx.vault.updateBodyCalls.get()).isEqualTo(saves + 1) // saved once, not lost, not duplicated
        assertThat(kotlinx.coroutines.runBlocking { fx.base.getDocument(fx.n1.value)!!.bodyMd }).contains("Z")

        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertThat(host.nav.current.toList()).isEqualTo(listOf(KnowledgeHomeKey(), NoteKey(fx.n1)))
        rule.onNodeWithTag(noteRowTag(fx.n1), useUnmergedTree = true).assertIsSelected()
        assertThat(rule.probeText(NoteKey(fx.n1).contentKey)).isEqualTo(noteVm)
    }

    // ---- Test F: Graph -------------------------------------------------------------------------------

    @Test
    fun `F1 canvas and selected node across a fold, graph state not rebuilt`() {
        val host =
            LiveHost(rule, fx, Fold.INNER_PORT, stackOf(Destination.GRAPH, GraphKey(fx.n1), GraphNodeKey(fx.n1, fx.n7)))
        assertThat(rule.exists(GRAPH_PANE_TAG)).isTrue()
        assertThat(rule.exists(NODE_TAG)).isTrue()
        val graphVm = rule.probeText(GraphKey(fx.n1).contentKey)
        val loads = fx.index.countOf("neighborhood")

        host.flip(Fold.OUTER_STOCK)
        assertThat(rule.exists(GRAPH_PANE_TAG)).isTrue()
        assertThat(rule.exists(PEEK_TAG)).isTrue()
        rule.onNodeWithText("Node · 3 links ▴").assertIsDisplayed()
        assertThat(fx.index.countOf("neighborhood")).isEqualTo(loads) // GraphState (zoom, pan, layout) kept

        host.flip(Fold.INNER_PORT)
        assertThat(rule.exists(NODE_TAG)).isTrue()
        assertThat(rule.exists(PEEK_TAG)).isFalse()
        assertThat(rule.probeText(GraphKey(fx.n1).contentKey)).isEqualTo(graphVm)
        assertThat(fx.index.countOf("neighborhood")).isEqualTo(loads)
        assertThat(host.nav.current.toList()).isEqualTo(listOf(GraphKey(fx.n1), GraphNodeKey(fx.n1, fx.n7)))
    }

    // ---- Test G: drawer and dialog state -----------------------------------------------------------

    @Test
    fun `G1 the drawer is closed after an unfold and after the refold`() {
        val host = LiveHost(rule, fx, Fold.OUTER_STOCK, stackOf(Destination.CHAT, ChatHomeKey))
        rule.onNodeWithTag(DRAWER_BUTTON_TAG).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(DRAWER_SHEET_TAG).assertIsDisplayed()
        host.flip(Fold.INNER_PORT)
        assertThat(rule.exists(RAIL_TAG)).isTrue()
        rule.onNodeWithTag(DRAWER_SHEET_TAG).assertIsNotDisplayed()
        host.flip(Fold.OUTER_STOCK)
        rule.onNodeWithTag(DRAWER_SHEET_TAG).assertIsNotDisplayed()
    }

    // ---- Test C2b: navigation mid-stream -------------------------------------------------------------

    private fun waitForTokens(n: Int) =
        rule.waitUntil(60_000) {
            org.robolectric.Shadows
                .shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofMillis(50))
            fx.emitted.get() >= n
        }

    @Test
    fun `C2b a T3-held turn survives following a source on one pane and a destination switch`() {
        val host = LiveHost(rule, fx, Fold.OUTER, stackOf(Destination.CHAT, ChatHomeKey, ChatKey(fx.c1)))
        rule.onNodeWithTag(T3_SEND_TAG).performClick()
        waitForTokens(40)
        host.nav.push(ChatSourceKey(fx.c1, fx.n1)) // one pane: the source route replaces the chat on screen
        rule.waitForIdle()
        assertThat(rule.exists(chatPaneTag(fx.c1))).isFalse()
        waitForTokens(70)
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        host.nav.switchTo(Destination.KNOWLEDGE)
        rule.waitForIdle()
        waitForTokens(100)
        host.nav.switchTo(Destination.CHAT)
        rule.waitUntil(60_000) {
            org.robolectric.Shadows
                .shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofMillis(50))
            fx.messages(fx.c1).size == 14
        }
        assertThat(fx.messages(fx.c1).last().contentMd).isEqualTo(fx.answer.joinToString(""))
        assertThat(fx.streams.get()).isEqualTo(1)
        // The chat entry's T3 store outlived the push and the switch (one holder ever). Today's ChatScreen
        // holder is composition-owned and would not have: that is CHAT_UX_SPEC.md C1, not the nav layer.
        assertThat(ProbeLedger.createdCount(ChatKey(fx.c1).contentKey)).isEqualTo(1)
    }

    @Test
    fun `G4 a delete confirmation persists across flips with the same target`() {
        val host =
            LiveHost(rule, fx, Fold.INNER_PORT, stackOf(Destination.KNOWLEDGE, KnowledgeHomeKey(), NoteKey(fx.n1)))
        rule.onNodeWithTag(DELETE_TAG).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Delete this note? (this one)").assertIsDisplayed()
        for (size in listOf(Fold.OUTER_STOCK, Fold.INNER_LAND, Fold.OUTER)) {
            host.flip(size)
            rule.onNodeWithText("Delete this note? (this one)").assertIsDisplayed()
        }
    }
}
