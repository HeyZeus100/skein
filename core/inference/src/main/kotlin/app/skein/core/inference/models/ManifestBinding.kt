// skein-st1r (POST_REVIEW_RESOLUTIONS §2.2, rule 3): `ManifestBinding` — the
// object that makes "every file the loader opens is manifest-covered" checkable
// in one place.
//
// The review's second finding: `EmbedderLoadRequest` took each companion as a
// bare descriptor with only the main model's hash guaranteed, so a tampered
// `tokenizer.json` sitting next to a good `.gguf` was used unverified. A
// binding closes that in both directions:
//
//   manifest -> disk : every required manifest entry must exist in the store
//                      with a usable sha256, else `CompanionMissing`.
//   disk -> manifest : every file present in the store directory must be
//                      covered by a manifest entry, else `UncoveredFile`.
//
// The second direction matters as much as the first. Checking only
// manifest -> disk still permits dropping an extra `tokenizer.json` beside a
// fully-verified model and letting a runtime path pick it up.
//
// This is the JVM-side shape of the `ManifestBinding` Parcelable in §2.3;
// when `E0.I16` lands the AIDL contract, `:core:ipc`'s Parcelable carries the
// same fields plus the descriptors, and converts to this type on the service
// side.

package app.skein.core.inference.models

import java.io.File

/** One file the loader is allowed to open, with both expected digests resolved. */
data class BoundFile(
    val role: ModelFileRole,
    val path: File,
    val expectedSha256: String,
    val expectedSizeBytes: Long,
    /**
     * The post-mmap expectation. Never null: it is either declared by the
     * manifest or computed during import, so the post-mmap gate always has
     * something to compare against and can never be skipped "because no
     * BLAKE3 was recorded".
     */
    val expectedBlake3: String,
)

/** The complete, checked set of files one load may touch. */
class ManifestBinding private constructor(
    val manifestId: String,
    val manifestVersion: Int,
    val files: List<BoundFile>,
) {
    val main: BoundFile get() = files.first { it.role == ModelFileRole.MAIN }

    /** Companions only, in manifest order. */
    val companions: List<BoundFile> get() = files.filter { it.role != ModelFileRole.MAIN }

    fun byRole(role: ModelFileRole): BoundFile? = files.firstOrNull { it.role == role }

    companion object {
        /**
         * Binds [manifest] to the files [stored] actually holds.
         *
         * Pure checking: this opens nothing and hashes nothing. It establishes
         * *which* files the load may touch and *what* each must hash to;
         * `ModelVerifier` then proves the bytes.
         */
        @Suppress("ReturnCount")
        fun bind(
            manifest: ModelManifest,
            stored: StoredModel,
        ): BindResult {
            if (manifest.id != stored.id) {
                return BindResult.Refused(ModelVerification.MalformedManifest("manifest id does not match the store"))
            }
            if (manifest.manifestVersion != ModelManifest.SUPPORTED_VERSION) {
                return BindResult.Refused(
                    ModelVerification.MalformedManifest("manifest_version ${manifest.manifestVersion} is not bindable"),
                )
            }

            val bound = mutableListOf<BoundFile>()
            for (entry in manifest.files) {
                val file = stored.files[entry.role]
                if (file == null) {
                    if (entry.required) {
                        return BindResult.Refused(
                            ModelVerification.CompanionMissing(entry.role, entry.file),
                        )
                    }
                    continue
                }
                if (file.path.name != entry.file) {
                    return BindResult.Refused(ModelVerification.CompanionMissing(entry.role, entry.file))
                }
                bound +=
                    BoundFile(
                        role = entry.role,
                        path = file.path,
                        expectedSha256 = entry.sha256,
                        expectedSizeBytes = entry.sizeBytes,
                        // A manifest-declared BLAKE3 wins; import already
                        // proved the two agree about the same bytes.
                        expectedBlake3 = entry.blake3 ?: file.blake3,
                    )
            }
            if (bound.none { it.role == ModelFileRole.MAIN }) {
                return BindResult.Refused(ModelVerification.CompanionMissing(ModelFileRole.MAIN, "main"))
            }

            uncoveredFile(stored, bound)?.let { return BindResult.Refused(it) }

            return BindResult.Bound(ManifestBinding(manifest.id, manifest.manifestVersion, bound.toList()))
        }

        /** disk -> manifest: any file in the store directory that no bound entry covers. */
        private fun uncoveredFile(
            stored: StoredModel,
            bound: List<BoundFile>,
        ): ModelVerification.Refusal? {
            val covered = bound.mapTo(mutableSetOf()) { it.path.name }
            val present = stored.directory.listFiles() ?: return null
            val stray = present.firstOrNull { it.name !in covered }
            return stray?.let { ModelVerification.UncoveredFile(it.name) }
        }
    }
}

/** Result of [ManifestBinding.bind]. */
sealed interface BindResult {
    data class Bound(
        val binding: ManifestBinding,
    ) : BindResult

    data class Refused(
        val refusal: ModelVerification.Refusal,
    ) : BindResult
}
