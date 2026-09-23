package app.skein

import androidx.work.WorkManager
import app.skein.core.inference.ContextBudget
import app.skein.core.inference.TokenCounter
import app.skein.core.inference.models.ImmutableModelStore
import app.skein.core.inference.models.ModelBytesSource
import app.skein.core.inference.models.ModelInspector
import app.skein.core.inference.models.ModelManager
import app.skein.core.inference.models.PickedFileHandle
import app.skein.core.model.InferenceEngine
import app.skein.core.model.SamplingParams
import app.skein.core.rag.ingest.IngestPace
import app.skein.core.rag.prompt.PromptAssemblerImpl
import app.skein.core.rag.retrieval.RetrievalServiceImpl
import app.skein.core.vault.lifecycle.VaultReset
import app.skein.core.vault.provider.VaultDocumentsProvider
import app.skein.core.vault.session.LockPolicy
import app.skein.core.vault.session.UnlockManager
import app.skein.core.vault.session.UnlockState
import app.skein.export.stage.ExportStageCoordinator
import app.skein.export.stage.FakeExportStageRepository
import app.skein.feature.chat.SendPipeline
import app.skein.ingest.IngestPacer
import app.skein.ingest.IngestPipelines
import app.skein.ingest.IngestScheduler
import app.skein.ingest.IngestWorkPort
import app.skein.ipc.ErrorCode
import app.skein.ipc.ModelInspection
import app.skein.models.ManagedInferenceEngine
import app.skein.models.ManifestCache
import app.skein.models.ModelServices
import app.skein.models.syncCountTokens
import app.skein.system.SecurityPrefs
import app.skein.testing.FakeExportService
import app.skein.testing.FakeImportService
import app.skein.testing.FakeInferenceEngine
import app.skein.testing.InMemoryIndexStore
import app.skein.testing.InMemoryModelRegistry
import app.skein.testing.InMemoryPersonaService
import app.skein.testing.InMemoryVaultRepository
import app.skein.vault.DocumentsProviderPort
import app.skein.vault.ScriptedVaultKeyProvider
import app.skein.vault.VaultBootstrap
import app.skein.vault.VaultOpenException
import app.skein.vault.VaultServices
import app.skein.vault.VaultSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration

/**
 * Robolectric stand-in for [SkeinApplication] (`@Config(application = ...)`):
 * the same [VaultServices] shape over a [ScriptedVaultKeyProvider], an
 * in-memory vault, and a no-op provider port — so `MainActivity` tests can
 * drive the real setup → unlock → bring-up → shell path without SQLCipher
 * or a biometric prompt. The bootstrap runs the production first-persona
 * seed ([VaultServices.seedFirstPersona]) over [personaService].
 *
 * E5.I10 (skein-7v3): the `IngestScheduler` is wired over a recording
 * [IngestWorkPort] ([enqueuedEpochs]) instead of WorkManager, so no
 * Robolectric test here initializes WorkManager as a side effect.
 */
class TestSkeinApplication : SkeinApplication() {
    val keyProvider = ScriptedVaultKeyProvider()
    val repository = InMemoryVaultRepository()
    val personaService = InMemoryPersonaService()

    /**
     * skein-whg8: the ask-path composition root's substitutable engine —
     * "unlock -> /chat -> send with a FakeInferenceEngine substituted at the
     * composition root". Tests script this (`app.fakeInferenceEngine =
     * FakeInferenceEngine(script = mapOf(...))`) before `ActivityScenario.launch`,
     * matching every other scripted-before-launch fixture on this class
     * (`keyProvider.nextUnlock`, `failOpenWith`); [createVaultServices]'s
     * `openVault` lambda reads this field lazily, at the moment a test
     * actually unlocks, so a per-test script always takes effect. Typed as
     * the plain [InferenceEngine] contract (not the concrete
     * `FakeInferenceEngine`) so a lock/unload test can substitute a
     * call-counting decorator around one.
     */
    var fakeInferenceEngine: InferenceEngine = FakeInferenceEngine()

    /** Backs `/models`, `/import model` and the default-model lookup [ManagedInferenceEngine.defaultModel] reads. */
    val modelRegistry = InMemoryModelRegistry()

    /**
     * `/import model`'s picker result — a plausible GGUF prefix
     * ([validGguf]) by default so [ModelManager.import]'s bounded
     * structural pre-check (`GgufPreCheck`) accepts it; a test that wants
     * [app.skein.core.inference.models.ImportRefusal.StructurallyInvalid]
     * instead sets this to non-GGUF bytes before launch.
     */
    var pickedModelBytes: ByteArray = validGguf(ByteArray(0))

