package app.skein

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.skein.core.inference.models.ImmutableModelStore
import app.skein.core.inference.models.ImportRefusal
import app.skein.core.inference.models.ModelCopyResult
import app.skein.core.inference.models.ModelImportStager
import app.skein.core.inference.models.PickedFileHandle
import app.skein.core.model.ModelId
import app.skein.core.model.ModelRecord
import app.skein.core.model.ModelRegistry
import app.skein.feature.chat.COMPOSER_TEST_TAG
import app.skein.feature.chat.entries.ChatEntryTestTags
import app.skein.feature.models.entries.ModelsEntryTestTags
import app.skein.models.ModelImportCoordinator
import app.skein.models.ModelImportOutcome
import app.skein.models.ModelImportState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/** App-owned import observation through the production Activity, vault gate and NavShell. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestSkeinApplication::class, qualifiers = "w400dp-h800dp")
class MainActivityModelImportTest {
    @get:Rule val composeRule = createEmptyComposeRule()

    private val app: TestSkeinApplication get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `copy survives recreation and completion makes visible Chat ready without navigation`() {
        ImportHarness(app).use { fixture ->
            val registry = DelayedLookupRegistry(app.modelRegistry)
            app.modelRegistryOverride = registry
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitTag(ChatEntryTestTags.LANDING)
                composeRule.onNodeWithText(NO_MODEL).assertExists()
                composeRule.onNodeWithTag(COMPOSER_TEST_TAG).assertDoesNotExist()
                openModels()
                pickModel(scenario)
                awaitText(PROGRESS)
                composeRule.onNodeWithTag(ModelsEntryTestTags.PROGRESS_BAR).assertExists()

                val session = app.vault.session.value
                scenario.recreate()
                awaitText(PROGRESS)
                composeRule.onNodeWithTag(ModelsEntryTestTags.PROGRESS_BAR).assertExists()
                assertSame(session, app.vault.session.value)
                assertEquals(1, fixture.copies.get())

                navigate("Chat")
                awaitTag(ChatEntryTestTags.LANDING)
                composeRule.onNodeWithText(NO_MODEL).assertExists()
                composeRule.onNodeWithTag(COMPOSER_TEST_TAG).assertDoesNotExist()
                fixture.finishCopy.complete(Unit)

                // No navigation/recreation after completion: this must observe the registry refresh.
                awaitTag(COMPOSER_TEST_TAG)
                composeRule.onNodeWithText(NO_MODEL).assertDoesNotExist()
                composeRule.onNodeWithTag(ChatEntryTestTags.LANDING).assertExists()
                runBlocking {
                    val records = app.modelRegistry.list()
                    assertEquals(1, records.size)
                    assertEquals(records.single().model.id, app.modelRegistry.default())
                }
                assertEquals(1, fixture.copies.get())
                assertEquals(1, registry.defaultWrites.get())
            }
        }
    }

    @Test
    fun `late rescued name after recreation cannot replace or dismiss newer import refusal`() {
        ImportHarness(app).use { fixture ->
            val orphan = fixture.seedOrphan()
            val registry = DelayedLookupRegistry(app.modelRegistry)
            app.modelRegistryOverride = registry
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitTag(ChatEntryTestTags.LANDING)
                val services =
                    requireNotNull(
                        app.vault.session.value
                            ?.models,
                    )
                await("orphan adoption") { services.rescued.value.contains(orphan) }
                openModels()
                awaitText("Registered ", substring = true)
                fixture.refuseCopy = true
                pickModel(scenario)
                awaitText(PROGRESS)
                fixture.finishCopy.complete(Unit)
                awaitText(REFUSAL)
                val refusal = fixture.imports.state.value as ModelImportState.Done
                assertEquals(ModelImportOutcome.REFUSED, refusal.outcome)

                val gate = LookupGate(orphan)
                registry.gate = gate
                scenario.recreate()
                await("replayed rescue name lookup to suspend") { gate.entered.isCompleted }
                awaitText(REFUSAL)
                gate.release.complete(Unit)
                await("replayed rescue name lookup to finish") { gate.finished.isCompleted }
                composeRule.waitForIdle()
                composeRule.onNodeWithText(REFUSAL).assertExists()
                composeRule.onNodeWithText("Registered ", substring = true).assertDoesNotExist()

                // Cover both Compose virtual time and Android's delayed main-loop callbacks.
                composeRule.mainClock.advanceTimeBy(7_000)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(7))
                composeRule.waitForIdle()
                composeRule.onNodeWithText(REFUSAL).assertExists()
                assertSame(refusal, fixture.imports.state.value)
                assertEquals(1, fixture.copies.get())
            }
        }
    }

    private fun pickModel(scenario: ActivityScenario<MainActivity>) {
        composeRule.onNodeWithTag(ModelsEntryTestTags.IMPORT_ACTION).performSemanticsAction(SemanticsActions.OnClick)
        scenario.onActivity { activity ->
            val shadow = shadowOf(activity)
            val request = requireNotNull(shadow.nextStartedActivityForResult)
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, request.intent.action)
            shadow.receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(MODEL_URI))
        }
    }

    private fun openModels() {
        navigate("Models")
        awaitTag(ModelsEntryTestTags.IMPORT_ACTION)
    }

    private fun navigate(destination: String) {
        composeRule.onNodeWithContentDescription("Open navigation").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithText(destination).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun awaitTag(tag: String) {
        await("tag $tag") { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun awaitText(
        text: String,
        substring: Boolean = false,
    ) {
        await(
            "text $text",
        ) { composeRule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun await(
        description: String,
        condition: () -> Boolean,
    ) {
        composeRule.waitUntil(description, timeoutMillis = 30_000, condition = condition)
    }

    private class ImportHarness(
        private val app: TestSkeinApplication,
    ) : Closeable {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val store = ImmutableModelStore(File(app.filesDir, "shell-import-models"))
        private val stager =
            ModelImportStager(
                store,
                reader = {
                    PickedFileHandle(
                        "shell-fixture.gguf",
                        app.pickedModelBytes.size.toLong(),
                        ByteArrayInputStream(app.pickedModelBytes),
                    )
                },
                freeBytes = { Long.MAX_VALUE },
            )
        val finishCopy = CompletableDeferred<Unit>()
        val copies = AtomicInteger()

        @Volatile var refuseCopy = false
        val imports: ModelImportCoordinator

        init {
            lateinit var coordinator: ModelImportCoordinator
            coordinator =
                ModelImportCoordinator(
                    store,
                    copy = { uri, progress ->
                        copies.incrementAndGet()
                        progress(1, 4)
                        finishCopy.await()
                        if (refuseCopy) {
                            ModelCopyResult.Refused(
                                ImportRefusal.Unsupported,
                            )
                        } else {
                            stager.copy(uri, progress)
                        }
                    },
                    startExecution = { _, token -> assertTrue(coordinator.executePending(token) {}) },
                    scope = scope,
                )
            imports = coordinator
            app.modelImports = imports
        }

        fun seedOrphan(): ModelId = runBlocking { (stager.copy(MODEL_URI) as ModelCopyResult.Copied).manifest.id }

        override fun close() {
            scope.cancel()
            app.modelImports = null
            app.modelRegistryOverride = null
        }
    }

    private class LookupGate(
        val model: ModelId,
    ) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
    }

    private class DelayedLookupRegistry(
        private val delegate: ModelRegistry,
    ) : ModelRegistry by delegate {
        @Volatile var gate: LookupGate? = null
        val defaultWrites = AtomicInteger()

        override suspend fun setDefault(id: ModelId?) {
            defaultWrites.incrementAndGet()
            delegate.setDefault(id)
        }

        override suspend fun get(id: ModelId): ModelRecord? {
            val active = gate?.takeIf { it.model == id }
            active?.entered?.complete(Unit)
            active?.release?.await()
            return delegate.get(id).also { active?.finished?.complete(Unit) }
        }
    }

    private companion object {
        val MODEL_URI: Uri = Uri.parse("content://test/shell-fixture.gguf")
        const val NO_MODEL = "Add a model to start"
        const val PROGRESS = "Importing model… 25%"
        const val REFUSAL = "Couldn't import the model. Choose a different file and try again."
    }
}
