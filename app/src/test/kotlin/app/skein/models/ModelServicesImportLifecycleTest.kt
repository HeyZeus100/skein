package app.skein.models

import android.app.Application
import android.net.Uri
import app.skein.core.inference.models.ImmutableModelStore
import app.skein.core.inference.models.ImportOutcome
import app.skein.core.inference.models.ImportRefusal
import app.skein.core.inference.models.ManifestFile
import app.skein.core.inference.models.ManifestLicense
import app.skein.core.inference.models.ModelCopyResult
import app.skein.core.inference.models.ModelManager
import app.skein.core.inference.models.ModelManifest
import app.skein.core.inference.models.PermissionEnforcement
import app.skein.core.inference.models.StoredModel
import app.skein.core.model.ModelFormat
import app.skein.core.model.ModelRecord
import app.skein.core.model.ModelRegistry
import app.skein.core.verify.ModelFileRole
import app.skein.feature.chat.SendPipeline
import app.skein.testing.FakeInferenceEngine
import app.skein.testing.FakePromptAssembler
import app.skein.testing.FakeRetrievalService
import app.skein.testing.InMemoryModelRegistry
import app.skein.testing.InMemoryVaultRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ModelServicesImportLifecycleTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `HIGH during parked unlock prevents old attachment cancelling newer registration`() =
        runTest {
            assertStaleUnlockCannotReplace { it.freezeTurns(7, 150) }
        }

    @Test
    fun `close during parked unlock prevents old attachment cancelling newer registration`() =
        runTest {
            assertStaleUnlockCannotReplace { it.closeSessionState() }
        }

    @Test
    fun `close during parked cache refresh prevents late authorization and import admission`() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val registry =
                object : ModelRegistry by InMemoryModelRegistry() {
                    override suspend fun list(): List<ModelRecord> {
                        entered.complete(Unit)
                        release.await()
                        return emptyList()
                    }
                }
            val imports = coordinator()
            var authorizations = 0
            val services = services(imports, registry) { authorizations++ }
            val unlocking = backgroundScope.async { services.unlocked(7) }
            runCurrent()
            assertThat(entered.isCompleted).isTrue()
            services.closeSessionState()
            release.complete(Unit)
            unlocking.await()
            assertThat(authorizations).isEqualTo(0)
            assertThat(imports.startImport(Uri.parse("content://test/model"))).isFalse()
        }

    private suspend fun TestScope.assertStaleUnlockCannotReplace(revoke: (ModelServices) -> Unit) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val imports = coordinator()
        val old =
            services(imports) {
                entered.complete(Unit)
                release.await()
            }
        val unlocking = backgroundScope.async { old.unlocked(7) }
        runCurrent()
        assertThat(entered.isCompleted).isTrue()
        revoke(old)

        val newerOwner = Any()
        val newerEntered = CompletableDeferred<Unit>()
        val newerRelease = CompletableDeferred<Unit>()
        var newerCancelled = false
        imports.attach(newerOwner) {
            newerEntered.complete(Unit)
            try {
                newerRelease.await()
                ImportOutcome.Refused(ImportRefusal.Unsupported)
            } finally {
                if (!newerRelease.isCompleted) newerCancelled = true
            }
        }
        assertThat(imports.startImport(Uri.parse("content://test/model"))).isTrue()
        imports.executePending(1) {}
        runCurrent()
        assertThat(newerEntered.isCompleted).isTrue()
        release.complete(Unit)
        unlocking.await()
        runCurrent()
        assertThat(newerCancelled).isFalse()
        old.closeSessionState() // A later backstop also cannot revoke the replacement capability.
        runCurrent()
        assertThat(newerCancelled).isFalse()
        newerRelease.complete(Unit)
        runCurrent()
        assertThat(imports.state.value).isEqualTo(ModelImportState.Done(ModelImportOutcome.REFUSED))
        assertThat(old.rescued.value).isEmpty()
        imports.detachOwner(newerOwner)
    }

    private fun TestScope.coordinator(): ModelImportCoordinator =
        ModelImportCoordinator(
            ImmutableModelStore(temp.root),
            copy = { _, _ -> copied() },
            startExecution = { _, _ -> },
            scope = backgroundScope,
        )

    private fun TestScope.services(
        imports: ModelImportCoordinator,
        registry: ModelRegistry = InMemoryModelRegistry(),
        onUnlocked: suspend (Long) -> Unit,
    ): ModelServices {
        val managed = ManagedInferenceEngine(FakeInferenceEngine()) { null }
        val manager =
            ModelManager(
                registry = registry,
                store = imports.store,
                modelInspector = { error("Revoked manager must never inspect") },
                pickedFileReader = { error("No provider copy through the session") },
                bundledSource = { null },
                freeBytes = { Long.MAX_VALUE },
                isLoaded = { false },
            )
        return ModelServices(
            store = imports.store,
            registry = registry,
            manager = manager,
            engine = managed,
            engineStatus = managed.status,
            sendPipeline =
                SendPipeline(
                    vaultRepository = InMemoryVaultRepository(),
                    retrievalService = FakeRetrievalService(),
                    promptAssembler = FakePromptAssembler(),
                    engine = managed,
                    personaProvider = { null },
                    budgetFor = { _, _ -> error("No chat turn in this lifecycle fixture") },
                    countTokens = { 0 },
                ),
            manifestCache = ManifestCache(registry),
            pushOnSessionUnlocked = onUnlocked,
            scope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob()),
            clearEngineState = managed::closeSession,
            imports = imports,
        )
    }

    private fun copied(): ModelCopyResult.Copied =
        ModelCopyResult.Copied(
            ModelManifest(
                id = "fixture-model",
                manifestVersion = 2,
                name = "fixture",
                format = ModelFormat.GGUF,
                capabilities = emptySet(),
                license = ManifestLicense("UNKNOWN"),
                main = ManifestFile(ModelFileRole.MAIN, "model.gguf", "0".repeat(64), 1),
                companions = emptyList(),
            ),
            StoredModel("fixture-model", temp.root, emptyMap(), PermissionEnforcement.POSIX),
        )
}
