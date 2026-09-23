// skein-whg8 — the per-unlocked-session composition root for the ask path:
// `ImmutableModelStore`, `ModelRegistryImpl`, `ModelManager`, `LlamaCppEngine`
// and the chat `SendPipeline`, all built exactly once per session
// (`VaultSession.models`) and torn down on lock. See `bd show skein-whg8`
// for the injection points this wires and `bd show skein-1uw`/`skein-cyq`
// for the constructors it calls.
//
// TESTABILITY (DoD: "unlock -> /chat -> send with a FakeInferenceEngine
// substituted at the composition root"). [ModelServices]'s own constructor
// takes already-built collaborators — [engine] as the locked `InferenceEngine`
// contract, never the concrete `LlamaCppEngine` — so a test builds one over
// `FakeInferenceEngine` wrapped in [ManagedInferenceEngine] directly, with
// the three session-epoch pushes left at their no-op defaults (they are
// `LlamaCppEngine`-only API — LOCK_POLICY_INDEXING.md §5.2/§6.1 — that
// `FakeInferenceEngine` has no reason to implement). [forSession] is the
// ONLY place that constructs a real [LlamaCppEngine] and wires those three
// pushes to it.
//
// REGISTRY REHYDRATION (correctness note, not in any coordinator brief but
// required by `ImmutableModelStore`'s own KDoc): the store's in-memory
// `registry` map is empty on every fresh process start — it is not the
// `models` table, and the store "never persists metadata into the model
// directory". [forSession] rehydrates it from [ModelRegistry.list] before
// anything else touches the store, so a model imported in an earlier
// process lifetime can still be `open()`ed (and therefore loaded) in this
// one. `store.open` would otherwise refuse every previously-imported model
// with `FileMissing` on the first launch after the process that imported it.
package app.skein.models

import android.content.Context
import android.content.SharedPreferences
import androidx.sqlite.SQLiteConnection
import app.skein.core.inference.ContextBudget
import app.skein.core.inference.InferenceConfig
import app.skein.core.inference.engine.AndroidServiceConnector
import app.skein.core.inference.engine.LlamaCppEngine
import app.skein.core.inference.engine.StoreModelPinSource
import app.skein.core.inference.models.ContentResolverPickedFileReader
import app.skein.core.inference.models.ImmutableModelStore
import app.skein.core.inference.models.ModelBytesSource
import app.skein.core.inference.models.ModelInspector
import app.skein.core.inference.models.ModelManager
import app.skein.core.inference.models.PermissionEnforcement
import app.skein.core.inference.models.StoredFile
import app.skein.core.inference.models.StoredModel
import app.skein.core.model.EngineState
import app.skein.core.model.IndexStore
import app.skein.core.model.InferenceEngine
import app.skein.core.model.ModelId
import app.skein.core.model.ModelRegistry
import app.skein.core.model.ModelStatus
import app.skein.core.model.Persona
import app.skein.core.model.SamplingParams
import app.skein.core.model.VaultRepository
import app.skein.core.rag.prompt.PromptAssemblerImpl
import app.skein.core.rag.retrieval.RetrievalServiceImpl
import app.skein.core.vault.models.ModelRegistryImpl
import app.skein.core.verify.ModelFileRole
import app.skein.feature.chat.SendPipeline
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException

/**
 * Everything the ask path needs for one open vault, built by [forSession]
 * (or, in tests, this constructor directly) and owned by
 * [app.skein.vault.VaultSession.models].
 *
 * @param engine the [InferenceEngine] `SendPipeline`/`feature.chat` sees —
 *   always a [ManagedInferenceEngine], wrapping either the real
 *   `LlamaCppEngine` ([forSession]) or a `FakeInferenceEngine` (tests).
 * @param engineStatus [ManagedInferenceEngine.status] — the command-bar
 *   chip's and `ModelNotifier`'s only source of truth (never
 *   `:core:inference` directly; see that class's file header for why).
 * @param pushOnSessionUnlocked/pushOnSessionLocking/pushOnSessionLocked the
 *   `LlamaCppEngine`-only session-epoch pushes (skein-1uw's contract).
 *   No-ops by default so a test composition never has to fabricate them.
 */
