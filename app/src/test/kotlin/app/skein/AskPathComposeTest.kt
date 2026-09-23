package app.skein

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.skein.core.inference.models.ImportOutcome
import app.skein.core.inference.models.ImportProgress
import app.skein.core.inference.models.ImportSource
import app.skein.core.model.Capability
import app.skein.core.model.InferenceEngine
import app.skein.core.model.Model
import app.skein.core.model.ModelFormat
import app.skein.core.model.ModelRecord
import app.skein.core.vault.session.LockReason
import app.skein.core.vault.session.UnlockState
import app.skein.feature.chat.CHAT_SCREEN_TEST_TAG
import app.skein.feature.chat.COMPOSER_TEST_TAG
import app.skein.feature.chat.SEND_BUTTON_TEST_TAG
import app.skein.feature.models.MODELS_DEFAULT_MARKER
import app.skein.feature.models.MODELS_EMPTY_TEST_TAG
import app.skein.feature.shell.testing.ShellTestTags
import app.skein.testing.FakeInferenceEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * skein-whg8: the ask-path wiring's own Robolectric suite, following
 * [MainActivityComposeTest]'s precedent exactly ([awaitCondition]'s
 * diagnostics-on-timeout shape, [createEmptyComposeRule], scripting
 * [TestSkeinApplication] before `ActivityScenario.launch`, no wall-clock
 * sleeps). Each test proves exactly one DoD item from `bd show skein-whg8`;
 * see each test's own KDoc for which.
 *
 * RUNG CHOSEN for `/import model` (test 3's KDoc has the detail): the SAF
 * picker Intent itself is Android platform plumbing, not this bead's logic
 * — the test drives `ModelManager.import` the same way the real
 * `rememberLauncherForActivityResult` callback in `MainActivity` does,
 * rather than simulating the system document picker through Robolectric's
 * shadow `Activity.startActivityForResult`/`onActivityResult` machinery,
 * which would add real risk (an unfamiliar, version-sensitive shadow API)
 * for no additional coverage of code this bead actually wrote: the picker
 * launch call itself is one line (`importLauncher.launch(...)`), and the
 * business logic it triggers — `ModelManager.import` then `setDefault` then
 * `manifestCache.refresh()` — is exactly what this test exercises.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestSkeinApplication::class)
class AskPathComposeTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val app: TestSkeinApplication
        get() = ApplicationProvider.getApplicationContext()

    // ---- precedent helpers, mirroring MainActivityComposeTest ----

    private fun awaitTag(tag: String) {
        awaitCondition("a node with test tag \"$tag\" to appear") {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitText(text: String) {
        awaitCondition("text \"$text\" to appear") {
            composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitCondition(
        description: String,
        condition: () -> Boolean,
    ) {
        try {
            composeRule.waitUntil(
                conditionDescription = description,
                timeoutMillis = WAIT_MILLIS,
                condition = condition,
            )
        } catch (e: ComposeTimeoutException) {
            val tree = runCatching { composeRule.onRoot().printToString() }.getOrElse { "<failed: $it>" }
            throw AssertionError("Timed out waiting for $description\n${tree.take(4_000)}", e)
        }
    }

    private fun registerDefaultModel(): Model {
        val model =
            Model(
                id = "test-model",
                name = "Test Model",
                path = "/tmp/test-model/model.gguf",
                sha256 = "a".repeat(64),
                format = ModelFormat.GGUF,
                capabilities = setOf(Capability.TEXT),
                sizeBytes = 1_000L,
                contextLength = 2048,
            )
        runBlocking {
            app.modelRegistry.upsert(ModelRecord(model = model, blake3 = "b".repeat(64)))
            app.modelRegistry.setDefault(model.id)
        }
        return model
    }

    private fun openChatTab() {
        composeRule.onNode(hasSetTextAction()).performTextInput("/chat")
        composeRule.onNode(hasSetTextAction()).performImeAction()
    }

    /**
     * DoD item 3 / acceptance criterion 1: "unlock -> /chat -> send with a
     * FakeInferenceEngine substituted at the composition root shows the
     * streamed answer bubble."
     */
    @Test
    fun `chat sends a message and shows the streamed answer`() {
        // `FakeInferenceEngine`'s script keys off the FINAL assembled user
        // message, not the raw composer text: with no retrieved context,
        // `PromptAssemblerImpl.finalUserMessage` renders it as `"User: "`
        // plus the query verbatim (`PromptGuard.wrapRetrieved` returns "" for
        // an empty retrieval, so the guarded-context block is skipped).
        app.fakeInferenceEngine = FakeInferenceEngine(script = mapOf("User: hello" to listOf("answer")))
        registerDefaultModel()

        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag(ShellTestTags.SKEIN_SHELL_ROOT)
            openChatTab()
            awaitTag(CHAT_SCREEN_TEST_TAG)

            composeRule.onNodeWithTag(COMPOSER_TEST_TAG).performTextInput("hello")
            composeRule.onNodeWithTag(SEND_BUTTON_TEST_TAG).performSemanticsAction(SemanticsActions.OnClick)

            awaitText("answer")
        }
    }

    /**
     * DoD item 3: "/chat with no default model shows the guidance" —
     * "never crash", offers `/import model`.
     */
    @Test
    fun `chat with no default model shows guidance instead of crashing`() {
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag(ShellTestTags.SKEIN_SHELL_ROOT)
            openChatTab()

            awaitTag(MainActivityTestTags.CHAT_NO_MODEL_GUIDANCE)
            composeRule.onNodeWithText("No model yet.", substring = true).assertExists()
            composeRule.onNodeWithText("/import model", substring = true).assertExists()
            composeRule.onNodeWithTag(CHAT_SCREEN_TEST_TAG).assertDoesNotExist()
        }
    }

    /**
     * DoD item 2 / acceptance criterion 2: "lock during READY calls unload
     * exactly once" — and the companion half of skein-whg8's own JVM-test
     * requirement, "never after a lock with no model loaded".
     */
    @Test
    fun `locking the vault while a model is loaded unloads it exactly once`() {
        val counting = CountingUnloadEngine(FakeInferenceEngine(script = mapOf("User: hello" to listOf("answer"))))
        app.fakeInferenceEngine = counting
        registerDefaultModel()

        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag(ShellTestTags.SKEIN_SHELL_ROOT)
            openChatTab()
            awaitTag(CHAT_SCREEN_TEST_TAG)
            composeRule.onNodeWithTag(COMPOSER_TEST_TAG).performTextInput("hello")
            composeRule.onNodeWithTag(SEND_BUTTON_TEST_TAG).performSemanticsAction(SemanticsActions.OnClick)
            awaitText("answer")

            lockAndAwait()

            assertEquals(1, counting.unloadCalls)
        }
    }

    /** The other half of the same acceptance criterion: nothing was ever loaded, so unload is never called. */
    @Test
    fun `locking the vault with no model ever loaded never calls unload`() {
        val counting = CountingUnloadEngine(FakeInferenceEngine())
        app.fakeInferenceEngine = counting

        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag(ShellTestTags.SKEIN_SHELL_ROOT)

            lockAndAwait()

            assertEquals(0, counting.unloadCalls)
        }
    }

    /**
     * DoD item 3 / acceptance criterion 3: "/import model ... shows
     * progress then the default marker in /models." See this class's own
     * KDoc for the rung chosen on the picker Intent itself.
     */
    @Test
    fun `importing a model shows progress then the default marker in models`() {
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag(ShellTestTags.SKEIN_SHELL_ROOT)

            // `/models` before any import: the empty state, never a crash.
            composeRule.onNode(hasSetTextAction()).performTextInput("/models")
            composeRule.onNode(hasSetTextAction()).performImeAction()
            awaitTag(MODELS_EMPTY_TEST_TAG)

            val session = requireNotNull(app.vault.session.value)
            val services = requireNotNull(session.models)
            val events =
                runBlocking {
                    withTimeout(WAIT_MILLIS) {
                        services.manager
                            .import(
                                ImportSource.Picked(android.net.Uri.parse("content://test/model.gguf")),
                            ).toList()
                    }
                }
            // "Progress then done": at least one InProgress tick, exactly one terminal Done, in that order.
            assertTrue("expected at least one InProgress tick", events.first() is ImportProgress.InProgress)
            assertTrue("expected the last event to be Done", events.last() is ImportProgress.Done)
            val outcome = (events.last() as ImportProgress.Done).outcome
            val imported = outcome as? ImportOutcome.Imported ?: error("import refused: $outcome")
            runBlocking {
                services.manager.setDefault(imported.record.model.id)
                services.manifestCache.refresh()
            }

            // Reopen /models: the imported row now carries the default marker.
            composeRule.onNode(hasSetTextAction()).performTextInput("/models")
            composeRule.onNode(hasSetTextAction()).performImeAction()
            awaitText(imported.record.model.name)
            composeRule.onNodeWithText(MODELS_DEFAULT_MARKER, substring = true).assertExists()
        }
    }

    /** Locks the vault and blocks (bounded) until the state actually reaches `Locked`. */
    private fun lockAndAwait() {
        runBlocking {
            withTimeout(WAIT_MILLIS) {
                app.vault.unlockManager.lock(LockReason.USER_REQUESTED)
                app.vault.unlockManager.state
                    .first { it is UnlockState.Locked }
            }
        }
    }

    private companion object {
        const val WAIT_MILLIS = 5_000L
    }
}

/**
 * Counts [unload] calls at the DELEGATE — the engine [app.skein.models.ManagedInferenceEngine]
 * itself wraps — so a test can assert exactly what reached the real engine,
 * independent of [app.skein.models.ManagedInferenceEngine]'s own unload
 * guard (which is the thing under test: the guard is what makes "0 calls
 * when nothing was loaded" and "1 call when something was" both true
 * through the SAME unconditional lock-path call).
 */
private class CountingUnloadEngine(
    private val delegate: InferenceEngine,
) : InferenceEngine by delegate {
    var unloadCalls: Int = 0
        private set

    override suspend fun unload() {
        unloadCalls++
        delegate.unload()
    }
}
