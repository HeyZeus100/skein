// skein-28wm (POST_REVIEW_RESOLUTIONS.md §2.3, coordinator note 2026-09-21
// folding in skein-6ef5's requirement): the converter between the two
// `ManifestBinding` types this codebase now has.
//
// WHICH TYPE IS WHICH
// ============================================================================
//
//   `app.skein.core.inference.models.ManifestBinding` (STORE-SIDE, `:core:inference`,
//   landed by `skein-st1r`) — a pure-JVM, PATH-carrying value produced by
//   `ManifestBinding.bind(manifest, stored)`. It never opens a file: it only
//   proves *which* files an `ImmutableModelStore`-backed model directory
//   actually holds, matched against the manifest, with each `BoundFile`
//   carrying an absolute `java.io.File` path plus its expected SHA-256/size.
//
//   `us.aherrera.skein.ipc.ManifestBinding` (WIRE-SIDE, `:core:ipc`, landed by
//   `skein-mfw`) — the Binder `Parcelable` carrying the SAME information
//   across a process boundary: `ManifestFileRef.fd` is an opened, read-only
//   `ParcelFileDescriptor` in place of `BoundFile.path`, because
//   `:inference-service` / `:embedder-service` run `isolatedProcess=true` and
//   cannot open `:app`'s app-private files by path at all — only a fd handed
//   to them over Binder works.
//
// THIS FILE MAPS STORE-SIDE -> WIRE ([WireBindings.toWire]), i.e.
// `app.skein.core.inference.models.ManifestBinding` ->
// `us.aherrera.skein.ipc.ManifestBinding`. `skein-cqiu`'s closing decision:
// the wire type "is a straight mapping from it, not a second parser" — every
// `BoundFile` becomes exactly one `ManifestFileRef`; nothing here re-derives a
// digest, re-reads the manifest JSON, or re-runs `ManifestBinding.bind`'s
// coverage check. The reverse direction (wire -> store-side) is not
// implemented: E4.I3's service-side load flow
// (POST_REVIEW_RESOLUTIONS.md §2.3's pseudocode) reads the wire
// `ManifestBinding` directly against the fds it was handed and has no
// `StoredModel` of its own to reconstruct a store-side value against.
//
// FD OWNERSHIP FOR THIS SPECIFIC HANDOFF
// ============================================================================
//
// POST_REVIEW_RESOLUTIONS.md §3.2 rule 3 ("the service OWNS every fd it
// receives and MUST close() after processing, success or failure") is stated
// for `IInferenceService`/`IEmbedderService` generally; restated here for the
// concrete fds [toWire] opens:
//
//   * [toWire] itself opens one [android.os.ParcelFileDescriptor] per
//     [BoundFile] (plus one more when an attestation bundle is
//     supplied) and hands them back inside the returned wire
//     [us.aherrera.skein.ipc.ManifestBinding]. If opening file N of M fails,
//     every descriptor already opened for files 1..N-1 (and the attestation
//     fd, if it was opened first) is closed before the `IOException`
//     propagates — `WireBindingsTest`'s
//     `a failed conversion closes every descriptor already opened` proves
//     this against a real permission failure, not a mock. A failed
//     conversion must never leak a descriptor.
//   * On success, the CLIENT — the future `:app` / `:core:inference` code
//     that builds a `LoadRequest`/`EmbedderLoadRequest` around this binding
//     and calls `IInferenceService.load` / `IEmbedderService.load` — does
//     NOT close these fds itself, and must not: AIDL's generated stubs
//     marshal every `in ParcelFileDescriptor` with
//     `Parcelable.PARCELABLE_WRITE_RETURN_VALUE`, which closes the sender's
//     local copy as a side effect of writing the transaction (exactly what
//     `:core:ipc`'s `ParcelRoundTripTest` and this file's own
//     `WireBindingsTest` exercise with a real `Parcel`). By the time `load()`
//     returns, the client's copies are already gone; there is nothing left
//     to close.
//   * The SERVICE that receives the call owns every fd in the
//     Binder-side `ManifestBinding` / `AttestationRefParcel` it is handed and
//     MUST `close()` each one after processing (§3.2 rule 3) — except the
//     main model fd it intends to keep mapped for the load's lifetime, which
//     it `dup()`s first (POST_REVIEW_RESOLUTIONS.md §2.3's `dupFd(mainFd)`
//     pseudocode); the original it received is closed like every other fd.
//
// WHERE THE ATTESTATION BUNDLE FILE LIVES IS DELIBERATELY NOT DECIDED HERE
// ============================================================================
//
// `ModelManifest.attestation` (a `ManifestAttestationRef`) names the bundle
// only by a bare file name (`bundle_file`); it says nothing about a
// directory. `ImmutableModelStore.import` never writes that file anywhere —
// it only ever stages `manifest.files` (`main` + `companions`) into
// `<id>/` — and `ManifestBinding.bind`'s `uncoveredFile` check would refuse a
// binding if a store directory ever held a file the manifest's `companions`
// list didn't cover, so the bundle structurally cannot be resolved by
// looking inside the bound model's own directory even once something does
// start writing it somewhere (`MODEL_STORE.md` §6 lists the sigstore
// implementation, `E3.I6`, as not yet wired). Given that, [toWire] takes the
// already-resolved bundle [java.io.File] as an explicit parameter rather than
// deriving one, and skips attestation (returns a null
// [us.aherrera.skein.ipc.AttestationRefParcel], logged, never thrown) when
// [attestation] is non-null but no [attestationBundle] was supplied — origin
// trust is `POST_REVIEW_RESOLUTIONS.md` §2.5's SOFT gate, so its absence must
// never block a load the digest gate would otherwise allow.