public class ModelServices(
    public val store: ImmutableModelStore,
    public val registry: ModelRegistry,
    public val manager: ModelManager,
    public val engine: InferenceEngine,
    public val engineStatus: StateFlow<ModelStatus>,
    public val sendPipeline: SendPipeline,
    public val manifestCache: ManifestCache,
    private val pushOnSessionUnlocked: suspend (epoch: Long) -> Unit = { _ -> },
    private val pushOnSessionLocking: suspend (epoch: Long, budgetMillis: Long) -> Unit = { _, _ -> },
    private val pushOnSessionLocked: suspend (epoch: Long) -> Unit = { _ -> },
) {
    /** Synchronous "is this the model currently loaded" read for `/models`' "delete refused while loaded" row. */
    public fun isLoaded(id: ModelId): Boolean {
        val status = engineStatus.value
        return status.modelId == id && status.state != EngineState.UNLOADED && status.state != EngineState.ERROR
    }

    /**
     * Called once, right after [app.skein.vault.VaultBootstrap] publishes
     * the freshly opened session — "at start-up" per skein-1uw's
     * coordinator note: the engine's `authorizedEpoch` starts at `NONE` and
     * refuses everything until this runs.
     */
    public suspend fun unlocked(epoch: Long) {
        manifestCache.refresh()
        pushOnSessionUnlocked(epoch)
    }

    /**
     * The lock-path hook `VaultBootstrap.LockHandler.onLocking` calls, while
     * the master key is still live, BEFORE the session's connections close
     * (DoD item 5). Order: push the locking notice first (the cancel budget
     * starts, LOCK_POLICY_INDEXING.md §5.2), then unload — guarded by
     * [ManagedInferenceEngine.unload] itself, so a session that opened a
     * chat tab but never sent a message never asks the delegate to unload
     * anything — then push the epoch-forget immediately rather than
     * deferring to [onLocked]'s backstop, which in the common case never
     * fires at all (see `VaultBootstrap`'s edit for why). Forgetting the
     * epoch touches no key-derived state, so running it here rather than
     * strictly after `keyProvider.lock()` does not weaken the lock
     * contract's "no key material after onLocked" rule.
     */
    public suspend fun onLocking(
        epoch: Long,
        budgetMillis: Long,
    ) {
        pushOnSessionLocking(epoch, budgetMillis)
        engine.unload()
        pushOnSessionLocked(epoch)
    }

    /**
     * The backstop `VaultBootstrap.LockHandler.onLocked` calls only when
     * [onLocking] never ran (its tier's shared budget was exhausted before
     * the mutex). Idempotent: [pushOnSessionLocked] itself no-ops on a
     * repeat call (`LlamaCppEngine.onSessionLocked`'s `compareAndSet`).
     */
    public suspend fun onLocked(epoch: Long) {
        pushOnSessionLocked(epoch)
    }

    public companion object {
        /**
         * Production wiring — every constructor here is the one named on
         * `bd show skein-whg8` / `skein-1uw` / `skein-cyq`.
         *
         * @param connection the session's own reader connection, drawn from
         *   `DeviceVaultOpener`'s pool exactly like `IndexStoreImpl`'s and
         *   `PersonaServiceImpl`'s (see that file's edit for this bead).
         * @param prefs `context.getSharedPreferences("ui_prefs", MODE_PRIVATE)`
         *   — `ModelRegistryImpl`'s own file header names this exact name.
         * @param sessionEpoch read FRESH per request, by `LlamaCppEngine`
         *   itself — the caller (`VaultServices.forDevice`) supplies
         *   `{ unlockManager.authorizationToken.value?.epoch ?: 0L }`, never
         *   a captured value.
         * @param personaProvider `{ personaService.default() }` — no
         *   "current persona" concept exists in the shell yet (`SkeinApp`'s
         *   own doc), so every turn resolves the same way `seedFirstPersona`
         *   already does.
         */
        public suspend fun forSession(
            context: Context,
            connection: SQLiteConnection,
            prefs: SharedPreferences,
            sessionEpoch: () -> Long,
            vaultRepository: VaultRepository,
            indexStore: IndexStore,
            personaProvider: suspend () -> Persona?,
            config: InferenceConfig = InferenceConfig(),
        ): ModelServices {
            val store = ImmutableModelStore(File(context.filesDir, MODELS_DIR_NAME))
            val registry = ModelRegistryImpl(connection = connection, prefs = prefs)
            rehydrate(store, registry)

            val manifestCache = ManifestCache(registry)
            manifestCache.refresh()

            val connector = AndroidServiceConnector(context)
            val pins = StoreModelPinSource(store = store, manifests = manifestCache::get)
            val llamaCppEngine =
                LlamaCppEngine(
                    connector = connector,
                    pins = pins,
                    spillDir = context.cacheDir,
                    sessionEpoch = sessionEpoch,
                    config = config,
                )
            val managed =
                ManagedInferenceEngine(
                    delegate = llamaCppEngine,
                    defaultModel = { defaultModelOf(registry) },
                )

            val manager =
                ModelManager(
                    registry = registry,
                    store = store,
                    modelInspector = ModelInspector { binding -> llamaCppEngine.inspect(binding) },
                    pickedFileReader = ContentResolverPickedFileReader(context.contentResolver),
                    // No bundled default model ships yet (assets/models/ is
                    // empty) — every lookup degrades to "no such asset"
                    // rather than throwing, so `ImportSource.Bundled` is
                    // simply unreachable until a default model ships.
                    bundledSource =
                        ModelBytesSource { file ->
                            try {
                                context.assets.open("$BUNDLED_ASSET_DIR/${file.file}")
                            } catch (e: IOException) {
                                null
                            }
                        },
                    freeBytes = { context.filesDir.usableSpace },
                    isLoaded = { id ->
                        val status = managed.status.value
                        status.modelId == id &&
                            status.state != EngineState.UNLOADED &&
                            status.state != EngineState.ERROR
                    },
                    config = config,
                )

            val contextBudget = ContextBudget(tokenCounter = llamaCppEngine, config = config)
            val retrievalService =
                RetrievalServiceImpl(index = indexStore, repository = vaultRepository, embedder = null)
            val promptAssembler = PromptAssemblerImpl()

            val sendPipeline =
                SendPipeline(
                    vaultRepository = vaultRepository,
                    retrievalService = retrievalService,
                    promptAssembler = promptAssembler,
                    engine = managed,
                    personaProvider = personaProvider,
                    budgetFor = contextBudget::computeBudget,
                    countTokens = syncCountTokens(contextBudget),
                    samplingParams = { SamplingParams() },
                )

            return ModelServices(
                store = store,
                registry = registry,
                manager = manager,
                engine = managed,
                engineStatus = managed.status,
                sendPipeline = sendPipeline,
                manifestCache = manifestCache,
                pushOnSessionUnlocked = llamaCppEngine::onSessionUnlocked,
                pushOnSessionLocking = llamaCppEngine::onSessionLocking,
                pushOnSessionLocked = llamaCppEngine::onSessionLocked,
            )
        }

        /** `ModelId?` of the registry's current default, resolved to its full [app.skein.core.model.Model]. */
        private suspend fun defaultModelOf(registry: ModelRegistry) =
            registry.default()?.let { registry.get(it)?.model }

        /**
         * See file header. Reconstructs one [StoredModel] per row — MAIN
         * file only, matching [ManifestCache]'s own limit (no import path
         * this bead builds ever attaches a companion). A row whose file no
         * longer exists on disk is skipped rather than failing the whole
         * session open — `ModelManager.reconcilePaths` (skein-cyq) is the
         * sanctioned repair path for that case, not this constructor.
         */
        private suspend fun rehydrate(
            store: ImmutableModelStore,
            registry: ModelRegistry,
        ) {
            for (record in registry.list()) {
                val model = record.model
                val mainPath = File(model.path)
                if (!mainPath.isFile) continue
                store.register(
                    StoredModel(
                        id = model.id,
                        directory = mainPath.parentFile ?: continue,
                        files =
                            mapOf(
                                ModelFileRole.MAIN to
                                    StoredFile(
                                        role = ModelFileRole.MAIN,
                                        path = mainPath,
                                        sha256 = model.sha256,
                                        blake3 = record.blake3 ?: "",
                                        sizeBytes = model.sizeBytes,
                                    ),
                            ),
                        permissionEnforcement = PermissionEnforcement.POSIX,
                    ),
                )
            }
        }

        private const val MODELS_DIR_NAME = "models"
        private const val BUNDLED_ASSET_DIR = "models"
    }
}

