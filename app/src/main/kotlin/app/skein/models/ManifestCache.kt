// skein-whg8 — the app-side lookup `StoreModelPinSource` needs
// (`core/inference/.../engine/ModelPin.kt`: `manifests: (ModelId) ->
// ModelManifest?`) and, one level below it, the store's own in-memory
// registry rehydration (`ImmutableModelStore`'s KDoc: "`:app` rehydrates it
// from the database at start-up and the store itself never persists
// metadata into the model directory").
//
// Both problems have the same shape and the same cause: `ModelRegistry`
// persists `ModelRecord`/`Model` rows (the `:core:model` contract), never
// the `ModelManifest` document `ModelManager` synthesised at import time —
// nothing in this tree writes a manifest back to disk once import finishes
// (`core/inference/.../models/ModelManifest.kt`'s own header: the manifest
// is what makes a binding checkable, not a persisted artifact). So a
// manifest is RECONSTRUCTED here from the row, not looked up.
//
// This is exact for what this bead's own `/import model` command ever
// produces: `ModelManager.importPicked` never attaches a companion
// (`ModelManager`'s class header, "Picked/HubOffer never carry a
// companion — no multi-file SAF picker precedent"), so a reconstructed
// manifest with `companions = emptyList()` matches every row this bead's
// import path can create. A future `Bundled`/`HubOffer` import WITH
// companions would need this cache to carry companion sizes too (`Model`'s
// own `CompanionFile` has no `sizeBytes` field to reconstruct from) — noted
// here rather than guessed at.
package app.skein.models

import app.skein.core.inference.models.ManifestFile
import app.skein.core.inference.models.ManifestLicense
import app.skein.core.inference.models.ModelManifest
import app.skein.core.model.ModelId
import app.skein.core.model.ModelRecord
import app.skein.core.model.ModelRegistry
import app.skein.core.verify.ModelFileRole
import java.util.concurrent.ConcurrentHashMap

/**
 * A synchronous `ModelId -> ModelManifest?` view over [registry], refreshed
 * by [refresh]. Synchronous because [StoreModelPinSource]'s `manifests`
 * parameter — and therefore [ModelPin.pin] — is a plain function, called
 * from inside `LlamaCppEngine.load` without a dispatcher guarantee: a
 * suspend lookup there would mean either blocking whatever thread `load`
 * happens to run on, or making the pin source's contract suspend for every
 * caller. [refresh] does the one suspend read this class ever needs, up
 * front, so [get] never touches [registry] itself.
 *
 * @param registry read once per [refresh] call. The composition root calls
 *   [refresh] when the session opens and after every mutation this bead's
 *   commands make (`/import model` success, `/models`' set-default/delete)
 *   — see `ModelServices`'s own call sites.
 */
public class ManifestCache(
    private val registry: ModelRegistry,
) {
    private val cache = ConcurrentHashMap<ModelId, ModelManifest>()

    /** Re-reads every row from [registry] and rebuilds the cache from scratch. */
    public suspend fun refresh() {
        val fresh = registry.list().associate { record -> record.model.id to record.toManifest() }
        cache.clear()
        cache.putAll(fresh)
    }

    /** The reconstructed manifest for [id], or `null` if [refresh] has never seen this id. */
    public fun get(id: ModelId): ModelManifest? = cache[id]

    public companion object {
        /**
         * Reconstructs the manifest this row's import would have synthesised
         * — see the file header for exactly what this can and cannot
         * recover. [ModelRecord.blake3] is expected non-null for every row
         * this bead's own import path writes (`ImmutableModelStore.import`
         * always computes it during the same streaming pass); a row without
         * one (a foreign or pre-migration row) reconstructs a manifest that
         * simply carries no post-mmap expectation — [ManifestFile.blake3] is
         * optional — rather than a false empty-string digest that would fail
         * every comparison.
         */
        internal fun ModelRecord.toManifest(): ModelManifest {
            val mainFile = model.path.substringAfterLast('/')
            return ModelManifest(
                id = model.id,
                manifestVersion = ModelManifest.SUPPORTED_VERSION,
                name = model.name,
                format = model.format,
                capabilities = model.capabilities,
                license = ManifestLicense(spdx = licenseSpdx ?: "UNKNOWN"),
                main =
                    ManifestFile(
                        role = ModelFileRole.MAIN,
                        file = mainFile,
                        sha256 = model.sha256,
                        sizeBytes = model.sizeBytes,
                        // Nullable in the schema: a row with no recorded
                        // blake3 (never true of a row this bead's own
                        // import path writes) reconstructs a manifest that
                        // carries no post-mmap expectation, never a false
                        // empty-string digest that would fail every check.
                        blake3 = blake3,
                    ),
                // See file header: no import path this bead builds ever
                // attaches a companion.
                companions = emptyList(),
                contextLength = model.contextLength,
            )
        }
    }
}
