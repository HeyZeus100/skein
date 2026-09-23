// skein-1uw (E4.I4) — the fixtures the JVM engine suite shares: a fake
// connector whose death callback a test can fire by hand, a real immutable
// store holding a real (tiny, pseudo-random) "model", and the `Model` row that
// names it.
//
// The store is REAL rather than faked on purpose. `load`'s whole job is to get
// from a registry row to descriptors, and the interesting failure modes —
// the read lock, the manifest binding, the registry-vs-manifest digest
// cross-check — only exist if the store and the manifest are the real ones.
// `ModelStoreFixtures` (`skein-st1r`) already builds both; this file reuses
// them rather than inventing a second set.

package app.skein.core.inference.engine

import app.skein.core.inference.models.BindResult
import app.skein.core.inference.models.ImmutableModelStore
import app.skein.core.inference.models.ImportResult
import app.skein.core.inference.models.MAIN_FILE
import app.skein.core.inference.models.MODEL_ID
import app.skein.core.inference.models.ManifestBinding
import app.skein.core.inference.models.defaultFixtureFiles
import app.skein.core.inference.models.parsedManifest
import app.skein.core.inference.models.sourceOf
import app.skein.core.model.Capability
import app.skein.core.model.Model
import app.skein.core.model.ModelFormat
import app.skein.ipc.IInferenceService
import java.io.File

/**
 * A [ServiceConnector] that hands back [service] and remembers the engine's
 * death callback so a test can fire it — the thing a local `Binder` can never
 * do (see [ServiceConnector]'s header).
 */
internal class FakeServiceConnector(
    var service: IInferenceService,
) : ServiceConnector {
    var connects: Int = 0
        private set
    var disconnects: Int = 0
        private set

    /** Set when the connector should refuse to bind at all. */
    var refuse: Boolean = false

    private var onDeath: (() -> Unit)? = null

    override suspend fun connect(onDeath: () -> Unit): IInferenceService {
        if (refuse) {
            throw app.skein.core.model.InferenceException
                .ServiceDied()
        }
        connects += 1
        this.onDeath = onDeath
        return service
    }

    override fun disconnect() {
        disconnects += 1
        onDeath = null
    }

    /** Fires the death callback the engine registered, as `linkToDeath` would. */
    fun killService() {
        val callback = onDeath ?: error("nothing is bound")
        onDeath = null
        callback()
    }
}

/** A store with the fixture model imported, plus everything the engine needs to load it. */
internal class StoreFixture(
    root: File,
) {
    val store: ImmutableModelStore = ImmutableModelStore(root)
    private val files = defaultFixtureFiles()
    private val manifest = parsedManifest(files)

    val mainSha256: String = files.first { it.name == MAIN_FILE }.manifestSha256

    init {
        check(store.import(manifest, sourceOf(files)) is ImportResult.Imported) { "fixture import failed" }
    }

    /** [ModelPinSource] over this store — the real [StoreModelPinSource], not a fake. */
    val pins: ModelPinSource = StoreModelPinSource(store) { id -> manifest.takeIf { it.id == id } }

    /** The store-side binding, for `inspect` (which takes one directly). */
    fun binding(): ManifestBinding {
        val stored = checkNotNull(store.stored(MODEL_ID))
        return (ManifestBinding.bind(manifest, stored) as BindResult.Bound).binding
    }

    /** The registry row the engine is handed. TEXT only — the contract suite requires no EMBEDDING. */
    fun model(): Model =
        Model(
            id = MODEL_ID,
            name = "Store fixture model",
            path = File(File(store.stored(MODEL_ID)!!.directory, MAIN_FILE).path).path,
            sha256 = mainSha256,
            format = ModelFormat.GGUF,
            capabilities = setOf(Capability.TEXT),
            sizeBytes = 4_096L,
            contextLength = 2_048,
        )

    /** The same model, declared embedding-capable — for `embed`'s capability gate. */
    fun embeddingModel(): Model = model().copy(capabilities = setOf(Capability.TEXT, Capability.EMBEDDING))
}
