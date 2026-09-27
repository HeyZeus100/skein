// skein-xtov.24.4 (AL-05, throwaway): the JVM harness for the D8 gate — the
// §9.1 "Fold fixture" over the real ChatScreen/NoteTab/GraphScreen with fakes.
package app.skein.prototype.nav3

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.skein.core.model.Capability
import app.skein.core.model.DocId
import app.skein.core.model.Document
import app.skein.core.model.InferenceEngine
import app.skein.core.model.Message
import app.skein.core.model.Model
import app.skein.core.model.ModelFormat
import app.skein.core.model.Prompt
import app.skein.core.model.Role
import app.skein.core.model.SamplingParams
import app.skein.core.model.Token
import app.skein.core.model.TokenBudget
import app.skein.core.model.VaultRepository
import app.skein.feature.chat.SendPipeline
import app.skein.testing.CountingIndexStore
import app.skein.testing.FakePromptAssembler
import app.skein.testing.FakeRetrievalService
import app.skein.testing.InMemoryIndexStore
import app.skein.testing.fakeVault
import app.skein.testing.scriptedEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** The windows of §1 / §2.6. */
object Fold {
    val INNER_LAND = DpSize(1043.dp, 1006.dp) // row 10, the owner's grip
    val INNER_PORT = DpSize(1006.dp, 1043.dp) // row 9
    val OUTER = DpSize(524.dp, 1175.dp) // row 4, the owner's closed phone
    val OUTER_STOCK = DpSize(443.dp, 994.dp) // row 3
    val OUTER_LAND = DpSize(1175.dp, 524.dp) // row 6
    val OUTER_LAND_STOCK = DpSize(994.dp, 443.dp) // row 5
    val INNER_STOCK = DpSize(852.dp, 883.dp) // row 7
    val MEDIUM = DpSize(791.dp, 820.dp) // row 11
    val LARGE = DpSize(1280.dp, 800.dp) // row 24
}

/** Counts the calls that betray a re-created holder: each ChatViewModel subscribes once. */
class CountingVault(
    private val delegate: VaultRepository,
) : VaultRepository by delegate {
    val observeMessagesCalls = AtomicInteger()
    val updateBodyCalls = AtomicInteger()
    val getDocumentCalls = AtomicInteger()

    override suspend fun getDocument(id: DocId): Document? {
        getDocumentCalls.incrementAndGet()
        return delegate.getDocument(id)
    }

    override fun observeMessages(chatDocId: DocId): Flow<List<Message>> {
        observeMessagesCalls.incrementAndGet()
        return delegate.observeMessages(chatDocId)
    }

    override suspend fun updateBody(
        id: DocId,
        title: String,
        bodyMd: String,
    ): Document {
        updateBodyCalls.incrementAndGet()
        return delegate.updateBody(id, title, bodyMd)
    }
}

const val DRAFT = "draft that should survive a fold ✓"

/** §9.1's Fold fixture: chat c1 with 12 turns, notes n1/n2/n7, a slow scripted engine (one token per 50 ms). */
class GateFixture(
    tokens: Int = 200,
    tokenDelay: Duration = 50.milliseconds,
) {
    private lateinit var chat1: Document
    private lateinit var chat2: Document
    private lateinit var note1: Document
    private lateinit var note2: Document
    private lateinit var note7: Document

    val base =
        fakeVault {
            val turns = (1..6).flatMap { listOf(Role.USER to "question $it", Role.ASSISTANT to "answer $it") }
            chat1 = chat("Fold test", *turns.toTypedArray())
            chat2 = chat("Other chat")
            note1 = note("Note one", "First note. Links to [[Note two]].")
            note2 = note("Note two", "Second note.")
            note7 = note("Node seven", "A graph node.")
        }
    val c1 = SkeinId(chat1.id)
    val c2 = SkeinId(chat2.id)
    val n1 = SkeinId(note1.id)
    val n2 = SkeinId(note2.id)
    val n7 = SkeinId(note7.id)

    val vault = CountingVault(base)
    val index = CountingIndexStore(InMemoryIndexStore())
    val answer: List<String> = List(tokens) { "t${it + 1} " }
    val engine =
        scriptedEngine("User: q" to answer, tokenDelay = tokenDelay).also {
            runBlocking {
                it
                    .load(
                        Model(
                            id = "fake-model",
                            name = "Fake",
                            path = "/dev/null/fake.gguf",
                            sha256 = "a".repeat(64),
                            format = ModelFormat.GGUF,
                            capabilities = setOf(Capability.TEXT),
                            sizeBytes = 1_000L,
                        ),
                    ).getOrThrow()
            }
        }

    /** Tokens the engine has emitted (the streaming bubble may be scrolled out of the lazy list). */
    val emitted = AtomicInteger()
    val streams = AtomicInteger()
    private val countingEngine =
        object : InferenceEngine by engine {
            override fun stream(
                prompt: Prompt,
                params: SamplingParams,
            ): Flow<Token> {
                streams.incrementAndGet()
                return engine.stream(prompt, params).onEach { if (it is Token.Text) emitted.incrementAndGet() }
            }
        }
    val pipeline =
        SendPipeline(
            vaultRepository = vault,
            retrievalService = FakeRetrievalService(),
            promptAssembler = FakePromptAssembler(),
            engine = countingEngine,
            personaProvider = { null },
            budgetFor = {
                _,
                _,
                ->
                TokenBudget(contextLength = 16_384, reserveForAnswer = 1024, maxRetrievedTokens = 3072)
            },
            countTokens = { it.length / 4 },
        )
    val deps = ProtoDeps(vault, pipeline, index, chats = listOf(c1, c2), notes = listOf(n1, n2, n7))
    val gate = ProtoGate(open = true)

    fun chatDocumentCount(): Int = runBlocking { listOf(c1, c2).count { base.getDocument(it.value) != null } }

    fun messages(chat: SkeinId): List<Message> = runBlocking { base.listMessages(chat.value) }
}

fun stackOf(
    top: Destination,
    vararg keys: ProtoKey,
): () -> ProtoNavigationState = { ProtoNavigationState(top, keys.groupBy { it.destination }) }

/** One composition whose window is flipped live (§9.1 "The live flip"). */
@OptIn(ExperimentalTestApi::class)
class LiveHost(
    private val rule: ComposeContentTestRule,
    val fx: GateFixture,
    start: DpSize,
    nav: () -> ProtoNavigationState,
) {
    var size by mutableStateOf(start)
    lateinit var nav: ProtoNavigationState

    init {
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.WindowSize(size)) {
                ProtoRoot(fx.deps, fx.gate, nav, onNavigationState = { this.nav = it })
            }
        }
        rule.waitForIdle()
    }

    fun flip(to: DpSize) {
        size = to
        rule.waitForIdle()
    }
}

fun SemanticsNodeInteractionsProvider.exists(tag: String): Boolean =
    onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

fun SemanticsNodeInteractionsProvider.count(tag: String): Int =
    onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

fun SemanticsNodeInteractionsProvider.probe(contentKey: String): SemanticsNodeInteraction =
    onNodeWithTag(probeTag(contentKey), useUnmergedTree = true)

/** "vm=<serial> t2=<n>" of an entry's probe. */
fun SemanticsNodeInteractionsProvider.probeText(contentKey: String): String =
    probe(
        contentKey,
    ).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()

fun SemanticsNodeInteractionsProvider.editableText(tag: String): String =
    onNodeWithTag(
        tag,
        useUnmergedTree = true,
    ).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