package app.skein.core.inference.models

import android.os.ParcelFileDescriptor
import app.skein.core.model.SkeinLog
import us.aherrera.skein.ipc.AttestationRefParcel
import us.aherrera.skein.ipc.ManifestFileRef
import java.io.File
import java.io.IOException
import us.aherrera.skein.ipc.ManifestBinding as WireManifestBinding

private const val TAG = "WireBindings"

/** Converts the store-side, checked [ManifestBinding] into the wire type Binder carries. See this file's header. */
object WireBindings {
    /**
     * Opens one read-only [ParcelFileDescriptor] per file [bound] resolved and returns the
     * `:core:ipc` [WireManifestBinding] carrying them.
     *
     * @param bound the real, already-checked store-side binding
     *   (`ManifestBinding.bind(manifest, stored)`'s success case). Every
     *   [BoundFile] in it becomes one [ManifestFileRef]: role, fd, expected
     *   SHA-256, expected size — verbatim, per `skein-cqiu`.
     * @param attestation [app.skein.core.inference.models.ModelManifest.attestation], or null when the
     *   manifest declares none. `ManifestAttestationRef.covers` already
     *   defaults to `{MAIN}` at parse time, so "covers defaults to `[main]`
     *   when absent" is automatic here — this function only maps the
     *   vocabulary, it does not re-decide the default.
     * @param attestationBundle the sigstore bundle file [attestation] names,
     *   already resolved by the caller (see this file's header for why
     *   resolving it is not this function's job). Ignored when [attestation]
     *   is null; when [attestation] is non-null but this is null, the
     *   resulting binding simply carries no attestation, because origin trust
     *   is a soft gate.
     * @throws IOException if opening any file's descriptor fails. Every
     *   descriptor already opened for this call is closed first.
     */
    fun toWire(
        bound: ManifestBinding,
        attestation: ManifestAttestationRef? = null,
        attestationBundle: File? = null,
    ): WireManifestBinding = buildWire(bound, attestation, attestationBundle)

    /**
     * The same conversion as [toWire], with the list of descriptors opened so
     * far exposed via [opened] — including on failure, once the caller has
     * caught the thrown [IOException] — so a test can assert the cleanup rule
     * in this file's header without needing to mock `ParcelFileDescriptor`.
     * `internal`: this seam exists for `WireBindingsTest`, not for callers
     * outside this module.
     */
    internal fun buildWire(
        bound: ManifestBinding,
        attestation: ManifestAttestationRef? = null,
        attestationBundle: File? = null,
        opened: MutableList<ParcelFileDescriptor> = mutableListOf(),
    ): WireManifestBinding {
        try {
            val files = bound.files.map { boundFile -> toFileRef(boundFile, opened) }
            val attestationRef = toAttestationRef(attestation, attestationBundle, opened)
            return WireManifestBinding(
                manifestId = bound.manifestId,
                manifestVersion = bound.manifestVersion,
                files = files,
                attestation = attestationRef,
            )
        } catch (e: IOException) {
            closeAll(opened)
            throw e
        }
    }

    private fun toFileRef(
        boundFile: BoundFile,
        opened: MutableList<ParcelFileDescriptor>,
    ): ManifestFileRef {
        val fd = openReadOnly(boundFile.path, opened)
        return ManifestFileRef(
            role = boundFile.role.wire,
            fd = fd,
            expectedSha256 = boundFile.expectedSha256,
            expectedSizeBytes = boundFile.expectedSizeBytes,
        )
    }

    private fun toAttestationRef(
        attestation: ManifestAttestationRef?,
        bundle: File?,
        opened: MutableList<ParcelFileDescriptor>,
    ): AttestationRefParcel? {
        if (attestation == null) return null
        if (bundle == null) {
            // Soft gate (POST_REVIEW_RESOLUTIONS.md §2.5): a manifest can
            // declare an attestation the caller has not (yet) resolved a
            // bundle file for. That must never fail the load, only the
            // attestation.
            SkeinLog.w(TAG, "attestation declared but no bundle file resolved; loading without attestation")
            return null
        }
        val fd = openReadOnly(bundle, opened)
        return AttestationRefParcel(
            bundleFd = fd,
            covers = attestation.covers.map { it.wire },
        )
    }

    private fun openReadOnly(
        file: File,
        opened: MutableList<ParcelFileDescriptor>,
    ): ParcelFileDescriptor {
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        opened += fd
        return fd
    }

    private fun closeAll(fds: List<ParcelFileDescriptor>) {
        fds.forEach { fd -> runCatching { fd.close() } }
    }
}