    /** Session epochs the scheduler asked the (fake) work port to enqueue, in order. */
    val enqueuedEpochs = java.util.Collections.synchronizedList(mutableListOf<Long>())

    /** When non-null, every open fails with this reason (exercises the gate's failure state). */
    @Volatile
    var failOpenWith: String? = null

    /**
     * Controls when a successful vault open completes for deterministic testing (skein-spe3).
     * Used by the retry test to gate the open until the test explicitly allows it.
     * Starts as a completed deferred so normal tests proceed without waiting.
     */
    var openReadySignal: CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { it.complete(Unit) }

    /** skein-v3wb: aliases the fake keystore in [createVaultServices] recorded a delete call for. */
    val deletedKeystoreAliases: MutableList<String> = mutableListOf()

    override fun createVaultServices(): VaultServices {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val unlockManager = UnlockManager(keyProvider = keyProvider, scope = scope)
        val vaultReset =
            VaultReset(
                vaultDir = filesDir,
                databaseFile = File(filesDir, "vault.db"),
                attachmentsDir = File(filesDir, VaultServices.ATTACHMENTS_DIR),
                stagingDir = File(cacheDir, "staging_export"),
                keystore = { alias -> deletedKeystoreAliases += alias },
                isUnlocked = { unlockManager.state.value is UnlockState.Unlocked },
            )
        val bootstrap =
            VaultBootstrap(
                unlockManager = unlockManager,
                openVault = {
                    failOpenWith?.let { throw VaultOpenException(it) }
                    // For deterministic testing (skein-spe3): gate successful opens behind
                    // a signal the test controls, so the retry path is not timing-sensitive.
                    openReadySignal.await()
                    val indexStore = InMemoryIndexStore()
                    VaultSession(
                        repository = repository,
                        indexStore = indexStore,
                        personaService = personaService,
                        exportService = FakeExportService(),
                        importService = FakeImportService(),
                        exportStages = FakeExportStageRepository(),
                        // skein-whg8: built fresh per open, over whatever
                        // `fakeInferenceEngine` the test scripted — see that
                        // field's own KDoc.
                        models = buildTestModelServices(indexStore),
                    ) {}
                },
                provider =
                    object : DocumentsProviderPort {
                        override fun install(services: VaultDocumentsProvider.Services?) = Unit

                        override fun notifyRootsChanged() = Unit
                    },
                scope = scope,
                seed = VaultServices::seedFirstPersona,
            )
        wireLockPolicyForTest(unlockManager, scope)
        val ingest =
            IngestScheduler(
                unlockManager = unlockManager,
                session = bootstrap.session,
                port =
                    object : IngestWorkPort {
                        override fun enqueue(sessionEpoch: Long) {
                            enqueuedEpochs += sessionEpoch
                        }

                        override fun cancel() = Unit
                    },
                pacer =
                    object : IngestPacer {
                        override fun pace(): IngestPace = IngestPace.FULL
                    },
                pipelines = { session, pace -> IngestPipelines.forSession(session, pace) },
                scope = scope,
            ).also { it.start() }
        // skein-0m1z: the real coordinator over the test session's fake
        // repository. `workManager` is a lambda and is only resolved by
        // `record()`, which no test in this source set calls, so a test that
        // never initialises WorkManager is unaffected.
        val exportStages =
            ExportStageCoordinator(
                unlockManager = unlockManager,
                repository = { bootstrap.session.value?.exportStages },
                workManager = { WorkManager.getInstance(this) },
                stagingDir = File(cacheDir, "staging_export"),
                scope = scope,
            )
        return VaultServices(keyProvider, unlockManager, bootstrap, ingest, vaultReset, exportStages)
    }

