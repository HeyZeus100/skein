// skein-st1r (POST_REVIEW_RESOLUTIONS §2.3): `ModelManifest` v2 — the
// manifest shape that makes "every file the loader opens is hash-covered" a
// schema property rather than a convention.
//
// Shape is the plan's `app/src/main/assets/models/*.skein.json` document, not
// `tools/m0-benchmark/models.yaml` (that file is the benchmark harness's own
// pinning list, parsed by its own restricted-YAML reader, and is out of scope
// here). The fields carried over from the benchmark manifest are the same
// ones: `id`, `file`, `sha256`, `license`, plus the `companions` array §2.3
// adds and the `blake3` field discussed below.
//
// `blake3` — recorded, and why. §2.2 requires the post-mmap pass to use a
// distinct algorithm, which means the expected BLAKE3-256 of the main file
// has to exist somewhere before the mapping is made. §2.3 resolves this two
// ways and this parser supports both:
//   * a shipped default-model manifest declares `blake3` alongside `sha256`,
//     so the post-mmap expectation is known without ever loading the file;
//   * a user-imported model has no such declaration, so `ImmutableModelStore`
//     computes BLAKE3 during the same streaming pass that checks SHA-256 and
//     records it on the resulting `StoredFile` (the in-memory equivalent of
//     the `models.post_mmap_blake3` column migration 004 adds).
// When a manifest *does* declare `blake3`, import verifies it too, so a
// manifest whose two digests disagree about the same bytes is refused rather
// than silently preferring one.
//
// The parser does not read, fetch or trust an attestation. Origin trust is a
// separate gate — see `ManifestAttestation`.

