// skein-nxk (E4.I3, coordinator decision skein-hiwb): the bridge between the
// store-side [ManifestBinding] and the verifier's own [VerifyBinding].
//
// `ModelVerifier` moved to the pure-JVM `:core:verify` so `:inference-service`
// — an `isolatedProcess=true` module whose isolation allowlist admits no
// Android library — can run it. `ManifestBinding` did not move: it is produced
// by binding a parsed `ModelManifest` against an `ImmutableModelStore`
// directory, both of which are this module's. So the two are joined here,
// in the module that can see both, by one total function.
//
// Nothing is re-derived: [toVerifyBinding] copies the four fields the gates
// read and the path the app-side companion fallback needs. A store-side
// binding always has a BLAKE3 for every file (the manifest declared it or
// import computed it), which is why the app side never exercises
// `ModelVerifier`'s observed-BLAKE3 fallback and the service always does.

package app.skein.core.inference.models

import app.skein.core.verify.LoadPhaseHook
import app.skein.core.verify.LoadVerification
import app.skein.core.verify.ModelVerification
import app.skein.core.verify.ModelVerifier
import app.skein.core.verify.VerifyBinding
import app.skein.core.verify.VerifyCancellation
import app.skein.core.verify.VerifyFile
import app.skein.core.verify.VerifyProgress

/** The verifier's view of this binding: same files, same expectations, same order. */
fun ManifestBinding.toVerifyBinding(): VerifyBinding =
    VerifyBinding(
        files.map { bound ->
            VerifyFile(
                role = bound.role,
                expectedSha256 = bound.expectedSha256,
                expectedSizeBytes = bound.expectedSizeBytes,
                expectedBlake3 = bound.expectedBlake3,
                path = bound.path,
            )
        },
    )

/**
 * Gate 1 over the channel [handle]'s shared read lock is held on.
 *
 * This is the overload that used to live on `ModelVerifier` itself. It moved
 * here with `ModelHandle`, which is `ImmutableModelStore`'s type and stays in
 * this module.
 */
fun ModelVerifier.verifyBeforeMmap(
    handle: ModelHandle,
    binding: ManifestBinding,
    cancellation: VerifyCancellation = VerifyCancellation.Never,
    progress: VerifyProgress = VerifyProgress.None,
): ModelVerification = verifyBeforeMmap(binding.toVerifyBinding(), handle.mainChannel, cancellation, progress)

/**
 * The full §2 load gate over the channel [handle]'s shared read lock is held
 * on — verify, map, re-verify.
 *
 * Same move as [verifyBeforeMmap]: `ModelVerifier.verifyForLoad(handle, …)`
 * could not follow the verifier into `:core:verify` without dragging
 * `ImmutableModelStore` with it.
 */
fun ModelVerifier.verifyForLoad(
    handle: ModelHandle,
    binding: ManifestBinding,
    hook: LoadPhaseHook = LoadPhaseHook.None,
    cancellation: VerifyCancellation = VerifyCancellation.Never,
    progress: VerifyProgress = VerifyProgress.None,
): LoadVerification = verifyForLoad(handle.mainChannel, binding.toVerifyBinding(), hook, cancellation, progress)