    /**
     * skein-whg8: the JVM-testable twin of `ModelServices.forSession` —
     * same shape (store/registry/manager/engine/sendPipeline), but every
     * Android/Binder-bound collaborator (`LlamaCppEngine`,
     * `AndroidServiceConnector`, `ContentResolverPickedFileReader`) is
     * replaced by its fake, and the three session-epoch pushes are left at
     * [ModelServices]'s own no-op defaults (they are `LlamaCppEngine`-only
     * API — see that class's constructor doc). [indexStore] is the SAME
     * instance the enclosing [VaultSession] gets, so `RetrievalServiceImpl`
     * sees whatever a test indexes.
     */
    private fun buildTestModelServices(indexStore: InMemoryIndexStore): ModelServices {
        val store = ImmutableModelStore(File(filesDir, "test-models"))
        val managed =
            ManagedInferenceEngine(fakeInferenceEngine) {
                modelRegistry.default()?.let { id -> modelRegistry.get(id)?.model }
            }
        val manager =
            ModelManager(
                registry = modelRegistry,
                store = store,
                modelInspector =
                    ModelInspector {
                        ModelInspection(
                            errorCode = ErrorCode.OK,
                            architecture = "test",
                            quantization = null,
                            parameterCount = null,
                            contextLength = 4096,
                            embeddingWidth = null,
                            hasVision = false,
                            hasChatTemplate = false,
                            chatTemplateOk = false,
                            tokenizerModel = null,
                        )
                    },
                pickedFileReader = {
                    PickedFileHandle(
                        "test.gguf",
                        pickedModelBytes.size.toLong(),
                        ByteArrayInputStream(pickedModelBytes),
                    )
                },
                bundledSource = ModelBytesSource { null },
                freeBytes = { Long.MAX_VALUE },
                isLoaded = { id -> managed.status.value.modelId == id },
            )
        val manifestCache = ManifestCache(modelRegistry)
        val contextBudget = ContextBudget(tokenCounter = TokenCounter { text -> text.length })
        val retrievalService = RetrievalServiceImpl(index = indexStore, repository = repository, embedder = null)
        val sendPipeline =
            SendPipeline(
                vaultRepository = repository,
                retrievalService = retrievalService,
                promptAssembler = PromptAssemblerImpl(),
                engine = managed,
                personaProvider = { personaService.default() },
                budgetFor = contextBudget::computeBudget,
                countTokens = syncCountTokens(contextBudget),
                samplingParams = { SamplingParams() },
            )
        return ModelServices(
            store = store,
            registry = modelRegistry,
            manager = manager,
            engine = managed,
            engineStatus = managed.status,
            sendPipeline = sendPipeline,
            manifestCache = manifestCache,
        )
    }

    /**
     * Test-only mirror of `VaultServices.forDevice`'s private
     * `wireLockPolicy` (skein-up0/skein-qsux): collects this same
     * [SecurityPrefs] instance `MainActivity` reads/writes into
     * [UnlockManager.configure], live, for the lifetime of [scope] — so a
     * Robolectric test that drives Settings › Security through the real
     * `MainActivity` UI can assert `unlockManager.policy` reflects a change
     * without `MainActivity` itself ever calling `configure` (that stays the
     * production wiring's sole responsibility; see `VaultServices.forDevice`'s
     * doc). Duplicated rather than reused because `wireLockPolicy` is a
     * private implementation detail of `VaultServices`'s companion, and this
     * worktree's task is scoped to `:app`'s test sources only.
     */
    private fun wireLockPolicyForTest(
        unlockManager: UnlockManager,
        scope: CoroutineScope,
    ) {
        val securityPrefs = SecurityPrefs(this)
        scope.launch {
            combine(
                securityPrefs.idleTimeoutMinutes,
                securityPrefs.lockOnScreenOff,
                securityPrefs.lockOnBackground,
            ) { minutes, lockOnScreenOff, lockOnBackground ->
                LockPolicy(
                    idleTimeout = Duration.ofMinutes(minutes.toLong()),
                    lockOnScreenOff = lockOnScreenOff,
                    lockOnBackground = lockOnBackground,
                )
            }.collect { policy -> unlockManager.configure(policy) }
        }
    }
}

/**
 * A minimal, `GgufPreCheck`-plausible header (magic, version 3, zero
 * tensors, zero KV pairs) followed by [payload] — the same fixture recipe
 * `core/inference`'s own `ModelManagerTest.validGguf` uses. `ImportSource.Picked`
 * always runs the bounded pre-check before registering, so [TestSkeinApplication.pickedModelBytes]
 * needs a real GGUF-shaped prefix; plain random bytes are correctly refused
 * as `ImportRefusal.StructurallyInvalid`, which is the pre-check doing its
 * job, not a bug to route around with anything less than a valid header.
 */
private fun validGguf(payload: ByteArray): ByteArray {
    val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
    header.putInt(0x4655_4747) // "GGUF" magic, little-endian u32
    header.putInt(3) // version
    header.putLong(0L) // tensorCount
    header.putLong(0L) // kvCount
    return header.array() + payload
}