package app.skein.core.inference.models

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Role of one file inside a model manifest. `main` is the mmap'd model itself; the rest are companions. */
enum class ModelFileRole(
    val wire: String,
) {
    MAIN("main"),
    MMPROJ("mmproj"),
    TOKENIZER("tokenizer"),
    TOKENIZER_CONFIG("tokenizer_config"),
    CONFIG("config"),
    GENERATION_CONFIG("generation_config"),
    LICENSE("license"),
    SPECIAL_TOKENS_MAP("special_tokens_map"),
    ;

    companion object {
        fun fromWire(wire: String): ModelFileRole? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * One manifest-covered file.
 *
 * [sha256] is mandatory for every entry — that is the §2 rule. [blake3] is
 * the post-mmap expectation and is optional in the document; see this file's
 * header for when it is present and what happens when it is not.
 */
data class ManifestFile(
    val role: ModelFileRole,
    val file: String,
    val sha256: String,
    val sizeBytes: Long,
    val required: Boolean = true,
    val blake3: String? = null,
)

/**
 * A parsed, schema-valid model manifest.
 *
 * Schema validity here means exactly §2.3's rule set: manifest_version 2, one
 * `main` entry, every companion carrying a known role and a well-formed
 * sha256, and no duplicate roles or file names. It says nothing about whether
 * the bytes on disk match, and nothing about whether the manifest came from
 * anyone trustworthy.
 */
data class ModelManifest(
    val id: String,
    val manifestVersion: Int,
    val license: String,
    val main: ManifestFile,
    val companions: List<ManifestFile>,
    val attestationUrl: String? = null,
) {
    /** Main file first, then companions in declaration order. */
    val files: List<ManifestFile> get() =
        buildList(companions.size + 1) {
            add(main)
            addAll(companions)
        }

    fun byRole(role: ModelFileRole): ManifestFile? = files.firstOrNull { it.role == role }

    fun byFileName(name: String): ManifestFile? = files.firstOrNull { it.file == name }

    companion object {
        /** The only manifest version this loader will bind. §2.5 retires v1 for anything with companions. */
        const val SUPPORTED_VERSION: Int = 2

        private const val HEX_DIGEST_LENGTH = 64

        /**
         * Parses a `*.skein.json` manifest document.
         *
         * Returns [ManifestParse.Parsed] or a typed
         * [ModelVerification.Refusal] — no exception escapes, because a
         * malformed manifest is a refusal like any other, not a crash.
         */
        fun parse(document: String): ManifestParse =
            try {
                parseOrThrow(document)
            } catch (e: IllegalArgumentException) {
                // kotlinx.serialization signals every malformed-JSON and
                // wrong-JsonElement-type condition as IllegalArgumentException
                // (SerializationException extends it), as do the `require`
                // calls in parseOrThrow.
                ManifestParse.Refused(ModelVerification.MalformedManifest(e.message ?: "unparseable"))
            }

        private fun parseOrThrow(document: String): ManifestParse {
            val root = SkeinJson.decode(document).asObject("manifest")
            val version = root.requireInt("manifest_version")
            if (version != SUPPORTED_VERSION) {
                return ManifestParse.Refused(
                    ModelVerification.MalformedManifest("manifest_version $version is not supported"),
                )
            }
            val id = root.requireString("id")
            val license = root.requireString("license")

            val main =
                fileEntry(root, ModelFileRole.MAIN)
                    ?: return ManifestParse.Refused(
                        ModelVerification.CompanionMissing(ModelFileRole.MAIN, "main"),
                    )

            val companions = mutableListOf<ManifestFile>()
            for (element in root["companions"]?.jsonArray.orEmpty()) {
                val entry = element.asObject("companion")
                val roleWire = entry.requireString("role")
                val role =
                    ModelFileRole.fromWire(roleWire)
                        ?: return ManifestParse.Refused(
                            ModelVerification.MalformedManifest("unknown companion role '$roleWire'"),
                        )
                if (role == ModelFileRole.MAIN) {
                    return ManifestParse.Refused(
                        ModelVerification.MalformedManifest("'main' may not appear in companions"),
                    )
                }
                val file = entry.requireString("file")
                val sha256 = entry.optionalString("sha256")
                if (sha256 == null || !isHexDigest(sha256)) {
                    // §2: a companion without a usable sha256 is the exact
                    // "trust the adjacent file" hole. Refuse at parse time.
                    return ManifestParse.Refused(ModelVerification.CompanionMissing(role, file))
                }
                val blake3 = entry.optionalString("blake3")
                if (blake3 != null && !isHexDigest(blake3)) {
                    return ManifestParse.Refused(
                        ModelVerification.MalformedManifest("companion blake3 is not 64 hex characters"),
                    )
                }
                companions +=
                    ManifestFile(
                        role = role,
                        file = file,
                        sha256 = sha256.lowercase(),
                        sizeBytes = entry.requireLong("size_bytes"),
                        required = entry["required"]?.jsonPrimitive?.booleanOrNull ?: true,
                        blake3 = blake3?.lowercase(),
                    )
            }

            duplicateComplaint(main, companions)?.let { return ManifestParse.Refused(it) }

            return ManifestParse.Parsed(
                ModelManifest(
                    id = id,
                    manifestVersion = version,
                    license = license,
                    main = main,
                    companions = companions,
                    attestationUrl = root.optionalString("attestation_url"),
                ),
            )
        }

        private fun fileEntry(
            root: JsonObject,
            role: ModelFileRole,
        ): ManifestFile? {
            val file = root.optionalString("file") ?: return null
            val sha256 = root.optionalString("sha256")?.lowercase() ?: return null
            if (!isHexDigest(sha256)) return null
            val blake3 = root.optionalString("blake3")?.lowercase()
            require(blake3 == null || isHexDigest(blake3)) { "blake3 is not 64 hex characters" }
            return ManifestFile(
                role = role,
                file = file,
                sha256 = sha256,
                sizeBytes = root.requireLong("size_bytes"),
                required = true,
                blake3 = blake3,
            )
        }

        private fun duplicateComplaint(
            main: ManifestFile,
            companions: List<ManifestFile>,
        ): ModelVerification.Refusal? {
            val all =
                buildList {
                    add(main)
                    addAll(companions)
                }
            val seenRoles = mutableSetOf<ModelFileRole>()
            val seenFiles = mutableSetOf<String>()
            for (entry in all) {
                if (!seenRoles.add(entry.role)) {
                    return ModelVerification.MalformedManifest("duplicate role '${entry.role.wire}'")
                }
                if (!seenFiles.add(entry.file)) {
                    return ModelVerification.MalformedManifest("duplicate file name")
                }
                if (!isSafeFileName(entry.file)) {
                    return ModelVerification.MalformedManifest("file name must be a plain name, not a path")
                }
            }
            return null
        }

        private fun isHexDigest(value: String): Boolean =
            value.length == HEX_DIGEST_LENGTH && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

        /**
         * The store writes each manifest entry into `models/<id>/<file>`, so a
         * name carrying a separator or `..` would escape the immutable store.
         */
        private fun isSafeFileName(name: String): Boolean =
            name.isNotEmpty() &&
                name != "." &&
                name != ".." &&
                !name.contains('/') &&
                !name.contains('\\') &&
                !name.contains('\u0000')
    }
}

/** Result of [ModelManifest.parse]. */
sealed interface ManifestParse {
    data class Parsed(
        val manifest: ModelManifest,
    ) : ManifestParse

    data class Refused(
        val refusal: ModelVerification.Refusal,
    ) : ManifestParse
}

// ------------------------------------------------------------------ JSON glue

/**
 * `kotlinx.serialization`'s tree API, used without the compiler plugin: the
 * manifest is small and hand-shaped, and `parseToJsonElement` gives strict
 * JSON parsing without adding `@Serializable` codegen to this module.
 */
private object SkeinJson {
    private val json = kotlinx.serialization.json.Json

    fun decode(document: String): JsonElement = json.parseToJsonElement(document)
}

private fun JsonElement.asObject(what: String): JsonObject =
    this as? JsonObject ?: throw IllegalArgumentException("$what is not a JSON object")

private fun JsonObject.optionalString(key: String): String? =
    (this[key] as? JsonPrimitive)
        ?.takeIf {
            it.isString
        }?.content

private fun JsonObject.requireString(key: String): String =
    optionalString(key) ?: throw IllegalArgumentException("missing or non-string '$key'")

private fun JsonObject.requireLong(key: String): Long =
    (this[key] as? JsonPrimitive)?.longOrNull ?: throw IllegalArgumentException("missing or non-integer '$key'")

private fun JsonObject.requireInt(key: String): Int = requireLong(key).toInt()

private fun kotlinx.serialization.json.JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