/**
 * Bridges `ContextBudget.countTokens` (suspend) to `SendPipeline.countTokens`
 * (sync — `PromptAssembler.assemble`'s locked, synchronous contract). Rung
 * chosen, per skein-whg8's own "pre-warmed cache or a bounded blocking call"
 * instruction: a bounded blocking call. [ContextBudget] already memoizes by
 * content hash, so every history turn after its first counting in a
 * conversation is a cache hit and never blocks; only genuinely new text pays
 * the round trip. On a timeout (the isolated-service Binder call taking
 * longer than [timeoutMillis] — a cold `:inference` process start, say) this
 * falls back to a coarse characters/4 estimate rather than hanging
 * indefinitely — an estimate is the wrong token count for one turn's budget
 * math, never a crash or a frozen composer. Tested in `CountTokensBridgeTest`
 * (both the fast path and the timeout fallback).
 */
public fun syncCountTokens(
    contextBudget: ContextBudget,
    timeoutMillis: Long = COUNT_TOKENS_TIMEOUT_MILLIS,
): (String) -> Int =
    { text ->
        runBlocking {
            withTimeoutOrNull(timeoutMillis) { contextBudget.countTokens(text) } ?: estimateTokens(text)
        }
    }

/** Coarse fallback only reached on a [syncCountTokens] timeout. */
internal fun estimateTokens(text: String): Int = if (text.isEmpty()) 0 else (text.length / CHARS_PER_TOKEN_ESTIMATE) + 1

public const val COUNT_TOKENS_TIMEOUT_MILLIS: Long = 3_000L
private const val CHARS_PER_TOKEN_ESTIMATE = 4
