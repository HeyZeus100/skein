// skein-nxk (E4.I3, coordinator decision skein-hiwb): the pure, path-optional
// binding vocabulary `ModelVerifier` is typed in.
//
// WHY THIS TYPE EXISTS
// ============================================================================
//
// `ModelVerifier` has two callers and they hold different things:
//
//   * `:app` / `:core:inference` holds an `ImmutableModelStore` and a parsed
//     manifest, so it has a store-side `ManifestBinding` — absolute
//     `java.io.File` paths, plus a BLAKE3 for every file that either the
//     manifest declared or import computed.
//   * `:inference-service` holds neither. It runs `isolatedProcess=true`, it
//     cannot open `:app`'s files by path at all, and what it is handed over
//     Binder is a `app.skein.ipc.ManifestBinding` whose
//     `ManifestFileRef` carries `role`, `fd`, `expectedSha256` and
//     `expectedSizeBytes` — no path and **no BLAKE3**.
//
// So both optional fields below are load-bearing, not convenience:
//
//   [VerifyFile.path]           null service-side; the verifier then reads
//                               only through the channel it was given, which
//                               is the anti-swap rule `PinnedModelFile`'s
//                               header states.
//   [VerifyFile.expectedBlake3] null service-side. The post-mmap gate then
//                               compares the mapped bytes against the BLAKE3
//                               that the *pre-mmap pass over the same
//                               descriptor* observed, computed in the same
//                               single read. That still detects the §2.4
//                               in-place write — the two digests are taken at
//                               two different times over two different views
//                               of the file, which is the entire mechanism —
//                               it simply cannot also detect a file that was
//                               already wrong before the load began. That case
//                               is what `expectedSha256` is for, and it is
//                               checked either way.

package app.skein.core.verify

import java.io.File

/**
 * One file a load is allowed to open, with whatever expectations the caller
 * could supply.
 *
 * @param expectedSha256 64 lowercase hex characters; the pre-mmap gate. Always
 *   present — a file with no declared SHA-256 is not bindable (§2.2 rule 3).
 * @param expectedSizeBytes cheap pre-check before hashing, and resource planning.
 * @param expectedBlake3 the post-mmap expectation, or null when the caller has
 *   none. See this file's header for what the verifier does with null.
 * @param path the store path, for the app-side loader that legitimately reads a
 *   companion off disk. Null whenever the file arrives as a descriptor.
 */
data class VerifyFile(
    val role: ModelFileRole,
    val expectedSha256: String,
    val expectedSizeBytes: Long,
    val expectedBlake3: String? = null,
    val path: File? = null,
)

/**
 * The complete set of files one load may touch.
 *
 * Construction enforces the two structural rules the verifier would otherwise
 * have to re-check on every pass: exactly one file per role, and a `main`
 * present. Both are programming errors rather than verification refusals — a
 * caller that assembled a binding with two `tokenizer` entries has a bug, and
 * silently hashing one of them would hide it.
 */
class VerifyBinding(
    val files: List<VerifyFile>,
) {
    init {
        require(files.isNotEmpty()) { "a verify binding covers at least the main file" }
        val roles = files.map { it.role }
        require(roles.size == roles.toSet().size) { "a verify binding carries at most one file per role" }
        require(roles.contains(ModelFileRole.MAIN)) { "a verify binding must cover the main file" }
    }

    /** The mmap'd model itself. */
    val main: VerifyFile get() = files.first { it.role == ModelFileRole.MAIN }

    /** Companions only, in binding order. */
    val companions: List<VerifyFile> get() = files.filter { it.role != ModelFileRole.MAIN }

    fun byRole(role: ModelFileRole): VerifyFile? = files.firstOrNull { it.role == role }

    /** Total declared size of every file in the binding — the denominator for a verification progress bar. */
    val totalSizeBytes: Long get() = files.sumOf { it.expectedSizeBytes }

    override fun toString(): String = "VerifyBinding(files=${files.map { it.role.wire }})"
}
