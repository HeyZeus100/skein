// skein-xtov.24.4 (AL-05): gate G7 — a vault lock clears every entry
// ViewModel (T3) but keeps the stacks (T1) and the hoisted T2 state, and
// (SECURITY_REVIEW_D7.md M12) the clearing is driven by the lock, not by
// composition, so it also happens while the Activity is stopped.
package app.skein.prototype.nav3

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.test.core.app.ActivityScenario
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

class Nav3GateLockTest {
    @RunWith(RobolectricTestRunner::class)
    @Config(sdk = [34], qualifiers = "w1400dp-h1400dp")
    class Live {
        @get:Rule
        val rule = createAndroidComposeRule<ComponentActivity>()

        private val fx = GateFixture(tokens = 3)

        @Before
        fun reset() = ProbeLedger.reset()

        @Test
        fun `G7 lock clears every entry ViewModel, keeps stacks and T2, and unlock restores the place`() {
            val host =
                LiveHost(
                    rule,
                    fx,
                    Fold.INNER_LAND,
                    {
                        ProtoNavigationState(
                            Destination.CHAT,
                            mapOf(
                                Destination.CHAT to listOf(ChatHomeKey, ChatKey(fx.c1), ChatContextKey(fx.c1)),
                                Destination.KNOWLEDGE to listOf(KnowledgeHomeKey(), NoteKey(fx.n1)),
                            ),
                        )
                    },
                )
            val chat = ChatKey(fx.c1).contentKey
            val context = ChatContextKey(fx.c1).contentKey
            rule.onNodeWithTag(bumpTag(chat), useUnmergedTree = true).performClick()
            rule.onNodeWithTag(STANDIN_COMPOSER_TAG).performTextInput(DRAFT)
            host.nav.switchTo(Destination.KNOWLEDGE)
            rule.waitForIdle()
            rule.onNodeWithTag(bumpTag(NoteKey(fx.n1).contentKey), useUnmergedTree = true).performClick()
            host.nav.switchTo(Destination.CHAT)
            rule.waitForIdle()
            val chatSerial = rule.probeText(chat)
            val stacks = host.nav.stacks.mapValues { it.value.toList() }
            val stores = ViewModelProvider(rule.activity)[SessionEntryStores::class.java]
            assertThat(stores.size).isGreaterThan(0)

            fx.gate.lock()
            // Synchronously, before any recomposition: every entry holder that was ever created is cleared.
            for ((key, created) in ProbeLedger.created) {
                assertThat(
                    ProbeLedger.clearedCount(key),
                ).isEqualTo(created.get())
            }
            assertThat(ProbeLedger.clearedCount(NoteKey(fx.n1).contentKey)).isEqualTo(1) // the other destination's too
            assertThat(stores.size).isEqualTo(0)
            rule.waitForIdle()
            assertThat(rule.exists(GATE_TAG)).isTrue()
            assertThat(rule.exists(chatPaneTag(fx.c1))).isFalse()
            assertThat(host.nav.stacks.mapValues { it.value.toList() }).isEqualTo(stacks)

            fx.gate.unlock()
            rule.waitForIdle()
            assertThat(host.nav.stacks.mapValues { it.value.toList() }).isEqualTo(stacks)
            assertThat(rule.exists(INSPECTOR_TAG)).isTrue()
            val after = rule.probeText(chat)
            assertThat(after).endsWith("t2=1") // T2 survived the lock
            assertThat(after).isNotEqualTo(chatSerial) // a fresh T3 holder
            // T3 text did not survive the lock (B3's encrypted row is what brings drafts back).
            assertThat(rule.editableText(STANDIN_COMPOSER_TAG)).isEmpty()
            assertThat(rule.probeText(context)).endsWith("t2=0")
            host.nav.switchTo(Destination.KNOWLEDGE)
            rule.waitForIdle()
            assertThat(rule.probeText(NoteKey(fx.n1).contentKey)).endsWith("t2=1")
        }
    }

    @RunWith(RobolectricTestRunner::class)
    @Config(sdk = [34], qualifiers = "w1043dp-h1006dp")
    class Stopped {
        @get:Rule
        val rule = createEmptyComposeRule()

        private val fx = GateFixture(tokens = 3)

        @Test
        fun `M12 a lock while the Activity is stopped clears every entry ViewModel at once`() {
            ProbeLedger.reset()
            ProtoHost.deps = fx.deps
            ProtoHost.gate = fx.gate
            ProtoHost.initialNav = stackOf(Destination.CHAT, ChatHomeKey, ChatKey(fx.c1), ChatContextKey(fx.c1))
            val scenario = ActivityScenario.launch(ProtoActivity::class.java)
            rule.waitForIdle()
            assertThat(ProbeLedger.createdCount(ChatKey(fx.c1).contentKey)).isEqualTo(1)
            scenario.moveToState(Lifecycle.State.CREATED) // screen off: the recomposer pauses
            fx.gate.lock()
            for ((key, created) in ProbeLedger.created) {
                assertThat(
                    ProbeLedger.clearedCount(key),
                ).isEqualTo(created.get())
            }
            scenario.moveToState(Lifecycle.State.RESUMED)
            rule.waitForIdle()
            assertThat(rule.exists(GATE_TAG)).isTrue()
            scenario.close()
        }
    }

    /** Evidence for AL-08: the stock T3 decorator defers `onCleared` until the entry leaves composition. */
    @RunWith(RobolectricTestRunner::class)
    @Config(sdk = [34])
    class StockDecorator {
        @get:Rule
        val rule = createAndroidComposeRule<ComponentActivity>()

        class Holder : ViewModel() {
            override fun onCleared() {
                cleared.incrementAndGet()
            }

            companion object {
                val cleared = AtomicInteger()
            }
        }

        @Test
        fun stockDecoratorDefersClearWhileComposed() {
            Holder.cleared.set(0)
            val session =
                object : ViewModelStoreOwner {
                    override val viewModelStore = ViewModelStore()
                }
            var unlocked by mutableStateOf(true)
            rule.setContent {
                if (unlocked) {
                    NavDisplay(
                        backStack = listOf("entry"),
                        entryDecorators =
                            listOf(
                                rememberSaveableStateHolderNavEntryDecorator(),
                                rememberViewModelStoreNavEntryDecorator(session),
                            ),
                        entryProvider = { key ->
                            NavEntry(key) {
                                viewModel { Holder() }
                                Text("entry")
                            }
                        },
                    )
                }
            }
            rule.waitForIdle()
            rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            session.viewModelStore.clear() // the session closes while the screen is off
            unlocked = false // the gate flips, but the recomposer is paused
            assertThat(Holder.cleared.get()).isEqualTo(0) // decrypted content would still be held
            rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            rule.waitForIdle()
            assertThat(Holder.cleared.get()).isEqualTo(1) // only once composition catches up
        }
    }
}
