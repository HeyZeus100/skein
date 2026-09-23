// skein-1uw (E4.I4) — how `LlamaCppEngine.load(model)` gets from a
// `app.skein.core.model.Model` row to descriptors it may hand across Binder.
//
// THE GAP THIS CLOSES
// ============================================================================
//
// The `InferenceEngine` contract speaks `Model` (an id, a path, a digest, a
// capability set — `:core:model`). The `:inference` service speaks
// `app.skein.ipc.ManifestBinding` (one read-only descriptor per file, each with
// the digest it must hash to — POST_REVIEW_RESOLUTIONS.md §2.3, "the service
// hashes THIS fd and mmaps THIS fd, never re-opens the path").
//
// Between the two sit two things the engine must not own:
//
//   1. the immutable store's shared READ LOCK on the main file, which must be
//      held for as long as the model is loaded (`ImmutableModelStore.open`),
//      and
//   2. the model's MANIFEST, which is what makes the binding checkable in both
//      directions (`ManifestBinding.bind`). The manifest deliberately does not
//      live in the store directory — a file beside the model that the manifest
//      did not cover is exactly what `ManifestBinding` refuses — so it is held
//      by the registry (`skein-cyq`'s `ModelManager`/`ModelRegistry`), not by
//      anything on this side.
//
// [ModelPinSource] is therefore a one-method seam: "give me the pinned,
// manifest-checked form of this model". [StoreModelPinSource] is the
// implementation over the two collaborators that already exist, so the engine
// is usable from the wiring bead (`skein-whg8`) without waiting on anything
// else; a `ModelManager` that already holds both can implement the seam
// directly instead.
//
// NOT a second verification gate. Nothing here hashes bytes: the digests
// travel as EXPECTATIONS and the isolated process is the only place that
// checks them against the mapped pages (§2.3 — the whole point of inspecting
// and loading over a descriptor inside `:inference` is that a parser exploit
// there has nothing to steal). The one comparison this file's caller does make
// is registry-vs-manifest, which touches no model bytes at all; see
// `LlamaCppEngine.load`.

package app.skein.core.inference.engine

import app.skein.core.inference.models.BindResult
import app.skein.core.inference.models.ImmutableModelStore
import app.skein.core.inference.models.ManifestAttestationRef
import app.skein.core.inference.models.ManifestBinding
import app.skein.core.inference.models.ModelHandle
import app.skein.core.inference.models.ModelManifest
import app.skein.core.inference.models.OpenResult
import app.skein.core.model.InferenceException
import app.skein.core.model.Model
import app.skein.core.model.ModelId
import app.skein.core.verify.ModelVerification
import java.io.Closeable
import java.io.File

/**
 * One model held open for loading: the store's shared read lock plus the
 * manifest-checked list of files the load may touch.
 *
 * Closing releases the lock. The engine closes its pin on `unload`, on a failed
 * `load`, and when the service dies — a pin that outlived its load would keep
 * `ModelManager.delete` and re-import refused forever
 * (`ModelVerification.InUse`).
 *
 * @param handle the `ImmutableModelStore` handle whose release drops the lock.
 * @param binding the store-side binding; [app.skein.core.inference.models.WireBindings.toWire]
 *   turns it into descriptors.
 * @param attestation the manifest's sigstore reference, or null. Origin trust
 *   is a soft gate (POST_REVIEW_RESOLUTIONS.md §2.5) and never blocks a load.
 * @param attestationBundle the resolved bundle file for [attestation], or null.
 */
public class ModelPin(
    public val handle: ModelHandle,
    public val binding: ManifestBinding,
    public val attestation: ManifestAttestationRef? = null,
    public val attestationBundle: File? = null,
) : Closeable {
    override fun close() {
        handle.release()
    }
}

/**
 * Supplies the pinned, manifest-checked form of a [Model].
 *
 * A `fun interface` so the wiring bead can pass a lambda over whatever holds
 * the manifests (`skein-cyq`'s registry) without a class of its own.
 *
 * Implementations never throw: a refusal is `Result.failure` carrying the
 * [InferenceException] the engine will hand back from `load`, because the
 * contract says `load` never throws (spec §4.1).
 */
