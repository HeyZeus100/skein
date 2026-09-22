// skein-st1r (POST_REVIEW_RESOLUTIONS §2): the typed outcome vocabulary
// shared by the manifest parser, the immutable store and the verifier.
//
// Every refusal is a value, never an exception and never a boolean: §2's whole
// point is that "the load did not happen" must be distinguishable into
// "the manifest does not cover a file we would open" (`CompanionMissing`),
// "the bytes on disk are not the bytes the manifest asserts" (`HashMismatch`),
// "the bytes changed between the pre-mmap check and the mapping"
// (`Tampered`) and "somebody is holding this model open" (`InUse`). Collapsing
// those into one failure loses the forensics the review asked for.
//
// Logging discipline (spec §9): a refusal's [ModelVerification.Refusal.summary]
// names roles, digest algorithms and model ids only. Filesystem paths, the
// expected digest and the observed digest are deliberately absent — they are
// carried in the typed fields for the caller that legitimately needs them
// (tests, the model-manager UI) and never formatted into a log line.

package app.skein.core.inference.models

/** Which of the two digests in §2's dual-hash discipline a result refers to. */
enum class DigestAlgorithm(
    val wire: String,
) {
    /** The pre-mmap gate: streaming SHA-256 over the exact bytes of the fd. */
    SHA256("sha256"),

    /** The post-mmap gate: BLAKE3-256 over the mapped region. */
    BLAKE3("blake3"),
}

/** Outcome of a §2 verification step. */
sealed interface ModelVerification {
    /** Every file in the binding matched every digest the manifest declares for it. */
    data object Verified : ModelVerification

    /** Something refused the load. Never thrown — returned. */
    sealed interface Refusal : ModelVerification {
        /** Log-safe one-liner: roles and algorithms only, never paths or digests. */
        val summary: String
    }

    /**
     * A file's content digest did not match the manifest.
     *
     * [algorithm] distinguishes the two gates; a [DigestAlgorithm.SHA256]
     * mismatch is the pre-mmap (or import-time) refusal. A post-mmap BLAKE3
     * mismatch is reported as [Tampered] instead, because by then the same
     * bytes have already passed SHA-256 — see that type's doc.
     */
    data class HashMismatch(
        val role: ModelFileRole,
        val algorithm: DigestAlgorithm,
    ) : Refusal {
        override val summary: String get() = "hash mismatch role=${role.wire} digest=${algorithm.wire}"
    }

    /**
     * The manifest does not cover a file the loader would open, or covers it
     * without a usable sha256.
     *
     * This is the review's "verifying only the .gguf while trusting an
     * adjacent tokenizer.json" hole, made into a hard refusal.
     */
    data class CompanionMissing(
        val role: ModelFileRole?,
        val file: String,
    ) : Refusal {
        override val summary: String get() = "companion not covered by manifest role=${role?.wire ?: "unknown"}"
    }

    /**
     * A file exists inside the model's store directory that no manifest entry
     * covers. The store refuses rather than ignoring it: an uncovered file in
     * the directory the loader reads from is exactly the sidecar-substitution
     * path §2 closes.
     */
    data class UncoveredFile(
        val file: String,
    ) : Refusal {
        override val summary: String get() = "store directory holds a file no manifest entry covers"
    }

    /**
     * The model id is open (its shared [java.nio.channels.FileLock] is held),
     * so it can be neither re-imported over nor deleted. §2.2: re-import must
     * use a fresh id; delete-then-import waits for `unload`.
     */
    data class InUse(
        val modelId: String,
    ) : Refusal {
        override val summary: String get() = "model is loaded; re-import or delete refused"
    }

    /** The id already exists in the store. §2.2 forbids re-import over an existing id even when idle. */
    data class AlreadyImported(
        val modelId: String,
    ) : Refusal {
        override val summary: String get() = "model id already present in the immutable store"
    }

    /**
     * The post-mmap BLAKE3-256 over the mapped region disagrees with the
     * digest recorded at import, although the pre-mmap SHA-256 over the same
     * path passed moments earlier.
     *
     * This is the review's headline attack — an actor with write access
     * modifies the file in place after the hash check and before/while the
     * mapping is read — and the only outcome that proves the dual-hash
     * discipline earned its cost. Maps to `ErrorCode.HASH_MISMATCH_POST_MMAP`.
     */
    data class Tampered(
        val role: ModelFileRole,
    ) : Refusal {
        override val summary: String get() = "post-mmap digest mismatch; bytes changed role=${role.wire}"
    }

    /**
     * The caller cancelled the verification while a digest was streaming — in
     * practice an `unload` that arrived during the ~10 s a 2.5 GB model takes
     * to hash (plan `E3.I5`).
     *
     * Deliberately not a [HashMismatch]: a cancelled pass proves nothing about
     * the bytes in either direction, and reporting "this model is tampered"
     * because the user backgrounded the app would be a false alarm the threat
     * model (`E3.I12`) would then have to answer for. The natural wire code is
     * `ErrorCode.CANCELLED`, not one of the hash-mismatch codes — the mapping
     * itself belongs to the service (`skein-nxk`), since `:core:inference`
     * must not depend on `:core:ipc`.
     *
     * [role] is the file that was being hashed when the signal was seen, or
     * null when the cancellation was observed outside a per-file pass.
     */
    data class Cancelled(
        val role: ModelFileRole?,
    ) : Refusal {
        override val summary: String get() = "verification cancelled role=${role?.wire ?: "n/a"}"
    }

    /** A manifest entry names a file that is not present in the store directory. */
    data class FileMissing(
        val role: ModelFileRole,
    ) : Refusal {
        override val summary: String get() = "manifest file absent from the store role=${role.wire}"
    }

    /** A file's length differs from the manifest's `size_bytes`. Cheap pre-check before hashing. */
    data class SizeMismatch(
        val role: ModelFileRole,
    ) : Refusal {
        override val summary: String get() = "size mismatch role=${role.wire}"
    }

    /** The manifest itself did not parse, or violated the v2 schema rules in §2.3. */
    data class MalformedManifest(
        val reason: String,
    ) : Refusal {
        override val summary: String get() = "malformed manifest: $reason"
    }

    /** An I/O failure while reading, writing or mapping. Carries no path. */
    data class IoFailure(
        val role: ModelFileRole?,
        val kind: String,
    ) : Refusal {
        override val summary: String get() = "io failure role=${role?.wire ?: "n/a"} kind=$kind"
    }
}
