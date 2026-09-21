// skein-st1r (POST_REVIEW_RESOLUTIONS §2.2, review issue 3): origin trust,
// kept deliberately outside the digest gate.
//
// THIS IS NOT PART OF THE DIGEST GATE.
//
// The review's third finding was that "digest matches manifest" and "manifest
// origin is trusted" were being conflated. They are two different properties
// with two different failure modes:
//
//   content trust  — do the bytes on disk equal the bytes the manifest
//                    asserts? Answered by SHA-256 before mmap and BLAKE3-256
//                    after mmap, in `ModelVerifier`. This is the HARD gate:
//                    a failure refuses the load, always, unconditionally.
//
//   origin trust   — did someone we trust sign this manifest? Answered here,
//                    by an offline sigstore bundle check against the pinned
//                    trust root (`E3.I6`, `skein-2zag`). This is a SOFT gate:
//                    its result is a badge in the model-manager UI and an
//                    input to policy ("the default-model slot requires
//                    Verified"), never a precondition inside `ModelVerifier`.
//
// The separation is enforced structurally rather than by convention:
// `ModelVerifier` and `ImmutableModelStore` do not reference this file, and
// `AttestationStatus` never appears in `ModelVerification`. A degraded or
// absent attestation therefore cannot weaken the digest gate, and a Verified
// attestation cannot excuse a digest mismatch — which is precisely what §2.4's
// `AttestationDegradationTest` asserts.
//
// Implementation status: interface only. The sigstore-backed implementation
// lands with `E3.I6`; `sigstore-java` is already pinned in the version
// catalog for it. Until then `ManifestAttestation.Unavailable` is the
// production binding and every model shows the "unattested" badge.

package app.skein.core.inference.models

/** Origin-trust verdict for a manifest. Surfaced in the UI; never consulted by the digest gate. */
enum class AttestationStatus(
    val wire: String,
) {
    /** Bundle verified offline against the pinned trust root. */
    VERIFIED("verified"),

    /** A bundle was present and did not verify. Suspicious, but not a digest failure. */
    FAILED("failed"),

    /** The bundle verified structurally but the trust root or certificate has expired. */
    EXPIRED("expired"),

    /** No bundle was supplied, or no verifier is wired up yet. The default. */
    UNAVAILABLE("unavailable"),
}

/** What an attestation covers, per §2.3's `attestation.covers`. */
enum class AttestationCoverage(
    val wire: String,
) {
    MAIN("main"),
    COMPANIONS("companions"),
    ALL("all"),
}

/** An attestation verdict plus what it claims to cover. */
data class Attestation(
    val status: AttestationStatus,
    val coverage: Set<AttestationCoverage> = setOf(AttestationCoverage.MAIN),
) {
    companion object {
        /** The verdict for a model nobody has attested. */
        val Unattested: Attestation = Attestation(AttestationStatus.UNAVAILABLE, emptySet())
    }
}

/**
 * Verifies the *origin* of a manifest. Stub for `E3.I6`'s sigstore
 * implementation.
 *
 * Implementations must be offline-only (the app holds no `INTERNET`
 * permission) and must never throw: a verification failure is an
 * [AttestationStatus], because an attestation problem must not be able to
 * abort a load that the digest gate already approved.
 */
fun interface ManifestAttestation {
    /**
     * @param manifest the manifest whose origin is in question.
     * @param bundle the raw sigstore bundle bytes, or null when none shipped.
     */
    fun verify(
        manifest: ModelManifest,
        bundle: ByteArray?,
    ): Attestation

    companion object {
        /** Production binding until `E3.I6` lands: everything is unattested. */
        val Unavailable: ManifestAttestation = ManifestAttestation { _, _ -> Attestation.Unattested }
    }
}