public fun interface ModelPinSource {
    public fun pin(model: Model): Result<ModelPin>
}

/**
 * [ModelPinSource] over the immutable store plus a manifest lookup.
 *
 * @param store the store the model was imported into (`context.filesDir/models`
 *   in production).
 * @param manifests resolves a model id to the manifest recorded for it at
 *   import. Returning null means "this id is not a model this app imported",
 *   which is [InferenceException.InvalidModel] rather than a crash.
 */
public class StoreModelPinSource(
    private val store: ImmutableModelStore,
    private val manifests: (ModelId) -> ModelManifest?,
) : ModelPinSource {
    override fun pin(model: Model): Result<ModelPin> {
        val manifest =
            manifests(model.id)
                ?: return Result.failure(InferenceException.InvalidModel("no manifest recorded for this model"))

        val handle =
            when (val opened = store.open(model.id)) {
                is OpenResult.Opened -> opened.handle
                is OpenResult.Refused -> return Result.failure(StoreRefusals.toException(opened.refusal))
            }

        return when (val bound = ManifestBinding.bind(manifest, handle.model)) {
            is BindResult.Bound ->
                Result.success(
                    ModelPin(
                        handle = handle,
                        binding = bound.binding,
                        attestation = manifest.attestation,
                        // The bundle file is resolved by whoever imported it
                        // (see `WireBindings.toWire`'s KDoc for why resolving
                        // it is not the converter's job either). Until `E3.I6`
                        // lands there is nothing to resolve it from, and a
                        // missing bundle is a soft gate, not a refusal.
                        attestationBundle = null,
                    ),
                )

            is BindResult.Refused -> {
                // The lock must not survive a refused bind.
                handle.release()
                Result.failure(StoreRefusals.toException(bound.refusal))
            }
        }
    }
}

/**
 * `ModelVerification.Refusal` → [InferenceException], app-side.
 *
 * The SERVICE-side twin of this table is
 * `app.skein.inference.service.ServiceErrorMapping.toErrorCode` — and it
 * cannot be shared: `:core:verify` deliberately has no `:core:ipc` edge (a
 * verifier that knew about Binder codes could not be used from the import
 * path, which has no Binder), and `:core:inference` may not depend on
 * `:inference-service`. Rather than inventing a second vocabulary, this table
 * produces the SAME `ErrorCode` for the same refusal and then goes through
 * [app.skein.ipc.ErrorCodes.toException], so an app-side refusal and a
 * service-side one of the same kind are indistinguishable to a caller. If the
 * two tables ever need to become one, the shared home is a new pure-JVM module
 * both can see; that is a consolidation, not a behaviour change.
 */
internal object StoreRefusals {
    fun toException(refusal: ModelVerification.Refusal): InferenceException =
        when (refusal) {
            // Store-side, a digest mismatch can only be the pre-mmap kind:
            // nothing here maps a file. `expected`/`actual` stay empty — this
            // refusal is raised by the import-time record, which does not
            // report the observed digest (spec §9 keeps model bytes and their
            // digests out of an app-side error path).
            is ModelVerification.HashMismatch -> InferenceException.HashMismatch(expected = "", actual = "")
            is ModelVerification.Tampered -> InferenceException.PostMmapHashMismatch(refusal.summary)
            is ModelVerification.InUse, is ModelVerification.AlreadyImported ->
                InferenceException.ModelInUse(refusal.summary)

            is ModelVerification.Cancelled -> InferenceException.Internal(refusal.summary)
            is ModelVerification.CompanionMissing,
            is ModelVerification.FileMissing,
            is ModelVerification.SizeMismatch,
            is ModelVerification.UncoveredFile,
            is ModelVerification.MalformedManifest,
            -> InferenceException.InvalidModel(refusal.summary)

            is ModelVerification.IoFailure -> InferenceException.Internal(refusal.summary)
        }
}
