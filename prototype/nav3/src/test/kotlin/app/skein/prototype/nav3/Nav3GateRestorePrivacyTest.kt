// skein-xtov.24.4 (AL-05): gate G6 — process-death restore from a parcelled
// Bundle, and the Bundle privacy scan under SECURITY_REVIEW_D7.md M1–M4:
// allowlist deny-by-default (M3a), no sentinel anywhere, nothing composed or
// queried before unlock (M4a), total decode (M4c), foreign ids never saved (M2).
package app.skein.prototype.nav3

import android.os.Bundle
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.navigation3.runtime.serialization.NavKeySerializer
import androidx.savedstate.serialization.decodeFromSavedState
import androidx.savedstate.serialization.encodeToSavedState
import app.skein.feature.chat.COMPOSER_TEST_TAG
import app.skein.feature.editor.SKEIN_EDITOR_TEST_TAG
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1043dp-h1006dp")
class Nav3GateRestorePrivacyTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val fx = GateFixture(tokens = 3)

    private val sentinels =
        listOf(
            "SENTINEL-composer-7f3a",
            "SENTINEL-t3draft-19c2",
            "SENTINEL-editor-a0b1",
            // Fixture content: titles, bodies, message text.
            "Fold test",
            "Other chat",
            "Note one",
            "Note two",
            "Node seven",
            "First note",
            "question 3",
            "answer 5",
        )

    @Before
    fun setUp() {
        ProbeLedger.reset()
        ProtoHost.deps = fx.deps
        ProtoHost.gate = fx.gate
        ProtoHost.nav = null
    }

    private val nav get() = ProtoHost.nav!!

    private fun bump(contentKey: String) =
        rule.onNodeWithTag(bumpTag(contentKey), useUnmergedTree = true).performClick()

    @Test
    fun `G6 process death - identical stacks from a parcelled Bundle that holds no text, nothing before unlock`() {
        ProtoHost.initialNav = {
            ProtoNavigationState(
                Destination.CHAT,
                mapOf(
                    Destination.CHAT to listOf(ChatHomeKey, ChatKey(fx.c1), ChatContextKey(fx.c1)),
                    Destination.KNOWLEDGE to listOf(KnowledgeHomeKey(KnowledgeFilter.NOTES), NoteKey(fx.n1)),
                    Destination.GRAPH to listOf(GraphKey(fx.n1), GraphNodeKey(fx.n1, fx.n7)),
                ),
            )
        }
        val first = Robolectric.buildActivity(ProtoActivity::class.java).setup()
        rule.waitForIdle()
        rule.onNodeWithTag(COMPOSER_TEST_TAG).performTextInput(sentinels[0])
        rule.onNodeWithTag(STANDIN_COMPOSER_TAG).performTextInput(sentinels[1])
        bump(ChatKey(fx.c1).contentKey)
        bump(ChatContextKey(fx.c1).contentKey)
        nav.switchTo(Destination.KNOWLEDGE)
        rule.waitUntil(10_000) { rule.exists(SKEIN_EDITOR_TEST_TAG) }
        rule.onNodeWithTag(SKEIN_EDITOR_TEST_TAG).performTextInput(sentinels[2])
        bump(NoteKey(fx.n1).contentKey)
        nav.switchTo(Destination.GRAPH)
        rule.waitForIdle()
        bump(GraphKey(fx.n1).contentKey)
        nav.switchTo(Destination.CHAT)
        rule.waitForIdle()
        val stacks = nav.stacks.mapValues { it.value.toList() }

        val saved = Bundle()
        first.saveInstanceState(saved)
        first.pause().stop().destroy() // not a configuration change: the Activity's ViewModelStore goes too
        val parcelled = BundleScan.roundTrip(saved)

        // M3(a): deny by default; every string key and leaf is on the allowlist.
        assertThat(BundleScan.violations(parcelled)).isEmpty()
        val bytes = BundleScan.bytes(parcelled)
        for (s in sentinels) assertThat(BundleScan.contains(bytes, s)).isFalse()
        assertThat(bytes.size).isLessThan(64 * 1024) // S4
        println("G6 saved-state parcel: ${bytes.size} bytes")

        // A new process, on the folded phone: a locked gate, fresh stores, the vault on disk.
        ProbeLedger.reset()
        ProtoHost.gate = ProtoGate(open = false)
        ProtoHost.initialNav = { error("the stacks must come from the Bundle") }
        val readsBefore = fx.vault.getDocumentCalls.get()
        RuntimeEnvironment.setQualifiers("w524dp-h1175dp")
        val second = Robolectric.buildActivity(ProtoActivity::class.java).setup(parcelled)
        rule.waitForIdle()
        // M4(a): only the gate composes; the repository is not touched.
        assertThat(rule.exists(GATE_TAG)).isTrue()
        assertThat(rule.exists(probeTag(ChatKey(fx.c1).contentKey))).isFalse()
        assertThat(ProbeLedger.created).isEmpty()
        assertThat(fx.vault.getDocumentCalls.get()).isEqualTo(readsBefore)

        ProtoHost.gate.unlock()
        rule.waitForIdle()
        assertThat(nav.stacks.mapValues { it.value.toList() }).isEqualTo(stacks)
        assertThat(nav.top).isEqualTo(Destination.CHAT)
        assertThat(rule.exists(PEEK_TAG)).isTrue() // A3: the restored inspector is a peek
        assertThat(rule.exists(SCRIM_TAG)).isFalse()
        assertThat(rule.probeText(ChatKey(fx.c1).contentKey)).endsWith("t2=1") // T2 from the Bundle
        assertThat(rule.probeText(ChatContextKey(fx.c1).contentKey)).endsWith("t2=1")
        assertThat(rule.editableText(STANDIN_COMPOSER_TAG)).isEmpty() // T3 text died with the process
        nav.switchTo(Destination.KNOWLEDGE)
        rule.waitForIdle()
        assertThat(rule.probeText(NoteKey(fx.n1).contentKey)).endsWith("t2=1")
        second.pause().stop().destroy()
    }

    @Test
    fun `the scan is not vacuous - a saveable string, a titled key and an unknown Parcelable are flagged`() {
        val leaky =
            Bundle().apply {
                putBundle(
                    "androidx.lifecycle.BundlableSavedStateRegistry.key",
                    Bundle().apply {
                        putBundle(
                            "SaveableStateRegistry:-1",
                            Bundle().apply {
                                // rememberSaveable { "…" }, and a default Nav3 contentKey of a key with a title:
                                putStringArrayList("k1a2b3", arrayListOf("my divorce lawyer"))
                                putString("ChatKey(chatId=x, title=Letters)", "")
                            },
                        )
                    },
                )
                putParcelable("android:views", android.graphics.Rect(1, 2, 3, 4))
            }
        val values = BundleScan.violations(BundleScan.roundTrip(leaky)).map { it.value }
        assertThat(values).contains("my divorce lawyer")
        assertThat(values).contains("ChatKey(chatId=x, title=Letters)")
        assertThat(values).contains("opaque Parcelable android.graphics.Rect")
    }

    @Test
    fun `M4c decode is total - unknown tag, foreign id and a truncated payload drop only that entry`() {
        val good = encodeToSavedState(ProtoKey.serializer(), ChatKey(fx.c1))
        val unknownTag = good.deepCopy().apply { putString("type", "chat.bogus") }
        val foreignId = good.deepCopy().apply { getBundle("value")?.putString("chatId", "project-falcon-notes") }
        val truncated = Bundle().apply { putString("type", "chat") }
        val wrongType = good.deepCopy().apply { getBundle("value")?.putInt("chatId", 7) }
        val decoded = KeyCodec.decode(listOf(unknownTag, good, foreignId, truncated, wrongType))
        assertThat(decoded).containsExactly(ChatKey(fx.c1))

        val allInvalid =
            Bundle().apply {
                putString("top", "NOT_A_DESTINATION")
                putParcelableArrayList(Destination.CHAT.name, arrayListOf(unknownTag, foreignId))
                putString(Destination.GRAPH.name, "not a list")
            }
        val restored = ProtoNavigationState.Saver.restore(allInvalid)!!
        assertThat(restored.top).isEqualTo(Destination.CHAT)
        assertThat(restored.stacks.mapValues { it.value.toList() })
            .isEqualTo(Destination.entries.associateWith { listOf(ProtoNavigationState.rootOf(it)) })
    }

    @Test
    fun `M1 M2 only canonical ids become keys, and a rejected id never reaches the message`() {
        for (foreign in listOf(
            "tag:divorce",
            "title:letter to my lawyer",
            "project-falcon-notes",
            fx.c1.value.uppercase(),
            "{${fx.c1.value}}",
            " ${fx.c1.value}",
        )) {
            assertThat(SkeinId.parse(foreign)).isNull()
            val e = runCatching { SkeinId(foreign) }.exceptionOrNull()
            assertThat(e).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(e!!.message).doesNotContain(foreign)
        }
        assertThat(SkeinId.parse(fx.c1.value)).isEqualTo(fx.c1)
    }

    @Test
    fun `M4d sanitise drops a deleted id silently and keeps the rest`() {
        val restoredNav =
            ProtoNavigationState(
                Destination.KNOWLEDGE,
                mapOf(
                    Destination.KNOWLEDGE to listOf(KnowledgeHomeKey(), NoteKey(fx.n1), NoteKey(fx.n2)),
                    Destination.GRAPH to listOf(GraphKey(fx.n2)),
                ),
            )
        kotlinx.coroutines.runBlocking {
            fx.base.deleteDocument(fx.n2.value)
            restoredNav.sanitise { fx.base.getDocument(it.value) != null }
        }
        assertThat(
            restoredNav.stacks.getValue(Destination.KNOWLEDGE).toList(),
        ).isEqualTo(listOf(KnowledgeHomeKey(), NoteKey(fx.n1)))
        assertThat(restoredNav.stacks.getValue(Destination.GRAPH).toList()).isEqualTo(listOf(GraphKey(null)))
    }

    /**
     * Why Skein does not use `rememberNavBackStack`: Nav3's stock key serializer writes the key's
     * Java class name (not its @SerialName) and loads whatever class the Bundle names.
     */
    @Test
    fun `evidence - Nav3's NavKeySerializer stores the class name and throws on an unknown one`() {
        val saved = encodeToSavedState(NavKeySerializer<ProtoKey>(), ChatKey(fx.c1))
        assertThat(BundleScan.violations(saved).map { it.value }).contains("app.skein.prototype.nav3.ChatKey")
        val tampered = saved.deepCopy().apply { putString("type", "com.example.Gone") }
        assertThat(runCatching { decodeFromSavedState(NavKeySerializer<ProtoKey>(), tampered) }.isFailure).isTrue()
    }
}
